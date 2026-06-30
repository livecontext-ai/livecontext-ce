package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for NodeDescriptionBuilder inner types and DescriptionResult.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NodeDescriptionBuilder")
class NodeDescriptionBuilderTest {

    @Mock
    private DataSourceClient dataSourceService;

    @Mock
    private InterfaceClient interfaceClient;

    private NodeDescriptionBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new NodeDescriptionBuilder(dataSourceService, interfaceClient);
    }

    @Nested
    @DisplayName("DescriptionResult")
    class DescriptionResultTests {

        @Test
        @DisplayName("empty should return empty collections")
        void emptyShouldReturnEmptyCollections() {
            NodeDescriptionBuilder.DescriptionResult result = NodeDescriptionBuilder.DescriptionResult.empty();

            assertThat(result.config()).isEmpty();
            assertThat(result.modifiableFields()).isEmpty();
            assertThat(result.helpTopic()).isNull();
            assertThat(result.warning()).isNull();
        }

        @Test
        @DisplayName("Should store all fields")
        void shouldStoreAllFields() {
            Map<String, Object> config = Map.of("key", "value");
            Map<String, NodeDescriptionBuilder.ModifiableField> fields = Map.of(
                "field1", new NodeDescriptionBuilder.ModifiableField("val", "key1", "desc1"));

            NodeDescriptionBuilder.DescriptionResult result =
                new NodeDescriptionBuilder.DescriptionResult(config, fields, "plan", "warning!");

            assertThat(result.config()).containsEntry("key", "value");
            assertThat(result.modifiableFields()).containsKey("field1");
            assertThat(result.helpTopic()).isEqualTo("plan");
            assertThat(result.warning()).isEqualTo("warning!");
        }
    }

    @Nested
    @DisplayName("ModifiableField")
    class ModifiableFieldTests {

        @Test
        @DisplayName("Should store all fields")
        void shouldStoreAllFields() {
            NodeDescriptionBuilder.ModifiableField field =
                new NodeDescriptionBuilder.ModifiableField("current", "param_key", "A description");

            assertThat(field.currentValue()).isEqualTo("current");
            assertThat(field.paramKey()).isEqualTo("param_key");
            assertThat(field.description()).isEqualTo("A description");
        }
    }

    @Nested
    @DisplayName("buildDescription")
    class BuildDescriptionTests {

        @Test
        @DisplayName("Should return empty for unknown prefix")
        void shouldReturnEmptyForUnknownPrefix() {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("label", "Test");

            NodeDescriptionBuilder.DescriptionResult result =
                builder.buildDescription("unknown:test", node, "test-tenant");

            assertThat(result.config()).isEmpty();
        }

        @Test
        @DisplayName("Should handle trigger prefix")
        void shouldHandleTriggerPrefix() {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("type", "webhook");
            node.put("label", "My Webhook");

            NodeDescriptionBuilder.DescriptionResult result =
                builder.buildDescription("trigger:my_webhook", node, "test-tenant");

            // Should not throw and should return a result
            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("Describe sanitization - no dashed IDs in output")
    class DescribeSanitization {

        @Test
        @DisplayName("Decision conditions: strip dashed IDs, add computed ports")
        @SuppressWarnings("unchecked")
        void shouldSanitizeDecisionConditions() {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("type", "decision");
            node.put("label", "Check Status");
            node.put("decisionConditions", List.of(
                Map.of("id", "check_status-if", "type", "if", "label", "OK", "expression", "{{x == 1}}"),
                Map.of("id", "check_status-elseif-0", "type", "elseif", "label", "Warn", "expression", "{{x == 2}}"),
                Map.of("id", "check_status-else", "type", "else", "label", "Error", "expression", "default")
            ));

            NodeDescriptionBuilder.DescriptionResult result =
                builder.buildDescription("core:check_status", node, "test-tenant");

            List<Map<String, Object>> conditions = (List<Map<String, Object>>) result.config().get("conditions");
            assertThat(conditions).hasSize(3);

            // No dashed IDs
            for (Map<String, Object> c : conditions) {
                assertThat(c).doesNotContainKey("id");
            }

            // Computed ports
            assertThat(conditions.get(0).get("port")).isEqualTo("if");
            assertThat(conditions.get(1).get("port")).isEqualTo("elseif_0");
            assertThat(conditions.get(2).get("port")).isEqualTo("else");

            // Labels preserved
            assertThat(conditions.get(0).get("label")).isEqualTo("OK");
            assertThat(conditions.get(1).get("label")).isEqualTo("Warn");
        }

        @Test
        @DisplayName("Switch cases: strip dashed IDs, add computed ports")
        @SuppressWarnings("unchecked")
        void shouldSanitizeSwitchCases() {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("type", "switch");
            node.put("label", "Route");
            node.put("switchExpression", "{{input.type}}");
            node.put("switchCases", List.of(
                Map.of("id", "core:route-case-0", "type", "case", "label", "A", "value", "a"),
                Map.of("id", "core:route-case-1", "type", "case", "label", "B", "value", "b"),
                Map.of("id", "core:route-default", "type", "default", "label", "Other")
            ));

            NodeDescriptionBuilder.DescriptionResult result =
                builder.buildDescription("core:route", node, "test-tenant");

            List<Map<String, Object>> cases = (List<Map<String, Object>>) result.config().get("cases");
            assertThat(cases).hasSize(3);

            for (Map<String, Object> c : cases) {
                assertThat(c).doesNotContainKey("id");
            }

            assertThat(cases.get(0).get("port")).isEqualTo("case_0");
            assertThat(cases.get(1).get("port")).isEqualTo("case_1");
            assertThat(cases.get(2).get("port")).isEqualTo("default");
            assertThat(cases.get(0).get("value")).isEqualTo("a");
        }

        @Test
        @DisplayName("Option choices: strip dashed IDs, add computed ports")
        @SuppressWarnings("unchecked")
        void shouldSanitizeOptionChoices() {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("type", "option");
            node.put("label", "Pick");
            node.put("optionChoices", List.of(
                Map.of("id", "core:pick-choice-0", "label", "Fast", "expression", "{{speed > 100}}"),
                Map.of("id", "core:pick-choice-1", "label", "Slow", "expression", "true")
            ));

            NodeDescriptionBuilder.DescriptionResult result =
                builder.buildDescription("core:pick", node, "test-tenant");

            List<Map<String, Object>> choices = (List<Map<String, Object>>) result.config().get("choices");
            assertThat(choices).hasSize(2);

            for (Map<String, Object> c : choices) {
                assertThat(c).doesNotContainKey("id");
            }

            assertThat(choices.get(0).get("port")).isEqualTo("choice_0");
            assertThat(choices.get(1).get("port")).isEqualTo("choice_1");
        }

        @Test
        @DisplayName("Fork branches: strip dashed IDs, add computed ports")
        @SuppressWarnings("unchecked")
        void shouldSanitizeForkBranches() {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("type", "fork");
            node.put("label", "Parallel");
            node.put("forkOutputs", List.of(
                Map.of("id", "core:parallel-output-0", "label", "Email"),
                Map.of("id", "core:parallel-output-1", "label", "SMS")
            ));

            NodeDescriptionBuilder.DescriptionResult result =
                builder.buildDescription("core:parallel", node, "test-tenant");

            List<Map<String, Object>> branches = (List<Map<String, Object>>) result.config().get("branches");
            assertThat(branches).hasSize(2);

            for (Map<String, Object> b : branches) {
                assertThat(b).doesNotContainKey("id");
            }

            assertThat(branches.get(0).get("port")).isEqualTo("branch_0");
            assertThat(branches.get(1).get("port")).isEqualTo("branch_1");
            assertThat(branches.get(0).get("label")).isEqualTo("Email");
        }

        @Test
        @DisplayName("Loop: shows ports body/exit")
        void shouldDescribeLoopWithPorts() {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("type", "loop");
            node.put("label", "Iterate");
            node.put("condition", "{{hasMore}}");
            node.put("maxIterations", 100);

            NodeDescriptionBuilder.DescriptionResult result =
                builder.buildDescription("core:iterate", node, "test-tenant");

            assertThat(result.config().get("ports")).isEqualTo(List.of("body", "exit"));
            assertThat(result.helpTopic()).isEqualTo("loop");
        }

        @Test
        @DisplayName("Approval: shows ports approved/rejected/timeout")
        void shouldDescribeApprovalWithPorts() {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("type", "approval");
            node.put("label", "Review");

            NodeDescriptionBuilder.DescriptionResult result =
                builder.buildDescription("core:review", node, "test-tenant");

            assertThat(result.config().get("ports")).isEqualTo(List.of("approved", "rejected", "timeout"));
            assertThat(result.helpTopic()).isEqualTo("approval");
        }
    }

    @Nested
    @DisplayName("Interface Description")
    class InterfaceDescriptionTests {

        @Test
        @DisplayName("should include template_variables from catalog and warn on mismatch")
        void shouldIncludeTemplateVarsAndWarnOnMismatch() {
            // Setup: interface in catalog has template vars [title, description]
            var ifaceDto = new com.apimarketplace.interfaces.client.dto.InterfaceDto();
            ifaceDto.setHtmlTemplate("<h1>{{title|Hello}}</h1><p>{{description|Info}}</p>");
            ifaceDto.setTemplateVariables(List.of("title", "description"));
            org.mockito.Mockito.when(interfaceClient.getInterface(
                java.util.UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), "test-tenant"))
                .thenReturn(ifaceDto);

            // Node has mismatched variable_mapping keys
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
            node.put("label", "Dashboard");
            node.put("variableMapping", Map.of(
                "name", "{{mcp:step.output.name}}",       // NOT in template
                "title", "{{mcp:step.output.title}}"       // matches
            ));

            NodeDescriptionBuilder.DescriptionResult result =
                builder.buildDescription("interface:dashboard", node, "test-tenant");

            // Should include template_variables
            assertThat(result.config().get("template_variables")).isEqualTo(List.of("title", "description"));

            // Should warn about mismatch
            assertThat(result.warning()).isNotNull();
            assertThat(result.warning()).contains("description");  // unmapped
            assertThat(result.warning()).contains("name");         // extra key not in template
        }

        @Test
        @DisplayName("should warn when HTML template is empty")
        void shouldWarnOnEmptyTemplate() {
            var ifaceDto = new com.apimarketplace.interfaces.client.dto.InterfaceDto();
            ifaceDto.setHtmlTemplate("");  // empty
            ifaceDto.setTemplateVariables(List.of());
            org.mockito.Mockito.when(interfaceClient.getInterface(
                java.util.UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), "test-tenant"))
                .thenReturn(ifaceDto);

            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
            node.put("label", "Empty Page");

            NodeDescriptionBuilder.DescriptionResult result =
                builder.buildDescription("interface:empty_page", node, "test-tenant");

            assertThat(result.warning()).isNotNull();
            assertThat(result.warning()).contains("EMPTY");
        }

        @Test
        @DisplayName("should have no warning when mapping matches template variables")
        void shouldHaveNoWarningWhenAligned() {
            var ifaceDto = new com.apimarketplace.interfaces.client.dto.InterfaceDto();
            ifaceDto.setHtmlTemplate("<h1>{{title|Hello}}</h1>");
            ifaceDto.setTemplateVariables(List.of("title"));
            org.mockito.Mockito.when(interfaceClient.getInterface(
                java.util.UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), "test-tenant"))
                .thenReturn(ifaceDto);

            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
            node.put("label", "Good Page");
            node.put("variableMapping", Map.of("title", "{{mcp:step.output.title}}"));

            NodeDescriptionBuilder.DescriptionResult result =
                builder.buildDescription("interface:good_page", node, "test-tenant");

            assertThat(result.warning()).isNull();
            assertThat(result.config().get("template_variables")).isEqualTo(List.of("title"));
        }

        @Test
        @DisplayName("should handle interface not found in catalog gracefully")
        void shouldHandleInterfaceNotFound() {
            org.mockito.Mockito.when(interfaceClient.getInterface(
                java.util.UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), "test-tenant"))
                .thenReturn(null);

            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
            node.put("label", "Missing");

            NodeDescriptionBuilder.DescriptionResult result =
                builder.buildDescription("interface:missing", node, "test-tenant");

            // Should not crash, no template_variables, no warning
            assertThat(result.config().get("template_variables")).isNull();
            assertThat(result.warning()).isNull();
        }
    }
}
