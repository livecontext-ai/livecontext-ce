package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderValidator.ValidationError;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderValidator.ValidationResult;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderValidator.ValidationWarning;
import com.apimarketplace.orchestrator.tools.workflow.builder.validation.CoreValidator;
import com.apimarketplace.orchestrator.tools.workflow.builder.validation.EdgeValidator;
import com.apimarketplace.orchestrator.tools.workflow.builder.validation.GraphValidation;
import com.apimarketplace.orchestrator.tools.workflow.builder.validation.NodeStructureValidator;
import com.apimarketplace.orchestrator.tools.workflow.builder.validation.ReferenceValidator;
import com.apimarketplace.orchestrator.tools.workflow.builder.validation.StepValidator;
import com.apimarketplace.orchestrator.tools.workflow.builder.validation.TriggerValidator;
import com.apimarketplace.orchestrator.tools.workflow.builder.validation.ValidationGraphAnalyzer;
import com.apimarketplace.orchestrator.tools.workflow.builder.viewer.WorkflowErrorChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Tests for WorkflowBuilderValidator: inner types, sub-validator orchestration,
 * legacy WorkflowErrorChecker merging, and agent-format rendering.
 */
@DisplayName("WorkflowBuilderValidator")
@ExtendWith(MockitoExtension.class)
class WorkflowBuilderValidatorTest {

    @Nested
    @DisplayName("ValidationResult")
    class ValidationResultTests {

        @Test
        @DisplayName("Should initialize with empty lists")
        void shouldInitializeWithEmptyLists() {
            ValidationResult result = ValidationResult.builder().build();

            assertThat(result.getErrors()).isEmpty();
            assertThat(result.getWarnings()).isEmpty();
            assertThat(result.getAgentErrors()).isEmpty();
            assertThat(result.getAgentWarnings()).isEmpty();
        }

        @Test
        @DisplayName("Should add error with 3-arg method")
        void shouldAddErrorWith3Args() {
            ValidationResult result = ValidationResult.builder().build();
            result.addError("CODE_1", "trigger:start", "Some error");

            assertThat(result.getErrors()).hasSize(1);
            assertThat(result.getErrors().get(0).code()).isEqualTo("CODE_1");
            assertThat(result.getErrors().get(0).nodeId()).isEqualTo("trigger:start");
            assertThat(result.getErrors().get(0).field()).isNull();
            assertThat(result.getErrors().get(0).message()).isEqualTo("Some error");
        }

        @Test
        @DisplayName("Should add error with 4-arg method")
        void shouldAddErrorWith4Args() {
            ValidationResult result = ValidationResult.builder().build();
            result.addError("CODE_2", "mcp:step1", "from", "Invalid source");

            assertThat(result.getErrors()).hasSize(1);
            assertThat(result.getErrors().get(0).code()).isEqualTo("CODE_2");
            assertThat(result.getErrors().get(0).nodeId()).isEqualTo("mcp:step1");
            assertThat(result.getErrors().get(0).field()).isEqualTo("from");
            assertThat(result.getErrors().get(0).message()).isEqualTo("Invalid source");
        }

        @Test
        @DisplayName("Should add warning")
        void shouldAddWarning() {
            ValidationResult result = ValidationResult.builder().build();
            result.addWarning("WARN_1", "core:check", "May cause issues");

            assertThat(result.getWarnings()).hasSize(1);
            assertThat(result.getWarnings().get(0).code()).isEqualTo("WARN_1");
            assertThat(result.getWarnings().get(0).nodeId()).isEqualTo("core:check");
            assertThat(result.getWarnings().get(0).message()).isEqualTo("May cause issues");
        }
    }

    @Nested
    @DisplayName("ValidationError record")
    class ValidationErrorTests {

        @Test
        @DisplayName("Should store all fields")
        void shouldStoreAllFields() {
            ValidationError error = new ValidationError("ERR", "node:1", "field1", "message");

            assertThat(error.code()).isEqualTo("ERR");
            assertThat(error.nodeId()).isEqualTo("node:1");
            assertThat(error.field()).isEqualTo("field1");
            assertThat(error.message()).isEqualTo("message");
        }
    }

    @Nested
    @DisplayName("ValidationWarning record")
    class ValidationWarningTests {

        @Test
        @DisplayName("Should store all fields")
        void shouldStoreAllFields() {
            ValidationWarning warning = new ValidationWarning("WARN", "node:2", "warning text");

            assertThat(warning.code()).isEqualTo("WARN");
            assertThat(warning.nodeId()).isEqualTo("node:2");
            assertThat(warning.message()).isEqualTo("warning text");
        }
    }

    /**
     * Regression tests for the Gmail-in-prod bug: validate / finish / save used to
     * run different validator chains and disagree, so a workflow with (a) classify
     * missing categories and (b) legacy-checker findings (MCP tool id unknown)
     * could pass validate but land in prod via save. Now all three call
     * {@link WorkflowBuilderValidator#validate} which merges typed sub-validator
     * errors with {@link WorkflowErrorChecker}'s legacy Map-format errors.
     */
    @Nested
    @DisplayName("Unified validate() - sub-validators + legacy checker")
    class UnifiedValidateTests {

        @Mock private TriggerValidator triggerValidator;
        @Mock private StepValidator stepValidator;
        @Mock private CoreValidator coreValidator;
        @Mock private EdgeValidator edgeValidator;
        @Mock private GraphValidation graphValidator;
        @Mock private ReferenceValidator referenceValidator;
        @Mock private NodeStructureValidator nodeStructureValidator;
        @Mock private WorkflowErrorChecker workflowErrorChecker;

        private WorkflowBuilderValidator validator;
        private WorkflowBuilderSession session;

        @BeforeEach
        void setUp() {
            validator = new WorkflowBuilderValidator(
                triggerValidator, stepValidator, coreValidator, edgeValidator,
                graphValidator, referenceValidator, nodeStructureValidator, workflowErrorChecker);

            session = WorkflowBuilderSession.builder()
                .sessionId("s")
                .tenantId("t")
                .workflowName("Test")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        }

        @Test
        @DisplayName("Aggregates typed errors from a sub-validator into ValidationResult.errors")
        void aggregatesTypedErrors() {
            doAnswer(inv -> {
                ValidationResult r = inv.getArgument(1);
                r.addError("AGENT_MISSING_PARAM", "agent:classify_email",
                    "'Classify email' (classify) requires 'categories'. Fix: ...");
                return null;
            }).when(stepValidator).validate(any(), any());
            when(workflowErrorChecker.checkForErrors(any()))
                .thenReturn(new WorkflowErrorChecker.CheckResult(List.of(), List.of(), true, "ok"));

            ValidationResult result = validator.validate(session);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrors()).hasSize(1);
            assertThat(result.getErrors().get(0).code()).isEqualTo("AGENT_MISSING_PARAM");
            assertThat(result.getAgentErrors()).isEmpty();
        }

        @Test
        @DisplayName("Merges legacy Map-format errors into agentErrors so valid=false even when typed errors are empty")
        void mergesLegacyErrors() {
            Map<String, Object> legacyErr = new LinkedHashMap<>();
            legacyErr.put("type", "TOOL_ID_NOT_FOUND");
            legacyErr.put("node", "mcp:gmail_send");
            legacyErr.put("message", "Tool 'gmail/send' is not registered");
            legacyErr.put("fix", "Use workflow(action='list_nodes') to find a valid tool");
            when(workflowErrorChecker.checkForErrors(any()))
                .thenReturn(new WorkflowErrorChecker.CheckResult(
                    List.of(legacyErr), List.of(), false, "1 error"));

            ValidationResult result = validator.validate(session);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrors()).isEmpty();
            assertThat(result.getAgentErrors()).hasSize(1);
            assertThat(result.getAgentErrors().get(0).get("type")).isEqualTo("TOOL_ID_NOT_FOUND");
            assertThat(result.getAgentErrors().get(0).get("fix"))
                .isEqualTo("Use workflow(action='list_nodes') to find a valid tool");
        }

        @Test
        @DisplayName("Returns valid=true only when BOTH typed errors and legacy errors are empty")
        void validOnlyWhenBothEmpty() {
            when(workflowErrorChecker.checkForErrors(any()))
                .thenReturn(new WorkflowErrorChecker.CheckResult(List.of(), List.of(), true, "ok"));

            ValidationResult result = validator.validate(session);

            assertThat(result.isValid()).isTrue();
            assertThat(result.getErrors()).isEmpty();
            assertThat(result.getAgentErrors()).isEmpty();
        }

        @Test
        @DisplayName("Prod Gmail scenario: typed classify + legacy crud errors both surface with fix hints preserved")
        void prodGmailScenarioSurfacesBothSources() {
            // Typed: classify missing categories (from StepValidator)
            doAnswer(inv -> {
                ValidationResult r = inv.getArgument(1);
                r.addError("AGENT_MISSING_PARAM", "agent:tri",
                    "'Tri' (classify) requires 'categories'");
                return null;
            }).when(stepValidator).validate(any(), any());

            // Legacy: crud missing columns (from WorkflowErrorChecker)
            Map<String, Object> legacyErr = new LinkedHashMap<>();
            legacyErr.put("type", "CRUD_MISSING_PARAM");
            legacyErr.put("node", "table:insert_email");
            legacyErr.put("message", "'Insert email' requires 'columns'");
            legacyErr.put("fix", "workflow(action='modify', node='Insert email', params={columns: {...}})");

            when(workflowErrorChecker.checkForErrors(any()))
                .thenReturn(new WorkflowErrorChecker.CheckResult(
                    List.of(legacyErr), List.of(), false, "errors"));

            ValidationResult result = validator.validate(session);

            assertThat(result.isValid()).isFalse();
            Map<String, Object> agent = validator.toAgentFormat(result);
            List<?> errors = (List<?>) agent.get("errors");
            assertThat(errors).hasSize(2);

            // Typed error rendered as Map with type/node/message keys
            @SuppressWarnings("unchecked")
            Map<String, Object> typedAsMap = (Map<String, Object>) errors.get(0);
            assertThat(typedAsMap.get("type")).isEqualTo("AGENT_MISSING_PARAM");
            assertThat(typedAsMap.get("node")).isEqualTo("agent:tri");

            // Legacy error kept verbatim with 'fix' hint intact
            @SuppressWarnings("unchecked")
            Map<String, Object> legacyAsMap = (Map<String, Object>) errors.get(1);
            assertThat(legacyAsMap.get("type")).isEqualTo("CRUD_MISSING_PARAM");
            assertThat(legacyAsMap.get("fix")).asString().startsWith("workflow(action='modify'");

            assertThat(agent.get("can_create")).isEqualTo(false);
            assertThat(agent.get("error_count")).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("toAgentFormat()")
    class ToAgentFormatTests {

        private WorkflowBuilderValidator validator;

        @BeforeEach
        void setUp() {
            // We never call validate() in this nested class, so sub-validator mocks
            // aren't needed - passing nulls is safe for pure toAgentFormat() tests.
            validator = new WorkflowBuilderValidator(null, null, null, null, null, null, null, null);
        }

        @Test
        @DisplayName("Empty result produces 'No issues' message and can_create=true")
        void emptyResultIsClean() {
            Map<String, Object> agent = validator.toAgentFormat(ValidationResult.builder().build());

            assertThat(agent.get("can_create")).isEqualTo(true);
            assertThat(agent.get("error_count")).isEqualTo(0);
            assertThat(agent.get("warning_count")).isEqualTo(0);
            assertThat(agent.get("message")).asString().contains("No issues");
        }

        @Test
        @DisplayName("Result with warnings only is still can_create=true")
        void warningsOnlyCanCreate() {
            ValidationResult r = ValidationResult.builder().build();
            r.addWarning("AGENT_NO_CONFIG", "agent:a", "no prompt");

            Map<String, Object> agent = validator.toAgentFormat(r);

            assertThat(agent.get("can_create")).isEqualTo(true);
            assertThat(agent.get("error_count")).isEqualTo(0);
            assertThat(agent.get("warning_count")).isEqualTo(1);
            assertThat(agent.get("message")).asString().contains("warning");
            assertThat(agent.get("message")).asString().contains("can be created");
        }

        @Test
        @DisplayName("Result with typed errors has can_create=false")
        void errorsBlockCreation() {
            ValidationResult r = ValidationResult.builder().build();
            r.addError("ERR", "mcp:x", "boom");

            Map<String, Object> agent = validator.toAgentFormat(r);

            assertThat(agent.get("can_create")).isEqualTo(false);
            assertThat(agent.get("error_count")).isEqualTo(1);
            assertThat(agent.get("message")).asString().contains("must be fixed");
        }

        @Test
        @DisplayName("Legacy agentWarnings merge into warnings list with fix hint preserved")
        void legacyAgentWarningsMerge() {
            ValidationResult r = ValidationResult.builder().build();
            r.addWarning("TYPED_WARN", "core:a", "typed warn");
            Map<String, Object> legacyWarn = new LinkedHashMap<>();
            legacyWarn.put("type", "LEGACY_WARN");
            legacyWarn.put("message", "Dead end detected");
            legacyWarn.put("fix", "Connect this node or remove it");
            List<Map<String, Object>> agentWarnings = new ArrayList<>();
            agentWarnings.add(legacyWarn);
            r.setAgentWarnings(agentWarnings);

            Map<String, Object> agent = validator.toAgentFormat(r);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> warnings = (List<Map<String, Object>>) agent.get("warnings");
            assertThat(warnings).hasSize(2);
            assertThat(warnings.get(0).get("type")).isEqualTo("TYPED_WARN");
            assertThat(warnings.get(1).get("type")).isEqualTo("LEGACY_WARN");
            assertThat(warnings.get(1).get("fix")).isEqualTo("Connect this node or remove it");
            assertThat(agent.get("warning_count")).isEqualTo(2);
            // Warnings don't block: with only warnings, can_create=true
            assertThat(agent.get("can_create")).isEqualTo(true);
        }

        @Test
        @DisplayName("Typed errors and agent errors concatenate, typed first")
        void typedAndAgentErrorsMerge() {
            ValidationResult r = ValidationResult.builder().build();
            r.addError("TYPED", "trigger:a", "typed-msg");
            Map<String, Object> legacy = new LinkedHashMap<>();
            legacy.put("type", "LEGACY");
            legacy.put("message", "legacy-msg");
            List<Map<String, Object>> agentErrors = new ArrayList<>();
            agentErrors.add(legacy);
            r.setAgentErrors(agentErrors);

            Map<String, Object> agent = validator.toAgentFormat(r);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> errors = (List<Map<String, Object>>) agent.get("errors");
            assertThat(errors).hasSize(2);
            assertThat(errors.get(0).get("type")).isEqualTo("TYPED");
            assertThat(errors.get(1).get("type")).isEqualTo("LEGACY");
        }
    }
}
