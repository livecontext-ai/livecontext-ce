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

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Tests for ReferenceValidator.
 * Validates variable references in step params point to valid nodes,
 * and credential tracking.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReferenceValidator")
class ReferenceValidatorTest {

    @Mock
    private WorkflowBuilderSession session;

    private ReferenceValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ReferenceValidator();
    }

    private void stubSession(List<Map<String, Object>> triggers,
                             List<Map<String, Object>> mcps,
                             List<Map<String, Object>> cores) {
        when(session.getTriggers()).thenReturn(triggers);
        when(session.getMcps()).thenReturn(mcps);
        when(session.getCores()).thenReturn(cores);
        lenient().when(session.getInterfaces()).thenReturn(List.of());
        lenient().when(session.getTables()).thenReturn(List.of());
    }

    @Nested
    @DisplayName("Reference validation")
    class ReferenceTests {

        @Test
        @DisplayName("Should not warn for valid trigger reference")
        void shouldNotWarnForValidTriggerReference() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Process");
            step.put("params", Map.of("input", "{{trigger:start.body.name}}"));

            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(step),
                    List.of()
            );
            lenient().when(session.hasMissingCredentials()).thenReturn(false);

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getWarnings()).noneMatch(w ->
                    w.code().equals("INVALID_REFERENCE"));
        }

        @Test
        @DisplayName("Should not warn for valid mcp reference")
        void shouldNotWarnForValidMcpReference() {
            Map<String, Object> step1 = new HashMap<>();
            step1.put("label", "Fetch Data");
            step1.put("params", null);

            Map<String, Object> step2 = new HashMap<>();
            step2.put("label", "Process");
            step2.put("params", Map.of("input", "{{mcp:fetch_data.output.result}}"));

            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(step1, step2),
                    List.of()
            );
            lenient().when(session.hasMissingCredentials()).thenReturn(false);

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getWarnings()).noneMatch(w ->
                    w.code().equals("INVALID_REFERENCE"));
        }

        @Test
        @DisplayName("Should warn for reference to non-existent node")
        void shouldWarnForReferenceToNonExistentNode() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Process");
            step.put("params", Map.of("input", "{{mcp:non_existent.output.result}}"));

            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(step),
                    List.of()
            );
            lenient().when(session.hasMissingCredentials()).thenReturn(false);

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getWarnings()).anyMatch(w ->
                    w.code().equals("INVALID_REFERENCE") &&
                    w.message().contains("mcp:non_existent"));
        }

        @Test
        @DisplayName("Should warn for reference to non-existent agent node")
        void shouldWarnForReferenceToNonExistentAgentNode() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Process");
            step.put("params", Map.of("input", "{{agent:missing_agent.output.result}}"));

            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(step),
                    List.of()
            );
            lenient().when(session.hasMissingCredentials()).thenReturn(false);

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getWarnings()).anyMatch(w ->
                    w.code().equals("INVALID_REFERENCE") &&
                    w.message().contains("agent:missing_agent"));
        }

        @Test
        @DisplayName("Should not warn for reference to valid core node")
        void shouldNotWarnForReferenceToValidCoreNode() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Process");
            step.put("params", Map.of("input", "{{core:my_loop.output.item}}"));

            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(step),
                    List.of(Map.of("label", "My Loop", "type", "loop"))
            );
            lenient().when(session.hasMissingCredentials()).thenReturn(false);

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getWarnings()).noneMatch(w ->
                    w.code().equals("INVALID_REFERENCE"));
        }

        @Test
        @DisplayName("Should not warn when params are null")
        void shouldNotWarnWhenParamsNull() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Process");
            step.put("params", null);

            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(step),
                    List.of()
            );
            lenient().when(session.hasMissingCredentials()).thenReturn(false);

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getWarnings()).noneMatch(w ->
                    w.code().equals("INVALID_REFERENCE"));
        }

        @Test
        @DisplayName("Should not warn for non-string param values")
        void shouldNotWarnForNonStringParamValues() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Process");
            step.put("params", Map.of("count", 42, "enabled", true));

            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(step),
                    List.of()
            );
            lenient().when(session.hasMissingCredentials()).thenReturn(false);

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getWarnings()).noneMatch(w ->
                    w.code().equals("INVALID_REFERENCE"));
        }

        @Test
        @DisplayName("Should not warn for simple strings without references")
        void shouldNotWarnForSimpleStringsWithoutReferences() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Process");
            step.put("params", Map.of("name", "Hello World"));

            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(step),
                    List.of()
            );
            lenient().when(session.hasMissingCredentials()).thenReturn(false);

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getWarnings()).noneMatch(w ->
                    w.code().equals("INVALID_REFERENCE"));
        }

        @Test
        @DisplayName("Should validate references in nested maps")
        void shouldValidateReferencesInNestedMaps() {
            Map<String, Object> nested = new HashMap<>();
            nested.put("field", "{{mcp:missing_step.output.data}}");

            Map<String, Object> step = new HashMap<>();
            step.put("label", "Process");
            step.put("params", Map.of("body", nested));

            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(step),
                    List.of()
            );
            lenient().when(session.hasMissingCredentials()).thenReturn(false);

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getWarnings()).anyMatch(w ->
                    w.code().equals("INVALID_REFERENCE") &&
                    w.message().contains("mcp:missing_step"));
        }

        @Test
        @DisplayName("Should not flag references without dot-separated parts as node references")
        void shouldNotFlagReferencesWithoutDotSeparatedParts() {
            // References like {{some_var}} without : prefix should not trigger node validation
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Process");
            step.put("params", Map.of("input", "{{simple_var}}"));

            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(step),
                    List.of()
            );
            lenient().when(session.hasMissingCredentials()).thenReturn(false);

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            // extractNodeFromReference returns null for references without : in first part
            assertThat(result.getWarnings()).noneMatch(w ->
                    w.code().equals("INVALID_REFERENCE"));
        }

        @Test
        @DisplayName("Should handle multiple references in same string")
        void shouldHandleMultipleReferencesInSameString() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Process");
            step.put("params", Map.of("input",
                    "Hello {{trigger:start.name}}, result is {{mcp:missing.output.data}}"));

            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(step),
                    List.of()
            );
            lenient().when(session.hasMissingCredentials()).thenReturn(false);

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            // trigger:start is valid, mcp:missing is not
            assertThat(result.getWarnings()).anyMatch(w ->
                    w.code().equals("INVALID_REFERENCE") &&
                    w.message().contains("mcp:missing"));
        }

        @Test
        @DisplayName("Should validate agent node reference by agent prefix")
        void shouldValidateAgentNodeReferenceByAgentPrefix() {
            Map<String, Object> agentStep = new HashMap<>();
            agentStep.put("label", "Analyzer");
            agentStep.put("isAgent", true);
            agentStep.put("params", null);

            Map<String, Object> step = new HashMap<>();
            step.put("label", "Process");
            step.put("params", Map.of("input", "{{agent:analyzer.output.result}}"));

            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(agentStep, step),
                    List.of()
            );
            lenient().when(session.hasMissingCredentials()).thenReturn(false);

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getWarnings()).noneMatch(w ->
                    w.code().equals("INVALID_REFERENCE"));
        }

        @Test
        @DisplayName("Should handle reference with pipe default value")
        void shouldHandleReferenceWithPipeDefaultValue() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Process");
            step.put("params", Map.of("input", "{{mcp:missing.output|default_value}}"));

            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(step),
                    List.of()
            );
            lenient().when(session.hasMissingCredentials()).thenReturn(false);

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getWarnings()).anyMatch(w ->
                    w.code().equals("INVALID_REFERENCE"));
        }
    }

    @Nested
    @DisplayName("Credential validation")
    class CredentialTests {

        @Test
        @DisplayName("Should not warn when no missing credentials")
        void shouldNotWarnWhenNoMissingCredentials() {
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(),
                    List.of()
            );
            when(session.hasMissingCredentials()).thenReturn(false);

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getWarnings()).noneMatch(w ->
                    w.code().equals("MISSING_CREDENTIAL") ||
                    w.code().equals("CREDENTIALS_REQUIRED"));
        }

        @Test
        @DisplayName("Should warn for each step with missing credentials")
        void shouldWarnForEachStepWithMissingCredentials() {
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "API Call")),
                    List.of()
            );

            when(session.hasMissingCredentials()).thenReturn(true);

            Map<String, Map<String, String>> missingCreds = new LinkedHashMap<>();
            Map<String, String> credInfo = new LinkedHashMap<>();
            credInfo.put("serviceName", "GitHub");
            missingCreds.put("mcp:api_call", credInfo);
            when(session.getMissingCredentials()).thenReturn(missingCreds);
            when(session.getLogicalIdOrFail("mcp:api_call")).thenReturn("\"API Call\"");
            when(session.getMissingCredentialServices()).thenReturn(List.of("github"));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getWarnings()).anyMatch(w ->
                    w.code().equals("MISSING_CREDENTIAL") &&
                    w.nodeId().equals("mcp:api_call") &&
                    w.message().contains("GitHub"));
        }

        @Test
        @DisplayName("Should add summary warning for missing credentials")
        void shouldAddSummaryWarningForMissingCredentials() {
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "API Call")),
                    List.of()
            );

            when(session.hasMissingCredentials()).thenReturn(true);

            Map<String, Map<String, String>> missingCreds = new LinkedHashMap<>();
            Map<String, String> credInfo = new LinkedHashMap<>();
            credInfo.put("serviceName", "GitHub");
            missingCreds.put("mcp:api_call", credInfo);
            when(session.getMissingCredentials()).thenReturn(missingCreds);
            when(session.getLogicalIdOrFail("mcp:api_call")).thenReturn("\"API Call\"");
            when(session.getMissingCredentialServices()).thenReturn(List.of("github"));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getWarnings()).anyMatch(w ->
                    w.code().equals("CREDENTIALS_REQUIRED") &&
                    w.message().contains("github"));
        }

        @Test
        @DisplayName("Should not add summary when missing credential services list is empty")
        void shouldNotAddSummaryWhenServicesEmpty() {
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(),
                    List.of()
            );

            when(session.hasMissingCredentials()).thenReturn(true);
            when(session.getMissingCredentials()).thenReturn(Map.of());
            when(session.getMissingCredentialServices()).thenReturn(List.of());

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getWarnings()).noneMatch(w ->
                    w.code().equals("CREDENTIALS_REQUIRED"));
        }

        @Test
        @DisplayName("Should handle multiple steps with different missing credentials")
        void shouldHandleMultipleStepsWithDifferentMissingCredentials() {
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "GitHub Call"), Map.of("label", "Slack Notify")),
                    List.of()
            );

            when(session.hasMissingCredentials()).thenReturn(true);

            Map<String, Map<String, String>> missingCreds = new LinkedHashMap<>();
            Map<String, String> githubCred = new LinkedHashMap<>();
            githubCred.put("serviceName", "GitHub");
            missingCreds.put("mcp:github_call", githubCred);

            Map<String, String> slackCred = new LinkedHashMap<>();
            slackCred.put("serviceName", "Slack");
            missingCreds.put("mcp:slack_notify", slackCred);

            when(session.getMissingCredentials()).thenReturn(missingCreds);
            when(session.getLogicalIdOrFail("mcp:github_call")).thenReturn("\"GitHub Call\"");
            when(session.getLogicalIdOrFail("mcp:slack_notify")).thenReturn("\"Slack Notify\"");
            when(session.getMissingCredentialServices()).thenReturn(List.of("github", "slack"));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            long missingCredCount = result.getWarnings().stream()
                    .filter(w -> w.code().equals("MISSING_CREDENTIAL"))
                    .count();
            assertThat(missingCredCount).isEqualTo(2);

            assertThat(result.getWarnings()).anyMatch(w ->
                    w.code().equals("CREDENTIALS_REQUIRED") &&
                    w.message().contains("github") &&
                    w.message().contains("slack"));
        }
    }

    @Nested
    @DisplayName("Core node reference scanning")
    class CoreNodeReferenceTests {

        @Test
        @DisplayName("Should warn for invalid reference in decision condition")
        void decisionConditionWithInvalidReference() {
            Map<String, Object> decision = new HashMap<>();
            decision.put("label", "Check Status");
            decision.put("id", "core:check_status");
            decision.put("type", "decision");
            decision.put("decisionConditions", List.of(
                    Map.of("expression", "{{mcp:nonexistent.output.status}} == 'ok'", "label", "Success")
            ));

            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(),
                    List.of(decision)
            );
            lenient().when(session.hasMissingCredentials()).thenReturn(false);

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getWarnings()).anyMatch(w ->
                    w.code().equals("INVALID_REFERENCE") &&
                    w.message().contains("mcp:nonexistent"));
        }

        @Test
        @DisplayName("Should not warn for valid reference in decision condition")
        void decisionConditionWithValidReference() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Fetch");
            step.put("params", null);

            Map<String, Object> decision = new HashMap<>();
            decision.put("label", "Check");
            decision.put("id", "core:check");
            decision.put("type", "decision");
            decision.put("decisionConditions", List.of(
                    Map.of("expression", "{{mcp:fetch.output.status}} == 'ok'", "label", "OK")
            ));

            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(step),
                    List.of(decision)
            );
            lenient().when(session.hasMissingCredentials()).thenReturn(false);

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getWarnings()).noneMatch(w ->
                    w.code().equals("INVALID_REFERENCE"));
        }

        @Test
        @DisplayName("Should warn for invalid reference in split list expression")
        void splitListWithInvalidReference() {
            Map<String, Object> split = new HashMap<>();
            split.put("label", "Split Items");
            split.put("id", "core:split_items");
            split.put("type", "split");
            split.put("list", "{{mcp:missing_step.output.items}}");

            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(),
                    List.of(split)
            );
            lenient().when(session.hasMissingCredentials()).thenReturn(false);

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getWarnings()).anyMatch(w ->
                    w.code().equals("INVALID_REFERENCE") &&
                    w.message().contains("mcp:missing_step"));
        }

        @Test
        @DisplayName("Should handle SpEL-wrapped references in core nodes")
        void spelWrappedReferenceInCoreNode() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Fetch");
            step.put("params", null);

            Map<String, Object> transform = new HashMap<>();
            transform.put("label", "Transform");
            transform.put("id", "core:transform");
            transform.put("type", "set");
            transform.put("set", Map.of("assignments", List.of(
                    Map.of("key", "data", "value", "{{json(mcp:fetch.output.result)}}")
            )));

            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(step),
                    List.of(transform)
            );
            lenient().when(session.hasMissingCredentials()).thenReturn(false);

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getWarnings()).noneMatch(w ->
                    w.code().equals("INVALID_REFERENCE"));
        }

        @Test
        @DisplayName("Should not produce false positive for nested SpEL functions")
        void nestedSpelDoesNotProduceFalsePositive() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Fetch");
            step.put("params", null);

            Map<String, Object> transform = new HashMap<>();
            transform.put("label", "Concat");
            transform.put("id", "core:concat");
            transform.put("type", "set");
            transform.put("set", Map.of("assignments", List.of(
                    Map.of("key", "msg", "value", "{{concat(mcp:fetch.output.a, mcp:fetch.output.b)}}")
            )));

            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(step),
                    List.of(transform)
            );
            lenient().when(session.hasMissingCredentials()).thenReturn(false);

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getWarnings()).noneMatch(w ->
                    w.code().equals("INVALID_REFERENCE"));
        }
    }

    @Nested
    @DisplayName("Interface and table node reference scanning")
    class InterfaceTableReferenceTests {

        @Test
        @DisplayName("Should warn for invalid reference in interface action mapping")
        void interfaceActionMappingWithInvalidReference() {
            Map<String, Object> iface = new HashMap<>();
            iface.put("label", "Dashboard");
            iface.put("actionMapping", Map.of("submit", "{{mcp:nonexistent.output.url}}"));

            when(session.getTriggers()).thenReturn(List.of(Map.of("label", "Start")));
            when(session.getMcps()).thenReturn(List.of());
            when(session.getCores()).thenReturn(List.of());
            when(session.getInterfaces()).thenReturn(List.of(iface));
            lenient().when(session.getTables()).thenReturn(List.of());
            lenient().when(session.hasMissingCredentials()).thenReturn(false);

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getWarnings()).anyMatch(w ->
                    w.code().equals("INVALID_REFERENCE") &&
                    w.message().contains("mcp:nonexistent"));
        }

        @Test
        @DisplayName("Should warn for invalid reference in table params")
        void tableParamsWithInvalidReference() {
            Map<String, Object> table = new HashMap<>();
            table.put("label", "Insert Row");
            table.put("params", Map.of("name", "{{mcp:missing.output.value}}"));

            when(session.getTriggers()).thenReturn(List.of(Map.of("label", "Start")));
            when(session.getMcps()).thenReturn(List.of());
            when(session.getCores()).thenReturn(List.of());
            lenient().when(session.getInterfaces()).thenReturn(List.of());
            when(session.getTables()).thenReturn(List.of(table));
            lenient().when(session.hasMissingCredentials()).thenReturn(false);

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getWarnings()).anyMatch(w ->
                    w.code().equals("INVALID_REFERENCE") &&
                    w.message().contains("mcp:missing"));
        }
    }

    @Nested
    @DisplayName("Workflow variable references ($vars / vars:)")
    class WorkflowVariableReferenceTests {

        @Test
        @DisplayName("Should not warn for a vars: alias reference (workflow variable, not a node)")
        void shouldNotWarnForVarsColonReference() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Call API");
            step.put("params", Map.of("url", "{{vars:api.base}}"));

            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(step),
                    List.of()
            );
            lenient().when(session.hasMissingCredentials()).thenReturn(false);

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getWarnings()).noneMatch(w ->
                    w.code().equals("INVALID_REFERENCE"));
        }

        @Test
        @DisplayName("Should not warn for a $vars.x.y reference (workflow variable, not a node)")
        void shouldNotWarnForDollarVarsReference() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Call API");
            step.put("params", Map.of("url", "{{$vars.x.y}}"));

            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(step),
                    List.of()
            );
            lenient().when(session.hasMissingCredentials()).thenReturn(false);

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getWarnings()).noneMatch(w ->
                    w.code().equals("INVALID_REFERENCE"));
        }

        @Test
        @DisplayName("Should still warn for an unknown node reference alongside vars references")
        void shouldStillWarnForUnknownNodeReference() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Call API");
            step.put("params", Map.of(
                    "url", "{{vars:api.base}}",
                    "input", "{{mcp:ghost.output.x}}"));

            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(step),
                    List.of()
            );
            lenient().when(session.hasMissingCredentials()).thenReturn(false);

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getWarnings()).anyMatch(w ->
                    w.code().equals("INVALID_REFERENCE") &&
                    w.message().contains("mcp:ghost"));
            assertThat(result.getWarnings()).noneMatch(w ->
                    w.code().equals("INVALID_REFERENCE") &&
                    w.message().contains("vars"));
        }
    }

    /**
     * 2026-09-29, a Gemini chat: the validator told the agent to wrap a whole expression in one
     * {{...}}, then flagged the result it had asked for, because the node was taken to be
     * everything left of the first '.'. The agent dropped the concatenation to silence it.
     */
    @Nested
    @DisplayName("References inside a full SpEL expression")
    class FullExpressionReferenceTests {

        private ValidationResult validateTransform(String... expressions) {
            Map<String, Object> source = new HashMap<>();
            source.put("label", "Formatage Metriques");
            List<Map<String, Object>> mappings = new ArrayList<>();
            for (int i = 0; i < expressions.length; i++) {
                mappings.add(Map.of("label", "f" + i, "expression", expressions[i]));
            }
            Map<String, Object> transform = new HashMap<>();
            transform.put("label", "Synthese");
            transform.put("transform", Map.of("mappings", mappings));

            stubSession(List.of(Map.of("label", "Start")), List.of(), List.of(source, transform));
            lenient().when(session.hasMissingCredentials()).thenReturn(false);

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);
            return result;
        }

        private List<String> invalidReferenceMessages(ValidationResult result) {
            return result.getWarnings().stream()
                    .filter(w -> w.code().equals("INVALID_REFERENCE"))
                    .map(w -> w.message())
                    .toList();
        }

        @Test
        @DisplayName("A string literal concatenated before a valid reference is not flagged (the exact Gemini expressions)")
        void literalBeforeValidReferenceIsNotFlagged() {
            ValidationResult result = validateTransform(
                    "{{'Analyse : ' + core:formatage_metriques.output.theme_maj}}",
                    "{{core:formatage_metriques.output.quote_text + ' (' + core:formatage_metriques.output.author_name + ')'}}");

            assertThat(invalidReferenceMessages(result)).isEmpty();
        }

        @Test
        @DisplayName("A comparison and a ternary around valid references are not flagged")
        void ternaryAroundValidReferencesIsNotFlagged() {
            ValidationResult result = validateTransform(
                    "{{core:formatage_metriques.output.words_count > 10 ? 'Citation longue' : 'Citation concise'}}",
                    "{{int(core:formatage_metriques.output.words_count) > 10 ? trigger:start.output.a : core:formatage_metriques.output.b}}");

            assertThat(invalidReferenceMessages(result)).isEmpty();
        }

        @Test
        @DisplayName("Text shaped like a reference inside a string literal is never read as one")
        void referenceShapedLiteralIsIgnored() {
            ValidationResult result = validateTransform(
                    "{{'see core:ghost.output.x' + core:formatage_metriques.output.theme_maj}}",
                    "{{\"mcp:ghost.output.y\" + core:formatage_metriques.output.theme_maj}}");

            assertThat(invalidReferenceMessages(result)).isEmpty();
        }

        @Test
        @DisplayName("An unknown node inside a concatenation is flagged by its own id, not by the operand before it")
        void unknownNodeInsideConcatenationIsNamedExactly() {
            ValidationResult result = validateTransform(
                    "{{'Analyse : ' + core:ghost.output.theme_maj}}");

            assertThat(invalidReferenceMessages(result))
                    .singleElement()
                    .satisfies(m -> assertThat(m).contains("unknown node 'core:ghost'"));
        }

        @Test
        @DisplayName("Every reference of a multi-argument function is checked (was skipped wholesale)")
        void everyArgumentOfMultiArgFunctionIsChecked() {
            ValidationResult result = validateTransform(
                    "{{concat(core:formatage_metriques.output.a, mcp:ghost.output.b)}}");

            assertThat(invalidReferenceMessages(result))
                    .singleElement()
                    .satisfies(m -> assertThat(m).contains("unknown node 'mcp:ghost'"));
        }

        @Test
        @DisplayName("An un-normalized label is still reported as unknown")
        void unNormalizedLabelIsStillReported() {
            ValidationResult result = validateTransform(
                    "{{core:Formatage Metriques.output.theme_maj}}");

            assertThat(invalidReferenceMessages(result))
                    .singleElement()
                    .satisfies(m -> assertThat(m).contains("unknown node 'core:Formatage Metriques'"));
        }

        @Test
        @DisplayName("Two unknown nodes in one expression are both reported")
        void twoUnknownNodesAreBothReported() {
            ValidationResult result = validateTransform(
                    "{{mcp:ghost_a.output.x + ' / ' + agent:ghost_b.output.response}}");

            assertThat(invalidReferenceMessages(result)).hasSize(2)
                    .anySatisfy(m -> assertThat(m).contains("'mcp:ghost_a'"))
                    .anySatisfy(m -> assertThat(m).contains("'agent:ghost_b'"));
        }
    }
}
