package com.apimarketplace.orchestrator.services.cache;

/**
 * Interface for all run-scoped caches in the workflow system.
 *
 * <p>SOLID Principles applied:
 * <ul>
 *   <li><b>Single Responsibility</b>: Each cache handles one domain</li>
 *   <li><b>Open/Closed</b>: New caches implement this interface without modifying registry</li>
 *   <li><b>Liskov Substitution</b>: All caches are interchangeable for cleanup</li>
 *   <li><b>Interface Segregation</b>: Minimal interface - only cleanup lifecycle</li>
 *   <li><b>Dependency Inversion</b>: Registry depends on abstraction, not concrete caches</li>
 * </ul>
 *
 * <p>Usage: Implement this interface and annotate with @Component.
 * The {@link RunCacheRegistry} will auto-discover and manage cleanup.
 *
 * <p>Example:
 * <pre>
 * {@code
 * @Component
 * public class MyDomainCache implements RunScopedCache {
 *     private final Map<String, MyState> cache = new ConcurrentHashMap<>();
 *
 *     @Override
 *     public void cleanupRun(String runId) {
 *         cache.remove(runId);
 *     }
 *
 *     @Override
 *     public String getCacheName() {
 *         return "MyDomainCache";
 *     }
 *
 *     @Override
 *     public CacheDomain getDomain() {
 *         return CacheDomain.EXECUTION;
 *     }
 * }
 * }
 * </pre>
 *
 * @see RunCacheRegistry
 */
public interface RunScopedCache {

    /**
     * Cleans up all cached data for a specific workflow run.
     *
     * <p>Called by {@link RunCacheRegistry} when a run completes or is closed.
     * Implementations should remove all entries keyed by the given runId.
     *
     * <p>This method must be:
     * <ul>
     *   <li>Thread-safe</li>
     *   <li>Idempotent (safe to call multiple times)</li>
     *   <li>Non-blocking (avoid long operations)</li>
     * </ul>
     *
     * @param runId The workflow run ID to clean up
     */
    void cleanupRun(String runId);

    /**
     * Returns the human-readable name of this cache.
     * Used for logging and monitoring.
     *
     * @return The cache name (e.g., "ExecutionCache", "SplitContextCache")
     */
    String getCacheName();

    /**
     * Returns the domain this cache belongs to.
     * Used for grouping and documentation.
     *
     * @return The cache domain
     */
    CacheDomain getDomain();

    /**
     * Returns the current number of entries in the cache.
     * Used for monitoring and debugging.
     *
     * @return The number of cached entries, or -1 if not trackable
     */
    default int getCacheSize() {
        return -1;
    }

    /**
     * Cache domains for categorization.
     */
    enum CacheDomain {
        /**
         * Execution state: trees, executions, plans.
         */
        EXECUTION,

        /**
         * Workflow state: node statuses, ready nodes.
         */
        STATE,

        /**
         * Control flow: split, merge, loop, decision.
         */
        CONTROL_FLOW,

        /**
         * Streaming: events, real-time updates.
         */
        STREAMING,

        /**
         * Persistence: deduplication, entity resolution.
         */
        PERSISTENCE,

        /**
         * Redis-backed caches.
         */
        REDIS
    }
}
