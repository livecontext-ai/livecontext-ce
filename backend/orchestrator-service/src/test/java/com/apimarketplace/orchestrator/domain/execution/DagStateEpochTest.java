package com.apimarketplace.orchestrator.domain.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DagState.openEpoch() fireCount increment
 * and related epoch lifecycle methods.
 */
@DisplayName("DagState epoch lifecycle")
class DagStateEpochTest {

    /**
     * openEpoch should increment fireCount from 0 to 1.
     */
    @Test
    @DisplayName("openEpoch increments fireCount from initial state")
    void openEpoch_incrementsFireCount() {
        DagState initial = DagState.initial();
        assertEquals(0, initial.getFireCount(), "Initial fireCount should be 0");

        DagState afterOpen = initial.openEpoch(0);
        assertEquals(1, afterOpen.getFireCount(), "fireCount should be 1 after openEpoch");
    }

    /**
     * Multiple openEpoch calls should each increment fireCount.
     */
    @Test
    @DisplayName("openEpoch multiple times increments fireCount each time")
    void openEpoch_multipleEpochs_incrementsFireCountEachTime() {
        DagState state = DagState.initial();
        assertEquals(0, state.getFireCount());

        state = state.openEpoch(0);
        assertEquals(1, state.getFireCount(), "fireCount should be 1 after first openEpoch");

        state = state.openEpoch(1);
        assertEquals(2, state.getFireCount(), "fireCount should be 2 after second openEpoch");

        state = state.openEpoch(2);
        assertEquals(3, state.getFireCount(), "fireCount should be 3 after third openEpoch");
    }

    /**
     * openEpoch should add the epoch to activeEpochs.
     */
    @Test
    @DisplayName("openEpoch adds epoch to activeEpochs")
    void openEpoch_addsToActiveEpochs() {
        DagState initial = DagState.initial();
        assertTrue(initial.getActiveEpochs().isEmpty(), "Initial should have no active epochs");

        DagState afterOpen = initial.openEpoch(5);
        assertTrue(afterOpen.getActiveEpochs().contains(5),
                "Epoch 5 should be in activeEpochs after openEpoch(5)");
        assertEquals(1, afterOpen.getActiveEpochs().size());
    }

    /**
     * If epoch already has state (e.g., from prepareNextCycle), openEpoch should preserve it.
     * putIfAbsent ensures existing EpochState is NOT overwritten.
     */
    @Test
    @DisplayName("openEpoch does not reset existing epoch state")
    void openEpoch_doesNotResetExistingEpochState() {
        // Create a DagState with epoch 3 already having state (e.g., from prepareNextCycle)
        DagState prepared = DagState.initial().prepareNextCycle(3, "trigger:manual");
        EpochState existingState = prepared.getEpochState(3);
        assertNotNull(existingState, "Epoch 3 should exist after prepareNextCycle");
        assertTrue(existingState.getReadyNodeIds().contains("trigger:manual"),
                "Epoch 3 should have trigger:manual as ready node");

        // Now openEpoch(3) should NOT reset the existing EpochState
        DagState opened = prepared.openEpoch(3);
        EpochState afterOpen = opened.getEpochState(3);
        assertNotNull(afterOpen, "Epoch 3 should still exist after openEpoch");
        assertTrue(afterOpen.getReadyNodeIds().contains("trigger:manual"),
                "Existing state should be preserved (trigger:manual still ready)");
    }

    /**
     * advanceEpoch should also increment fireCount (existing behavior preserved).
     */
    @Test
    @DisplayName("advanceEpoch increments fireCount")
    void advanceEpoch_incrementsFireCount() {
        DagState initial = DagState.initial();
        assertEquals(0, initial.getFireCount());

        DagState advanced = initial.advanceEpoch(1);
        assertEquals(1, advanced.getFireCount(), "fireCount should be 1 after advanceEpoch");

        DagState advanced2 = advanced.advanceEpoch(2);
        assertEquals(2, advanced2.getFireCount(), "fireCount should be 2 after second advanceEpoch");
    }

    /**
     * prepareNextCycle should NOT increment fireCount.
     * It only prepares epoch state without activating it.
     */
    @Test
    @DisplayName("prepareNextCycle does not increment fireCount")
    void prepareNextCycle_doesNotIncrementFireCount() {
        DagState initial = DagState.initial();
        assertEquals(0, initial.getFireCount());

        DagState prepared = initial.prepareNextCycle(1, "trigger:manual");
        assertEquals(0, prepared.getFireCount(),
                "prepareNextCycle should NOT increment fireCount");
    }

    /**
     * openEpoch should update currentEpoch to the max of current and new epoch.
     */
    @Test
    @DisplayName("openEpoch updates currentEpoch to max value")
    void openEpoch_updatesCurrentEpochToMax() {
        DagState state = DagState.initial();
        assertEquals(0, state.getCurrentEpoch());

        DagState opened = state.openEpoch(5);
        assertEquals(5, opened.getCurrentEpoch(), "currentEpoch should be updated to 5");

        // Opening a lower epoch should not decrease currentEpoch
        DagState openedLower = opened.openEpoch(3);
        assertEquals(5, openedLower.getCurrentEpoch(),
                "currentEpoch should remain 5 when opening epoch 3");
    }

    /**
     * openEpoch should preserve existing active epochs (not replace them).
     */
    @Test
    @DisplayName("openEpoch preserves existing active epochs")
    void openEpoch_preservesExistingActiveEpochs() {
        DagState state = DagState.initial();

        state = state.openEpoch(1);
        state = state.openEpoch(2);
        state = state.openEpoch(3);

        Set<Integer> active = state.getActiveEpochs();
        assertEquals(3, active.size());
        assertTrue(active.contains(1));
        assertTrue(active.contains(2));
        assertTrue(active.contains(3));
    }

    /**
     * openEpoch should create EpochState if absent.
     */
    @Test
    @DisplayName("openEpoch creates fresh EpochState if absent")
    void openEpoch_createsEpochStateIfAbsent() {
        DagState state = DagState.initial();
        assertNull(state.getEpochState(7), "Epoch 7 should not exist initially");

        DagState opened = state.openEpoch(7);
        assertNotNull(opened.getEpochState(7), "Epoch 7 should exist after openEpoch");
    }
}
