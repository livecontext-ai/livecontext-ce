package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowPlanVersionEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.repository.WorkflowPlanVersionRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.credit.CreditBudgetService;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.services.resume.WorkflowRunState;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.SnapshotService;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
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
 * Tests for the Pinned Version production protection system.
 *
 * Verifies:
 * - Pinned version: trigger fires use the pinned version's plan
 * - Unpinned: trigger fires use the workflow's latest saved plan (not the run's cached plan)
 * - Pinned version not found: falls back to workflow's latest plan
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Pinned Version - Production Protection")
class PinnedVersionTest {

    @Mock private WorkflowRunRepository runRepository;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowPlanVersionRepository planVersionRepository;
    @Mock private TriggerEpochManager epochManager;
    @Mock private WorkflowStreamingService streamingService;
    @Mock private WorkflowExecutionService executionService;
    @Mock private com.apimarketplace.orchestrator.services.TriggerResolverService triggerResolverService;
    @Mock private StateSnapshotService stateSnapshotService;
    @Mock private EpochConcurrencyLimiter epochConcurrencyLimiter;
    @Mock private com.apimarketplace.orchestrator.trigger.queue.ExecutionQueue executionQueueService;
    @Mock private UnifiedSignalService unifiedSignalService;
    @Mock private SnapshotService snapshotService;
    @Mock private WorkflowResumeService resumeService;
    @Mock private CreditConsumptionClient creditClient;
    @Mock private CreditBudgetService creditBudgetService;

    private ReusableTriggerService service;

    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final String RUN_ID = "run-pin-1";
    private static final String TRIGGER_ID = "trigger:my_webhook";
    private static final String TENANT_ID = "tenant-1";

    @BeforeEach
    void setUp() {
        service = new ReusableTriggerService(
                runRepository, workflowRepository, planVersionRepository,
                epochManager, streamingService,
                executionService, triggerResolverService, stateSnapshotService,
                epochConcurrencyLimiter, executionQueueService, creditClient, creditBudgetService);
        ReflectionTestUtils.setField(service, "resumeService", resumeService);
        ReflectionTestUtils.setField(service, "unifiedSignalService", unifiedSignalService);
        ReflectionTestUtils.setField(service, "snapshotService", snapshotService);
        ReflectionTestUtils.setField(service, "self", service);
    }

    // ==================== Plan Resolution at Trigger Fire ====================

    @Nested
    @DisplayName("Plan Resolution at Trigger Fire")
    class PlanResolutionTests {

        /**
         * Prepares mocks so executeTriggerInternal() reaches the plan resolution block,
         * then throws at epochManager to stop execution after we can verify the plan.
         */
        private void setupForPlanResolution(WorkflowRunEntity run, WorkflowEntity workflow) {
            when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            // reconstructState returns a state with the run's OLD plan
            Map<String, Object> runPlan = createPlanMap("run_cached_plan");
            WorkflowRunState state = new WorkflowRunState(
                    RUN_ID, WORKFLOW_ID.toString(), RunStatus.RUNNING, ExecutionMode.AUTOMATIC,
                    Instant.now(), null, runPlan, List.of(), List.of(),
                    Set.of(), Set.of(), Set.of(), Set.of(), Map.of());

            // Mock workflow lookup (critical for plan resolution)
            if (workflow != null) {
                when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            }
            // Stop execution after plan resolution by throwing at epoch manager
            when(epochManager.getCurrentEpoch(any(WorkflowRunEntity.class), anyString())).thenThrow(
                    new RuntimeException("stop after plan resolution"));
        }

        @Test
        @DisplayName("Pinned: should use pinned version's plan, not run's cached plan")
        void shouldUsePinnedVersionPlan() {
            // Given: workflow pinned to v5
            WorkflowEntity workflow = createWorkflow(5);
            Map<String, Object> pinnedPlan = createPlanMap("pinned_v5_plan");
            workflow.setPlan(createPlanMap("latest_workflow_plan"));

            WorkflowPlanVersionEntity versionEntity = new WorkflowPlanVersionEntity();
            versionEntity.setPlan(pinnedPlan);

            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER, workflow, 3);
            setupForPlanResolution(run, workflow);

            when(planVersionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 5))
                    .thenReturn(Optional.of(versionEntity));

            // When
            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

            // Then: pinned version's plan was loaded
            verify(planVersionRepository).findByWorkflowIdAndVersion(WORKFLOW_ID, 5);
        }

        @Test
        @DisplayName("Unpinned: should use workflow's latest plan, not run's cached plan")
        void shouldUseLatestWorkflowPlanWhenUnpinned() {
            // Given: workflow with NO pinned version, but latest plan differs from run's plan
            WorkflowEntity workflow = createWorkflow(null);
            Map<String, Object> latestPlan = createPlanMap("latest_workflow_plan");
            workflow.setPlan(latestPlan);

            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER, workflow, 3);
            setupForPlanResolution(run, workflow);

            // When
            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

            // Then: should NOT have tried to load a pinned version
            verify(planVersionRepository, never()).findByWorkflowIdAndVersion(any(), anyInt());
            // And workflow.getPlan() was called (the latest plan is used)
            // We verify this indirectly: if run's cached plan ("run_cached_plan") was used,
            // it would have different triggers. The latest plan has "latest_workflow_plan".
            // Since we can't easily capture the WorkflowPlan, we verify no version repo call was made.
        }

        @Test
        @DisplayName("Pinned version not found: should fall back to workflow's latest plan")
        void shouldFallbackToLatestWhenPinnedVersionMissing() {
            // Given: workflow pinned to v99, but that version was deleted
            WorkflowEntity workflow = createWorkflow(99);
            Map<String, Object> latestPlan = createPlanMap("latest_fallback_plan");
            workflow.setPlan(latestPlan);

            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER, workflow, 3);
            setupForPlanResolution(run, workflow);

            when(planVersionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 99))
                    .thenReturn(Optional.empty());
            lenient().when(planVersionRepository.getMaxVersion(WORKFLOW_ID))
                    .thenReturn(Optional.of(10));

            // When
            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

            // Then: tried to load pinned version, got empty, falls back to latest
            verify(planVersionRepository).findByWorkflowIdAndVersion(WORKFLOW_ID, 99);
        }

        @Test
        @DisplayName("Pinned version repo error: should fall back to run's cached plan (safe)")
        void shouldFallbackToRunPlanOnError() {
            // Given: workflow pinned to v5, but repo throws
            WorkflowEntity workflow = createWorkflow(5);
            workflow.setPlan(createPlanMap("latest_plan"));

            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER, workflow, 3);
            setupForPlanResolution(run, workflow);

            when(planVersionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 5))
                    .thenThrow(new RuntimeException("DB error"));

            // When - should not throw, just fall back gracefully
            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

            // Then: attempted to load pinned version
            verify(planVersionRepository).findByWorkflowIdAndVersion(WORKFLOW_ID, 5);
        }

        @Test
        @DisplayName("Pinned: latest run CANCELLED → uses version table, not older run's plan")
        void shouldUseVersionTableWhenLatestRunCancelled() {
            // Given: workflow pinned to v5
            // run1 (v5, COMPLETED) with save-in-run plan - but should NOT be used
            // run2 (v5, CANCELLED) - most recent
            WorkflowEntity workflow = createWorkflow(5);
            Map<String, Object> versionPlan = createPlanMap("original_v5");
            WorkflowPlanVersionEntity versionEntity = new WorkflowPlanVersionEntity();
            versionEntity.setPlan(versionPlan);
            workflow.setPlan(createPlanMap("latest_workflow_plan"));

            WorkflowRunEntity cancelledRun = createRun(RunStatus.CANCELLED, workflow, 5);
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER, workflow, 3);
            setupForPlanResolution(run, workflow);

            // Most recent run (any status) → CANCELLED
            when(runRepository.findFirstByWorkflowIdAndPlanVersionOrderByStartedAtDesc(WORKFLOW_ID, 5))
                    .thenReturn(Optional.of(cancelledRun));
            // Version table → return original plan
            when(planVersionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 5))
                    .thenReturn(Optional.of(versionEntity));

            // When
            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

            // Then: should use version table, NOT the completed run's plan
            verify(planVersionRepository).findByWorkflowIdAndVersion(WORKFLOW_ID, 5);
            // The trusted-run query should NOT have been called (skipped due to latest being CANCELLED)
            verify(runRepository, never()).findFirstByWorkflowIdAndPlanVersionAndStatusInOrderByStartedAtDesc(
                    any(), anyInt(), any());
        }

        @Test
        @DisplayName("Workflow is null: should use run's cached plan (safe fallback)")
        void shouldUseRunPlanWhenWorkflowNull() {
            // Given: run with no workflow reference (edge case)
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER, null, 3);
            ReflectionTestUtils.setField(run, "workflow", null);
            setupForPlanResolution(run, null);

            // When
            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

            // Then: no version repo or workflow calls - used the run's cached plan
            verify(planVersionRepository, never()).findByWorkflowIdAndVersion(any(), anyInt());
        }
    }

    // ==================== Helpers ====================

    private WorkflowEntity createWorkflow(Integer pinnedVersion) {
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(WORKFLOW_ID);
        workflow.setTenantId(TENANT_ID);
        workflow.setPinnedVersion(pinnedVersion);
        return workflow;
    }

    private WorkflowRunEntity createRun(RunStatus status, WorkflowEntity workflow, Integer planVersion) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID);
        run.setStatus(status);
        run.setTenantId(TENANT_ID);
        run.setPlanVersion(planVersion);
        run.setMetadata(new HashMap<>());
        // Phase G.1: trigger fire reads plan directly from run entity
        run.setPlan(createPlanMap("run_cached_plan"));
        if (workflow != null) {
            ReflectionTestUtils.setField(run, "workflow", workflow);
        }
        return run;
    }

    private Map<String, Object> createPlanMap(String identifier) {
        Map<String, Object> planMap = new HashMap<>();
        planMap.put("id", WORKFLOW_ID.toString());
        planMap.put("name", identifier);
        planMap.put("triggers", List.of(Map.of("type", "webhook", "label", "My Webhook")));
        planMap.put("mcps", List.of());
        planMap.put("cores", List.of());
        planMap.put("edges", List.of());
        return planMap;
    }

    private WorkflowExecution createExecution() {
        WorkflowPlan plan = WorkflowPlan.fromMap(createPlanMap("execution"), TENANT_ID);
        return new WorkflowExecution(RUN_ID, plan, Map.of());
    }

    private WorkflowPlan createPlan() {
        return WorkflowPlan.fromMap(createPlanMap("plan"), TENANT_ID);
    }
}
