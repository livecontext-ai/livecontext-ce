package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionStatistics;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for ErrorTriggerDispatchService.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ErrorTriggerDispatchService")
class ErrorTriggerDispatchServiceTest {

    @Mock
    private WorkflowTriggerLookupService triggerLookupService;

    @Mock
    private WorkflowRunRepository runRepository;

    @Mock
    private ReusableTriggerService triggerService;

    private ErrorTriggerDispatchService service;

    private static final UUID PARENT_WORKFLOW_ID = UUID.randomUUID();
    private static final UUID PARENT_WORKFLOW_RUN_ID = UUID.randomUUID();
    private static final String PARENT_RUN_ID = "run-parent-123";
    private static final UUID DOWNSTREAM_WORKFLOW_ID = UUID.randomUUID();
    private static final String DOWNSTREAM_RUN_ID = "run-downstream-456";
    private static final String TENANT_ID = "tenant-test";

    @BeforeEach
    void setUp() {
        service = new ErrorTriggerDispatchService(triggerLookupService, runRepository, triggerService);
    }

    /**
     * Build a plan map with triggers of the given type referencing parentWorkflowId.
     */
    private Map<String, Object> buildPlanWithTrigger(String triggerType, String triggerId) {
        Map<String, Object> trigger = new HashMap<>();
        trigger.put("type", triggerType);
        trigger.put("id", triggerId);
        trigger.put("label", "error_handler");
        trigger.put("strategy", "single");

        Map<String, Object> plan = new HashMap<>();
        plan.put("triggers", List.of(trigger));
        plan.put("mcps", List.of());
        plan.put("edges", List.of());
        plan.put("cores", List.of());
        return plan;
    }

    private WorkflowExecution createFailedExecution(RunStatus status) {
        WorkflowExecution execution = mock(WorkflowExecution.class);
        when(execution.getRunId()).thenReturn(PARENT_RUN_ID);
        when(execution.getStatus()).thenReturn(status);
        when(execution.getWorkflowRunId()).thenReturn(PARENT_WORKFLOW_RUN_ID);
        when(execution.getErrorMessage()).thenReturn("Something went wrong");

        ExecutionStatistics stats = new ExecutionStatistics(
            10, 7, 2, 1, 0, 5000L, status, 3, 3, Map.of()
        );
        when(execution.getStatistics()).thenReturn(stats);
        return execution;
    }

    private WorkflowRunEntity createParentRunEntity(Map<String, Object> plan) {
        WorkflowRunEntity runEntity = mock(WorkflowRunEntity.class);
        WorkflowEntity workflow = mock(WorkflowEntity.class);
        when(workflow.getId()).thenReturn(PARENT_WORKFLOW_ID);
        when(workflow.getTenantId()).thenReturn(TENANT_ID);
        when(workflow.getOrganizationId()).thenReturn(null);
        when(runEntity.getWorkflow()).thenReturn(workflow);
        when(runEntity.getPlan()).thenReturn(plan);
        when(runEntity.getRunIdPublic()).thenReturn(PARENT_RUN_ID);
        return runEntity;
    }

    private WorkflowRunEntity createDownstreamRunEntity(Map<String, Object> plan) {
        return createDownstreamRunEntity(plan, RunStatus.WAITING_TRIGGER);
    }

    private WorkflowRunEntity createDownstreamRunEntity(Map<String, Object> plan, RunStatus status) {
        WorkflowRunEntity runEntity = mock(WorkflowRunEntity.class);
        when(runEntity.getPlan()).thenReturn(plan);
        when(runEntity.getRunIdPublic()).thenReturn(DOWNSTREAM_RUN_ID);
        when(runEntity.getStatus()).thenReturn(status);
        return runEntity;
    }

    private WorkflowEntity createDownstreamWorkflow() {
        return createDownstreamWorkflow(null);
    }

    private WorkflowEntity createDownstreamWorkflow(Integer pinnedVersion) {
        WorkflowEntity workflow = mock(WorkflowEntity.class);
        when(workflow.getId()).thenReturn(DOWNSTREAM_WORKFLOW_ID);
        when(workflow.getName()).thenReturn("Error Handler Workflow");
        when(workflow.getPinnedVersion()).thenReturn(pinnedVersion);
        when(workflow.getTenantId()).thenReturn(TENANT_ID);
        when(workflow.getOrganizationId()).thenReturn(null);
        return workflow;
    }

    @Nested
    @DisplayName("dispatchWorkflowFailure")
    class DispatchWorkflowFailureTests {

        @Test
        @DisplayName("Should dispatch to workflow with error trigger referencing failed workflow")
        void shouldDispatchToErrorHandlerWorkflow() {
            Map<String, Object> parentPlan = buildPlanWithTrigger("manual", "start");
            WorkflowRunEntity parentRun = createParentRunEntity(parentPlan);
            WorkflowExecution execution = createFailedExecution(RunStatus.FAILED);

            when(runRepository.findById(PARENT_WORKFLOW_RUN_ID)).thenReturn(Optional.of(parentRun));

            WorkflowEntity downstream = createDownstreamWorkflow();
            when(triggerLookupService.findByErrorTrigger(PARENT_WORKFLOW_ID.toString()))
                .thenReturn(List.of(downstream));

            Map<String, Object> downstreamPlan = buildPlanWithTrigger("error", PARENT_WORKFLOW_ID.toString());
            WorkflowRunEntity downstreamRun = createDownstreamRunEntity(downstreamPlan);
            when(runRepository.countByWorkflowIdAndStatus(DOWNSTREAM_WORKFLOW_ID, RunStatus.RUNNING)).thenReturn(0L);
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(DOWNSTREAM_WORKFLOW_ID))
                .thenReturn(Optional.of(downstreamRun));

            TriggerExecutionResult triggerResult = TriggerExecutionResult.success(
                DOWNSTREAM_RUN_ID, "trigger:error_handler", TriggerType.ERROR, Set.of(), 1);
            when(triggerService.executeTrigger(eq(downstreamRun), any(), eq(TriggerType.ERROR), any()))
                .thenReturn(triggerResult);

            service.dispatchWorkflowFailure(execution);

            verify(triggerService).executeTrigger(eq(downstreamRun), any(), eq(TriggerType.ERROR), any());
        }

        @Test
        @DisplayName("Should dispatch on PARTIAL_SUCCESS status")
        void shouldDispatchOnPartialSuccess() {
            Map<String, Object> parentPlan = buildPlanWithTrigger("manual", "start");
            WorkflowRunEntity parentRun = createParentRunEntity(parentPlan);
            WorkflowExecution execution = createFailedExecution(RunStatus.PARTIAL_SUCCESS);

            when(runRepository.findById(PARENT_WORKFLOW_RUN_ID)).thenReturn(Optional.of(parentRun));

            WorkflowEntity downstream = createDownstreamWorkflow();
            when(triggerLookupService.findByErrorTrigger(PARENT_WORKFLOW_ID.toString()))
                .thenReturn(List.of(downstream));

            Map<String, Object> downstreamPlan = buildPlanWithTrigger("error", PARENT_WORKFLOW_ID.toString());
            WorkflowRunEntity downstreamRun = createDownstreamRunEntity(downstreamPlan);
            when(runRepository.countByWorkflowIdAndStatus(DOWNSTREAM_WORKFLOW_ID, RunStatus.RUNNING)).thenReturn(0L);
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(DOWNSTREAM_WORKFLOW_ID))
                .thenReturn(Optional.of(downstreamRun));

            TriggerExecutionResult triggerResult = TriggerExecutionResult.success(
                DOWNSTREAM_RUN_ID, "trigger:error_handler", TriggerType.ERROR, Set.of(), 1);
            when(triggerService.executeTrigger(eq(downstreamRun), any(), eq(TriggerType.ERROR), any()))
                .thenReturn(triggerResult);

            service.dispatchWorkflowFailure(execution);

            verify(triggerService).executeTrigger(eq(downstreamRun), any(), eq(TriggerType.ERROR), any());
        }

        @Test
        @DisplayName("Should NOT dispatch for COMPLETED status")
        void shouldNotDispatchForCompletedStatus() {
            WorkflowExecution execution = mock(WorkflowExecution.class);
            when(execution.getRunId()).thenReturn(PARENT_RUN_ID);
            when(execution.getStatus()).thenReturn(RunStatus.COMPLETED);

            service.dispatchWorkflowFailure(execution);

            verifyNoInteractions(triggerLookupService);
            verifyNoInteractions(triggerService);
        }

        @Test
        @DisplayName("Should NOT dispatch for RUNNING status")
        void shouldNotDispatchForRunningStatus() {
            WorkflowExecution execution = mock(WorkflowExecution.class);
            when(execution.getRunId()).thenReturn(PARENT_RUN_ID);
            when(execution.getStatus()).thenReturn(RunStatus.RUNNING);

            service.dispatchWorkflowFailure(execution);

            verifyNoInteractions(triggerLookupService);
            verifyNoInteractions(triggerService);
        }

        @Test
        @DisplayName("Should NOT dispatch for error handler workflows (anti-loop protection)")
        void shouldNotDispatchForErrorHandlerWorkflows() {
            Map<String, Object> parentPlan = buildPlanWithTrigger("error", UUID.randomUUID().toString());
            WorkflowRunEntity parentRun = createParentRunEntity(parentPlan);
            WorkflowExecution execution = createFailedExecution(RunStatus.FAILED);

            when(runRepository.findById(PARENT_WORKFLOW_RUN_ID)).thenReturn(Optional.of(parentRun));

            service.dispatchWorkflowFailure(execution);

            verifyNoInteractions(triggerLookupService);
            verifyNoInteractions(triggerService);
        }

        @Test
        @DisplayName("Should handle no matching downstream workflows gracefully")
        void shouldHandleNoMatchingDownstreamWorkflows() {
            Map<String, Object> parentPlan = buildPlanWithTrigger("manual", "start");
            WorkflowRunEntity parentRun = createParentRunEntity(parentPlan);
            WorkflowExecution execution = createFailedExecution(RunStatus.FAILED);

            when(runRepository.findById(PARENT_WORKFLOW_RUN_ID)).thenReturn(Optional.of(parentRun));
            when(triggerLookupService.findByErrorTrigger(PARENT_WORKFLOW_ID.toString()))
                .thenReturn(List.of());

            service.dispatchWorkflowFailure(execution);

            verifyNoInteractions(triggerService);
        }

        @Test
        @DisplayName("Should handle no active runs for downstream workflow")
        void shouldHandleNoActiveRunsForDownstream() {
            Map<String, Object> parentPlan = buildPlanWithTrigger("manual", "start");
            WorkflowRunEntity parentRun = createParentRunEntity(parentPlan);
            WorkflowExecution execution = createFailedExecution(RunStatus.FAILED);

            when(runRepository.findById(PARENT_WORKFLOW_RUN_ID)).thenReturn(Optional.of(parentRun));

            WorkflowEntity downstream = createDownstreamWorkflow();
            when(triggerLookupService.findByErrorTrigger(PARENT_WORKFLOW_ID.toString()))
                .thenReturn(List.of(downstream));
            when(runRepository.countByWorkflowIdAndStatus(DOWNSTREAM_WORKFLOW_ID, RunStatus.RUNNING)).thenReturn(0L);
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(DOWNSTREAM_WORKFLOW_ID))
                .thenReturn(Optional.empty());

            service.dispatchWorkflowFailure(execution);

            verifyNoInteractions(triggerService);
        }

        @Test
        @DisplayName("Should handle null execution gracefully")
        void shouldHandleNullExecution() {
            service.dispatchWorkflowFailure(null);

            verifyNoInteractions(runRepository);
            verifyNoInteractions(triggerLookupService);
            verifyNoInteractions(triggerService);
        }

        @Test
        @DisplayName("Should handle null workflowRunId gracefully")
        void shouldHandleNullWorkflowRunId() {
            WorkflowExecution execution = mock(WorkflowExecution.class);
            when(execution.getRunId()).thenReturn(PARENT_RUN_ID);
            when(execution.getStatus()).thenReturn(RunStatus.FAILED);
            when(execution.getWorkflowRunId()).thenReturn(null);

            service.dispatchWorkflowFailure(execution);

            verify(runRepository, never()).findById(any());
            verifyNoInteractions(triggerService);
        }

        @Test
        @DisplayName("Should skip when concurrent runs limit is reached")
        void shouldSkipWhenConcurrentRunsLimitReached() {
            Map<String, Object> parentPlan = buildPlanWithTrigger("manual", "start");
            WorkflowRunEntity parentRun = createParentRunEntity(parentPlan);
            WorkflowExecution execution = createFailedExecution(RunStatus.FAILED);

            when(runRepository.findById(PARENT_WORKFLOW_RUN_ID)).thenReturn(Optional.of(parentRun));

            WorkflowEntity downstream = createDownstreamWorkflow();
            when(triggerLookupService.findByErrorTrigger(PARENT_WORKFLOW_ID.toString()))
                .thenReturn(List.of(downstream));
            when(runRepository.countByWorkflowIdAndStatus(DOWNSTREAM_WORKFLOW_ID, RunStatus.RUNNING)).thenReturn(5L);

            service.dispatchWorkflowFailure(execution);

            verifyNoInteractions(triggerService);
        }
    }

    @Nested
    @DisplayName("Error payload")
    class ErrorPayloadTests {

        @Test
        @DisplayName("Error payload should contain all required fields")
        @SuppressWarnings("unchecked")
        void errorPayloadShouldContainAllFields() {
            Map<String, Object> parentPlan = buildPlanWithTrigger("manual", "start");
            WorkflowRunEntity parentRun = createParentRunEntity(parentPlan);
            WorkflowExecution execution = createFailedExecution(RunStatus.FAILED);

            when(runRepository.findById(PARENT_WORKFLOW_RUN_ID)).thenReturn(Optional.of(parentRun));

            WorkflowEntity downstream = createDownstreamWorkflow();
            when(triggerLookupService.findByErrorTrigger(PARENT_WORKFLOW_ID.toString()))
                .thenReturn(List.of(downstream));

            Map<String, Object> downstreamPlan = buildPlanWithTrigger("error", PARENT_WORKFLOW_ID.toString());
            WorkflowRunEntity downstreamRun = createDownstreamRunEntity(downstreamPlan);
            when(runRepository.countByWorkflowIdAndStatus(DOWNSTREAM_WORKFLOW_ID, RunStatus.RUNNING)).thenReturn(0L);
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(DOWNSTREAM_WORKFLOW_ID))
                .thenReturn(Optional.of(downstreamRun));

            TriggerExecutionResult triggerResult = TriggerExecutionResult.success(
                DOWNSTREAM_RUN_ID, "trigger:error_handler", TriggerType.ERROR, Set.of(), 1);
            when(triggerService.executeTrigger(eq(downstreamRun), any(), eq(TriggerType.ERROR), any()))
                .thenReturn(triggerResult);

            service.dispatchWorkflowFailure(execution);

            ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(triggerService).executeTrigger(eq(downstreamRun), any(), eq(TriggerType.ERROR), payloadCaptor.capture());

            Map<String, Object> payload = payloadCaptor.getValue();
            assertThat(payload).containsKey("parentWorkflowId");
            assertThat(payload.get("parentWorkflowId")).isEqualTo(PARENT_WORKFLOW_ID.toString());
            assertThat(payload).containsKey("parentRunId");
            assertThat(payload.get("parentRunId")).isEqualTo(PARENT_RUN_ID);
            assertThat(payload).containsKey("status");
            assertThat(payload.get("status")).isEqualTo("FAILED");
            assertThat(payload).containsKey("errorMessage");
            assertThat(payload.get("errorMessage")).isEqualTo("Something went wrong");
            assertThat(payload).containsKey("triggeredAt");
            assertThat(payload).containsKey("failedSteps");
            assertThat(payload.get("failedSteps")).isEqualTo(2);
            assertThat(payload).containsKey("completedSteps");
            assertThat(payload.get("completedSteps")).isEqualTo(7);
            assertThat(payload).containsKey("totalSteps");
            assertThat(payload.get("totalSteps")).isEqualTo(10);
            assertThat(payload).containsKey("skippedSteps");
            assertThat(payload.get("skippedSteps")).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Version-Aware Dispatch")
    class VersionAwareDispatchTests {

        @Test
        @DisplayName("Pinned downstream: should find run by version")
        void pinnedFindsRunByVersion() {
            Map<String, Object> parentPlan = buildPlanWithTrigger("manual", "start");
            WorkflowRunEntity parentRun = createParentRunEntity(parentPlan);
            WorkflowExecution execution = createFailedExecution(RunStatus.FAILED);
            when(runRepository.findById(PARENT_WORKFLOW_RUN_ID)).thenReturn(Optional.of(parentRun));

            WorkflowEntity downstream = createDownstreamWorkflow(5);
            when(triggerLookupService.findByErrorTrigger(PARENT_WORKFLOW_ID.toString()))
                    .thenReturn(List.of(downstream));

            Map<String, Object> downstreamPlan = buildPlanWithTrigger("error", PARENT_WORKFLOW_ID.toString());
            WorkflowRunEntity downstreamRun = createDownstreamRunEntity(downstreamPlan);
            when(runRepository.countByWorkflowIdAndStatus(DOWNSTREAM_WORKFLOW_ID, RunStatus.RUNNING)).thenReturn(0L);
            when(runRepository.findFirstByWorkflowIdAndPlanVersionOrderByStartedAtDesc(DOWNSTREAM_WORKFLOW_ID, 5))
                    .thenReturn(Optional.of(downstreamRun));
            when(triggerService.executeTrigger(eq(downstreamRun), any(), eq(TriggerType.ERROR), any()))
                    .thenReturn(TriggerExecutionResult.success(DOWNSTREAM_RUN_ID, "trigger:error_handler", TriggerType.ERROR, Set.of(), 1));

            service.dispatchWorkflowFailure(execution);

            verify(runRepository).findFirstByWorkflowIdAndPlanVersionOrderByStartedAtDesc(DOWNSTREAM_WORKFLOW_ID, 5);
            verify(runRepository, never()).findFirstByWorkflowIdOrderByStartedAtDesc(DOWNSTREAM_WORKFLOW_ID);
            verify(triggerService).executeTrigger(eq(downstreamRun), any(), eq(TriggerType.ERROR), any());
        }

        @Test
        @DisplayName("Pinned downstream: no matching run → skip dispatch")
        void pinnedNoMatchSkipsDispatch() {
            Map<String, Object> parentPlan = buildPlanWithTrigger("manual", "start");
            WorkflowRunEntity parentRun = createParentRunEntity(parentPlan);
            WorkflowExecution execution = createFailedExecution(RunStatus.FAILED);
            when(runRepository.findById(PARENT_WORKFLOW_RUN_ID)).thenReturn(Optional.of(parentRun));

            WorkflowEntity downstream = createDownstreamWorkflow(5);
            when(triggerLookupService.findByErrorTrigger(PARENT_WORKFLOW_ID.toString()))
                    .thenReturn(List.of(downstream));
            when(runRepository.countByWorkflowIdAndStatus(DOWNSTREAM_WORKFLOW_ID, RunStatus.RUNNING)).thenReturn(0L);
            when(runRepository.findFirstByWorkflowIdAndPlanVersionOrderByStartedAtDesc(DOWNSTREAM_WORKFLOW_ID, 5))
                    .thenReturn(Optional.empty());

            service.dispatchWorkflowFailure(execution);

            verifyNoInteractions(triggerService);
        }

        @Test
        @DisplayName("Pinned v0: zero is a valid version")
        void pinnedVersionZeroIsValid() {
            Map<String, Object> parentPlan = buildPlanWithTrigger("manual", "start");
            WorkflowRunEntity parentRun = createParentRunEntity(parentPlan);
            WorkflowExecution execution = createFailedExecution(RunStatus.FAILED);
            when(runRepository.findById(PARENT_WORKFLOW_RUN_ID)).thenReturn(Optional.of(parentRun));

            WorkflowEntity downstream = createDownstreamWorkflow(0);
            when(triggerLookupService.findByErrorTrigger(PARENT_WORKFLOW_ID.toString()))
                    .thenReturn(List.of(downstream));

            Map<String, Object> downstreamPlan = buildPlanWithTrigger("error", PARENT_WORKFLOW_ID.toString());
            WorkflowRunEntity downstreamRun = createDownstreamRunEntity(downstreamPlan);
            when(runRepository.countByWorkflowIdAndStatus(DOWNSTREAM_WORKFLOW_ID, RunStatus.RUNNING)).thenReturn(0L);
            when(runRepository.findFirstByWorkflowIdAndPlanVersionOrderByStartedAtDesc(DOWNSTREAM_WORKFLOW_ID, 0))
                    .thenReturn(Optional.of(downstreamRun));
            when(triggerService.executeTrigger(eq(downstreamRun), any(), eq(TriggerType.ERROR), any()))
                    .thenReturn(TriggerExecutionResult.success(DOWNSTREAM_RUN_ID, "trigger:error_handler", TriggerType.ERROR, Set.of(), 1));

            service.dispatchWorkflowFailure(execution);

            verify(runRepository).findFirstByWorkflowIdAndPlanVersionOrderByStartedAtDesc(DOWNSTREAM_WORKFLOW_ID, 0);
        }
    }

    @Nested
    @DisplayName("Terminal Run Rejection")
    class TerminalRunRejectionTests {

        @Test
        @DisplayName("CANCELLED downstream run → skip dispatch")
        void cancelledRunSkipsDispatch() {
            Map<String, Object> parentPlan = buildPlanWithTrigger("manual", "start");
            WorkflowRunEntity parentRun = createParentRunEntity(parentPlan);
            WorkflowExecution execution = createFailedExecution(RunStatus.FAILED);
            when(runRepository.findById(PARENT_WORKFLOW_RUN_ID)).thenReturn(Optional.of(parentRun));

            WorkflowEntity downstream = createDownstreamWorkflow();
            when(triggerLookupService.findByErrorTrigger(PARENT_WORKFLOW_ID.toString()))
                    .thenReturn(List.of(downstream));
            when(runRepository.countByWorkflowIdAndStatus(DOWNSTREAM_WORKFLOW_ID, RunStatus.RUNNING)).thenReturn(0L);

            WorkflowRunEntity cancelledRun = createDownstreamRunEntity(
                    buildPlanWithTrigger("error", PARENT_WORKFLOW_ID.toString()), RunStatus.CANCELLED);
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(DOWNSTREAM_WORKFLOW_ID))
                    .thenReturn(Optional.of(cancelledRun));

            service.dispatchWorkflowFailure(execution);

            verifyNoInteractions(triggerService);
        }

        @Test
        @DisplayName("TIMEOUT downstream run → skip dispatch")
        void timeoutRunSkipsDispatch() {
            Map<String, Object> parentPlan = buildPlanWithTrigger("manual", "start");
            WorkflowRunEntity parentRun = createParentRunEntity(parentPlan);
            WorkflowExecution execution = createFailedExecution(RunStatus.FAILED);
            when(runRepository.findById(PARENT_WORKFLOW_RUN_ID)).thenReturn(Optional.of(parentRun));

            WorkflowEntity downstream = createDownstreamWorkflow();
            when(triggerLookupService.findByErrorTrigger(PARENT_WORKFLOW_ID.toString()))
                    .thenReturn(List.of(downstream));
            when(runRepository.countByWorkflowIdAndStatus(DOWNSTREAM_WORKFLOW_ID, RunStatus.RUNNING)).thenReturn(0L);

            WorkflowRunEntity timeoutRun = createDownstreamRunEntity(
                    buildPlanWithTrigger("error", PARENT_WORKFLOW_ID.toString()), RunStatus.TIMEOUT);
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(DOWNSTREAM_WORKFLOW_ID))
                    .thenReturn(Optional.of(timeoutRun));

            service.dispatchWorkflowFailure(execution);

            verifyNoInteractions(triggerService);
        }
    }

    @Nested
    @DisplayName("isErrorHandlerWorkflow")
    class IsErrorHandlerWorkflowTests {

        @Test
        @DisplayName("Should return true for workflow with error trigger")
        void shouldReturnTrueForErrorTrigger() {
            Map<String, Object> plan = buildPlanWithTrigger("error", UUID.randomUUID().toString());
            WorkflowRunEntity runEntity = mock(WorkflowRunEntity.class);
            when(runEntity.getPlan()).thenReturn(plan);

            assertThat(service.isErrorHandlerWorkflow(runEntity)).isTrue();
        }

        @Test
        @DisplayName("Should return false for workflow with workflow trigger")
        void shouldReturnFalseForWorkflowTrigger() {
            Map<String, Object> plan = buildPlanWithTrigger("workflow", UUID.randomUUID().toString());
            WorkflowRunEntity runEntity = mock(WorkflowRunEntity.class);
            when(runEntity.getPlan()).thenReturn(plan);

            assertThat(service.isErrorHandlerWorkflow(runEntity)).isFalse();
        }

        @Test
        @DisplayName("Should return false for workflow with manual trigger")
        void shouldReturnFalseForManualTrigger() {
            Map<String, Object> plan = buildPlanWithTrigger("manual", "start");
            WorkflowRunEntity runEntity = mock(WorkflowRunEntity.class);
            when(runEntity.getPlan()).thenReturn(plan);

            assertThat(service.isErrorHandlerWorkflow(runEntity)).isFalse();
        }

        @Test
        @DisplayName("Should return false when plan is null")
        void shouldReturnFalseWhenPlanIsNull() {
            WorkflowRunEntity runEntity = mock(WorkflowRunEntity.class);
            when(runEntity.getPlan()).thenReturn(null);

            assertThat(service.isErrorHandlerWorkflow(runEntity)).isFalse();
        }
    }

    // Reusable-trigger runs (manual/webhook/chat/schedule/form) never transition to
    // FAILED/PARTIAL_SUCCESS - they reset to WAITING_TRIGGER between epochs. The
    // standard dispatchWorkflowFailure skips them because its terminal-status gate
    // fires. dispatchEpochFailure is the variant ReusableTriggerService calls when
    // an epoch had failures but the run stays active (#ET1).
    @Nested
    @DisplayName("dispatchEpochFailure - non-terminal reusable-trigger runs")
    class DispatchEpochFailureTests {

        @Test
        @DisplayName("Should dispatch even when status is RUNNING (reusable-trigger epoch failure)")
        void shouldDispatchOnRunningStatus() {
            Map<String, Object> parentPlan = buildPlanWithTrigger("manual", "start");
            WorkflowRunEntity parentRun = createParentRunEntity(parentPlan);

            WorkflowExecution execution = mock(WorkflowExecution.class);
            when(execution.getRunId()).thenReturn(PARENT_RUN_ID);
            when(execution.getStatus()).thenReturn(RunStatus.RUNNING);
            when(execution.getWorkflowRunId()).thenReturn(PARENT_WORKFLOW_RUN_ID);
            when(execution.getErrorMessage()).thenReturn("step failed");
            ExecutionStatistics stats = new ExecutionStatistics(
                5, 3, 1, 1, 0, 500L, RunStatus.RUNNING, 1, 1, Map.of());
            when(execution.getStatistics()).thenReturn(stats);

            when(runRepository.findById(PARENT_WORKFLOW_RUN_ID)).thenReturn(Optional.of(parentRun));

            WorkflowEntity downstream = createDownstreamWorkflow();
            when(triggerLookupService.findByErrorTrigger(PARENT_WORKFLOW_ID.toString()))
                .thenReturn(List.of(downstream));

            Map<String, Object> downstreamPlan = buildPlanWithTrigger("error", PARENT_WORKFLOW_ID.toString());
            WorkflowRunEntity downstreamRun = createDownstreamRunEntity(downstreamPlan);
            when(runRepository.countByWorkflowIdAndStatus(DOWNSTREAM_WORKFLOW_ID, RunStatus.RUNNING)).thenReturn(0L);
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(DOWNSTREAM_WORKFLOW_ID))
                .thenReturn(Optional.of(downstreamRun));

            TriggerExecutionResult triggerResult = TriggerExecutionResult.success(
                DOWNSTREAM_RUN_ID, "trigger:error_handler", TriggerType.ERROR, Set.of(), 1);
            when(triggerService.executeTrigger(eq(downstreamRun), any(), eq(TriggerType.ERROR), any()))
                .thenReturn(triggerResult);

            service.dispatchEpochFailure(execution);

            verify(triggerService).executeTrigger(eq(downstreamRun), any(), eq(TriggerType.ERROR), any());
        }

        @Test
        @DisplayName("Should dispatch when status is WAITING_TRIGGER (post-reset reusable trigger)")
        void shouldDispatchOnWaitingTriggerStatus() {
            Map<String, Object> parentPlan = buildPlanWithTrigger("webhook", "start");
            WorkflowRunEntity parentRun = createParentRunEntity(parentPlan);

            WorkflowExecution execution = mock(WorkflowExecution.class);
            when(execution.getRunId()).thenReturn(PARENT_RUN_ID);
            when(execution.getStatus()).thenReturn(RunStatus.WAITING_TRIGGER);
            when(execution.getWorkflowRunId()).thenReturn(PARENT_WORKFLOW_RUN_ID);
            when(execution.getErrorMessage()).thenReturn("step failed");

            when(runRepository.findById(PARENT_WORKFLOW_RUN_ID)).thenReturn(Optional.of(parentRun));

            WorkflowEntity downstream = createDownstreamWorkflow();
            when(triggerLookupService.findByErrorTrigger(PARENT_WORKFLOW_ID.toString()))
                .thenReturn(List.of(downstream));

            Map<String, Object> downstreamPlan = buildPlanWithTrigger("error", PARENT_WORKFLOW_ID.toString());
            WorkflowRunEntity downstreamRun = createDownstreamRunEntity(downstreamPlan);
            when(runRepository.countByWorkflowIdAndStatus(DOWNSTREAM_WORKFLOW_ID, RunStatus.RUNNING)).thenReturn(0L);
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(DOWNSTREAM_WORKFLOW_ID))
                .thenReturn(Optional.of(downstreamRun));

            TriggerExecutionResult triggerResult = TriggerExecutionResult.success(
                DOWNSTREAM_RUN_ID, "trigger:error_handler", TriggerType.ERROR, Set.of(), 1);
            when(triggerService.executeTrigger(eq(downstreamRun), any(), eq(TriggerType.ERROR), any()))
                .thenReturn(triggerResult);

            service.dispatchEpochFailure(execution);

            verify(triggerService).executeTrigger(eq(downstreamRun), any(), eq(TriggerType.ERROR), any());
        }

        @Test
        @DisplayName("Should still apply anti-loop protection for error handler parents")
        void shouldStillApplyAntiLoopProtection() {
            Map<String, Object> parentPlan = buildPlanWithTrigger("error", UUID.randomUUID().toString());
            WorkflowRunEntity parentRun = createParentRunEntity(parentPlan);

            WorkflowExecution execution = mock(WorkflowExecution.class);
            when(execution.getRunId()).thenReturn(PARENT_RUN_ID);
            when(execution.getStatus()).thenReturn(RunStatus.RUNNING);
            when(execution.getWorkflowRunId()).thenReturn(PARENT_WORKFLOW_RUN_ID);

            when(runRepository.findById(PARENT_WORKFLOW_RUN_ID)).thenReturn(Optional.of(parentRun));

            service.dispatchEpochFailure(execution);

            verifyNoInteractions(triggerLookupService);
            verifyNoInteractions(triggerService);
        }

        @Test
        @DisplayName("Should handle null execution gracefully")
        void shouldHandleNullExecution() {
            service.dispatchEpochFailure(null);

            verifyNoInteractions(runRepository);
            verifyNoInteractions(triggerLookupService);
            verifyNoInteractions(triggerService);
        }
    }
}
