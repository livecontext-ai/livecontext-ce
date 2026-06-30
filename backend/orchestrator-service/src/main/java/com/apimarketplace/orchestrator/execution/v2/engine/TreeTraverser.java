package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService;

/**
 * Functional interface for tree traversal callback.
 * Used to allow UnifiedExecutionEngine to pass its traverseTree method
 * to handlers (BackEdgeHandler) that need recursive traversal.
 */
@FunctionalInterface
public interface TreeTraverser {
    ExecutionContext traverse(
        ExecutionNode node,
        ExecutionContext context,
        WorkflowExecution execution,
        V2ExecutionEventService eventService,
        TriggerItem item);
}
