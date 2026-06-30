package com.apimarketplace.orchestrator.domain.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("StateSnapshot")
class StateSnapshotTest {

    @Nested
    @DisplayName("empty()")
    class EmptyTests {

        @Test
        @DisplayName("Should create empty snapshot")
        void shouldCreateEmpty() {
            StateSnapshot snapshot = StateSnapshot.empty();
            assertTrue(snapshot.isEmpty());
            assertEquals(3, snapshot.getVersion());
            assertTrue(snapshot.getCompletedNodeIds().isEmpty());
            assertTrue(snapshot.getFailedNodeIds().isEmpty());
            assertTrue(snapshot.getSkippedNodeIds().isEmpty());
            assertTrue(snapshot.getRunningNodeIds().isEmpty());
            assertTrue(snapshot.getReadyNodeIds().isEmpty());
            assertTrue(snapshot.getNodes().isEmpty());
            assertTrue(snapshot.getEdges().isEmpty());
            assertTrue(snapshot.getDecisionBranches().isEmpty());
            assertTrue(snapshot.getLoops().isEmpty());
            assertTrue(snapshot.getSplits().isEmpty());
            assertTrue(snapshot.getAwaitingSignalNodeIds().isEmpty());
            assertNotNull(snapshot.getLastUpdated());
        }
    }

    @Nested
    @DisplayName("getTerminalNodeIds()")
    class TerminalNodeIdsTests {

        // Two distinct DAGs so a scoped query can be proven to EXCLUDE the other DAG's node.
        private StateSnapshot twoDagSnapshot() {
            return StateSnapshot.empty()
                .markNodeCompleted("trigger:webhook", "core:w_done")
                .markNodeFailed("trigger:webhook", "core:w_fail")
                .markNodeSkipped("trigger:webhook", "core:w_skip")
                .markNodeCompleted("trigger:chat", "core:c_done");
        }

        @Test
        @DisplayName("epoch-scoped: returns COMPLETED union FAILED union SKIPPED for the (trigger, epoch), excluding other DAGs")
        void epochScopedUnionExcludesOtherDag() {
            StateSnapshot s = twoDagSnapshot();

            Set<String> terminal = s.getTerminalNodeIds("trigger:webhook", 0);

            assertTrue(terminal.contains("core:w_done"));
            assertTrue(terminal.contains("core:w_fail"));
            assertTrue(terminal.contains("core:w_skip"));
            // Scoped to trigger:webhook epoch 0 - the chat DAG's terminal node must NOT leak in.
            // This also pins the epoch >= 0 gate: under a `> 0` gate, epoch 0 would fall through to
            // the cross-DAG flat union and wrongly include core:c_done (regression guard).
            assertFalse(terminal.contains("core:c_done"));
        }

        @Test
        @DisplayName("flat fallback: a null trigger unions terminal nodes across all DAGs/epochs")
        void nullTriggerFlatFallback() {
            StateSnapshot s = twoDagSnapshot();

            Set<String> terminal = s.getTerminalNodeIds(null, 0);

            assertTrue(terminal.contains("core:w_done"));
            assertTrue(terminal.contains("core:w_fail"));
            assertTrue(terminal.contains("core:w_skip"));
            assertTrue(terminal.contains("core:c_done"));
        }

        @Test
        @DisplayName("flat fallback: a negative epoch unions terminal nodes across all DAGs/epochs")
        void negativeEpochFlatFallback() {
            StateSnapshot s = twoDagSnapshot();

            Set<String> terminal = s.getTerminalNodeIds("trigger:webhook", -1);

            assertTrue(terminal.contains("core:c_done"));
        }
    }

    @Nested
    @DisplayName("Node status mutations")
    class NodeStatusMutationTests {

        @Test
        @DisplayName("markNodeCompleted should add to completed and remove from running")
        void markNodeCompletedShouldWork() {
            StateSnapshot snapshot = StateSnapshot.empty();
            StateSnapshot updated = snapshot.markNodeCompleted("mcp:step1");

            assertTrue(updated.getCompletedNodeIds().contains("mcp:step1"));
            assertFalse(updated.getRunningNodeIds().contains("mcp:step1"));
            assertEquals(1, updated.getNodeCounts("mcp:step1").completed());
        }

        @Test
        @DisplayName("markNodeFailed should add to failed and remove from running")
        void markNodeFailedShouldWork() {
            StateSnapshot snapshot = StateSnapshot.empty();
            StateSnapshot updated = snapshot.markNodeFailed("mcp:step1");

            assertTrue(updated.getFailedNodeIds().contains("mcp:step1"));
            assertEquals(1, updated.getNodeCounts("mcp:step1").failed());
        }

        @Test
        @DisplayName("markNodeSkipped should add to skipped")
        void markNodeSkippedShouldWork() {
            StateSnapshot snapshot = StateSnapshot.empty();
            StateSnapshot updated = snapshot.markNodeSkipped("mcp:step1");

            assertTrue(updated.getSkippedNodeIds().contains("mcp:step1"));
            assertEquals(1, updated.getNodeCounts("mcp:step1").skipped());
        }

        @Test
        @DisplayName("markNodeCompleted with timing should accumulate in NodeCounts")
        void markNodeCompletedWithTimingShouldAccumulate() {
            StateSnapshot snapshot = StateSnapshot.empty();
            StateSnapshot updated = snapshot.markNodeCompleted("trigger:default", "mcp:step1", 0, 150L);

            assertTrue(updated.getCompletedNodeIds().contains("mcp:step1"));
            StateSnapshot.NodeCounts counts = updated.getNodeCounts("mcp:step1");
            assertEquals(1, counts.completed());
            assertEquals(150L, counts.totalExecutionTimeMs());
            assertTrue(counts.lastEndTimeMs() > 0);
        }

        @Test
        @DisplayName("markNodeFailed with timing should accumulate in NodeCounts")
        void markNodeFailedWithTimingShouldAccumulate() {
            StateSnapshot snapshot = StateSnapshot.empty();
            StateSnapshot updated = snapshot.markNodeFailed("trigger:default", "mcp:step1", 0, 300L);

            assertTrue(updated.getFailedNodeIds().contains("mcp:step1"));
            StateSnapshot.NodeCounts counts = updated.getNodeCounts("mcp:step1");
            assertEquals(1, counts.failed());
            assertEquals(300L, counts.totalExecutionTimeMs());
            assertTrue(counts.lastEndTimeMs() > 0);
        }

        @Test
        @DisplayName("incrementNodeCountsOnly should increment skipped without touching EpochState")
        void incrementNodeCountsOnlyShouldNotTouchEpochState() {
            StateSnapshot snapshot = StateSnapshot.empty();
            // First mark the node completed (in EpochState + NodeCounts)
            StateSnapshot withCompleted = snapshot.markNodeCompleted("trigger:default", "mcp:step1", 0, 100L);
            assertEquals(1, withCompleted.getNodeCounts("mcp:step1").completed());

            // Now increment only NodeCounts.skipped by 5
            StateSnapshot updated = withCompleted.incrementNodeCountsOnly("mcp:step1", "SKIPPED", 5);

            // NodeCounts should reflect the batch increment
            StateSnapshot.NodeCounts counts = updated.getNodeCounts("mcp:step1");
            assertEquals(5, counts.skipped());
            assertEquals(1, counts.completed());

            // EpochState should NOT contain the node in skippedNodeIds
            assertFalse(updated.getSkippedNodeIds().contains("mcp:step1"));
            // But it should still be in completedNodeIds (from the first mark)
            assertTrue(updated.getCompletedNodeIds().contains("mcp:step1"));
        }

        @Test
        @DisplayName("incrementNodeCountsOnly with count=0 should return same snapshot")
        void incrementNodeCountsOnlyZeroCountReturnsIdentity() {
            StateSnapshot snapshot = StateSnapshot.empty();
            StateSnapshot updated = snapshot.incrementNodeCountsOnly("mcp:step1", "SKIPPED", 0);
            assertSame(snapshot, updated);
        }

        /**
         * Regression - production runId run_<id> (2026-05-02): Gmail
         * Auto-Labeler split-async classify produced {@code NodeCounts.completed=5} for
         * 4 actual items because {@code recordSplitAggregateIfMissing} called the regular
         * {@code markNodeCompleted}, which re-incremented {@code NodeCounts.completed} on
         * top of the 4 per-item {@code incrementNodeCountsOnly} calls. The fix is the new
         * {@code markNodeCompletedEpochOnly} which updates {@code EpochState} only.
         */
        @Test
        @DisplayName("markNodeCompletedEpochOnly should update EpochState without re-incrementing NodeCounts")
        void splitAsyncSealDoesNotInflateCompletedCount() {
            StateSnapshot snapshot = StateSnapshot.empty();
            // Per-item completion path: 4 items each suppress global mark and only
            // increment NodeCounts.
            StateSnapshot afterPerItem = snapshot
                .incrementNodeCountsOnly("agent:classify", "COMPLETED", 1)
                .incrementNodeCountsOnly("agent:classify", "COMPLETED", 1)
                .incrementNodeCountsOnly("agent:classify", "COMPLETED", 1)
                .incrementNodeCountsOnly("agent:classify", "COMPLETED", 1);
            assertEquals(4, afterPerItem.getNodeCounts("agent:classify").completed(),
                "After 4 per-item completions NodeCounts.completed must be 4");
            // EpochState should NOT contain the node yet - per-item path suppressed the global mark.
            assertFalse(afterPerItem.getCompletedNodeIds().contains("agent:classify"),
                "Per-item path must not write EpochState global mark");

            // Barrier seal: mark the node completed in the EpochState only.
            StateSnapshot afterSeal =
                afterPerItem.markNodeCompletedEpochOnly("trigger:default", "agent:classify", 0);

            assertEquals(4, afterSeal.getNodeCounts("agent:classify").completed(),
                "Seal must NOT re-increment NodeCounts on top of per-item completions");
            // Epoch-scoped getter - robust to flat-view seeding changes in DagState.initial().
            assertTrue(afterSeal.getCompletedNodeIds("trigger:default", 0).contains("agent:classify"),
                "Seal must add the node to EpochState.completedNodeIds for the targeted (triggerId, epoch)");
        }

        @Test
        @DisplayName("markNodeFailedEpochOnly should update EpochState without re-incrementing NodeCounts")
        void markNodeFailedEpochOnlyMatchesCompletedTwin() {
            StateSnapshot snapshot = StateSnapshot.empty()
                .incrementNodeCountsOnly("agent:classify", "FAILED", 1)
                .incrementNodeCountsOnly("agent:classify", "FAILED", 1);
            StateSnapshot afterSeal =
                snapshot.markNodeFailedEpochOnly("trigger:default", "agent:classify", 0);
            assertEquals(2, afterSeal.getNodeCounts("agent:classify").failed());
            assertTrue(afterSeal.getEpochState("trigger:default", 0).getFailedNodeIds().contains("agent:classify"));
            assertEquals(0, afterSeal.getNodeCounts("agent:classify").completed(),
                "Failed seal must not touch the completed bucket");
        }

        @Test
        @DisplayName("addReadyNode should add node to ready set")
        void addReadyNodeShouldWork() {
            StateSnapshot snapshot = StateSnapshot.empty();
            StateSnapshot updated = snapshot.addReadyNode("mcp:step1");

            assertTrue(updated.getReadyNodeIds().contains("mcp:step1"));
            assertTrue(updated.getReadyNodes().contains("mcp:step1")); // alias
        }

        @Test
        @DisplayName("removeReadyNode should remove node from ready set")
        void removeReadyNodeShouldWork() {
            StateSnapshot snapshot = StateSnapshot.empty()
                .addReadyNode("mcp:step1");
            StateSnapshot updated = snapshot.removeReadyNode("mcp:step1");

            assertFalse(updated.getReadyNodeIds().contains("mcp:step1"));
        }
    }

    @Nested
    @DisplayName("Edge mutations")
    class EdgeMutationTests {

        @Test
        @DisplayName("incrementEdge should update edge counts")
        void incrementEdgeShouldWork() {
            StateSnapshot snapshot = StateSnapshot.empty();
            StateSnapshot updated = snapshot.incrementEdge("a", "b", "COMPLETED");

            StateSnapshot.EdgeCounts counts = updated.getEdgeCounts("a", "b");
            assertEquals(1, counts.completed());
        }

        @Test
        @DisplayName("Should increment edge multiple times")
        void shouldIncrementMultiple() {
            StateSnapshot snapshot = StateSnapshot.empty()
                .incrementEdge("a", "b", "COMPLETED")
                .incrementEdge("a", "b", "COMPLETED")
                .incrementEdge("a", "b", "SKIPPED");

            StateSnapshot.EdgeCounts counts = snapshot.getEdgeCounts("a", "b");
            assertEquals(2, counts.completed());
            assertEquals(1, counts.skipped());
        }
    }

    @Nested
    @DisplayName("Decision branches")
    class DecisionBranchTests {

        @Test
        @DisplayName("Should record and retrieve decision branches")
        void shouldRecordAndRetrieve() {
            StateSnapshot snapshot = StateSnapshot.empty()
                .recordDecisionBranch("core:d1", "if");

            Set<String> branches = snapshot.getDecisionBranches("core:d1");
            assertTrue(branches.contains("if"));
        }

        @Test
        @DisplayName("Should return empty set for unknown node")
        void shouldReturnEmptyForUnknown() {
            Set<String> branches = StateSnapshot.empty().getDecisionBranches("unknown");
            assertTrue(branches.isEmpty());
        }
    }

    @Nested
    @DisplayName("Loop state")
    class LoopStateTests {

        @Test
        @DisplayName("Should update and get loop state")
        void shouldUpdateAndGetLoopState() {
            StateSnapshot.LoopState loopState = StateSnapshot.LoopState.initial(5);
            StateSnapshot snapshot = StateSnapshot.empty().updateLoopState("loop1", loopState);

            StateSnapshot.LoopState retrieved = snapshot.getLoopState("loop1");
            assertNotNull(retrieved);
            assertEquals(0, retrieved.currentIndex());
            assertEquals(5, retrieved.totalItems());
            assertFalse(retrieved.isComplete());
        }

        @Test
        @DisplayName("LoopState nextIteration should increment index")
        void nextIterationShouldIncrement() {
            StateSnapshot.LoopState state = StateSnapshot.LoopState.initial(3);
            StateSnapshot.LoopState next = state.nextIteration();
            assertEquals(1, next.currentIndex());
        }

        @Test
        @DisplayName("LoopState complete should set status")
        void completeShouldSetStatus() {
            StateSnapshot.LoopState state = StateSnapshot.LoopState.initial(3);
            StateSnapshot.LoopState completed = state.complete();
            assertTrue(completed.isComplete());
        }

        @Test
        @DisplayName("LoopState should be complete when index >= total")
        void shouldBeCompleteWhenIndexReachesTotal() {
            StateSnapshot.LoopState state = new StateSnapshot.LoopState(3, 3, "ITERATING");
            assertTrue(state.isComplete());
        }
    }

    @Nested
    @DisplayName("Split state")
    class SplitStateTests {

        @Test
        @DisplayName("Should update and get split state")
        void shouldUpdateAndGetSplitState() {
            StateSnapshot.SplitState splitState = StateSnapshot.SplitState.initial(10);
            StateSnapshot snapshot = StateSnapshot.empty().updateSplitState("split1", splitState);

            StateSnapshot.SplitState retrieved = snapshot.getSplitState("split1");
            assertNotNull(retrieved);
            assertEquals(10, retrieved.itemCount());
            assertEquals(0, retrieved.completedCount());
            assertEquals(10, retrieved.pendingCount());
            assertFalse(retrieved.isComplete());
        }

        @Test
        @DisplayName("SplitState incrementCompleted should work")
        void incrementCompletedShouldWork() {
            StateSnapshot.SplitState state = StateSnapshot.SplitState.initial(3)
                .incrementCompleted();
            assertEquals(1, state.completedCount());
            assertEquals(2, state.pendingCount());
        }

        @Test
        @DisplayName("SplitState incrementFailed should work")
        void incrementFailedShouldWork() {
            StateSnapshot.SplitState state = StateSnapshot.SplitState.initial(3)
                .incrementFailed();
            assertEquals(1, state.failedCount());
        }

        @Test
        @DisplayName("SplitState should be complete when all processed")
        void shouldBeCompleteWhenAllProcessed() {
            StateSnapshot.SplitState state = StateSnapshot.SplitState.initial(2)
                .incrementCompleted()
                .incrementFailed();
            assertTrue(state.isComplete());
        }
    }

    @Nested
    @DisplayName("Signal system")
    class SignalSystemTests {

        @Test
        @DisplayName("markNodeAwaitingSignal should add to awaiting and remove from running")
        void markAwaitingShouldWork() {
            StateSnapshot snapshot = StateSnapshot.empty()
                .markNodeAwaitingSignal("mcp:step1");

            assertTrue(snapshot.getAwaitingSignalNodeIds().contains("mcp:step1"));
            assertFalse(snapshot.getRunningNodeIds().contains("mcp:step1"));
        }

        @Test
        @DisplayName("resolveAwaitingSignal should move to completed")
        void resolveAwaitingShouldWork() {
            StateSnapshot snapshot = StateSnapshot.empty()
                .markNodeAwaitingSignal("mcp:step1")
                .resolveAwaitingSignal("mcp:step1");

            assertFalse(snapshot.getAwaitingSignalNodeIds().contains("mcp:step1"));
            assertTrue(snapshot.getCompletedNodeIds().contains("mcp:step1"));
            assertEquals(1, snapshot.getNodeCounts("mcp:step1").completed());
        }

        @Test
        @DisplayName("regression: skipAwaitingSignal moves signal node to skipped without completed count")
        void skipAwaitingSignalShouldNotIncrementCompleted() {
            StateSnapshot snapshot = StateSnapshot.empty()
                .markNodeAwaitingSignal("mcp:step1")
                .skipAwaitingSignal("trigger:default", "mcp:step1", 0);

            assertFalse(snapshot.getAwaitingSignalNodeIds().contains("mcp:step1"));
            assertFalse(snapshot.getCompletedNodeIds().contains("mcp:step1"));
            assertTrue(snapshot.getSkippedNodeIds().contains("mcp:step1"));
            assertEquals(0, snapshot.getNodeCounts("mcp:step1").completed());
            assertEquals(1, snapshot.getNodeCounts("mcp:step1").skipped());
        }
    }

    @Nested
    @DisplayName("resetDag()")
    class ResetDagTests {

        private static final String DAG1 = "trigger:webhook";
        private static final String DAG2 = "trigger:chat";
        private static final String DAG3 = "trigger:schedule";

        @Test
        @DisplayName("Should only reset nodes in target DAG, preserving other DAG")
        void shouldOnlyResetDagNodes() {
            StateSnapshot snapshot = StateSnapshot.empty()
                .markNodeCompleted(DAG1, "mcp:step1")
                .markNodeCompleted(DAG2, "mcp:step2");

            StateSnapshot reset = snapshot.resetDag(DAG1, 1);

            assertFalse(reset.getCompletedNodeIds().contains("mcp:step1"));
            assertTrue(reset.getCompletedNodeIds().contains("mcp:step2"));
        }

        @Test
        @DisplayName("Should reset all status types for DAG nodes while preserving other DAG")
        void shouldResetAllStatusTypesForDag() {
            StateSnapshot snapshot = StateSnapshot.empty()
                // DAG1
                .markNodeCompleted(DAG1, "trigger:webhook")
                .markNodeCompleted(DAG1, "mcp:step_a")
                .markNodeFailed(DAG1, "mcp:step_b")
                // DAG2
                .markNodeCompleted(DAG2, "trigger:chat")
                .markNodeCompleted(DAG2, "agent:reply")
                .addReadyNode(DAG2, "mcp:notify");

            // Reset DAG1 only (advance to epoch 1)
            StateSnapshot reset = snapshot.resetDag(DAG1, 1);

            // DAG1 nodes should be cleared from all status sets
            assertFalse(reset.getCompletedNodeIds().contains("trigger:webhook"));
            assertFalse(reset.getCompletedNodeIds().contains("mcp:step_a"));
            assertFalse(reset.getFailedNodeIds().contains("mcp:step_b"));

            // DAG2 nodes should be preserved
            assertTrue(reset.getCompletedNodeIds().contains("trigger:chat"));
            assertTrue(reset.getCompletedNodeIds().contains("agent:reply"));
            assertTrue(reset.getReadyNodeIds().contains("mcp:notify"));
        }

        @Test
        @DisplayName("resetDag should always preserve NodeCounts (accumulation)")
        void resetDagShouldAlwaysPreserveNodeCounts() {
            StateSnapshot snapshot = StateSnapshot.empty()
                .markNodeCompleted(DAG1, "mcp:step_a")
                .markNodeCompleted(DAG1, "mcp:step_a")  // second execution
                .markNodeCompleted(DAG2, "agent:reply");

            StateSnapshot reset = snapshot.resetDag(DAG1, 1);

            // NodeCounts always preserved (accumulation across epochs and reruns)
            assertEquals(2, reset.getNodeCounts("mcp:step_a").completed());
            assertEquals(1, reset.getNodeCounts("agent:reply").completed());
        }

        @Test
        @DisplayName("resetDag should always preserve EdgeCounts (accumulation)")
        void resetDagShouldAlwaysPreserveEdgeCounts() {
            StateSnapshot snapshot = StateSnapshot.empty()
                .markNodeCompleted(DAG1, "trigger:webhook")
                .markNodeCompleted(DAG2, "trigger:chat")
                .incrementEdge("trigger:webhook", "mcp:step_a", "COMPLETED")
                .incrementEdge("trigger:chat", "agent:reply", "COMPLETED");

            StateSnapshot reset = snapshot.resetDag(DAG1, 1);

            // EdgeCounts always preserved
            assertEquals(1, reset.getEdgeCounts("trigger:webhook", "mcp:step_a").completed());
            assertEquals(1, reset.getEdgeCounts("trigger:chat", "agent:reply").completed());
        }

        @Test
        @DisplayName("openEpochForDag should preserve NodeCounts and EdgeCounts across epoch open")
        void openEpochForDagShouldPreserveCountsAcrossEpochOpen() {
            // Simulate epoch 1: trigger fires, nodes complete, edges traverse
            StateSnapshot snapshot = StateSnapshot.empty()
                .resetDag(DAG1, 0)
                .markNodeCompleted(DAG1, "trigger:start")
                .markNodeCompleted(DAG1, "mcp:step_a")
                .markNodeCompleted(DAG1, "mcp:step_a")  // split: 2 items
                .incrementEdge("trigger:start", "mcp:step_a", "COMPLETED")
                .incrementEdge("trigger:start", "mcp:step_a", "COMPLETED")
                .incrementEdge("mcp:step_a", "mcp:step_b", "COMPLETED")
                .incrementEdge("mcp:step_a", "mcp:step_b", "SKIPPED");

            // Prepare next cycle (dormant epoch 1)
            StateSnapshot prepared = snapshot.prepareDagForNextCycle(DAG1, 1, "trigger:start");

            // Open epoch 1 for execution - this MUST preserve accumulated counts
            StateSnapshot opened = prepared.openEpochForDag(DAG1, 1);

            // NodeCounts from epoch 0 must survive
            assertEquals(1, opened.getNodeCounts("trigger:start").completed(),
                "trigger:start completed count must survive openEpochForDag");
            assertEquals(2, opened.getNodeCounts("mcp:step_a").completed(),
                "mcp:step_a completed count must survive openEpochForDag");

            // EdgeCounts from epoch 0 must survive
            assertEquals(2, opened.getEdgeCounts("trigger:start", "mcp:step_a").completed(),
                "trigger:start→mcp:step_a completed edge count must survive openEpochForDag");
            assertEquals(1, opened.getEdgeCounts("mcp:step_a", "mcp:step_b").completed(),
                "mcp:step_a→mcp:step_b completed edge count must survive");
            assertEquals(1, opened.getEdgeCounts("mcp:step_a", "mcp:step_b").skipped(),
                "mcp:step_a→mcp:step_b skipped edge count must survive");

            // After epoch 1 executes, counts should ACCUMULATE (not reset)
            StateSnapshot afterEpoch1 = opened
                .markNodeCompleted(DAG1, "trigger:start")
                .markNodeCompleted(DAG1, "mcp:step_a")
                .incrementEdge("trigger:start", "mcp:step_a", "COMPLETED")
                .incrementEdge("mcp:step_a", "mcp:step_b", "COMPLETED");

            assertEquals(2, afterEpoch1.getNodeCounts("trigger:start").completed(),
                "trigger:start should accumulate to 2 across epochs");
            assertEquals(3, afterEpoch1.getNodeCounts("mcp:step_a").completed(),
                "mcp:step_a should accumulate to 3 across epochs");
            assertEquals(3, afterEpoch1.getEdgeCounts("trigger:start", "mcp:step_a").completed(),
                "Edge completed should accumulate to 3 across epochs");
            assertEquals(2, afterEpoch1.getEdgeCounts("mcp:step_a", "mcp:step_b").completed(),
                "Edge completed should accumulate across epochs");
            assertEquals(1, afterEpoch1.getEdgeCounts("mcp:step_a", "mcp:step_b").skipped(),
                "Edge skipped from epoch 0 should still be present");
        }

        @Test
        @DisplayName("Should reset decision branches only for DAG nodes")
        void shouldResetDecisionBranchesOnlyForDag() {
            StateSnapshot snapshot = StateSnapshot.empty()
                .recordDecisionBranch(DAG1, "core:check_dag1", "if")
                .recordDecisionBranch(DAG2, "core:check_dag2", "else");

            StateSnapshot reset = snapshot.resetDag(DAG1, 1);

            // DAG1 decision branch should be cleared
            assertTrue(reset.getDecisionBranches("core:check_dag1").isEmpty());
            // DAG2 decision branch should be preserved
            assertTrue(reset.getDecisionBranches("core:check_dag2").contains("else"));
        }

        @Test
        @DisplayName("Should reset loop state only for DAG nodes")
        void shouldResetLoopStateOnlyForDag() {
            StateSnapshot.LoopState loop1 = StateSnapshot.LoopState.initial(5).nextIteration().nextIteration();
            StateSnapshot.LoopState loop2 = StateSnapshot.LoopState.initial(3);

            StateSnapshot snapshot = StateSnapshot.empty()
                .updateLoopState(DAG1, "core:loop_dag1", loop1)
                .updateLoopState(DAG2, "core:loop_dag2", loop2);

            StateSnapshot reset = snapshot.resetDag(DAG1, 1);

            // DAG1 loop state should be cleared
            assertNull(reset.getLoopState("core:loop_dag1"));
            // DAG2 loop state should be preserved
            assertNotNull(reset.getLoopState("core:loop_dag2"));
            assertEquals(0, reset.getLoopState("core:loop_dag2").currentIndex());
        }

        @Test
        @DisplayName("Should reset split state only for DAG nodes")
        void shouldResetSplitStateOnlyForDag() {
            StateSnapshot.SplitState split1 = StateSnapshot.SplitState.initial(10).incrementCompleted();
            StateSnapshot.SplitState split2 = StateSnapshot.SplitState.initial(5);

            StateSnapshot snapshot = StateSnapshot.empty()
                .updateSplitState(DAG1, "core:split_dag1", split1)
                .updateSplitState(DAG2, "core:split_dag2", split2);

            StateSnapshot reset = snapshot.resetDag(DAG1, 1);

            // DAG1 split state should be cleared
            assertNull(reset.getSplitState("core:split_dag1"));
            // DAG2 split state should be preserved
            assertNotNull(reset.getSplitState("core:split_dag2"));
            assertEquals(5, reset.getSplitState("core:split_dag2").itemCount());
        }

        @Test
        @DisplayName("Rerun scenario: resetDag should preserve all counts but reset execution state")
        void rerunShouldPreserveCountsButResetExecutionState() {
            // Simulate: trigger → decision → (if) step_a / (else) step_b - all in one DAG
            String dag = "trigger:manual";
            StateSnapshot snapshot = StateSnapshot.empty()
                .markNodeCompleted(dag, "trigger:manual")
                .markNodeCompleted(dag, "core:decision")
                .markNodeCompleted(dag, "mcp:step_a")
                .markNodeSkipped(dag, "mcp:step_b")
                .incrementEdge("trigger:manual", "core:decision", "COMPLETED")
                .incrementEdge("trigger:manual", "core:decision", "COMPLETED")
                .incrementEdge("trigger:manual", "core:decision", "COMPLETED")
                .incrementEdge("core:decision:if", "mcp:step_a", "COMPLETED")
                .incrementEdge("core:decision:if", "mcp:step_a", "COMPLETED")
                .incrementEdge("core:decision:if", "mcp:step_a", "COMPLETED")
                .incrementEdge("core:decision:else", "mcp:step_b", "SKIPPED")
                .incrementEdge("core:decision:else", "mcp:step_b", "SKIPPED")
                .incrementEdge("core:decision:else", "mcp:step_b", "SKIPPED");

            // Reset DAG (advance to epoch 1 - fresh epoch state)
            StateSnapshot reset = snapshot.resetDag(dag, 1);

            // ALL edge counts preserved (accumulation - edge counts are global)
            assertEquals(3, reset.getEdgeCounts("core:decision:if", "mcp:step_a").completed(),
                "Edge counts should accumulate even after rerun");
            assertEquals(3, reset.getEdgeCounts("core:decision:else", "mcp:step_b").skipped(),
                "Edge to non-reset step should be preserved");
            assertEquals(3, reset.getEdgeCounts("trigger:manual", "core:decision").completed(),
                "Edge to upstream step should be preserved");

            // ALL node counts preserved (accumulation)
            assertEquals(1, reset.getNodeCounts("mcp:step_a").completed(),
                "NodeCounts should accumulate even after rerun");

            // Execution state: DAG is fully reset (new epoch is empty)
            assertFalse(reset.getCompletedNodeIds().contains("mcp:step_a"));
            assertFalse(reset.getCompletedNodeIds().contains("core:decision"));
            assertFalse(reset.getSkippedNodeIds().contains("mcp:step_b"));
        }

        @Test
        @DisplayName("Should reset skipped and running nodes in DAG while preserving others")
        void shouldResetSkippedAndRunningInDag() {
            StateSnapshot snapshot = StateSnapshot.empty()
                // DAG1: various statuses
                .markNodeSkipped(DAG1, "mcp:skipped_dag1")
                .addReadyNode(DAG1, "mcp:ready_dag1")
                // DAG2: various statuses
                .markNodeSkipped(DAG2, "mcp:skipped_dag2")
                .addReadyNode(DAG2, "mcp:ready_dag2");

            StateSnapshot reset = snapshot.resetDag(DAG1, 1);

            // DAG1 skipped and ready should be cleared
            assertFalse(reset.getSkippedNodeIds().contains("mcp:skipped_dag1"));
            assertFalse(reset.getReadyNodeIds().contains("mcp:ready_dag1"));

            // DAG2 should be preserved
            assertTrue(reset.getSkippedNodeIds().contains("mcp:skipped_dag2"));
            assertTrue(reset.getReadyNodeIds().contains("mcp:ready_dag2"));
        }

        @Test
        @DisplayName("Should reset awaiting signal nodes in DAG while preserving others")
        void shouldResetAwaitingSignalInDag() {
            StateSnapshot snapshot = StateSnapshot.empty()
                .markNodeAwaitingSignal(DAG1, "core:wait_dag1")
                .markNodeAwaitingSignal(DAG2, "core:wait_dag2");

            StateSnapshot reset = snapshot.resetDag(DAG1, 1);

            assertFalse(reset.getAwaitingSignalNodeIds().contains("core:wait_dag1"));
            assertTrue(reset.getAwaitingSignalNodeIds().contains("core:wait_dag2"));
        }

        @Test
        @DisplayName("Should handle complex 3-DAG scenario with mixed node prefixes")
        void shouldHandleComplex3DagScenario() {
            StateSnapshot snapshot = StateSnapshot.empty()
                // DAG1 (webhook flow): trigger + mcp + core + table
                .markNodeCompleted(DAG1, "trigger:webhook")
                .markNodeCompleted(DAG1, "mcp:fetch_data")
                .markNodeCompleted(DAG1, "core:check_condition")
                .markNodeFailed(DAG1, "table:insert_row")
                .recordDecisionBranch(DAG1, "core:check_condition", "if")
                // DAG2 (chat flow): trigger + agent
                .markNodeCompleted(DAG2, "trigger:chat")
                .markNodeCompleted(DAG2, "agent:ai_responder")
                .markNodeSkipped(DAG2, "mcp:log_chat")
                // DAG3 (schedule flow): trigger + core(loop) + mcp
                .markNodeCompleted(DAG3, "trigger:schedule")
                .markNodeCompleted(DAG3, "core:daily_loop")
                .addReadyNode(DAG3, "mcp:send_report")
                .updateLoopState(DAG3, "core:daily_loop", StateSnapshot.LoopState.initial(7).nextIteration());

            // Reset DAG2 only
            StateSnapshot reset = snapshot.resetDag(DAG2, 1);

            // DAG2 fully cleared
            assertFalse(reset.getCompletedNodeIds().contains("trigger:chat"));
            assertFalse(reset.getCompletedNodeIds().contains("agent:ai_responder"));
            assertFalse(reset.getSkippedNodeIds().contains("mcp:log_chat"));

            // DAG1 fully preserved
            assertTrue(reset.getCompletedNodeIds().contains("trigger:webhook"));
            assertTrue(reset.getCompletedNodeIds().contains("mcp:fetch_data"));
            assertTrue(reset.getCompletedNodeIds().contains("core:check_condition"));
            assertTrue(reset.getFailedNodeIds().contains("table:insert_row"));
            assertTrue(reset.getDecisionBranches("core:check_condition").contains("if"));

            // DAG3 fully preserved
            assertTrue(reset.getCompletedNodeIds().contains("trigger:schedule"));
            assertTrue(reset.getCompletedNodeIds().contains("core:daily_loop"));
            assertTrue(reset.getReadyNodeIds().contains("mcp:send_report"));
            assertNotNull(reset.getLoopState("core:daily_loop"));
            assertEquals(1, reset.getLoopState("core:daily_loop").currentIndex());
        }
    }

    @Nested
    @DisplayName("resetDag() Stress Tests")
    class ResetDagStressTests {

        private static final String DAG1 = "trigger:webhook";
        private static final String DAG2 = "trigger:chat";
        private static final String DAG3 = "trigger:schedule";

        @Test
        @DisplayName("STRESS: Sequential resetDag on 3 DAGs - each reset is isolated")
        void stressSequentialResetOn3Dags() {
            // Build a complex snapshot with 3 DAGs using per-DAG API
            StateSnapshot snapshot = StateSnapshot.empty()
                // DAG1
                .markNodeCompleted(DAG1, "trigger:webhook")
                .markNodeCompleted(DAG1, "mcp:fetch")
                .markNodeFailed(DAG1, "mcp:process")
                .recordDecisionBranch(DAG1, "core:check_1", "if")
                .updateLoopState(DAG1, "core:loop_1", StateSnapshot.LoopState.initial(10).nextIteration())
                .incrementEdge("trigger:webhook", "mcp:fetch", "COMPLETED")
                // DAG2
                .markNodeCompleted(DAG2, "trigger:chat")
                .markNodeCompleted(DAG2, "agent:respond")
                .markNodeSkipped(DAG2, "mcp:log")
                .updateSplitState(DAG2, "core:split_2", StateSnapshot.SplitState.initial(5).incrementCompleted())
                .incrementEdge("trigger:chat", "agent:respond", "COMPLETED")
                // DAG3
                .markNodeCompleted(DAG3, "trigger:schedule")
                .markNodeCompleted(DAG3, "table:cleanup")
                .addReadyNode(DAG3, "mcp:report")
                .markNodeAwaitingSignal(DAG3, "core:wait_3")
                .incrementEdge("trigger:schedule", "table:cleanup", "COMPLETED");

            // Reset DAG1
            StateSnapshot afterDag1 = snapshot.resetDag(DAG1, 1);
            assertFalse(afterDag1.getCompletedNodeIds().contains("trigger:webhook"));
            assertFalse(afterDag1.getFailedNodeIds().contains("mcp:process"));
            assertNull(afterDag1.getLoopState("core:loop_1"));
            assertTrue(afterDag1.getDecisionBranches("core:check_1").isEmpty());
            // DAG2 + DAG3 intact
            assertTrue(afterDag1.getCompletedNodeIds().contains("trigger:chat"));
            assertTrue(afterDag1.getSkippedNodeIds().contains("mcp:log"));
            assertTrue(afterDag1.getCompletedNodeIds().contains("trigger:schedule"));
            assertTrue(afterDag1.getAwaitingSignalNodeIds().contains("core:wait_3"));

            // Reset DAG2 from the already-reset-DAG1 snapshot
            StateSnapshot afterDag2 = afterDag1.resetDag(DAG2, 1);
            assertFalse(afterDag2.getCompletedNodeIds().contains("trigger:chat"));
            assertFalse(afterDag2.getSkippedNodeIds().contains("mcp:log"));
            assertNull(afterDag2.getSplitState("core:split_2"));
            // DAG3 still intact
            assertTrue(afterDag2.getCompletedNodeIds().contains("trigger:schedule"));
            assertTrue(afterDag2.getReadyNodeIds().contains("mcp:report"));
            assertTrue(afterDag2.getAwaitingSignalNodeIds().contains("core:wait_3"));

            // Reset DAG3
            StateSnapshot afterDag3 = afterDag2.resetDag(DAG3, 1);
            assertFalse(afterDag3.getCompletedNodeIds().contains("trigger:schedule"));
            assertFalse(afterDag3.getReadyNodeIds().contains("mcp:report"));
            assertFalse(afterDag3.getAwaitingSignalNodeIds().contains("core:wait_3"));

            // All execution state empty
            assertTrue(afterDag3.getCompletedNodeIds().isEmpty());
            assertTrue(afterDag3.getFailedNodeIds().isEmpty());
            assertTrue(afterDag3.getSkippedNodeIds().isEmpty());
            assertTrue(afterDag3.getReadyNodeIds().isEmpty());
            assertTrue(afterDag3.getAwaitingSignalNodeIds().isEmpty());

            // Edge counts PRESERVED (accumulation - never reset)
            assertEquals(1, afterDag3.getEdgeCounts("trigger:webhook", "mcp:fetch").completed(),
                "Edge counts should be preserved after resetDag");
            assertEquals(1, afterDag3.getEdgeCounts("trigger:chat", "agent:respond").completed(),
                "Edge counts should be preserved after resetDag");
            assertEquals(1, afterDag3.getEdgeCounts("trigger:schedule", "table:cleanup").completed(),
                "Edge counts should be preserved after resetDag");
            // NodeCounts PRESERVED (accumulation - never reset)
            assertEquals(1, afterDag3.getNodeCounts("mcp:fetch").completed(),
                "NodeCounts should be preserved after resetDag");
            assertEquals(1, afterDag3.getNodeCounts("agent:respond").completed(),
                "NodeCounts should be preserved after resetDag");
            assertEquals(1, afterDag3.getNodeCounts("table:cleanup").completed(),
                "NodeCounts should be preserved after resetDag");
        }

        @Test
        @DisplayName("STRESS: resetDag on non-existent trigger - nothing breaks")
        void stressResetDagNonExistentTrigger() {
            StateSnapshot snapshot = StateSnapshot.empty()
                .markNodeCompleted(DAG1, "mcp:step1")
                .markNodeFailed(DAG1, "mcp:step2")
                .markNodeSkipped(DAG1, "mcp:step3")
                .addReadyNode(DAG1, "mcp:step4")
                .markNodeAwaitingSignal(DAG1, "core:wait");

            // Reset a non-existent DAG - should not affect existing state
            StateSnapshot reset = snapshot.resetDag("trigger:phantom", 1);

            assertEquals(snapshot.getCompletedNodeIds(), reset.getCompletedNodeIds());
            assertEquals(snapshot.getFailedNodeIds(), reset.getFailedNodeIds());
            assertEquals(snapshot.getSkippedNodeIds(), reset.getSkippedNodeIds());
            assertEquals(snapshot.getReadyNodeIds(), reset.getReadyNodeIds());
            assertEquals(snapshot.getAwaitingSignalNodeIds(), reset.getAwaitingSignalNodeIds());
        }

        @Test
        @DisplayName("STRESS: 50 nodes across 5 DAGs - massive resetDag")
        void stress50NodesAcross5Dags() {
            StateSnapshot snapshot = StateSnapshot.empty();

            // Build 5 DAGs of 10 nodes each using per-DAG API
            for (int dag = 0; dag < 5; dag++) {
                String triggerId = "trigger:dag" + dag;
                for (int node = 0; node < 10; node++) {
                    String nodeId = "mcp:dag" + dag + "_node" + node;
                    if (node % 3 == 0) {
                        snapshot = snapshot.markNodeCompleted(triggerId, nodeId);
                    } else if (node % 3 == 1) {
                        snapshot = snapshot.markNodeFailed(triggerId, nodeId);
                    } else {
                        snapshot = snapshot.markNodeSkipped(triggerId, nodeId);
                    }
                }
                snapshot = snapshot.incrementEdge("trigger:dag" + dag, "mcp:dag" + dag + "_node0", "COMPLETED");
            }

            // Reset DAG2
            StateSnapshot reset = snapshot.resetDag("trigger:dag2", 1);

            // DAG2 nodes cleared
            for (int node = 0; node < 10; node++) {
                String id = "mcp:dag2_node" + node;
                assertFalse(reset.getCompletedNodeIds().contains(id));
                assertFalse(reset.getFailedNodeIds().contains(id));
                assertFalse(reset.getSkippedNodeIds().contains(id));
            }

            // Other DAGs untouched (spot check DAG0 and DAG4)
            assertTrue(reset.getCompletedNodeIds().contains("mcp:dag0_node0"));
            assertTrue(reset.getFailedNodeIds().contains("mcp:dag0_node1"));
            assertTrue(reset.getSkippedNodeIds().contains("mcp:dag0_node2"));
            assertTrue(reset.getCompletedNodeIds().contains("mcp:dag4_node0"));
            assertTrue(reset.getFailedNodeIds().contains("mcp:dag4_node1"));

            // Edge counts: ALL preserved (accumulation - never reset)
            for (int dag = 0; dag < 5; dag++) {
                StateSnapshot.EdgeCounts ec = reset.getEdgeCounts("trigger:dag" + dag, "mcp:dag" + dag + "_node0");
                assertEquals(1, ec.completed(), "DAG" + dag + " edge counts should be preserved");
            }
        }

        @Test
        @DisplayName("STRESS: Node in every status set simultaneously (impossible but tests robustness)")
        void stressNodeInEveryStatusSet() {
            // Manually craft a snapshot where a node appears in multiple sets (shouldn't happen, but tests robustness)
            StateSnapshot snapshot = new StateSnapshot(
                3,
                0L,                                      // seq
                null,                                    // dags (will migrate from flat)
                Set.of("mcp:weird", "mcp:other"),       // completed
                Set.of("mcp:weird"),                     // failed
                Set.of("mcp:weird"),                     // skipped
                Set.of("mcp:weird"),                     // running
                Set.of("mcp:weird"),                     // ready
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Set.of("mcp:weird"),                     // awaiting
                0L,                                      // totalDurationMs
                null
            );

            // resetDag(Set<String>) resets any DAG containing specified nodes
            StateSnapshot reset = snapshot.resetDag(Set.of("mcp:weird"));

            assertFalse(reset.getCompletedNodeIds().contains("mcp:weird"));
            assertFalse(reset.getFailedNodeIds().contains("mcp:weird"));
            assertFalse(reset.getSkippedNodeIds().contains("mcp:weird"));
            assertFalse(reset.getRunningNodeIds().contains("mcp:weird"));
            assertFalse(reset.getReadyNodeIds().contains("mcp:weird"));
            assertFalse(reset.getAwaitingSignalNodeIds().contains("mcp:weird"));
            // NOTE: with flat migration, all nodes go to same default DAG, so mcp:other also gets reset
            // This is expected behavior for the legacy flat API
        }

        @Test
        @DisplayName("STRESS: resetDag preserves multiple decision branches per node in other DAG")
        void stressPreservesMultipleDecisionBranches() {
            StateSnapshot snapshot = StateSnapshot.empty()
                .recordDecisionBranch(DAG2, "core:switch_dag2", "case_0")
                .recordDecisionBranch(DAG2, "core:switch_dag2", "case_1")
                .recordDecisionBranch(DAG2, "core:switch_dag2", "default")
                .recordDecisionBranch(DAG1, "core:decision_dag1", "if");

            StateSnapshot reset = snapshot.resetDag(DAG1, 1);

            assertTrue(reset.getDecisionBranches("core:decision_dag1").isEmpty());
            // DAG2 switch preserved with all 3 branches
            Set<String> branches = reset.getDecisionBranches("core:switch_dag2");
            assertEquals(3, branches.size());
            assertTrue(branches.contains("case_0"));
            assertTrue(branches.contains("case_1"));
            assertTrue(branches.contains("default"));
        }

        @Test
        @DisplayName("STRESS: resetDag then add new state - immutability verified")
        void stressResetThenAddNewState() {
            StateSnapshot original = StateSnapshot.empty()
                .markNodeCompleted(DAG1, "trigger:dag1")
                .markNodeCompleted(DAG1, "mcp:step_dag1")
                .markNodeCompleted(DAG2, "trigger:dag2")
                .markNodeCompleted(DAG2, "mcp:step_dag2");

            StateSnapshot reset = original.resetDag(DAG1, 1);

            // Add new state to the reset snapshot
            StateSnapshot withNew = reset
                .markNodeCompleted(DAG1, "trigger:dag1")  // re-fire dag1
                .addReadyNode(DAG1, "mcp:step_dag1");

            // Original should be unchanged (immutability)
            assertTrue(original.getCompletedNodeIds().contains("trigger:dag1"));
            assertTrue(original.getCompletedNodeIds().contains("mcp:step_dag1"));

            // Reset should still not have dag1 completed
            assertFalse(reset.getCompletedNodeIds().contains("trigger:dag1"));

            // New snapshot should have the re-fired trigger
            assertTrue(withNew.getCompletedNodeIds().contains("trigger:dag1"));
            assertTrue(withNew.getReadyNodeIds().contains("mcp:step_dag1"));
            // DAG2 still there through all mutations
            assertTrue(withNew.getCompletedNodeIds().contains("trigger:dag2"));
            assertTrue(withNew.getCompletedNodeIds().contains("mcp:step_dag2"));
        }
    }

    @Nested
    @DisplayName("Multi-DAG ready node preservation")
    class MultiDagReadyNodeTests {

        @Test
        @DisplayName("withReadyNodes REPLACES entire set - demonstrates the bug")
        void withReadyNodesShouldReplaceEntireSet() {
            // Setup: DAG1 node_a2 is READY, DAG2 trigger just completed
            StateSnapshot snapshot = StateSnapshot.empty()
                .markNodeCompleted("trigger:webhook_a")
                .markNodeCompleted("mcp:node_a1")
                .addReadyNode("mcp:node_a2");  // DAG1 ready node

            // Simulate what withReadyNodes does: REPLACES the entire set
            StateSnapshot replaced = snapshot.withReadyNodes(Set.of("mcp:node_b1"));

            // DAG1's ready node is LOST - this is the bug behavior
            assertFalse(replaced.getReadyNodeIds().contains("mcp:node_a2"));
            assertTrue(replaced.getReadyNodeIds().contains("mcp:node_b1"));
            assertEquals(1, replaced.getReadyNodeIds().size());
        }

        @Test
        @DisplayName("Manual merge preserves ready nodes from other DAGs - the fix")
        void manualMergeShouldPreserveOtherDagReadyNodes() {
            // Setup: DAG1 node_a2 is READY, DAG2 trigger is being executed
            StateSnapshot snapshot = StateSnapshot.empty()
                .markNodeCompleted("trigger:webhook_a")
                .markNodeCompleted("mcp:node_a1")
                .addReadyNode("mcp:node_a2")       // DAG1 ready
                .addReadyNode("trigger:webhook_b"); // DAG2 trigger ready

            // Simulate mergeReadyNodesAfterExecution for trigger:webhook_b
            String executedNode = "trigger:webhook_b";
            Set<String> newReadyNodes = Set.of("mcp:node_b1"); // successor of trigger:webhook_b

            Set<String> merged = new java.util.HashSet<>(snapshot.getReadyNodeIds());
            merged.remove(executedNode);
            merged.addAll(newReadyNodes);
            StateSnapshot updated = snapshot.withReadyNodes(merged);

            // DAG1's node_a2 is PRESERVED
            assertTrue(updated.getReadyNodeIds().contains("mcp:node_a2"));
            // DAG2's new ready node is added
            assertTrue(updated.getReadyNodeIds().contains("mcp:node_b1"));
            // Executed trigger is removed
            assertFalse(updated.getReadyNodeIds().contains("trigger:webhook_b"));
            assertEquals(2, updated.getReadyNodeIds().size());
        }

        @Test
        @DisplayName("Full multi-DAG step-by-step scenario: DAG1 advancing while DAG2 triggers")
        void fullMultiDagStepByStepScenario() {
            // === Step 1: Both triggers are READY ===
            StateSnapshot step1 = StateSnapshot.empty()
                .addReadyNode("trigger:webhook_a")
                .addReadyNode("trigger:webhook_b");

            assertEquals(2, step1.getReadyNodeIds().size());

            // === Step 2: DAG1 webhook fires, trigger:webhook_a executes ===
            Set<String> merged2 = new java.util.HashSet<>(step1.getReadyNodeIds());
            merged2.remove("trigger:webhook_a");
            merged2.addAll(Set.of("mcp:node_a1")); // successor
            StateSnapshot step2 = step1
                .markNodeCompleted("trigger:webhook_a")
                .withReadyNodes(merged2);

            assertTrue(step2.getReadyNodeIds().contains("mcp:node_a1"));
            assertTrue(step2.getReadyNodeIds().contains("trigger:webhook_b"));
            assertEquals(2, step2.getReadyNodeIds().size());

            // === Step 3: User advances DAG1 node_a1 ===
            Set<String> merged3 = new java.util.HashSet<>(step2.getReadyNodeIds());
            merged3.remove("mcp:node_a1");
            merged3.addAll(Set.of("mcp:node_a2")); // successor
            StateSnapshot step3 = step2
                .markNodeCompleted("mcp:node_a1")
                .withReadyNodes(merged3);

            assertTrue(step3.getReadyNodeIds().contains("mcp:node_a2"));
            assertTrue(step3.getReadyNodeIds().contains("trigger:webhook_b"));
            assertEquals(2, step3.getReadyNodeIds().size());

            // === Step 4: DAG2 webhook fires while DAG1 node_a2 is still READY ===
            Set<String> merged4 = new java.util.HashSet<>(step3.getReadyNodeIds());
            merged4.remove("trigger:webhook_b");
            merged4.addAll(Set.of("mcp:node_b1")); // successor
            StateSnapshot step4 = step3
                .markNodeCompleted("trigger:webhook_b")
                .withReadyNodes(merged4);

            // CRITICAL: DAG1's node_a2 MUST still be READY
            assertTrue(step4.getReadyNodeIds().contains("mcp:node_a2"),
                "DAG1 node_a2 should remain READY when DAG2 triggers");
            assertTrue(step4.getReadyNodeIds().contains("mcp:node_b1"),
                "DAG2 node_b1 should be READY after trigger");
            assertFalse(step4.getReadyNodeIds().contains("trigger:webhook_b"),
                "Executed trigger should be removed from READY");
            assertEquals(2, step4.getReadyNodeIds().size());

            // === Step 5: User advances both DAGs independently ===
            Set<String> merged5 = new java.util.HashSet<>(step4.getReadyNodeIds());
            merged5.remove("mcp:node_a2");
            // node_a2 is the last node of DAG1, no successor
            StateSnapshot step5 = step4
                .markNodeCompleted("mcp:node_a2")
                .withReadyNodes(merged5);

            assertFalse(step5.getReadyNodeIds().contains("mcp:node_a2"));
            assertTrue(step5.getReadyNodeIds().contains("mcp:node_b1"));
            assertEquals(1, step5.getReadyNodeIds().size());
        }

        @Test
        @DisplayName("resetDag followed by merge should preserve other DAG ready nodes")
        void resetDagFollowedByMergeShouldPreserve() {
            // DAG1 has completed some nodes, node_a2 is READY
            // DAG2 is being re-fired (resetDag then execute)
            String dagA = "trigger:webhook_a";
            String dagB = "trigger:webhook_b";
            StateSnapshot snapshot = StateSnapshot.empty()
                .markNodeCompleted(dagA, "trigger:webhook_a")
                .markNodeCompleted(dagA, "mcp:node_a1")
                .addReadyNode(dagA, "mcp:node_a2")
                .markNodeCompleted(dagB, "trigger:webhook_b")
                .markNodeCompleted(dagB, "mcp:node_b1");

            // Step 1: resetDag for DAG_B (re-fire) - advances to new epoch
            StateSnapshot afterReset = snapshot.resetDag(dagB, 1);

            // DAG_A ready nodes survive resetDag
            assertTrue(afterReset.getReadyNodeIds().contains("mcp:node_a2"));

            // Step 2: Add trigger:webhook_b as ready after reset
            StateSnapshot withTriggerReady = afterReset.addReadyNode(dagB, "trigger:webhook_b");
            assertTrue(withTriggerReady.getReadyNodeIds().contains("mcp:node_a2"));
            assertTrue(withTriggerReady.getReadyNodeIds().contains("trigger:webhook_b"));

            // Step 3: Execute trigger:webhook_b, use per-DAG merge
            StateSnapshot afterExec = withTriggerReady
                .markNodeCompleted(dagB, "trigger:webhook_b")
                .removeReadyNode(dagB, "trigger:webhook_b")
                .addReadyNode(dagB, "mcp:node_b1");

            // CRITICAL: DAG_A's node_a2 MUST still be READY
            assertTrue(afterExec.getReadyNodeIds().contains("mcp:node_a2"),
                "DAG1 node_a2 must survive DAG2 re-fire + trigger execution");
            assertTrue(afterExec.getReadyNodeIds().contains("mcp:node_b1"));
            assertEquals(2, afterExec.getReadyNodeIds().size());
        }

        @Test
        @DisplayName("STRESS: 3 DAGs interleaving step-by-step execution")
        void stress3DagsInterleavingExecution() {
            // 3 DAGs each with: trigger -> step1 -> step2
            StateSnapshot s = StateSnapshot.empty()
                .addReadyNode("trigger:dag1")
                .addReadyNode("trigger:dag2")
                .addReadyNode("trigger:dag3");

            // DAG1: execute trigger -> step1 ready
            Set<String> m1 = new java.util.HashSet<>(s.getReadyNodeIds());
            m1.remove("trigger:dag1");
            m1.add("mcp:dag1_step1");
            s = s.markNodeCompleted("trigger:dag1").withReadyNodes(m1);
            assertEquals(3, s.getReadyNodeIds().size()); // dag1_step1, trigger:dag2, trigger:dag3

            // DAG2: execute trigger -> step1 ready
            Set<String> m2 = new java.util.HashSet<>(s.getReadyNodeIds());
            m2.remove("trigger:dag2");
            m2.add("mcp:dag2_step1");
            s = s.markNodeCompleted("trigger:dag2").withReadyNodes(m2);
            assertEquals(3, s.getReadyNodeIds().size()); // dag1_step1, dag2_step1, trigger:dag3

            // DAG1: execute step1 -> step2 ready
            Set<String> m3 = new java.util.HashSet<>(s.getReadyNodeIds());
            m3.remove("mcp:dag1_step1");
            m3.add("mcp:dag1_step2");
            s = s.markNodeCompleted("mcp:dag1_step1").withReadyNodes(m3);
            assertEquals(3, s.getReadyNodeIds().size()); // dag1_step2, dag2_step1, trigger:dag3

            // DAG3: execute trigger -> step1 ready
            Set<String> m4 = new java.util.HashSet<>(s.getReadyNodeIds());
            m4.remove("trigger:dag3");
            m4.add("mcp:dag3_step1");
            s = s.markNodeCompleted("trigger:dag3").withReadyNodes(m4);
            assertEquals(3, s.getReadyNodeIds().size()); // dag1_step2, dag2_step1, dag3_step1

            // All 3 DAGs have their expected ready nodes
            assertTrue(s.getReadyNodeIds().contains("mcp:dag1_step2"));
            assertTrue(s.getReadyNodeIds().contains("mcp:dag2_step1"));
            assertTrue(s.getReadyNodeIds().contains("mcp:dag3_step1"));
        }
    }

    @Nested
    @DisplayName("NodeCounts")
    class NodeCountsTests {

        @Test
        @DisplayName("zero should create all-zero counts")
        void zeroShouldCreateAllZero() {
            StateSnapshot.NodeCounts counts = StateSnapshot.NodeCounts.zero();
            assertEquals(0, counts.running());
            assertEquals(0, counts.completed());
            assertEquals(0, counts.failed());
            assertEquals(0, counts.skipped());
            assertEquals(0, counts.total());
        }

        @Test
        @DisplayName("increment should handle different statuses")
        void incrementShouldHandleDifferentStatuses() {
            StateSnapshot.NodeCounts counts = StateSnapshot.NodeCounts.zero()
                .increment("COMPLETED")
                .increment("SUCCESS")  // alias
                .increment("FAILED")
                .increment("SKIPPED")
                .increment("RUNNING");  // no-op

            assertEquals(0, counts.running());
            assertEquals(2, counts.completed()); // COMPLETED + SUCCESS
            assertEquals(1, counts.failed());
            assertEquals(1, counts.skipped());
            assertEquals(4, counts.total());
        }

        @Test
        @DisplayName("increment should handle ERROR alias")
        void incrementShouldHandleErrorAlias() {
            StateSnapshot.NodeCounts counts = StateSnapshot.NodeCounts.zero()
                .increment("ERROR");
            assertEquals(1, counts.failed());
        }

        @Test
        @DisplayName("increment should ignore unknown statuses")
        void incrementShouldIgnoreUnknown() {
            StateSnapshot.NodeCounts counts = StateSnapshot.NodeCounts.zero()
                .increment("UNKNOWN");
            assertEquals(0, counts.total());
        }

        @Test
        @DisplayName("toMap should include all counts")
        void toMapShouldIncludeAllCounts() {
            StateSnapshot.NodeCounts counts = new StateSnapshot.NodeCounts(1, 2, 3, 4, 0L, 0L, 0L);
            Map<String, Integer> map = counts.toMap();
            assertEquals(1, map.get("RUNNING"));
            assertEquals(2, map.get("COMPLETED"));
            assertEquals(3, map.get("FAILED"));
            assertEquals(4, map.get("SKIPPED"));
            assertEquals(10, map.get("TOTAL"));
        }

        @Test
        @DisplayName("6-arg constructor should preserve timing fields")
        void sixArgConstructorShouldPreserveTimingFields() {
            StateSnapshot.NodeCounts counts = new StateSnapshot.NodeCounts(0, 1, 0, 0, 500L, 1000L, 0L);
            assertEquals(500L, counts.totalExecutionTimeMs());
            assertEquals(1000L, counts.lastEndTimeMs());
        }

        @Test
        @DisplayName("incrementWithTiming should accumulate duration for COMPLETED")
        void incrementWithTimingShouldAccumulateForCompleted() {
            StateSnapshot.NodeCounts counts = StateSnapshot.NodeCounts.zero()
                .incrementWithTiming("COMPLETED", 150L)
                .incrementWithTiming("COMPLETED", 200L);

            assertEquals(2, counts.completed());
            assertEquals(350L, counts.totalExecutionTimeMs());
            assertTrue(counts.lastEndTimeMs() > 0);
        }

        @Test
        @DisplayName("incrementWithTiming should accumulate duration for FAILED")
        void incrementWithTimingShouldAccumulateForFailed() {
            StateSnapshot.NodeCounts counts = StateSnapshot.NodeCounts.zero()
                .incrementWithTiming("FAILED", 500L);

            assertEquals(1, counts.failed());
            assertEquals(500L, counts.totalExecutionTimeMs());
            assertTrue(counts.lastEndTimeMs() > 0);
        }

        @Test
        @DisplayName("incrementWithTiming should NOT accumulate duration for SKIPPED")
        void incrementWithTimingShouldNotAccumulateForSkipped() {
            StateSnapshot.NodeCounts counts = StateSnapshot.NodeCounts.zero()
                .incrementWithTiming("SKIPPED", 100L);

            assertEquals(1, counts.skipped());
            assertEquals(0L, counts.totalExecutionTimeMs());
            assertEquals(0L, counts.lastEndTimeMs());
        }

        @Test
        @DisplayName("incrementWithTiming with zero duration should not update timing")
        void incrementWithTimingZeroDurationShouldNotUpdateTiming() {
            StateSnapshot.NodeCounts counts = StateSnapshot.NodeCounts.zero()
                .incrementWithTiming("COMPLETED", 0L);

            assertEquals(1, counts.completed());
            assertEquals(0L, counts.totalExecutionTimeMs());
            assertEquals(0L, counts.lastEndTimeMs());
        }

        @Test
        @DisplayName("increment delegates to incrementWithTiming with zero duration")
        void incrementDelegatesToIncrementWithTimingZero() {
            StateSnapshot.NodeCounts counts = StateSnapshot.NodeCounts.zero()
                .incrementWithTiming("COMPLETED", 100L)
                .increment("COMPLETED");

            assertEquals(2, counts.completed());
            // Only the first call added timing
            assertEquals(100L, counts.totalExecutionTimeMs());
        }
    }

    @Nested
    @DisplayName("EdgeCounts")
    class EdgeCountsTests {

        @Test
        @DisplayName("zero should create all-zero counts")
        void zeroShouldCreateAllZero() {
            StateSnapshot.EdgeCounts counts = StateSnapshot.EdgeCounts.zero();
            assertEquals(0, counts.running());
            assertEquals(0, counts.completed());
            assertEquals(0, counts.skipped());
            assertEquals(0, counts.total());
        }

        @Test
        @DisplayName("increment should handle statuses correctly")
        void incrementShouldHandleStatuses() {
            StateSnapshot.EdgeCounts counts = StateSnapshot.EdgeCounts.zero()
                .increment("COMPLETED")
                .increment("SKIPPED")
                .increment("RUNNING"); // no-op

            assertEquals(0, counts.running());
            assertEquals(1, counts.completed());
            assertEquals(1, counts.skipped());
            assertEquals(2, counts.total());
        }

        @Test
        @DisplayName("toMap should include all counts")
        void toMapShouldIncludeAllCounts() {
            StateSnapshot.EdgeCounts counts = new StateSnapshot.EdgeCounts(0, 3, 1);
            Map<String, Integer> map = counts.toMap();
            assertEquals(0, map.get("RUNNING"));
            assertEquals(3, map.get("COMPLETED"));
            assertEquals(1, map.get("SKIPPED"));
            assertEquals(4, map.get("TOTAL"));
        }
    }

    @Nested
    @DisplayName("isEmpty()")
    class IsEmptyTests {

        @Test
        @DisplayName("Empty snapshot should return true")
        void emptyShouldReturnTrue() {
            assertTrue(StateSnapshot.empty().isEmpty());
        }

        @Test
        @DisplayName("Snapshot with completed node should return false")
        void withCompletedShouldReturnFalse() {
            StateSnapshot snapshot = StateSnapshot.empty().markNodeCompleted("mcp:step1");
            assertFalse(snapshot.isEmpty());
        }
    }

    @Nested
    @DisplayName("Null handling in constructor")
    class NullHandlingTests {

        @Test
        @DisplayName("Should handle all null parameters")
        void shouldHandleAllNull() {
            StateSnapshot snapshot = new StateSnapshot(
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);

            assertEquals(3, snapshot.getVersion());
            assertTrue(snapshot.getCompletedNodeIds().isEmpty());
            assertNotNull(snapshot.getLastUpdated());
        }
    }

    // ==================== closeAllActiveEpochs (DagState + StateSnapshot) ====================

    @Nested
    @DisplayName("DagState.closeAllActiveEpochs()")
    class DagStateCloseAllActiveEpochsTests {

        @Test
        @DisplayName("Should close all active epochs and prune their EpochStates")
        void shouldCloseAllActiveEpochs() {
            // Two active epochs: 1 and 2
            EpochState es1 = EpochState.fresh().markNodeCompleted("mcp:step1");
            EpochState es2 = EpochState.fresh().markNodeCompleted("mcp:step2");
            DagState dag = new DagState(2, 0, 2,
                    Map.of(1, es1, 2, es2), Set.of(1, 2));

            DagState result = dag.closeAllActiveEpochs();

            // Active epochs should be empty
            assertTrue(result.getActiveEpochs().isEmpty());
            assertFalse(result.hasActiveEpochs());
            // Epoch data should be pruned
            assertTrue(result.getEpochs().isEmpty());
            // currentEpoch, currentSpawn, fireCount preserved
            assertEquals(2, result.getCurrentEpoch());
            assertEquals(0, result.getCurrentSpawn());
            assertEquals(2, result.getFireCount());
        }

        @Test
        @DisplayName("Should be no-op when no active epochs exist")
        void shouldBeNoOpWhenNoActiveEpochs() {
            DagState dag = new DagState(1, 0, 1, Map.of(), Set.of());

            DagState result = dag.closeAllActiveEpochs();

            assertTrue(result.getActiveEpochs().isEmpty());
            assertTrue(result.getEpochs().isEmpty());
        }

        @Test
        @DisplayName("Should preserve non-active epoch data")
        void shouldPreserveNonActiveEpochData() {
            // Epoch 1 is NOT active (already closed), epoch 2 IS active
            EpochState es1 = EpochState.fresh().markNodeCompleted("mcp:step1");
            EpochState es2 = EpochState.fresh().markNodeCompleted("mcp:step2");
            DagState dag = new DagState(2, 0, 2,
                    Map.of(1, es1, 2, es2), Set.of(2));

            DagState result = dag.closeAllActiveEpochs();

            assertTrue(result.getActiveEpochs().isEmpty());
            // Epoch 1 should still exist (was not active, not pruned)
            assertEquals(1, result.getEpochs().size());
            assertNotNull(result.getEpochState(1));
            // Epoch 2 should be pruned (was active)
            assertNull(result.getEpochState(2));
        }

        @Test
        @DisplayName("Should handle single active epoch")
        void shouldHandleSingleActiveEpoch() {
            EpochState es = EpochState.fresh().markNodeCompleted("mcp:step1");
            DagState dag = new DagState(1, 0, 1,
                    Map.of(1, es), Set.of(1));

            DagState result = dag.closeAllActiveEpochs();

            assertTrue(result.getActiveEpochs().isEmpty());
            assertTrue(result.getEpochs().isEmpty());
        }
    }

    @Nested
    @DisplayName("StateSnapshot.closeAllActiveEpochsForDag()")
    class SnapshotCloseAllActiveEpochsForDagTests {

        @Test
        @DisplayName("Should close all active epochs for specified trigger")
        void shouldCloseActiveEpochsForTrigger() {
            String triggerId = "trigger:webhook";
            EpochState es1 = EpochState.fresh().markNodeCompleted("mcp:step1");
            EpochState es2 = EpochState.fresh().addRunningNode("mcp:step2");
            DagState dag = new DagState(2, 0, 2,
                    Map.of(1, es1, 2, es2), Set.of(1, 2));

            StateSnapshot snapshot = StateSnapshot.empty().withDagState(triggerId, dag);
            StateSnapshot result = snapshot.closeAllActiveEpochsForDag(triggerId);

            DagState resultDag = result.getDags().get(triggerId);
            assertNotNull(resultDag);
            assertTrue(resultDag.getActiveEpochs().isEmpty());
            assertTrue(resultDag.getEpochs().isEmpty());
        }

        @Test
        @DisplayName("Should not affect other triggers")
        void shouldNotAffectOtherTriggers() {
            String trigger1 = "trigger:webhook";
            String trigger2 = "trigger:manual";

            EpochState es1 = EpochState.fresh().markNodeCompleted("mcp:a");
            DagState dag1 = new DagState(1, 0, 1,
                    Map.of(1, es1), Set.of(1));

            EpochState es2 = EpochState.fresh().markNodeCompleted("mcp:b");
            DagState dag2 = new DagState(1, 0, 1,
                    Map.of(1, es2), Set.of(1));

            StateSnapshot snapshot = StateSnapshot.empty()
                    .withDagState(trigger1, dag1)
                    .withDagState(trigger2, dag2);

            StateSnapshot result = snapshot.closeAllActiveEpochsForDag(trigger1);

            // trigger1 should be closed
            assertTrue(result.getDags().get(trigger1).getActiveEpochs().isEmpty());
            // trigger2 should be unaffected
            assertEquals(Set.of(1), result.getDags().get(trigger2).getActiveEpochs());
        }

        @Test
        @DisplayName("Should handle non-existent trigger gracefully")
        void shouldHandleNonExistentTrigger() {
            StateSnapshot snapshot = StateSnapshot.empty();
            StateSnapshot result = snapshot.closeAllActiveEpochsForDag("trigger:unknown");

            // Should create an initial DagState with no active epochs
            DagState dag = result.getDags().get("trigger:unknown");
            assertNotNull(dag);
            assertTrue(dag.getActiveEpochs().isEmpty());
        }
    }

    @Nested
    @DisplayName("Lazy flat views (regression guard for OOM 2026-05-06)")
    class LazyFlatViewsRegression {

        @Test
        @DisplayName("Mutation chain does NOT compute flat views - they remain null until first getter call")
        void mutationChainStaysLazy() throws Exception {
            // Build a non-trivial snapshot, then chain mutations. Pre-fix, every
            // mutation eagerly recomputed 9 flat collections (computeFlatSet × 6
            // + computeFlatBranches/Loops/Splits × 3) - the dominant per-mutation
            // allocation profile observed in the OOM. Post-fix, no flat collection
            // is touched on the mutation hot path.
            String trig = "trigger:cron";
            StateSnapshot s = StateSnapshot.empty()
                .addReadyNode(trig, trig)
                .addRunningNode(trig, trig)
                .markNodeCompleted(trig, trig)
                .addReadyNode(trig, "mcp:fetch")
                .addRunningNode(trig, "mcp:fetch")
                .markNodeCompleted(trig, "mcp:fetch");

            // Reflectively peek at the cache fields. They must all be null -
            // proof that no getter (and therefore no computeFlat*) ran on the
            // mutation chain.
            assertCacheFieldNull(s, "completedNodeIds");
            assertCacheFieldNull(s, "failedNodeIds");
            assertCacheFieldNull(s, "skippedNodeIds");
            assertCacheFieldNull(s, "runningNodeIds");
            assertCacheFieldNull(s, "readyNodeIds");
            assertCacheFieldNull(s, "awaitingSignalNodeIds");
            assertCacheFieldNull(s, "decisionBranches");
            assertCacheFieldNull(s, "loops");
            assertCacheFieldNull(s, "splits");

            // First getter call MUST compute and populate the cache.
            Set<String> completed = s.getCompletedNodeIds();
            assertTrue(completed.contains(trig) && completed.contains("mcp:fetch"),
                "Lazy compute must produce the same content as eager would have");
            assertCacheFieldNonNull(s, "completedNodeIds");

            // Second call is a pure cached read - same instance returned.
            assertSame(completed, s.getCompletedNodeIds(),
                "Lazy cache must memoize: second call returns the same instance");
        }

        @Test
        @DisplayName("V2 backward-compat path migrates inputs to dags but keeps caches lazy")
        void v2BackwardCompatStaysLazy() throws Exception {
            // V2 path = `dags` null/empty + flat fields used as input. Inputs are
            // migrated into a default dag (which becomes the source of truth);
            // caches stay null so getters recompute from the *post-migration* dag.
            // This avoids the cache/dags drift bug flagged by the audit: if
            // migrateFlatToDags early-returns Map.of() for some input shapes, a
            // pre-staged cache would still hold the input value, but recomputing
            // from dags would return Map.of().
            StateSnapshot s = new StateSnapshot(
                2, 0L,
                null,                              // dags (triggers V2 path)
                Set.of("nodeA"),                  // completedNodeIds
                Set.of("nodeB"),                  // failedNodeIds
                Set.of(),                          // skippedNodeIds
                Set.of(),                          // runningNodeIds
                Set.of("nodeC"),                  // readyNodeIds
                Map.of(),                          // nodes
                Map.of(),                          // edges
                Map.of(),                          // decisionBranches
                Map.of(),                          // loops
                Map.of(),                          // splits
                Set.of(),                          // awaitingSignalNodeIds
                0L, java.time.Instant.now()
            );

            // Caches stay null until first getter call (lazy contract).
            assertCacheFieldNull(s, "completedNodeIds");
            assertCacheFieldNull(s, "failedNodeIds");
            assertCacheFieldNull(s, "readyNodeIds");

            // Getters recompute from the migrated dag and the result equals the input.
            assertEquals(Set.of("nodeA"), s.getCompletedNodeIds());
            assertEquals(Set.of("nodeB"), s.getFailedNodeIds());
            assertEquals(Set.of("nodeC"), s.getReadyNodeIds());

            // After the getter call, the cache is populated.
            assertCacheFieldNonNull(s, "completedNodeIds");
        }

        @Test
        @DisplayName("Map-typed caches (decisionBranches, loops, splits) follow the same lazy contract")
        void mapCachesAreLazy() throws Exception {
            // Map caches have a different code path than Set caches (they go through
            // computeFlatBranches/Loops/Splits, which build nested HashSets / records).
            // A regression in just one of them would slip past the Set-only test.
            String trig = "trigger:cron";
            StateSnapshot s = StateSnapshot.empty()
                .addReadyNode(trig, "core:decision")
                .addRunningNode(trig, "core:decision")
                .markNodeCompleted(trig, "core:decision");

            assertCacheFieldNull(s, "decisionBranches");
            assertCacheFieldNull(s, "loops");
            assertCacheFieldNull(s, "splits");

            Map<String, Set<String>> branches = s.getDecisionBranches();
            assertCacheFieldNonNull(s, "decisionBranches");
            assertSame(branches, s.getDecisionBranches(),
                "Lazy Map cache must memoize");

            assertCacheFieldNull(s, "loops");
            s.getLoops();
            assertCacheFieldNonNull(s, "loops");

            assertCacheFieldNull(s, "splits");
            s.getSplits();
            assertCacheFieldNonNull(s, "splits");
        }

        @Test
        @DisplayName("Cache content equals what dags-derived computation produces - invariant lock for drift")
        void cacheContentMatchesDagsState() throws Exception {
            // After mutations, walk each dag's epochs by hand and verify the cache
            // output matches the union. This is the brutal-observation lock: any
            // future code path that mutates dags but staged a stale value in the
            // cache (e.g. V2 path divergence with migrateFlatToDags early-return)
            // would fail here.
            String trig = "trigger:cron";
            StateSnapshot s = StateSnapshot.empty()
                .addReadyNode(trig, "n1")
                .addRunningNode(trig, "n1")
                .markNodeCompleted(trig, "n1")
                .addReadyNode(trig, "n2")
                .markNodeFailed(trig, "n2")
                .addReadyNode(trig, "n3")
                .markNodeSkipped(trig, "n3");

            // Walk dags manually with the same fallback rule production uses:
            // active epochs if any, else currentEpochState().
            Set<String> expectedCompleted = new java.util.HashSet<>();
            Set<String> expectedFailed = new java.util.HashSet<>();
            Set<String> expectedSkipped = new java.util.HashSet<>();
            for (DagState dag : s.getDags().values()) {
                Iterable<EpochState> epochs = dag.hasActiveEpochs()
                    ? dag.getActiveEpochs().stream().map(dag::getEpochState).filter(java.util.Objects::nonNull).toList()
                    : java.util.List.of(dag.currentEpochState());
                for (EpochState es : epochs) {
                    expectedCompleted.addAll(es.getCompletedNodeIds());
                    expectedFailed.addAll(es.getFailedNodeIds());
                    expectedSkipped.addAll(es.getSkippedNodeIds());
                }
            }

            assertEquals(expectedCompleted, s.getCompletedNodeIds(),
                "Cache must equal dags-derived completed set");
            assertEquals(expectedFailed, s.getFailedNodeIds(),
                "Cache must equal dags-derived failed set");
            assertEquals(expectedSkipped, s.getSkippedNodeIds(),
                "Cache must equal dags-derived skipped set");
        }

        @Test
        @DisplayName("Jackson roundtrip preserves content; deserialized snapshot starts with lazy caches")
        void jacksonRoundtripIsLazy() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());

            String trig = "trigger:cron";
            StateSnapshot original = StateSnapshot.empty()
                .addReadyNode(trig, "n1")
                .addRunningNode(trig, "n1")
                .markNodeCompleted(trig, "n1");

            String json = mapper.writeValueAsString(original);
            StateSnapshot deserialized = mapper.readValue(json, StateSnapshot.class);

            // V3 path: dags is the source of truth - caches must start lazy on
            // a freshly deserialized snapshot. (Previous serialization populated
            // the original's caches via getters, but the JSON is dag-keyed only.)
            assertCacheFieldNull(deserialized, "completedNodeIds");
            assertCacheFieldNull(deserialized, "decisionBranches");

            // Content equivalence - the lazy getter computes the same set as
            // the original (which had its cache populated by writeValueAsString).
            assertEquals(original.getCompletedNodeIds(), deserialized.getCompletedNodeIds());
            assertEquals(original.getReadyNodeIds(), deserialized.getReadyNodeIds());
        }


        private static void assertCacheFieldNull(StateSnapshot s, String name) throws Exception {
            assertNull(reflectField(s, name),
                "Cache field '" + name + "' must be null - mutation chain triggered an eager flat-view compute");
        }

        private static void assertCacheFieldNonNull(StateSnapshot s, String name) throws Exception {
            assertNotNull(reflectField(s, name),
                "Cache field '" + name + "' must be populated after a getter / V2 init");
        }

        private static Object reflectField(StateSnapshot s, String name) throws Exception {
            java.lang.reflect.Field f = StateSnapshot.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(s);
        }
    }

    /**
     * P2.3 site 9 - flat findDagContaining MUST NOT consult runningNodeIds.
     * Post-elide (default-ON), running state lives only in Redis. The flat
     * methods route via findDagContaining, which routes via epochContainsNode.
     * If we re-introduce the runningNodeIds clause, a strictly-running node
     * gets located in JSONB only when the elide flag is OFF - silent
     * inconsistency between elided and non-elided tenants. These tests pin
     * the contract.
     */
    @Nested
    @DisplayName("Site 9 - flat findDagContaining ignores runningNodeIds (P2.3)")
    class Site9FlatRoutingDropsRunning {

        @Test
        @DisplayName("epochContainsNode does NOT detect a strictly-running node (P2.3 site 9 drop)")
        void epochContainsNodeIgnoresRunningNodeIds() throws Exception {
            // Direct unit-test of the private static epochContainsNode via reflection.
            // Pre-P2.3 site-9 drop, this returned true when nodeId was in runningNodeIds.
            // Post-drop, it MUST return false - running state lives only in Redis.
            EpochState es = new EpochState(
                    Set.of(),                         // completed
                    Set.of(),                         // failed
                    Set.of(),                         // skipped
                    Set.of("mcp:running_x"),          // running - the only set with the node
                    Set.of("trigger:webhook"),        // ready
                    Set.of(),                         // awaiting_signal
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Instant.parse("2026-05-08T10:00:00Z"));

            java.lang.reflect.Method m = StateSnapshot.class.getDeclaredMethod(
                    "epochContainsNode", EpochState.class, String.class);
            m.setAccessible(true);

            // The strict contract: a node ONLY in runningNodeIds is invisible to flat predicate.
            assertFalse((Boolean) m.invoke(null, es, "mcp:running_x"),
                    "Post-P2.3 site-9 drop, runningNodeIds is NOT consulted by epochContainsNode");

            // The trigger node IS in readyNodeIds - must still be found.
            assertTrue((Boolean) m.invoke(null, es, "trigger:webhook"),
                    "Sanity: readyNodeIds is still consulted (only runningNodeIds was dropped)");
        }

        @Test
        @DisplayName("Flat findDagContaining still routes correctly when node is in completed/ready/awaiting_signal sets")
        void flatRoutingStillWorksForNonRunningNodes() {
            // Sanity: dropping runningNodeIds from the predicate must not break
            // the legitimate cases where the node IS in JSONB-tracked sets.
            // 10-arg backward-compat: completed, failed, skipped, running, ready, awaiting, decisionBranches, loops, splits, startedAt.
            EpochState es = new EpochState(
                    Set.of("mcp:done"),               // completed - findable
                    Set.of(),                         // failed
                    Set.of(),                         // skipped
                    Set.of(),                         // running
                    Set.of("trigger:webhook"),        // ready
                    Set.of("core:wait"),              // awaiting_signal - findable
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Instant.parse("2026-05-08T10:00:00Z"));
            DagState dag = new DagState(0, 0, 1, Map.of(0, es), Set.of(0));
            StateSnapshot snap = StateSnapshot.empty().withDagState("trigger:webhook", dag);

            // markNodeFailed on the completed node - flat routing must find the right DAG.
            StateSnapshot after1 = snap.markNodeFailed("mcp:done");
            assertTrue(after1.getDags().get("trigger:webhook")
                    .currentEpochState().getFailedNodeIds().contains("mcp:done"));

            // markNodeFailed on the awaiting_signal node - same.
            StateSnapshot after2 = snap.markNodeFailed("core:wait");
            assertTrue(after2.getDags().get("trigger:webhook")
                    .currentEpochState().getFailedNodeIds().contains("core:wait"));
        }
    }
}
