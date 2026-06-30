package com.apimarketplace.orchestrator.services.resume.state;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StateReconstructionContext")
class StateReconstructionContextTest {

    @Mock
    private WorkflowPlan mockPlan;

    @Nested
    @DisplayName("Record construction")
    class ConstructionTests {

        @Test
        @DisplayName("Should create with all fields")
        void shouldCreateWithAllFields() {
            WorkflowRunEntity entity = mock(WorkflowRunEntity.class);
            Map<String, Object> metadata = Map.of("key", "val");

            StateReconstructionContext context = new StateReconstructionContext(
                "run-1", entity, mockPlan, 2, metadata
            );

            assertEquals("run-1", context.runId());
            assertEquals(entity, context.runEntity());
            assertEquals(mockPlan, context.plan());
            assertEquals(2, context.currentEpoch());
            assertEquals(metadata, context.metadata());
        }
    }

    @Nested
    @DisplayName("fromRunEntity()")
    class FromRunEntityTests {

        @Test
        @DisplayName("Should extract epoch from metadata")
        void shouldExtractEpochFromMetadata() {
            WorkflowRunEntity entity = mock(WorkflowRunEntity.class);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("currentEpoch", 3);
            when(entity.getMetadata()).thenReturn(metadata);
            when(entity.getRunIdPublic()).thenReturn("run-1");

            StateReconstructionContext context = StateReconstructionContext.fromRunEntity(entity, mockPlan);

            assertEquals("run-1", context.runId());
            assertEquals(3, context.currentEpoch());
            assertEquals(metadata, context.metadata());
        }

        @Test
        @DisplayName("Should default to epoch 0 when no metadata")
        void shouldDefaultToEpochZeroWhenNoMetadata() {
            WorkflowRunEntity entity = mock(WorkflowRunEntity.class);
            when(entity.getMetadata()).thenReturn(null);
            when(entity.getRunIdPublic()).thenReturn("run-1");

            StateReconstructionContext context = StateReconstructionContext.fromRunEntity(entity, mockPlan);

            assertEquals(0, context.currentEpoch());
        }

        @Test
        @DisplayName("Should default to epoch 0 when metadata has no currentEpoch")
        void shouldDefaultToEpochZeroWhenNoCurrentEpoch() {
            WorkflowRunEntity entity = mock(WorkflowRunEntity.class);
            when(entity.getMetadata()).thenReturn(Map.of());
            when(entity.getRunIdPublic()).thenReturn("run-1");

            StateReconstructionContext context = StateReconstructionContext.fromRunEntity(entity, mockPlan);

            assertEquals(0, context.currentEpoch());
        }

        @Test
        @DisplayName("Should handle non-Number epoch value gracefully")
        void shouldHandleNonNumberEpoch() {
            WorkflowRunEntity entity = mock(WorkflowRunEntity.class);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("currentEpoch", "not_a_number");
            when(entity.getMetadata()).thenReturn(metadata);
            when(entity.getRunIdPublic()).thenReturn("run-1");

            StateReconstructionContext context = StateReconstructionContext.fromRunEntity(entity, mockPlan);

            assertEquals(0, context.currentEpoch());
        }
    }

    @Nested
    @DisplayName("Record equality")
    class EqualityTests {

        @Test
        @DisplayName("Should be equal for same values")
        void shouldBeEqualForSameValues() {
            WorkflowRunEntity entity = mock(WorkflowRunEntity.class);
            Map<String, Object> metadata = Map.of("key", "val");

            StateReconstructionContext a = new StateReconstructionContext(
                "run-1", entity, mockPlan, 0, metadata
            );
            StateReconstructionContext b = new StateReconstructionContext(
                "run-1", entity, mockPlan, 0, metadata
            );

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }
    }
}
