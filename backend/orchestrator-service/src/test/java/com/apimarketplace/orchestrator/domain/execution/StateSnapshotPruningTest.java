package com.apimarketplace.orchestrator.domain.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that closeAndPruneEpoch removes epoch data from StateSnapshot
 * so the JSONB stays at constant size (only active epochs).
 */
@DisplayName("StateSnapshot - Epoch Pruning")
class StateSnapshotPruningTest {

    private static final String TRIGGER = "trigger:webhook";

    @Test
    @DisplayName("10 epochs opened, 9 closed+pruned → DagState.epochs has 1 entry")
    void pruning_keepsOnlyActiveEpoch() {
        StateSnapshot snapshot = StateSnapshot.empty();

        // Open 10 epochs
        for (int i = 0; i < 10; i++) {
            snapshot = snapshot.resetDag(TRIGGER, i);
            snapshot = snapshot.markNodeCompleted(TRIGGER, "trigger:webhook", i);
            snapshot = snapshot.markNodeCompleted(TRIGGER, "mcp:step1", i);
        }

        DagState dagBefore = snapshot.getDags().get(TRIGGER);
        assertEquals(10, dagBefore.getActiveEpochs().size());
        assertEquals(10, dagBefore.getEpochs().size());

        // Close+prune epochs 0-8, leave epoch 9 active
        for (int i = 0; i < 9; i++) {
            snapshot = snapshot.closeAndPruneEpochForDag(TRIGGER, i);
        }

        DagState dagAfter = snapshot.getDags().get(TRIGGER);
        // Only epoch 9 should remain in the epochs map
        assertEquals(1, dagAfter.getEpochs().size());
        assertTrue(dagAfter.getEpochs().containsKey(9));
        // Only epoch 9 should be active
        assertEquals(1, dagAfter.getActiveEpochs().size());
        assertTrue(dagAfter.getActiveEpochs().contains(9));
        // currentEpoch unchanged
        assertEquals(9, dagAfter.getCurrentEpoch());
    }

    @Test
    @DisplayName("closeAndPruneEpochForDag removes epoch from both activeEpochs and epochs map")
    void pruning_removesFromBothSets() {
        StateSnapshot snapshot = StateSnapshot.empty()
                .resetDag(TRIGGER, 0)
                .markNodeCompleted(TRIGGER, "mcp:step1", 0);

        // Verify epoch 0 is in both
        DagState dag = snapshot.getDags().get(TRIGGER);
        assertTrue(dag.getActiveEpochs().contains(0));
        assertNotNull(dag.getEpochState(0));

        // Prune
        StateSnapshot pruned = snapshot.closeAndPruneEpochForDag(TRIGGER, 0);

        DagState dagPruned = pruned.getDags().get(TRIGGER);
        assertFalse(dagPruned.getActiveEpochs().contains(0));
        assertNull(dagPruned.getEpochState(0));
    }

    @Test
    @DisplayName("Global NodeCounts survive pruning (never reset)")
    void pruning_preservesGlobalNodeCounts() {
        StateSnapshot snapshot = StateSnapshot.empty()
                .resetDag(TRIGGER, 0)
                .markNodeCompleted(TRIGGER, "mcp:step1", 0)
                .markNodeCompleted(TRIGGER, "mcp:step1", 0); // 2 completions in epoch 0

        // Add epoch 1
        snapshot = snapshot.resetDag(TRIGGER, 1)
                .markNodeCompleted(TRIGGER, "mcp:step1", 1);

        // Global counts: 3 completions total
        assertEquals(3, snapshot.getNodeCounts("mcp:step1").completed());

        // Prune epoch 0
        StateSnapshot pruned = snapshot.closeAndPruneEpochForDag(TRIGGER, 0);

        // Global counts PRESERVED
        assertEquals(3, pruned.getNodeCounts("mcp:step1").completed());
    }

    @Test
    @DisplayName("Pruning all epochs leaves empty epochs map")
    void pruning_allEpochs_leavesEmptyMap() {
        StateSnapshot snapshot = StateSnapshot.empty()
                .resetDag(TRIGGER, 0)
                .closeAndPruneEpochForDag(TRIGGER, 0);

        DagState dag = snapshot.getDags().get(TRIGGER);
        assertTrue(dag.getEpochs().isEmpty());
        assertFalse(dag.hasActiveEpochs());
    }

    @Test
    @DisplayName("Pruning non-existent epoch is safe (no-op)")
    void pruning_nonExistentEpoch_isNoOp() {
        StateSnapshot snapshot = StateSnapshot.empty()
                .resetDag(TRIGGER, 0);

        StateSnapshot pruned = snapshot.closeAndPruneEpochForDag(TRIGGER, 99);

        DagState dag = pruned.getDags().get(TRIGGER);
        // Epoch 0 unchanged
        assertTrue(dag.getActiveEpochs().contains(0));
        assertNotNull(dag.getEpochState(0));
    }
}
