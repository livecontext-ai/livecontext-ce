package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionStatistics;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.persistence.StepPayloadService;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WorkflowTriggerDispatchService")
class WorkflowTriggerDispatchServiceTest {

    @Mock private WorkflowTriggerLookupService triggerLookupService;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private ReusableTriggerService triggerService;
    @Mock private ProductionRunResolver productionRunResolver;
    @Mock private StepPayloadService stepPayloadService;

    private WorkflowTriggerDispatchService service;

    private static final UUID PARENT_WORKFLOW_ID = UUID.randomUUID();
    private static final UUID PARENT_WORKFLOW_RUN_ID = UUID.randomUUID();
    private static final String PARENT_RUN_ID = "run-parent-123";
    private static final UUID DOWNSTREAM_WORKFLOW_ID = UUID.randomUUID();
    private static final String DOWNSTREAM_RUN_ID = "run-downstream-456";
    private static final String TENANT_ID = "tenant-test";

    @BeforeEach
    void setUp() {
        service = new WorkflowTriggerDispatchService(
                triggerLookupService, runRepository, triggerService, productionRunResolver, stepPayloadService);
    }

    private Map<String, Object> buildPlanWithTrigger(String triggerType, String triggerId) {
        Map<String, Object> trigger = new HashMap<>();
        trigger.put("type", triggerType);
        trigger.put("id", triggerId);
        trigger.put("label", "trigger_label");
        trigger.put("strategy", "single");

        Map<String, Object> plan = new HashMap<>();
        plan.put("triggers", List.of(trigger));
        plan.put("mcps", List.of());
        plan.put("edges", List.of());
        plan.put("cores", List.of());
        return plan;
    }

    private WorkflowExecution createCompletedExecution() {
        WorkflowExecution execution = mock(WorkflowExecution.class);
        when(execution.getRunId()).thenReturn(PARENT_RUN_ID);
        when(execution.getStatus()).thenReturn(RunStatus.COMPLETED);
        when(execution.getWorkflowRunId()).thenReturn(PARENT_WORKFLOW_RUN_ID);
        when(execution.getStepOutputs()).thenReturn(Map.of("result", "ok"));
        ExecutionStatistics stats = new ExecutionStatistics(
                5, 5, 0, 0, 0, 2000L, RunStatus.COMPLETED, 1, 1, Map.of());
        when(execution.getStatistics()).thenReturn(stats);
        return execution;
    }

    private WorkflowRunEntity createParentRunEntity() {
        return createParentRunEntity(true);
    }

    /**
     * @param workflowFieldsUsable when false the workflow proxy throws on every accessor
     *                             but getId(), which is what an uninitialised Hibernate
     *                             proxy does on these {@code @Async} dispatch threads
     *                             (open-in-view is off). The workspace must therefore be
     *                             read from the RUN, which carries both columns.
     */
    private WorkflowRunEntity createParentRunEntity(boolean workflowFieldsUsable) {
        WorkflowRunEntity runEntity = mock(WorkflowRunEntity.class);
        WorkflowEntity parentWorkflow = mock(WorkflowEntity.class);
        when(parentWorkflow.getId()).thenReturn(PARENT_WORKFLOW_ID);
        if (workflowFieldsUsable) {
            lenient().when(parentWorkflow.getTenantId()).thenReturn(TENANT_ID);
            lenient().when(parentWorkflow.getOrganizationId()).thenReturn(null);
        } else {
            lenient().when(parentWorkflow.getTenantId()).thenThrow(
                new org.hibernate.LazyInitializationException("could not initialize proxy - no Session"));
            lenient().when(parentWorkflow.getOrganizationId()).thenThrow(
                new org.hibernate.LazyInitializationException("could not initialize proxy - no Session"));
        }
        when(runEntity.getWorkflow()).thenReturn(parentWorkflow);
        when(runEntity.getPlan()).thenReturn(buildPlanWithTrigger("manual", "start"));
        lenient().when(runEntity.getTenantId()).thenReturn(TENANT_ID);
        lenient().when(runEntity.getOrganizationId()).thenReturn(null);
        return runEntity;
    }

    private WorkflowEntity createDownstreamWorkflow(Integer pinnedVersion) {
        WorkflowEntity workflow = mock(WorkflowEntity.class);
        when(workflow.getId()).thenReturn(DOWNSTREAM_WORKFLOW_ID);
        when(workflow.getName()).thenReturn("Downstream Workflow");
        when(workflow.getPinnedVersion()).thenReturn(pinnedVersion);
        when(workflow.getTenantId()).thenReturn(TENANT_ID);
        when(workflow.getOrganizationId()).thenReturn(null);
        return workflow;
    }

    private WorkflowRunEntity createDownstreamRun(RunStatus status) {
        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        when(run.getRunIdPublic()).thenReturn(DOWNSTREAM_RUN_ID);
        when(run.getStatus()).thenReturn(status);
        when(run.getPlan()).thenReturn(buildPlanWithTrigger("workflow", PARENT_WORKFLOW_ID.toString()));
        return run;
    }

    private ProductionRunResolver.Resolution foundResolution(WorkflowRunEntity run) {
        return new ProductionRunResolver.Resolution(
                Optional.of(run), ProductionRunResolver.Outcome.FOUND, "Downstream Workflow");
    }

    private ProductionRunResolver.Resolution emptyResolution(ProductionRunResolver.Outcome outcome) {
        return new ProductionRunResolver.Resolution(Optional.empty(), outcome, "Downstream Workflow");
    }

    private void stubLatestTrustedResolution(ProductionRunResolver.Resolution resolution) {
        when(productionRunResolver.resolve(
                eq(DOWNSTREAM_WORKFLOW_ID),
                eq(ProductionRunResolver.RunSelectionPolicy.LATEST_TRUSTED)))
                .thenReturn(resolution);
    }

    // ==================== Version-Aware Dispatch ====================

    @Nested
    @DisplayName("Version-Aware Dispatch")
    class VersionAwareTests {

        @Test
        @DisplayName("REGRESSION: completion dispatch survives an uninitialised parent-workflow proxy")
        void completionDispatchSurvivesLazyProxy() {
            // Both dispatch methods here read the parent's workspace, and both run @Async
            // with open-in-view off, so runEntity.getWorkflow() is a proxy that throws on
            // every field but the id. Reading the workspace off it killed the whole
            // dispatch inside the outer catch - the same defect that left error handlers
            // dead since 2026-05-17. Chained workflow triggers were hit identically.
            WorkflowExecution execution = createCompletedExecution();
            WorkflowRunEntity parentRun = createParentRunEntity(false);
            when(runRepository.findById(PARENT_WORKFLOW_RUN_ID)).thenReturn(Optional.of(parentRun));

            WorkflowEntity downstream = createDownstreamWorkflow(3);
            when(triggerLookupService.findByWorkflowTrigger(PARENT_WORKFLOW_ID.toString()))
                    .thenReturn(List.of(downstream));
            when(runRepository.countByWorkflowIdAndStatus(DOWNSTREAM_WORKFLOW_ID, RunStatus.RUNNING)).thenReturn(0L);
            stubLatestTrustedResolution(emptyResolution(ProductionRunResolver.Outcome.NOT_PINNED));

            service.dispatchWorkflowCompletion(execution);

            // Reaching the resolver at all proves the guard no longer aborts the dispatch.
            verify(productionRunResolver).resolve(
                    eq(DOWNSTREAM_WORKFLOW_ID),
                    eq(ProductionRunResolver.RunSelectionPolicy.LATEST_TRUSTED));
        }

        @Test
        @DisplayName("Unpinned downstream: should refuse production dispatch")
        void unpinnedRefusesProductionDispatch() {
            WorkflowExecution execution = createCompletedExecution();
            WorkflowRunEntity parentRun = createParentRunEntity();
            when(runRepository.findById(PARENT_WORKFLOW_RUN_ID)).thenReturn(Optional.of(parentRun));

            WorkflowEntity downstream = createDownstreamWorkflow(null);
            when(triggerLookupService.findByWorkflowTrigger(PARENT_WORKFLOW_ID.toString()))
                    .thenReturn(List.of(downstream));
            when(runRepository.countByWorkflowIdAndStatus(DOWNSTREAM_WORKFLOW_ID, RunStatus.RUNNING)).thenReturn(0L);
            stubLatestTrustedResolution(emptyResolution(ProductionRunResolver.Outcome.NOT_PINNED));

            service.dispatchWorkflowCompletion(execution);

            verify(productionRunResolver).resolve(
                    eq(DOWNSTREAM_WORKFLOW_ID),
                    eq(ProductionRunResolver.RunSelectionPolicy.LATEST_TRUSTED));
            verifyNoInteractions(triggerService);
        }

        @Test
        @DisplayName("Pinned downstream: should dispatch resolved production run")
        void pinnedDispatchesResolvedProductionRun() {
            WorkflowExecution execution = createCompletedExecution();
            WorkflowRunEntity parentRun = createParentRunEntity();
            when(runRepository.findById(PARENT_WORKFLOW_RUN_ID)).thenReturn(Optional.of(parentRun));

            WorkflowEntity downstream = createDownstreamWorkflow(7);
            when(triggerLookupService.findByWorkflowTrigger(PARENT_WORKFLOW_ID.toString()))
                    .thenReturn(List.of(downstream));
            when(runRepository.countByWorkflowIdAndStatus(DOWNSTREAM_WORKFLOW_ID, RunStatus.RUNNING)).thenReturn(0L);

            WorkflowRunEntity downstreamRun = createDownstreamRun(RunStatus.WAITING_TRIGGER);
            stubLatestTrustedResolution(foundResolution(downstreamRun));
            when(triggerService.executeTrigger(eq(downstreamRun), any(), eq(TriggerType.WORKFLOW), any()))
                    .thenReturn(TriggerExecutionResult.success(DOWNSTREAM_RUN_ID, "trigger:trigger_label", TriggerType.WORKFLOW, Set.of(), 1));

            service.dispatchWorkflowCompletion(execution);

            verify(productionRunResolver).resolve(
                    eq(DOWNSTREAM_WORKFLOW_ID),
                    eq(ProductionRunResolver.RunSelectionPolicy.LATEST_TRUSTED));
            verify(triggerService).executeTrigger(eq(downstreamRun), any(), eq(TriggerType.WORKFLOW), any());
        }

        @Test
        @DisplayName("Pinned downstream: no matching run → skip dispatch")
        void pinnedNoMatchSkipsDispatch() {
            WorkflowExecution execution = createCompletedExecution();
            WorkflowRunEntity parentRun = createParentRunEntity();
            when(runRepository.findById(PARENT_WORKFLOW_RUN_ID)).thenReturn(Optional.of(parentRun));

            WorkflowEntity downstream = createDownstreamWorkflow(5);
            when(triggerLookupService.findByWorkflowTrigger(PARENT_WORKFLOW_ID.toString()))
                    .thenReturn(List.of(downstream));
            when(runRepository.countByWorkflowIdAndStatus(DOWNSTREAM_WORKFLOW_ID, RunStatus.RUNNING)).thenReturn(0L);
            stubLatestTrustedResolution(emptyResolution(ProductionRunResolver.Outcome.NO_PRODUCTION_RUN));

            service.dispatchWorkflowCompletion(execution);

            verify(productionRunResolver).resolve(
                    eq(DOWNSTREAM_WORKFLOW_ID),
                    eq(ProductionRunResolver.RunSelectionPolicy.LATEST_TRUSTED));
            verifyNoInteractions(triggerService);
        }

        @Test
        @DisplayName("Pinned v0: zero is a valid version")
        void pinnedVersionZeroIsValid() {
            WorkflowExecution execution = createCompletedExecution();
            WorkflowRunEntity parentRun = createParentRunEntity();
            when(runRepository.findById(PARENT_WORKFLOW_RUN_ID)).thenReturn(Optional.of(parentRun));

            WorkflowEntity downstream = createDownstreamWorkflow(0);
            when(triggerLookupService.findByWorkflowTrigger(PARENT_WORKFLOW_ID.toString()))
                    .thenReturn(List.of(downstream));
            when(runRepository.countByWorkflowIdAndStatus(DOWNSTREAM_WORKFLOW_ID, RunStatus.RUNNING)).thenReturn(0L);

            WorkflowRunEntity downstreamRun = createDownstreamRun(RunStatus.WAITING_TRIGGER);
            stubLatestTrustedResolution(foundResolution(downstreamRun));
            when(triggerService.executeTrigger(eq(downstreamRun), any(), eq(TriggerType.WORKFLOW), any()))
                    .thenReturn(TriggerExecutionResult.success(DOWNSTREAM_RUN_ID, "trigger:trigger_label", TriggerType.WORKFLOW, Set.of(), 1));

            service.dispatchWorkflowCompletion(execution);

            verify(productionRunResolver).resolve(
                    eq(DOWNSTREAM_WORKFLOW_ID),
                    eq(ProductionRunResolver.RunSelectionPolicy.LATEST_TRUSTED));
            verify(triggerService).executeTrigger(eq(downstreamRun), any(), eq(TriggerType.WORKFLOW), any());
        }
    }

    // ==================== Terminal Run Rejection ====================

    @Nested
    @DisplayName("Terminal Run Rejection")
    class TerminalRunTests {

        @Test
        @DisplayName("CANCELLED downstream run → skip dispatch")
        void cancelledRunSkipsDispatch() {
            WorkflowExecution execution = createCompletedExecution();
            WorkflowRunEntity parentRun = createParentRunEntity();
            when(runRepository.findById(PARENT_WORKFLOW_RUN_ID)).thenReturn(Optional.of(parentRun));

            WorkflowEntity downstream = createDownstreamWorkflow(1);
            when(triggerLookupService.findByWorkflowTrigger(PARENT_WORKFLOW_ID.toString()))
                    .thenReturn(List.of(downstream));
            when(runRepository.countByWorkflowIdAndStatus(DOWNSTREAM_WORKFLOW_ID, RunStatus.RUNNING)).thenReturn(0L);

            WorkflowRunEntity cancelledRun = createDownstreamRun(RunStatus.CANCELLED);
            stubLatestTrustedResolution(foundResolution(cancelledRun));

            service.dispatchWorkflowCompletion(execution);

            verifyNoInteractions(triggerService);
        }

        @Test
        @DisplayName("TIMEOUT downstream run → skip dispatch")
        void timeoutRunSkipsDispatch() {
            WorkflowExecution execution = createCompletedExecution();
            WorkflowRunEntity parentRun = createParentRunEntity();
            when(runRepository.findById(PARENT_WORKFLOW_RUN_ID)).thenReturn(Optional.of(parentRun));

            WorkflowEntity downstream = createDownstreamWorkflow(1);
            when(triggerLookupService.findByWorkflowTrigger(PARENT_WORKFLOW_ID.toString()))
                    .thenReturn(List.of(downstream));
            when(runRepository.countByWorkflowIdAndStatus(DOWNSTREAM_WORKFLOW_ID, RunStatus.RUNNING)).thenReturn(0L);

            WorkflowRunEntity timeoutRun = createDownstreamRun(RunStatus.TIMEOUT);
            stubLatestTrustedResolution(foundResolution(timeoutRun));

            service.dispatchWorkflowCompletion(execution);

            verifyNoInteractions(triggerService);
        }

        @Test
        @DisplayName("Every isTerminal() status is rejected - FAILED, COMPLETED, PARTIAL_SUCCESS, SKIPPED added")
        void allTerminalStatusesRefused() {
            // Regression for prod 2026-05-07 12:40 UTC: this dispatcher used to accept
            // FAILED/COMPLETED runs (the legacy gate listed only CANCELLED|TIMEOUT, with a
            // misleading comment claiming alignment with WebhookDispatchService - which
            // already had the same bug). After a JVM crash mid-cycle, the run stayed FAILED
            // and chained workflow-trigger fires kept reopening epochs on the dead run.
            for (RunStatus terminal : new RunStatus[]{
                    RunStatus.FAILED, RunStatus.COMPLETED,
                    RunStatus.PARTIAL_SUCCESS, RunStatus.SKIPPED}) {
                WorkflowExecution execution = createCompletedExecution();
                WorkflowRunEntity parentRun = createParentRunEntity();
                when(runRepository.findById(PARENT_WORKFLOW_RUN_ID)).thenReturn(Optional.of(parentRun));
                WorkflowEntity downstream = createDownstreamWorkflow(1);
                when(triggerLookupService.findByWorkflowTrigger(PARENT_WORKFLOW_ID.toString()))
                        .thenReturn(List.of(downstream));
                when(runRepository.countByWorkflowIdAndStatus(DOWNSTREAM_WORKFLOW_ID, RunStatus.RUNNING)).thenReturn(0L);
                WorkflowRunEntity terminalRun = createDownstreamRun(terminal);
                stubLatestTrustedResolution(foundResolution(terminalRun));

                service.dispatchWorkflowCompletion(execution);
            }

            verifyNoInteractions(triggerService);
        }

        @Test
        @DisplayName("WAITING_TRIGGER run → accepted")
        void waitingTriggerRunIsAccepted() {
            WorkflowExecution execution = createCompletedExecution();
            WorkflowRunEntity parentRun = createParentRunEntity();
            when(runRepository.findById(PARENT_WORKFLOW_RUN_ID)).thenReturn(Optional.of(parentRun));

            WorkflowEntity downstream = createDownstreamWorkflow(1);
            when(triggerLookupService.findByWorkflowTrigger(PARENT_WORKFLOW_ID.toString()))
                    .thenReturn(List.of(downstream));
            when(runRepository.countByWorkflowIdAndStatus(DOWNSTREAM_WORKFLOW_ID, RunStatus.RUNNING)).thenReturn(0L);

            WorkflowRunEntity downstreamRun = createDownstreamRun(RunStatus.WAITING_TRIGGER);
            stubLatestTrustedResolution(foundResolution(downstreamRun));
            when(triggerService.executeTrigger(eq(downstreamRun), any(), eq(TriggerType.WORKFLOW), any()))
                    .thenReturn(TriggerExecutionResult.success(DOWNSTREAM_RUN_ID, "trigger:trigger_label", TriggerType.WORKFLOW, Set.of(), 1));

            service.dispatchWorkflowCompletion(execution);

            verify(triggerService).executeTrigger(eq(downstreamRun), any(), eq(TriggerType.WORKFLOW), any());
        }
    }

    // ==================== Guard Conditions ====================

    @Nested
    @DisplayName("Guard Conditions")
    class GuardTests {

        @Test
        @DisplayName("Null execution → no dispatch")
        void nullExecution() {
            service.dispatchWorkflowCompletion(null);
            verifyNoInteractions(runRepository);
            verifyNoInteractions(triggerService);
        }

        @Test
        @DisplayName("Non-COMPLETED status → no dispatch")
        void nonCompletedStatus() {
            WorkflowExecution execution = mock(WorkflowExecution.class);
            when(execution.getRunId()).thenReturn(PARENT_RUN_ID);
            when(execution.getStatus()).thenReturn(RunStatus.FAILED);

            service.dispatchWorkflowCompletion(execution);

            verify(runRepository, never()).findById(any());
            verifyNoInteractions(triggerService);
        }

        @Test
        @DisplayName("No downstream workflows → no dispatch")
        void noDownstreamWorkflows() {
            WorkflowExecution execution = createCompletedExecution();
            WorkflowRunEntity parentRun = createParentRunEntity();
            when(runRepository.findById(PARENT_WORKFLOW_RUN_ID)).thenReturn(Optional.of(parentRun));
            when(triggerLookupService.findByWorkflowTrigger(PARENT_WORKFLOW_ID.toString()))
                    .thenReturn(List.of());

            service.dispatchWorkflowCompletion(execution);

            verifyNoInteractions(triggerService);
        }

        @Test
        @DisplayName("Concurrent runs limit reached → skip dispatch")
        void concurrentLimitReached() {
            WorkflowExecution execution = createCompletedExecution();
            WorkflowRunEntity parentRun = createParentRunEntity();
            when(runRepository.findById(PARENT_WORKFLOW_RUN_ID)).thenReturn(Optional.of(parentRun));

            WorkflowEntity downstream = createDownstreamWorkflow(null);
            when(triggerLookupService.findByWorkflowTrigger(PARENT_WORKFLOW_ID.toString()))
                    .thenReturn(List.of(downstream));
            when(runRepository.countByWorkflowIdAndStatus(DOWNSTREAM_WORKFLOW_ID, RunStatus.RUNNING)).thenReturn(5L);

            service.dispatchWorkflowCompletion(execution);

            verifyNoInteractions(triggerService);
        }
    }

    // ==================== LC-066: restricted parent taint ====================

    /**
     * A chained workflow receives its parent's step outputs as its trigger payload, so a parent
     * run that read Gmail / Drive hands that content to the downstream run. The downstream fire
     * must then carry {@link ReusableTriggerService#RESTRICTED_DATA_MARKER}, exactly as a
     * restricted parent taints a sub-workflow; before, the downstream run stored it all NORMAL.
     */
    @Nested
    @DisplayName("a restricted parent run makes the downstream fire carry the restricted-data marker")
    class RestrictedParentTaint {

        private WorkflowRunEntity downstreamReadyToFire() {
            WorkflowRunEntity parentRun = createParentRunEntity();
            when(runRepository.findById(PARENT_WORKFLOW_RUN_ID)).thenReturn(Optional.of(parentRun));
            WorkflowEntity downstream = createDownstreamWorkflow(7);
            when(triggerLookupService.findByWorkflowTrigger(PARENT_WORKFLOW_ID.toString()))
                    .thenReturn(List.of(downstream));
            when(runRepository.countByWorkflowIdAndStatus(DOWNSTREAM_WORKFLOW_ID, RunStatus.RUNNING)).thenReturn(0L);
            WorkflowRunEntity downstreamRun = createDownstreamRun(RunStatus.WAITING_TRIGGER);
            stubLatestTrustedResolution(foundResolution(downstreamRun));
            when(triggerService.executeTrigger(eq(downstreamRun), any(), eq(TriggerType.WORKFLOW), any()))
                    .thenReturn(TriggerExecutionResult.success(DOWNSTREAM_RUN_ID, "trigger:trigger_label",
                            TriggerType.WORKFLOW, Set.of(), 1));
            return downstreamRun;
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> firedPayload(WorkflowRunEntity downstreamRun) {
            ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
            verify(triggerService).executeTrigger(eq(downstreamRun), any(), eq(TriggerType.WORKFLOW), payload.capture());
            return payload.getValue();
        }

        @Test
        @DisplayName("regression: completion of a restricted parent fires the downstream run with the marker and the parent's result")
        void restrictedParentCompletionCarriesTheMarker() {
            WorkflowRunEntity downstreamRun = downstreamReadyToFire();
            when(stepPayloadService.isRunRestricted(PARENT_RUN_ID)).thenReturn(true);

            service.dispatchWorkflowCompletion(createCompletedExecution());

            Map<String, Object> payload = firedPayload(downstreamRun);
            assertThat(payload).containsEntry(ReusableTriggerService.RESTRICTED_DATA_MARKER, Boolean.TRUE);
            assertThat(payload).containsEntry("result", Map.of("result", "ok"));
        }

        @Test
        @DisplayName("regression: an epoch completion of a restricted reusable-trigger parent carries the marker too")
        void restrictedParentCycleCompletionCarriesTheMarker() {
            WorkflowRunEntity downstreamRun = downstreamReadyToFire();
            when(stepPayloadService.isRunRestricted(PARENT_RUN_ID)).thenReturn(true);

            service.dispatchCycleCompletion(PARENT_RUN_ID, PARENT_WORKFLOW_RUN_ID, Map.of("gmail", "inbox"), false);

            assertThat(firedPayload(downstreamRun))
                    .containsEntry(ReusableTriggerService.RESTRICTED_DATA_MARKER, Boolean.TRUE);
        }

        @Test
        @DisplayName("a parent that holds no restricted data fires the downstream run without the marker")
        void normalParentCarriesNoMarker() {
            WorkflowRunEntity downstreamRun = downstreamReadyToFire();
            when(stepPayloadService.isRunRestricted(PARENT_RUN_ID)).thenReturn(false);

            service.dispatchWorkflowCompletion(createCompletedExecution());

            assertThat(firedPayload(downstreamRun))
                    .doesNotContainKey(ReusableTriggerService.RESTRICTED_DATA_MARKER);
        }
    }
}
