package com.apimarketplace.orchestrator.controllers.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for WorkflowPlanRequest.
 */
@DisplayName("WorkflowPlanRequest")
class WorkflowPlanRequestTest {

    @Nested
    @DisplayName("constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should store all fields")
        void shouldStoreAllFields() {
            Map<String, Object> inputs = Map.of("key", "value");
            WorkflowPlanRequest request = new WorkflowPlanRequest(
                "{\"plan\":true}", inputs, "wf-123", "automatic", null, null);

            assertThat(request.getPlanJson()).isEqualTo("{\"plan\":true}");
            assertThat(request.getDataInputs()).containsEntry("key", "value");
            assertThat(request.getWorkflowId()).isEqualTo("wf-123");
            assertThat(request.getExecutionMode()).isEqualTo("automatic");
        }

        @Test
        @DisplayName("Should default null dataInputs to empty map")
        void shouldDefaultNullDataInputs() {
            WorkflowPlanRequest request = new WorkflowPlanRequest("{}", null, null, null, null, null);
            assertThat(request.getDataInputs()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("Should return unmodifiable dataInputs")
        void shouldReturnUnmodifiableDataInputs() {
            WorkflowPlanRequest request = new WorkflowPlanRequest(
                "{}", Map.of("k", "v"), null, null, null, null);

            assertThatThrownBy(() -> request.getDataInputs().put("new", "val"))
                .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("isStepByStepMode")
    class StepByStepTests {

        @Test
        @DisplayName("Should return true for step_by_step mode")
        void shouldReturnTrueForStepByStep() {
            WorkflowPlanRequest request = new WorkflowPlanRequest("{}", null, null, "step_by_step", null, null);
            assertThat(request.isStepByStepMode()).isTrue();
        }

        @Test
        @DisplayName("Should be case insensitive")
        void shouldBeCaseInsensitive() {
            WorkflowPlanRequest request = new WorkflowPlanRequest("{}", null, null, "STEP_BY_STEP", null, null);
            assertThat(request.isStepByStepMode()).isTrue();
        }

        @Test
        @DisplayName("Should return false for automatic mode")
        void shouldReturnFalseForAutomatic() {
            WorkflowPlanRequest request = new WorkflowPlanRequest("{}", null, null, "automatic", null, null);
            assertThat(request.isStepByStepMode()).isFalse();
        }

        @Test
        @DisplayName("Should return false for null mode")
        void shouldReturnFalseForNull() {
            WorkflowPlanRequest request = new WorkflowPlanRequest("{}", null, null, null, null, null);
            assertThat(request.isStepByStepMode()).isFalse();
        }
    }
}
