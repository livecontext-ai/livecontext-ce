package com.apimarketplace.orchestrator.domain.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the SBS (step-by-step) epoch lifecycle on domain objects.
 *
 * SBS model:
 *   - Epoch stays open (never close/prune) so reruns always work
 *   - Status determined purely by readyNodeIds:
 *       non-trigger ready → PAUSED
 *       only trigger ready → WAITING_TRIGGER
 *   - Rerun resets target + downstream, marks target as READY
 *   - Unlimited rerun/play cycles within the same epoch
 *
 * Pure domain tests (no Spring context, no mocks).
 */
@DisplayName("SBS Epoch Lifecycle - Domain Objects")
class SbsEpochLifecycleTest {

    private static final String TRIGGER_ID = "trigger:webhook";
    private static final String NODE_ID = "table:create_row";

    /**
     * Build a V3 StateSnapshot with a single DAG containing an active epoch.
     */
    private StateSnapshot createSnapshot(int epoch, EpochState epochState) {
        DagState dag = new DagState(epoch, 0, 1, Map.of(epoch, epochState), Set.of(epoch));
        return new StateSnapshot(3, 0L, Map.of(TRIGGER_ID, dag),
                null, null, null, null, null,
                new HashMap<>(), new HashMap<>(),
                null, null, null, null, null, null);
    }

    private StateSnapshot createSnapshotWithReadyNodes(int epoch) {
        EpochState es = EpochState.fresh()
                .addReadyNode(TRIGGER_ID)
                .addReadyNode(NODE_ID);
        return createSnapshot(epoch, es);
    }

    /**
     * Determine SBS status from readyNodeIds (same logic as InternalSbsController).
     */
    private boolean hasNonTriggerReady(StateSnapshot snap) {
        return snap.getReadyNodeIds().stream()
                .anyMatch(id -> !id.startsWith("trigger:"));
    }

    private boolean hasTriggerReady(StateSnapshot snap) {
        return snap.getReadyNodeIds().stream()
                .anyMatch(id -> id.startsWith("trigger:"));
    }

    private String sbsStatus(StateSnapshot snap) {
        if (hasNonTriggerReady(snap)) return "PAUSED";
        if (hasTriggerReady(snap)) return "WAITING_TRIGGER";
        return "PAUSED";
    }

    // ========================================================================
    // EpochState: readyNodeIds cleanup on status transitions
    // ========================================================================

    @Nested
    @DisplayName("EpochState readyNodeIds cleanup")
    class EpochStateReadyCleanup {

        @Test
        @DisplayName("markNodeCompleted removes node from readyNodeIds")
        void markNodeCompletedRemovesFromReady() {
            EpochState es = EpochState.fresh()
                    .addReadyNode(NODE_ID)
                    .addReadyNode(TRIGGER_ID);

            EpochState after = es.markNodeCompleted(NODE_ID);
            assertThat(after.getReadyNodeIds()).containsExactly(TRIGGER_ID);
            assertThat(after.getCompletedNodeIds()).contains(NODE_ID);
        }

        @Test
        @DisplayName("markNodeFailed removes node from readyNodeIds")
        void markNodeFailedRemovesFromReady() {
            EpochState es = EpochState.fresh().addReadyNode(NODE_ID);
            EpochState after = es.markNodeFailed(NODE_ID);
            assertThat(after.getReadyNodeIds()).isEmpty();
            assertThat(after.getFailedNodeIds()).contains(NODE_ID);
        }

        @Test
        @DisplayName("markNodeSkipped removes node from readyNodeIds")
        void markNodeSkippedRemovesFromReady() {
            EpochState es = EpochState.fresh().addReadyNode(NODE_ID);
            EpochState after = es.markNodeSkipped(NODE_ID);
            assertThat(after.getReadyNodeIds()).isEmpty();
            assertThat(after.getSkippedNodeIds()).contains(NODE_ID);
        }

        @Test
        @DisplayName("markNodeAwaitingSignal removes node from readyNodeIds")
        void markNodeAwaitingSignalRemovesFromReady() {
            EpochState es = EpochState.fresh().addReadyNode(NODE_ID);
            EpochState after = es.markNodeAwaitingSignal(NODE_ID);
            assertThat(after.getReadyNodeIds()).isEmpty();
            assertThat(after.getAwaitingSignalNodeIds()).contains(NODE_ID);
        }

        @Test
        @DisplayName("addRunningNode removes node from readyNodeIds")
        void addRunningNodeRemovesFromReady() {
            EpochState es = EpochState.fresh().addReadyNode(NODE_ID);
            EpochState after = es.addRunningNode(NODE_ID);
            assertThat(after.getReadyNodeIds()).isEmpty();
            assertThat(after.getRunningNodeIds()).contains(NODE_ID);
        }

        @Test
        @DisplayName("markNodeCompleted also removes from runningNodeIds")
        void markNodeCompletedRemovesFromRunning() {
            EpochState es = EpochState.fresh().addRunningNode(NODE_ID);
            EpochState after = es.markNodeCompleted(NODE_ID);
            assertThat(after.getRunningNodeIds()).isEmpty();
            assertThat(after.getCompletedNodeIds()).contains(NODE_ID);
        }
    }

    // ========================================================================
    // SBS: basic trigger → execute → status flow (no epoch close)
    // ========================================================================

    @Nested
    @DisplayName("SBS basic flow (epoch stays open)")
    class SbsBasicFlow {

        @Test
        @DisplayName("Initial state: both trigger and node ready → PAUSED")
        void initialStateIsPaused() {
            StateSnapshot snap = createSnapshotWithReadyNodes(1);
            assertThat(sbsStatus(snap)).isEqualTo("PAUSED");
        }

        @Test
        @DisplayName("After trigger executes: node still ready → PAUSED")
        void afterTriggerExecuteStillPaused() {
            StateSnapshot snap = createSnapshotWithReadyNodes(1);
            snap = snap.markNodeCompleted(TRIGGER_ID, TRIGGER_ID, 1);

            // trigger completed, but node is still ready
            assertThat(snap.getReadyNodeIds()).contains(NODE_ID);
            assertThat(sbsStatus(snap)).isEqualTo("PAUSED");
        }

        @Test
        @DisplayName("After all nodes execute: only trigger ready → WAITING_TRIGGER")
        void afterAllExecuteWaitingTrigger() {
            StateSnapshot snap = createSnapshotWithReadyNodes(1);
            snap = snap.markNodeCompleted(TRIGGER_ID, TRIGGER_ID, 1);
            snap = snap.markNodeCompleted(TRIGGER_ID, NODE_ID, 1);

            // Both completed, readyNodeIds empty → PAUSED (no trigger ready)
            assertThat(snap.getReadyNodeIds()).isEmpty();
            assertThat(sbsStatus(snap)).isEqualTo("PAUSED");

            // In real flow, prepareNextEpochReady adds trigger back
            snap = snap.addReadyNode(TRIGGER_ID);
            assertThat(sbsStatus(snap)).isEqualTo("WAITING_TRIGGER");
        }

        @Test
        @DisplayName("NodeCounts accumulate across executions within same epoch")
        void nodeCountsAccumulateInSameEpoch() {
            StateSnapshot snap = createSnapshotWithReadyNodes(1);
            snap = snap.markNodeCompleted(TRIGGER_ID, NODE_ID, 1);
            assertThat(snap.getNodes().get(NODE_ID).completed()).isEqualTo(1);
        }
    }

    // ========================================================================
    // SBS: node rerun (non-trigger)
    // ========================================================================

    @Nested
    @DisplayName("SBS node rerun (non-trigger)")
    class SbsNodeRerun {

        @Test
        @DisplayName("Rerun node: reset + addReady → node is READY → PAUSED")
        void rerunNodeMakesPaused() {
            StateSnapshot snap = createSnapshotWithReadyNodes(1);
            snap = snap.markNodeCompleted(TRIGGER_ID, TRIGGER_ID, 1);
            snap = snap.markNodeCompleted(TRIGGER_ID, NODE_ID, 1);
            snap = snap.addReadyNode(TRIGGER_ID); // prepareNextEpochReady

            assertThat(sbsStatus(snap)).isEqualTo("WAITING_TRIGGER");

            // Rerun node: resetDag removes from completed, addReadyNode makes it ready
            snap = snap.resetDag(Set.of(NODE_ID)).addReadyNode(NODE_ID);

            assertThat(snap.getReadyNodeIds()).contains(NODE_ID);
            assertThat(sbsStatus(snap)).isEqualTo("PAUSED");
        }

        @Test
        @DisplayName("After rerun + execute: back to WAITING_TRIGGER")
        void afterRerunAndExecuteBackToWaiting() {
            StateSnapshot snap = createSnapshotWithReadyNodes(1);
            snap = snap.markNodeCompleted(TRIGGER_ID, TRIGGER_ID, 1);
            snap = snap.markNodeCompleted(TRIGGER_ID, NODE_ID, 1);
            snap = snap.addReadyNode(TRIGGER_ID);

            // Rerun
            snap = snap.resetDag(Set.of(NODE_ID)).addReadyNode(NODE_ID);
            assertThat(sbsStatus(snap)).isEqualTo("PAUSED");

            // Execute rerun
            snap = snap.markNodeCompleted(TRIGGER_ID, NODE_ID, 1);
            assertThat(sbsStatus(snap)).isEqualTo("WAITING_TRIGGER");
        }

        @Test
        @DisplayName("Rerun + execute cycle is repeatable 5 times (same epoch)")
        void rerunCycleRepeatable5Times() {
            StateSnapshot snap = createSnapshotWithReadyNodes(1);
            snap = snap.markNodeCompleted(TRIGGER_ID, TRIGGER_ID, 1);
            snap = snap.markNodeCompleted(TRIGGER_ID, NODE_ID, 1);
            snap = snap.addReadyNode(TRIGGER_ID);

            for (int i = 0; i < 5; i++) {
                // Rerun
                snap = snap.resetDag(Set.of(NODE_ID)).addReadyNode(NODE_ID);
                assertThat(sbsStatus(snap))
                        .as("Rerun %d: should be PAUSED", i).isEqualTo("PAUSED");

                // Execute
                snap = snap.markNodeCompleted(TRIGGER_ID, NODE_ID, 1);
                assertThat(sbsStatus(snap))
                        .as("After execute %d: should be WAITING_TRIGGER", i).isEqualTo("WAITING_TRIGGER");
            }
        }

        @Test
        @DisplayName("NodeCounts accumulate across reruns")
        void nodeCountsAccumulateAcrossReruns() {
            StateSnapshot snap = createSnapshotWithReadyNodes(1);
            snap = snap.markNodeCompleted(TRIGGER_ID, NODE_ID, 1);
            assertThat(snap.getNodes().get(NODE_ID).completed()).isEqualTo(1);

            // Rerun + execute
            snap = snap.resetDag(Set.of(NODE_ID)).addReadyNode(NODE_ID);
            snap = snap.markNodeCompleted(TRIGGER_ID, NODE_ID, 1);
            assertThat(snap.getNodes().get(NODE_ID).completed()).isEqualTo(2);

            // Rerun + execute again
            snap = snap.resetDag(Set.of(NODE_ID)).addReadyNode(NODE_ID);
            snap = snap.markNodeCompleted(TRIGGER_ID, NODE_ID, 1);
            assertThat(snap.getNodes().get(NODE_ID).completed()).isEqualTo(3);
        }
    }

    // ========================================================================
    // SBS: trigger rerun
    // ========================================================================

    @Nested
    @DisplayName("SBS trigger rerun")
    class SbsTriggerRerun {

        @Test
        @DisplayName("Rerun trigger: only trigger ready, downstream NOT ready → WAITING_TRIGGER")
        void rerunTriggerOnlyTriggerReady() {
            StateSnapshot snap = createSnapshotWithReadyNodes(1);
            snap = snap.markNodeCompleted(TRIGGER_ID, TRIGGER_ID, 1);
            snap = snap.markNodeCompleted(TRIGGER_ID, NODE_ID, 1);
            snap = snap.addReadyNode(TRIGGER_ID);

            // Rerun trigger: reset trigger + all downstream, mark only trigger ready
            snap = snap.resetDag(Set.of(TRIGGER_ID, NODE_ID)).addReadyNode(TRIGGER_ID);

            assertThat(snap.getReadyNodeIds()).containsExactly(TRIGGER_ID);
            assertThat(sbsStatus(snap)).isEqualTo("WAITING_TRIGGER");
        }

        @Test
        @DisplayName("After trigger rerun + fire: downstream becomes ready → PAUSED")
        void triggerRerunThenFireMakesPaused() {
            StateSnapshot snap = createSnapshotWithReadyNodes(1);
            snap = snap.markNodeCompleted(TRIGGER_ID, TRIGGER_ID, 1);
            snap = snap.markNodeCompleted(TRIGGER_ID, NODE_ID, 1);
            snap = snap.addReadyNode(TRIGGER_ID);

            // Rerun trigger
            snap = snap.resetDag(Set.of(TRIGGER_ID, NODE_ID)).addReadyNode(TRIGGER_ID);
            assertThat(sbsStatus(snap)).isEqualTo("WAITING_TRIGGER");

            // Trigger fires → trigger completes, downstream becomes ready
            snap = snap.markNodeCompleted(TRIGGER_ID, TRIGGER_ID, 1);
            snap = snap.addReadyNode(NODE_ID);
            assertThat(sbsStatus(snap)).isEqualTo("PAUSED");
        }

        @Test
        @DisplayName("Full trigger rerun cycle: rerun → fire → execute → WAITING_TRIGGER")
        void fullTriggerRerunCycle() {
            StateSnapshot snap = createSnapshotWithReadyNodes(1);
            snap = snap.markNodeCompleted(TRIGGER_ID, TRIGGER_ID, 1);
            snap = snap.markNodeCompleted(TRIGGER_ID, NODE_ID, 1);
            snap = snap.addReadyNode(TRIGGER_ID);

            // Rerun trigger
            snap = snap.resetDag(Set.of(TRIGGER_ID, NODE_ID)).addReadyNode(TRIGGER_ID);

            // Trigger fires
            snap = snap.markNodeCompleted(TRIGGER_ID, TRIGGER_ID, 1);
            snap = snap.addReadyNode(NODE_ID);

            // Execute downstream
            snap = snap.markNodeCompleted(TRIGGER_ID, NODE_ID, 1);
            // After all nodes execute, trigger is re-added to ready by the scheduler
            snap = snap.addReadyNode(TRIGGER_ID);
            assertThat(sbsStatus(snap)).isEqualTo("WAITING_TRIGGER");
        }

        @Test
        @DisplayName("Trigger rerun is repeatable 3 times")
        void triggerRerunRepeatable3Times() {
            StateSnapshot snap = createSnapshotWithReadyNodes(1);

            for (int i = 0; i < 3; i++) {
                // Execute all
                snap = snap.markNodeCompleted(TRIGGER_ID, TRIGGER_ID, 1);
                snap = snap.markNodeCompleted(TRIGGER_ID, NODE_ID, 1);
                snap = snap.addReadyNode(TRIGGER_ID);
                assertThat(sbsStatus(snap))
                        .as("Cycle %d: WAITING_TRIGGER after execution", i).isEqualTo("WAITING_TRIGGER");

                // Rerun trigger
                snap = snap.resetDag(Set.of(TRIGGER_ID, NODE_ID)).addReadyNode(TRIGGER_ID);
                assertThat(sbsStatus(snap))
                        .as("Cycle %d: WAITING_TRIGGER after trigger rerun", i).isEqualTo("WAITING_TRIGGER");
            }
        }
    }

    // ========================================================================
    // SBS: mixed rerun scenarios (trigger + node reruns interleaved)
    // ========================================================================

    @Nested
    @DisplayName("SBS mixed rerun scenarios")
    class SbsMixedReruns {

        @Test
        @DisplayName("Rerun node, then rerun trigger, then rerun node again")
        void mixedRerunSequence() {
            StateSnapshot snap = createSnapshotWithReadyNodes(1);
            snap = snap.markNodeCompleted(TRIGGER_ID, TRIGGER_ID, 1);
            snap = snap.markNodeCompleted(TRIGGER_ID, NODE_ID, 1);
            snap = snap.addReadyNode(TRIGGER_ID);

            // 1. Rerun node
            snap = snap.resetDag(Set.of(NODE_ID)).addReadyNode(NODE_ID);
            assertThat(sbsStatus(snap)).isEqualTo("PAUSED");

            // 2. Execute node
            snap = snap.markNodeCompleted(TRIGGER_ID, NODE_ID, 1);
            assertThat(sbsStatus(snap)).isEqualTo("WAITING_TRIGGER");

            // 3. Rerun trigger
            snap = snap.resetDag(Set.of(TRIGGER_ID, NODE_ID)).addReadyNode(TRIGGER_ID);
            assertThat(sbsStatus(snap)).isEqualTo("WAITING_TRIGGER");

            // 4. Fire trigger + execute downstream
            snap = snap.markNodeCompleted(TRIGGER_ID, TRIGGER_ID, 1);
            snap = snap.addReadyNode(NODE_ID);
            snap = snap.markNodeCompleted(TRIGGER_ID, NODE_ID, 1);
            // After all nodes execute, trigger is re-added to ready by the scheduler
            snap = snap.addReadyNode(TRIGGER_ID);
            assertThat(sbsStatus(snap)).isEqualTo("WAITING_TRIGGER");

            // 5. Rerun node again
            snap = snap.resetDag(Set.of(NODE_ID)).addReadyNode(NODE_ID);
            assertThat(sbsStatus(snap)).isEqualTo("PAUSED");
            snap = snap.markNodeCompleted(TRIGGER_ID, NODE_ID, 1);
            snap = snap.addReadyNode(TRIGGER_ID);
            assertThat(sbsStatus(snap)).isEqualTo("WAITING_TRIGGER");
        }

        @Test
        @DisplayName("10 consecutive node reruns in same epoch")
        void tenConsecutiveNodeReruns() {
            StateSnapshot snap = createSnapshotWithReadyNodes(1);
            snap = snap.markNodeCompleted(TRIGGER_ID, TRIGGER_ID, 1);
            snap = snap.markNodeCompleted(TRIGGER_ID, NODE_ID, 1);
            snap = snap.addReadyNode(TRIGGER_ID);

            for (int i = 0; i < 10; i++) {
                snap = snap.resetDag(Set.of(NODE_ID)).addReadyNode(NODE_ID);
                assertThat(sbsStatus(snap)).as("Rerun %d", i).isEqualTo("PAUSED");

                snap = snap.markNodeCompleted(TRIGGER_ID, NODE_ID, 1);
                assertThat(sbsStatus(snap)).as("After exec %d", i).isEqualTo("WAITING_TRIGGER");
            }

            // NodeCounts: 1 initial + 10 reruns = 11 completions
            assertThat(snap.getNodes().get(NODE_ID).completed()).isEqualTo(11);
        }
    }

    // ========================================================================
    // SBS: rerunnable state detection (StepRerunService logic)
    // ========================================================================

    @Nested
    @DisplayName("Rerunnable state detection via NodeCounts")
    class RerunnableStateDetection {

        @Test
        @DisplayName("Completed node is rerunnable via flat view (epoch stays open)")
        void completedNodeRerunnableViaFlatView() {
            StateSnapshot snap = createSnapshotWithReadyNodes(1);
            snap = snap.markNodeCompleted(TRIGGER_ID, NODE_ID, 1);

            // Epoch stays open → flat view shows completed
            assertThat(snap.getCompletedNodeIds()).contains(NODE_ID);

            // Also in NodeCounts
            assertThat(snap.getNodes().get(NODE_ID).completed()).isGreaterThan(0);
        }

        @Test
        @DisplayName("Failed node is rerunnable via flat view (epoch stays open)")
        void failedNodeRerunnableViaFlatView() {
            StateSnapshot snap = createSnapshotWithReadyNodes(1);
            snap = snap.markNodeFailed(TRIGGER_ID, NODE_ID, 1);

            // Epoch stays open → flat view shows failed
            assertThat(snap.getFailedNodeIds()).contains(NODE_ID);

            // Also in NodeCounts
            assertThat(snap.getNodes().get(NODE_ID).failed()).isGreaterThan(0);
        }

        @Test
        @DisplayName("NodeCounts fallback still works if epoch were pruned")
        void nodeCountsFallbackForPrunedEpoch() {
            StateSnapshot snap = createSnapshotWithReadyNodes(1);
            snap = snap.markNodeCompleted(TRIGGER_ID, NODE_ID, 1);

            // Simulate epoch prune (should not happen in SBS, but testing resilience)
            snap = snap.closeAndPruneEpochForDag(TRIGGER_ID, 1);
            assertThat(snap.getCompletedNodeIds()).doesNotContain(NODE_ID);

            // NodeCounts survives
            assertThat(snap.getNodes().get(NODE_ID).completed()).isGreaterThan(0);
        }
    }

    // ========================================================================
    // SBS: epoch active state persists through reruns
    // ========================================================================

    @Nested
    @DisplayName("Epoch stays active (no close/prune in SBS)")
    class EpochStaysActive {

        @Test
        @DisplayName("Epoch remains active after all nodes complete")
        void epochRemainsActiveAfterCompletion() {
            StateSnapshot snap = createSnapshotWithReadyNodes(1);
            snap = snap.markNodeCompleted(TRIGGER_ID, TRIGGER_ID, 1);
            snap = snap.markNodeCompleted(TRIGGER_ID, NODE_ID, 1);

            // Epoch stays active - NOT closed
            assertThat(snap.getDags().get(TRIGGER_ID).getActiveEpochs()).contains(1);
            assertThat(snap.hasAnyActiveEpoch()).isTrue();
        }

        @Test
        @DisplayName("resetDag preserves epoch active state")
        void resetDagPreservesActiveState() {
            StateSnapshot snap = createSnapshotWithReadyNodes(1);
            snap = snap.markNodeCompleted(TRIGGER_ID, TRIGGER_ID, 1);
            snap = snap.markNodeCompleted(TRIGGER_ID, NODE_ID, 1);

            // Reset for rerun
            snap = snap.resetDag(Set.of(NODE_ID));

            // Epoch still active
            assertThat(snap.getDags().get(TRIGGER_ID).getActiveEpochs()).contains(1);
        }

        @Test
        @DisplayName("Flat views work correctly with active epoch after rerun")
        void flatViewsWorkAfterRerun() {
            StateSnapshot snap = createSnapshotWithReadyNodes(1);
            snap = snap.markNodeCompleted(TRIGGER_ID, TRIGGER_ID, 1);
            snap = snap.markNodeCompleted(TRIGGER_ID, NODE_ID, 1);

            // Reset node
            snap = snap.resetDag(Set.of(NODE_ID)).addReadyNode(NODE_ID);

            // Flat views: trigger still completed, node is ready
            assertThat(snap.getCompletedNodeIds()).contains(TRIGGER_ID);
            assertThat(snap.getCompletedNodeIds()).doesNotContain(NODE_ID);
            assertThat(snap.getReadyNodeIds()).contains(NODE_ID);
        }
    }
}
