package com.apimarketplace.orchestrator.services.completion;

import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.utils.ExecutionConstants;

import java.util.Objects;

/**
 * Immutable context for step completion operations.
 *
 * This record encapsulates all the data needed to complete a step,
 * following the principle of making illegal states unrepresentable.
 *
 * @param execution    The workflow execution context (required)
 * @param nodeId       The fully qualified node ID, e.g., "mcp:my_step" (required)
 * @param nodeLabel    The node label for DB storage, e.g., "my_step" (required)
 * @param result       The execution result (required)
 * @param itemIndex    The item index (0-based), 0 for single-item workflows
 * @param iteration    The loop iteration (0-based), 0 if not in a loop
 * @param itemId       The hierarchical item ID for Split tracking (e.g., "0", "0.1", "0.2")
 * @param epoch        The execution epoch (0 = use global fallback via getCurrentEpochFromRun)
 * @param suppressGlobalMark When true, StepCompletionOrchestrator must NOT write to
 *                           StateSnapshot.markNode{Completed,Failed} for this completion.
 *                           Per-item DB row still persists; only the global epoch-state
 *                           write is suppressed. Set to true by AgentAsyncCompletionService
 *                           for split-async items so the aggregate is written ONCE at barrier
 *                           seal via recordSplitAggregateIfMissing - prevents the first
 *                           per-item failure from poisoning EpochState.failedNodeIds for
 *                           the whole node.
 */
public record StepCompletionContext(
    WorkflowExecution execution,
    String nodeId,
    String nodeLabel,
    StepExecutionResult result,
    int itemIndex,
    int iteration,
    String itemId,
    int epoch,
    boolean suppressGlobalMark
) {
    /**
     * Canonical constructor with validation.
     */
    public StepCompletionContext {
        Objects.requireNonNull(execution, "execution must not be null");
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(nodeLabel, "nodeLabel must not be null");
        Objects.requireNonNull(result, "result must not be null");

        if (itemIndex < 0) {
            throw new IllegalArgumentException("itemIndex must be >= 0, got: " + itemIndex);
        }
        if (iteration < 0) {
            throw new IllegalArgumentException("iteration must be >= 0, got: " + iteration);
        }

        // Derive itemId from itemIndex if not provided
        if (itemId == null) {
            itemId = deriveItemId(itemIndex);
        }
    }

    /**
     * 8-arg legacy constructor (suppressGlobalMark=false). Existing call sites compile
     * unchanged; only AgentAsyncCompletionService uses the 9-arg form.
     */
    public StepCompletionContext(
            WorkflowExecution execution,
            String nodeId,
            String nodeLabel,
            StepExecutionResult result,
            int itemIndex,
            int iteration,
            String itemId,
            int epoch) {
        this(execution, nodeId, nodeLabel, result, itemIndex, iteration, itemId, epoch, false);
    }

    /**
     * Return a copy with suppressGlobalMark overridden.
     */
    public StepCompletionContext withSuppressGlobalMark(boolean suppress) {
        return new StepCompletionContext(execution, nodeId, nodeLabel, result, itemIndex,
            iteration, itemId, epoch, suppress);
    }

    /**
     * Convenience constructor for simple cases (single item, no loop).
     */
    public static StepCompletionContext of(
            WorkflowExecution execution,
            String nodeId,
            String nodeLabel,
            StepExecutionResult result) {
        return new StepCompletionContext(execution, nodeId, nodeLabel, result, 0, 0, "0", 0);
    }

    /**
     * Convenience constructor with nullable itemIndex and iteration.
     */
    public static StepCompletionContext of(
            WorkflowExecution execution,
            String nodeId,
            String nodeLabel,
            StepExecutionResult result,
            Integer itemIndex,
            Integer iteration) {
        int idx = itemIndex != null ? itemIndex : 0;
        int iter = iteration != null ? iteration : 0;
        return new StepCompletionContext(
            execution,
            nodeId,
            nodeLabel,
            result,
            idx,
            iter,
            deriveItemId(idx),
            0
        );
    }

    /**
     * Constructor with explicit epoch for parallel epoch support.
     */
    public static StepCompletionContext of(
            WorkflowExecution execution,
            String nodeId,
            String nodeLabel,
            StepExecutionResult result,
            Integer itemIndex,
            Integer iteration,
            int epoch) {
        int idx = itemIndex != null ? itemIndex : 0;
        int iter = iteration != null ? iteration : 0;
        return new StepCompletionContext(
            execution,
            nodeId,
            nodeLabel,
            result,
            idx,
            iter,
            deriveItemId(idx),
            epoch
        );
    }

    /**
     * Constructor with explicit itemId for Split items.
     */
    public static StepCompletionContext of(
            WorkflowExecution execution,
            String nodeId,
            String nodeLabel,
            StepExecutionResult result,
            Integer itemIndex,
            Integer iteration,
            String itemId) {
        return new StepCompletionContext(
            execution,
            nodeId,
            nodeLabel,
            result,
            itemIndex != null ? itemIndex : 0,
            iteration != null ? iteration : 0,
            itemId,
            0
        );
    }

    /**
     * Derives itemId from itemIndex.
     *
     * <p>For root items (itemIndex < MULTIPLIER): itemId is just the index as string ("0", "1", ...)
     * <p>For Split items: itemId is "parentId.childIndex" where childIndex is 1-based
     */
    private static String deriveItemId(int itemIndex) {
        if (itemIndex < ExecutionConstants.SPLIT_ITEM_INDEX_MULTIPLIER) {
            return String.valueOf(itemIndex);
        }
        // Split item: parentIndex * MULTIPLIER + childIndex (0-based)
        int parentIndex = itemIndex / ExecutionConstants.SPLIT_ITEM_INDEX_MULTIPLIER;
        int childIndex = itemIndex % ExecutionConstants.SPLIT_ITEM_INDEX_MULTIPLIER;
        // Note: childIndex in itemId is 1-based (e.g., "0.1", "0.2", not "0.0", "0.1")
        return parentIndex + "." + (childIndex + 1);
    }

    /**
     * Get the run ID from the execution context.
     */
    public String runId() {
        return execution.getRunId();
    }

    /**
     * Check if this is a loop iteration (iteration > 0 or has loop context).
     */
    public boolean isLoopIteration() {
        return iteration > 0;
    }

    /**
     * Check if this is a multi-item workflow (itemIndex context present).
     */
    public boolean isMultiItem() {
        return itemIndex > 0;
    }

    /**
     * Check if this is a Split child item.
     */
    public boolean isSplitChild() {
        return itemId != null && itemId.contains(".");
    }
}
