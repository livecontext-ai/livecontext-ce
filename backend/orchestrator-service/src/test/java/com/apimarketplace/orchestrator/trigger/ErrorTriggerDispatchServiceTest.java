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
    private ProductionRunResolver productionRunResolver;

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
        service = new ErrorTriggerDispatchService(
            triggerLookupService, runRepository, productionRunResolver, triggerService);
    }

    /** The handler has a fireable run: what {@code resolveActiveRun} returns on the happy path. */
    private void stubActiveRun(WorkflowRunEntity run) {
        when(productionRunResolver.resolveActiveRun(any(WorkflowEntity.class), anyList()))
            .thenReturn(new ProductionRunResolver.Resolution(
                Optional.of(run), ProductionRunResolver.Outcome.FOUND, "Error Handler Workflow"));
    }

    /**
     * The handler has no fireable run. This single outcome now covers what used to be two
     * distinct code paths in the service: "no run row at all" and "the newest run is
     * terminal". The resolver filters on status inside the query, so a terminal run is
     * simply not selected instead of being selected and then rejected.
     */
    private void stubNoActiveRun() {
        when(productionRunResolver.resolveActiveRun(any(WorkflowEntity.class), anyList()))
            .thenReturn(new ProductionRunResolver.Resolution(
                Optional.empty(), ProductionRunResolver.Outcome.NO_PRODUCTION_RUN, "Error Handler Workflow"));
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

    /**
     * The parent run, shaped like production: {@code getWorkflow()} returns a LAZY proxy
     * whose only safe accessor off-session is {@code getId()}. The workspace columns are
     * read from the RUN, which carries them (NOT NULL since V263).
     *
     * @param workflowFieldsUsable when false, every non-id accessor on the workflow proxy
     *                             throws like an uninitialised Hibernate proxy does on the
     *                             {@code @Async} dispatch thread.
     */
    private WorkflowRunEntity createParentRunEntity(Map<String, Object> plan, boolean workflowFieldsUsable) {
        WorkflowRunEntity runEntity = mock(WorkflowRunEntity.class);
        WorkflowEntity workflow = mock(WorkflowEntity.class);
        when(workflow.getId()).thenReturn(PARENT_WORKFLOW_ID);
        if (workflowFieldsUsable) {
            lenient().when(workflow.getTenantId()).thenReturn(TENANT_ID);
            lenient().when(workflow.getOrganizationId()).thenReturn(null);
        } else {
            lenient().when(workflow.getTenantId()).thenThrow(
                new org.hibernate.LazyInitializationException("could not initialize proxy - no Session"));
            lenient().when(workflow.getOrganizationId()).thenThrow(
                new org.hibernate.LazyInitializationException("could not initialize proxy - no Session"));
        }
        when(runEntity.getWorkflow()).thenReturn(workflow);
        when(runEntity.getPlan()).thenReturn(plan);
        when(runEntity.getRunIdPublic()).thenReturn(PARENT_RUN_ID);
        lenient().when(runEntity.getTenantId()).thenReturn(TENANT_ID);
        lenient().when(runEntity.getOrganizationId()).thenReturn(null);
        return runEntity;
    }

    private WorkflowRunEntity createParentRunEntity(Map<String, Object> plan) {
        return createParentRunEntity(plan, true);
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
            stubActiveRun(downstreamRun);

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
            stubActiveRun(downstreamRun);

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
            stubNoActiveRun();

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
            stubActiveRun(downstreamRun);

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
    @DisplayName("LC-066: a failed run holding Gmail / Drive data restricts the handler's run")
    class RestrictedFailedRun {

        @SuppressWarnings("unchecked")
        private Map<String, Object> dispatchAndCapturePayload(boolean failedRunRestricted) {
            com.apimarketplace.orchestrator.services.persistence.StepPayloadService stepPayloadService =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.services.persistence.StepPayloadService.class);
            when(stepPayloadService.isRunRestricted(PARENT_RUN_ID)).thenReturn(failedRunRestricted);
            org.springframework.test.util.ReflectionTestUtils.setField(service, "stepPayloadService", stepPayloadService);

            WorkflowRunEntity parentRun = createParentRunEntity(buildPlanWithTrigger("manual", "start"));
            when(runRepository.findById(PARENT_WORKFLOW_RUN_ID)).thenReturn(Optional.of(parentRun));
            WorkflowEntity downstream = createDownstreamWorkflow();
            when(triggerLookupService.findByErrorTrigger(PARENT_WORKFLOW_ID.toString())).thenReturn(List.of(downstream));
            WorkflowRunEntity downstreamRun = createDownstreamRunEntity(
                buildPlanWithTrigger("error", PARENT_WORKFLOW_ID.toString()));
            when(runRepository.countByWorkflowIdAndStatus(DOWNSTREAM_WORKFLOW_ID, RunStatus.RUNNING)).thenReturn(0L);
            stubActiveRun(downstreamRun);
            when(triggerService.executeTrigger(eq(downstreamRun), any(), eq(TriggerType.ERROR), any()))
                .thenReturn(TriggerExecutionResult.success(
                    DOWNSTREAM_RUN_ID, "trigger:error_handler", TriggerType.ERROR, Set.of(), 1));

            service.dispatchWorkflowFailure(createFailedExecution(RunStatus.FAILED));

            ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
            verify(triggerService).executeTrigger(eq(downstreamRun), any(), eq(TriggerType.ERROR), payload.capture());
            return payload.getValue();
        }

        @Test
        @DisplayName("regression: the error fire of a restricted run carries the restricted-data marker")
        void restrictedFailedRunMarksTheHandlerFire() {
            // The payload quotes the failed run (its error message, its failing step), which can
            // be Gmail content: the handler's run must be restricted from its first payload on.
            assertThat(dispatchAndCapturePayload(true))
                .containsEntry(ReusableTriggerService.RESTRICTED_DATA_MARKER, Boolean.TRUE);
        }

        @Test
        @DisplayName("an ordinary failed run's error fire carries no marker")
        void ordinaryFailedRunCarriesNoMarker() {
            assertThat(dispatchAndCapturePayload(false))
                .doesNotContainKey(ReusableTriggerService.RESTRICTED_DATA_MARKER);
        }
    }

    /**
     * Run resolution moved from a hand-rolled repository lookup to
     * {@link ProductionRunResolver#resolveActiveRun}. These tests pin the SEAM: the
     * service must ask the resolver and must never fall back to the raw, unfiltered
     * queries it used before.
     *
     * <p>The behaviour BEHIND the seam is pinned elsewhere: version scoping, FK
     * preference and the pin-optional contract in
     * {@code ProductionRunResolverTest.ResolveActiveRunTests}; the SQL-level status
     * filtering, showcase exclusion and the CANCELLED-shadowing regression in
     * {@code WorkflowRunRepositoryIntegrationTest} (nested {@code ProductionRunsBatch},
     * {@code cancelledRunDoesNotShadowWaitingTrigger}).
     */
    @Nested
    @DisplayName("Run resolution is delegated to ProductionRunResolver")
    class RunResolutionDelegationTests {

        private WorkflowExecution arrangeFailure(WorkflowEntity downstream) {
            Map<String, Object> parentPlan = buildPlanWithTrigger("manual", "start");
            WorkflowRunEntity parentRun = createParentRunEntity(parentPlan);
            WorkflowExecution execution = createFailedExecution(RunStatus.FAILED);
            when(runRepository.findById(PARENT_WORKFLOW_RUN_ID)).thenReturn(Optional.of(parentRun));
            when(triggerLookupService.findByErrorTrigger(PARENT_WORKFLOW_ID.toString()))
                    .thenReturn(List.of(downstream));
            when(runRepository.countByWorkflowIdAndStatus(DOWNSTREAM_WORKFLOW_ID, RunStatus.RUNNING)).thenReturn(0L);
            return execution;
        }

        @Test
        @DisplayName("REGRESSION: never uses the raw unfiltered run queries - a newer terminal run can no longer shadow a healthy one")
        void neverUsesRawUnfilteredQueries() {
            WorkflowEntity downstream = createDownstreamWorkflow(5);
            WorkflowExecution execution = arrangeFailure(downstream);

            WorkflowRunEntity downstreamRun = createDownstreamRunEntity(
                    buildPlanWithTrigger("error", PARENT_WORKFLOW_ID.toString()));
            stubActiveRun(downstreamRun);
            when(triggerService.executeTrigger(eq(downstreamRun), any(), eq(TriggerType.ERROR), any()))
                    .thenReturn(TriggerExecutionResult.success(DOWNSTREAM_RUN_ID, "trigger:error_handler", TriggerType.ERROR, Set.of(), 1));

            service.dispatchWorkflowFailure(execution);

            // The error lane must keep accepting every NON-TERMINAL status, including
            // AWAITING_SIGNAL / PENDING: a handler parked on an approval is still live,
            // and that is exactly what the pre-fix "reject isTerminal()" check allowed.
            verify(productionRunResolver).resolveActiveRun(eq(downstream), eq(ProductionRunResolver.NON_TERMINAL_STATUSES));
            // These two are the pre-fix lookups. Both order by started_at with no status
            // predicate, which is what let a cancelled editor test permanently kill the
            // error handler. They must never run again.
            verify(runRepository, never()).findFirstByWorkflowIdOrderByStartedAtDesc(any());
            verify(runRepository, never()).findFirstByWorkflowIdAndPlanVersionOrderByStartedAtDesc(any(), any());
            verify(triggerService).executeTrigger(eq(downstreamRun), any(), eq(TriggerType.ERROR), any());
        }

        @Test
        @DisplayName("Unpinned downstream: resolver still answers, dispatch proceeds (pin stays optional for error handlers)")
        void unpinnedDownstreamStillDispatches() {
            WorkflowEntity downstream = createDownstreamWorkflow(null);
            WorkflowExecution execution = arrangeFailure(downstream);

            WorkflowRunEntity downstreamRun = createDownstreamRunEntity(
                    buildPlanWithTrigger("error", PARENT_WORKFLOW_ID.toString()));
            stubActiveRun(downstreamRun);
            when(triggerService.executeTrigger(eq(downstreamRun), any(), eq(TriggerType.ERROR), any()))
                    .thenReturn(TriggerExecutionResult.success(DOWNSTREAM_RUN_ID, "trigger:error_handler", TriggerType.ERROR, Set.of(), 1));

            service.dispatchWorkflowFailure(execution);

            verify(triggerService).executeTrigger(eq(downstreamRun), any(), eq(TriggerType.ERROR), any());
        }

        @Test
        @DisplayName("NO_PRODUCTION_RUN → skip dispatch (covers both 'no run at all' and 'only terminal runs')")
        void noProductionRunSkipsDispatch() {
            WorkflowEntity downstream = createDownstreamWorkflow(5);
            WorkflowExecution execution = arrangeFailure(downstream);
            stubNoActiveRun();

            service.dispatchWorkflowFailure(execution);

            verifyNoInteractions(triggerService);
        }

        @Test
        @DisplayName("REGRESSION: dispatch works when the parent workflow is an uninitialised lazy proxy")
        void dispatchesWhenParentWorkflowProxyCannotInitialise() {
            // dispatchEpochFailure is @Async and open-in-view is off, so runEntity.getWorkflow()
            // hands back a proxy that throws on every field but the id. Reading the parent's
            // workspace off that proxy made the whole dispatch die in the outer catch: from
            // 2026-05-17 until this fix, NO error handler ever fired in production, and the
            // only trace was one ERROR line per failure.
            Map<String, Object> parentPlan = buildPlanWithTrigger("manual", "start");
            WorkflowRunEntity parentRun = createParentRunEntity(parentPlan, false);
            WorkflowExecution execution = createFailedExecution(RunStatus.FAILED);
            when(runRepository.findById(PARENT_WORKFLOW_RUN_ID)).thenReturn(Optional.of(parentRun));

            WorkflowEntity downstream = createDownstreamWorkflow();
            when(triggerLookupService.findByErrorTrigger(PARENT_WORKFLOW_ID.toString()))
                    .thenReturn(List.of(downstream));
            when(runRepository.countByWorkflowIdAndStatus(DOWNSTREAM_WORKFLOW_ID, RunStatus.RUNNING)).thenReturn(0L);

            WorkflowRunEntity downstreamRun = createDownstreamRunEntity(
                    buildPlanWithTrigger("error", PARENT_WORKFLOW_ID.toString()));
            stubActiveRun(downstreamRun);
            when(triggerService.executeTrigger(eq(downstreamRun), any(), eq(TriggerType.ERROR), any()))
                    .thenReturn(TriggerExecutionResult.success(DOWNSTREAM_RUN_ID, "trigger:error_handler", TriggerType.ERROR, Set.of(), 1));

            service.dispatchWorkflowFailure(execution);

            verify(triggerService).executeTrigger(eq(downstreamRun), any(), eq(TriggerType.ERROR), any());
        }

        @Test
        @DisplayName("WORKFLOW_MISSING → skip dispatch")
        void workflowMissingSkipsDispatch() {
            WorkflowEntity downstream = createDownstreamWorkflow(5);
            WorkflowExecution execution = arrangeFailure(downstream);
            when(productionRunResolver.resolveActiveRun(any(WorkflowEntity.class), anyList()))
                    .thenReturn(new ProductionRunResolver.Resolution(
                            Optional.empty(), ProductionRunResolver.Outcome.WORKFLOW_MISSING, null));

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
            stubActiveRun(downstreamRun);

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
            stubActiveRun(downstreamRun);

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
