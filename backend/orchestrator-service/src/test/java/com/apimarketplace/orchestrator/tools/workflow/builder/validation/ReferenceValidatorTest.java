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
}
