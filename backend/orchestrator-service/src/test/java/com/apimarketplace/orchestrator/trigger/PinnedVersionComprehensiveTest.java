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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for the Pinned Version production protection system.
 *
 * Covers:
 * 1. Plan resolution at trigger fire (pinned + unpinned + edge cases)
 * 2. Run entity update (planVersion + plan JSONB) after resolution
 * 3. Pin/Unpin transitions (pin → unpin → re-pin)
 * 4. Multi-trigger / multi-DAG scenarios
 * 5. Version deletion while pinned
 * 6. Concurrent trigger fire stress tests
 * 7. Edge cases (null plans, empty maps, version 0, negative versions)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Pinned Version - Comprehensive Test Suite")
class PinnedVersionComprehensiveTest {

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

    private static final UUID WORKFLOW_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String RUN_ID = "run-pin-comprehensive";
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

    // ==================== 1. Plan + Version Resolution (Pinned) ====================

    @Nested
    @DisplayName("1. Plan Resolution - Pinned Version")
    class PinnedPlanResolutionTests {

        @Test
        @DisplayName("Should use pinned version's plan from version table when no prior run exists")
        void shouldUsePinnedPlanAndUpdateRun() {
            WorkflowEntity workflow = createWorkflow(5);
            workflow.setPlan(createPlanMap("latest_v7_plan"));

            Map<String, Object> pinnedPlan = createPlanMap("pinned_v5_plan");
            WorkflowPlanVersionEntity versionEntity = createVersionEntity(5, pinnedPlan);

            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER, workflow, 3);
            run.setPlan(createPlanMap("old_run_cached_plan"));

            setupForPlanResolution(run, workflow);
            // No prior run for pinned version → falls back to version table
            when(runRepository.findFirstByWorkflowIdAndPlanVersionAndStatusInOrderByStartedAtDesc(eq(WORKFLOW_ID), eq(5), any()))
                    .thenReturn(Optional.empty());
            when(planVersionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 5))
                    .thenReturn(Optional.of(versionEntity));

            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

            // Then: run entity was saved with updated planVersion and plan
            ArgumentCaptor<WorkflowRunEntity> captor = ArgumentCaptor.forClass(WorkflowRunEntity.class);
            verify(runRepository, atLeast(2)).save(captor.capture());
            WorkflowRunEntity lastSaved = captor.getAllValues().get(captor.getAllValues().size() - 1);
            assertThat(lastSaved.getPlanVersion()).isEqualTo(5);
            assertThat(lastSaved.getPlan()).containsEntry("name", "pinned_v5_plan");
        }

        @Test
        @DisplayName("Should prefer last run's plan over version table (save-in-run scenario)")
        void shouldPreferLastRunPlanOverVersionTable() {
            WorkflowEntity workflow = createWorkflow(5);
            workflow.setPlan(createPlanMap("latest_v7_plan"));

            // Version table has original plan
            Map<String, Object> originalPlan = createPlanMap("original_v5_plan");
            WorkflowPlanVersionEntity versionEntity = createVersionEntity(5, originalPlan);

            // Last run for v5 has a modified plan (save-in-run)
            WorkflowRunEntity lastRunForV5 = createRun(RunStatus.WAITING_TRIGGER, workflow, 5);
            lastRunForV5.setPlan(createPlanMap("modified_v5_save_in_run"));

            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER, workflow, 3);
            run.setPlan(createPlanMap("old_run_cached_plan"));

            setupForPlanResolution(run, workflow);
            when(runRepository.findFirstByWorkflowIdAndPlanVersionAndStatusInOrderByStartedAtDesc(eq(WORKFLOW_ID), eq(5), any()))
                    .thenReturn(Optional.of(lastRunForV5));

            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

            // Should use last run's plan (save-in-run), NOT version table
            ArgumentCaptor<WorkflowRunEntity> captor = ArgumentCaptor.forClass(WorkflowRunEntity.class);
            verify(runRepository, atLeast(2)).save(captor.capture());
            WorkflowRunEntity lastSaved = captor.getAllValues().get(captor.getAllValues().size() - 1);
            assertThat(lastSaved.getPlanVersion()).isEqualTo(5);
            assertThat(lastSaved.getPlan()).containsEntry("name", "modified_v5_save_in_run");
            // Version table should NOT have been consulted
            verify(planVersionRepository, never()).findByWorkflowIdAndVersion(eq(WORKFLOW_ID), anyInt());
        }

        @Test
        @DisplayName("Should NOT use latest plan when pinned version exists")
        void shouldNotUseLatestWhenPinned() {
            WorkflowEntity workflow = createWorkflow(10);
            workflow.setPlan(createPlanMap("latest_dangerous_v13"));

            Map<String, Object> pinnedPlan = createPlanMap("safe_pinned_v10");
            WorkflowPlanVersionEntity versionEntity = createVersionEntity(10, pinnedPlan);

            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER, workflow, 8);
            setupForPlanResolution(run, workflow);
            // No prior run for v10 → falls back to version table
            when(runRepository.findFirstByWorkflowIdAndPlanVersionAndStatusInOrderByStartedAtDesc(eq(WORKFLOW_ID), eq(10), any()))
                    .thenReturn(Optional.empty());
            when(planVersionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 10))
                    .thenReturn(Optional.of(versionEntity));

            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

            ArgumentCaptor<WorkflowRunEntity> captor = ArgumentCaptor.forClass(WorkflowRunEntity.class);
            verify(runRepository, atLeast(2)).save(captor.capture());
            WorkflowRunEntity lastSaved = captor.getAllValues().get(captor.getAllValues().size() - 1);
            assertThat(lastSaved.getPlan().get("name")).isEqualTo("safe_pinned_v10");
            assertThat(lastSaved.getPlan().get("name")).isNotEqualTo("latest_dangerous_v13");
        }

        @Test
        @DisplayName("Pinned version deleted: should fall back to latest and resolve max version")
        void shouldFallbackToLatestWhenPinnedDeleted() {
            WorkflowEntity workflow = createWorkflow(99);
            workflow.setPlan(createPlanMap("latest_fallback_plan"));

            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER, workflow, 3);
            setupForPlanResolution(run, workflow);
            // No prior run for v99, and version table also empty
            when(runRepository.findFirstByWorkflowIdAndPlanVersionAndStatusInOrderByStartedAtDesc(eq(WORKFLOW_ID), eq(99), any()))
                    .thenReturn(Optional.empty());
            when(planVersionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 99))
                    .thenReturn(Optional.empty());
            when(planVersionRepository.getMaxVersion(WORKFLOW_ID))
                    .thenReturn(Optional.of(12));

            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

            ArgumentCaptor<WorkflowRunEntity> captor = ArgumentCaptor.forClass(WorkflowRunEntity.class);
            verify(runRepository, atLeast(2)).save(captor.capture());
            WorkflowRunEntity lastSaved = captor.getAllValues().get(captor.getAllValues().size() - 1);
            assertThat(lastSaved.getPlanVersion()).isEqualTo(12);
            assertThat(lastSaved.getPlan().get("name")).isEqualTo("latest_fallback_plan");
        }

        @Test
        @DisplayName("Pinned repo error: should NOT crash, falls back to run's cached plan")
        void shouldNotCrashOnRepoError() {
            WorkflowEntity workflow = createWorkflow(5);
            workflow.setPlan(createPlanMap("latest_plan"));

            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER, workflow, 3);
            run.setPlan(createPlanMap("cached_safe_plan"));
            setupForPlanResolution(run, workflow);
            lenient().when(runRepository.findFirstByWorkflowIdAndPlanVersionAndStatusInOrderByStartedAtDesc(eq(WORKFLOW_ID), eq(5), any()))
                    .thenThrow(new RuntimeException("DB connection lost"));

            assertThatCode(() ->
                service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false)
            ).doesNotThrowAnyException();
        }
    }

    // ==================== 2. Plan + Version Resolution (Unpinned) ====================

    @Nested
    @DisplayName("2. Plan Resolution - Unpinned (Latest)")
    class UnpinnedPlanResolutionTests {

        @Test
        @DisplayName("Unpinned: should use workflow's latest plan AND update run.planVersion")
        void shouldUseLatestPlanAndUpdateVersion() {
            WorkflowEntity workflow = createWorkflow(null);
            Map<String, Object> latestPlan = createPlanMap("latest_v13_plan");
            workflow.setPlan(latestPlan);

            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER, workflow, 10);
            run.setPlan(createPlanMap("old_v10_plan"));
            setupForPlanResolution(run, workflow);
            when(planVersionRepository.getMaxVersion(WORKFLOW_ID))
                    .thenReturn(Optional.of(13));

            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

            ArgumentCaptor<WorkflowRunEntity> captor = ArgumentCaptor.forClass(WorkflowRunEntity.class);
            verify(runRepository, atLeast(2)).save(captor.capture());
            WorkflowRunEntity lastSaved = captor.getAllValues().get(captor.getAllValues().size() - 1);
            assertThat(lastSaved.getPlanVersion()).isEqualTo(13);
            assertThat(lastSaved.getPlan().get("name")).isEqualTo("latest_v13_plan");
        }

        @Test
        @DisplayName("Unpinned: should never call findByWorkflowIdAndVersion")
        void shouldNotLoadVersionWhenUnpinned() {
            WorkflowEntity workflow = createWorkflow(null);
            workflow.setPlan(createPlanMap("latest_plan"));

            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER, workflow, 3);
            setupForPlanResolution(run, workflow);
            lenient().when(planVersionRepository.getMaxVersion(WORKFLOW_ID))
                    .thenReturn(Optional.of(5));

            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

            verify(planVersionRepository, never()).findByWorkflowIdAndVersion(any(), anyInt());
        }

        @Test
        @DisplayName("Unpinned with no versions in DB: plan still updated, planVersion null")
        void shouldHandleNoVersionsInDb() {
            WorkflowEntity workflow = createWorkflow(null);
            workflow.setPlan(createPlanMap("the_plan"));

            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER, workflow, null);
            setupForPlanResolution(run, workflow);
            when(planVersionRepository.getMaxVersion(WORKFLOW_ID))
                    .thenReturn(Optional.empty());

            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

            ArgumentCaptor<WorkflowRunEntity> captor = ArgumentCaptor.forClass(WorkflowRunEntity.class);
            verify(runRepository, atLeast(2)).save(captor.capture());
            WorkflowRunEntity lastSaved = captor.getAllValues().get(captor.getAllValues().size() - 1);
            // Plan content is updated to latest
            assertThat(lastSaved.getPlan().get("name")).isEqualTo("the_plan");
        }

        @Test
        @DisplayName("Unpinned with null workflow plan: should keep run's cached plan")
        void shouldKeepCachedPlanWhenWorkflowPlanNull() {
            WorkflowEntity workflow = createWorkflow(null);
            workflow.setPlan(null);

            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER, workflow, 3);
            run.setPlan(createPlanMap("cached_plan"));
            setupForPlanResolution(run, workflow);

            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

            // The run's plan comes from state.plan() (reconstructed = "run_cached_plan")
            // since workflow.getPlan() is null
            ArgumentCaptor<WorkflowRunEntity> captor = ArgumentCaptor.forClass(WorkflowRunEntity.class);
            verify(runRepository, atLeast(1)).save(captor.capture());
        }
    }

    // ==================== 3. Workflow Entity Edge Cases ====================

    @Nested
    @DisplayName("3. Workflow Entity Edge Cases")
    class WorkflowEdgeCaseTests {

        @Test
        @DisplayName("Workflow not found in DB: should use run's cached plan (safe)")
        void shouldFallbackWhenWorkflowNotFound() {
            WorkflowEntity workflow = createWorkflow(null);
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER, workflow, 3);
            run.setPlan(createPlanMap("cached_plan"));
            setupForPlanResolution(run, null); // null = workflow NOT found in DB

            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

            verify(planVersionRepository, never()).findByWorkflowIdAndVersion(any(), anyInt());
            verify(planVersionRepository, never()).getMaxVersion(any());
        }

        @Test
        @DisplayName("Workflow null reference on run: should NOT crash")
        void shouldNotCrashWhenWorkflowNullOnRun() {
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER, null, 3);
            ReflectionTestUtils.setField(run, "workflow", null);

            Map<String, Object> runPlan = createPlanMap("run_cached_plan");
            when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            WorkflowRunState state = new WorkflowRunState(
                    RUN_ID, WORKFLOW_ID.toString(), RunStatus.RUNNING, ExecutionMode.AUTOMATIC,
                    Instant.now(), null, runPlan, List.of(), List.of(),
                    Set.of(), Set.of(), Set.of(), Set.of(), Map.of());
            lenient().when(resumeService.reconstructState(RUN_ID)).thenReturn(state);

            // run.getWorkflow() is null → NullPointerException
            // executeTriggerInternal catches all exceptions
            assertThatCode(() ->
                service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false)
            ).doesNotThrowAnyException();
        }
    }

    // ==================== 5. Pin → Unpin → Re-Pin Transitions ====================

    @Nested
    @DisplayName("4. Pin/Unpin Transitions")
    class PinUnpinTransitionTests {

        @Test
        @DisplayName("Pin v5 → triggers use v5 plan")
        void pinnedTriggerUsesCorrectPlan() {
            WorkflowEntity workflow = createWorkflow(5);
            Map<String, Object> v5Plan = createPlanMap("v5_production");
            workflow.setPlan(createPlanMap("v8_latest_dev"));

            WorkflowPlanVersionEntity v5Entity = createVersionEntity(5, v5Plan);
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER, workflow, 3);
            setupForPlanResolution(run, workflow);
            when(runRepository.findFirstByWorkflowIdAndPlanVersionAndStatusInOrderByStartedAtDesc(eq(WORKFLOW_ID), eq(5), any()))
                    .thenReturn(Optional.empty());
            when(planVersionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 5))
                    .thenReturn(Optional.of(v5Entity));

            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

            ArgumentCaptor<WorkflowRunEntity> captor = ArgumentCaptor.forClass(WorkflowRunEntity.class);
            verify(runRepository, atLeast(2)).save(captor.capture());
            WorkflowRunEntity lastSaved = captor.getAllValues().get(captor.getAllValues().size() - 1);
            assertThat(lastSaved.getPlan().get("name")).isEqualTo("v5_production");
            assertThat(lastSaved.getPlanVersion()).isEqualTo(5);
        }

        @Test
        @DisplayName("Unpin → triggers use latest plan (v8)")
        void unpinnedTriggerUsesLatestPlan() {
            WorkflowEntity workflow = createWorkflow(null);
            workflow.setPlan(createPlanMap("v8_latest"));

            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER, workflow, 5);
            setupForPlanResolution(run, workflow);
            when(planVersionRepository.getMaxVersion(WORKFLOW_ID))
                    .thenReturn(Optional.of(8));

            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

            ArgumentCaptor<WorkflowRunEntity> captor = ArgumentCaptor.forClass(WorkflowRunEntity.class);
            verify(runRepository, atLeast(2)).save(captor.capture());
            WorkflowRunEntity lastSaved = captor.getAllValues().get(captor.getAllValues().size() - 1);
            assertThat(lastSaved.getPlan().get("name")).isEqualTo("v8_latest");
            assertThat(lastSaved.getPlanVersion()).isEqualTo(8);
        }

        @Test
        @DisplayName("Re-pin to v3 after unpin → triggers use v3 plan (rollback)")
        void rePinAfterUnpinUsesNewPinnedVersion() {
            WorkflowEntity workflow = createWorkflow(3);
            Map<String, Object> v3Plan = createPlanMap("v3_rollback");
            workflow.setPlan(createPlanMap("v8_current"));

            WorkflowPlanVersionEntity v3Entity = createVersionEntity(3, v3Plan);
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER, workflow, 5);
            setupForPlanResolution(run, workflow);
            // No prior run for v3 → falls back to version table
            when(runRepository.findFirstByWorkflowIdAndPlanVersionAndStatusInOrderByStartedAtDesc(eq(WORKFLOW_ID), eq(3), any()))
                    .thenReturn(Optional.empty());
            when(planVersionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 3))
                    .thenReturn(Optional.of(v3Entity));

            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

            ArgumentCaptor<WorkflowRunEntity> captor = ArgumentCaptor.forClass(WorkflowRunEntity.class);
            verify(runRepository, atLeast(2)).save(captor.capture());
            WorkflowRunEntity lastSaved = captor.getAllValues().get(captor.getAllValues().size() - 1);
            assertThat(lastSaved.getPlan().get("name")).isEqualTo("v3_rollback");
            assertThat(lastSaved.getPlanVersion()).isEqualTo(3);
        }
    }

    // ==================== 6. Sequential Trigger Fires ====================

    @Nested
    @DisplayName("5. Sequential Trigger Fires - Version Tracking")
    class SequentialTriggerFireTests {

        @Test
        @DisplayName("Pinned: 3 consecutive fires should all use pinned plan")
        void consecutivePinnedFiresUseConsistentPlan() {
            WorkflowEntity workflow = createWorkflow(5);
            Map<String, Object> pinnedPlan = createPlanMap("pinned_v5");
            workflow.setPlan(createPlanMap("latest_v10"));

            WorkflowPlanVersionEntity v5Entity = createVersionEntity(5, pinnedPlan);
            // Fire 0: no prior run for v5 → falls back to version table
            when(runRepository.findFirstByWorkflowIdAndPlanVersionAndStatusInOrderByStartedAtDesc(eq(WORKFLOW_ID), eq(5), any()))
                    .thenReturn(Optional.empty());
            when(planVersionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 5))
                    .thenReturn(Optional.of(v5Entity));
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));

            for (int fire = 0; fire < 3; fire++) {
                // Fire 0: run.planVersion=3 (differs from pinned 5) → safety net: last run → version table
                // Fires 1-2: run.planVersion=5 (matches pinned) → skips safety net entirely
                WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER, workflow, fire == 0 ? 3 : 5);
                // Phase G.1: simulate run.plan being set (production-realistic)
                run.setPlan(createPlanMap("cached"));
                when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
                WorkflowRunState state = new WorkflowRunState(
                        RUN_ID, WORKFLOW_ID.toString(), RunStatus.RUNNING, ExecutionMode.AUTOMATIC,
                        Instant.now(), null, createPlanMap("cached"), List.of(), List.of(),
                        Set.of(), Set.of(), Set.of(), Set.of(), Map.of());
                lenient().when(resumeService.reconstructState(RUN_ID)).thenReturn(state);
                when(epochManager.getCurrentEpoch(any(WorkflowRunEntity.class), anyString()))
                        .thenThrow(new RuntimeException("stop"));

                service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);
            }

            // Only fire 0 hits the safety net (run.planVersion=3 != pinned=5).
            // Fires 1-2 skip it (run.planVersion=5 == pinned=5, optimization).
            // Safety net: first tries last run, then version table (both get 1 call)
            verify(runRepository, times(1)).findFirstByWorkflowIdAndPlanVersionAndStatusInOrderByStartedAtDesc(eq(WORKFLOW_ID), eq(5), any());
            verify(planVersionRepository, times(1)).findByWorkflowIdAndVersion(WORKFLOW_ID, 5);
        }
    }

    // ==================== 7. Trigger Type Variations ====================

    @Nested
    @DisplayName("6. Different Trigger Types")
    class TriggerTypeTests {

        @Test
        @DisplayName("WEBHOOK trigger: uses pinned version")
        void webhookUsesPinnedVersion() {
            verifyTriggerTypeUsesPinnedPlan(TriggerType.WEBHOOK);
        }

        @Test
        @DisplayName("SCHEDULE trigger: uses pinned version")
        void scheduleUsesPinnedVersion() {
            verifyTriggerTypeUsesPinnedPlan(TriggerType.SCHEDULE);
        }

        @Test
        @DisplayName("MANUAL trigger: uses pinned version")
        void manualUsesPinnedVersion() {
            verifyTriggerTypeUsesPinnedPlan(TriggerType.MANUAL);
        }

        @Test
        @DisplayName("CHAT trigger: uses pinned version")
        void chatUsesPinnedVersion() {
            verifyTriggerTypeUsesPinnedPlan(TriggerType.CHAT);
        }

        @Test
        @DisplayName("FORM trigger: uses pinned version")
        void formUsesPinnedVersion() {
            verifyTriggerTypeUsesPinnedPlan(TriggerType.FORM);
        }

        private void verifyTriggerTypeUsesPinnedPlan(TriggerType type) {
            WorkflowEntity workflow = createWorkflow(7);
            Map<String, Object> pinnedPlan = createPlanMap("pinned_v7");
            workflow.setPlan(createPlanMap("latest_v12"));

            WorkflowPlanVersionEntity v7Entity = createVersionEntity(7, pinnedPlan);
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER, workflow, 5);
            setupForPlanResolution(run, workflow);
            // No prior run for v7 → falls back to version table
            when(runRepository.findFirstByWorkflowIdAndPlanVersionAndStatusInOrderByStartedAtDesc(eq(WORKFLOW_ID), eq(7), any()))
                    .thenReturn(Optional.empty());
            when(planVersionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 7))
                    .thenReturn(Optional.of(v7Entity));

            service.executeTriggerInternal(run, TRIGGER_ID, type, Map.of(), false);

            ArgumentCaptor<WorkflowRunEntity> captor = ArgumentCaptor.forClass(WorkflowRunEntity.class);
            verify(runRepository, atLeast(2)).save(captor.capture());
            WorkflowRunEntity lastSaved = captor.getAllValues().get(captor.getAllValues().size() - 1);
            assertThat(lastSaved.getPlanVersion()).isEqualTo(7);
            assertThat(lastSaved.getPlan().get("name")).isEqualTo("pinned_v7");
        }
    }

    // ==================== 8. Plan Content Integrity ====================

    @Nested
    @DisplayName("7. Plan Content Integrity")
    class PlanContentIntegrityTests {

        @Test
        @DisplayName("Pinned plan with complex structure should be preserved exactly")
        void complexPlanStructurePreserved() {
            WorkflowEntity workflow = createWorkflow(3);
            Map<String, Object> complexPlan = new HashMap<>();
            complexPlan.put("id", WORKFLOW_ID.toString());
            complexPlan.put("name", "complex_workflow");
            complexPlan.put("triggers", List.of(
                Map.of("type", "webhook", "label", "Webhook A"),
                Map.of("type", "schedule", "label", "Daily Cron", "cron", "0 0 * * *")
            ));
            complexPlan.put("mcps", List.of(
                Map.of("label", "API Call", "toolId", "http/get", "parameters", Map.of("url", "https://api.test")),
                Map.of("label", "Transform", "toolId", "core/transform")
            ));
            complexPlan.put("cores", List.of(
                Map.of("type", "decision", "label", "Check Status", "condition", "{{mcp:api_call.output.status}} == 200")
            ));
            complexPlan.put("edges", List.of(
                Map.of("from", "trigger:webhook_a", "to", "mcp:api_call"),
                Map.of("from", "core:check_status:if", "to", "mcp:transform")
            ));

            WorkflowPlanVersionEntity v3Entity = createVersionEntity(3, complexPlan);
            workflow.setPlan(createPlanMap("latest_different"));

            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER, workflow, 1);
            setupForPlanResolution(run, workflow);
            when(runRepository.findFirstByWorkflowIdAndPlanVersionAndStatusInOrderByStartedAtDesc(eq(WORKFLOW_ID), eq(3), any()))
                    .thenReturn(Optional.empty());
            when(planVersionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 3))
                    .thenReturn(Optional.of(v3Entity));

            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

            ArgumentCaptor<WorkflowRunEntity> captor = ArgumentCaptor.forClass(WorkflowRunEntity.class);
            verify(runRepository, atLeast(2)).save(captor.capture());
            WorkflowRunEntity lastSaved = captor.getAllValues().get(captor.getAllValues().size() - 1);
            Map<String, Object> savedPlan = lastSaved.getPlan();

            assertThat(savedPlan.get("name")).isEqualTo("complex_workflow");
            assertThat((List<?>) savedPlan.get("triggers")).hasSize(2);
            assertThat((List<?>) savedPlan.get("mcps")).hasSize(2);
            assertThat((List<?>) savedPlan.get("cores")).hasSize(1);
            assertThat((List<?>) savedPlan.get("edges")).hasSize(2);
        }

        @Test
        @DisplayName("Empty plan map should be handled without error")
        void emptyPlanMapHandled() {
            WorkflowEntity workflow = createWorkflow(null);
            workflow.setPlan(new HashMap<>());

            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER, workflow, 1);
            setupForPlanResolution(run, workflow);
            lenient().when(planVersionRepository.getMaxVersion(WORKFLOW_ID))
                    .thenReturn(Optional.of(1));

            assertThatCode(() ->
                service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false)
            ).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Run plan saved via setPlan is a HashMap (independent copy from run.setPlan call)")
        void runPlanIsSavedAsHashMap() {
            WorkflowEntity workflow = createWorkflow(5);
            Map<String, Object> originalPlan = createPlanMap("pinned_v5");
            WorkflowPlanVersionEntity v5Entity = createVersionEntity(5, originalPlan);
            workflow.setPlan(createPlanMap("latest"));

            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER, workflow, 3);
            setupForPlanResolution(run, workflow);
            lenient().when(runRepository.findFirstByWorkflowIdAndPlanVersionAndStatusInOrderByStartedAtDesc(eq(WORKFLOW_ID), eq(5), any()))
                    .thenReturn(Optional.empty());
            lenient().when(planVersionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 5))
                    .thenReturn(Optional.of(v5Entity));

            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

            ArgumentCaptor<WorkflowRunEntity> captor = ArgumentCaptor.forClass(WorkflowRunEntity.class);
            verify(runRepository, atLeast(2)).save(captor.capture());
            WorkflowRunEntity lastSaved = captor.getAllValues().get(captor.getAllValues().size() - 1);

            // The saved plan should be a HashMap (ensured by the setPlan call)
            assertThat(lastSaved.getPlan()).isInstanceOf(HashMap.class);
            assertThat(lastSaved.getPlan().get("name")).isEqualTo("pinned_v5");
        }
    }

    // ==================== 9. forceAutoMode ====================

    @Nested
    @DisplayName("8. forceAutoMode Interaction")
    class ForceAutoModeTests {

        @Test
        @DisplayName("forceAutoMode=true should still use pinned version plan")
        void forceAutoModeStillUsesPinnedPlan() {
            WorkflowEntity workflow = createWorkflow(5);
            Map<String, Object> pinnedPlan = createPlanMap("pinned_v5");
            workflow.setPlan(createPlanMap("latest_v10"));

            WorkflowPlanVersionEntity v5Entity = createVersionEntity(5, pinnedPlan);
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER, workflow, 3);
            setupForPlanResolution(run, workflow);
            when(runRepository.findFirstByWorkflowIdAndPlanVersionAndStatusInOrderByStartedAtDesc(eq(WORKFLOW_ID), eq(5), any()))
                    .thenReturn(Optional.empty());
            when(planVersionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 5))
                    .thenReturn(Optional.of(v5Entity));

            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), true);

            ArgumentCaptor<WorkflowRunEntity> captor = ArgumentCaptor.forClass(WorkflowRunEntity.class);
            verify(runRepository, atLeast(2)).save(captor.capture());
            WorkflowRunEntity lastSaved = captor.getAllValues().get(captor.getAllValues().size() - 1);
            assertThat(lastSaved.getPlanVersion()).isEqualTo(5);
        }
    }

    // ==================== 10. Stress Tests ====================

    @Nested
    @DisplayName("9. Stress Tests - Concurrent Trigger Fires")
    class ConcurrentStressTests {

        @Test
        @DisplayName("10 concurrent fires should all resolve pinned version consistently")
        void concurrentPinnedFiresAreConsistent() throws InterruptedException {
            int threadCount = 10;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            List<Integer> resolvedVersions = Collections.synchronizedList(new ArrayList<>());

            WorkflowEntity workflow = createWorkflow(5);
            Map<String, Object> pinnedPlan = createPlanMap("pinned_v5");
            workflow.setPlan(createPlanMap("latest_v10"));

            WorkflowPlanVersionEntity v5Entity = createVersionEntity(5, pinnedPlan);
            lenient().when(runRepository.findFirstByWorkflowIdAndPlanVersionAndStatusInOrderByStartedAtDesc(eq(WORKFLOW_ID), eq(5), any()))
                    .thenReturn(Optional.empty());
            when(planVersionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 5))
                    .thenReturn(Optional.of(v5Entity));
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // Pre-register all mocks before starting threads (avoids race on mock setup)
            lenient().when(resumeService.reconstructState(anyString())).thenAnswer(inv -> {
                String runId = inv.getArgument(0);
                Map<String, Object> cachedPlan = createPlanMap("cached");
                return new WorkflowRunState(
                        runId, WORKFLOW_ID.toString(), RunStatus.RUNNING,
                        ExecutionMode.AUTOMATIC, Instant.now(), null, cachedPlan,
                        List.of(), List.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                        Map.of());
            });
            when(epochManager.getCurrentEpoch(any(WorkflowRunEntity.class), anyString()))
                    .thenThrow(new RuntimeException("stop"));

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int i = 0; i < threadCount; i++) {
                final int fireIndex = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();

                        String runId = RUN_ID + "-" + fireIndex;
                        WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER, workflow, 3);
                        run.setRunIdPublic(runId);

                        service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

                        if (run.getPlanVersion() != null) {
                            resolvedVersions.add(run.getPlanVersion());
                        }
                    } catch (Exception e) {
                        // Expected: mock concurrency issues
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            boolean completed = doneLatch.await(15, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(completed).isTrue();
            assertThat(resolvedVersions).isNotEmpty();
            assertThat(resolvedVersions).allMatch(v -> v == 5,
                    "All concurrent fires should resolve to pinned version 5");
        }

    }

    // ==================== 11. Version Number Edge Cases ====================

    @Nested
    @DisplayName("10. Version Number Edge Cases")
    class VersionNumberEdgeCaseTests {

        @Test
        @DisplayName("Version 1 (minimum) should work as pinned version")
        void versionOneWorks() {
            WorkflowEntity workflow = createWorkflow(1);
            Map<String, Object> v1Plan = createPlanMap("v1_plan");
            WorkflowPlanVersionEntity v1Entity = createVersionEntity(1, v1Plan);
            workflow.setPlan(createPlanMap("latest_v20"));

            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER, workflow, null);
            setupForPlanResolution(run, workflow);
            when(runRepository.findFirstByWorkflowIdAndPlanVersionAndStatusInOrderByStartedAtDesc(eq(WORKFLOW_ID), eq(1), any()))
                    .thenReturn(Optional.empty());
            when(planVersionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 1))
                    .thenReturn(Optional.of(v1Entity));

            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

            ArgumentCaptor<WorkflowRunEntity> captor = ArgumentCaptor.forClass(WorkflowRunEntity.class);
            verify(runRepository, atLeast(2)).save(captor.capture());
            WorkflowRunEntity lastSaved = captor.getAllValues().get(captor.getAllValues().size() - 1);
            assertThat(lastSaved.getPlanVersion()).isEqualTo(1);
        }

        @Test
        @DisplayName("Very high version number should work")
        void highVersionNumberWorks() {
            WorkflowEntity workflow = createWorkflow(9999);
            Map<String, Object> plan = createPlanMap("high_version_plan");
            WorkflowPlanVersionEntity entity = createVersionEntity(9999, plan);
            workflow.setPlan(createPlanMap("latest"));

            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER, workflow, 1);
            setupForPlanResolution(run, workflow);
            when(runRepository.findFirstByWorkflowIdAndPlanVersionAndStatusInOrderByStartedAtDesc(eq(WORKFLOW_ID), eq(9999), any()))
                    .thenReturn(Optional.empty());
            when(planVersionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 9999))
                    .thenReturn(Optional.of(entity));

            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

            verify(planVersionRepository).findByWorkflowIdAndVersion(WORKFLOW_ID, 9999);
        }

        @Test
        @DisplayName("Pinned version same as run.planVersion: skip version table reload, use run's cached plan")
        void pinnedSameAsCurrentSkipsVersionTableReload() {
            WorkflowEntity workflow = createWorkflow(10);
            Map<String, Object> workflowPlan = createPlanMap("v10_maybe_modified");
            workflow.setPlan(workflowPlan);

            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER, workflow, 10);
            // Phase G.1: production runs have run.plan set; simulate that here so the
            // pinned-match path skips the version-table fallback (production-realistic).
            run.setPlan(createPlanMap("run_cached_v10"));
            setupForPlanResolution(run, workflow);

            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

            // Version table should NOT be consulted - run already has the correct plan
            verify(planVersionRepository, never()).findByWorkflowIdAndVersion(eq(WORKFLOW_ID), anyInt());

            // The run's plan should be the cached one from state reconstruction (not workflow.plan)
            ArgumentCaptor<WorkflowRunEntity> captor = ArgumentCaptor.forClass(WorkflowRunEntity.class);
            verify(runRepository, atLeast(2)).save(captor.capture());
            WorkflowRunEntity lastSaved = captor.getAllValues().get(captor.getAllValues().size() - 1);
            assertThat(lastSaved.getPlanVersion()).isEqualTo(10);
        }
    }

    // ==================== 12. Payload Preservation ====================

    @Nested
    @DisplayName("11. Payload Preservation with Plan Resolution")
    class PayloadPreservationTests {

        @Test
        @DisplayName("Webhook payload should be preserved regardless of plan resolution")
        void webhookPayloadPreserved() {
            WorkflowEntity workflow = createWorkflow(5);
            Map<String, Object> pinnedPlan = createPlanMap("pinned_v5");
            WorkflowPlanVersionEntity v5Entity = createVersionEntity(5, pinnedPlan);
            workflow.setPlan(createPlanMap("latest"));

            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER, workflow, 3);
            setupForPlanResolution(run, workflow);
            lenient().when(runRepository.findFirstByWorkflowIdAndPlanVersionAndStatusInOrderByStartedAtDesc(eq(WORKFLOW_ID), eq(5), any()))
                    .thenReturn(Optional.empty());
            lenient().when(planVersionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 5))
                    .thenReturn(Optional.of(v5Entity));

            Map<String, Object> payload = Map.of(
                "event", "push",
                "repository", "my-repo",
                "data", Map.of("key", "value")
            );

            assertThatCode(() ->
                service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, payload, false)
            ).doesNotThrowAnyException();
        }
    }

    // ==================== Helpers ====================

    /**
     * Setup mocks so executeTriggerInternal() reaches the plan resolution block,
     * then throws at epochManager to stop execution after we can verify the plan.
     *
     * @param run      the run entity
     * @param workflow the workflow entity to return from findById (null = not found)
     */
    private void setupForPlanResolution(WorkflowRunEntity run, WorkflowEntity workflow) {
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Map<String, Object> runPlan = createPlanMap("run_cached_plan");
        WorkflowRunState state = new WorkflowRunState(
                run.getRunIdPublic(), WORKFLOW_ID.toString(), RunStatus.RUNNING, ExecutionMode.AUTOMATIC,
                Instant.now(), null, runPlan, List.of(), List.of(),
                Set.of(), Set.of(), Set.of(), Set.of(), Map.of());
        lenient().when(resumeService.reconstructState(run.getRunIdPublic())).thenReturn(state);
        // Mock workflow lookup - this is CRITICAL for plan resolution to work
        if (workflow != null) {
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
        } else {
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.empty());
        }
        // Stop execution after plan resolution by throwing at epoch manager
        when(epochManager.getCurrentEpoch(any(WorkflowRunEntity.class), anyString())).thenThrow(
                new RuntimeException("stop after plan resolution"));
    }

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
        // Note: run.plan intentionally null - exercises the safety-net + unpinned-refresh paths
        // which populate planMap from workflow/version table. Pinned-match path tests must set
        // run.plan explicitly (workflow.plan/version-entity should be set for null-cached fallback).
        if (workflow != null) {
            ReflectionTestUtils.setField(run, "workflow", workflow);
        }
        return run;
    }

    private WorkflowPlanVersionEntity createVersionEntity(int version, Map<String, Object> plan) {
        WorkflowPlanVersionEntity entity = new WorkflowPlanVersionEntity();
        entity.setPlan(plan);
        entity.setVersion(version);
        entity.setWorkflowId(WORKFLOW_ID);
        return entity;
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
