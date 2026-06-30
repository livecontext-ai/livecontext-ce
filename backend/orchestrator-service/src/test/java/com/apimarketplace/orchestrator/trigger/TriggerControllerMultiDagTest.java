package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * Tests for TriggerController multi-DAG support.
 *
 * Verifies:
 * - findTriggerIdByType returns null for ambiguous multi-trigger (same type)
 * - Error message directs users to specific trigger endpoint
 * - Single trigger of a type still works
 * - getAvailableTriggers returns all triggers
 * - triggerSpecific works with multi-trigger workflows
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TriggerController - Multi-DAG Support")
class TriggerControllerMultiDagTest {

    @Mock
    private WorkflowRunRepository runRepository;

    @Mock
    private ReusableTriggerService triggerService;

    @Mock
    private WorkflowResumeService resumeService;

    @Mock
    private com.apimarketplace.common.credit.CreditConsumptionClient creditClient;

    private TriggerController controller;

    @BeforeEach
    void setUp() {
        lenient().when(creditClient.checkCredits(any())).thenReturn(true);
        controller = new TriggerController(runRepository, triggerService, resumeService, creditClient);
    }

    // ==================== Helper Methods ====================

    /**
     * Build a plan map with multiple triggers.
     */
    private Map<String, Object> buildPlan(List<Map<String, Object>> triggers) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", triggers);
        plan.put("mcps", List.of());
        plan.put("edges", List.of());
        return plan;
    }

    private Map<String, Object> triggerMap(String id, String label, String type) {
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("id", id);
        trigger.put("label", label);
        trigger.put("type", type);
        return trigger;
    }

    private WorkflowRunEntity mockRun(String runId, RunStatus status, Map<String, Object> plan) {
        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        lenient().when(run.getRunIdPublic()).thenReturn(runId);
        lenient().when(run.getStatus()).thenReturn(status);
        lenient().when(run.getPlan()).thenReturn(plan);
        return run;
    }

    // ==================== Type-based endpoints with multi-trigger ====================

    @Nested
    @DisplayName("Type-based trigger endpoints with multiple triggers of same type")
    class TypeBasedMultiTrigger {

        @Test
        @DisplayName("Should return error with helpful message when two webhook triggers exist")
        void shouldReturnErrorForTwoWebhooks() {
            Map<String, Object> plan = buildPlan(List.of(
                    triggerMap("wh1", "Webhook A", "webhook"),
                    triggerMap("wh2", "Webhook B", "webhook")
            ));
            WorkflowRunEntity run = mockRun("run-1", RunStatus.WAITING_TRIGGER, plan);
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            ResponseEntity<TriggerController.TriggerResponse> response =
                    controller.triggerManual("run-1", null, null);

            // Manual type not found (webhooks exist but not manual)
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message()).contains("No manual trigger found");
        }

        @Test
        @DisplayName("Should return ambiguity error for two manual triggers via manual endpoint")
        void shouldReturnAmbiguityErrorForTwoManualTriggers() {
            Map<String, Object> plan = buildPlan(List.of(
                    triggerMap("m1", "Manual A", "manual"),
                    triggerMap("m2", "Manual B", "manual")
            ));
            WorkflowRunEntity run = mockRun("run-1", RunStatus.WAITING_TRIGGER, plan);
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            ResponseEntity<TriggerController.TriggerResponse> response =
                    controller.triggerManual("run-1", null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message()).contains("Multiple");
            assertThat(response.getBody().message()).contains("trigger:manual_a");
            assertThat(response.getBody().message()).contains("trigger:manual_b");
            assertThat(response.getBody().message()).contains("/{triggerId}");
        }

        @Test
        @DisplayName("Should return ambiguity error for two chat triggers via chat endpoint")
        void shouldReturnAmbiguityErrorForTwoChatTriggers() {
            Map<String, Object> plan = buildPlan(List.of(
                    triggerMap("c1", "Support Chat", "chat"),
                    triggerMap("c2", "Sales Chat", "chat")
            ));
            WorkflowRunEntity run = mockRun("run-1", RunStatus.WAITING_TRIGGER, plan);
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            ResponseEntity<TriggerController.TriggerResponse> response =
                    controller.triggerChat("run-1", Map.of("message", "hello"), null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message()).contains("Multiple");
            assertThat(response.getBody().message()).contains("/{triggerId}");
        }

        @Test
        @DisplayName("Should return ambiguity error for two form triggers via form endpoint")
        void shouldReturnAmbiguityErrorForTwoFormTriggers() {
            Map<String, Object> plan = buildPlan(List.of(
                    triggerMap("f1", "Contact Form", "form"),
                    triggerMap("f2", "Feedback Form", "form")
            ));
            WorkflowRunEntity run = mockRun("run-1", RunStatus.WAITING_TRIGGER, plan);
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            ResponseEntity<TriggerController.TriggerResponse> response =
                    controller.triggerForm("run-1", Map.of("email", "test@test.com"), null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message()).contains("Multiple");
        }

        @Test
        @DisplayName("Should succeed with single trigger of requested type")
        void shouldSucceedWithSingleTriggerOfType() {
            Map<String, Object> plan = buildPlan(List.of(
                    triggerMap("wh1", "My Webhook", "webhook"),
                    triggerMap("m1", "My Manual", "manual")
            ));
            WorkflowRunEntity run = mockRun("run-1", RunStatus.WAITING_TRIGGER, plan);
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            // Mock successful execution
            when(triggerService.executeTrigger(any(), eq("trigger:my_manual"), eq(TriggerType.MANUAL), any()))
                    .thenReturn(TriggerExecutionResult.success("run-1", "trigger:my_manual",
                            TriggerType.MANUAL, "OK", Set.of(), 1));

            ResponseEntity<TriggerController.TriggerResponse> response =
                    controller.triggerManual("run-1", null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().triggerId()).isEqualTo("trigger:my_manual");
        }
    }

    // ==================== Specific trigger endpoint ====================

    @Nested
    @DisplayName("Specific trigger endpoint for multi-DAG")
    class SpecificTriggerEndpoint {

        @Test
        @DisplayName("Should execute specific trigger by ID in multi-trigger workflow")
        void shouldExecuteSpecificTriggerById() {
            Map<String, Object> plan = buildPlan(List.of(
                    triggerMap("wh1", "Webhook A", "webhook"),
                    triggerMap("wh2", "Webhook B", "webhook")
            ));
            WorkflowRunEntity run = mockRun("run-1", RunStatus.WAITING_TRIGGER, plan);
            when(run.getExecutionMode()).thenReturn(ExecutionMode.AUTOMATIC);
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            // AUTOMATIC mode → controller dispatches via executeTriggerAsync
            when(triggerService.executeTriggerAsync(any(), eq("trigger:webhook_b"), eq(TriggerType.WEBHOOK), any()))
                    .thenReturn(TriggerExecutionResult.accepted("run-1", "trigger:webhook_b", TriggerType.WEBHOOK));

            ResponseEntity<TriggerController.TriggerResponse> response =
                    controller.triggerSpecific("run-1", "webhook", "trigger:webhook_b", Map.of(), null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().triggerId()).isEqualTo("trigger:webhook_b");
        }

        @Test
        @DisplayName("Should reject specific trigger with invalid triggerId")
        void shouldRejectInvalidTriggerId() {
            Map<String, Object> plan = buildPlan(List.of(
                    triggerMap("wh1", "Webhook A", "webhook")
            ));
            WorkflowRunEntity run = mockRun("run-1", RunStatus.WAITING_TRIGGER, plan);
            when(run.getExecutionMode()).thenReturn(ExecutionMode.AUTOMATIC);
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            ResponseEntity<TriggerController.TriggerResponse> response =
                    controller.triggerSpecific("run-1", "webhook", "trigger:nonexistent", null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message()).contains("not found");
        }

        @Test
        @DisplayName("Should work with specific manual trigger in multi-manual workflow")
        void shouldWorkWithSpecificManualTrigger() {
            Map<String, Object> plan = buildPlan(List.of(
                    triggerMap("m1", "Start Process A", "manual"),
                    triggerMap("m2", "Start Process B", "manual")
            ));
            WorkflowRunEntity run = mockRun("run-1", RunStatus.WAITING_TRIGGER, plan);
            when(run.getExecutionMode()).thenReturn(ExecutionMode.AUTOMATIC);
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            // AUTOMATIC mode → controller dispatches via executeTriggerAsync
            when(triggerService.executeTriggerAsync(any(), eq("trigger:start_process_a"), eq(TriggerType.MANUAL), any()))
                    .thenReturn(TriggerExecutionResult.accepted("run-1", "trigger:start_process_a", TriggerType.MANUAL));

            ResponseEntity<TriggerController.TriggerResponse> response =
                    controller.triggerSpecific("run-1", "manual", "trigger:start_process_a", Map.of(), null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            verify(triggerService).executeTriggerAsync(any(), eq("trigger:start_process_a"), eq(TriggerType.MANUAL), any());
        }

        @Test
        @DisplayName("Should work with specific chat trigger in multi-chat workflow")
        void shouldWorkWithSpecificChatTrigger() {
            Map<String, Object> plan = buildPlan(List.of(
                    triggerMap("c1", "Support Chat", "chat"),
                    triggerMap("c2", "Sales Chat", "chat")
            ));
            WorkflowRunEntity run = mockRun("run-1", RunStatus.WAITING_TRIGGER, plan);
            when(run.getExecutionMode()).thenReturn(ExecutionMode.AUTOMATIC);
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            // AUTOMATIC mode → controller dispatches via executeTriggerAsync
            when(triggerService.executeTriggerAsync(any(), eq("trigger:sales_chat"), eq(TriggerType.CHAT), any()))
                    .thenReturn(TriggerExecutionResult.accepted("run-1", "trigger:sales_chat", TriggerType.CHAT));

            ResponseEntity<TriggerController.TriggerResponse> response =
                    controller.triggerSpecific("run-1", "chat", "trigger:sales_chat",
                            Map.of("message", "I want to buy"), null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }

        @Test
        @DisplayName("Should work with specific form trigger in multi-form workflow")
        void shouldWorkWithSpecificFormTrigger() {
            Map<String, Object> plan = buildPlan(List.of(
                    triggerMap("f1", "Contact Form", "form"),
                    triggerMap("f2", "Feedback Form", "form")
            ));
            WorkflowRunEntity run = mockRun("run-1", RunStatus.WAITING_TRIGGER, plan);
            when(run.getExecutionMode()).thenReturn(ExecutionMode.AUTOMATIC);
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            // AUTOMATIC mode → controller dispatches via executeTriggerAsync
            when(triggerService.executeTriggerAsync(any(), eq("trigger:feedback_form"), eq(TriggerType.FORM), any()))
                    .thenReturn(TriggerExecutionResult.accepted("run-1", "trigger:feedback_form", TriggerType.FORM));

            ResponseEntity<TriggerController.TriggerResponse> response =
                    controller.triggerSpecific("run-1", "form", "trigger:feedback_form",
                            Map.of("rating", 5, "comment", "Great!"), null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }
    }

    // ==================== Get available triggers ====================

    @Nested
    @DisplayName("getAvailableTriggers with multi-DAG")
    class GetAvailableTriggersMultiDag {

        @Test
        @DisplayName("Should return all triggers for multi-trigger workflow")
        void shouldReturnAllTriggers() {
            Map<String, Object> plan = buildPlan(List.of(
                    triggerMap("wh1", "Webhook A", "webhook"),
                    triggerMap("m1", "Manual Start", "manual"),
                    triggerMap("c1", "Chat Input", "chat")
            ));
            WorkflowRunEntity run = mockRun("run-1", RunStatus.WAITING_TRIGGER, plan);
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            ResponseEntity<List<TriggerInfo>> response = controller.getAvailableTriggers("run-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody()).hasSize(3);

            List<String> types = response.getBody().stream().map(TriggerInfo::type).toList();
            assertThat(types).containsExactlyInAnyOrder("webhook", "manual", "chat");
        }

        @Test
        @DisplayName("Should return two triggers of same type")
        void shouldReturnTwoTriggersOfSameType() {
            Map<String, Object> plan = buildPlan(List.of(
                    triggerMap("wh1", "Webhook Alpha", "webhook"),
                    triggerMap("wh2", "Webhook Beta", "webhook")
            ));
            WorkflowRunEntity run = mockRun("run-1", RunStatus.WAITING_TRIGGER, plan);
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            ResponseEntity<List<TriggerInfo>> response = controller.getAvailableTriggers("run-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).hasSize(2);
            assertThat(response.getBody()).allMatch(t -> "webhook".equals(t.type()));

            List<String> triggerIds = response.getBody().stream().map(TriggerInfo::triggerId).toList();
            assertThat(triggerIds).containsExactlyInAnyOrder("trigger:webhook_alpha", "trigger:webhook_beta");
        }

        @Test
        @DisplayName("Should return all trigger types including schedule and form")
        void shouldReturnAllTypesIncludingScheduleAndForm() {
            Map<String, Object> plan = buildPlan(List.of(
                    triggerMap("wh1", "API Webhook", "webhook"),
                    triggerMap("s1", "Daily Sync", "schedule"),
                    triggerMap("f1", "User Form", "form"),
                    triggerMap("m1", "Manual Run", "manual")
            ));
            WorkflowRunEntity run = mockRun("run-1", RunStatus.WAITING_TRIGGER, plan);
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            ResponseEntity<List<TriggerInfo>> response = controller.getAvailableTriggers("run-1");

            assertThat(response.getBody()).hasSize(4);
            List<String> types = response.getBody().stream().map(TriggerInfo::type).toList();
            assertThat(types).containsExactlyInAnyOrder("webhook", "schedule", "form", "manual");
        }
    }

    // ==================== Mixed trigger type combos ====================

    @Nested
    @DisplayName("Mixed trigger type combinations")
    class MixedTypeCombinations {

        @Test
        @DisplayName("Should resolve single webhook when manual + webhook exist")
        void shouldResolveSingleWebhookWithManualAndWebhook() {
            Map<String, Object> plan = buildPlan(List.of(
                    triggerMap("wh1", "API Endpoint", "webhook"),
                    triggerMap("m1", "Manual Trigger", "manual")
            ));
            WorkflowRunEntity run = mockRun("run-1", RunStatus.WAITING_TRIGGER, plan);
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            when(triggerService.executeTrigger(any(), eq("trigger:manual_trigger"), eq(TriggerType.MANUAL), any()))
                    .thenReturn(TriggerExecutionResult.success("run-1", "trigger:manual_trigger",
                            TriggerType.MANUAL, "OK", Set.of(), 1));

            // Manual endpoint should find the single manual trigger
            ResponseEntity<TriggerController.TriggerResponse> response =
                    controller.triggerManual("run-1", null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }

        @Test
        @DisplayName("Should resolve single form when webhook + form + schedule exist")
        void shouldResolveSingleFormInMixedWorkflow() {
            Map<String, Object> plan = buildPlan(List.of(
                    triggerMap("wh1", "Webhook", "webhook"),
                    triggerMap("f1", "Contact Form", "form"),
                    triggerMap("s1", "Hourly Check", "schedule")
            ));
            WorkflowRunEntity run = mockRun("run-1", RunStatus.WAITING_TRIGGER, plan);
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            when(triggerService.executeTrigger(any(), eq("trigger:contact_form"), eq(TriggerType.FORM), any()))
                    .thenReturn(TriggerExecutionResult.success("run-1", "trigger:contact_form",
                            TriggerType.FORM, "OK", Set.of(), 1));

            ResponseEntity<TriggerController.TriggerResponse> response =
                    controller.triggerForm("run-1", Map.of("email", "test@test.com"), null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getBody().triggerId()).isEqualTo("trigger:contact_form");
        }

        @Test
        @DisplayName("Should return 400 for type that does not exist in multi-trigger workflow")
        void shouldReturn400ForMissingType() {
            Map<String, Object> plan = buildPlan(List.of(
                    triggerMap("wh1", "Webhook A", "webhook"),
                    triggerMap("wh2", "Webhook B", "webhook")
            ));
            WorkflowRunEntity run = mockRun("run-1", RunStatus.WAITING_TRIGGER, plan);
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            // Try chat endpoint - no chat trigger exists
            ResponseEntity<TriggerController.TriggerResponse> response =
                    controller.triggerChat("run-1", Map.of("message", "hello"), null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().message()).contains("No chat trigger found");
        }
    }
}
