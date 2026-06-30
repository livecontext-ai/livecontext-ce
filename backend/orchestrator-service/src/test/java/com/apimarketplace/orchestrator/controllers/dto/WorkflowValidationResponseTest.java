package com.apimarketplace.orchestrator.controllers.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for WorkflowValidationResponse DTO.
 */
@DisplayName("WorkflowValidationResponse")
class WorkflowValidationResponseTest {

    @Nested
    @DisplayName("constructor and getters")
    class ConstructorTests {

        @Test
        @DisplayName("Should store valid response with no errors")
        void shouldStoreValidResponse() {
            Instant now = Instant.now();
            WorkflowValidationResponse response = new WorkflowValidationResponse(
                true, List.of(), List.of(), List.of(), List.of(), 5, now
            );

            assertThat(response.isValid()).isTrue();
            assertThat(response.getErrors()).isEmpty();
            assertThat(response.getErrorDetails()).isEmpty();
            assertThat(response.getWarnings()).isEmpty();
            assertThat(response.getWarningDetails()).isEmpty();
            assertThat(response.getComplexityScore()).isEqualTo(5);
            assertThat(response.getTimestamp()).isEqualTo(now);
        }

        @Test
        @DisplayName("Should store invalid response with errors and warnings")
        void shouldStoreInvalidResponse() {
            Instant now = Instant.now();
            List<String> errors = List.of("Missing trigger", "Invalid edge");
            List<WorkflowValidationResponse.ValidationErrorDetail> errorDetails = List.of(
                new WorkflowValidationResponse.ValidationErrorDetail(
                    "MISSING_TRIGGER", "Missing trigger", "triggers", Map.of("expected", "at least 1")
                )
            );
            List<String> warnings = List.of("Unused node");
            List<WorkflowValidationResponse.ValidationWarningDetail> warningDetails = List.of(
                new WorkflowValidationResponse.ValidationWarningDetail(
                    "UNUSED_NODE", "Unused node", "mcps[0]", Map.of("node", "step_1")
                )
            );

            WorkflowValidationResponse response = new WorkflowValidationResponse(
                false, errors, errorDetails, warnings, warningDetails, 15, now
            );

            assertThat(response.isValid()).isFalse();
            assertThat(response.getErrors()).hasSize(2);
            assertThat(response.getErrorDetails()).hasSize(1);
            assertThat(response.getWarnings()).hasSize(1);
            assertThat(response.getWarningDetails()).hasSize(1);
            assertThat(response.getComplexityScore()).isEqualTo(15);
        }
    }

    @Nested
    @DisplayName("ValidationErrorDetail")
    class ValidationErrorDetailTests {

        @Test
        @DisplayName("Should store all error detail fields")
        void shouldStoreAllFields() {
            Map<String, Object> context = Map.of("node", "mcp:step_1", "severity", "critical");
            WorkflowValidationResponse.ValidationErrorDetail detail =
                new WorkflowValidationResponse.ValidationErrorDetail(
                    "INVALID_EDGE", "Edge references unknown node", "edges[0]", context
                );

            assertThat(detail.getType()).isEqualTo("INVALID_EDGE");
            assertThat(detail.getMessage()).isEqualTo("Edge references unknown node");
            assertThat(detail.getPath()).isEqualTo("edges[0]");
            assertThat(detail.getContext()).containsEntry("node", "mcp:step_1");
        }

        @Test
        @DisplayName("Should handle null context")
        void shouldHandleNullContext() {
            WorkflowValidationResponse.ValidationErrorDetail detail =
                new WorkflowValidationResponse.ValidationErrorDetail(
                    "TYPE", "message", null, null
                );

            assertThat(detail.getContext()).isNull();
            assertThat(detail.getPath()).isNull();
        }
    }

    @Nested
    @DisplayName("ValidationWarningDetail")
    class ValidationWarningDetailTests {

        @Test
        @DisplayName("Should store all warning detail fields")
        void shouldStoreAllFields() {
            Map<String, Object> context = Map.of("suggestion", "Remove unused node");
            WorkflowValidationResponse.ValidationWarningDetail detail =
                new WorkflowValidationResponse.ValidationWarningDetail(
                    "UNUSED_NODE", "Node has no incoming edges", "mcps[2]", context
                );

            assertThat(detail.getType()).isEqualTo("UNUSED_NODE");
            assertThat(detail.getMessage()).isEqualTo("Node has no incoming edges");
            assertThat(detail.getPath()).isEqualTo("mcps[2]");
            assertThat(detail.getContext()).containsEntry("suggestion", "Remove unused node");
        }
    }
}
