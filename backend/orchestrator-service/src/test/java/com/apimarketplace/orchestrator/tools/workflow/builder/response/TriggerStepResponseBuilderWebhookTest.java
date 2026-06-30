package com.apimarketplace.orchestrator.tools.workflow.builder.response;

import com.apimarketplace.orchestrator.tools.workflow.builder.ResponseContextBuilder;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for TriggerStepResponseBuilder webhook guidance.
 *
 * Verifies that:
 * - Webhook trigger responses include webhook guidance section
 * - Guidance includes URL generation info, default config, and modify hint
 * - Non-webhook triggers don't show webhook guidance
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TriggerStepResponseBuilder - Webhook Guidance")
class TriggerStepResponseBuilderWebhookTest {

    @Mock
    private ResponseContextBuilder contextBuilder;

    private TriggerStepResponseBuilder builder;

    private WorkflowBuilderSession session;

    @BeforeEach
    void setUp() {
        builder = new TriggerStepResponseBuilder(contextBuilder);

        session = WorkflowBuilderSession.builder()
                .sessionId("test-session")
                .tenantId("test-tenant")
                .workflowName("Test Workflow")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    // ==================== Webhook Guidance ====================

    @Nested
    @DisplayName("Webhook trigger guidance")
    class WebhookGuidance {

        @Test
        @DisplayName("Should include webhook section for webhook triggers")
        void shouldIncludeWebhookSection() {
            Map<String, Object> response = builder.buildTriggerResponse(
                    session, "trigger:my_hook", "My Hook", "webhook", null,
                    Collections.emptyMap(), Collections.emptyMap()
            );

            assertThat(response).containsKey("webhook");
        }

        @Test
        @DisplayName("Webhook section should have default_config and configure fields")
        @SuppressWarnings("unchecked")
        void shouldHaveAllWebhookFields() {
            Map<String, Object> response = builder.buildTriggerResponse(
                    session, "trigger:my_hook", "My Hook", "webhook", null,
                    Collections.emptyMap(), Collections.emptyMap()
            );

            Map<String, Object> webhook = (Map<String, Object>) response.get("webhook");
            assertThat(webhook).containsKey("default_config");
            assertThat(webhook).containsKey("configure");
        }

        @Test
        @DisplayName("URL is set by TriggerCreator after auto-creating standalone webhook, not in response builder")
        @SuppressWarnings("unchecked")
        void urlShouldNotBeInResponseBuilder() {
            Map<String, Object> response = builder.buildTriggerResponse(
                    session, "trigger:my_hook", "My Hook", "webhook", null,
                    Collections.emptyMap(), Collections.emptyMap()
            );

            Map<String, Object> webhook = (Map<String, Object>) response.get("webhook");
            // URL is set elsewhere (TriggerCreator), not in the response builder
            assertThat(webhook).doesNotContainKey("url");
        }

        @Test
        @DisplayName("Default config should show POST and none")
        @SuppressWarnings("unchecked")
        void defaultConfigShouldShowDefaults() {
            Map<String, Object> response = builder.buildTriggerResponse(
                    session, "trigger:my_hook", "My Hook", "webhook", null,
                    Collections.emptyMap(), Collections.emptyMap()
            );

            Map<String, Object> webhook = (Map<String, Object>) response.get("webhook");
            Map<String, Object> defaultConfig = (Map<String, Object>) webhook.get("default_config");
            assertThat(defaultConfig).containsEntry("httpMethod", "POST");
            assertThat(defaultConfig).containsEntry("authType", "none");
        }

        @Test
        @DisplayName("Configure hint should reference the trigger label")
        @SuppressWarnings("unchecked")
        void configureHintShouldReferenceLabel() {
            Map<String, Object> response = builder.buildTriggerResponse(
                    session, "trigger:api_endpoint", "API Endpoint", "webhook", null,
                    Collections.emptyMap(), Collections.emptyMap()
            );

            Map<String, Object> webhook = (Map<String, Object>) response.get("webhook");
            String configure = (String) webhook.get("configure");
            assertThat(configure).contains("API Endpoint");
            assertThat(configure).contains("modify");
        }

        @Test
        @DisplayName("Webhook response should still have status OK")
        void shouldHaveStatusOk() {
            Map<String, Object> response = builder.buildTriggerResponse(
                    session, "trigger:my_hook", "My Hook", "webhook", null,
                    Collections.emptyMap(), Collections.emptyMap()
            );

            assertThat(response.get("status")).isEqualTo("OK");
        }

        @Test
        @DisplayName("Webhook response should have NEXT pattern")
        void shouldHaveNextPattern() {
            Map<String, Object> response = builder.buildTriggerResponse(
                    session, "trigger:my_hook", "My Hook", "webhook", null,
                    Collections.emptyMap(), Collections.emptyMap()
            );

            assertThat(response).containsKey("NEXT");
        }

        @Test
        @DisplayName("Webhook response should show correct node_type")
        void shouldShowCorrectNodeType() {
            Map<String, Object> response = builder.buildTriggerResponse(
                    session, "trigger:my_hook", "My Hook", "webhook", null,
                    Collections.emptyMap(), Collections.emptyMap()
            );

            assertThat(response.get("node_type")).isEqualTo("webhook");
        }
    }

    // ==================== Non-webhook triggers ====================

    @Nested
    @DisplayName("Non-webhook triggers should not show webhook guidance")
    class NonWebhookTriggers {

        @Test
        @DisplayName("Table trigger should not have webhook section")
        void tableShouldNotHaveWebhookSection() {
            Map<String, Object> response = builder.buildTriggerResponse(
                    session, "trigger:orders", "Orders", "table", "42",
                    Collections.emptyMap(), Collections.emptyMap()
            );

            assertThat(response).doesNotContainKey("webhook");
        }

        @Test
        @DisplayName("Schedule trigger should not have webhook section")
        void scheduleShouldNotHaveWebhookSection() {
            Map<String, Object> response = builder.buildTriggerResponse(
                    session, "trigger:daily", "Daily", "schedule", null,
                    Collections.emptyMap(), Collections.emptyMap()
            );

            assertThat(response).doesNotContainKey("webhook");
        }

        @Test
        @DisplayName("Manual trigger should not have webhook section")
        void manualShouldNotHaveWebhookSection() {
            Map<String, Object> response = builder.buildTriggerResponse(
                    session, "trigger:start", "Start", "manual", null,
                    Collections.emptyMap(), Collections.emptyMap()
            );

            assertThat(response).doesNotContainKey("webhook");
        }

        @Test
        @DisplayName("Form trigger should not have webhook section")
        void formShouldNotHaveWebhookSection() {
            Map<String, Object> response = builder.buildTriggerResponse(
                    session, "trigger:contact", "Contact", "form", null,
                    Collections.emptyMap(), Collections.emptyMap()
            );

            assertThat(response).doesNotContainKey("webhook");
        }

        @Test
        @DisplayName("Chat trigger should not have webhook section")
        void chatShouldNotHaveWebhookSection() {
            Map<String, Object> response = builder.buildTriggerResponse(
                    session, "trigger:help", "Help", "chat", null,
                    Collections.emptyMap(), Collections.emptyMap()
            );

            assertThat(response).doesNotContainKey("webhook");
        }

        @Test
        @DisplayName("Null type trigger should not have webhook section")
        void nullTypeShouldNotHaveWebhookSection() {
            Map<String, Object> response = builder.buildTriggerResponse(
                    session, "trigger:unknown", "Unknown", null, null,
                    Collections.emptyMap(), Collections.emptyMap()
            );

            assertThat(response).doesNotContainKey("webhook");
        }
    }

    // ==================== Production-pin requirement ====================
    //
    // Regression: agents that built webhook/schedule workflows shipped them without
    // pinning, then hit "no pinned version - production trigger refused" silently.
    // The pin requirement must be surfaced at trigger-creation time, not buried in
    // workflow(action='help', topics=['pin']).

    @Nested
    @DisplayName("Production-pin requirement surfaced at trigger creation")
    class ProductionPinGuidance {

        @Test
        @DisplayName("Regression - webhook trigger response includes production.requires_pin guidance")
        @SuppressWarnings("unchecked")
        void webhookExplainsPinRequirement() {
            Map<String, Object> response = builder.buildTriggerResponse(
                    session, "trigger:my_hook", "My Hook", "webhook", null,
                    Collections.emptyMap(), Collections.emptyMap()
            );

            assertThat(response).containsKey("production");
            Map<String, Object> production = (Map<String, Object>) response.get("production");
            assertThat(production).containsKey("requires_pin");
            assertThat(production).containsKey("flow");
            assertThat(production).containsKey("see_also");
            // The flow string must name the three actions in order - these are the
            // exact MCP actions the agent has access to.
            String flow = (String) production.get("flow");
            assertThat(flow).contains("finish");
            assertThat(flow).contains("execute");
            assertThat(flow).contains("pin");
        }

        @Test
        @DisplayName("Regression - schedule trigger response includes production.requires_pin guidance")
        @SuppressWarnings("unchecked")
        void scheduleExplainsPinRequirement() {
            Map<String, Object> response = builder.buildTriggerResponse(
                    session, "trigger:daily", "Daily", "schedule", null,
                    Collections.emptyMap(), Collections.emptyMap()
            );

            assertThat(response).containsKey("production");
            Map<String, Object> production = (Map<String, Object>) response.get("production");
            assertThat((String) production.get("requires_pin")).contains("pinned version");
            assertThat((String) production.get("requires_pin")).contains("schedule skips the tick");
        }

        @Test
        @DisplayName("Regression - form trigger has its own public endpoint enforced through ProductionRunResolver, must show pin guidance")
        @SuppressWarnings("unchecked")
        void formExplainsPinRequirement() {
            Map<String, Object> response = builder.buildTriggerResponse(
                    session, "trigger:contact", "Contact", "form", null,
                    Collections.emptyMap(), Collections.emptyMap()
            );

            assertThat(response).containsKey("production");
            Map<String, Object> production = (Map<String, Object>) response.get("production");
            // Refusal detail mirrors what FormDispatchService actually returns when
            // ProductionRunResolver.resolve(...) reports isNotPinned() - the agent's
            // browser code keys off this exact "not_pinned" status string.
            assertThat((String) production.get("requires_pin")).contains("form endpoint");
            assertThat((String) production.get("requires_pin")).contains("not_pinned");
        }

        @Test
        @DisplayName("Regression - chat trigger has its own public endpoint enforced through ProductionRunResolver, must show pin guidance")
        @SuppressWarnings("unchecked")
        void chatExplainsPinRequirement() {
            Map<String, Object> response = builder.buildTriggerResponse(
                    session, "trigger:assistant", "Assistant", "chat", null,
                    Collections.emptyMap(), Collections.emptyMap()
            );

            assertThat(response).containsKey("production");
            Map<String, Object> production = (Map<String, Object>) response.get("production");
            assertThat((String) production.get("requires_pin")).contains("chat endpoint");
            assertThat((String) production.get("requires_pin")).contains("not_pinned");
        }

        @Test
        @DisplayName("Triggers without a public production endpoint (manual/table/error) do NOT show pin guidance")
        void nonFireableTriggersOmitPinGuidance() {
            // manual: fires from interface clicks within an editor session.
            // table: event-driven from real row insert/update/delete (covered by
            //        the existing 'production_event_driven' behavior block, not pin).
            // error: bootstrap-driven, only fires after a parent failure (covered by
            //        its own 'bootstrap_required' block, not pin).
            for (String type : List.of("manual", "table", "error")) {
                Map<String, Object> response = builder.buildTriggerResponse(
                        session, "trigger:t", "T", type,
                        "table".equals(type) ? "42" : null,
                        Collections.emptyMap(), Collections.emptyMap()
                );
                assertThat(response)
                    .as("type=%s should NOT include production block", type)
                    .doesNotContainKey("production");
            }
        }
    }
}
