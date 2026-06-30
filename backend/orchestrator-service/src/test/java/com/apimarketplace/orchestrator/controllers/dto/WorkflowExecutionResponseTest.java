package com.apimarketplace.orchestrator.controllers.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for WorkflowExecutionResponse DTO.
 */
@DisplayName("WorkflowExecutionResponse")
class WorkflowExecutionResponseTest {

    @Nested
    @DisplayName("constructor and getters")
    class ConstructorTests {

        @Test
        @DisplayName("Should store all fields correctly")
        void shouldStoreAllFields() {
            Instant now = Instant.now();
            Instant startTime = now.minusSeconds(60);

            WorkflowExecutionResponse response = new WorkflowExecutionResponse(
                "run-123",
                "wf-456",
                "RUNNING",
                "Workflow started successfully",
                "tenant-1",
                startTime,
                5,
                true,
                now
            );

            assertThat(response.getRunId()).isEqualTo("run-123");
            assertThat(response.getWorkflowId()).isEqualTo("wf-456");
            assertThat(response.getStatus()).isEqualTo("RUNNING");
            assertThat(response.getMessage()).isEqualTo("Workflow started successfully");
            assertThat(response.getTenantId()).isEqualTo("tenant-1");
            assertThat(response.getStartTime()).isEqualTo(startTime);
            assertThat(response.getTotalSteps()).isEqualTo(5);
            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getTimestamp()).isEqualTo(now);
        }

        @Test
        @DisplayName("Should handle null fields")
        void shouldHandleNullFields() {
            WorkflowExecutionResponse response = new WorkflowExecutionResponse(
                null, null, "FAILED", "Error", null, null, 0, false, null
            );

            assertThat(response.getRunId()).isNull();
            assertThat(response.getWorkflowId()).isNull();
            assertThat(response.getStatus()).isEqualTo("FAILED");
            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getTotalSteps()).isZero();
        }
    }
}
