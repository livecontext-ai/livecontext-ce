package com.apimarketplace.orchestrator.domain.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("StateSnapshot - Parallel Epoch Support")
class StateSnapshotParallelEpochTest {

    private static final String TRIGGER = "trigger:webhook";

    /**
     * Helper: create a snapshot with a DAG that has the specified active epochs.
     * Each epoch gets a fresh EpochState.
     */
    private StateSnapshot snapshotWithActiveEpochs(int... epochs) {
        StateSnapshot snapshot = StateSnapshot.empty();
        DagState dagState = DagState.initial();
        for (int e : epochs) {
            dagState = dagState.advanceEpoch(e);
        }
        return snapshot.withDagState(TRIGGER, dagState);
    }

    // ========================================================================
    // EPOCH-SCOPED MUTATIONS
    // ========================================================================

    @Nested
    @DisplayName("Epoch-scoped markNodeCompleted(triggerId, nodeId, epoch)")
    class EpochScopedCompletedTests {

        @Test
        @DisplayName("Should mark node completed in the specific epoch only")
        void shouldMarkInSpecificEpoch() {
            StateSnapshot snapshot = snapshotWithActiveEpochs(1, 2);

            StateSnapshot updated = snapshot.markNodeCompleted(TRIGGER, "mcp:step1", 1);

            // Epoch 1 should have the completed node
            assertTrue(updated.getEpochState(TRIGGER, 1).getCompletedNodeIds().contains("mcp:step1"));
            // Epoch 2 should NOT have it
            assertFalse(updated.getEpochState(TRIGGER, 2).getCompletedNodeIds().contains("mcp:step1"));
        }

        @Test
        @DisplayName("Should increment global NodeCounts")
        void shouldIncrementGlobalCounts() {
            StateSnapshot snapshot = snapshotWithActiveEpochs(1);

            StateSnapshot updated = snapshot
                    .markNodeCompleted(TRIGGER, "mcp:step1", 1)
                    .markNodeCompleted(TRIGGER, "mcp:step1", 1); // 2nd time same epoch

            assertEquals(2, updated.getNodeCounts("mcp:step1").completed());
        }

        @Test
        @DisplayName("Should handle non-existent epoch gracefully (creates fresh)")
        void shouldHandleNonExistentEpoch() {
            StateSnapshot snapshot = snapshotWithActiveEpochs(1);

            // Mark completed in epoch 99 (doesn't exist yet)
            StateSnapshot updated = snapshot.markNodeCompleted(TRIGGER, "mcp:step1", 99);

            assertTrue(updated.getEpochState(TRIGGER, 99).getCompletedNodeIds().contains("mcp:step1"));
        }
    }

    @Nested
    @DisplayName("Epoch-scoped markNodeFailed(triggerId, nodeId, epoch)")
    class EpochScopedFailedTests {

        @Test
        @DisplayName("Should mark failed in specific epoch")
        void shouldMarkFailedInSpecificEpoch() {
            StateSnapshot snapshot = snapshotWithActiveEpochs(1, 2);

            StateSnapshot updated = snapshot.markNodeFailed(TRIGGER, "mcp:step1", 2);

            assertFalse(updated.getEpochState(TRIGGER, 1).getFailedNodeIds().contains("mcp:step1"));
            assertTrue(updated.getEpochState(TRIGGER, 2).getFailedNodeIds().contains("mcp:step1"));
        }
    }

    @Nested
    @DisplayName("Epoch-scoped markNodeSkipped(triggerId, nodeId, epoch)")
    class EpochScopedSkippedTests {

        @Test
        @DisplayName("Should mark skipped in specific epoch")
        void shouldMarkSkippedInSpecificEpoch() {
            StateSnapshot snapshot = snapshotWithActiveEpochs(1, 2);

            StateSnapshot updated = snapshot.markNodeSkipped(TRIGGER, "mcp:step1", 1);

            assertTrue(updated.getEpochState(TRIGGER, 1).getSkippedNodeIds().contains("mcp:step1"));
            assertFalse(updated.getEpochState(TRIGGER, 2).getSkippedNodeIds().contains("mcp:step1"));
        }
    }

    @Nested
    @DisplayName("Epoch-scoped addRunningNode(triggerId, nodeId, epoch)")
    class EpochScopedRunningTests {

        @Test
        @DisplayName("Should add running in specific epoch")
        void shouldAddRunningInSpecificEpoch() {
            StateSnapshot snapshot = snapshotWithActiveEpochs(1, 2);

            StateSnapshot updated = snapshot.addRunningNode(TRIGGER, "mcp:step1", 1);

            assertTrue(updated.getEpochState(TRIGGER, 1).getRunningNodeIds().contains("mcp:step1"));
            assertFalse(updated.getEpochState(TRIGGER, 2).getRunningNodeIds().contains("mcp:step1"));
        }
    }

    @Nested
    @DisplayName("Epoch-scoped readyNode mutations")
    class EpochScopedReadyTests {

        @Test
        @DisplayName("addReadyNode should add to specific epoch")
        void addReadyShouldBeEpochScoped() {
            StateSnapshot snapshot = snapshotWithActiveEpochs(1, 2);

            StateSnapshot updated = snapshot.addReadyNode(TRIGGER, "mcp:step2", 2);

            assertFalse(updated.getEpochState(TRIGGER, 1).getReadyNodeIds().contains("mcp:step2"));
            assertTrue(updated.getEpochState(TRIGGER, 2).getReadyNodeIds().contains("mcp:step2"));
        }

        @Test
        @DisplayName("removeReadyNode should remove from specific epoch")
        void removeReadyShouldBeEpochScoped() {
            StateSnapshot snapshot = snapshotWithActiveEpochs(1, 2)
                    .addReadyNode(TRIGGER, "mcp:step1", 1)
                    .addReadyNode(TRIGGER, "mcp:step1", 2);

            StateSnapshot updated = snapshot.removeReadyNode(TRIGGER, "mcp:step1", 1);

            assertFalse(updated.getEpochState(TRIGGER, 1).getReadyNodeIds().contains("mcp:step1"));
            assertTrue(updated.getEpochState(TRIGGER, 2).getReadyNodeIds().contains("mcp:step1"));
        }
    }

    @Nested
    @DisplayName("Epoch-scoped signal mutations")
    class EpochScopedSignalTests {

        @Test
        @DisplayName("markNodeAwaitingSignal should be epoch-scoped")
        void awaitingSignalShouldBeEpochScoped() {
            StateSnapshot snapshot = snapshotWithActiveEpochs(1, 2);

            StateSnapshot updated = snapshot.markNodeAwaitingSignal(TRIGGER, "interface:form", 1);

            assertTrue(updated.getEpochState(TRIGGER, 1).getAwaitingSignalNodeIds().contains("interface:form"));
            assertFalse(updated.getEpochState(TRIGGER, 2).getAwaitingSignalNodeIds().contains("interface:form"));
        }

        @Test
        @DisplayName("resolveAwaitingSignal should be epoch-scoped")
        void resolveSignalShouldBeEpochScoped() {
            StateSnapshot snapshot = snapshotWithActiveEpochs(1)
                    .markNodeAwaitingSignal(TRIGGER, "interface:form", 1);

            StateSnapshot resolved = snapshot.resolveAwaitingSignal(TRIGGER, "interface:form", 1);

            assertFalse(resolved.getEpochState(TRIGGER, 1).getAwaitingSignalNodeIds().contains("interface:form"));
            assertTrue(resolved.getEpochState(TRIGGER, 1).getCompletedNodeIds().contains("interface:form"));
            assertEquals(1, resolved.getNodeCounts("interface:form").completed());
        }
    }

    // ========================================================================
    // FLAT VIEW (computeFlatSet)
    // ========================================================================

    @Nested
    @DisplayName("computeFlatSet - union of all active epochs")
    class FlatViewTests {

        @Test
        @DisplayName("Flat completedNodeIds should union all active epochs")
        void completedShouldUnionAllActive() {
            StateSnapshot snapshot = snapshotWithActiveEpochs(1, 2)
                    .markNodeCompleted(TRIGGER, "mcp:step_a", 1)
                    .markNodeCompleted(TRIGGER, "mcp:step_b", 2);

            Set<String> flatCompleted = snapshot.getCompletedNodeIds();
            assertTrue(flatCompleted.contains("mcp:step_a"));
            assertTrue(flatCompleted.contains("mcp:step_b"));
        }

        @Test
        @DisplayName("Flat readyNodeIds should union all active epochs")
        void readyShouldUnionAllActive() {
            StateSnapshot snapshot = snapshotWithActiveEpochs(1, 2)
                    .addReadyNode(TRIGGER, "mcp:step1", 1)
                    .addReadyNode(TRIGGER, "mcp:step2", 2);

            Set<String> flatReady = snapshot.getReadyNodeIds();
            assertTrue(flatReady.contains("mcp:step1"));
            assertTrue(flatReady.contains("mcp:step2"));
        }

        @Test
        @DisplayName("Closed epoch should NOT contribute to flat view")
        void closedEpochShouldNotContributeToFlat() {
            StateSnapshot snapshot = snapshotWithActiveEpochs(1, 2)
                    .markNodeCompleted(TRIGGER, "mcp:step_a", 1)
                    .markNodeCompleted(TRIGGER, "mcp:step_b", 2);

            // Close epoch 1
            StateSnapshot closed = snapshot.closeAndPruneEpochForDag(TRIGGER, 1);

            Set<String> flatCompleted = closed.getCompletedNodeIds();
            // step_a was in epoch 1 which is now closed → no longer active
            assertFalse(flatCompleted.contains("mcp:step_a"));
            // step_b is still in active epoch 2
            assertTrue(flatCompleted.contains("mcp:step_b"));
        }

        @Test
        @DisplayName("Same node in multiple epochs should appear once in flat view")
        void sameNodeInMultipleEpochsShouldAppearOnce() {
            StateSnapshot snapshot = snapshotWithActiveEpochs(1, 2)
                    .markNodeCompleted(TRIGGER, TRIGGER, 1)
                    .markNodeCompleted(TRIGGER, TRIGGER, 2);

            // Should just contain the trigger node once (it's a set)
            assertTrue(snapshot.getCompletedNodeIds().contains(TRIGGER));
        }

        @Test
        @DisplayName("Multiple DAGs with active epochs should all contribute")
        void multipleDagsWithActiveEpochsShouldContribute() {
            String trigger2 = "trigger:chat";

            StateSnapshot snapshot = snapshotWithActiveEpochs(1)
                    .markNodeCompleted(TRIGGER, "mcp:step_a", 1);

            // Add a second DAG with its own active epoch
            DagState dag2 = DagState.initial().advanceEpoch(1);
            snapshot = snapshot.withDagState(trigger2, dag2)
                    .markNodeCompleted(trigger2, "mcp:step_b", 1);

            Set<String> flat = snapshot.getCompletedNodeIds();
            assertTrue(flat.contains("mcp:step_a"));
            assertTrue(flat.contains("mcp:step_b"));
        }

        @Test
        @DisplayName("Flat runningNodeIds should union all active epochs")
        void runningShouldUnionAllActive() {
            StateSnapshot snapshot = snapshotWithActiveEpochs(1, 2)
                    .addRunningNode(TRIGGER, "mcp:step1", 1)
                    .addRunningNode(TRIGGER, "mcp:step2", 2);

            Set<String> flatRunning = snapshot.getRunningNodeIds();
            assertTrue(flatRunning.contains("mcp:step1"));
            assertTrue(flatRunning.contains("mcp:step2"));
        }

        @Test
        @DisplayName("Flat awaitingSignalNodeIds should union all active epochs")
        void awaitingShouldUnionAllActive() {
            StateSnapshot snapshot = snapshotWithActiveEpochs(1, 2)
                    .markNodeAwaitingSignal(TRIGGER, "interface:form1", 1)
                    .markNodeAwaitingSignal(TRIGGER, "interface:form2", 2);

            Set<String> flatAwaiting = snapshot.getAwaitingSignalNodeIds();
            assertTrue(flatAwaiting.contains("interface:form1"));
            assertTrue(flatAwaiting.contains("interface:form2"));
        }
    }

    // ========================================================================
    // closeAndPruneEpochForDag
    // ========================================================================

    @Nested
    @DisplayName("closeAndPruneEpochForDag()")
    class CloseEpochForDagTests {

        @Test
        @DisplayName("Should close epoch and update flat views")
        void shouldCloseAndUpdateFlatViews() {
            StateSnapshot snapshot = snapshotWithActiveEpochs(1, 2)
                    .addRunningNode(TRIGGER, "mcp:step1", 1)
                    .addRunningNode(TRIGGER, "mcp:step2", 2);

            StateSnapshot closed = snapshot.closeAndPruneEpochForDag(TRIGGER, 1);

            // Flat running should only have epoch 2's node
            assertFalse(closed.getRunningNodeIds().contains("mcp:step1"));
            assertTrue(closed.getRunningNodeIds().contains("mcp:step2"));
        }

        @Test
        @DisplayName("Should not affect other DAGs")
        void shouldNotAffectOtherDags() {
            String trigger2 = "trigger:chat";

            StateSnapshot snapshot = snapshotWithActiveEpochs(1);

            DagState dag2 = DagState.initial().advanceEpoch(1);
            snapshot = snapshot.withDagState(trigger2, dag2)
                    .addRunningNode(trigger2, "mcp:chat_step", 1);

            // Close epoch 1 for TRIGGER only
            StateSnapshot closed = snapshot.closeAndPruneEpochForDag(TRIGGER, 1);

            // trigger2's epoch should still be active
            assertTrue(closed.getDagState(trigger2).hasActiveEpochs());
            assertTrue(closed.getRunningNodeIds().contains("mcp:chat_step"));
        }
    }

    // ========================================================================
    // hasAnyActiveEpoch
    // ========================================================================

    @Nested
    @DisplayName("hasAnyActiveEpoch()")
    class HasAnyActiveEpochTests {

        @Test
        @DisplayName("Should return true when any DAG has active epochs")
        void shouldReturnTrueWhenAnyActive() {
            StateSnapshot snapshot = snapshotWithActiveEpochs(1);
            assertTrue(snapshot.hasAnyActiveEpoch());
        }

        @Test
        @DisplayName("Should return false when no DAG has active epochs")
        void shouldReturnFalseWhenNone() {
            StateSnapshot snapshot = snapshotWithActiveEpochs(1)
                    .closeAndPruneEpochForDag(TRIGGER, 1);
            assertFalse(snapshot.hasAnyActiveEpoch());
        }

        @Test
        @DisplayName("Should return false for empty snapshot")
        void shouldReturnFalseForEmpty() {
            assertFalse(StateSnapshot.empty().hasAnyActiveEpoch());
        }
    }

    // ========================================================================
    // MIXED OPERATIONS (epoch-scoped + legacy)
    // ========================================================================

    @Nested
    @DisplayName("Mixed epoch-scoped and legacy operations")
    class MixedOperationTests {

        @Test
        @DisplayName("Legacy markNodeCompleted should use currentEpochState")
        void legacyMarkShouldUseCurrentEpoch() {
            // Set up a single-epoch scenario
            StateSnapshot snapshot = StateSnapshot.empty();
            DagState dagState = DagState.initial().advanceEpoch(1);
            snapshot = snapshot.withDagState(TRIGGER, dagState);

            // Use legacy (non-epoch-scoped) API
            StateSnapshot updated = snapshot.markNodeCompleted(TRIGGER, "mcp:step1");

            // Should have been added to current epoch (1)
            assertTrue(updated.getEpochState(TRIGGER, 1).getCompletedNodeIds().contains("mcp:step1"));
        }

        @Test
        @DisplayName("Epoch-scoped operations should not interfere with each other")
        void epochScopedShouldNotInterfere() {
            StateSnapshot snapshot = snapshotWithActiveEpochs(1, 2);

            // Epoch 1: step1 completed, step2 running
            snapshot = snapshot
                    .markNodeCompleted(TRIGGER, "mcp:step1", 1)
                    .addRunningNode(TRIGGER, "mcp:step2", 1);

            // Epoch 2: step1 running, step2 ready
            snapshot = snapshot
                    .addRunningNode(TRIGGER, "mcp:step1", 2)
                    .addReadyNode(TRIGGER, "mcp:step2", 2);

            // Verify epoch 1 state
            EpochState e1 = snapshot.getEpochState(TRIGGER, 1);
            assertTrue(e1.getCompletedNodeIds().contains("mcp:step1"));
            assertTrue(e1.getRunningNodeIds().contains("mcp:step2"));
            assertFalse(e1.getRunningNodeIds().contains("mcp:step1"));

            // Verify epoch 2 state
            EpochState e2 = snapshot.getEpochState(TRIGGER, 2);
            assertTrue(e2.getRunningNodeIds().contains("mcp:step1"));
            assertTrue(e2.getReadyNodeIds().contains("mcp:step2"));
            assertFalse(e2.getCompletedNodeIds().contains("mcp:step1"));
        }
    }

    // ========================================================================
    // PER-EPOCH GETTERS
    // ========================================================================

    @Nested
    @DisplayName("Per-epoch getters")
    class PerEpochGetterTests {

        @Test
        @DisplayName("getCompletedNodeIds(triggerId, epoch) should return epoch-specific data")
        void getCompletedByEpoch() {
            StateSnapshot snapshot = snapshotWithActiveEpochs(1, 2)
                    .markNodeCompleted(TRIGGER, "mcp:a", 1)
                    .markNodeCompleted(TRIGGER, "mcp:b", 2);

            assertEquals(Set.of("mcp:a"), snapshot.getCompletedNodeIds(TRIGGER, 1));
            assertEquals(Set.of("mcp:b"), snapshot.getCompletedNodeIds(TRIGGER, 2));
        }

        @Test
        @DisplayName("getReadyNodeIds(triggerId, epoch) should return epoch-specific data")
        void getReadyByEpoch() {
            StateSnapshot snapshot = snapshotWithActiveEpochs(1, 2)
                    .addReadyNode(TRIGGER, "mcp:x", 1)
                    .addReadyNode(TRIGGER, "mcp:y", 2);

            assertEquals(Set.of("mcp:x"), snapshot.getReadyNodeIds(TRIGGER, 1));
            assertEquals(Set.of("mcp:y"), snapshot.getReadyNodeIds(TRIGGER, 2));
        }

        @Test
        @DisplayName("getEpochState for non-existent epoch should return fresh")
        void nonExistentEpochShouldReturnFresh() {
            StateSnapshot snapshot = snapshotWithActiveEpochs(1);
            EpochState es = snapshot.getEpochState(TRIGGER, 99);
            assertNotNull(es);
            assertTrue(es.isEmpty());
        }
    }

    // ========================================================================
    // EDGE CASES
    // ========================================================================

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Many parallel epochs should all be tracked in flat view")
        void manyParallelEpochsAllTracked() {
            StateSnapshot snapshot = StateSnapshot.empty();
            DagState dagState = DagState.initial();
            for (int i = 1; i <= 10; i++) {
                dagState = dagState.advanceEpoch(i);
            }
            snapshot = snapshot.withDagState(TRIGGER, dagState);

            // Add a unique node to each epoch
            for (int i = 1; i <= 10; i++) {
                snapshot = snapshot.markNodeCompleted(TRIGGER, "mcp:step_" + i, i);
            }

            // Flat view should have all 10 nodes
            Set<String> flat = snapshot.getCompletedNodeIds();
            assertEquals(10, flat.size());
            for (int i = 1; i <= 10; i++) {
                assertTrue(flat.contains("mcp:step_" + i));
            }
        }

        @Test
        @DisplayName("Closing epochs progressively should shrink flat view while active epochs remain")
        void closingProgressivelyShouldShrinkFlat() {
            StateSnapshot snapshot = snapshotWithActiveEpochs(1, 2, 3)
                    .addReadyNode(TRIGGER, "mcp:a", 1)
                    .addReadyNode(TRIGGER, "mcp:b", 2)
                    .addReadyNode(TRIGGER, "mcp:c", 3);

            assertEquals(3, snapshot.getReadyNodeIds().size());

            snapshot = snapshot.closeAndPruneEpochForDag(TRIGGER, 1);
            assertEquals(2, snapshot.getReadyNodeIds().size());
            assertFalse(snapshot.getReadyNodeIds().contains("mcp:a"));

            snapshot = snapshot.closeAndPruneEpochForDag(TRIGGER, 2);
            assertEquals(1, snapshot.getReadyNodeIds().size());
            assertTrue(snapshot.getReadyNodeIds().contains("mcp:c"));

            // When all active epochs are closed+pruned, epoch data is removed.
            // computeFlatSet falls back to currentEpochState() which returns fresh (empty)
            // since the epoch was pruned from the map.
            snapshot = snapshot.closeAndPruneEpochForDag(TRIGGER, 3);
            // Epoch 3 pruned → currentEpochState() returns fresh → flat view is empty
            assertTrue(snapshot.getReadyNodeIds().isEmpty());
        }

        @Test
        @DisplayName("With no active epochs after pruning, flat view is empty")
        void noActiveEpochsAfterPruning_flatViewEmpty() {
            StateSnapshot snapshot = snapshotWithActiveEpochs(1)
                    .markNodeCompleted(TRIGGER, "mcp:step1", 1)
                    .closeAndPruneEpochForDag(TRIGGER, 1);

            // After pruning, epoch 1 is removed from epochs map.
            // currentEpochState() returns fresh (empty) since epoch 1 is pruned.
            assertNull(snapshot.getDags().get(TRIGGER).getEpochState(1));
            // Flat view is empty because no active epochs and currentEpochState is fresh
            assertTrue(snapshot.getCompletedNodeIds().isEmpty());
        }

        @Test
        @DisplayName("findDagContaining should find nodes in active epochs")
        void findDagContainingShouldSearchActiveEpochs() {
            StateSnapshot snapshot = snapshotWithActiveEpochs(1, 2)
                    .addRunningNode(TRIGGER, "mcp:step1", 2);

            // Use legacy API which internally calls findDagContaining
            StateSnapshot updated = snapshot.markNodeCompleted("mcp:step1");

            // Should have found it in TRIGGER's epoch 2
            assertTrue(updated.getCompletedNodeIds().contains("mcp:step1"));
        }
    }
}
