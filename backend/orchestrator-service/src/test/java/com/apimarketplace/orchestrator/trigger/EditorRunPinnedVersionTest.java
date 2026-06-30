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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for editor-initiated runs vs trigger-dispatched runs regarding pinned version.
 *
 * Scenarios:
 * 1. Editor run (v6, pinned=v3): user clicks Run in editor → should keep v6
 * 2. Trigger run (planVersion=3, pinned changed to v5): safety net should override to v5
 * 3. Editor run (v8, pinned=v3): higher version from editor → should keep v8
 * 4. Trigger run already aligned (planVersion=5, pinned=5): skip reload
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Editor Run vs Trigger Run - Pinned Version Behavior")
class EditorRunPinnedVersionTest {

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
    private static final String RUN_ID = "run-editor-1";
    private static final String TRIGGER_ID = "trigger:my_chat";
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

    // ==================== Editor Run Tests ====================

    @Nested
    @DisplayName("Editor-Initiated Runs (__editorRun__=true)")
    class EditorRunTests {

        @Test
        @DisplayName("Scenario 1: Editor run v6 with pinned=v3 → should keep v6, NOT override to v3")
        void editorRunShouldKeepItsVersion() {
            // Given: user is on v6 in editor, pinned is v3
            WorkflowEntity workflow = createWorkflow(3);
            WorkflowRunEntity run = createEditorRun(RunStatus.WAITING_TRIGGER, workflow, 6);
            setupForPlanResolution(run, workflow);

            // When: chat trigger fires via sidepanel
            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.CHAT, Map.of(), false);

            // Then: should NOT load pinned version (v3) - should keep editor's v6
            verify(planVersionRepository, never()).findByWorkflowIdAndVersion(WORKFLOW_ID, 3);
        }

        @Test
        @DisplayName("Scenario 3: Editor run v8 with pinned=v3 → should keep v8 (higher version)")
        void editorRunHigherVersionShouldKeepItsVersion() {
            // Given: user is on v8 (latest), pinned is v3
            WorkflowEntity workflow = createWorkflow(3);
            WorkflowRunEntity run = createEditorRun(RunStatus.WAITING_TRIGGER, workflow, 8);
            setupForPlanResolution(run, workflow);

            // When: chat trigger fires
            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.CHAT, Map.of(), false);

            // Then: should NOT load pinned version - editor run keeps v8
            verify(planVersionRepository, never()).findByWorkflowIdAndVersion(WORKFLOW_ID, 3);
        }

        @Test
        @DisplayName("Editor run v3 with pinned=v3 → already aligned, skip reload")
        void editorRunAlignedWithPinnedShouldSkipReload() {
            // Given: editor version matches pinned
            WorkflowEntity workflow = createWorkflow(3);
            WorkflowRunEntity run = createEditorRun(RunStatus.WAITING_TRIGGER, workflow, 3);
            setupForPlanResolution(run, workflow);

            // When
            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.CHAT, Map.of(), false);

            // Then: planVersion == pinnedVersion → skip reload (first branch)
            verify(planVersionRepository, never()).findByWorkflowIdAndVersion(any(), anyInt());
        }
    }

    // ==================== Trigger-Dispatched Run Tests ====================

    @Nested
    @DisplayName("Trigger-Dispatched Runs (no __editorRun__ flag)")
    class TriggerRunTests {

        @Test
        @DisplayName("Scenario 2: Trigger run v3, pinned changed to v5 → safety net overrides to v5")
        void triggerRunShouldApplySafetyNet() {
            // Given: run created at v3, pinned later changed to v5
            WorkflowEntity workflow = createWorkflow(5);
            WorkflowRunEntity run = createTriggerRun(RunStatus.WAITING_TRIGGER, workflow, 3);
            setupForPlanResolution(run, workflow);

            WorkflowPlanVersionEntity versionEntity = new WorkflowPlanVersionEntity();
            versionEntity.setPlan(createPlanMap("pinned_v5_plan"));
            when(planVersionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 5))
                    .thenReturn(Optional.of(versionEntity));

            // When: webhook fires
            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

            // Then: safety net should load pinned v5
            verify(planVersionRepository).findByWorkflowIdAndVersion(WORKFLOW_ID, 5);
        }

        @Test
        @DisplayName("Scenario 4: Trigger run v5, pinned=v5 → already aligned, skip reload")
        void triggerRunAlignedShouldSkipReload() {
            // Given: run at same version as pinned
            WorkflowEntity workflow = createWorkflow(5);
            WorkflowRunEntity run = createTriggerRun(RunStatus.WAITING_TRIGGER, workflow, 5);
            setupForPlanResolution(run, workflow);

            // When
            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

            // Then: planVersion == pinnedVersion → skip reload
            verify(planVersionRepository, never()).findByWorkflowIdAndVersion(any(), anyInt());
        }

        @Test
        @DisplayName("Trigger run with null planVersion, pinned=v5 → safety net loads v5")
        void triggerRunNullVersionShouldApplySafetyNet() {
            // Given: legacy run with no planVersion set
            WorkflowEntity workflow = createWorkflow(5);
            WorkflowRunEntity run = createTriggerRun(RunStatus.WAITING_TRIGGER, workflow, null);
            setupForPlanResolution(run, workflow);

            WorkflowPlanVersionEntity versionEntity = new WorkflowPlanVersionEntity();
            versionEntity.setPlan(createPlanMap("pinned_v5_plan"));
            when(planVersionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 5))
                    .thenReturn(Optional.of(versionEntity));

            // When
            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

            // Then: safety net should load pinned v5
            verify(planVersionRepository).findByWorkflowIdAndVersion(WORKFLOW_ID, 5);
        }

        @Test
        @DisplayName("Trigger run with no __editorRun__ and planVersion != pinned → applies safety net")
        void triggerRunWithoutFlagShouldAlwaysApplySafetyNet() {
            // Given: run at v7, pinned at v3 - but NO __editorRun__ flag
            // This simulates a trigger-dispatched run where version diverged
            WorkflowEntity workflow = createWorkflow(3);
            WorkflowRunEntity run = createTriggerRun(RunStatus.WAITING_TRIGGER, workflow, 7);
            setupForPlanResolution(run, workflow);

            WorkflowPlanVersionEntity versionEntity = new WorkflowPlanVersionEntity();
            versionEntity.setPlan(createPlanMap("pinned_v3_plan"));
            when(planVersionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 3))
                    .thenReturn(Optional.of(versionEntity));

            // When
            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

            // Then: should load pinned v3 (safety net applies because NOT editor run)
            verify(planVersionRepository).findByWorkflowIdAndVersion(WORKFLOW_ID, 3);
        }
    }

    // ==================== Helpers ====================

    private void setupForPlanResolution(WorkflowRunEntity run, WorkflowEntity workflow) {
        // Phase G.1: trigger fire path no longer calls resumeService.reconstructState;
        // plan is read directly from run.getPlan() (set in createTriggerRun helper).
        // Safety-net circuit breaker is now epochManager.getCurrentEpoch.
        // All stubs are lenient since editor vs trigger runs exercise different paths.
        lenient().when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        if (workflow != null) {
            lenient().when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
        }
        lenient().when(epochManager.getCurrentEpoch(any(WorkflowRunEntity.class), anyString())).thenThrow(
                new RuntimeException("stop after plan resolution"));
    }

    private WorkflowEntity createWorkflow(Integer pinnedVersion) {
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(WORKFLOW_ID);
        workflow.setTenantId(TENANT_ID);
        workflow.setPinnedVersion(pinnedVersion);
        workflow.setPlan(createPlanMap("workflow_plan"));
        return workflow;
    }

    /**
     * Creates a run WITH __editorRun__=true metadata (simulates manual run from editor).
     */
    private WorkflowRunEntity createEditorRun(RunStatus status, WorkflowEntity workflow, Integer planVersion) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID);
        run.setStatus(status);
        run.setTenantId(TENANT_ID);
        run.setPlanVersion(planVersion);
        // Phase G.1: production runs have run.plan set; simulate that here so pinned-match
        // path skips version-table fallback (production-realistic).
        run.setPlan(createPlanMap("editor_cached_plan"));
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("__editorRun__", true);
        run.setMetadata(metadata);
        if (workflow != null) {
            ReflectionTestUtils.setField(run, "workflow", workflow);
        }
        return run;
    }

    /**
     * Creates a run WITHOUT __editorRun__ flag (simulates trigger-dispatched run).
     */
    private WorkflowRunEntity createTriggerRun(RunStatus status, WorkflowEntity workflow, Integer planVersion) {
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
        planMap.put("triggers", List.of(Map.of("type", "webhook", "label", "My Chat")));
        planMap.put("mcps", List.of());
        planMap.put("cores", List.of());
        planMap.put("edges", List.of());
        return planMap;
    }
}
