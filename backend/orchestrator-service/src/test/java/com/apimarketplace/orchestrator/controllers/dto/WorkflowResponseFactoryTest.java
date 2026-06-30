package com.apimarketplace.orchestrator.controllers.dto;

import com.apimarketplace.orchestrator.domain.workflow.ValidationError;
import com.apimarketplace.orchestrator.domain.workflow.ValidationWarning;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for WorkflowResponseFactory.
 */
@DisplayName("WorkflowResponseFactory")
class WorkflowResponseFactoryTest {

    private WorkflowResponseFactory factory;

    @BeforeEach
    void setUp() {
        factory = new WorkflowResponseFactory();
    }

    @Nested
    @DisplayName("createFailureResponse")
    class CreateFailureResponseTests {

        @Test
        @DisplayName("Should create failure response with message")
        void shouldCreateFailureResponse() {
            WorkflowExecutionResponse response = factory.createFailureResponse("Plan not found");

            assertThat(response.getRunId()).isNull();
            assertThat(response.getWorkflowId()).isNull();
            assertThat(response.getStatus()).isEqualTo("FAILED");
            assertThat(response.getMessage()).isEqualTo("Plan not found");
            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getTotalSteps()).isZero();
            assertThat(response.getTimestamp()).isNotNull();
        }
    }

    @Nested
    @DisplayName("extractErrorMessages")
    class ExtractErrorMessagesTests {

        @Test
        @DisplayName("Should extract messages from error list")
        void shouldExtractMessages() {
            List<ValidationError> errors = List.of(
                new ValidationError("MISSING_TRIGGER", "No trigger found", null, null),
                new ValidationError("INVALID_EDGE", "Edge to unknown node", null, null)
            );

            List<String> messages = factory.extractErrorMessages(errors);

            assertThat(messages).containsExactly("No trigger found", "Edge to unknown node");
        }

        @Test
        @DisplayName("Should return empty list for null errors")
        void shouldReturnEmptyForNull() {
            List<String> messages = factory.extractErrorMessages(null);
            assertThat(messages).isEmpty();
        }

        @Test
        @DisplayName("Should return empty list for empty errors")
        void shouldReturnEmptyForEmpty() {
            List<String> messages = factory.extractErrorMessages(List.of());
            assertThat(messages).isEmpty();
        }
    }

    @Nested
    @DisplayName("extractErrorDetails")
    class ExtractErrorDetailsTests {

        @Test
        @DisplayName("Should extract error details from list")
        void shouldExtractDetails() {
            Map<String, Object> context = Map.of("expected", "at least 1");
            List<ValidationError> errors = List.of(
                new ValidationError("MISSING_TRIGGER", "No trigger found", "triggers", context)
            );

            List<WorkflowValidationResponse.ValidationErrorDetail> details = factory.extractErrorDetails(errors);

            assertThat(details).hasSize(1);
            assertThat(details.get(0).getType()).isEqualTo("MISSING_TRIGGER");
            assertThat(details.get(0).getMessage()).isEqualTo("No trigger found");
            assertThat(details.get(0).getPath()).isEqualTo("triggers");
            assertThat(details.get(0).getContext()).containsEntry("expected", "at least 1");
        }

        @Test
        @DisplayName("Should return empty list for null errors")
        void shouldReturnEmptyForNull() {
            assertThat(factory.extractErrorDetails(null)).isEmpty();
        }

        @Test
        @DisplayName("Should return empty list for empty errors")
        void shouldReturnEmptyForEmpty() {
            assertThat(factory.extractErrorDetails(List.of())).isEmpty();
        }
    }

    @Nested
    @DisplayName("extractWarningMessages")
    class ExtractWarningMessagesTests {

        @Test
        @DisplayName("Should extract messages from warning list")
        void shouldExtractMessages() {
            List<ValidationWarning> warnings = List.of(
                new ValidationWarning("UNUSED", "Node is unused", null, null)
            );

            List<String> messages = factory.extractWarningMessages(warnings);
            assertThat(messages).containsExactly("Node is unused");
        }

        @Test
        @DisplayName("Should return empty list for null warnings")
        void shouldReturnEmptyForNull() {
            assertThat(factory.extractWarningMessages(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("extractWarningDetails")
    class ExtractWarningDetailsTests {

        @Test
        @DisplayName("Should extract warning details from list")
        void shouldExtractDetails() {
            Map<String, Object> context = Map.of("hint", "connect it");
            List<ValidationWarning> warnings = List.of(
                new ValidationWarning("UNUSED", "Node unused", "mcps[0]", context)
            );

            List<WorkflowValidationResponse.ValidationWarningDetail> details =
                factory.extractWarningDetails(warnings);

            assertThat(details).hasSize(1);
            assertThat(details.get(0).getType()).isEqualTo("UNUSED");
            assertThat(details.get(0).getMessage()).isEqualTo("Node unused");
            assertThat(details.get(0).getPath()).isEqualTo("mcps[0]");
        }

        @Test
        @DisplayName("Should return empty list for null warnings")
        void shouldReturnEmptyForNull() {
            assertThat(factory.extractWarningDetails(null)).isEmpty();
        }

        @Test
        @DisplayName("Should return empty list for empty warnings")
        void shouldReturnEmptyForEmpty() {
            assertThat(factory.extractWarningDetails(List.of())).isEmpty();
        }
    }
}
