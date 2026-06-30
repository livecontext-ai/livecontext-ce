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
 * Tests for TriggerEpochManager.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TriggerEpochManager")
class TriggerEpochManagerTest {

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

    @Nested
    @DisplayName("getCurrentEpoch(WorkflowRunEntity)")
    class GetCurrentEpochFromEntityTests {

        @Test
        @DisplayName("Should return 0 for null run")
        void shouldReturn0ForNullRun() {
            assertThat(epochManager.getCurrentEpoch((WorkflowRunEntity) null)).isZero();
        }

        @Test
        @DisplayName("Should return 0 for null metadata")
        void shouldReturn0ForNullMetadata() {
            WorkflowRunEntity run = createRun("run-1", null);
            assertThat(epochManager.getCurrentEpoch(run)).isZero();
        }

        @Test
        @DisplayName("Should return 0 when no epoch in metadata")
        void shouldReturn0WhenNoEpoch() {
            WorkflowRunEntity run = createRun("run-1", Map.of());
            assertThat(epochManager.getCurrentEpoch(run)).isZero();
        }

        @Test
        @DisplayName("Should return epoch from metadata")
        void shouldReturnEpochFromMetadata() {
            WorkflowRunEntity run = createRun("run-1", Map.of("currentEpoch", 5));
            assertThat(epochManager.getCurrentEpoch(run)).isEqualTo(5);
        }

        @Test
        @DisplayName("Should handle non-Number epoch value")
        void shouldHandleNonNumberEpoch() {
            WorkflowRunEntity run = createRun("run-1", Map.of("currentEpoch", "invalid"));
            assertThat(epochManager.getCurrentEpoch(run)).isZero();
        }
    }

    @Nested
    @DisplayName("getCurrentEpoch(WorkflowRunEntity, String triggerId)")
    class GetCurrentEpochPerDagTests {

        @Test
        @DisplayName("Should fall back to global epoch when triggerId is null")
        void shouldFallBackWhenTriggerIdNull() {
            WorkflowRunEntity run = createRun("run-1", Map.of("currentEpoch", 3));
            assertThat(epochManager.getCurrentEpoch(run, null)).isEqualTo(3);
        }

        @Test
        @DisplayName("Should return per-DAG global epoch from dagLastEpoch map")
        void shouldReturnPerDagEpoch() {
            Map<String, Object> dagLastEpoch = Map.of(
                "trigger:webhook_a", 5,
                "trigger:webhook_b", 3
            );
            WorkflowRunEntity run = createRun("run-1", Map.of(
                "currentEpoch", 5,
                "dagLastEpoch", dagLastEpoch
            ));

            assertThat(epochManager.getCurrentEpoch(run, "trigger:webhook_a")).isEqualTo(5);
            assertThat(epochManager.getCurrentEpoch(run, "trigger:webhook_b")).isEqualTo(3);
        }

        @Test
        @DisplayName("Should fall back to global epoch when triggerId not in dagEpochs")
        void shouldFallBackWhenTriggerIdNotFound() {
            Map<String, Object> dagEpochs = Map.of("trigger:other", 5);
            WorkflowRunEntity run = createRun("run-1", Map.of(
                "currentEpoch", 2,
                "dagEpochs", dagEpochs
            ));

            assertThat(epochManager.getCurrentEpoch(run, "trigger:missing")).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("getCurrentEpoch(String runId)")
    class GetCurrentEpochByRunIdTests {

        @Test
        @DisplayName("Should return 0 for null runId")
        void shouldReturn0ForNullRunId() {
            assertThat(epochManager.getCurrentEpoch((String) null)).isZero();
        }

        @Test
        @DisplayName("Should return 0 when run not found")
        void shouldReturn0WhenRunNotFound() {
            when(runRepository.findByRunIdPublic("run-missing")).thenReturn(Optional.empty());
            assertThat(epochManager.getCurrentEpoch("run-missing")).isZero();
        }

        @Test
        @DisplayName("Should return epoch from found run")
        void shouldReturnEpochFromFoundRun() {
            WorkflowRunEntity run = createRun("run-1", Map.of("currentEpoch", 7));
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));
            assertThat(epochManager.getCurrentEpoch("run-1")).isEqualTo(7);
        }
    }

    @Nested
    @DisplayName("incrementEpoch(WorkflowRunEntity)")
    class IncrementEpochTests {

        @Test
        @DisplayName("Should throw for null run")
        void shouldThrowForNullRun() {
            assertThatThrownBy(() -> epochManager.incrementEpoch((WorkflowRunEntity) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be null");
        }

        @Test
        @DisplayName("Should increment epoch from 0 to 1")
        void shouldIncrementFromZero() {
            WorkflowRunEntity run = createRun("run-1", null);
            when(runRepository.save(any())).thenReturn(run);

            int newEpoch = epochManager.incrementEpoch(run);

            assertThat(newEpoch).isEqualTo(1);
            verify(runRepository).save(run);
        }

        @Test
        @DisplayName("Should increment epoch from existing value")
        void shouldIncrementFromExisting() {
            Map<String, Object> metadata = new HashMap<>(Map.of("currentEpoch", 3));
            WorkflowRunEntity run = createRun("run-1", metadata);
            when(runRepository.save(any())).thenReturn(run);

            int newEpoch = epochManager.incrementEpoch(run);

            assertThat(newEpoch).isEqualTo(4);
            assertThat(run.getMetadata()).containsEntry("currentEpoch", 4);
            assertThat(run.getMetadata()).containsEntry("triggerCount", 4);
        }
    }

    @Nested
    @DisplayName("incrementEpoch(WorkflowRunEntity, String triggerId)")
    class IncrementEpochPerDagTests {

        @Test
        @DisplayName("Should throw for null run")
        void shouldThrowForNullRun() {
            assertThatThrownBy(() -> epochManager.incrementEpoch(null, "trigger:test"))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Should fall back to global increment when triggerId is null")
        void shouldFallBackWhenTriggerIdNull() {
            WorkflowRunEntity run = createRun("run-1", null);
            when(runRepository.save(any())).thenReturn(run);

            int newEpoch = epochManager.incrementEpoch(run, null);

            assertThat(newEpoch).isEqualTo(1);
        }

        @Test
        @DisplayName("Should increment per-DAG epoch with locked entity")
        void shouldIncrementPerDagEpoch() {
            Map<String, Object> dagEpochs = new HashMap<>(Map.of("trigger:wh_a", 2));
            Map<String, Object> metadata = new HashMap<>(Map.of(
                "currentEpoch", 2,
                "dagEpochs", dagEpochs
            ));
            WorkflowRunEntity lockedRun = createRun("run-1", metadata);

            when(runRepository.findByRunIdPublicForUpdate("run-1"))
                .thenReturn(Optional.of(lockedRun));
            when(runRepository.save(any())).thenReturn(lockedRun);

            WorkflowRunEntity inputRun = createRun("run-1", Map.of());
            int newEpoch = epochManager.incrementEpoch(inputRun, "trigger:wh_a");

            assertThat(newEpoch).isEqualTo(3);
            verify(runRepository).findByRunIdPublicForUpdate("run-1");
            verify(runRepository).save(lockedRun);
        }
    }

    // =========================================================================
    // Multi-DAG Epoch Isolation Tests
    // =========================================================================

    @Nested
    @DisplayName("Multi-DAG Epoch Isolation")
    class MultiDagEpochIsolationTests {

        @Test
        @DisplayName("incrementEpoch always increments global currentEpoch monotonically")
        void globalEpochAlwaysIncrements() {
            // Setup: DAG1 already at epoch 2, DAG2 at epoch 0, global at 2
            Map<String, Object> dagEpochs = new HashMap<>(Map.of("trigger:dag1", 2));
            Map<String, Object> metadata = new HashMap<>(Map.of(
                "currentEpoch", 2,
                "dagEpochs", dagEpochs
            ));
            WorkflowRunEntity lockedRun = createRun("run-1", metadata);

            when(runRepository.findByRunIdPublicForUpdate("run-1"))
                .thenReturn(Optional.of(lockedRun));
            when(runRepository.save(any())).thenReturn(lockedRun);

            // Act: increment DAG2's epoch (first fire, 0 → 1)
            WorkflowRunEntity inputRun = createRun("run-1", Map.of());
            epochManager.incrementEpoch(inputRun, "trigger:dag2");

            // Verify per-DAG fire counts are correct (dagFireCount replaces legacy dagEpochs)
            @SuppressWarnings("unchecked")
            Map<String, Object> savedDagFireCount = (Map<String, Object>) lockedRun.getMetadata().get("dagFireCount");
            // dag1 was migrated from dagEpochs (backward compat)
            assertThat(savedDagFireCount.get("trigger:dag1")).isEqualTo(2);
            assertThat(savedDagFireCount.get("trigger:dag2")).isEqualTo(1);

            // Global currentEpoch is always previous + 1 (monotonic), not max(dagEpochs).
            // This guarantees every fire gets a unique global epoch for step_data persistence.
            int globalEpoch = (int) lockedRun.getMetadata().get("currentEpoch");
            assertThat(globalEpoch)
                .as("Global epoch should be previous(2) + 1 = 3, not max(dag1=2, dag2=1)")
                .isEqualTo(3);
        }

        @Test
        @DisplayName("BUG: getCurrentEpoch(runId) returns global max, not per-DAG epoch")
        void getCurrentEpochByRunIdReturnsGlobalMax() {
            // DAG1 fired 5 times, DAG2 fired 1 time
            Map<String, Object> metadata = new HashMap<>(Map.of(
                "currentEpoch", 5,  // max(5, 1) = 5
                "dagEpochs", Map.of("trigger:dag1", 5, "trigger:dag2", 1)
            ));
            WorkflowRunEntity run = createRun("run-1", metadata);
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            // getCurrentEpoch(runId) returns global max = 5
            int epoch = epochManager.getCurrentEpoch("run-1");

            // This returns 5, not DAG2's actual epoch of 1
            assertThat(epoch)
                .as("getCurrentEpoch(runId) returns global max, ignoring per-DAG epochs")
                .isEqualTo(5);
        }

        @Test
        @DisplayName("Per-DAG epoch correctly returns DAG-specific value via dagLastEpoch")
        void perDagEpochReturnsCorrectValue() {
            // getCurrentEpoch(run, triggerId) now delegates to getGlobalEpochForDag()
            // which reads from dagLastEpoch (the global epoch assigned to each trigger's last fire)
            Map<String, Object> metadata = new HashMap<>(Map.of(
                "currentEpoch", 5,
                "dagLastEpoch", Map.of("trigger:dag1", 5, "trigger:dag2", 1)
            ));
            WorkflowRunEntity run = createRun("run-1", metadata);

            // Per-DAG epoch correctly returns each DAG's global epoch
            assertThat(epochManager.getCurrentEpoch(run, "trigger:dag1")).isEqualTo(5);
            assertThat(epochManager.getCurrentEpoch(run, "trigger:dag2")).isEqualTo(1);
        }

        @Test
        @DisplayName("BUG SCENARIO: DAG2 step output loaded with wrong epoch when using global getCurrentEpoch")
        void dag2StepOutputLoadedWithWrongEpoch() {
            // Simulate: DAG1 at global epoch 3, DAG2 at global epoch 1
            // When V2StepByStepContextManager calls getCurrentEpoch(runId),
            // it gets epoch 3 instead of 1 for DAG2's context
            Map<String, Object> metadata = new HashMap<>(Map.of(
                "currentEpoch", 3,
                "dagLastEpoch", Map.of("trigger:dag1", 3, "trigger:dag2", 1)
            ));
            WorkflowRunEntity run = createRun("run-1", metadata);
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            // V2StepByStepContextManager line 118 does:
            // int epoch = triggerEpochManager.getCurrentEpoch(tree.runId());
            int epochUsedByContextManager = epochManager.getCurrentEpoch("run-1");

            // This is wrong for DAG2! It should be 1, but it's 3
            int correctEpochForDag2 = epochManager.getCurrentEpoch(run, "trigger:dag2");

            assertThat(epochUsedByContextManager)
                .as("Context manager gets global epoch (3) instead of DAG2's epoch (1)")
                .isEqualTo(3);
            assertThat(correctEpochForDag2)
                .as("Per-DAG epoch correctly returns 1")
                .isEqualTo(1);

            // The mismatch proves the bug:
            assertThat(epochUsedByContextManager)
                .as("BUG: global epoch ≠ DAG2's actual epoch")
                .isNotEqualTo(correctEpochForDag2);
        }

        @Test
        @DisplayName("FIX: getCurrentEpoch(runId, triggerId) returns per-DAG global epoch by run ID")
        void getCurrentEpochByRunIdAndTriggerId() {
            Map<String, Object> metadata = new HashMap<>(Map.of(
                "currentEpoch", 5,
                "dagLastEpoch", Map.of("trigger:dag1", 5, "trigger:dag2", 1)
            ));
            WorkflowRunEntity run = createRun("run-1", metadata);
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            // getCurrentEpoch(runId, triggerId) delegates to getGlobalEpochForDag
            assertThat(epochManager.getCurrentEpoch("run-1", "trigger:dag1"))
                .as("Per-DAG epoch for dag1 via runId")
                .isEqualTo(5);
            assertThat(epochManager.getCurrentEpoch("run-1", "trigger:dag2"))
                .as("Per-DAG epoch for dag2 via runId")
                .isEqualTo(1);
        }

        @Test
        @DisplayName("FIX: getCurrentEpoch(runId, null triggerId) falls back to global epoch")
        void getCurrentEpochByRunIdWithNullTriggerIdFallsBack() {
            Map<String, Object> metadata = new HashMap<>(Map.of(
                "currentEpoch", 5,
                "dagLastEpoch", Map.of("trigger:dag1", 5, "trigger:dag2", 1)
            ));
            WorkflowRunEntity run = createRun("run-1", metadata);
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            // null triggerId falls back to global epoch
            assertThat(epochManager.getCurrentEpoch("run-1", null))
                .as("Null triggerId falls back to global epoch")
                .isEqualTo(5);
        }

        @Test
        @DisplayName("FIX: getCurrentEpoch(runId, triggerId) returns 0 for unknown run")
        void getCurrentEpochByRunIdAndTriggerIdForUnknownRun() {
            when(runRepository.findByRunIdPublic("unknown")).thenReturn(Optional.empty());

            assertThat(epochManager.getCurrentEpoch("unknown", "trigger:dag1"))
                .isEqualTo(0);
        }

        @Test
        @DisplayName("Single-DAG workflow: global and per-DAG epoch are always equal")
        void singleDagEpochsAreConsistent() {
            Map<String, Object> dagLastEpoch = new HashMap<>(Map.of("trigger:only", 3));
            Map<String, Object> metadata = new HashMap<>(Map.of(
                "currentEpoch", 3,
                "dagLastEpoch", dagLastEpoch
            ));
            WorkflowRunEntity run = createRun("run-1", metadata);
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            // For single-DAG, global and per-DAG are always the same
            assertThat(epochManager.getCurrentEpoch("run-1"))
                .isEqualTo(epochManager.getCurrentEpoch(run, "trigger:only"))
                .isEqualTo(3);
        }
    }

    // =========================================================================
    // dagGlobalEpoch Tests
    // =========================================================================

    @Nested
    @DisplayName("dagGlobalEpoch mapping")
    class DagGlobalEpochTests {

        @Test
        @DisplayName("incrementEpoch stores dagLastEpoch mapping in metadata")
        void incrementEpochStoresDagLastEpoch() {
            Map<String, Object> metadata = new HashMap<>(Map.of(
                "currentEpoch", 2,
                "dagEpochs", new HashMap<>(Map.of("trigger:wh1", 2))
            ));
            WorkflowRunEntity lockedRun = createRun("run-1", metadata);

            when(runRepository.findByRunIdPublicForUpdate("run-1"))
                .thenReturn(Optional.of(lockedRun));
            when(runRepository.save(any())).thenReturn(lockedRun);

            // Act: fire trigger:wh2 for the first time
            WorkflowRunEntity inputRun = createRun("run-1", Map.of());
            epochManager.incrementEpoch(inputRun, "trigger:wh2");

            // Verify dagLastEpoch is stored (replaces legacy dagGlobalEpoch)
            @SuppressWarnings("unchecked")
            Map<String, Object> dagLastEpoch = (Map<String, Object>) lockedRun.getMetadata().get("dagLastEpoch");
            assertThat(dagLastEpoch).isNotNull();
            assertThat(dagLastEpoch.get("trigger:wh2"))
                .as("dagLastEpoch[wh2] should be the new global epoch (3)")
                .isEqualTo(3);
        }

        @Test
        @DisplayName("incrementEpoch updates dagLastEpoch on subsequent fires")
        void incrementEpochUpdatesDagLastEpochOnSubsequentFires() {
            Map<String, Object> dagGlobalEpoch = new HashMap<>(Map.of("trigger:wh1", 1));
            Map<String, Object> metadata = new HashMap<>(Map.of(
                "currentEpoch", 1,
                "dagEpochs", new HashMap<>(Map.of("trigger:wh1", 1)),
                "dagGlobalEpoch", dagGlobalEpoch
            ));
            WorkflowRunEntity lockedRun = createRun("run-1", metadata);

            when(runRepository.findByRunIdPublicForUpdate("run-1"))
                .thenReturn(Optional.of(lockedRun));
            when(runRepository.save(any())).thenReturn(lockedRun);

            // Act: fire trigger:wh1 again
            WorkflowRunEntity inputRun = createRun("run-1", Map.of());
            epochManager.incrementEpoch(inputRun, "trigger:wh1");

            // Verify dagLastEpoch updated for wh1 (migrated from legacy dagGlobalEpoch)
            @SuppressWarnings("unchecked")
            Map<String, Object> savedDagLastEpoch = (Map<String, Object>) lockedRun.getMetadata().get("dagLastEpoch");
            assertThat(savedDagLastEpoch.get("trigger:wh1"))
                .as("dagLastEpoch[wh1] should be updated to new global epoch (2)")
                .isEqualTo(2);
        }

        @Test
        @DisplayName("getGlobalEpochForDag returns correct mapping from entity")
        void getGlobalEpochForDagFromEntity() {
            Map<String, Object> metadata = new HashMap<>(Map.of(
                "currentEpoch", 7,
                "dagEpochs", Map.of("trigger:wh1", 3, "trigger:wh2", 4),
                "dagGlobalEpoch", Map.of("trigger:wh1", 5, "trigger:wh2", 7)
            ));
            WorkflowRunEntity run = createRun("run-1", metadata);

            assertThat(epochManager.getGlobalEpochForDag(run, "trigger:wh1"))
                .as("wh1 last fired at global epoch 5")
                .isEqualTo(5);
            assertThat(epochManager.getGlobalEpochForDag(run, "trigger:wh2"))
                .as("wh2 last fired at global epoch 7")
                .isEqualTo(7);
        }

        @Test
        @DisplayName("getGlobalEpochForDag returns correct mapping by runId")
        void getGlobalEpochForDagByRunId() {
            Map<String, Object> metadata = new HashMap<>(Map.of(
                "currentEpoch", 7,
                "dagGlobalEpoch", Map.of("trigger:wh1", 5, "trigger:wh2", 7)
            ));
            WorkflowRunEntity run = createRun("run-1", metadata);
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            assertThat(epochManager.getGlobalEpochForDag("run-1", "trigger:wh1")).isEqualTo(5);
            assertThat(epochManager.getGlobalEpochForDag("run-1", "trigger:wh2")).isEqualTo(7);
        }

        @Test
        @DisplayName("getGlobalEpochForDag falls back to global epoch when dagGlobalEpoch missing (backward compat)")
        void getGlobalEpochForDagFallsBackToGlobal() {
            // Old metadata without dagGlobalEpoch
            Map<String, Object> metadata = new HashMap<>(Map.of(
                "currentEpoch", 5,
                "dagEpochs", Map.of("trigger:wh1", 3, "trigger:wh2", 2)
            ));
            WorkflowRunEntity run = createRun("run-1", metadata);

            // No dagGlobalEpoch → falls back to global currentEpoch
            assertThat(epochManager.getGlobalEpochForDag(run, "trigger:wh1"))
                .as("Falls back to global epoch when dagGlobalEpoch is missing")
                .isEqualTo(5);
        }

        @Test
        @DisplayName("getGlobalEpochForDag falls back to global for unknown trigger")
        void getGlobalEpochForDagFallsBackForUnknownTrigger() {
            Map<String, Object> metadata = new HashMap<>(Map.of(
                "currentEpoch", 7,
                "dagGlobalEpoch", Map.of("trigger:wh1", 5)
            ));
            WorkflowRunEntity run = createRun("run-1", metadata);

            // trigger:unknown not in dagGlobalEpoch → falls back to global
            assertThat(epochManager.getGlobalEpochForDag(run, "trigger:unknown"))
                .as("Falls back to global epoch for unknown trigger")
                .isEqualTo(7);
        }

        @Test
        @DisplayName("getGlobalEpochForDag with null run returns 0")
        void getGlobalEpochForDagNullRun() {
            assertThat(epochManager.getGlobalEpochForDag((WorkflowRunEntity) null, "trigger:wh1"))
                .isEqualTo(0);
        }

        @Test
        @DisplayName("getGlobalEpochForDag with null triggerId falls back to global")
        void getGlobalEpochForDagNullTriggerId() {
            Map<String, Object> metadata = new HashMap<>(Map.of("currentEpoch", 3));
            WorkflowRunEntity run = createRun("run-1", metadata);

            assertThat(epochManager.getGlobalEpochForDag(run, null))
                .as("Null triggerId falls back to global epoch")
                .isEqualTo(3);
        }

        @Test
        @DisplayName("getGlobalEpochForDag by runId with null runId returns 0")
        void getGlobalEpochForDagNullRunId() {
            assertThat(epochManager.getGlobalEpochForDag((String) null, "trigger:wh1"))
                .isEqualTo(0);
        }

        @Test
        @DisplayName("getGlobalEpochForDag by runId with null triggerId falls back to global")
        void getGlobalEpochForDagByRunIdNullTriggerId() {
            Map<String, Object> metadata = new HashMap<>(Map.of("currentEpoch", 4));
            WorkflowRunEntity run = createRun("run-1", metadata);
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            assertThat(epochManager.getGlobalEpochForDag("run-1", null))
                .as("Null triggerId falls back to global epoch")
                .isEqualTo(4);
        }
    }
}
