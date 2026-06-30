package com.apimarketplace.orchestrator.services.resume.skip;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;

import java.util.Set;

/**
 * Context object for skip propagation operations.
 *
 * <p>Encapsulates all parameters needed for skip propagation, making method
 * signatures cleaner and allowing for future extensibility without breaking
 * existing code.
 *
 * @param execution The workflow execution context
 * @param plan The workflow plan containing edges and core nodes
 * @param skippedNodeId The ID of the node being skipped
 * @param skipSourceId The original source that triggered the skip (e.g., decision node)
 * @param visited Set of already visited nodes to prevent infinite loops (mutable)
 */
public record SkipPropagationContext(
    WorkflowExecution execution,
    WorkflowPlan plan,
    String skippedNodeId,
    String skipSourceId,
    Set<String> visited
) {
    /**
     * Creates a new context for propagating to a successor node.
     * Reuses the same execution, plan, skipSourceId, and visited set.
     *
     * @param successorNodeId The successor node to propagate the skip to
     * @return A new context for the successor
     */
    public SkipPropagationContext forSuccessor(String successorNodeId) {
        return new SkipPropagationContext(
            execution,
            plan,
            successorNodeId,
            skipSourceId,
            visited
        );
    }

    /**
     * Gets the run ID from the execution.
     *
     * @return The workflow run ID
     */
    public String runId() {
        return execution != null ? execution.getRunId() : null;
    }
}
