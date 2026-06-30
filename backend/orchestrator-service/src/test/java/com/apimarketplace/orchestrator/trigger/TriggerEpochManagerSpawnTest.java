package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.streaming.context.RunContextRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for TriggerEpochManager spawn-related methods and epoch/spawn interaction.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TriggerEpochManager - Spawn")
class TriggerEpochManagerSpawnTest {

    @Mock
    private WorkflowRunRepository runRepository;

    @Mock
    private RunContextRegistry runContextRegistry;

    private TriggerEpochManager epochManager;

    @BeforeEach
    void setUp() {
        epochManager = new TriggerEpochManager(runRepository, runContextRegistry);
    }

    private WorkflowRunEntity createRun(String runId, Map<String, Object> metadata) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(runId);
        run.setMetadata(metadata);
        return run;
    }

    // =========================================================================
    // getCurrentSpawnForDag
    // =========================================================================

    @Nested
    @DisplayName("getCurrentSpawnForDag(WorkflowRunEntity, String)")
    class GetCurrentSpawnForDagEntityTests {

        @Test
        @DisplayName("Should return 0 for null run")
        void shouldReturn0ForNullRun() {
            assertThat(epochManager.getCurrentSpawnForDag((WorkflowRunEntity) null, "trigger:wh1"))
                .isZero();
        }

        @Test
        @DisplayName("Should return 0 for null triggerId")
        void shouldReturn0ForNullTriggerId() {
            WorkflowRunEntity run = createRun("run-1", Map.of());
            assertThat(epochManager.getCurrentSpawnForDag(run, null)).isZero();
        }

        @Test
        @DisplayName("Should return 0 for null metadata")
        void shouldReturn0ForNullMetadata() {
            WorkflowRunEntity run = createRun("run-1", null);
            assertThat(epochManager.getCurrentSpawnForDag(run, "trigger:wh1")).isZero();
        }

        @Test
        @DisplayName("Should return 0 when dagCurrentSpawn map is absent")
        void shouldReturn0WhenMapAbsent() {
            WorkflowRunEntity run = createRun("run-1", Map.of("currentEpoch", 3));
            assertThat(epochManager.getCurrentSpawnForDag(run, "trigger:wh1")).isZero();
        }

        @Test
        @DisplayName("Should return 0 when trigger not in dagCurrentSpawn")
        void shouldReturn0WhenTriggerNotInMap() {
            Map<String, Object> metadata = Map.of(
                "dagCurrentSpawn", Map.of("trigger:other", 5)
            );
            WorkflowRunEntity run = createRun("run-1", metadata);
            assertThat(epochManager.getCurrentSpawnForDag(run, "trigger:wh1")).isZero();
        }

        @Test
        @DisplayName("Should return spawn value from dagCurrentSpawn")
        void shouldReturnSpawnValue() {
            Map<String, Object> metadata = Map.of(
                "dagCurrentSpawn", Map.of("trigger:wh1", 3, "trigger:wh2", 7)
            );
            WorkflowRunEntity run = createRun("run-1", metadata);

            assertThat(epochManager.getCurrentSpawnForDag(run, "trigger:wh1")).isEqualTo(3);
            assertThat(epochManager.getCurrentSpawnForDag(run, "trigger:wh2")).isEqualTo(7);
        }

        @Test
        @DisplayName("Should handle non-Number value in dagCurrentSpawn gracefully")
        void shouldHandleNonNumberValue() {
            Map<String, Object> metadata = Map.of(
                "dagCurrentSpawn", Map.of("trigger:wh1", "invalid")
            );
            WorkflowRunEntity run = createRun("run-1", metadata);
            assertThat(epochManager.getCurrentSpawnForDag(run, "trigger:wh1")).isZero();
        }

        @Test
        @DisplayName("Should handle dagCurrentSpawn with non-Map type gracefully")
        void shouldHandleNonMapDagCurrentSpawn() {
            Map<String, Object> metadata = Map.of("dagCurrentSpawn", "not-a-map");
            WorkflowRunEntity run = createRun("run-1", metadata);
            assertThat(epochManager.getCurrentSpawnForDag(run, "trigger:wh1")).isZero();
        }
    }

    @Nested
    @DisplayName("getCurrentSpawnForDag(String runId, String triggerId)")
    class GetCurrentSpawnByRunIdTests {

        @Test
        @DisplayName("Should return 0 for null runId")
        void shouldReturn0ForNullRunId() {
            assertThat(epochManager.getCurrentSpawnForDag((String) null, "trigger:wh1")).isZero();
        }

        @Test
        @DisplayName("Should return 0 for null triggerId")
        void shouldReturn0ForNullTriggerId() {
            assertThat(epochManager.getCurrentSpawnForDag("run-1", null)).isZero();
        }

        @Test
        @DisplayName("Should return 0 when run not found")
        void shouldReturn0WhenRunNotFound() {
            when(runRepository.findByRunIdPublic("run-missing")).thenReturn(Optional.empty());
            assertThat(epochManager.getCurrentSpawnForDag("run-missing", "trigger:wh1")).isZero();
        }

        @Test
        @DisplayName("Should return spawn from found run")
        void shouldReturnSpawnFromFoundRun() {
            Map<String, Object> metadata = new HashMap<>(Map.of(
                "dagCurrentSpawn", Map.of("trigger:wh1", 4)
            ));
            WorkflowRunEntity run = createRun("run-1", metadata);
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            assertThat(epochManager.getCurrentSpawnForDag("run-1", "trigger:wh1")).isEqualTo(4);
        }
    }

    // =========================================================================
    // incrementSpawn
    // =========================================================================

    @Nested
    @DisplayName("incrementSpawn()")
    class IncrementSpawnTests {

        @Test
        @DisplayName("Should throw for null run")
        void shouldThrowForNullRun() {
            assertThatThrownBy(() -> epochManager.incrementSpawn(null, "trigger:wh1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be null");
        }

        @Test
        @DisplayName("Should throw for null triggerId")
        void shouldThrowForNullTriggerId() {
            WorkflowRunEntity run = createRun("run-1", Map.of());
            assertThatThrownBy(() -> epochManager.incrementSpawn(run, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be null");
        }

        @Test
        @DisplayName("Should throw when run not found for lock")
        void shouldThrowWhenRunNotFound() {
            WorkflowRunEntity run = createRun("run-1", Map.of());
            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> epochManager.incrementSpawn(run, "trigger:wh1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
        }

        @Test
        @DisplayName("Should increment spawn from 0 to 1 for fresh trigger")
        void shouldIncrementFromZero() {
            WorkflowRunEntity lockedRun = createRun("run-1", new HashMap<>());
            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(lockedRun));
            when(runRepository.save(any())).thenReturn(lockedRun);

            WorkflowRunEntity inputRun = createRun("run-1", Map.of());
            int newSpawn = epochManager.incrementSpawn(inputRun, "trigger:wh1");

            assertThat(newSpawn).isEqualTo(1);
            verify(runRepository).save(lockedRun);
        }

        @Test
        @DisplayName("Should increment spawn from existing value")
        void shouldIncrementFromExisting() {
            Map<String, Object> dagCurrentSpawn = new HashMap<>(Map.of("trigger:wh1", 3));
            Map<String, Object> metadata = new HashMap<>(Map.of("dagCurrentSpawn", dagCurrentSpawn));
            WorkflowRunEntity lockedRun = createRun("run-1", metadata);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(lockedRun));
            when(runRepository.save(any())).thenReturn(lockedRun);

            WorkflowRunEntity inputRun = createRun("run-1", Map.of());
            int newSpawn = epochManager.incrementSpawn(inputRun, "trigger:wh1");

            assertThat(newSpawn).isEqualTo(4);
        }

        @Test
        @DisplayName("Should persist updated spawn in metadata")
        @SuppressWarnings("unchecked")
        void shouldPersistUpdatedSpawn() {
            Map<String, Object> dagCurrentSpawn = new HashMap<>(Map.of("trigger:wh1", 2));
            Map<String, Object> metadata = new HashMap<>(Map.of("dagCurrentSpawn", dagCurrentSpawn));
            WorkflowRunEntity lockedRun = createRun("run-1", metadata);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(lockedRun));
            when(runRepository.save(any())).thenReturn(lockedRun);

            WorkflowRunEntity inputRun = createRun("run-1", Map.of());
            epochManager.incrementSpawn(inputRun, "trigger:wh1");

            Map<String, Object> savedSpawnMap = (Map<String, Object>) lockedRun.getMetadata().get("dagCurrentSpawn");
            assertThat(savedSpawnMap.get("trigger:wh1")).isEqualTo(3);
        }

        @Test
        @DisplayName("Should not affect other triggers' spawn values")
        @SuppressWarnings("unchecked")
        void shouldNotAffectOtherTriggers() {
            Map<String, Object> dagCurrentSpawn = new HashMap<>(Map.of(
                "trigger:wh1", 2,
                "trigger:wh2", 5
            ));
            Map<String, Object> metadata = new HashMap<>(Map.of("dagCurrentSpawn", dagCurrentSpawn));
            WorkflowRunEntity lockedRun = createRun("run-1", metadata);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(lockedRun));
            when(runRepository.save(any())).thenReturn(lockedRun);

            WorkflowRunEntity inputRun = createRun("run-1", Map.of());
            epochManager.incrementSpawn(inputRun, "trigger:wh1");

            Map<String, Object> savedSpawnMap = (Map<String, Object>) lockedRun.getMetadata().get("dagCurrentSpawn");
            assertThat(savedSpawnMap.get("trigger:wh1")).isEqualTo(3);
            assertThat(savedSpawnMap.get("trigger:wh2")).isEqualTo(5); // unchanged
        }

        @Test
        @DisplayName("Should create dagCurrentSpawn map if absent")
        @SuppressWarnings("unchecked")
        void shouldCreateMapIfAbsent() {
            Map<String, Object> metadata = new HashMap<>(Map.of("currentEpoch", 1));
            WorkflowRunEntity lockedRun = createRun("run-1", metadata);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(lockedRun));
            when(runRepository.save(any())).thenReturn(lockedRun);

            WorkflowRunEntity inputRun = createRun("run-1", Map.of());
            int newSpawn = epochManager.incrementSpawn(inputRun, "trigger:wh1");

            assertThat(newSpawn).isEqualTo(1);
            Map<String, Object> savedSpawnMap = (Map<String, Object>) lockedRun.getMetadata().get("dagCurrentSpawn");
            assertThat(savedSpawnMap).isNotNull();
            assertThat(savedSpawnMap.get("trigger:wh1")).isEqualTo(1);
        }

        @Test
        @DisplayName("Should create metadata if null")
        @SuppressWarnings("unchecked")
        void shouldCreateMetadataIfNull() {
            WorkflowRunEntity lockedRun = createRun("run-1", null);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(lockedRun));
            when(runRepository.save(any())).thenReturn(lockedRun);

            WorkflowRunEntity inputRun = createRun("run-1", Map.of());
            int newSpawn = epochManager.incrementSpawn(inputRun, "trigger:wh1");

            assertThat(newSpawn).isEqualTo(1);
            assertThat(lockedRun.getMetadata()).isNotNull();
        }

        @Test
        @DisplayName("Should use pessimistic lock (findByRunIdPublicForUpdate)")
        void shouldUsePessimisticLock() {
            WorkflowRunEntity lockedRun = createRun("run-1", new HashMap<>());
            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(lockedRun));
            when(runRepository.save(any())).thenReturn(lockedRun);

            WorkflowRunEntity inputRun = createRun("run-1", Map.of());
            epochManager.incrementSpawn(inputRun, "trigger:wh1");

            verify(runRepository).findByRunIdPublicForUpdate("run-1");
            verify(runRepository, never()).findByRunIdPublic(any());
        }
    }

    // =========================================================================
    // Epoch/Spawn Interaction
    // =========================================================================

    @Nested
    @DisplayName("Epoch/Spawn Interaction")
    class EpochSpawnInteractionTests {

        @Test
        @DisplayName("incrementEpoch resets spawn to 0 for the trigger")
        @SuppressWarnings("unchecked")
        void epochIncrementResetsSpawn() {
            // Pre-condition: trigger:wh1 has spawn=5
            Map<String, Object> dagCurrentSpawn = new HashMap<>(Map.of("trigger:wh1", 5));
            Map<String, Object> metadata = new HashMap<>(Map.of(
                "currentEpoch", 2,
                "dagCurrentSpawn", dagCurrentSpawn
            ));
            WorkflowRunEntity lockedRun = createRun("run-1", metadata);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(lockedRun));
            when(runRepository.save(any())).thenReturn(lockedRun);

            // Act: increment epoch for trigger:wh1
            WorkflowRunEntity inputRun = createRun("run-1", Map.of());
            epochManager.incrementEpoch(inputRun, "trigger:wh1");

            // Assert: spawn reset to 0
            Map<String, Object> savedSpawnMap = (Map<String, Object>) lockedRun.getMetadata().get("dagCurrentSpawn");
            assertThat(savedSpawnMap.get("trigger:wh1"))
                .as("Spawn should be reset to 0 when epoch increments")
                .isEqualTo(0);
        }

        @Test
        @DisplayName("incrementEpoch does not reset other triggers' spawn")
        @SuppressWarnings("unchecked")
        void epochIncrementDoesNotResetOtherSpawn() {
            Map<String, Object> dagCurrentSpawn = new HashMap<>(Map.of(
                "trigger:wh1", 5,
                "trigger:wh2", 3
            ));
            Map<String, Object> metadata = new HashMap<>(Map.of(
                "currentEpoch", 2,
                "dagCurrentSpawn", dagCurrentSpawn
            ));
            WorkflowRunEntity lockedRun = createRun("run-1", metadata);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(lockedRun));
            when(runRepository.save(any())).thenReturn(lockedRun);

            // Act: increment epoch only for wh1
            WorkflowRunEntity inputRun = createRun("run-1", Map.of());
            epochManager.incrementEpoch(inputRun, "trigger:wh1");

            // Assert: wh2's spawn unchanged
            Map<String, Object> savedSpawnMap = (Map<String, Object>) lockedRun.getMetadata().get("dagCurrentSpawn");
            assertThat(savedSpawnMap.get("trigger:wh1")).isEqualTo(0); // reset
            assertThat(savedSpawnMap.get("trigger:wh2")).isEqualTo(3); // unchanged
        }

        @Test
        @DisplayName("Spawn increment then epoch increment resets spawn back to 0")
        @SuppressWarnings("unchecked")
        void spawnThenEpochResetsSpawn() {
            // Initial state: epoch=1, spawn=0
            Map<String, Object> metadata = new HashMap<>(Map.of("currentEpoch", 1));
            WorkflowRunEntity lockedRun = createRun("run-1", metadata);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(lockedRun));
            when(runRepository.save(any())).thenReturn(lockedRun);

            // Step 1: increment spawn (rerun) → spawn=1
            WorkflowRunEntity inputRun = createRun("run-1", Map.of());
            int spawn1 = epochManager.incrementSpawn(inputRun, "trigger:wh1");
            assertThat(spawn1).isEqualTo(1);

            // Step 2: increment spawn again → spawn=2
            int spawn2 = epochManager.incrementSpawn(inputRun, "trigger:wh1");
            assertThat(spawn2).isEqualTo(2);

            // Step 3: increment epoch → spawn reset to 0
            epochManager.incrementEpoch(inputRun, "trigger:wh1");

            Map<String, Object> savedSpawnMap = (Map<String, Object>) lockedRun.getMetadata().get("dagCurrentSpawn");
            assertThat(savedSpawnMap.get("trigger:wh1"))
                .as("Spawn should be 0 after epoch increment")
                .isEqualTo(0);
        }

        @Test
        @DisplayName("Multiple spawn increments accumulate correctly")
        void multipleSpawnIncrements() {
            Map<String, Object> metadata = new HashMap<>();
            WorkflowRunEntity lockedRun = createRun("run-1", metadata);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(lockedRun));
            when(runRepository.save(any())).thenReturn(lockedRun);

            WorkflowRunEntity inputRun = createRun("run-1", Map.of());
            for (int i = 1; i <= 10; i++) {
                int spawn = epochManager.incrementSpawn(inputRun, "trigger:wh1");
                assertThat(spawn).isEqualTo(i);
            }
        }
    }

    // =========================================================================
    // Multi-DAG Spawn Isolation
    // =========================================================================

    @Nested
    @DisplayName("Multi-DAG Spawn Isolation")
    class MultiDagSpawnIsolationTests {

        @Test
        @DisplayName("Each DAG has independent spawn counter")
        void eachDagHasIndependentSpawn() {
            Map<String, Object> metadata = new HashMap<>();
            WorkflowRunEntity lockedRun = createRun("run-1", metadata);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(lockedRun));
            when(runRepository.save(any())).thenReturn(lockedRun);

            WorkflowRunEntity inputRun = createRun("run-1", Map.of());

            // Increment spawn for wh1 three times
            epochManager.incrementSpawn(inputRun, "trigger:wh1");
            epochManager.incrementSpawn(inputRun, "trigger:wh1");
            int wh1Spawn = epochManager.incrementSpawn(inputRun, "trigger:wh1");

            // Increment spawn for wh2 once
            int wh2Spawn = epochManager.incrementSpawn(inputRun, "trigger:wh2");

            assertThat(wh1Spawn).isEqualTo(3);
            assertThat(wh2Spawn).isEqualTo(1);
        }

        @Test
        @DisplayName("Epoch increment for one DAG does not affect other DAG's spawn")
        @SuppressWarnings("unchecked")
        void epochForOneDagDoesNotAffectOther() {
            Map<String, Object> dagCurrentSpawn = new HashMap<>(Map.of(
                "trigger:wh1", 3,
                "trigger:wh2", 7
            ));
            Map<String, Object> dagFireCount = new HashMap<>(Map.of(
                "trigger:wh1", 2,
                "trigger:wh2", 1
            ));
            Map<String, Object> metadata = new HashMap<>(Map.of(
                "currentEpoch", 3,
                "dagCurrentSpawn", dagCurrentSpawn,
                "dagFireCount", dagFireCount
            ));
            WorkflowRunEntity lockedRun = createRun("run-1", metadata);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(lockedRun));
            when(runRepository.save(any())).thenReturn(lockedRun);

            // Increment epoch for wh1 only
            WorkflowRunEntity inputRun = createRun("run-1", Map.of());
            epochManager.incrementEpoch(inputRun, "trigger:wh1");

            Map<String, Object> savedSpawn = (Map<String, Object>) lockedRun.getMetadata().get("dagCurrentSpawn");
            assertThat(savedSpawn.get("trigger:wh1"))
                .as("wh1 spawn reset to 0")
                .isEqualTo(0);
            assertThat(savedSpawn.get("trigger:wh2"))
                .as("wh2 spawn unchanged at 7")
                .isEqualTo(7);
        }
    }

    // =========================================================================
    // Stress Tests
    // =========================================================================

    @Nested
    @DisplayName("Stress Tests")
    class StressTests {

        @Test
        @DisplayName("Should handle 100 sequential spawn increments")
        void shouldHandle100SpawnIncrements() {
            Map<String, Object> metadata = new HashMap<>();
            WorkflowRunEntity lockedRun = createRun("run-1", metadata);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(lockedRun));
            when(runRepository.save(any())).thenReturn(lockedRun);

            WorkflowRunEntity inputRun = createRun("run-1", Map.of());
            int lastSpawn = 0;
            for (int i = 0; i < 100; i++) {
                lastSpawn = epochManager.incrementSpawn(inputRun, "trigger:wh1");
            }

            assertThat(lastSpawn).isEqualTo(100);
        }

        @Test
        @DisplayName("Should handle 50 triggers with independent spawn counters")
        @SuppressWarnings("unchecked")
        void shouldHandle50TriggersIndependently() {
            Map<String, Object> metadata = new HashMap<>();
            WorkflowRunEntity lockedRun = createRun("run-1", metadata);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(lockedRun));
            when(runRepository.save(any())).thenReturn(lockedRun);

            WorkflowRunEntity inputRun = createRun("run-1", Map.of());

            // Create 50 triggers, each with different spawn counts
            for (int t = 0; t < 50; t++) {
                String triggerId = "trigger:wh_" + t;
                for (int s = 0; s <= t; s++) {
                    epochManager.incrementSpawn(inputRun, triggerId);
                }
            }

            // Verify each trigger has correct spawn count
            Map<String, Object> savedSpawn = (Map<String, Object>) lockedRun.getMetadata().get("dagCurrentSpawn");
            for (int t = 0; t < 50; t++) {
                assertThat(savedSpawn.get("trigger:wh_" + t))
                    .as("trigger:wh_%d should have spawn=%d", t, t + 1)
                    .isEqualTo(t + 1);
            }
        }

        @Test
        @DisplayName("Should handle alternating epoch and spawn increments")
        @SuppressWarnings("unchecked")
        void shouldHandleAlternatingEpochAndSpawn() {
            Map<String, Object> metadata = new HashMap<>();
            WorkflowRunEntity lockedRun = createRun("run-1", metadata);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(lockedRun));
            when(runRepository.save(any())).thenReturn(lockedRun);

            WorkflowRunEntity inputRun = createRun("run-1", Map.of());

            // 10 cycles: epoch increment → 3 reruns → epoch increment → ...
            for (int cycle = 0; cycle < 10; cycle++) {
                epochManager.incrementEpoch(inputRun, "trigger:wh1");

                // Verify spawn is reset after epoch
                Map<String, Object> spawnMap = (Map<String, Object>) lockedRun.getMetadata().get("dagCurrentSpawn");
                assertThat(spawnMap.get("trigger:wh1"))
                    .as("Spawn should be 0 at start of cycle %d", cycle)
                    .isEqualTo(0);

                // 3 reruns within this epoch
                for (int rerun = 1; rerun <= 3; rerun++) {
                    int spawn = epochManager.incrementSpawn(inputRun, "trigger:wh1");
                    assertThat(spawn).isEqualTo(rerun);
                }
            }

            // After 10 cycles of (epoch + 3 reruns), global epoch should be 10
            int globalEpoch = epochManager.getCurrentEpoch(lockedRun);
            assertThat(globalEpoch).isEqualTo(10);
        }
    }

    // =========================================================================
    // Edge Cases
    // =========================================================================

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle Integer.MAX_VALUE - 1 spawn increment")
        @SuppressWarnings("unchecked")
        void shouldHandleNearMaxSpawn() {
            Map<String, Object> dagCurrentSpawn = new HashMap<>(Map.of(
                "trigger:wh1", Integer.MAX_VALUE - 1
            ));
            Map<String, Object> metadata = new HashMap<>(Map.of("dagCurrentSpawn", dagCurrentSpawn));
            WorkflowRunEntity lockedRun = createRun("run-1", metadata);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(lockedRun));
            when(runRepository.save(any())).thenReturn(lockedRun);

            WorkflowRunEntity inputRun = createRun("run-1", Map.of());
            int newSpawn = epochManager.incrementSpawn(inputRun, "trigger:wh1");

            assertThat(newSpawn).isEqualTo(Integer.MAX_VALUE);
        }

        @Test
        @DisplayName("Spawn with Long value in metadata should convert to int")
        void shouldHandleLongSpawnValue() {
            Map<String, Object> dagCurrentSpawn = new HashMap<>(Map.of(
                "trigger:wh1", 42L
            ));
            Map<String, Object> metadata = new HashMap<>(Map.of("dagCurrentSpawn", dagCurrentSpawn));
            WorkflowRunEntity run = createRun("run-1", metadata);

            assertThat(epochManager.getCurrentSpawnForDag(run, "trigger:wh1")).isEqualTo(42);
        }

        @Test
        @DisplayName("Spawn with Double value in metadata should convert to int")
        void shouldHandleDoubleSpawnValue() {
            Map<String, Object> dagCurrentSpawn = new HashMap<>(Map.of(
                "trigger:wh1", 7.0
            ));
            Map<String, Object> metadata = new HashMap<>(Map.of("dagCurrentSpawn", dagCurrentSpawn));
            WorkflowRunEntity run = createRun("run-1", metadata);

            assertThat(epochManager.getCurrentSpawnForDag(run, "trigger:wh1")).isEqualTo(7);
        }

        @Test
        @DisplayName("Empty string triggerId for spawn should return 0")
        void emptyStringTriggerIdReturns0() {
            Map<String, Object> metadata = Map.of("dagCurrentSpawn", Map.of());
            WorkflowRunEntity run = createRun("run-1", metadata);
            assertThat(epochManager.getCurrentSpawnForDag(run, "")).isZero();
        }
    }
}
