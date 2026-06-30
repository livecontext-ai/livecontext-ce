package com.apimarketplace.orchestrator.services.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Central registry for all run-scoped caches.
 *
 * <p>This registry auto-discovers all beans implementing {@link RunScopedCache}
 * and provides a single point for cleanup operations.
 *
 * <p>Benefits:
 * <ul>
 *   <li>One call to {@link #cleanupRun(String)} cleans ALL caches</li>
 *   <li>Auto-discovery: new caches are automatically registered</li>
 *   <li>Monitoring: track cache sizes and domains</li>
 *   <li>No circular dependencies: uses interface abstraction</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 * {@code
 * @Autowired
 * private RunCacheRegistry cacheRegistry;
 *
 * // Clean up when workflow completes
 * cacheRegistry.cleanupRun(runId);
 *
 * // Get statistics
 * cacheRegistry.logCacheStatistics();
 * }
 * </pre>
 *
 * @see RunScopedCache
 */
@Component
public class RunCacheRegistry {

    private static final Logger log = LoggerFactory.getLogger(RunCacheRegistry.class);

    private final List<RunScopedCache> caches;

    /**
     * Spring auto-injects all beans implementing RunScopedCache.
     */
    public RunCacheRegistry(List<RunScopedCache> caches) {
        this.caches = caches;
    }

    @PostConstruct
    public void init() {
        log.info("=== RunCacheRegistry initialized with {} caches ===", caches.size());

        // Log caches grouped by domain (filter null domains to avoid NPE)
        Map<RunScopedCache.CacheDomain, List<RunScopedCache>> byDomain = caches.stream()
            .filter(cache -> {
                if (cache.getDomain() == null) {
                    log.warn("RunScopedCache '{}' returned null domain, skipping",
                        cache.getCacheName());
                    return false;
                }
                return true;
            })
            .collect(Collectors.groupingBy(RunScopedCache::getDomain));

        for (var entry : byDomain.entrySet()) {
            log.info("  [{}] domain:", entry.getKey());
            for (var cache : entry.getValue()) {
                log.info("    - {}", cache.getCacheName());
            }
        }
    }

    /**
     * Cleans up all caches for a workflow run.
     *
     * <p>This is the SINGLE method to call when a run completes.
     * It will clean up ALL registered caches in a safe manner.
     *
     * @param runId The workflow run ID to clean up
     * @return The number of caches cleaned
     */
    public int cleanupRun(String runId) {
        return cleanupRun(runId, Set.of());
    }

    /**
     * Cleans up caches for a workflow run, excluding the given domains.
     *
     * <p>Used by SBS refire path ({@code ReusableTriggerService}): the run is still
     * active across fires, so streaming caches ({@link RunScopedCache.CacheDomain#STREAMING})
     * holding the monotonic seq counter and active-run cache must survive between fires
     * - purging them mid-run causes a race where deferred publishes from fire #N collide
     * with seqs of fire #N+1, frontend strict-{@code <} drops the events, UI freezes.
     *
     * @param runId          The workflow run ID to clean up
     * @param excludeDomains Cache domains to skip (must not be null; empty = full purge)
     * @return The number of caches cleaned (excluded ones not counted)
     */
    public int cleanupRun(String runId, Set<RunScopedCache.CacheDomain> excludeDomains) {
        log.info("[CacheRegistry] Cleaning up caches for runId={} (exclude={})", runId, excludeDomains);

        int cleaned = 0;
        int skipped = 0;
        for (RunScopedCache cache : caches) {
            if (excludeDomains.contains(cache.getDomain())) {
                skipped++;
                continue;
            }
            try {
                cache.cleanupRun(runId);
                cleaned++;
                log.debug("[CacheRegistry] Cleaned {}", cache.getCacheName());
            } catch (Exception e) {
                log.warn("[CacheRegistry] Error cleaning {} for runId={}: {}",
                    cache.getCacheName(), runId, e.getMessage());
            }
        }

        log.info("[CacheRegistry] Cleaned {}/{} caches for runId={} (skipped={})",
            cleaned, caches.size(), runId, skipped);
        return cleaned;
    }

    /**
     * Gets all registered caches.
     */
    public List<RunScopedCache> getAllCaches() {
        return List.copyOf(caches);
    }

    /**
     * Gets caches by domain.
     */
    public List<RunScopedCache> getCachesByDomain(RunScopedCache.CacheDomain domain) {
        return caches.stream()
            .filter(c -> c.getDomain() == domain)
            .toList();
    }

    /**
     * Gets the total number of registered caches.
     */
    public int getCacheCount() {
        return caches.size();
    }

    /**
     * Logs current cache statistics.
     * Useful for monitoring and debugging.
     */
    public void logCacheStatistics() {
        log.info("=== Cache Statistics ===");
        for (RunScopedCache cache : caches) {
            int size = cache.getCacheSize();
            if (size >= 0) {
                log.info("  {} [{}]: {} entries",
                    cache.getCacheName(), cache.getDomain(), size);
            } else {
                log.info("  {} [{}]: size not tracked",
                    cache.getCacheName(), cache.getDomain());
            }
        }
    }

    /**
     * Gets total entries across all trackable caches.
     */
    public int getTotalCacheEntries() {
        return caches.stream()
            .mapToInt(RunScopedCache::getCacheSize)
            .filter(size -> size >= 0)
            .sum();
    }
}
