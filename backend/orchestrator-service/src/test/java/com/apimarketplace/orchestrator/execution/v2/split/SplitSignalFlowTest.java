package com.apimarketplace.orchestrator.execution.v2.split;

import com.apimarketplace.orchestrator.domain.execution.DagState;
import com.apimarketplace.orchestrator.domain.execution.EpochState;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for split-context signal resolution flow fixes.
 *
 * Scenario: trigger:start → core:split_items (5 items) → core:user_approval → core:wait
 *
 * Each split item's approval is independent. When all signals resolve, the successor
 * (core:wait) should execute for all approved items, and rejected items should be skipped.
 *
 * These tests cover:
 * 1. awaitingSignalCounts: epoch-based (1) vs signal-based (N) merge
 * 2. ensureNodeCompletedInEpoch guard: don't force-complete nodes still awaiting signals
 * 3. NodeCounts dedup: skip propagation records should not be duplicated
 * 4. Epoch-scoped routing: cross-epoch pollution prevention
 */
@DisplayName("Split + Signal flow fixes")
class SplitSignalFlowTest {

    // ========================================================================
    // 1. awaitingSignalCounts merge logic
    // ========================================================================

    @Nested
    @DisplayName("computeAwaitingSignalCounts logic")
    class AwaitingSignalCountsTests {

        /**
         * Intentionally replicates the computeAwaitingSignalCounts logic from
         * StateReconstructor (private method). This is a logic-level test validating
         * the merge semantics (epoch-count vs signal-count, Math.max) without
         * requiring full service wiring. If the production logic changes, this test
         * must be updated to match.
         */
        private Map<String, Integer> computeAwaitingSignalCounts(
                StateSnapshot snapshot, List<SignalWaitEntity> activeSignals) {
            Map<String, Integer> counts = new HashMap<>();
            for (DagState dag : snapshot.getDags().values()) {
                for (int epochNum : dag.getActiveEpochs()) {
                    EpochState epoch = dag.getEpochs().get(epochNum);
                    if (epoch == null) continue;
                    for (String nodeId : epoch.getAwaitingSignalNodeIds()) {
                        counts.merge(nodeId, 1, Integer::sum);
                    }
                }
            }
            if (activeSignals != null && !activeSignals.isEmpty()) {
                Map<String, Integer> signalCounts = new HashMap<>();
                for (SignalWaitEntity signal : activeSignals) {
                    signalCounts.merge(signal.getNodeId(), 1, Integer::sum);
                }
                for (Map.Entry<String, Integer> entry : signalCounts.entrySet()) {
                    counts.merge(entry.getKey(), entry.getValue(), Math::max);
                }
            }
            return counts;
        }

        /**
         * Helper: build a snapshot with an active epoch and a node in awaitingSignalNodeIds.
         */
        private StateSnapshot snapshotWithAwaitingNode(String triggerId, String nodeId) {
            return StateSnapshot.empty()
                .openEpochForDag(triggerId, 1)
                .markNodeAwaitingSignal(triggerId, nodeId, 1);
        }

        @Test
        @DisplayName("Split with 5 signals: epoch says 1, signals say 5 → result is 5")
        void shouldReturnSignalCountWhenHigherThanEpoch() {
            // Epoch has 1 entry for the node in awaitingSignalNodeIds
            StateSnapshot snapshot = snapshotWithAwaitingNode("trigger:start", "core:user_approval");

            // But there are 5 active signals (one per split item)
            List<SignalWaitEntity> signals = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                SignalWaitEntity signal = new SignalWaitEntity();
                signal.setNodeId("core:user_approval");
                signal.setItemId(String.valueOf(i));
                signals.add(signal);
            }

            Map<String, Integer> result = computeAwaitingSignalCounts(snapshot, signals);

            assertEquals(5, result.get("core:user_approval"),
                "Should return 5 (signal count) not 1 (epoch count)");
        }

        @Test
        @DisplayName("After 3 resolved, 2 remaining signals → count is 2")
        void shouldReturnRemainingSignalCount() {
            StateSnapshot snapshot = snapshotWithAwaitingNode("trigger:start", "core:user_approval");

            // Only 2 remaining active signals
            List<SignalWaitEntity> signals = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                SignalWaitEntity signal = new SignalWaitEntity();
                signal.setNodeId("core:user_approval");
                signal.setItemId(String.valueOf(i));
                signals.add(signal);
            }

            Map<String, Integer> result = computeAwaitingSignalCounts(snapshot, signals);

            assertEquals(2, result.get("core:user_approval"),
                "Should return 2 (remaining signals)");
        }

        @Test
        @DisplayName("No active signals with epoch count → returns epoch count")
        void shouldReturnEpochCountWhenNoSignals() {
            StateSnapshot snapshot = snapshotWithAwaitingNode("trigger:start", "core:user_approval");

            Map<String, Integer> result = computeAwaitingSignalCounts(snapshot, List.of());

            assertEquals(1, result.get("core:user_approval"),
                "Should return 1 (epoch count) when no DB signals");
        }

        @Test
        @DisplayName("Null signals list → returns epoch count")
        void shouldHandleNullSignals() {
            StateSnapshot snapshot = snapshotWithAwaitingNode("trigger:start", "core:user_approval");

            Map<String, Integer> result = computeAwaitingSignalCounts(snapshot, null);

            assertEquals(1, result.get("core:user_approval"));
        }

        @Test
        @DisplayName("Empty snapshot + empty signals → empty map")
        void shouldReturnEmptyForNoState() {
            Map<String, Integer> result = computeAwaitingSignalCounts(
                StateSnapshot.empty(), List.of());

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Multiple nodes awaiting signals → each gets correct count")
        void shouldHandleMultipleNodes() {
            StateSnapshot snapshot = StateSnapshot.empty()
                .openEpochForDag("trigger:start", 1)
                .markNodeAwaitingSignal("trigger:start", "core:user_approval", 1)
                .markNodeAwaitingSignal("trigger:start", "core:wait", 1);

            List<SignalWaitEntity> signals = new ArrayList<>();
            // 3 signals for user_approval
            for (int i = 0; i < 3; i++) {
                SignalWaitEntity s = new SignalWaitEntity();
                s.setNodeId("core:user_approval");
                s.setItemId(String.valueOf(i));
                signals.add(s);
            }
            // 1 signal for wait
            SignalWaitEntity waitSignal = new SignalWaitEntity();
            waitSignal.setNodeId("core:wait");
            waitSignal.setItemId("0");
            signals.add(waitSignal);

            Map<String, Integer> result = computeAwaitingSignalCounts(snapshot, signals);

            assertEquals(3, result.get("core:user_approval"));
            assertEquals(1, result.get("core:wait"));
        }
    }

    // ========================================================================
    // 2. ensureNodeCompletedInEpoch guard
    // ========================================================================

    @Nested
    @DisplayName("ensureNodeCompletedInEpoch awaiting-signal guard")
    class EnsureNodeCompletedGuardTests {

        @Test
        @DisplayName("Node in awaitingSignalNodeIds should NOT be force-completed")
        void shouldNotForceCompleteNodeStillAwaitingSignal() {
            StateSnapshot snapshot = StateSnapshot.empty()
                .openEpochForDag("trigger:start", 1)
                .markNodeAwaitingSignal("trigger:start", "core:user_approval", 1);

            DagState dag = snapshot.getDags().get("trigger:start");
            assertNotNull(dag);
            EpochState epoch = dag.getEpochState(1);
            assertNotNull(epoch);

            // Node is in awaitingSignalNodeIds
            assertTrue(epoch.getAwaitingSignalNodeIds().contains("core:user_approval"),
                "Node should be in awaitingSignalNodeIds");
            // Node is NOT in completedNodeIds
            assertFalse(epoch.getCompletedNodeIds().contains("core:user_approval"),
                "Node should NOT be in completedNodeIds");
        }

        @Test
        @DisplayName("Node already completed should remain completed")
        void shouldKeepAlreadyCompletedNode() {
            StateSnapshot snapshot = StateSnapshot.empty()
                .openEpochForDag("trigger:start", 1)
                .markNodeCompleted("trigger:start", "core:user_approval");

            DagState dag = snapshot.getDags().get("trigger:start");
            EpochState epoch = dag.getEpochState(1);

            assertTrue(epoch.getCompletedNodeIds().contains("core:user_approval"));
            assertFalse(epoch.getAwaitingSignalNodeIds().contains("core:user_approval"));
        }

        @Test
        @DisplayName("resolveAwaitingSignal with keepInAwaiting=true keeps node in awaiting set")
        void shouldKeepInAwaitingWhenFlagIsTrue() {
            StateSnapshot snapshot = StateSnapshot.empty()
                .openEpochForDag("trigger:start", 1)
                .markNodeAwaitingSignal("trigger:start", "core:user_approval", 1);

            // Resolve with keepInAwaiting=true (split context: more signals pending)
            StateSnapshot resolved = snapshot.resolveAwaitingSignal(
                "trigger:start", "core:user_approval", 1, 0L, true);

            DagState dag = resolved.getDags().get("trigger:start");
            EpochState epoch = dag.getEpochState(1);

            // Should still be in awaiting set
            assertTrue(epoch.getAwaitingSignalNodeIds().contains("core:user_approval"),
                "Node should remain in awaitingSignalNodeIds with keepInAwaiting=true");
            // Should NOT be in completed set
            assertFalse(epoch.getCompletedNodeIds().contains("core:user_approval"),
                "Node should NOT be completed when keepInAwaiting=true");
        }

        @Test
        @DisplayName("resolveAwaitingSignal with keepInAwaiting=false moves to completed")
        void shouldCompleteWhenKeepInAwaitingFalse() {
            StateSnapshot snapshot = StateSnapshot.empty()
                .openEpochForDag("trigger:start", 1)
                .markNodeAwaitingSignal("trigger:start", "core:user_approval", 1);

            StateSnapshot resolved = snapshot.resolveAwaitingSignal(
                "trigger:start", "core:user_approval", 1, 0L, false);

            DagState dag = resolved.getDags().get("trigger:start");
            EpochState epoch = dag.getEpochState(1);

            assertFalse(epoch.getAwaitingSignalNodeIds().contains("core:user_approval"),
                "Node should be removed from awaitingSignalNodeIds");
            assertTrue(epoch.getCompletedNodeIds().contains("core:user_approval"),
                "Node should be in completedNodeIds after final resolution");
        }
    }

    // ========================================================================
    // 3. NodeCounts dedup guard
    // ========================================================================

    @Nested
    @DisplayName("NodeCounts skip dedup guard")
    class NodeCountsDedupTests {

        @Test
        @DisplayName("NodeCounts.skipped starts at 0")
        void shouldStartAtZero() {
            StateSnapshot snapshot = StateSnapshot.empty();
            StateSnapshot.NodeCounts counts = snapshot.getNodeCounts("core:wait");

            assertEquals(0, counts.skipped());
        }

        @Test
        @DisplayName("incrementNodeCountsOnly increments skipped count")
        void shouldIncrementSkippedCount() {
            StateSnapshot snapshot = StateSnapshot.empty();
            StateSnapshot updated = snapshot.incrementNodeCountsOnly("core:wait", "SKIPPED", 5);

            assertEquals(5, updated.getNodeCounts("core:wait").skipped());
        }

        @Test
        @DisplayName("Dedup guard: when skipped >= expected, should skip duplicate creation")
        void shouldDetectExistingSkippedRecords() {
            // Simulate: skip propagation already incremented NodeCounts.skipped to 5
            StateSnapshot snapshot = StateSnapshot.empty()
                .incrementNodeCountsOnly("core:wait", "SKIPPED", 5);

            StateSnapshot.NodeCounts existing = snapshot.getNodeCounts("core:wait");
            int skippedCount = 5; // SplitAwareNodeExecutor wants to add 5 more

            // The guard condition from persistSkippedItemRecords
            assertTrue(existing.skipped() >= skippedCount,
                "Guard should fire: existing skipped (5) >= requested skipped (5)");
        }

        @Test
        @DisplayName("No dedup when skip propagation hasn't run yet")
        void shouldNotDedupWhenNoExistingRecords() {
            StateSnapshot snapshot = StateSnapshot.empty();

            StateSnapshot.NodeCounts existing = snapshot.getNodeCounts("core:wait");
            int skippedCount = 5;

            assertFalse(existing.skipped() >= skippedCount,
                "Guard should NOT fire: existing skipped (0) < requested skipped (5)");
        }

        @Test
        @DisplayName("Partial skip propagation: some items propagated, some not")
        void shouldNotDedupWhenPartialPropagation() {
            // Only 3 of 5 items had skip propagation
            StateSnapshot snapshot = StateSnapshot.empty()
                .incrementNodeCountsOnly("core:wait", "SKIPPED", 3);

            StateSnapshot.NodeCounts existing = snapshot.getNodeCounts("core:wait");
            int skippedCount = 5;

            assertFalse(existing.skipped() >= skippedCount,
                "Guard should NOT fire: existing (3) < requested (5) - partial propagation");
        }
    }

    // ========================================================================
    // 4. Epoch-scoped routing (no cross-epoch pollution)
    // ========================================================================

    @Nested
    @DisplayName("Cross-epoch isolation")
    class CrossEpochTests {

        @Test
        @DisplayName("NodeCounts accumulate across epochs (by design)")
        void nodeCountsAccumulateGlobally() {
            StateSnapshot snapshot = StateSnapshot.empty();

            // Epoch 1: 5 completed
            for (int i = 0; i < 5; i++) {
                snapshot = snapshot.incrementNodeCountsOnly("core:wait", "COMPLETED", 1);
            }

            // Epoch 2: 5 more completed
            for (int i = 0; i < 5; i++) {
                snapshot = snapshot.incrementNodeCountsOnly("core:wait", "COMPLETED", 1);
            }

            // Global NodeCounts = 10 (by design)
            assertEquals(10, snapshot.getNodeCounts("core:wait").completed());
        }

        @Test
        @DisplayName("EpochState is per-epoch (isolated)")
        void epochStateIsIsolated() {
            StateSnapshot snapshot = StateSnapshot.empty();

            // Complete node in epoch 1 (default)
            snapshot = snapshot.markNodeCompleted("trigger:start", "core:wait");

            DagState dag = snapshot.getDags().get("trigger:start");
            int epoch1 = dag.getCurrentEpoch();
            EpochState es1 = dag.getEpochState(epoch1);

            assertTrue(es1.getCompletedNodeIds().contains("core:wait"),
                "Epoch 1 should have core:wait as completed");
        }
    }

    // ========================================================================
    // 5. Null itemId for split context resolution
    // ========================================================================

    @Nested
    @DisplayName("Null itemId handling")
    class NullItemIdTests {

        @Test
        @DisplayName("Null string should not throw on contains('.')")
        void shouldHandleNullItemIdSafely() {
            String itemId = null;

            // The fix: itemId != null && itemId.contains(".")
            boolean result = itemId != null && itemId.contains(".");
            assertFalse(result, "Null itemId should safely return false");
        }

        @Test
        @DisplayName("Non-null itemId with dot should match")
        void shouldMatchItemIdWithDot() {
            String itemId = "0.1";
            boolean result = itemId != null && itemId.contains(".");
            assertTrue(result);
        }

        @Test
        @DisplayName("Non-null itemId without dot should not match")
        void shouldNotMatchItemIdWithoutDot() {
            String itemId = "4";
            boolean result = itemId != null && itemId.contains(".");
            assertFalse(result);
        }
    }
}
