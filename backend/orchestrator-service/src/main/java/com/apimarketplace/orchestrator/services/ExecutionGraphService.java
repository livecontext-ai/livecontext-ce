package com.apimarketplace.orchestrator.services;

import com.apimarketplace.orchestrator.cache.ExecutionGraphCache;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionGraph;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * Service for accessing execution graphs with Redis caching.
 * Use this service instead of calling plan.getExecutionGraph() directly
 * to benefit from distributed caching.
 */
@Service
public class ExecutionGraphService {

    private static final Logger logger = LoggerFactory.getLogger(ExecutionGraphService.class);

    @Nullable
    private final ExecutionGraphCache cache;

    public ExecutionGraphService(@Nullable ExecutionGraphCache cache) {
        this.cache = cache;
        if (cache == null) {
            logger.info("ExecutionGraphService initialized without Redis cache (direct computation)");
        }
    }

    /**
     * Gets the execution graph for a workflow plan, using Redis cache.
     *
     * @param plan The workflow plan
     * @return The execution graph (from cache or freshly computed)
     */
    public ExecutionGraph getExecutionGraph(WorkflowPlan plan) {
        if (plan == null) {
            logger.warn("Cannot get execution graph for null plan");
            return null;
        }

        ExecutionGraph graph = cache != null
                ? cache.getOrCompute(plan)
                : ExecutionGraph.build(plan);

        // Also set it on the plan for local access (avoids repeated cache lookups)
        plan.setExecutionGraph(graph);

        return graph;
    }

    /**
     * Invalidates the cached execution graph for a plan.
     * Call this when a workflow definition is modified.
     *
     * @param plan The workflow plan to invalidate
     */
    public void invalidateCache(WorkflowPlan plan) {
        if (plan != null && cache != null) {
            cache.invalidate(plan);
        }
    }
}
