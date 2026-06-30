package com.apimarketplace.orchestrator.services.completion;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;

import java.util.Objects;

/**
 * Immutable context for skip operations.
 *
 * @param execution      The workflow execution context (required)
 * @param nodeId         The fully qualified node ID being skipped (required)
 * @param nodeLabel      The node label for DB storage (required)
 * @param skipReason     Human-readable reason for the skip (required)
 * @param skipSourceNode The node that caused the skip (e.g., decision node)
 * @param itemIndex      The item index (0-based)
 * @param iteration      The loop iteration (0-based), 0 if not in a loop
 * @param epoch          The execution epoch (0 = use global fallback via getCurrentEpochFromRun)
 */
public record SkipContext(
    WorkflowExecution execution,
    String nodeId,
    String nodeLabel,
    String skipReason,
    String skipSourceNode,
    int itemIndex,
    int iteration,
    int epoch
) {
    /**
     * Canonical constructor with validation.
     */
    public SkipContext {
        Objects.requireNonNull(execution, "execution must not be null");
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(nodeLabel, "nodeLabel must not be null");
        Objects.requireNonNull(skipReason, "skipReason must not be null");

        if (itemIndex < 0) {
            throw new IllegalArgumentException("itemIndex must be >= 0, got: " + itemIndex);
        }
        if (iteration < 0) {
            throw new IllegalArgumentException("iteration must be >= 0, got: " + iteration);
        }
    }

    /**
     * Legacy constructor for callers that do not track loop iteration.
     */
    public SkipContext(
            WorkflowExecution execution,
            String nodeId,
            String nodeLabel,
            String skipReason,
            String skipSourceNode,
            int itemIndex,
            int epoch) {
        this(execution, nodeId, nodeLabel, skipReason, skipSourceNode, itemIndex, 0, epoch);
    }

    /**
     * Convenience factory method.
     */
    public static SkipContext of(
            WorkflowExecution execution,
            String nodeId,
            String nodeLabel,
            String skipReason,
            String skipSourceNode,
            int itemIndex) {
        return new SkipContext(execution, nodeId, nodeLabel, skipReason, skipSourceNode, itemIndex, 0, 0);
    }

    /**
     * Factory method with explicit epoch for parallel epoch support.
     */
    public static SkipContext of(
            WorkflowExecution execution,
            String nodeId,
            String nodeLabel,
            String skipReason,
            String skipSourceNode,
            int itemIndex,
            int epoch) {
        return new SkipContext(execution, nodeId, nodeLabel, skipReason, skipSourceNode, itemIndex, 0, epoch);
    }

    /**
     * Factory method with explicit iteration and epoch for loop-scoped skipped rows.
     */
    public static SkipContext of(
            WorkflowExecution execution,
            String nodeId,
            String nodeLabel,
            String skipReason,
            String skipSourceNode,
            int itemIndex,
            int iteration,
            int epoch) {
        return new SkipContext(execution, nodeId, nodeLabel, skipReason, skipSourceNode, itemIndex, iteration, epoch);
    }

    /**
     * Get the run ID from the execution context.
     */
    public String runId() {
        return execution.getRunId();
    }
}
