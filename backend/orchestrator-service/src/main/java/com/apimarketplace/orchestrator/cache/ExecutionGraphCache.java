package com.apimarketplace.orchestrator.cache;

import com.apimarketplace.orchestrator.config.RedisCacheConfig;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionGraph;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Caffeine-backed cache for execution graphs.
 * Avoids recalculating graphs for identical plans.
 * Local to the orchestrator process - no cross-process sharing needed.
 */
@Component
public class ExecutionGraphCache {

    private static final Logger logger = LoggerFactory.getLogger(ExecutionGraphCache.class);

    private final Cache<String, ExecutionGraph> cache;

    public ExecutionGraphCache(RedisCacheConfig cacheConfig) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(cacheConfig.getExecutionGraphTtl())
                .build();
        logger.info("ExecutionGraphCache initialized with Caffeine, TTL={}",
                   cacheConfig.getExecutionGraphTtl());
    }

    /**
     * Retrieves or computes the execution graph for a workflow plan.
     */
    public ExecutionGraph getOrCompute(WorkflowPlan plan) {
        String cacheKey = generateCacheKey(plan);
        return cache.get(cacheKey, key -> {
            logger.debug("Cache miss for plan: {}, computing graph", plan.getId());
            return ExecutionGraph.build(plan);
        });
    }

    /**
     * Invalidates the cache for a specific plan.
     */
    public void invalidate(WorkflowPlan plan) {
        String cacheKey = generateCacheKey(plan);
        cache.invalidate(cacheKey);
        logger.debug("Invalidated cache for plan: {}", plan.getId());
    }

    /**
     * Clears all execution graph entries from cache.
     */
    public void clear() {
        long size = cache.estimatedSize();
        cache.invalidateAll();
        logger.info("Cleared ~{} execution graph cache entries", size);
    }

    /**
     * Returns cache statistics.
     */
    public CacheStats getStats() {
        return new CacheStats((int) cache.estimatedSize(), 0, 500);
    }

    private String generateCacheKey(WorkflowPlan plan) {
        int hash = plan.getId().hashCode();
        hash = 31 * hash + (plan.getTriggers() != null ? plan.getTriggers().hashCode() : 0);
        hash = 31 * hash + (plan.getMcps() != null ? plan.getMcps().hashCode() : 0);
        hash = 31 * hash + (plan.getEdges() != null ? plan.getEdges().hashCode() : 0);
        return RedisCacheKeys.executionGraph(plan.getId(), Math.abs(hash));
    }

    public record CacheStats(int totalEntries, int expiredEntries, int maxSize) {
        public int getActiveEntries() {
            return totalEntries;
        }

        public double getUsagePercentage() {
            return maxSize > 0 ? (double) totalEntries / maxSize * 100 : 0;
        }
    }
}
