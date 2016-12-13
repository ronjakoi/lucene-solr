/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.metrics;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.MetricSet;
import com.codahale.metrics.SharedMetricRegistries;
import com.codahale.metrics.Timer;
import com.google.common.base.Preconditions;
import org.apache.solr.core.PluginInfo;
import org.apache.solr.core.SolrInfoMBean;
import org.apache.solr.core.SolrResourceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class maintains a repository of named {@link MetricRegistry} instances, and provides several
 * helper methods for managing various aspects of metrics reporting:
 * <ul>
 *   <li>registry creation, clearing and removal,</li>
 *   <li>creation of most common metric implementations,</li>
 *   <li>management of {@link SolrMetricReporter}-s specific to a named registry.</li>
 * </ul>
 * {@link MetricRegistry} instances are automatically created when first referenced by name. Similarly,
 * instances of {@link Metric} implementations, such as {@link Meter}, {@link Counter}, {@link Timer} and
 * {@link Histogram} are automatically created and registered under hierarchical names, in a specified
 * registry, when {@link #meter(String, String, String...)} and other similar methods are called.
 * <p>This class enforces a common prefix ({@link #REGISTRY_NAME_PREFIX}) in all registry
 * names.</p>
 * <p>Solr uses several different registries for collecting metrics belonging to different groups, using
 * {@link org.apache.solr.core.SolrInfoMBean.Group} as the main name of the registry (plus the
 * above-mentioned prefix).</p>
 */
public class SolrMetricManager {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public static final String REGISTRY_NAME_PREFIX = "solr.";

  private static final Map<String, Map<String, SolrMetricReporter>> reporters = new HashMap<>();

  private static final Lock reportersLock = new ReentrantLock();

  // don't create instances of this class
  private SolrMetricManager() { }

  /**
   * An implementation of {@link MetricFilter} that selects metrics
   * with names that start with a prefix.
   */
  public static class PrefixFilter implements MetricFilter {
    private final String prefix;
    private final Set<String> matched = new HashSet<>();

    /**
     * Create a filter that uses the provided prefix.
     * @param prefix prefix to use, must not be null. If empty then any
     *               name will match.
     */
    public PrefixFilter(String prefix) {
      Preconditions.checkNotNull(prefix);
      this.prefix = prefix;
    }

    @Override
    public boolean matches(String name, Metric metric) {
      if (prefix.isEmpty()) {
        matched.add(name);
        return true;
      }
      if (name.startsWith(prefix)) {
        matched.add(name);
        return true;
      } else {
        return false;
      }
    }

    /**
     * Return the set of names that matched this filter.
     * @return matching names
     */
    public Set<String> getMatched() {
      return Collections.unmodifiableSet(matched);
    }

    /**
     * Clear the set of names that matched.
     */
    public void reset() {
      matched.clear();
    }
  }

  /**
   * Return a set of existing registry names.
   */
  public static Set<String> registryNames() {
    return SharedMetricRegistries.names();
  }

  /**
   * Get (or create if not present) a named registry
   * @param registry name of the registry
   * @return existing or newly created registry
   */
  public static MetricRegistry registry(String registry) {
    return SharedMetricRegistries.getOrCreate(overridableRegistryName(registry));
  }

  /**
   * Remove a named registry.
   * @param registry name of the registry to remove
   */
  public static void removeRegistry(String registry) {
    // close any reporters for this registry first
    closeReporters(registry);
    // make sure we use a name with prefix, with overrides
    registry = overridableRegistryName(registry);
    SharedMetricRegistries.remove(registry);
  }

  /**
   * Move all matching metrics from one registry to another. This is useful eg. during
   * {@link org.apache.solr.core.SolrCore} rename or swap operations.
   * @param fromRegistry source registry
   * @param toRegistry target registry
   * @param filter optional {@link MetricFilter} to select what metrics to move. If null
   *               then all metrics will be moved.
   */
  public static void moveMetrics(String fromRegistry, String toRegistry, MetricFilter filter) {
    MetricRegistry from = registry(fromRegistry);
    MetricRegistry to = registry(toRegistry);
    if (from == to) {
      return;
    }
    if (filter == null) {
      to.registerAll(from);
      from.removeMatching(MetricFilter.ALL);
    } else {
      for (Map.Entry<String, Metric> entry : from.getMetrics().entrySet()) {
        if (filter.matches(entry.getKey(), entry.getValue())) {
          to.register(entry.getKey(), entry.getValue());
        }
      }
      from.removeMatching(filter);
    }
  }

  /**
   * Register all metrics in the provided {@link MetricSet}, optionally skipping those that
   * already exist.
   * @param registry registry name
   * @param metrics metric set to register
   * @param skipExisting if true then already existing metrics with the same name will be kept.
   *                     When false and a metric with the same name already exists an exception
   *                     will be thrown.
   * @throws Exception if a metric with this name already exists.
   */
  public static void registerAll(String registry, MetricSet metrics, boolean skipExisting) throws Exception {
    MetricRegistry metricRegistry = registry(registry);
    Map<String, Metric> existingMetrics = metricRegistry.getMetrics();
    for (Map.Entry<String, Metric> entry : metrics.getMetrics().entrySet()) {
      if (skipExisting && existingMetrics.containsKey(entry.getKey())) {
        continue;
      }
      metricRegistry.register(entry.getKey(), entry.getValue());
    }
  }

  /**
   * Remove all metrics from a specified registry.
   * @param registry registry name
   */
  public static void clearRegistry(String registry) {
    registry(registry).removeMatching(MetricFilter.ALL);
  }

  /**
   * Remove some metrics from a named registry
   * @param registry registry name
   * @param metricPath (optional) top-most metric name path elements. If empty then
   *        this is equivalent to calling {@link #clearRegistry(String)},
   *        otherwise non-empty elements will be joined using dotted notation
   *        to form a fully-qualified prefix. Metrics with names that start
   *        with the prefix will be removed.
   * @return set of metrics names that have been removed.
   */
  public static Set<String> clearMetrics(String registry, String... metricPath) {
    PrefixFilter filter;
    if (metricPath == null || metricPath.length == 0) {
      filter = new PrefixFilter("");
    } else {
      String prefix = MetricRegistry.name("", metricPath);
      filter = new PrefixFilter(prefix);
    }
    registry(registry).removeMatching(filter);
    return filter.getMatched();
  }

  /**
   * Create or get an existing named {@link Meter}
   * @param registry registry name
   * @param metricName metric name, either final name or a fully-qualified name
   *                   using dotted notation
   * @param metricPath (optional) additional top-most metric name path elements
   * @return existing or a newly created {@link Meter}
   */
  public static Meter meter(String registry, String metricName, String... metricPath) {
    return registry(registry).meter(mkName(metricName, metricPath));
  }

  /**
   * Create or get an existing named {@link Timer}
   * @param registry registry name
   * @param metricName metric name, either final name or a fully-qualified name
   *                   using dotted notation
   * @param metricPath (optional) additional top-most metric name path elements
   * @return existing or a newly created {@link Timer}
   */
  public static Timer timer(String registry, String metricName, String... metricPath) {
    return registry(registry).timer(mkName(metricName, metricPath));
  }

  /**
   * Create or get an existing named {@link Counter}
   * @param registry registry name
   * @param metricName metric name, either final name or a fully-qualified name
   *                   using dotted notation
   * @param metricPath (optional) additional top-most metric name path elements
   * @return existing or a newly created {@link Counter}
   */
  public static Counter counter(String registry, String metricName, String... metricPath) {
    return registry(registry).counter(mkName(metricName, metricPath));
  }

  /**
   * Create or get an existing named {@link Histogram}
   * @param registry registry name
   * @param metricName metric name, either final name or a fully-qualified name
   *                   using dotted notation
   * @param metricPath (optional) additional top-most metric name path elements
   * @return existing or a newly created {@link Histogram}
   */
  public static Histogram histogram(String registry, String metricName, String... metricPath) {
    return registry(registry).histogram(mkName(metricName, metricPath));
  }

  /**
   * Register an instance of {@link Metric}.
   * @param registry registry name
   * @param metric metric instance
   * @param skipExisting if true then already an existing metric with the same name will be kept.
   *                     When false and a metric with the same name already exists an exception
   *                     will be thrown.
   * @param metricName metric name, either final name or a fully-qualified name
   *                   using dotted notation
   * @param metricPath (optional) additional top-most metric name path elements
   */
  public static void register(String registry, Metric metric, boolean skipExisting, String metricName, String... metricPath) {
    MetricRegistry metricRegistry = registry(registry);
    String fullName = mkName(metricName, metricPath);
    if (skipExisting && metricRegistry.getMetrics().containsKey(fullName)) {
      return;
    }
    metricRegistry.register(fullName, metric);
  }



  /**
   * This method creates a hierarchical name with arbitrary levels of hierarchy
   * @param name the final segment of the name, must not be null or empty.
   * @param path optional path segments, starting from the top level. Empty or null
   *             segments will be skipped.
   * @return fully-qualified name using dotted notation, with all valid hierarchy
   * segments prepended to the name.
   */
  public static String mkName(String name, String... path) {
    if (name == null || name.isEmpty()) {
      throw new IllegalArgumentException("name must not be empty");
    }
    if (path == null || path.length == 0) {
      return name;
    } else {
      StringBuilder sb = new StringBuilder();
      for (String s : path) {
        if (s == null || s.isEmpty()) {
          continue;
        }
        if (sb.length() > 0) {
          sb.append('.');
        }
        sb.append(s);
      }
      if (sb.length() > 0) {
        sb.append('.');
      }
      sb.append(name);
      return sb.toString();
    }
  }

  /**
   * Allows named registries to be renamed using System properties.
   * This would be mostly be useful if you want to combine the metrics from a few registries for a single
   * reporter.
   * <p>For example, in order to collect metrics from related cores in a single registry you could specify
   * the following system properties:</p>
   * <pre>
   *   ... -Dsolr.core.collection1=solr.core.allCollections -Dsolr.core.collection2=solr.core.allCollections
   * </pre>
   * <b>NOTE:</b> Once a registry is renamed in a way that its metrics are combined with another repository
   * it is no longer possible to retrieve the original metrics until this renaming is removed and the Solr
   * {@link org.apache.solr.core.SolrInfoMBean.Group} of components that reported to that name is restarted.
   * @param registry The name of the registry
   * @return A potentially overridden (via System properties) registry name
   */
  public static String overridableRegistryName(String registry) {
    String fqRegistry = enforcePrefix(registry);
    return enforcePrefix(System.getProperty(fqRegistry,fqRegistry));
  }

  /**
   * Enforces the leading {@link #REGISTRY_NAME_PREFIX} in a name.
   * @param name input name, possibly without the prefix
   * @return original name if it contained the prefix, or the
   * input name with the prefix prepended.
   */
  public static String enforcePrefix(String name) {
    if (name.startsWith(REGISTRY_NAME_PREFIX)) {
      return name;
    } else {
      return new StringBuilder(REGISTRY_NAME_PREFIX).append(name).toString();
    }
  }

  /**
   * Helper method to construct a properly prefixed registry name based on the group.
   * @param group reporting group
   * @param names optional child elements of the registry name. If exactly one element is provided
   *              and it already contains the required prefix and group name then this value will be used,
   *              and the group parameter will be ignored.
   * @return fully-qualified and prefixed registry name, with overrides applied.
   */
  public static String getRegistryName(SolrInfoMBean.Group group, String... names) {
    String fullName;
    String prefix = REGISTRY_NAME_PREFIX + group.toString() + ".";
    // check for existing prefix and group
    if (names != null && names.length > 0 && names[0] != null && names[0].startsWith(prefix)) {
      // assume the first segment already was expanded
      if (names.length > 1) {
        String[] newNames = new String[names.length - 1];
        System.arraycopy(names, 1, newNames, 0, newNames.length);
        fullName = MetricRegistry.name(names[0], newNames);
      } else {
        fullName = MetricRegistry.name(names[0]);
      }
    } else {
      fullName = MetricRegistry.name(group.toString(), names);
    }
    return overridableRegistryName(fullName);
  }

  // reporter management

  /**
   * Create and register {@link SolrMetricReporter}-s specific to a {@link org.apache.solr.core.SolrInfoMBean.Group}.
   * Note: reporters that specify neither "group" nor "registry" attributes are treated as universal -
   * they will always be loaded for any group. These two attributes may also contain multiple comma- or
   * whitespace-separated values, in which case the reporter will be loaded for any matching value from
   * the list. If both attributes are present then only "group" attribute will be processed.
   * @param pluginInfos plugin configurations
   * @param loader resource loader
   * @param group selected group, not null
   * @param registryNames optional child registry name elements
   */
  public static void loadReporters(PluginInfo[] pluginInfos, SolrResourceLoader loader, SolrInfoMBean.Group group, String... registryNames) {
    if (pluginInfos == null || pluginInfos.length == 0) {
      return;
    }
    String registryName = getRegistryName(group, registryNames);
    for (PluginInfo info : pluginInfos) {
      String target = info.attributes.get("group");
      if (target == null) { // no "group"
        target = info.attributes.get("registry");
        if (target != null) {
          String[] targets = target.split("[\\s,]+");
          boolean found = false;
          for (String t : targets) {
            t = overridableRegistryName(t);
            if (registryName.equals(t)) {
              found = true;
              break;
            }
          }
          if (!found) {
            continue;
          }
        } else {
          // neither group nor registry specified.
          // always register this plugin for all groups and registries
        }
      } else { // check groups
        String[] targets = target.split("[\\s,]+");
        boolean found = false;
        for (String t : targets) {
          if (group.toString().equals(t)) {
            found = true;
            break;
          }
        }
        if (!found) {
          continue;
        }
      }
      try {
        loadReporter(registryName, loader, info);
      } catch (Exception e) {
        log.warn("Error loading metrics reporter, plugin info: " + info, e);
      }
    }
  }

  /**
   * Create and register an instance of {@link SolrMetricReporter}.
   * @param registry reporter is associated with this registry
   * @param loader loader to use when creating an instance of the reporter
   * @param pluginInfo plugin configuration. Plugin "name" and "class" attributes are required.
   * @throws Exception if any argument is missing or invalid
   */
  public static void loadReporter(String registry, SolrResourceLoader loader, PluginInfo pluginInfo) throws Exception {
    if (registry == null || pluginInfo == null || pluginInfo.name == null || pluginInfo.className == null) {
      throw new IllegalArgumentException("loadReporter called with missing arguments: " +
          "registry=" + registry + ", loader=" + loader + ", pluginInfo=" + pluginInfo);
    }
    // make sure we use a name with prefix, with overrides
    registry = overridableRegistryName(registry);
    SolrMetricReporter reporter = loader.newInstance(
        pluginInfo.className,
        SolrMetricReporter.class,
        new String[0],
        new Class[] { String.class },
        new Object[] { registry }
    );
    try {
      reporter.init(pluginInfo);
    } catch (IllegalStateException e) {
      throw new IllegalArgumentException("reporter init failed: " + pluginInfo, e);
    }
    try {
      if (!reportersLock.tryLock(10, TimeUnit.SECONDS)) {
        throw new Exception("Could not obtain lock to modify reporters registry: " + registry);
      }
    } catch (InterruptedException e) {
      throw new Exception("Interrupted while trying to obtain lock to modify reporters registry: " + registry);
    }
    try {
      Map<String, SolrMetricReporter> perRegistry = reporters.get(registry);
      if (perRegistry == null) {
        perRegistry = new HashMap<>();
        reporters.put(registry, perRegistry);
      }
      SolrMetricReporter oldReporter = perRegistry.get(pluginInfo.name);
      if (oldReporter != null) { // close it
        log.info("Replacing existing reporter '" + pluginInfo.name + "' in registry '" + registry + "': " + oldReporter.toString());
        oldReporter.close();
      }
      perRegistry.put(pluginInfo.name, reporter);

    } finally {
      reportersLock.unlock();
    }
  }

  /**
   * Close and unregister a named {@link SolrMetricReporter} for a registry.
   * @param registry registry name
   * @param name reporter name
   * @return true if a named reporter existed and was closed.
   */
  public static boolean closeReporter(String registry, String name) {
    // make sure we use a name with prefix, with overrides
    registry = overridableRegistryName(registry);
    try {
      if (!reportersLock.tryLock(10, TimeUnit.SECONDS)) {
        log.warn("Could not obtain lock to modify reporters registry: " + registry);
        return false;
      }
    } catch (InterruptedException e) {
      log.warn("Interrupted while trying to obtain lock to modify reporters registry: " + registry);
      return false;
    }
    try {
      Map<String, SolrMetricReporter> perRegistry = reporters.get(registry);
      if (perRegistry == null) {
        return false;
      }
      SolrMetricReporter reporter = perRegistry.remove(name);
      if (reporter == null) {
        return false;
      }
      try {
        reporter.close();
      } catch (Exception e) {
        log.warn("Error closing metric reporter, registry=" + registry + ", name=" + name, e);
      }
      return true;
    } finally {
      reportersLock.unlock();
    }
  }

  /**
   * Close and unregister all {@link SolrMetricReporter}-s for a registry.
   * @param registry registry name
   * @return names of closed reporters
   */
  public static Set<String> closeReporters(String registry) {
    // make sure we use a name with prefix, with overrides
    registry = overridableRegistryName(registry);
    try {
      if (!reportersLock.tryLock(10, TimeUnit.SECONDS)) {
        log.warn("Could not obtain lock to modify reporters registry: " + registry);
        return Collections.emptySet();
      }
    } catch (InterruptedException e) {
      log.warn("Interrupted while trying to obtain lock to modify reporters registry: " + registry);
      return Collections.emptySet();
    }
    log.info("Closing metric reporters for: " + registry);
    try {
      Map<String, SolrMetricReporter> perRegistry = reporters.remove(registry);
      if (perRegistry != null) {
        for (SolrMetricReporter reporter : perRegistry.values()) {
          try {
            reporter.close();
          } catch (IOException ioe) {
            log.warn("Exception closing reporter " + reporter, ioe);
          }
        }
        return perRegistry.keySet();
      } else {
        return Collections.emptySet();
      }
    } finally {
      reportersLock.unlock();
    }
  }

  /**
   * Get a map of reporters for a registry. Keys are reporter names, values are reporter instances.
   * @param registry registry name
   * @return map of reporters and their names, may be empty but never null
   */
  public static Map<String, SolrMetricReporter> getReporters(String registry) {
    // make sure we use a name with prefix, with overrides
    registry = overridableRegistryName(registry);
    try {
      if (!reportersLock.tryLock(10, TimeUnit.SECONDS)) {
        log.warn("Could not obtain lock to modify reporters registry: " + registry);
        return Collections.emptyMap();
      }
    } catch (InterruptedException e) {
      log.warn("Interrupted while trying to obtain lock to modify reporters registry: " + registry);
      return Collections.emptyMap();
    }
    try {
      Map<String, SolrMetricReporter> perRegistry = reporters.get(registry);
      if (perRegistry == null) {
        return Collections.emptyMap();
      } else {
        // defensive copy - the original map may change after we release the lock
        return Collections.unmodifiableMap(new HashMap<>(perRegistry));
      }
    } finally {
      reportersLock.unlock();
    }
  }
}