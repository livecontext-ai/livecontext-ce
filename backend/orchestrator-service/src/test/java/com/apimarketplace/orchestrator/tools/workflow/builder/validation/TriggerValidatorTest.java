package com.apimarketplace.orchestrator.tools.workflow.builder.validation;

import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderValidator.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Tests for TriggerValidator.
 * Validates trigger count, label presence, and outgoing edge requirements.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TriggerValidator")
class TriggerValidatorTest {

    @Mock
    private WorkflowBuilderSession session;

    private TriggerValidator validator;

    @BeforeEach
    void setUp() {
        validator = new TriggerValidator();
    }

    private void stubSession(List<Map<String, Object>> triggers,
                             List<Map<String, Object>> mcps,
                             List<Map<String, Object>> cores,
                             List<Map<String, Object>> edges) {
        when(session.getTriggers()).thenReturn(triggers);
        when(session.getMcps()).thenReturn(mcps);
        when(session.getCores()).thenReturn(cores);
        when(session.getInterfaces()).thenReturn(List.of());
        when(session.getTables()).thenReturn(List.of());
        when(session.getEdges()).thenReturn(edges);
    }

    @Nested
    @DisplayName("Trigger count validation")
    class TriggerCountTests {

        @Test
        @DisplayName("Should add error when no triggers exist")
        void shouldAddErrorWhenNoTriggers() {
            when(session.getTriggers()).thenReturn(List.of());

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e ->
                    e.code().equals("MISSING_TRIGGER"));
        }

        @Test
        @DisplayName("Should not add MISSING_TRIGGER error when one trigger exists")
        void shouldNotAddMissingTriggerErrorWhenOneTriggerExists() {
            stubSession(
                    List.of(Map.of("label", "My Webhook")),
                    List.of(),
                    List.of(),
                    List.of(Map.of("from", "trigger:my_webhook", "to", "mcp:step"))
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("MISSING_TRIGGER"));
        }

        @Test
        @DisplayName("Should allow multiple triggers (multi-DAG support)")
        void shouldAllowMultipleTriggers() {
            stubSession(
                    List.of(
                            Map.of("label", "Webhook A"),
                            Map.of("label", "Webhook B")
                    ),
                    List.of(),
                    List.of(),
                    List.of(
                            Map.of("from", "trigger:webhook_a", "to", "mcp:step_a"),
                            Map.of("from", "trigger:webhook_b", "to", "mcp:step_b")
                    )
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("MULTIPLE_TRIGGERS"));
            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("MISSING_TRIGGER"));
        }

        @Test
        @DisplayName("Should report both MISSING_TRIGGER and avoid duplicate for empty list")
        void shouldReportMissingTriggerOnly() {
            when(session.getTriggers()).thenReturn(List.of());

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e -> e.code().equals("MISSING_TRIGGER"));
            assertThat(result.getErrors()).noneMatch(e -> e.code().equals("MULTIPLE_TRIGGERS"));
        }
    }

    @Nested
    @DisplayName("Label validation")
    class LabelTests {

        @Test
        @DisplayName("Should add error when trigger has no label")
        void shouldAddErrorWhenTriggerHasNoLabel() {
            Map<String, Object> trigger = new HashMap<>();
            trigger.put("label", null);

            stubSession(
                    List.of(trigger),
                    List.of(),
                    List.of(),
                    List.of()
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e ->
                    e.code().equals("MISSING_LABEL"));
        }

        @Test
        @DisplayName("Should add error when trigger label is blank")
        void shouldAddErrorWhenTriggerLabelIsBlank() {
            stubSession(
                    List.of(Map.of("label", "   ")),
                    List.of(),
                    List.of(),
                    List.of()
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e ->
                    e.code().equals("MISSING_LABEL"));
        }

        @Test
        @DisplayName("Should not add MISSING_LABEL when trigger has valid label")
        void shouldNotAddMissingLabelWhenTriggerHasValidLabel() {
            stubSession(
                    List.of(Map.of("label", "My Trigger")),
                    List.of(),
                    List.of(),
                    List.of(Map.of("from", "trigger:my_trigger", "to", "mcp:step"))
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("MISSING_LABEL"));
        }

        @Test
        @DisplayName("Should skip outgoing edge check when label is missing")
        void shouldSkipOutgoingEdgeCheckWhenLabelIsMissing() {
            Map<String, Object> trigger = new HashMap<>();
            trigger.put("label", null);

            stubSession(
                    List.of(trigger),
                    List.of(),
                    List.of(),
                    List.of()
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            // Should have MISSING_LABEL but not TRIGGER_NO_EDGES
            assertThat(result.getErrors()).anyMatch(e -> e.code().equals("MISSING_LABEL"));
            assertThat(result.getErrors()).noneMatch(e -> e.code().equals("TRIGGER_NO_EDGES"));
        }
    }

    @Nested
    @DisplayName("Outgoing edge validation")
    class OutgoingEdgeTests {

        @Test
        @DisplayName("Should add error when trigger has no outgoing edges")
        void shouldAddErrorWhenTriggerHasNoOutgoingEdges() {
            stubSession(
                    List.of(Map.of("label", "My Trigger")),
                    List.of(),
                    List.of(),
                    List.of()
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e ->
                    e.code().equals("TRIGGER_NO_EDGES"));
        }

        @Test
        @DisplayName("Should not add TRIGGER_NO_EDGES when trigger has outgoing edges")
        void shouldNotAddTriggerNoEdgesWhenTriggerHasOutgoingEdges() {
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "Step")),
                    List.of(),
                    List.of(Map.of("from", "trigger:start", "to", "mcp:step"))
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("TRIGGER_NO_EDGES"));
        }

        @Test
        @DisplayName("Should report correct trigger index in error nodeId")
        void shouldReportCorrectTriggerIndexInError() {
            stubSession(
                    List.of(Map.of("label", "Lonely Trigger")),
                    List.of(),
                    List.of(),
                    List.of()
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e ->
                    e.code().equals("TRIGGER_NO_EDGES") &&
                    e.nodeId().equals("triggers[0]"));
        }

        @Test
        @DisplayName("Should include trigger label in error message for no edges")
        void shouldIncludeTriggerLabelInErrorMessage() {
            stubSession(
                    List.of(Map.of("label", "My Webhook")),
                    List.of(),
                    List.of(),
                    List.of()
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e ->
                    e.code().equals("TRIGGER_NO_EDGES") &&
                    e.message().contains("My Webhook"));
        }
    }

    @Nested
    @DisplayName("Validate with shared graph analyzer")
    class SharedGraphAnalyzerTests {

        @Test
        @DisplayName("Should work correctly with pre-built graph analyzer")
        void shouldWorkWithPreBuiltGraphAnalyzer() {
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "Step")),
                    List.of(),
                    List.of(Map.of("from", "trigger:start", "to", "mcp:step"))
            );

            ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);
            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, graph, result);

            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("MISSING_TRIGGER") ||
                    e.code().equals("TRIGGER_NO_EDGES"));
        }
    }

    @Test
    @DisplayName("Should handle trigger with special characters in label")
    void shouldHandleTriggerWithSpecialCharactersInLabel() {
        stubSession(
                List.of(Map.of("label", "My Webhook!!")),
                List.of(Map.of("label", "Step")),
                List.of(),
                List.of(Map.of("from", "trigger:my_webhook", "to", "mcp:step"))
        );

        ValidationResult result = ValidationResult.builder().build();
        validator.validate(session, result);

        assertThat(result.getErrors()).noneMatch(e ->
                e.code().equals("TRIGGER_NO_EDGES"));
    }

    // ==================== Multi-DAG Validation ====================

    @Nested
    @DisplayName("Multi-DAG trigger validation")
    class MultiDagValidation {

        @Test
        @DisplayName("Should pass validation for two triggers with edges (webhook + manual)")
        void shouldPassForTwoTriggersWithEdges() {
            stubSession(
                    List.of(
                            Map.of("label", "Webhook Start"),
                            Map.of("label", "Manual Start")
                    ),
                    List.of(),
                    List.of(),
                    List.of(
                            Map.of("from", "trigger:webhook_start", "to", "mcp:step_a"),
                            Map.of("from", "trigger:manual_start", "to", "mcp:step_b")
                    )
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).isEmpty();
        }

        @Test
        @DisplayName("Should pass for three triggers (webhook + schedule + chat)")
        void shouldPassForThreeTriggers() {
            stubSession(
                    List.of(
                            Map.of("label", "Webhook Entry"),
                            Map.of("label", "Schedule Entry"),
                            Map.of("label", "Chat Entry")
                    ),
                    List.of(),
                    List.of(),
                    List.of(
                            Map.of("from", "trigger:webhook_entry", "to", "mcp:wh_step"),
                            Map.of("from", "trigger:schedule_entry", "to", "mcp:sched_step"),
                            Map.of("from", "trigger:chat_entry", "to", "mcp:chat_step")
                    )
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).isEmpty();
        }

        @Test
        @DisplayName("Should report TRIGGER_NO_EDGES only for trigger without edges in multi-trigger")
        void shouldReportNoEdgesOnlyForTriggerWithoutEdges() {
            stubSession(
                    List.of(
                            Map.of("label", "Webhook Connected"),
                            Map.of("label", "Manual Orphan")
                    ),
                    List.of(),
                    List.of(),
                    List.of(
                            Map.of("from", "trigger:webhook_connected", "to", "mcp:step")
                    )
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            // Only the second trigger should have TRIGGER_NO_EDGES
            assertThat(result.getErrors()).hasSize(1);
            assertThat(result.getErrors().get(0).code()).isEqualTo("TRIGGER_NO_EDGES");
            assertThat(result.getErrors().get(0).message()).contains("Manual Orphan");
        }

        @Test
        @DisplayName("Should pass for two form triggers with edges")
        void shouldPassForTwoFormTriggersWithEdges() {
            stubSession(
                    List.of(
                            Map.of("label", "Contact Form"),
                            Map.of("label", "Feedback Form")
                    ),
                    List.of(),
                    List.of(),
                    List.of(
                            Map.of("from", "trigger:contact_form", "to", "mcp:send_email"),
                            Map.of("from", "trigger:feedback_form", "to", "mcp:store_feedback")
                    )
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).isEmpty();
        }

        @Test
        @DisplayName("Should pass for two schedule triggers with edges")
        void shouldPassForTwoScheduleTriggersWithEdges() {
            stubSession(
                    List.of(
                            Map.of("label", "Morning Sync"),
                            Map.of("label", "Evening Report")
                    ),
                    List.of(),
                    List.of(),
                    List.of(
                            Map.of("from", "trigger:morning_sync", "to", "mcp:sync_data"),
                            Map.of("from", "trigger:evening_report", "to", "mcp:generate_report")
                    )
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).isEmpty();
        }

        @Test
        @DisplayName("Should pass for webhook + form + schedule with edges")
        void shouldPassForWebhookFormSchedule() {
            stubSession(
                    List.of(
                            Map.of("label", "API Webhook"),
                            Map.of("label", "User Form"),
                            Map.of("label", "Daily Cron")
                    ),
                    List.of(),
                    List.of(),
                    List.of(
                            Map.of("from", "trigger:api_webhook", "to", "mcp:process_webhook"),
                            Map.of("from", "trigger:user_form", "to", "mcp:process_form"),
                            Map.of("from", "trigger:daily_cron", "to", "mcp:run_daily")
                    )
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).isEmpty();
        }
    }

    @Test
    @DisplayName("Should accumulate label errors for multiple triggers with missing labels")
    void shouldAccumulateMultipleErrors() {
        Map<String, Object> trigger1 = new HashMap<>();
        trigger1.put("label", null);
        Map<String, Object> trigger2 = new HashMap<>();
        trigger2.put("label", null);

        stubSession(
                List.of(trigger1, trigger2),
                List.of(),
                List.of(),
                List.of()
        );

        ValidationResult result = ValidationResult.builder().build();
        validator.validate(session, result);

        // No MULTIPLE_TRIGGERS error (multi-DAG allowed), but 2x MISSING_LABEL
        assertThat(result.getErrors()).noneMatch(e -> e.code().equals("MULTIPLE_TRIGGERS"));
        long missingLabelCount = result.getErrors().stream()
                .filter(e -> e.code().equals("MISSING_LABEL"))
                .count();
        assertThat(missingLabelCount).isEqualTo(2);
    }

    /**
     * 2026-09-29: set_plan imports a form field add_node would refuse and names it in its
     * warnings; validate and finish must keep naming it until it is fixed, or an agent that
     * missed the set_plan warning ships a form that cannot render. A warning, never an error:
     * a workflow saved long ago with such a field must stay saveable.
     */
    @Nested
    @DisplayName("Form trigger fields (FORM_FIELD_INVALID)")
    class FormFieldTests {

        private Map<String, Object> formTrigger(List<Map<String, Object>> fields) {
            return Map.of("label", "Demande", "type", "form", "params", Map.of("fields", fields));
        }

        private ValidationResult validate(Map<String, Object> trigger) {
            stubSession(List.of(trigger), List.of(), List.of(),
                    List.of(Map.of("from", "trigger:demande", "to", "mcp:step")));
            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);
            return result;
        }

        @Test
        @DisplayName("a field add_node would refuse is a warning naming it and the fix, never an error")
        void invalidFieldIsAWarning() {
            ValidationResult result = validate(formTrigger(List.of(
                    Map.of("name", "tier", "type", "select", "options", List.of()))));

            assertThat(result.getWarnings()).anySatisfy(w -> {
                assertThat(w.code()).isEqualTo("FORM_FIELD_INVALID");
                assertThat(w.message()).contains("Form trigger 'Demande'")
                        .contains("field 'tier': 'options' is empty")
                        .contains("workflow(action='modify', node='Demande'");
            });
            assertThat(result.getErrors()).noneMatch(e -> e.code().equals("FORM_FIELD_INVALID"));
        }

        @Test
        @DisplayName("valid fields, even in shorthand or with a `default` alias, raise nothing")
        void validFieldsRaiseNothing() {
            ValidationResult result = validate(formTrigger(List.of(
                    Map.of("name", "tier", "type", "select", "options", List.of("free", "pro")),
                    Map.of("name", "theme", "type", "string", "default", "Innovation"))));

            assertThat(result.getWarnings()).noneMatch(w -> w.code().equals("FORM_FIELD_INVALID"));
        }

        @Test
        @DisplayName("a non-form trigger is never checked for form fields")
        void nonFormTriggerIgnored() {
            ValidationResult result = validate(Map.of("label", "Demande", "type", "webhook",
                    "params", Map.of("fields", List.of(Map.of("type", "select")))));

            assertThat(result.getWarnings()).noneMatch(w -> w.code().equals("FORM_FIELD_INVALID"));
        }
    }
}

