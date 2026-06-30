package com.apimarketplace.orchestrator.services.resume.cache;

import com.apimarketplace.orchestrator.config.RedisCacheConfig;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.services.cache.RunScopedCache;
import com.apimarketplace.orchestrator.services.state.WorkflowStateManager;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages caches for workflow resume functionality.
 * Uses Caffeine for paused workflows and evaluated cores (local to orchestrator process).
 * State managers are always kept in local memory (active execution contexts).
 */
@Component
public class WorkflowCacheManager implements RunScopedCache {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowCacheManager.class);

    private static final boolean CACHE_ENABLED = true;

    private final Cache<String, PausedWorkflowState> pausedWorkflows;
    private final Cache<String, Set<String>> evaluatedCores;

    // State managers are kept in local memory as they are active execution contexts
    private final Map<String, WorkflowStateManager> stateManagers = new ConcurrentHashMap<>();

    public WorkflowCacheManager(RedisCacheConfig cacheConfig) {
        this.pausedWorkflows = Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(cacheConfig.getWorkflowStateTtl())
                .build();

        this.evaluatedCores = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(cacheConfig.getWorkflowStateTtl())
                .build();

        logger.info("WorkflowCacheManager initialized with Caffeine, stateTTL={}",
                   cacheConfig.getWorkflowStateTtl());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API - Paused Workflows
    // ═══════════════════════════════════════════════════════════════════════════

    public void storePausedWorkflow(String runId, PausedWorkflowState state) {
        if (!CACHE_ENABLED) return;
        pausedWorkflows.put(runId, state);
        logger.debug("Stored paused workflow: runId={}", runId);
    }

    public PausedWorkflowState getPausedWorkflow(String runId) {
        if (!CACHE_ENABLED) return null;
        return pausedWorkflows.getIfPresent(runId);
    }

    public void removePausedWorkflow(String runId) {
        pausedWorkflows.invalidate(runId);
        logger.debug("Removed paused workflow: runId={}", runId);
    }

    public boolean isPaused(String runId) {
        if (!CACHE_ENABLED) return false;
        return pausedWorkflows.getIfPresent(runId) != null;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API - Evaluated Core Nodes
    // ═══════════════════════════════════════════════════════════════════════════

    public void markCoreEvaluated(String runId, String coreId) {
        if (!CACHE_ENABLED) return;
        evaluatedCores.asMap()
                .computeIfAbsent(runId, k -> ConcurrentHashMap.newKeySet())
                .add(coreId);
        logger.debug("Marked core as evaluated: runId={}, coreId={}", runId, coreId);
    }

    public boolean isCoreEvaluated(String runId, String coreId) {
        if (!CACHE_ENABLED) return false;
        Set<String> cores = evaluatedCores.getIfPresent(runId);
        return cores != null && cores.contains(coreId);
    }

    public void clearEvaluatedCores(String runId) {
        evaluatedCores.invalidate(runId);
        logger.debug("Cleared evaluated core nodes: runId={}", runId);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API - StateManager Cache (Local Memory)
    // ═══════════════════════════════════════════════════════════════════════════

    public void storeStateManager(String runId, WorkflowStateManager stateManager) {
        if (!CACHE_ENABLED) return;
        stateManagers.put(runId, stateManager);
        logger.debug("Stored state manager (local): runId={}", runId);
    }

    public WorkflowStateManager getStateManager(String runId) {
        if (!CACHE_ENABLED) return null;
        return stateManagers.get(runId);
    }

    public void removeStateManager(String runId) {
        stateManagers.remove(runId);
        logger.debug("Removed state manager (local): runId={}", runId);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API - Bulk Clear
    // ═══════════════════════════════════════════════════════════════════════════

    public void clearAllCaches(String runId) {
        logger.info("Clearing all caches for runId: {}", runId);
        removePausedWorkflow(runId);
        clearEvaluatedCores(runId);
        removeStateManager(runId);
    }

    public int getLocalStateManagerCount() {
        return stateManagers.size();
    }

    public record PausedWorkflowState(
        String runId,
        Instant pausedAt,
        WorkflowPlan plan
    ) {}

    // ═══════════════════════════════════════════════════════════════════════════
    // RunScopedCache Implementation
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public void cleanupRun(String runId) {
        clearAllCaches(runId);
    }

    @Override
    public String getCacheName() {
        return "WorkflowCacheManager";
    }

    @Override
    public CacheDomain getDomain() {
        return CacheDomain.STATE;
    }

    @Override
    public int getCacheSize() {
        return stateManagers.size();
    }
}
