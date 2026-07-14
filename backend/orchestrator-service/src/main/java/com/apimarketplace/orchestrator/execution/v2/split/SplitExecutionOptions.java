package com.apimarketplace.orchestrator.execution.v2.split;

/**
 * Execution options threaded from a caller that needs a non-default split fan-out
 * disposition, down through {@code V2StepByStepService.executeNode} and
 * {@code UnifiedExecutionEngine.executeSingleNode} into
 * {@link SplitAwareNodeExecutor}.
 *
 * <h2>perItemContinuation</h2>
 * Set by {@code PerItemContinuationService} when walking the downstream chain of a
 * {@code continuationMode=per_item} approval (or future per-item signal) inside a
 * split, once per signal resolution. It flips four branch points in
 * {@code SplitAwareNodeExecutor.executeForAllItemsAndTraverse}:
 * <ol>
 *   <li><b>Durable per-item idempotency:</b> items that already have a terminal
 *       {@code workflow_step_data} row for (node, epoch) are excluded from execution -
 *       a later walk (sibling resolution) only runs the newly-routed items.</li>
 *   <li><b>No node-level snapshot mutation:</b> per-item persists run with
 *       {@code StepCompletionContext.suppressGlobalMark=true} (same mechanism as the
 *       split-async path, Phase 2.E). The node-level EpochState mark is written ONCE
 *       at seal by {@code StepCompletionOrchestrator.recordSplitAggregateIfMissing}.</li>
 *   <li><b>No skipped-record materialization:</b> unrouted sibling items are PENDING
 *       (their approval has not resolved yet), not skipped - the seal writes the
 *       real per-item SKIPPED rows once routing is final.</li>
 *   <li><b>Benign deferred result:</b> a walk that finds no executable item (wrong
 *       port, already persisted) returns a no-op summary flagged
 *       {@code SPLIT_ALREADY_PERSISTED} instead of marking the node SKIPPED.</li>
 * </ol>
 * It also disables the node-level idempotency guard in
 * {@code V2StepByStepService.executeNodeInternal}: between walks the chain node is
 * legitimately mid-flight (running, partially persisted), and the row-based exclusion
 * above IS this mode's idempotency.
 */
public record SplitExecutionOptions(boolean perItemContinuation) {

    /** Default disposition: normal fan-out semantics, no behavior change. */
    public static final SplitExecutionOptions NONE = new SplitExecutionOptions(false);

    /** Per-item continuation walk disposition (see class javadoc). */
    public static SplitExecutionOptions perItemContinuationWalk() {
        return new SplitExecutionOptions(true);
    }
}
