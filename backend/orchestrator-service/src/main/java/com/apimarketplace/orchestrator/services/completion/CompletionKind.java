package com.apimarketplace.orchestrator.services.completion;

/**
 * Disposition of a node-completion emission: is this the node's TERMINAL outcome,
 * or a NON-FINAL failed retry attempt of a node executing under a
 * {@link com.apimarketplace.orchestrator.domain.workflow.NodePolicy} with retries?
 *
 * <p>Both dispositions flow through ONE parameterized pipeline
 * ({@code V2ExecutionEventService.emitNodeComplete} →
 * {@code NodeCompletionService.emitNodeComplete} →
 * {@link StepCompletionOrchestrator#complete(StepCompletionContext, String, CompletionKind, boolean)});
 * this enum is the single switch consulted at every divergence point. Each accessor
 * below names one divergence point and documents WHY a non-final attempt skips it,
 * so the attempt-vs-terminal contract is readable at the exact line where it
 * branches - instead of living in two mirror methods that drift apart
 * (2026-06-10 audit items 2/3/4).
 *
 * <p>A {@link #NON_FINAL_ATTEMPT} is a transient state of a node that is still
 * RUNNING (it is about to retry). It is never silent: it always emits its
 * FAILURE-lifecycle WS step event, annotated with
 * {@code policy_attempt}/{@code policy_max_attempts} so the frontend can render
 * "attempt k/N failed, retrying" - and, outside loops, it persists its
 * {@code workflow_step_data} row. Everything else is terminal-only.
 */
public enum CompletionKind {

    /**
     * The node's terminal outcome (retry-then-success, exhausted failure, or a
     * plain non-retried completion): the full pipeline runs.
     */
    TERMINAL,

    /**
     * A failed retry attempt that is NOT the last one. The terminal attempt
     * (retry-then-success or exhausted failure) flows through the same pipeline
     * as {@link #TERMINAL}.
     */
    NON_FINAL_ATTEMPT;

    /**
     * StateSnapshot mutation ({@code NodeCounts} / {@code EpochState}) plus the
     * per-epoch {@code workflow_epochs} counter row - and, at the event layer, the
     * snapshot push that follows (nothing changed in the DB snapshot ⇒ nothing to push).
     *
     * <p><b>WHY attempts skip it:</b> {@code EpochState.failedNodeIds} is append-only
     * ({@code markNodeCompleted} does not remove entries), so recording an attempt
     * there would permanently poison the epoch state of a retry-then-success execution
     * and inflate failed+completed counts. The {@code workflow_epochs} additive counter
     * mirrors NodeCounts - both record the single terminal outcome only.
     */
    public boolean mutatesSnapshotCounts() {
        return this == TERMINAL;
    }

    /**
     * Outgoing-edge emission, per-epoch edge counts, and the {@code decisionEvaluated}
     * WS event.
     *
     * <p><b>WHY attempts skip it:</b> edges record the single terminal traversal, not
     * one COMPLETED/SKIPPED set per attempt - and a failed attempt selected no branch,
     * so there is no decision to evaluate. (2026-06-10 audit item 2 - snapshot pollution.)
     */
    public boolean emitsEdges() {
        return this == TERMINAL;
    }

    /**
     * Platform-credit billing and merge-collector recording.
     *
     * <p><b>WHY attempts skip it:</b> ONE platform credit per LOGICAL node execution,
     * charged on the terminal attempt (2026-06-10 audit item 3); and the ForEach merge
     * collector must never observe a node that has not actually completed.
     */
    public boolean bills() {
        return this == TERMINAL;
    }

    /**
     * Persistence of the {@code workflow_step_data} row. Only the TERMINAL outcome has one:
     * a non-final attempt is reported on the WS stream only, in a loop or not.
     *
     * <p><b>WHY attempts never persist a row:</b> the v6 unique index
     * ({@code …, iteration, item_index, epoch, spawn, status}) admits ONE FAILED row per
     * logical execution. An attempt row would claim it, and then:
     * <ul>
     *   <li>the TERMINAL failure's row (carrying {@code policy_attempt=N/N}, the final error
     *       and the {@code policy_continue_on_failure} flag that STEP_BY_STEP readiness and
     *       split routing read back from the row) is ON-CONFLICT-dropped, so the database
     *       records "attempt 1/N" as the node's outcome and a continued failure stops its
     *       successors (2026-06-10 audit item 4 found this for loops first);</li>
     *   <li>a retry-then-success keeps that FAILED row beside its COMPLETED one, which a
     *       row count (the split aggregate at seal) reads as a partial failure.</li>
     * </ul>
     * The terminal row stays authoritative; its {@code policy_attempt}/{@code policy_max_attempts}
     * annotation records how many attempts the execution consumed.
     */
    public boolean persistsRow() {
        return this == TERMINAL;
    }

    /**
     * {@code RunningNodeTracker.markCompleted} - clearing the per-epoch in-memory
     * running count.
     *
     * <p><b>WHY attempts skip it:</b> the node is still RUNNING - it is about to retry.
     */
    public boolean marksTrackerCompleted() {
        return this == TERMINAL;
    }

    /**
     * {@code ReadinessContextCache.invalidateRun} - readiness-cache invalidation.
     *
     * <p><b>WHY attempts skip it:</b> nothing observable by successors changed - the
     * node produced no terminal output and its completion state did not move.
     */
    public boolean invalidatesReadiness() {
        return this == TERMINAL;
    }

    /**
     * Saving a RESPONSE node's output message to the conversation
     * (chat trigger → response node flow).
     *
     * <p><b>WHY attempts skip it:</b> only a successful TERMINAL RESPONSE completion
     * produces the user-visible assistant message - a failed attempt has none, and
     * the node will retry.
     */
    public boolean savesResponseToConversation() {
        return this == TERMINAL;
    }
}
