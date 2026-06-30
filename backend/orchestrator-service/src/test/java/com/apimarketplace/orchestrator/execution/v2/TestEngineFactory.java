package com.apimarketplace.orchestrator.execution.v2;

import com.apimarketplace.orchestrator.execution.v2.engine.BackEdgeHandler;
import com.apimarketplace.orchestrator.execution.v2.engine.UnifiedExecutionEngine;
import com.apimarketplace.orchestrator.execution.v2.scheduler.V2AutoScheduler;
import com.apimarketplace.orchestrator.execution.v2.services.NodeSearchService;
import com.apimarketplace.orchestrator.execution.v2.services.ReadyNodeCalculator;
import com.apimarketplace.orchestrator.execution.v2.split.SplitAggregateHandler;
import com.apimarketplace.orchestrator.execution.v2.split.SplitAwareNodeExecutor;
import com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager;
import com.apimarketplace.orchestrator.execution.v2.split.SplitMergeHandler;
import com.apimarketplace.orchestrator.execution.v2.split.SplitNodeExecutor;
import com.apimarketplace.common.scaling.cache.InMemoryBudgetCache;
import com.apimarketplace.orchestrator.services.credit.CreditBudgetService;
import com.apimarketplace.orchestrator.services.resume.MergeNodeAnalyzer;

/**
 * Factory for creating UnifiedExecutionEngine instances in integration tests.
 * Provides real (non-mock) minimal dependencies for testing the traversal algorithm.
 */
public final class TestEngineFactory {

    private TestEngineFactory() {}

    /**
     * Creates an engine with real minimal dependencies for integration tests.
     * WorkflowFinalizer and StepByStepScheduler are null (not needed for auto-mode traversal tests).
     */
    public static UnifiedExecutionEngine create() {
        SplitContextManager contextManager = new SplitContextManager();
        return new UnifiedExecutionEngine(
            null, // workflowFinalizer
            new V2AutoScheduler(),
            null, // stepByStepScheduler
            new ReadyNodeCalculator(new MergeNodeAnalyzer(), null, null),
            new BackEdgeHandler(null, null, null, null),
            new SplitNodeExecutor(contextManager, null),
            new SplitAwareNodeExecutor(contextManager, null, null, null, null, null, null),
            new SplitMergeHandler(contextManager),
            new SplitAggregateHandler(contextManager, null, null, null, null),
            contextManager,
            new NodeSearchService(),
            null, // skipPropagationService
            new CreditBudgetService(null, new InMemoryBudgetCache()) // no credit check in tests
        );
    }
}
