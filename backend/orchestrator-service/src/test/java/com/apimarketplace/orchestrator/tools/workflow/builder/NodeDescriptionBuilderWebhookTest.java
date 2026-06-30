package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.datasource.client.DataSourceClient;
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
 * Tests for NodeDescriptionBuilder webhook configuration.
 *
 * Verifies that:
 * - buildTriggerDescription shows httpMethod, authType for webhooks
 * - buildModifyExamples shows webhook-specific examples
 * - Non-webhook triggers are unaffected
 * - Edge cases (missing params, null values) are handled
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NodeDescriptionBuilder - Webhook Config")
class NodeDescriptionBuilderWebhookTest {

    @Mock
    private DataSourceClient dataSourceService;

    @Mock
    private com.apimarketplace.interfaces.client.InterfaceClient interfaceClient;

    private NodeDescriptionBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new NodeDescriptionBuilder(dataSourceService, interfaceClient);
    }

    private Map<String, Object> webhookNode() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", "webhook");
        node.put("label", "My Webhook");
        return node;
    }

    private Map<String, Object> webhookNodeWithParams(String httpMethod, String authType) {
        Map<String, Object> node = webhookNode();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("httpMethod", httpMethod);
        params.put("authType", authType);
        node.put("params", params);
        return node;
    }

    // ==================== Trigger Description ====================

    @Nested
    @DisplayName("buildTriggerDescription for webhooks")
    class TriggerDescription {

        @Test
        @DisplayName("Should show httpMethod and authType from params")
        void shouldShowWebhookConfigFromParams() {
            Map<String, Object> node = webhookNodeWithParams("GET", "header");

            NodeDescriptionBuilder.DescriptionResult result =
                    builder.buildDescription("trigger:my_webhook", node, "test-tenant");

            assertThat(result.config()).containsEntry("httpMethod", "GET");
            assertThat(result.config()).containsEntry("authType", "header");
        }

        @Test
        @DisplayName("Should show defaults when params is null")
        void shouldShowDefaultsWhenNoParams() {
            Map<String, Object> node = webhookNode();
            // No params map

            NodeDescriptionBuilder.DescriptionResult result =
                    builder.buildDescription("trigger:my_webhook", node, "test-tenant");

            assertThat(result.config()).containsEntry("httpMethod", "POST");
            assertThat(result.config()).containsEntry("authType", "none");
        }

        @Test
        @DisplayName("Should include httpMethod in modifiable fields")
        void shouldIncludeHttpMethodInModifiableFields() {
            Map<String, Object> node = webhookNodeWithParams("POST", "none");

            NodeDescriptionBuilder.DescriptionResult result =
                    builder.buildDescription("trigger:my_webhook", node, "test-tenant");

            assertThat(result.modifiableFields()).containsKey("httpMethod");
            NodeDescriptionBuilder.ModifiableField field = result.modifiableFields().get("httpMethod");
            assertThat(field.currentValue()).isEqualTo("POST");
            assertThat(field.paramKey()).isEqualTo("httpMethod");
        }

        @Test
        @DisplayName("Should include authType in modifiable fields")
        void shouldIncludeAuthTypeInModifiableFields() {
            Map<String, Object> node = webhookNodeWithParams("POST", "jwt");

            NodeDescriptionBuilder.DescriptionResult result =
                    builder.buildDescription("trigger:my_webhook", node, "test-tenant");

            assertThat(result.modifiableFields()).containsKey("authType");
            NodeDescriptionBuilder.ModifiableField field = result.modifiableFields().get("authType");
            assertThat(field.currentValue()).isEqualTo("jwt");
            assertThat(field.paramKey()).isEqualTo("authType");
        }

        @Test
        @DisplayName("Should include inputSchema in modifiable fields")
        void shouldIncludeInputSchemaInModifiableFields() {
            Map<String, Object> node = webhookNodeWithParams("POST", "none");

            NodeDescriptionBuilder.DescriptionResult result =
                    builder.buildDescription("trigger:my_webhook", node, "test-tenant");

            assertThat(result.modifiableFields()).containsKey("inputSchema");
        }

        @Test
        @DisplayName("Should show inputSchema when present on node")
        void shouldShowInputSchemaWhenPresent() {
            Map<String, Object> node = webhookNodeWithParams("POST", "none");
            node.put("inputSchema", Map.of("name", "string", "amount", "number"));

            NodeDescriptionBuilder.DescriptionResult result =
                    builder.buildDescription("trigger:my_webhook", node, "test-tenant");

            assertThat(result.config()).containsKey("inputSchema");
            @SuppressWarnings("unchecked")
            Map<String, Object> schema = (Map<String, Object>) result.config().get("inputSchema");
            assertThat(schema).containsEntry("name", "string");
        }

        @Test
        @DisplayName("Should show trigger_type as webhook")
        void shouldShowTriggerType() {
            Map<String, Object> node = webhookNode();

            NodeDescriptionBuilder.DescriptionResult result =
                    builder.buildDescription("trigger:my_webhook", node, "test-tenant");

            assertThat(result.config()).containsEntry("trigger_type", "webhook");
        }

        @Test
        @DisplayName("Should still show standard trigger fields for webhooks")
        void shouldStillShowStandardTriggerFields() {
            Map<String, Object> node = webhookNodeWithParams("POST", "none");
            node.put("strategy", "all");

            NodeDescriptionBuilder.DescriptionResult result =
                    builder.buildDescription("trigger:my_webhook", node, "test-tenant");

            assertThat(result.config()).containsEntry("strategy", "all");
            assertThat(result.modifiableFields()).containsKey("strategy");
        }
    }

    // ==================== Non-webhook triggers ====================

    @Nested
    @DisplayName("Non-webhook triggers should be unaffected")
    class NonWebhookTriggers {

        @Test
        @DisplayName("Schedule trigger should not show httpMethod/authType")
        void scheduleShouldNotShowWebhookFields() {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("type", "schedule");
            node.put("label", "Daily Job");

            NodeDescriptionBuilder.DescriptionResult result =
                    builder.buildDescription("trigger:daily_job", node, "test-tenant");

            assertThat(result.config()).doesNotContainKey("httpMethod");
            assertThat(result.config()).doesNotContainKey("authType");
        }

        @Test
        @DisplayName("Manual trigger should not show httpMethod/authType")
        void manualShouldNotShowWebhookFields() {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("type", "manual");
            node.put("label", "Start");

            NodeDescriptionBuilder.DescriptionResult result =
                    builder.buildDescription("trigger:start", node, "test-tenant");

            assertThat(result.config()).doesNotContainKey("httpMethod");
            assertThat(result.config()).doesNotContainKey("authType");
        }

        @Test
        @DisplayName("Form trigger should not show httpMethod/authType")
        void formShouldNotShowWebhookFields() {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("type", "form");
            node.put("label", "Contact");

            NodeDescriptionBuilder.DescriptionResult result =
                    builder.buildDescription("trigger:contact", node, "test-tenant");

            assertThat(result.config()).doesNotContainKey("httpMethod");
            assertThat(result.config()).doesNotContainKey("authType");
        }
    }

    // ==================== Modify Examples ====================

    @Nested
    @DisplayName("buildModifyExamples for webhooks")
    class ModifyExamples {

        @Test
        @DisplayName("Webhook trigger should show change_http_method example")
        void shouldShowChangeHttpMethodExample() {
            Map<String, Object> node = webhookNode();
            WorkflowBuilderSession session = WorkflowBuilderSession.create("t", "c", "W", null);

            Map<String, String> examples = builder.buildModifyExamples(
                    "trigger:my_webhook", "My Webhook", node, session);

            assertThat(examples).containsKey("change_http_method");
            assertThat(examples.get("change_http_method")).contains("httpMethod");
            assertThat(examples.get("change_http_method")).contains("My Webhook");
        }

        @Test
        @DisplayName("Webhook trigger should show add_auth example")
        void shouldShowAddAuthExample() {
            Map<String, Object> node = webhookNode();
            WorkflowBuilderSession session = WorkflowBuilderSession.create("t", "c", "W", null);

            Map<String, String> examples = builder.buildModifyExamples(
                    "trigger:my_webhook", "My Webhook", node, session);

            assertThat(examples).containsKey("add_auth");
            assertThat(examples.get("add_auth")).contains("authType");
            assertThat(examples.get("add_auth")).contains("authHeaderName");
        }

        @Test
        @DisplayName("Webhook trigger should show set_input_schema example")
        void shouldShowSetInputSchemaExample() {
            Map<String, Object> node = webhookNode();
            WorkflowBuilderSession session = WorkflowBuilderSession.create("t", "c", "W", null);

            Map<String, String> examples = builder.buildModifyExamples(
                    "trigger:my_webhook", "My Webhook", node, session);

            assertThat(examples).containsKey("set_input_schema");
            assertThat(examples.get("set_input_schema")).contains("inputSchema");
        }

        @Test
        @DisplayName("Webhook trigger should NOT show change_schedule example")
        void webhookShouldNotShowScheduleExample() {
            Map<String, Object> node = webhookNode();
            WorkflowBuilderSession session = WorkflowBuilderSession.create("t", "c", "W", null);

            Map<String, String> examples = builder.buildModifyExamples(
                    "trigger:my_webhook", "My Webhook", node, session);

            assertThat(examples).doesNotContainKey("change_schedule");
        }

        @Test
        @DisplayName("Schedule trigger should still show change_schedule example")
        void scheduleShouldStillShowScheduleExample() {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("type", "schedule");
            node.put("label", "Daily");
            WorkflowBuilderSession session = WorkflowBuilderSession.create("t", "c", "W", null);

            Map<String, String> examples = builder.buildModifyExamples(
                    "trigger:daily", "Daily", node, session);

            assertThat(examples).containsKey("change_schedule");
            assertThat(examples).doesNotContainKey("change_http_method");
        }
    }
}
