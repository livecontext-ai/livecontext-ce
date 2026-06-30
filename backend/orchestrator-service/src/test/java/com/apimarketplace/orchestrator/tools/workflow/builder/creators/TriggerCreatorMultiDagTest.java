package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.orchestrator.tools.workflow.builder.ResponseOptimizer;
import com.apimarketplace.orchestrator.tools.workflow.builder.SmartDefaultsEngine;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSessionStore;
import com.apimarketplace.trigger.client.TriggerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Tests for TriggerCreator multi-DAG support.
 *
 * Verifies that:
 * - Multiple triggers with different labels are allowed
 * - Duplicate labels are rejected
 * - All trigger types (webhook, manual, chat, schedule, form, workflow) can coexist
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TriggerCreator - Multi-DAG Support")
class TriggerCreatorMultiDagTest {

    @Mock
    private WorkflowBuilderSessionStore sessionStore;

    @Mock
    private DataSourceClient dataSourceService;

    @Mock
    private SmartDefaultsEngine smartDefaultsEngine;

    @Mock
    private ResponseOptimizer responseOptimizer;

    @Mock
    private TriggerClient triggerClient;

    private TriggerCreator creator;

    @BeforeEach
    void setUp() {
        creator = new TriggerCreator(sessionStore, dataSourceService, smartDefaultsEngine, responseOptimizer, triggerClient);
    }

    private WorkflowBuilderSession createSession() {
        return WorkflowBuilderSession.create("tenant-1", "conv-1", "Test Workflow", null);
    }

    private Map<String, Object> triggerParams(String label, String type) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("label", label);
        params.put("trigger_type", type);
        return params;
    }

    private void stubDefaults() {
        when(smartDefaultsEngine.applyTriggerDefaults(any()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(responseOptimizer.buildTriggerResponse(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new LinkedHashMap<>());
    }

    // ==================== Multi-Trigger Allowed ====================

    @Nested
    @DisplayName("Multiple triggers with different labels")
    class MultiTriggerAllowed {

        @Test
        @DisplayName("Should allow adding two webhook triggers with different labels")
        void shouldAllowTwoWebhookTriggers() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            ToolExecutionResult first = creator.executeAddTrigger(session,
                    triggerParams("Webhook A", "webhook"), "tenant-1");
            assertThat(first.success()).isTrue();

            ToolExecutionResult second = creator.executeAddTrigger(session,
                    triggerParams("Webhook B", "webhook"), "tenant-1");
            assertThat(second.success()).isTrue();

            assertThat(session.getTriggers()).hasSize(2);
        }

        @Test
        @DisplayName("Should allow webhook + manual triggers")
        void shouldAllowWebhookAndManual() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            ToolExecutionResult webhookResult = creator.executeAddTrigger(session,
                    triggerParams("My Webhook", "webhook"), "tenant-1");
            assertThat(webhookResult.success()).isTrue();

            ToolExecutionResult manualResult = creator.executeAddTrigger(session,
                    triggerParams("Manual Start", "manual"), "tenant-1");
            assertThat(manualResult.success()).isTrue();

            assertThat(session.getTriggers()).hasSize(2);
        }

        @Test
        @DisplayName("Should allow webhook + schedule triggers")
        void shouldAllowWebhookAndSchedule() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            ToolExecutionResult webhookResult = creator.executeAddTrigger(session,
                    triggerParams("Incoming Data", "webhook"), "tenant-1");
            assertThat(webhookResult.success()).isTrue();

            Map<String, Object> scheduleParams = triggerParams("Hourly Check", "schedule");
            scheduleParams.put("schedule", "0 * * * *");
            ToolExecutionResult scheduleResult = creator.executeAddTrigger(session,
                    scheduleParams, "tenant-1");
            assertThat(scheduleResult.success()).isTrue();

            assertThat(session.getTriggers()).hasSize(2);
        }

        @Test
        @DisplayName("Should allow webhook + chat triggers")
        void shouldAllowWebhookAndChat() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            ToolExecutionResult webhookResult = creator.executeAddTrigger(session,
                    triggerParams("API Endpoint", "webhook"), "tenant-1");
            assertThat(webhookResult.success()).isTrue();

            ToolExecutionResult chatResult = creator.executeAddTrigger(session,
                    triggerParams("Chat Input", "chat"), "tenant-1");
            assertThat(chatResult.success()).isTrue();

            assertThat(session.getTriggers()).hasSize(2);
        }

        @Test
        @DisplayName("Should allow webhook + form triggers")
        void shouldAllowWebhookAndForm() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            ToolExecutionResult webhookResult = creator.executeAddTrigger(session,
                    triggerParams("Webhook Trigger", "webhook"), "tenant-1");
            assertThat(webhookResult.success()).isTrue();

            Map<String, Object> formParams = triggerParams("Contact Form", "form");
            formParams.put("fields", List.of(
                    Map.of("name", "email", "type", "email", "label", "Email")
            ));
            ToolExecutionResult formResult = creator.executeAddTrigger(session,
                    formParams, "tenant-1");
            assertThat(formResult.success()).isTrue();

            assertThat(session.getTriggers()).hasSize(2);
        }

        @Test
        @DisplayName("Should allow manual + schedule triggers")
        void shouldAllowManualAndSchedule() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            ToolExecutionResult manualResult = creator.executeAddTrigger(session,
                    triggerParams("Manual Run", "manual"), "tenant-1");
            assertThat(manualResult.success()).isTrue();

            Map<String, Object> scheduleParams = triggerParams("Daily Sync", "schedule");
            scheduleParams.put("schedule", "0 0 * * *");
            ToolExecutionResult scheduleResult = creator.executeAddTrigger(session,
                    scheduleParams, "tenant-1");
            assertThat(scheduleResult.success()).isTrue();

            assertThat(session.getTriggers()).hasSize(2);
        }

        @Test
        @DisplayName("Should allow chat + form triggers")
        void shouldAllowChatAndForm() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            ToolExecutionResult chatResult = creator.executeAddTrigger(session,
                    triggerParams("Chat Bot", "chat"), "tenant-1");
            assertThat(chatResult.success()).isTrue();

            Map<String, Object> formParams = triggerParams("Signup Form", "form");
            formParams.put("fields", List.of(
                    Map.of("name", "username", "type", "text", "label", "Username")
            ));
            ToolExecutionResult formResult = creator.executeAddTrigger(session,
                    formParams, "tenant-1");
            assertThat(formResult.success()).isTrue();

            assertThat(session.getTriggers()).hasSize(2);
        }

        @Test
        @DisplayName("Should allow three triggers of different types")
        void shouldAllowThreeTriggersOfDifferentTypes() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            assertThat(creator.executeAddTrigger(session,
                    triggerParams("Webhook Entry", "webhook"), "tenant-1").success()).isTrue();
            assertThat(creator.executeAddTrigger(session,
                    triggerParams("Manual Entry", "manual"), "tenant-1").success()).isTrue();
            assertThat(creator.executeAddTrigger(session,
                    triggerParams("Chat Entry", "chat"), "tenant-1").success()).isTrue();

            assertThat(session.getTriggers()).hasSize(3);
        }

        @Test
        @DisplayName("Should allow schedule + workflow triggers")
        void shouldAllowScheduleAndWorkflow() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> scheduleParams = triggerParams("Nightly Job", "schedule");
            scheduleParams.put("schedule", "0 0 * * *");
            assertThat(creator.executeAddTrigger(session, scheduleParams, "tenant-1").success()).isTrue();

            Map<String, Object> workflowParams = triggerParams("After Parent", "workflow");
            workflowParams.put("workflow_id", UUID.randomUUID().toString());
            assertThat(creator.executeAddTrigger(session, workflowParams, "tenant-1").success()).isTrue();

            assertThat(session.getTriggers()).hasSize(2);
        }

        @Test
        @DisplayName("Should store correct node IDs for each trigger")
        void shouldStoreCorrectNodeIds() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            creator.executeAddTrigger(session,
                    triggerParams("Webhook Alpha", "webhook"), "tenant-1");
            creator.executeAddTrigger(session,
                    triggerParams("Webhook Beta", "webhook"), "tenant-1");

            assertThat(session.getNodeSchemas()).containsKey("trigger:webhook_alpha");
            assertThat(session.getNodeSchemas()).containsKey("trigger:webhook_beta");
        }
    }

    // ==================== Duplicate Label Rejected ====================

    @Nested
    @DisplayName("Duplicate label rejection")
    class DuplicateLabelRejection {

        @Test
        @DisplayName("Should reject trigger with exact same label")
        void shouldRejectExactSameLabel() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            ToolExecutionResult first = creator.executeAddTrigger(session,
                    triggerParams("My Webhook", "webhook"), "tenant-1");
            assertThat(first.success()).isTrue();

            ToolExecutionResult duplicate = creator.executeAddTrigger(session,
                    triggerParams("My Webhook", "manual"), "tenant-1");
            assertThat(duplicate.success()).isFalse();
            assertThat(duplicate.error()).contains("already exists");
            assertThat(session.getTriggers()).hasSize(1);
        }

        @Test
        @DisplayName("Should reject trigger with label that normalizes to same key")
        void shouldRejectNormalizedDuplicateLabel() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            ToolExecutionResult first = creator.executeAddTrigger(session,
                    triggerParams("My Webhook", "webhook"), "tenant-1");
            assertThat(first.success()).isTrue();

            // "my_webhook" and "My Webhook" normalize to the same key
            ToolExecutionResult duplicate = creator.executeAddTrigger(session,
                    triggerParams("my_webhook", "manual"), "tenant-1");
            assertThat(duplicate.success()).isFalse();
            assertThat(duplicate.error()).contains("already exists");
        }

        @Test
        @DisplayName("Should reject trigger with case-different label")
        void shouldRejectCaseDifferentLabel() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            ToolExecutionResult first = creator.executeAddTrigger(session,
                    triggerParams("Start", "webhook"), "tenant-1");
            assertThat(first.success()).isTrue();

            ToolExecutionResult duplicate = creator.executeAddTrigger(session,
                    triggerParams("START", "manual"), "tenant-1");
            assertThat(duplicate.success()).isFalse();
        }

        @Test
        @DisplayName("Should include helpful message in duplicate rejection")
        void shouldIncludeHelpfulMessage() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            creator.executeAddTrigger(session, triggerParams("Start", "webhook"), "tenant-1");

            ToolExecutionResult duplicate = creator.executeAddTrigger(session,
                    triggerParams("Start", "schedule"), "tenant-1");
            assertThat(duplicate.success()).isFalse();
            assertThat(duplicate.error()).contains("unique label");
        }

        @Test
        @DisplayName("Should allow trigger after rejected duplicate with different label")
        void shouldAllowAfterRejectedDuplicate() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            creator.executeAddTrigger(session, triggerParams("Start", "webhook"), "tenant-1");

            // Rejected duplicate
            ToolExecutionResult dup = creator.executeAddTrigger(session,
                    triggerParams("Start", "manual"), "tenant-1");
            assertThat(dup.success()).isFalse();

            // Different label - should succeed
            ToolExecutionResult ok = creator.executeAddTrigger(session,
                    triggerParams("Manual Start", "manual"), "tenant-1");
            assertThat(ok.success()).isTrue();
            assertThat(session.getTriggers()).hasSize(2);
        }

        @Test
        @DisplayName("Rejects a trigger whose label collides with an existing non-trigger node (cross-prefix guard 2b)")
        void rejectsTriggerCollidingWithNonTriggerNode() {
            WorkflowBuilderSession session = createSession();
            // A non-trigger node already holds the normalized label "fetch".
            Map<String, Object> mcp = new LinkedHashMap<>();
            mcp.put("id", "mcp:fetch");
            mcp.put("label", "Fetch");
            session.getMcps().add(mcp);

            // Triggers sort FIRST in the label resolver, so a trigger "Fetch" would
            // shadow mcp:fetch - TriggerCreator step 2b must reject it.
            ToolExecutionResult r = creator.executeAddTrigger(session,
                    triggerParams("Fetch", "manual"), "tenant-1");

            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_ALREADY_EXISTS);
            assertThat(r.error()).contains("already exists as mcp");
            assertThat(session.getTriggers()).isEmpty();
        }
    }

    // ==================== All trigger types combinations ====================

    @Nested
    @DisplayName("All trigger type combinations (excluding table)")
    class AllTypeCombinations {

        @Test
        @DisplayName("Should allow all non-table trigger types in one workflow")
        void shouldAllowAllNonTableTypes() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            // Webhook
            assertThat(creator.executeAddTrigger(session,
                    triggerParams("Webhook Entry", "webhook"), "tenant-1").success()).isTrue();

            // Manual
            assertThat(creator.executeAddTrigger(session,
                    triggerParams("Manual Entry", "manual"), "tenant-1").success()).isTrue();

            // Chat
            assertThat(creator.executeAddTrigger(session,
                    triggerParams("Chat Entry", "chat"), "tenant-1").success()).isTrue();

            // Schedule
            Map<String, Object> schedParams = triggerParams("Schedule Entry", "schedule");
            schedParams.put("schedule", "*/5 * * * *");
            assertThat(creator.executeAddTrigger(session,
                    schedParams, "tenant-1").success()).isTrue();

            // Form
            Map<String, Object> formParams = triggerParams("Form Entry", "form");
            formParams.put("fields", List.of(Map.of("name", "email", "type", "email")));
            assertThat(creator.executeAddTrigger(session,
                    formParams, "tenant-1").success()).isTrue();

            // Workflow
            Map<String, Object> wfParams = triggerParams("Workflow Entry", "workflow");
            wfParams.put("workflow_id", UUID.randomUUID().toString());
            assertThat(creator.executeAddTrigger(session,
                    wfParams, "tenant-1").success()).isTrue();

            assertThat(session.getTriggers()).hasSize(6);
        }

        @Test
        @DisplayName("Should allow two form triggers with different labels")
        void shouldAllowTwoFormTriggers() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> form1 = triggerParams("Contact Form", "form");
            form1.put("fields", List.of(Map.of("name", "email", "type", "email")));
            assertThat(creator.executeAddTrigger(session, form1, "tenant-1").success()).isTrue();

            Map<String, Object> form2 = triggerParams("Feedback Form", "form");
            form2.put("fields", List.of(Map.of("name", "rating", "type", "number")));
            assertThat(creator.executeAddTrigger(session, form2, "tenant-1").success()).isTrue();

            assertThat(session.getTriggers()).hasSize(2);
        }

        @Test
        @DisplayName("Should allow two schedule triggers with different labels")
        void shouldAllowTwoScheduleTriggers() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> sched1 = triggerParams("Morning Job", "schedule");
            sched1.put("schedule", "0 8 * * *");
            assertThat(creator.executeAddTrigger(session, sched1, "tenant-1").success()).isTrue();

            Map<String, Object> sched2 = triggerParams("Evening Job", "schedule");
            sched2.put("schedule", "0 18 * * *");
            assertThat(creator.executeAddTrigger(session, sched2, "tenant-1").success()).isTrue();

            assertThat(session.getTriggers()).hasSize(2);
        }

        @Test
        @DisplayName("Should allow two chat triggers with different labels")
        void shouldAllowTwoChatTriggers() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            assertThat(creator.executeAddTrigger(session,
                    triggerParams("Support Chat", "chat"), "tenant-1").success()).isTrue();
            assertThat(creator.executeAddTrigger(session,
                    triggerParams("Sales Chat", "chat"), "tenant-1").success()).isTrue();

            assertThat(session.getTriggers()).hasSize(2);
        }

        @Test
        @DisplayName("Should allow two manual triggers with different labels")
        void shouldAllowTwoManualTriggers() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            assertThat(creator.executeAddTrigger(session,
                    triggerParams("Start Process A", "manual"), "tenant-1").success()).isTrue();
            assertThat(creator.executeAddTrigger(session,
                    triggerParams("Start Process B", "manual"), "tenant-1").success()).isTrue();

            assertThat(session.getTriggers()).hasSize(2);
        }
    }
}
