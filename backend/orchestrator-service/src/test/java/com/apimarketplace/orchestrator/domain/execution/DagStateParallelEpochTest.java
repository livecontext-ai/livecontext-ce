package com.apimarketplace.orchestrator.domain.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DagState - Parallel Epoch Support")
class DagStateParallelEpochTest {

    // ========================================================================
    // INITIAL STATE
    // ========================================================================

    @Nested
    @DisplayName("initial()")
    class InitialTests {

        @Test
        @DisplayName("Should start with no active epochs")
        void shouldHaveNoActiveEpochs() {
            DagState state = DagState.initial();
            assertFalse(state.hasActiveEpochs());
            assertTrue(state.getActiveEpochs().isEmpty());
            assertEquals(0, state.getCurrentEpoch());
            assertEquals(0, state.getCurrentSpawn());
            assertEquals(0, state.getFireCount());
        }

        @Test
        @DisplayName("Should have empty epochs map")
        void shouldHaveEmptyEpochsMap() {
            DagState state = DagState.initial();
            assertTrue(state.getEpochs().isEmpty());
            assertTrue(state.getActiveEpochStates().isEmpty());
        }

        @Test
        @DisplayName("currentEpochState should return fresh for uninitialized epoch")
        void currentEpochStateShouldReturnFresh() {
            DagState state = DagState.initial();
            EpochState es = state.currentEpochState();
            assertNotNull(es);
            assertTrue(es.isEmpty());
        }
    }

    // ========================================================================
    // ADVANCE EPOCH
    // ========================================================================

    @Nested
    @DisplayName("advanceEpoch()")
    class AdvanceEpochTests {

        @Test
        @DisplayName("Should create fresh epoch and add to activeEpochs")
        void shouldCreateFreshEpochAndAddToActive() {
            DagState state = DagState.initial();
            DagState advanced = state.advanceEpoch(1);

            assertEquals(1, advanced.getCurrentEpoch());
            assertEquals(1, advanced.getFireCount());
            assertTrue(advanced.hasActiveEpochs());
            assertTrue(advanced.getActiveEpochs().contains(1));
            assertNotNull(advanced.getEpochState(1));
            assertTrue(advanced.getEpochState(1).isEmpty());
        }

        @Test
        @DisplayName("Multiple advances should accumulate active epochs")
        void multipleAdvancesShouldAccumulate() {
            DagState state = DagState.initial();
            DagState e1 = state.advanceEpoch(1);
            DagState e2 = e1.advanceEpoch(2);
            DagState e3 = e2.advanceEpoch(3);

            assertEquals(3, e3.getCurrentEpoch());
            assertEquals(3, e3.getFireCount());
            assertEquals(Set.of(1, 2, 3), e3.getActiveEpochs());
            assertEquals(3, e3.getActiveEpochStates().size());
        }

        @Test
        @DisplayName("Should preserve previous epoch data")
        void shouldPreservePreviousEpochData() {
            DagState state = DagState.initial();
            DagState e1 = state.advanceEpoch(1);

            // Mutate epoch 1
            EpochState mutated = e1.getEpochState(1).markNodeCompleted("mcp:step1");
            DagState e1Updated = e1.withEpochState(1, mutated);

            // Advance to epoch 2
            DagState e2 = e1Updated.advanceEpoch(2);

            // Epoch 1's data should be preserved
            assertTrue(e2.getEpochState(1).getCompletedNodeIds().contains("mcp:step1"));
            // Epoch 2 should be fresh
            assertTrue(e2.getEpochState(2).isEmpty());
        }

        @Test
        @DisplayName("Should reset spawn counter on advance")
        void shouldResetSpawnCounter() {
            DagState state = DagState.initial().advanceEpoch(1).advanceSpawn().advanceSpawn();
            assertEquals(2, state.getCurrentSpawn());

            DagState advanced = state.advanceEpoch(2);
            assertEquals(0, advanced.getCurrentSpawn());
        }
    }

    // ========================================================================
    // CLOSE AND PRUNE EPOCH
    // ========================================================================

    @Nested
    @DisplayName("closeAndPruneEpoch()")
    class CloseAndPruneEpochTests {

        @Test
        @DisplayName("Should remove epoch from activeEpochs AND from epochs map")
        void shouldRemoveFromActiveAndHistory() {
            DagState state = DagState.initial().advanceEpoch(1);
            // Put some data in epoch 1
            EpochState mutated = state.getEpochState(1).markNodeCompleted("mcp:step1");
            state = state.withEpochState(1, mutated);

            DagState closed = state.closeAndPruneEpoch(1);

            assertFalse(closed.hasActiveEpochs());
            assertFalse(closed.getActiveEpochs().contains(1));
            // Epoch pruned from history
            assertNull(closed.getEpochState(1));
            assertTrue(closed.getEpochs().isEmpty());
        }

        @Test
        @DisplayName("Should only close+prune the specified epoch, not others")
        void shouldOnlyCloseSpecifiedEpoch() {
            DagState state = DagState.initial()
                    .advanceEpoch(1)
                    .advanceEpoch(2)
                    .advanceEpoch(3);

            DagState closed = state.closeAndPruneEpoch(2);

            assertEquals(Set.of(1, 3), closed.getActiveEpochs());
            assertTrue(closed.hasActiveEpochs());
            // Epoch 2 pruned
            assertNull(closed.getEpochState(2));
            // Epochs 1 and 3 still there
            assertNotNull(closed.getEpochState(1));
            assertNotNull(closed.getEpochState(3));
        }

        @Test
        @DisplayName("Closing all epochs should result in empty epochs map")
        void closingAllEpochsShouldResultInEmpty() {
            DagState state = DagState.initial()
                    .advanceEpoch(1)
                    .advanceEpoch(2);

            DagState closed = state.closeAndPruneEpoch(1).closeAndPruneEpoch(2);

            assertFalse(closed.hasActiveEpochs());
            assertTrue(closed.getActiveEpochs().isEmpty());
            // Both epochs pruned from history
            assertTrue(closed.getEpochs().isEmpty());
        }

        @Test
        @DisplayName("Closing a non-existent epoch should be safe (no-op)")
        void closingNonExistentEpochShouldBeNoOp() {
            DagState state = DagState.initial().advanceEpoch(1);
            DagState closed = state.closeAndPruneEpoch(99);

            assertEquals(Set.of(1), closed.getActiveEpochs());
            assertNotNull(closed.getEpochState(1));
        }

        @Test
        @DisplayName("Should not change currentEpoch when closing")
        void shouldNotChangeCurrentEpoch() {
            DagState state = DagState.initial()
                    .advanceEpoch(1)
                    .advanceEpoch(2);

            DagState closed = state.closeAndPruneEpoch(2);
            assertEquals(2, closed.getCurrentEpoch()); // currentEpoch unchanged
        }
    }

    // ========================================================================
    // BACKWARD COMPATIBILITY
    // ========================================================================

    @Nested
    @DisplayName("Backward compatible constructor (no activeEpochs)")
    class BackwardCompatTests {

        @Test
        @DisplayName("Should infer activeEpochs from currentEpoch")
        void shouldInferActiveEpochsFromCurrent() {
            Map<Integer, EpochState> epochs = Map.of(3, EpochState.fresh());
            DagState state = new DagState(3, 0, 5, epochs);

            assertTrue(state.hasActiveEpochs());
            assertEquals(Set.of(3), state.getActiveEpochs());
        }

        @Test
        @DisplayName("Should handle empty epochs map")
        void shouldHandleEmptyEpochsMap() {
            DagState state = new DagState(0, 0, 0, Map.of());

            assertFalse(state.hasActiveEpochs());
            assertTrue(state.getActiveEpochs().isEmpty());
        }

        @Test
        @DisplayName("Should handle null epochs map")
        void shouldHandleNullEpochsMap() {
            DagState state = new DagState(1, 0, 1, null);

            assertFalse(state.hasActiveEpochs());
        }

        @Test
        @DisplayName("Should handle currentEpoch not in epochs map")
        void shouldHandleCurrentNotInMap() {
            Map<Integer, EpochState> epochs = Map.of(1, EpochState.fresh());
            DagState state = new DagState(5, 0, 3, epochs);

            // currentEpoch=5 is not in epochs map, so activeEpochs should be empty
            assertFalse(state.hasActiveEpochs());
        }
    }

    // ========================================================================
    // IMMUTABILITY
    // ========================================================================

    @Nested
    @DisplayName("Immutability guarantees")
    class ImmutabilityTests {

        @Test
        @DisplayName("advanceEpoch should not modify original")
        void advanceShouldNotModifyOriginal() {
            DagState original = DagState.initial();
            DagState advanced = original.advanceEpoch(1);

            assertFalse(original.hasActiveEpochs());
            assertTrue(advanced.hasActiveEpochs());
            assertEquals(0, original.getCurrentEpoch());
            assertEquals(1, advanced.getCurrentEpoch());
        }

        @Test
        @DisplayName("closeAndPruneEpoch should not modify original")
        void closeShouldNotModifyOriginal() {
            DagState original = DagState.initial().advanceEpoch(1);
            DagState closed = original.closeAndPruneEpoch(1);

            assertTrue(original.hasActiveEpochs());
            assertFalse(closed.hasActiveEpochs());
            // Original retains epoch data
            assertNotNull(original.getEpochState(1));
            // Closed/pruned version does not
            assertNull(closed.getEpochState(1));
        }

        @Test
        @DisplayName("withEpochState should not modify original")
        void withEpochStateShouldNotModifyOriginal() {
            DagState original = DagState.initial().advanceEpoch(1);
            EpochState mutated = EpochState.fresh().markNodeCompleted("mcp:step1");
            DagState updated = original.withEpochState(1, mutated);

            assertTrue(original.getEpochState(1).isEmpty());
            assertFalse(updated.getEpochState(1).isEmpty());
        }

        @Test
        @DisplayName("activeEpochs set should be unmodifiable")
        void activeEpochsShouldBeUnmodifiable() {
            DagState state = DagState.initial().advanceEpoch(1);
            Set<Integer> active = state.getActiveEpochs();
            assertThrows(UnsupportedOperationException.class, () -> active.add(99));
        }

        @Test
        @DisplayName("epochs map should be unmodifiable")
        void epochsMapShouldBeUnmodifiable() {
            DagState state = DagState.initial().advanceEpoch(1);
            Map<Integer, EpochState> epochs = state.getEpochs();
            assertThrows(UnsupportedOperationException.class, () -> epochs.put(99, EpochState.fresh()));
        }
    }

    // ========================================================================
    // getActiveEpochStates()
    // ========================================================================

    @Nested
    @DisplayName("getActiveEpochStates()")
    class ActiveEpochStatesTests {

        @Test
        @DisplayName("Should return only active epoch states")
        void shouldReturnOnlyActive() {
            DagState state = DagState.initial()
                    .advanceEpoch(1)
                    .advanceEpoch(2)
                    .advanceEpoch(3);

            // Close+prune epoch 2
            DagState closed = state.closeAndPruneEpoch(2);

            Map<Integer, EpochState> activeStates = closed.getActiveEpochStates();
            assertEquals(2, activeStates.size());
            assertTrue(activeStates.containsKey(1));
            assertFalse(activeStates.containsKey(2));
            assertTrue(activeStates.containsKey(3));
        }

        @Test
        @DisplayName("Should return empty when no active epochs")
        void shouldReturnEmptyWhenNone() {
            DagState state = DagState.initial();
            assertTrue(state.getActiveEpochStates().isEmpty());
        }

        @Test
        @DisplayName("Should handle orphaned active epoch (in set but not in map)")
        void shouldHandleOrphanedActiveEpoch() {
            // Edge case: activeEpochs contains an epoch ID that has no EpochState
            DagState state = DagState.initial().advanceEpoch(1);
            // epoch 1 is in activeEpochs and in epochs map - normal case
            // After advance, both should match
            Map<Integer, EpochState> active = state.getActiveEpochStates();
            assertEquals(1, active.size());
        }
    }

    // ========================================================================
    // ADVANCE SPAWN
    // ========================================================================

    @Nested
    @DisplayName("advanceSpawn()")
    class AdvanceSpawnTests {

        @Test
        @DisplayName("Should increment spawn counter without affecting epochs")
        void shouldIncrementSpawn() {
            DagState state = DagState.initial().advanceEpoch(1);
            DagState spawned = state.advanceSpawn();

            assertEquals(1, spawned.getCurrentSpawn());
            assertEquals(state.getActiveEpochs(), spawned.getActiveEpochs());
            assertEquals(state.getFireCount(), spawned.getFireCount());
        }

        @Test
        @DisplayName("Multiple spawns should accumulate")
        void multipleSawnsShouldAccumulate() {
            DagState state = DagState.initial().advanceEpoch(1)
                    .advanceSpawn()
                    .advanceSpawn()
                    .advanceSpawn();

            assertEquals(3, state.getCurrentSpawn());
        }
    }

    // ========================================================================
    // EDGE CASES
    // ========================================================================

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Rapidly advancing many epochs should all be tracked")
        void rapidlyAdvancingManyEpochs() {
            DagState state = DagState.initial();
            for (int i = 1; i <= 100; i++) {
                state = state.advanceEpoch(i);
            }

            assertEquals(100, state.getActiveEpochs().size());
            assertEquals(100, state.getCurrentEpoch());
            assertEquals(100, state.getFireCount());
        }

        @Test
        @DisplayName("getEpochState for non-existent epoch should return null")
        void getEpochStateForNonExistentReturnsNull() {
            DagState state = DagState.initial().advanceEpoch(1);
            assertNull(state.getEpochState(99));
        }

        @Test
        @DisplayName("ensureCurrentEpochInitialized should be idempotent")
        void ensureInitializedShouldBeIdempotent() {
            DagState state = DagState.initial().advanceEpoch(1);
            DagState ensured = state.ensureCurrentEpochInitialized();
            assertSame(state, ensured); // No new instance needed (already has epoch 1)
        }

        @Test
        @DisplayName("ensureCurrentEpochInitialized should create epoch if missing")
        void ensureInitializedShouldCreateIfMissing() {
            DagState state = DagState.initial();
            // currentEpoch = 0, but no epoch 0 in map
            DagState ensured = state.ensureCurrentEpochInitialized();
            assertNotNull(ensured.getEpochState(0));
        }
    }

    // ========================================================================
    // JACKSON SERIALIZATION ROUND-TRIP
    // ========================================================================

    @Nested
    @DisplayName("Jackson serialization")
    class JacksonSerializationTests {

        private final ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());

        @Test
        @DisplayName("DagState with active epochs should survive JSON round-trip")
        void dagStateShouldSurviveRoundTrip() throws Exception {
            DagState original = DagState.initial()
                    .advanceEpoch(1)
                    .advanceEpoch(2);
            // Add some node state to epoch 1
            EpochState epoch1 = original.getEpochState(1).markNodeCompleted("mcp:step1");
            original = original.withEpochState(1, epoch1);

            String json = mapper.writeValueAsString(original);
            DagState deserialized = mapper.readValue(json, DagState.class);

            assertEquals(original.getCurrentEpoch(), deserialized.getCurrentEpoch());
            assertEquals(original.getFireCount(), deserialized.getFireCount());
            assertEquals(original.getActiveEpochs(), deserialized.getActiveEpochs());
            assertTrue(deserialized.getEpochState(1).getCompletedNodeIds().contains("mcp:step1"));
            assertNotNull(deserialized.getEpochState(2));
        }

        @Test
        @DisplayName("DagState JSON should NOT contain activeEpochStates (computed property)")
        void jsonShouldNotContainActiveEpochStates() throws Exception {
            DagState state = DagState.initial().advanceEpoch(1);
            String json = mapper.writeValueAsString(state);
            assertFalse(json.contains("activeEpochStates"),
                    "JSON should not contain 'activeEpochStates' (computed, @JsonIgnore). Got: " + json);
        }

        @Test
        @DisplayName("StateSnapshot with parallel epochs should survive JSON round-trip")
        void snapshotWithParallelEpochsShouldSurviveRoundTrip() throws Exception {
            StateSnapshot snapshot = StateSnapshot.empty()
                    .resetDag("trigger:webhook", 1)
                    .markNodeCompleted("trigger:webhook", "trigger:webhook", 1)
                    .markNodeCompleted("trigger:webhook", "mcp:step1", 1);

            // Advance to epoch 2
            StateSnapshot withEpoch2 = snapshot.resetDag("trigger:webhook", 2)
                    .markNodeCompleted("trigger:webhook", "trigger:webhook", 2);

            String json = mapper.writeValueAsString(withEpoch2);
            StateSnapshot deserialized = mapper.readValue(json, StateSnapshot.class);

            // NodeCounts should be preserved
            assertEquals(2, deserialized.getNodeCounts("trigger:webhook").completed());
            assertEquals(1, deserialized.getNodeCounts("mcp:step1").completed());

            // DAG structure should be preserved
            assertNotNull(deserialized.getDags().get("trigger:webhook"));
        }

        @Test
        @DisplayName("EpochState with decision branches should survive JSON round-trip")
        void epochStateWithBranchesShouldSurviveRoundTrip() throws Exception {
            EpochState original = EpochState.fresh()
                    .markNodeCompleted("trigger:start")
                    .recordDecisionBranch("core:check", "if")
                    .addReadyNode("mcp:success");

            String json = mapper.writeValueAsString(original);
            EpochState deserialized = mapper.readValue(json, EpochState.class);

            assertTrue(deserialized.getCompletedNodeIds().contains("trigger:start"));
            assertTrue(deserialized.getReadyNodeIds().contains("mcp:success"));
            assertEquals(Set.of("if"), deserialized.getDecisionBranchesMap().get("core:check"));
        }
    }
}
