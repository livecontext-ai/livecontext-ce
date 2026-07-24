package com.apimarketplace.orchestrator.services.resume.state;

import com.apimarketplace.orchestrator.domain.execution.DagState;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // ---------------------------------------------------------------------------
    // isMidRerunResetWindow - suppresses the overlay while a rerun reset is live.
    //
    // Bug (CE-SBS-002): rerunning a decision node resets its subgraph to PENDING at
    // spawn N+1, but no spawn-N+1 rows exist yet, so the "latest spawn per
    // coordinate" aggregate still reports the spawn-N terminal statuses. The overlay
    // then resurrected the stale COMPLETED on the previously-taken branch that the
    // reset had just cleared. The overlay must stand down while the rerun's epoch is
    // still active; once the epoch closes the aggregate matches reality again.
    //
    // CRITICAL data-shape detail: the spawn counter PRODUCTION writes lives in run
    // METADATA (metadata.dagCurrentSpawn[triggerId], TriggerEpochManager.incrementSpawn
    // via StepRerunService) - the snapshot's DagState.currentSpawn stays 0 on real runs
    // (no production caller of DagState.advanceSpawn). The guard must therefore fire on
    // the metadata shape; the snapshot field is only a fallback.
    // ---------------------------------------------------------------------------

    private static StateSnapshot snapshotWithDag(DagState dag) {
        return new StateSnapshot(3, 0L, Map.of("trigger:start", dag),
                null, null, null, null, null, Map.of(), Map.of(),
                null, null, null, null, 0L, Instant.now());
    }

    /** The exact metadata shape TriggerEpochManager.incrementSpawn persists. */
    private static Map<String, Object> rerunMetadata(String triggerId, int spawn) {
        return Map.of("dagCurrentSpawn", Map.of(triggerId, spawn), "lastRerunSpawn", spawn);
    }

    @Test
    @DisplayName("regression CE-SBS-002 (production shape): metadata dagCurrentSpawn > 0 + active epoch -> overlay suppressed even with snapshot spawn 0")
    void metadataSpawnAloneDetectsTheRerunWindow() {
        // Real runs NEVER stamp DagState.currentSpawn (stays 0); only metadata carries the
        // rerun counter. This is the shape reconstructStateForApi sees right after
        // StepRerunService flipped the decision in CE-SBS-002.
        DagState snapshotAsProductionWritesIt = new DagState(1, 0, 1, Map.of(), Set.of(1));
        assertTrue(
                StateReconstructor.isMidRerunResetWindow(
                        snapshotWithDag(snapshotAsProductionWritesIt),
                        rerunMetadata("trigger:start", 1)),
                "the guard must observe metadata.dagCurrentSpawn - with snapshot-only spawn it"
                        + " would be a production no-op and the stale COMPLETED would come back");
    }

    @Test
    @DisplayName("regression CE-SBS-002: active epoch + snapshot spawn > 0 (fallback writer) -> overlay suppressed")
    void midRerunResetWindowDetectedOnActiveRerunEpoch() {
        DagState rerunInFlight = new DagState(1, 1, 1, Map.of(), Set.of(1));
        assertTrue(StateReconstructor.isMidRerunResetWindow(snapshotWithDag(rerunInFlight), null),
                "an active epoch on a rerun DAG must suppress the aggregate overlay - it would"
                        + " resurrect the superseded spawn's COMPLETED on the reset subgraph");
    }

    @Test
    @DisplayName("rerun epoch closed (WAITING_TRIGGER / completed) -> overlay applies again")
    void closedRerunEpochIsNotAWindow() {
        DagState rerunAtRest = new DagState(1, 1, 1, Map.of(), Set.of());
        assertFalse(StateReconstructor.isMidRerunResetWindow(
                        snapshotWithDag(rerunAtRest), rerunMetadata("trigger:start", 1)),
                "once every epoch is closed the spawn-aware aggregate matches reality:"
                        + " the cumulative all-epochs display must come back");
    }

    @Test
    @DisplayName("active epoch without any rerun (spawn 0 everywhere) -> overlay applies (in-flight epochs keep cumulative display)")
    void activeEpochWithoutRerunIsNotAWindow() {
        DagState normalInFlight = new DagState(2, 0, 2, Map.of(), Set.of(2));
        assertFalse(StateReconstructor.isMidRerunResetWindow(snapshotWithDag(normalInFlight), Map.of()),
                "a never-rerun run mid-epoch is not a reset window - prior-epoch successes may"
                        + " still be reconciled onto the all-epochs canvas");
    }

    @Test
    @DisplayName("metadata spawn for ANOTHER trigger does not put this DAG in a window")
    void metadataSpawnIsPerTrigger() {
        DagState inFlight = new DagState(1, 0, 1, Map.of(), Set.of(1));
        assertFalse(StateReconstructor.isMidRerunResetWindow(
                snapshotWithDag(inFlight), rerunMetadata("trigger:other", 3)),
                "dagCurrentSpawn is keyed per trigger - a rerun on another DAG must not"
                        + " suppress this DAG's overlay");
    }

    @Test
    @DisplayName("no dags at all -> not a window")
    void emptySnapshotIsNotAWindow() {
        assertFalse(StateReconstructor.isMidRerunResetWindow(
                new StateSnapshot(3, 0L, Map.of(), null, null, null, null, null,
                        Map.of(), Map.of(), null, null, null, null, 0L, Instant.now()),
                rerunMetadata("trigger:start", 1)));
    }

    @Test
    @DisplayName("any one DAG in a reset window is enough to suppress")
    void anyDagInWindowSuppresses() {
        DagState atRest = new DagState(1, 0, 1, Map.of(), Set.of());
        DagState inFlight = new DagState(1, 0, 1, Map.of(), Set.of(1));
        StateSnapshot snapshot = new StateSnapshot(3, 0L,
                Map.of("trigger:a", atRest, "trigger:b", inFlight),
                null, null, null, null, null, Map.of(), Map.of(),
                null, null, null, null, 0L, Instant.now());
        assertTrue(StateReconstructor.isMidRerunResetWindow(
                snapshot, rerunMetadata("trigger:b", 2)));
    }

    @Test
    @DisplayName("malformed / absent metadata degrades to the snapshot field, never throws")
    void malformedMetadataFallsBackToSnapshot() {
        DagState inFlight = new DagState(1, 0, 1, Map.of(), Set.of(1));
        assertFalse(StateReconstructor.isMidRerunResetWindow(
                snapshotWithDag(inFlight), Map.of("dagCurrentSpawn", "not-a-map")));
        assertFalse(StateReconstructor.isMidRerunResetWindow(
                snapshotWithDag(inFlight), Map.of("dagCurrentSpawn", Map.of("trigger:start", "NaN"))));
        DagState inFlightSnapshotSpawn = new DagState(1, 1, 1, Map.of(), Set.of(1));
        assertTrue(StateReconstructor.isMidRerunResetWindow(
                snapshotWithDag(inFlightSnapshotSpawn), Map.of("dagCurrentSpawn", "not-a-map")),
                "when metadata is unusable the snapshot spawn still counts");
    }
}
