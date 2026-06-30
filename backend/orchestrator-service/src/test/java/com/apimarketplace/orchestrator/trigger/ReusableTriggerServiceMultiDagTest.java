package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.credit.CreditBudgetService;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for ReusableTriggerService multi-DAG support.
 *
 * Verifies:
 * - resetForNextCycle sets WAITING_TRIGGER (not COMPLETED/FAILED)
 * - resetForNextCycle stores cycle result in metadata
 * - resetForNextCycle clears endedAt
 * - streaming event sends WAITING_TRIGGER with cycle result in message
 * - findTriggerIdByType with single and multiple triggers of same type
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReusableTriggerService - Multi-DAG Support")
class ReusableTriggerServiceMultiDagTest {

    @Mock
    private WorkflowRunRepository runRepository;

    @Mock
    private TriggerEpochManager epochManager;

    @Mock
    private WorkflowStreamingService streamingService;

    @Mock
    private WorkflowExecutionService executionService;

    @Mock
    private com.apimarketplace.orchestrator.services.TriggerResolverService triggerResolverService;

    @Mock
    private com.apimarketplace.orchestrator.services.state.StateSnapshotService stateSnapshotService;

    @Mock
    private EpochConcurrencyLimiter epochConcurrencyLimiter;

    @Mock
    private com.apimarketplace.orchestrator.trigger.queue.ExecutionQueue executionQueueService;

    @Mock
    private CreditConsumptionClient creditClient;

    @Mock
    private CreditBudgetService creditBudgetService;

    private ReusableTriggerService service;

    @BeforeEach
    void setUp() {
        service = new ReusableTriggerService(
                runRepository, mock(com.apimarketplace.orchestrator.repository.WorkflowRepository.class),
                mock(com.apimarketplace.orchestrator.repository.WorkflowPlanVersionRepository.class),
                epochManager, streamingService,
                executionService, triggerResolverService, stateSnapshotService,
                epochConcurrencyLimiter, executionQueueService, creditClient, creditBudgetService);
    }

    // ==================== Helper Methods ====================

    private WorkflowPlan buildPlan(List<Trigger> triggers) {
        return new WorkflowPlan(null, null, triggers, List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), Map.of());
    }

    private WorkflowExecution buildExecution(String runId, WorkflowPlan plan) {
        return new WorkflowExecution(runId, plan, Map.of());
    }

    private WorkflowRunEntity createRun(String runId, RunStatus status) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        ReflectionTestUtils.setField(run, "runIdPublic", runId);
        run.setStatus(status);
        run.setUpdatedAt(Instant.now());
        return run;
    }

    // ==================== resetForNextCycle ====================

    @Nested
    @DisplayName("resetForNextCycle - status and metadata")
    class ResetForNextCycleTests {

        @Test
        @DisplayName("Should set status to WAITING_TRIGGER after successful cycle")
        void shouldSetWaitingTriggerOnSuccess() {
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING);
            Trigger trigger = new Trigger("wh1", "My Webhook", "receive_one", "webhook");
            WorkflowPlan plan = buildPlan(List.of(trigger));
            WorkflowExecution execution = buildExecution("run-1", plan);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
            when(epochManager.getCurrentEpoch(any(WorkflowRunEntity.class), eq("trigger:my_webhook"))).thenReturn(2);

            int epoch = service.resetForNextCycle(run, execution, plan, "run-1",
                    TriggerType.MANUAL, "trigger:my_webhook", false, -1);

            // Verify status is WAITING_TRIGGER
            ArgumentCaptor<WorkflowRunEntity> captor = ArgumentCaptor.forClass(WorkflowRunEntity.class);
            verify(runRepository).save(captor.capture());
            WorkflowRunEntity saved = captor.getValue();

            assertThat(saved.getStatus()).isEqualTo(RunStatus.WAITING_TRIGGER);
        }

        @Test
        @DisplayName("Already-reset parallel epoch returns the caller epoch instead of the latest run epoch")
        void alreadyResetParallelEpochReturnsExplicitEpoch() {
            WorkflowRunEntity run = createRun("run-1", RunStatus.WAITING_TRIGGER);
            Trigger trigger = new Trigger("child", "Child Start", "receive_one", "manual");
            WorkflowPlan plan = buildPlan(List.of(trigger));
            WorkflowExecution execution = buildExecution("run-1", plan);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));

            int epoch = service.resetForNextCycle(run, execution, plan, "run-1",
                    TriggerType.MANUAL, "trigger:child_start", false, 7);

            assertThat(epoch).isEqualTo(7);
            verify(epochManager, never()).getCurrentEpoch(any(WorkflowRunEntity.class), anyString());
            verify(runRepository, never()).save(any(WorkflowRunEntity.class));
        }

        @Test
        @DisplayName("Should set status to WAITING_TRIGGER after failed cycle")
        void shouldSetWaitingTriggerOnFailure() {
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING);
            Trigger trigger = new Trigger("wh1", "My Webhook", "receive_one", "webhook");
            WorkflowPlan plan = buildPlan(List.of(trigger));
            WorkflowExecution execution = buildExecution("run-1", plan);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
            when(epochManager.getCurrentEpoch(any(WorkflowRunEntity.class), eq("trigger:my_webhook"))).thenReturn(2);

            int epoch = service.resetForNextCycle(run, execution, plan, "run-1",
                    TriggerType.MANUAL, "trigger:my_webhook", true, -1);

            ArgumentCaptor<WorkflowRunEntity> captor = ArgumentCaptor.forClass(WorkflowRunEntity.class);
            verify(runRepository).save(captor.capture());
            WorkflowRunEntity saved = captor.getValue();

            assertThat(saved.getStatus()).isEqualTo(RunStatus.WAITING_TRIGGER);
        }

        @Test
        @DisplayName("Should clear endedAt on reset")
        void shouldClearEndedAt() {
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING);
            run.setEndedAt(Instant.now()); // Simulate previous end
            Trigger trigger = new Trigger("wh1", "Webhook", "receive_one", "webhook");
            WorkflowPlan plan = buildPlan(List.of(trigger));
            WorkflowExecution execution = buildExecution("run-1", plan);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
            when(epochManager.getCurrentEpoch(any(WorkflowRunEntity.class), eq("trigger:webhook"))).thenReturn(2);

            service.resetForNextCycle(run, execution, plan, "run-1",
                    TriggerType.MANUAL, "trigger:webhook", false, -1);

            ArgumentCaptor<WorkflowRunEntity> captor = ArgumentCaptor.forClass(WorkflowRunEntity.class);
            verify(runRepository).save(captor.capture());

            assertThat(captor.getValue().getEndedAt()).isNull();
        }

        @Test
        @DisplayName("Should store lastCycleResult=COMPLETED in metadata on success")
        void shouldStoreCompletedCycleResult() {
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING);
            Trigger trigger = new Trigger("wh1", "Webhook", "receive_one", "webhook");
            WorkflowPlan plan = buildPlan(List.of(trigger));
            WorkflowExecution execution = buildExecution("run-1", plan);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
            when(epochManager.getCurrentEpoch(any(WorkflowRunEntity.class), eq("trigger:webhook"))).thenReturn(3);

            service.resetForNextCycle(run, execution, plan, "run-1",
                    TriggerType.MANUAL, "trigger:webhook", false, -1);

            ArgumentCaptor<WorkflowRunEntity> captor = ArgumentCaptor.forClass(WorkflowRunEntity.class);
            verify(runRepository).save(captor.capture());

            Map<String, Object> metadata = captor.getValue().getMetadata();
            assertThat(metadata).isNotNull();
            assertThat(metadata).containsEntry("lastCycleResult", "completed");
            assertThat(metadata).containsKey("lastCycleEpoch");
            assertThat(metadata).containsKey("lastCycleAt");
        }

        @Test
        @DisplayName("Should store lastCycleResult=FAILED in metadata on failure")
        void shouldStoreFailedCycleResult() {
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING);
            Trigger trigger = new Trigger("wh1", "Webhook", "receive_one", "webhook");
            WorkflowPlan plan = buildPlan(List.of(trigger));
            WorkflowExecution execution = buildExecution("run-1", plan);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
            when(epochManager.getCurrentEpoch(any(WorkflowRunEntity.class), eq("trigger:webhook"))).thenReturn(2);

            service.resetForNextCycle(run, execution, plan, "run-1",
                    TriggerType.MANUAL, "trigger:webhook", true, -1);

            ArgumentCaptor<WorkflowRunEntity> captor = ArgumentCaptor.forClass(WorkflowRunEntity.class);
            verify(runRepository).save(captor.capture());

            Map<String, Object> metadata = captor.getValue().getMetadata();
            assertThat(metadata).containsEntry("lastCycleResult", "failed");
        }

        @Test
        @DisplayName("Should preserve existing metadata and add cycle result")
        void shouldPreserveExistingMetadata() {
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING);
            Map<String, Object> existingMeta = new HashMap<>();
            existingMeta.put("customKey", "customValue");
            run.setMetadata(existingMeta);

            Trigger trigger = new Trigger("wh1", "Webhook", "receive_one", "webhook");
            WorkflowPlan plan = buildPlan(List.of(trigger));
            WorkflowExecution execution = buildExecution("run-1", plan);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
            when(epochManager.getCurrentEpoch(any(WorkflowRunEntity.class), eq("trigger:webhook"))).thenReturn(2);

            service.resetForNextCycle(run, execution, plan, "run-1",
                    TriggerType.MANUAL, "trigger:webhook", false, -1);

            ArgumentCaptor<WorkflowRunEntity> captor = ArgumentCaptor.forClass(WorkflowRunEntity.class);
            verify(runRepository).save(captor.capture());

            Map<String, Object> metadata = captor.getValue().getMetadata();
            assertThat(metadata).containsEntry("customKey", "customValue");
            assertThat(metadata).containsEntry("lastCycleResult", "completed");
        }

        @Test
        @DisplayName("Should store lastCycleEpoch matching the new epoch")
        void shouldStoreCorrectEpoch() {
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING);
            Trigger trigger = new Trigger("wh1", "Webhook", "receive_one", "webhook");
            WorkflowPlan plan = buildPlan(List.of(trigger));
            WorkflowExecution execution = buildExecution("run-1", plan);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
            when(epochManager.getCurrentEpoch(any(WorkflowRunEntity.class), eq("trigger:webhook"))).thenReturn(5);

            service.resetForNextCycle(run, execution, plan, "run-1",
                    TriggerType.MANUAL, "trigger:webhook", false, -1);

            ArgumentCaptor<WorkflowRunEntity> captor = ArgumentCaptor.forClass(WorkflowRunEntity.class);
            verify(runRepository).save(captor.capture());

            Map<String, Object> metadata = captor.getValue().getMetadata();
            assertThat(metadata).containsEntry("lastCycleEpoch", 5);
        }

        @Test
        @DisplayName("Should send streaming event with WAITING_TRIGGER status for non-webhook trigger")
        void shouldSendWaitingTriggerSseEvent() {
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING);
            Trigger trigger = new Trigger("m1", "Manual Start", "receive_one", "manual");
            WorkflowPlan plan = buildPlan(List.of(trigger));
            WorkflowExecution execution = buildExecution("run-1", plan);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
            when(epochManager.getCurrentEpoch(any(WorkflowRunEntity.class), eq("trigger:manual_start"))).thenReturn(2);

            service.resetForNextCycle(run, execution, plan, "run-1",
                    TriggerType.MANUAL, "trigger:manual_start", false, -1);

            verify(streamingService).sendWorkflowStatusEvent(
                    eq(execution),
                    eq(RunStatus.WAITING_TRIGGER),
                    contains("completed"));
        }

        @Test
        @DisplayName("Should send streaming event with failed cycle result in message")
        void shouldSendFailedCycleResultInSse() {
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING);
            Trigger trigger = new Trigger("c1", "Chat Input", "receive_one", "chat");
            WorkflowPlan plan = buildPlan(List.of(trigger));
            WorkflowExecution execution = buildExecution("run-1", plan);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
            when(epochManager.getCurrentEpoch(any(WorkflowRunEntity.class), eq("trigger:chat_input"))).thenReturn(3);

            service.resetForNextCycle(run, execution, plan, "run-1",
                    TriggerType.CHAT, "trigger:chat_input", true, -1);

            verify(streamingService).sendWorkflowStatusEvent(
                    eq(execution),
                    eq(RunStatus.WAITING_TRIGGER),
                    contains("failed"));
        }

        @Test
        @DisplayName("Should send streaming event for webhook trigger type (parallel epoch support)")
        void shouldSendSseForWebhook() {
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING);
            Trigger trigger = new Trigger("wh1", "Webhook", "receive_one", "webhook");
            WorkflowPlan plan = buildPlan(List.of(trigger));
            WorkflowExecution execution = buildExecution("run-1", plan);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
            when(epochManager.getCurrentEpoch(any(WorkflowRunEntity.class), eq("trigger:webhook"))).thenReturn(2);

            service.resetForNextCycle(run, execution, plan, "run-1",
                    TriggerType.WEBHOOK, "trigger:webhook", false, -1);

            // In parallel epoch model, status events are sent for all trigger types
            // to notify frontend about cycle completion
            verify(streamingService).sendWorkflowStatusEvent(
                    eq(execution), eq(RunStatus.WAITING_TRIGGER), anyString());
        }

        @Test
        @DisplayName("Should skip reset when already WAITING_TRIGGER (concurrent protection)")
        void shouldSkipResetWhenAlreadyWaitingTrigger() {
            WorkflowRunEntity run = createRun("run-1", RunStatus.WAITING_TRIGGER);
            Trigger trigger = new Trigger("wh1", "Webhook", "receive_one", "webhook");
            WorkflowPlan plan = buildPlan(List.of(trigger));
            WorkflowExecution execution = buildExecution("run-1", plan);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
            when(epochManager.getCurrentEpoch(any(WorkflowRunEntity.class), eq("trigger:webhook"))).thenReturn(3);

            int epoch = service.resetForNextCycle(run, execution, plan, "run-1",
                    TriggerType.WEBHOOK, "trigger:webhook", false, -1);

            assertThat(epoch).isEqualTo(3);
            verify(runRepository, never()).save(any());
        }
    }

    // ==================== resetForNextCycle with different trigger types ====================

    @Nested
    @DisplayName("resetForNextCycle with all trigger types")
    class ResetAllTriggerTypes {

        private void verifyResetForTriggerType(String triggerLabel, String type, TriggerType triggerType) {
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING);
            Trigger trigger = new Trigger("id1", triggerLabel, "receive_one", type);
            WorkflowPlan plan = buildPlan(List.of(trigger));
            WorkflowExecution execution = buildExecution("run-1", plan);

            String normalizedKey = trigger.getNormalizedKey();
            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
            when(epochManager.getCurrentEpoch(any(WorkflowRunEntity.class), eq(normalizedKey))).thenReturn(2);

            service.resetForNextCycle(run, execution, plan, "run-1",
                    triggerType, normalizedKey, false, -1);

            ArgumentCaptor<WorkflowRunEntity> captor = ArgumentCaptor.forClass(WorkflowRunEntity.class);
            verify(runRepository).save(captor.capture());

            assertThat(captor.getValue().getStatus()).isEqualTo(RunStatus.WAITING_TRIGGER);
            assertThat(captor.getValue().getEndedAt()).isNull();
            assertThat(captor.getValue().getMetadata()).containsEntry("lastCycleResult", "completed");
        }

        @Test
        @DisplayName("Should reset correctly for webhook trigger")
        void resetForWebhookTrigger() {
            verifyResetForTriggerType("My Webhook", "webhook", TriggerType.WEBHOOK);
        }

        @Test
        @DisplayName("Should reset correctly for manual trigger")
        void resetForManualTrigger() {
            verifyResetForTriggerType("Manual Start", "manual", TriggerType.MANUAL);
        }

        @Test
        @DisplayName("Should reset correctly for chat trigger")
        void resetForChatTrigger() {
            verifyResetForTriggerType("Chat Input", "chat", TriggerType.CHAT);
        }

        @Test
        @DisplayName("Should reset correctly for schedule trigger")
        void resetForScheduleTrigger() {
            verifyResetForTriggerType("Hourly Sync", "schedule", TriggerType.SCHEDULE);
        }

        @Test
        @DisplayName("Should reset correctly for form trigger")
        void resetForFormTrigger() {
            verifyResetForTriggerType("Contact Form", "form", TriggerType.FORM);
        }

        @Test
        @DisplayName("Should reset correctly for workflow trigger")
        void resetForWorkflowTrigger() {
            verifyResetForTriggerType("After Parent", "workflow", TriggerType.WORKFLOW);
        }
    }

    // ==================== Multi-DAG reset scenarios ====================

    @Nested
    @DisplayName("Multi-DAG reset scenarios")
    class MultiDagResetScenarios {

        @Test
        @DisplayName("Should reset correctly for first trigger in multi-trigger workflow")
        void shouldResetFirstTriggerInMultiDag() {
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING);
            Trigger wh = new Trigger("wh1", "Webhook", "receive_one", "webhook");
            Trigger manual = new Trigger("m1", "Manual", "receive_one", "manual");
            WorkflowPlan plan = buildPlan(List.of(wh, manual));
            WorkflowExecution execution = buildExecution("run-1", plan);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
            when(epochManager.getCurrentEpoch(any(WorkflowRunEntity.class), eq("trigger:webhook"))).thenReturn(2);

            service.resetForNextCycle(run, execution, plan, "run-1",
                    TriggerType.WEBHOOK, "trigger:webhook", false, -1);

            ArgumentCaptor<WorkflowRunEntity> captor = ArgumentCaptor.forClass(WorkflowRunEntity.class);
            verify(runRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(RunStatus.WAITING_TRIGGER);

            // Verify DAG-scoped reset was called (not global)
            verify(epochManager).resetDagWithRerunPattern("run-1", plan, "trigger:webhook");
        }

        @Test
        @DisplayName("Should reset correctly for second trigger in multi-trigger workflow")
        void shouldResetSecondTriggerInMultiDag() {
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING);
            Trigger wh = new Trigger("wh1", "Webhook", "receive_one", "webhook");
            Trigger manual = new Trigger("m1", "Manual", "receive_one", "manual");
            WorkflowPlan plan = buildPlan(List.of(wh, manual));
            WorkflowExecution execution = buildExecution("run-1", plan);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
            when(epochManager.getCurrentEpoch(any(WorkflowRunEntity.class), eq("trigger:manual"))).thenReturn(3);

            service.resetForNextCycle(run, execution, plan, "run-1",
                    TriggerType.MANUAL, "trigger:manual", false, -1);

            verify(epochManager).resetDagWithRerunPattern("run-1", plan, "trigger:manual");

            ArgumentCaptor<WorkflowRunEntity> captor = ArgumentCaptor.forClass(WorkflowRunEntity.class);
            verify(runRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(RunStatus.WAITING_TRIGGER);
        }

        @Test
        @DisplayName("Should use global reset when triggerId is null")
        void shouldUseGlobalResetWhenTriggerIdNull() {
            WorkflowRunEntity run = createRun("run-1", RunStatus.RUNNING);
            Trigger wh = new Trigger("wh1", "Webhook", "receive_one", "webhook");
            WorkflowPlan plan = buildPlan(List.of(wh));
            WorkflowExecution execution = buildExecution("run-1", plan);

            when(runRepository.findByRunIdPublicForUpdate("run-1")).thenReturn(Optional.of(run));
            when(epochManager.getCurrentEpoch(any(WorkflowRunEntity.class))).thenReturn(2);

            service.resetForNextCycle(run, execution, plan, "run-1",
                    TriggerType.WEBHOOK, null, false, -1);

            // Should use global reset (resetWithRerunPattern, not resetDagWithRerunPattern)
            verify(epochManager).resetWithRerunPattern("run-1", plan, "trigger:webhook");
            verify(epochManager, never()).resetDagWithRerunPattern(anyString(), any(), anyString());
        }
    }
}
