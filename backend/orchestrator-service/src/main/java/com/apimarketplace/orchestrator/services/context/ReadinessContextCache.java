package com.apimarketplace.orchestrator.services.context;

import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.services.cache.RunScopedCache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Caffeine-backed cache for {@link ExecutionContext} instances used by the
 * readiness-calculation hot loop.
 *
 * <p><b>Scope: readiness only.</b> This cache is read EXCLUSIVELY by
 * {@code V2StepByStepService.getReadyNodes()} (which traverses N triggers and
 * builds N identical contexts per call). The execution path
 * ({@code V2StepByStepService.executeNode}, {@code UnifiedExecutionEngine.executeNode})
 * deliberately bypasses this cache so re-execution of the same node always sees
 * fresh DB state - preserving idempotency.
 *
 * <p><b>Cache key:</b> {@code runId | epoch | spawn | itemIndex | triggerId}.
 * Different epochs / spawns / items / triggers always get distinct entries,
 * so per-DAG isolation is preserved.
 *
 * <p><b>TTL: 500ms absolute</b> - a readiness traversal completes well under 500ms
 * even on large DAGs, so within a single traversal the cache absorbs all the
 * redundant loads. Cross-traversal hits are unlikely to matter, but the bound
 * prevents long-term staleness if a caller forgets to invalidate.
 *
 * <p><b>Max size: 200</b> - bounded against memory pressure. 200 = ~10 concurrent
 * runs × ~20 entries each.
 *
 * <p><b>Invalidation contract:</b>
 * <ul>
 *   <li>{@code cleanupRun(runId)} - called by {@link com.apimarketplace.orchestrator.services.cache.RunCacheRegistry}
 *       when a run terminates.</li>
 *   <li>{@code invalidateRun(runId)} - call this from
 *       {@code NodeCompletionService} after a node completes so the NEXT
 *       {@code getReadyNodes} for that run sees the fresh data.</li>
 * </ul>
 */
@Component
public class ReadinessContextCache implements RunScopedCache {

    private static final Logger logger = LoggerFactory.getLogger(ReadinessContextCache.class);

    static final Duration TTL = Duration.ofMillis(500);
    static final long MAX_SIZE = 200L;

    private final Cache<String, ExecutionContext> cache;

    public ReadinessContextCache() {
        this.cache = Caffeine.newBuilder()
            .expireAfterWrite(TTL)
            .maximumSize(MAX_SIZE)
            .recordStats()
            .build();
        logger.info("[ReadinessContextCache] Initialized - TTL={}ms maxSize={}", TTL.toMillis(), MAX_SIZE);
    }

    /**
     * Returns the cached context for the given key, or computes it via {@code loader} on miss.
     *
     * <p>{@code loader} is a {@link Supplier} (not a {@link java.util.function.Function}) because
     * the key-building logic lives in the caller - we don't want to leak per-overload coordinate
     * resolution into the cache.
     */
    public ExecutionContext getOrLoad(String key, Supplier<ExecutionContext> loader) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");
        return cache.get(key, k -> loader.get());
    }

    /**
     * Build a cache key from the readiness-traversal coordinates.
     *
     * <p>Spawn is included so {@code rerun} (spawn=1+) computations don't poison
     * the cache for the canonical (spawn=0) traversal.
     */
    public static String key(String runId, int epoch, int spawn, int itemIndex, String triggerId) {
        return runId + "|" + epoch + "|" + spawn + "|" + itemIndex + "|" + (triggerId == null ? "_" : triggerId);
    }

    /**
     * Invalidate every entry for a run. Call this after any node completion that
     * changes what the readiness calculator would see for this run.
     *
     * <p><b>Race contract:</b> Caffeine's {@code asMap().keySet()} is weakly consistent.
     * If a loader is mid-flight when this method is called, the loaded entry MAY land
     * in the cache after the {@code removeIf} pass and be visible to the next
     * {@code getOrLoad} for up to TTL ({@value #TTL_MILLIS}ms). This is acceptable
     * because:
     * <ul>
     *   <li>Readiness is recomputed every traversal - a one-cycle stale read self-heals
     *       on the next call which will see the post-completion DB state.</li>
     *   <li>The cache deliberately bypasses the execution path (executeNode), so a
     *       stale readiness view cannot cause a node to execute against stale data.</li>
     *   <li>TTL bounds the staleness window without needing distributed locking.</li>
     * </ul>
     */
    public void invalidateRun(String runId) {
        if (runId == null) return;
        String prefix = runId + "|";
        cache.asMap().keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** Milliseconds equivalent of {@link #TTL} - used in the race-contract javadoc. */
    private static final long TTL_MILLIS = TTL.toMillis();

    @Override
    public void cleanupRun(String runId) {
        invalidateRun(runId);
    }

    @Override
    public String getCacheName() {
        return "ReadinessContextCache";
    }

    @Override
    public CacheDomain getDomain() {
        return CacheDomain.STATE;
    }

    @Override
    public int getCacheSize() {
        return (int) cache.estimatedSize();
    }

    // Test-only accessors ------------------------------------------------------

    long estimatedSize() {
        return cache.estimatedSize();
    }

    boolean containsKey(String key) {
        return cache.getIfPresent(key) != null;
    }
}
