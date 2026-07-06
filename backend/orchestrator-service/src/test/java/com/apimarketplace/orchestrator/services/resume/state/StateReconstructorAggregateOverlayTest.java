package com.apimarketplace.orchestrator.services.resume.state;

import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the multi-epoch aggregate-status overlay decision
 * ({@link StateReconstructor#aggregateDisplayStatus}).
 *
 * <p>Bug: on the all-epochs canvas (authed /state AND marketplace showcase, both via
 * reconstructStateForApi), a node with no terminal result in the current epoch reconstructed as
 * SKIPPED (current epoch skipped it) or PENDING (a reusable-trigger run at WAITING_TRIGGER prunes
 * closed epochs, leaving empty flat sets) even though it COMPLETED in an earlier epoch. The flat
 * node sets only cover retained epochs and accumulated NodeCounts cannot tell a genuine prior-epoch
 * success from a superseded rerun spawn; the spawn-aware StepAggregationService can, so the overlay
 * reconciles a current PENDING/SKIPPED to the cumulative aggregate. Per-epoch view was already
 * correct (raw counts derived client-side, success wins).
 */
class StateReconstructorAggregateOverlayTest {

    @Test
    @DisplayName("regression: SKIPPED current + COMPLETED aggregate -> COMPLETED (multi-epoch success wins over skipped)")
    void skippedReconciledToCompletedOnMultiEpochSuccess() {
        assertEquals(RunStatus.COMPLETED,
                StateReconstructor.aggregateDisplayStatus(RunStatus.SKIPPED, RunStatus.COMPLETED),
                "3 completed + 8 skipped across epochs must colour the all-epochs node COMPLETED, not SKIPPED");
    }

    @Test
    @DisplayName("regression: PENDING current + COMPLETED aggregate -> COMPLETED (pruned reusable-trigger run at rest)")
    void pendingReconciledToCompletedWhenEpochsPruned() {
        // At WAITING_TRIGGER the closed epochs are pruned, so a node that completed earlier
        // reconstructs as PENDING. The all-epochs canvas must still show its cumulative success.
        assertEquals(RunStatus.COMPLETED,
                StateReconstructor.aggregateDisplayStatus(RunStatus.PENDING, RunStatus.COMPLETED));
    }

    @Test
    @DisplayName("PENDING/SKIPPED current + FAILED aggregate -> FAILED (failure wins)")
    void reconciledToFailed() {
        assertEquals(RunStatus.FAILED,
                StateReconstructor.aggregateDisplayStatus(RunStatus.SKIPPED, RunStatus.FAILED));
        assertEquals(RunStatus.FAILED,
                StateReconstructor.aggregateDisplayStatus(RunStatus.PENDING, RunStatus.FAILED));
    }

    @Test
    @DisplayName("PENDING/SKIPPED current + PARTIAL_SUCCESS aggregate -> PARTIAL_SUCCESS")
    void reconciledToPartialSuccess() {
        assertEquals(RunStatus.PARTIAL_SUCCESS,
                StateReconstructor.aggregateDisplayStatus(RunStatus.SKIPPED, RunStatus.PARTIAL_SUCCESS));
        assertEquals(RunStatus.PARTIAL_SUCCESS,
                StateReconstructor.aggregateDisplayStatus(RunStatus.PENDING, RunStatus.PARTIAL_SUCCESS));
    }

    @Test
    @DisplayName("PENDING current + SKIPPED aggregate -> SKIPPED (never-taken branch shows grey, not idle-pending)")
    void pendingReconciledToSkippedForNeverTakenBranch() {
        // A branch skipped in every epoch (pruned to PENDING at rest) shows SKIPPED, matching the
        // StepTable - but this is not a success upgrade, just cumulative-truth alignment.
        assertEquals(RunStatus.SKIPPED,
                StateReconstructor.aggregateDisplayStatus(RunStatus.PENDING, RunStatus.SKIPPED));
    }

    @Test
    @DisplayName("SKIPPED current + SKIPPED aggregate -> SKIPPED (rerun-deactivated branch stays grey)")
    void skippedStaysSkippedWhenAggregateSkipped() {
        // The spawn-aware aggregate keeps only the latest spawn per coordinate, so a branch-switch
        // rerun (old COMPLETED superseded by new SKIPPED) aggregates to SKIPPED - it stays SKIPPED,
        // preserving the rerun fix (commit e0f218ca9).
        assertEquals(RunStatus.SKIPPED,
                StateReconstructor.aggregateDisplayStatus(RunStatus.SKIPPED, RunStatus.SKIPPED));
    }

    @Test
    @DisplayName("PENDING/SKIPPED current + null aggregate (no step-data rows) -> unchanged (never-run node stays PENDING)")
    void staysWhenNoAggregate() {
        assertEquals(RunStatus.PENDING,
                StateReconstructor.aggregateDisplayStatus(RunStatus.PENDING, null));
        assertEquals(RunStatus.SKIPPED,
                StateReconstructor.aggregateDisplayStatus(RunStatus.SKIPPED, null));
    }

    @Test
    @DisplayName("PENDING/SKIPPED current + RUNNING/PENDING aggregate -> unchanged (only terminal aggregates reconcile)")
    void notReconciledByNonTerminalAggregate() {
        assertEquals(RunStatus.SKIPPED,
                StateReconstructor.aggregateDisplayStatus(RunStatus.SKIPPED, RunStatus.RUNNING));
        assertEquals(RunStatus.PENDING,
                StateReconstructor.aggregateDisplayStatus(RunStatus.PENDING, RunStatus.RUNNING));
        assertEquals(RunStatus.PENDING,
                StateReconstructor.aggregateDisplayStatus(RunStatus.PENDING, RunStatus.PENDING));
    }

    @Test
    @DisplayName("current COMPLETED/FAILED/RUNNING/AWAITING is never changed (live + current-epoch terminals win)")
    void currentTerminalOrLiveUntouched() {
        // The overlay only reconciles PENDING/SKIPPED. A current COMPLETED must not be demoted by a
        // stale-looking aggregate, and RUNNING/AWAITING (live state in the active epoch) always stands.
        assertEquals(RunStatus.COMPLETED,
                StateReconstructor.aggregateDisplayStatus(RunStatus.COMPLETED, RunStatus.SKIPPED));
        assertEquals(RunStatus.COMPLETED,
                StateReconstructor.aggregateDisplayStatus(RunStatus.COMPLETED, RunStatus.PARTIAL_SUCCESS));
        assertEquals(RunStatus.FAILED,
                StateReconstructor.aggregateDisplayStatus(RunStatus.FAILED, RunStatus.COMPLETED));
        assertEquals(RunStatus.RUNNING,
                StateReconstructor.aggregateDisplayStatus(RunStatus.RUNNING, RunStatus.COMPLETED));
        assertEquals(RunStatus.AWAITING_SIGNAL,
                StateReconstructor.aggregateDisplayStatus(RunStatus.AWAITING_SIGNAL, RunStatus.COMPLETED));
    }
}
