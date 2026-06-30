package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.orchestrator.tools.workflow.builder.viewer.FlowRepresentationBuilder;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * Tests for WorkflowBuilderViewer webhook URL display.
 *
 * Verifies that:
 * - Standalone webhook URL is shown when present on the trigger node
 * - Placeholder message when no standalone URL exists
 * - Non-webhook triggers don't show webhook_url
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderViewer - Webhook URL")
class WorkflowBuilderViewerWebhookTest {

    @Mock
    private FlowRepresentationBuilder flowRepresentationBuilder;

    @Mock
    private WorkflowBuilderValidator workflowBuilderValidator;

    @Mock
    private ResponseContextBuilder responseContextBuilder;

    @Mock
    private DataSourceClient dataSourceService;

    @Mock
    private AgentWorkflowFireService agentFireService;

    @Mock
    private com.apimarketplace.interfaces.client.InterfaceClient interfaceClient;

    private NodeDescriptionBuilder nodeDescriptionBuilder;
    private WorkflowBuilderViewer viewer;

    @BeforeEach
    void setUp() {
        nodeDescriptionBuilder = new NodeDescriptionBuilder(dataSourceService, interfaceClient);
        viewer = new WorkflowBuilderViewer(
                nodeDescriptionBuilder,
                flowRepresentationBuilder,
                workflowBuilderValidator,
                responseContextBuilder,
                agentFireService
        );

        lenient().when(responseContextBuilder.getAccessibleVariables(any(), anyString()))
                .thenReturn(Collections.emptyMap());
    }

    private WorkflowBuilderSession createSession() {
        return WorkflowBuilderSession.builder()
                .sessionId("test-session")
                .tenantId("test-tenant")
                .workflowName("Test Workflow")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private void addWebhookTrigger(WorkflowBuilderSession session, String label, String standaloneUrl) {
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("label", label);
        trigger.put("id", "trigger:" + WorkflowBuilderSession.normalizeLabel(label));
        trigger.put("type", "webhook");
        trigger.put("params", Map.of("httpMethod", "POST", "authType", "none"));
        if (standaloneUrl != null) {
            trigger.put("standaloneWebhookUrl", standaloneUrl);
        }
        session.getTriggers().add(trigger);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> describeNode(WorkflowBuilderSession session, String nodeName) {
        Map<String, Object> params = Map.of("node", nodeName);
        ToolExecutionResult result = viewer.executeDescribe(session, params);
        assertThat(result.success()).isTrue();
        return (Map<String, Object>) result.data();
    }

    @Nested
    @DisplayName("Standalone webhook URL display")
    class StandaloneWebhookUrl {

        @Test
        @DisplayName("Should show standalone webhook URL when present")
        void shouldShowStandaloneUrl() {
            WorkflowBuilderSession session = createSession();
            addWebhookTrigger(session, "My Hook", "https://api.example.com/webhook/wh_abc123");

            Map<String, Object> result = describeNode(session, "My Hook");

            assertThat(result.get("webhook_url"))
                    .isEqualTo("https://api.example.com/webhook/wh_abc123");
        }

        @Test
        @DisplayName("Should show placeholder when no standalone URL")
        void shouldShowPlaceholderWhenNoUrl() {
            WorkflowBuilderSession session = createSession();
            addWebhookTrigger(session, "My Hook", null);

            Map<String, Object> result = describeNode(session, "My Hook");

            assertThat(result.get("webhook_url").toString()).contains("not yet created");
        }
    }

    @Nested
    @DisplayName("Non-webhook triggers should not show webhook_url")
    class NonWebhookTriggers {

        @Test
        @DisplayName("Manual trigger should not show webhook_url")
        void manualShouldNotShowWebhookUrl() {
            WorkflowBuilderSession session = createSession();

            Map<String, Object> trigger = new LinkedHashMap<>();
            trigger.put("label", "Start");
            trigger.put("id", "trigger:start");
            trigger.put("type", "manual");
            session.getTriggers().add(trigger);

            Map<String, Object> result = describeNode(session, "Start");

            assertThat(result).doesNotContainKey("webhook_url");
        }

        @Test
        @DisplayName("Schedule trigger should not show webhook_url")
        void scheduleShouldNotShowWebhookUrl() {
            WorkflowBuilderSession session = createSession();

            Map<String, Object> trigger = new LinkedHashMap<>();
            trigger.put("label", "Daily");
            trigger.put("id", "trigger:daily");
            trigger.put("type", "schedule");
            session.getTriggers().add(trigger);

            Map<String, Object> result = describeNode(session, "Daily");

            assertThat(result).doesNotContainKey("webhook_url");
        }

        @Test
        @DisplayName("Form trigger should not show webhook_url")
        void formShouldNotShowWebhookUrl() {
            WorkflowBuilderSession session = createSession();

            Map<String, Object> trigger = new LinkedHashMap<>();
            trigger.put("label", "Contact");
            trigger.put("id", "trigger:contact");
            trigger.put("type", "form");
            session.getTriggers().add(trigger);

            Map<String, Object> result = describeNode(session, "Contact");

            assertThat(result).doesNotContainKey("webhook_url");
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Should handle empty standalone URL as missing")
        void shouldHandleEmptyUrl() {
            WorkflowBuilderSession session = createSession();
            addWebhookTrigger(session, "My Hook", "");

            Map<String, Object> result = describeNode(session, "My Hook");

            assertThat(result.get("webhook_url").toString()).contains("not yet created");
        }

        @Test
        @DisplayName("Should handle blank standalone URL as missing")
        void shouldHandleBlankUrl() {
            WorkflowBuilderSession session = createSession();
            addWebhookTrigger(session, "My Hook", "   ");

            Map<String, Object> result = describeNode(session, "My Hook");

            assertThat(result.get("webhook_url").toString()).contains("not yet created");
        }

        @Test
        @DisplayName("Should handle multiple webhook triggers with different URLs")
        void shouldHandleMultipleWebhooks() {
            WorkflowBuilderSession session = createSession();
            addWebhookTrigger(session, "Hook A", "https://api.example.com/webhook/wh_aaa");
            addWebhookTrigger(session, "Hook B", "https://api.example.com/webhook/wh_bbb");

            Map<String, Object> resultA = describeNode(session, "Hook A");
            assertThat(resultA.get("webhook_url"))
                    .isEqualTo("https://api.example.com/webhook/wh_aaa");

            Map<String, Object> resultB = describeNode(session, "Hook B");
            assertThat(resultB.get("webhook_url"))
                    .isEqualTo("https://api.example.com/webhook/wh_bbb");
        }
    }
}
