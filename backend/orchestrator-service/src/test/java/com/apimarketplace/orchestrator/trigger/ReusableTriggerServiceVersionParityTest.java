package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.execution.v2.services.V2StepByStepService;
import com.apimarketplace.orchestrator.repository.WorkflowPlanVersionRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.credit.CreditBudgetService;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.SnapshotService;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies the run.planVersion ↔ workflow_plan_versions content-parity invariant
 * in {@link ReusableTriggerService#executeTriggerInternal}'s plan resolution:
 *
 * <ul>
 *   <li>A version-replay run ({@code __versionReplay__} metadata, created by
 *       {@code workflow(action='execute', version=N)}) must execute its frozen
 *       historical plan - the unpinned passive-fire refresh must NOT swap in the
 *       live workflow plan, even when topology-compatible.</li>
 *   <li>When an unpinned passive fire DOES refresh from {@code workflow.plan}, the
 *       version stamped on the run must be content-true: resolved via
 *       {@code WorkflowPlanVersionService.createVersion} (dedupes against the latest
 *       stored version, creates one if some write path slipped past versioning) -
 *       not a blind max-version lookup.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReusableTriggerService - run/version parity at trigger fire")
class ReusableTriggerServiceVersionParityTest {

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
    @Mock private V2StepByStepService v2StepByStepService;
    @Mock private CreditConsumptionClient creditClient;
    @Mock private CreditBudgetService creditBudgetService;
    @Mock private WorkflowPlanVersionService planVersionService;

    private ReusableTriggerService service;

    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final String RUN_ID = "run-version-parity-1";
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
        ReflectionTestUtils.setField(service, "v2StepByStepService", v2StepByStepService);
        ReflectionTestUtils.setField(service, "unifiedSignalService", unifiedSignalService);
        ReflectionTestUtils.setField(service, "snapshotService", snapshotService);
        ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    @DisplayName("Version-replay run on unpinned workflow: frozen plan + version survive a passive fire despite compatible canvas edits")
    void versionReplayFireKeepsFrozenPlanOnUnpinnedWorkflow() {
        // Bug: workflow(action='execute', version=3) created a run frozen at v3,
        // but the unpinned passive-fire refresh swapped in workflow.plan (latest
        // draft) whenever topology was compatible - the "replay" silently executed
        // the wrong version and was re-stamped to it.
        Map<String, Object> frozenV3Plan = planWith(List.of(mcp("Step A", Map.of("url", "v3-url"))),
                List.of(edge("trigger:my_webhook", "mcp:step_a")));
        Map<String, Object> latestCanvasPlan = planWith(List.of(mcp("Step A", Map.of("url", "v9-url"))),
                List.of(edge("trigger:my_webhook", "mcp:step_a")));

        WorkflowEntity workflow = unpinnedWorkflow(latestCanvasPlan);
        WorkflowRunEntity run = runWith(frozenV3Plan, workflow);
        run.setPlanVersion(3);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("__editorRun__", Boolean.TRUE);
        metadata.put("__versionReplay__", 3);
        run.setMetadata(metadata);

        setupThroughPlanResolution(workflow);

        service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

        Map<?, ?> params = (Map<?, ?>) ((Map<?, ?>) ((List<?>) run.getPlan().get("mcps")).get(0)).get("params");
        assertThat(params.get("url")).isEqualTo("v3-url");
        assertThat(run.getPlanVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("Unpinned passive fire: stamped version is resolved content-true WITHOUT minting a new version (re-fire keeps the version stable)")
    void unpinnedPassiveFireStampsContentTrueVersion() {
        Map<String, Object> staleRunPlan = planWith(List.of(mcp("Step A", Map.of("url", "old"))),
                List.of(edge("trigger:my_webhook", "mcp:step_a")));
        Map<String, Object> liveWorkflowPlan = planWith(List.of(mcp("Step A", Map.of("url", "new"))),
                List.of(edge("trigger:my_webhook", "mcp:step_a")));

        WorkflowEntity workflow = unpinnedWorkflow(liveWorkflowPlan);
        WorkflowRunEntity run = runWith(staleRunPlan, workflow);
        run.setPlanVersion(7);

        ReflectionTestUtils.setField(service, "planVersionService", planVersionService);
        // Content-true resolution (2026-06-11): drifted workflow.plan is reconciled
        // INTO the latest version row (overwritten in place, same number) - a fire
        // must never mint v8 out of a v7 re-fire. REQUIRES_NEW variant so a failure
        // cannot poison a caller-owned transaction.
        when(planVersionService.resolveContentVersionForExecutionInNewTransaction(WORKFLOW_ID, liveWorkflowPlan, TENANT_ID)).thenReturn(7);

        setupThroughPlanResolution(workflow);

        service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

        verify(planVersionService).resolveContentVersionForExecutionInNewTransaction(WORKFLOW_ID, liveWorkflowPlan, TENANT_ID);
        verify(planVersionService, never()).createVersionInNewTransaction(any(UUID.class), any(), any(), any());
        verify(planVersionRepository, never()).getMaxVersion(any());
        assertThat(run.getPlanVersion()).isEqualTo(7);
        Map<?, ?> params = (Map<?, ?>) ((Map<?, ?>) ((List<?>) run.getPlan().get("mcps")).get(0)).get("params");
        assertThat(params.get("url")).isEqualTo("new");
    }

    @Test
    @DisplayName("Unpinned passive fire without the version-service bean: legacy max-version fallback still applies")
    void unpinnedPassiveFireFallsBackToMaxVersionWithoutService() {
        Map<String, Object> staleRunPlan = planWith(List.of(mcp("Step A", Map.of("url", "old"))),
                List.of(edge("trigger:my_webhook", "mcp:step_a")));
        Map<String, Object> liveWorkflowPlan = planWith(List.of(mcp("Step A", Map.of("url", "new"))),
                List.of(edge("trigger:my_webhook", "mcp:step_a")));

        WorkflowEntity workflow = unpinnedWorkflow(liveWorkflowPlan);
        WorkflowRunEntity run = runWith(staleRunPlan, workflow);
        run.setPlanVersion(3);

        // planVersionService deliberately NOT injected (narrow test wiring).
        when(planVersionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(7));
        setupThroughPlanResolution(workflow);

        service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

        assertThat(run.getPlanVersion()).isEqualTo(7);
    }

    @Test
    @DisplayName("Content-true resolution failure falls back to max-version (fire must not be blocked by versioning)")
    void resolveVersionFallsBackToMaxVersionWhenCreateVersionThrows() {
        Map<String, Object> staleRunPlan = planWith(List.of(mcp("Step A", Map.of("url", "old"))),
                List.of(edge("trigger:my_webhook", "mcp:step_a")));
        Map<String, Object> liveWorkflowPlan = planWith(List.of(mcp("Step A", Map.of("url", "new"))),
                List.of(edge("trigger:my_webhook", "mcp:step_a")));

        WorkflowEntity workflow = unpinnedWorkflow(liveWorkflowPlan);
        WorkflowRunEntity run = runWith(staleRunPlan, workflow);
        run.setPlanVersion(3);

        ReflectionTestUtils.setField(service, "planVersionService", planVersionService);
        when(planVersionService.resolveContentVersionForExecutionInNewTransaction(any(UUID.class), any(), any()))
                .thenThrow(new RuntimeException("version table unavailable"));
        when(planVersionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(7));
        setupThroughPlanResolution(workflow);

        service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

        // Fallback stamp applies; the refreshed plan is still adopted.
        assertThat(run.getPlanVersion()).isEqualTo(7);
        Map<?, ?> params = (Map<?, ?>) ((Map<?, ?>) ((List<?>) run.getPlan().get("mcps")).get(0)).get("params");
        assertThat(params.get("url")).isEqualTo("new");
    }

    @Test
    @DisplayName("Topology-rejected refresh creates NO version row - resolution runs only after the guard")
    void topologyRejectedRefreshCreatesNoVersionRow() {
        // Ordering bug guard: resolving the content-true version BEFORE the topology
        // guard would create a version row (with billing + purge side effects) for a
        // plan the run will not execute.
        Map<String, Object> frozenPlan = planWith(List.of(mcp("Step A", Map.of("url", "v1"))),
                List.of(edge("trigger:my_webhook", "mcp:step_a")));
        Map<String, Object> structurallyChangedPlan = planWith(
                List.of(mcp("Step A", Map.of("url", "v1")), mcp("Step B", Map.of())),
                List.of(edge("trigger:my_webhook", "mcp:step_a"), edge("mcp:step_a", "mcp:step_b")));

        WorkflowEntity workflow = unpinnedWorkflow(structurallyChangedPlan);
        WorkflowRunEntity run = runWith(frozenPlan, workflow);
        run.setPlanVersion(3);

        ReflectionTestUtils.setField(service, "planVersionService", planVersionService);
        setupThroughPlanResolution(workflow);

        service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

        verify(planVersionService, never()).createVersionInNewTransaction(any(UUID.class), any(), any(), any());
        verify(planVersionService, never()).resolveContentVersionForExecutionInNewTransaction(any(UUID.class), any(), any());
        assertThat(run.getPlanVersion()).isEqualTo(3);
        List<?> mcps = (List<?>) run.getPlan().get("mcps");
        assertThat(mcps).hasSize(1);
    }

    @Test
    @DisplayName("Version-replay run: payload-marker fire also keeps the frozen plan (marker path unaffected by replay guard)")
    void versionReplayWithPayloadMarkerKeepsRunPlan() {
        // The replay guard lives in the passive-fire branch; the marker path already
        // executes run.plan as-is. This pins that a replay run fired with the marker
        // (defensive combination - should not happen in practice) still executes its
        // own plan and keeps its version.
        Map<String, Object> frozenV3Plan = planWith(List.of(mcp("Step A", Map.of("url", "v3-url"))),
                List.of(edge("trigger:my_webhook", "mcp:step_a")));
        Map<String, Object> latestCanvasPlan = planWith(List.of(mcp("Step A", Map.of("url", "v9-url"))),
                List.of(edge("trigger:my_webhook", "mcp:step_a")));

        WorkflowEntity workflow = unpinnedWorkflow(latestCanvasPlan);
        WorkflowRunEntity run = runWith(frozenV3Plan, workflow);
        run.setPlanVersion(3);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("__versionReplay__", 3);
        run.setMetadata(metadata);

        setupThroughPlanResolution(workflow);

        Map<String, Object> payload = new HashMap<>();
        payload.put(ReusableTriggerService.PLAN_FROM_PAYLOAD_MARKER, Boolean.TRUE);

        service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.MANUAL, payload, false);

        Map<?, ?> params = (Map<?, ?>) ((Map<?, ?>) ((List<?>) run.getPlan().get("mcps")).get(0)).get("params");
        assertThat(params.get("url")).isEqualTo("v3-url");
        assertThat(run.getPlanVersion()).isEqualTo(3);
    }

    // ====================================================================
    // Helpers
    // ====================================================================

    @Test
    @DisplayName("Regression 2026-07-21: chokepoint catches a version-drifted PRODUCTION run despite its __editorRun__ flag")
    void chokepointCatchesDriftedProductionRunDespiteEditorFlag() {
        // Pinning promotes an editor run and never strips __editorRun__, so the old
        // `!isEditorRun(run)` exemption skipped the chokepoint for 100% of promoted
        // production runs - the defense-in-depth was dead for exactly the runs it
        // guards. The exemption now excludes the production run (FK identity).
        Map<String, Object> plan = planWith(List.of(mcp("Step A", Map.of("url", "x"))),
                List.of(edge("trigger:my_webhook", "mcp:step_a")));
        WorkflowEntity workflow = unpinnedWorkflow(plan);
        workflow.setPinnedVersion(17);
        WorkflowRunEntity run = runWith(plan, workflow);
        run.setPlanVersion(12); // drifted from the pin - the leak the chokepoint exists for
        run.getMetadata().put("__editorRun__", Boolean.TRUE);
        UUID productionRunId = UUID.randomUUID();
        ReflectionTestUtils.setField(run, "id", productionRunId);
        workflow.setProductionRunId(productionRunId);

        // Real resolver: isAllowedForProduction touches only its two arguments.
        ReflectionTestUtils.setField(service, "productionRunResolver",
                new ProductionRunResolver(workflowRepository, runRepository));
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
        lenient().when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

        TriggerExecutionResult result =
                service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Production trigger refused");
    }

    @Test
    @DisplayName("Chokepoint still exempts a NON-production editor run on a pinned workflow (draft testing keeps working)")
    void chokepointStillExemptsNonProductionEditorRun() {
        Map<String, Object> plan = planWith(List.of(mcp("Step A", Map.of("url", "x"))),
                List.of(edge("trigger:my_webhook", "mcp:step_a")));
        WorkflowEntity workflow = unpinnedWorkflow(plan);
        workflow.setPinnedVersion(17);
        // Production is a DIFFERENT run.
        workflow.setProductionRunId(UUID.randomUUID());
        WorkflowRunEntity run = runWith(plan, workflow);
        run.setPlanVersion(12); // draft testing at a non-pinned version - allowed
        run.getMetadata().put("__editorRun__", Boolean.TRUE);
        ReflectionTestUtils.setField(run, "id", UUID.randomUUID());

        ReflectionTestUtils.setField(service, "productionRunResolver",
                new ProductionRunResolver(workflowRepository, runRepository));
        setupThroughPlanResolution(workflow);

        TriggerExecutionResult result =
                service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

        // Passes the chokepoint (fails later at the deliberate epoch-manager stop) -
        // the refusal message must NOT be the chokepoint's.
        assertThat(result.message() == null || !result.message().contains("Production trigger refused"))
                .as("a non-production editor run must not be refused by the chokepoint")
                .isTrue();
    }

    private void setupThroughPlanResolution(WorkflowEntity workflow) {
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
        // Stop execution right after run.setPlan() + save - we verify the persisted plan state.
        when(epochManager.getCurrentEpoch(any(WorkflowRunEntity.class), anyString())).thenThrow(
                new RuntimeException("stop after plan persistence"));
    }

    private WorkflowEntity unpinnedWorkflow(Map<String, Object> plan) {
        WorkflowEntity w = new WorkflowEntity();
        w.setId(WORKFLOW_ID);
        w.setTenantId(TENANT_ID);
        w.setPinnedVersion(null);
        w.setPlan(plan);
        return w;
    }

    private WorkflowRunEntity runWith(Map<String, Object> plan, WorkflowEntity workflow) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID);
        run.setStatus(RunStatus.WAITING_TRIGGER);
        run.setExecutionMode(ExecutionMode.AUTOMATIC);
        run.setTenantId(TENANT_ID);
        run.setPlan(new HashMap<>(plan));
        run.setMetadata(new HashMap<>());
        ReflectionTestUtils.setField(run, "workflow", workflow);
        return run;
    }

    private Map<String, Object> planWith(List<Map<String, Object>> mcps, List<Map<String, Object>> edges) {
        Map<String, Object> plan = new HashMap<>();
        plan.put("id", WORKFLOW_ID.toString());
        plan.put("triggers", List.of(Map.of("type", "webhook", "label", "my_webhook")));
        plan.put("mcps", mcps);
        plan.put("agents", List.of());
        plan.put("cores", List.of());
        plan.put("tables", List.of());
        plan.put("interfaces", List.of());
        plan.put("edges", edges);
        return plan;
    }

    private Map<String, Object> mcp(String label, Map<String, Object> params) {
        Map<String, Object> step = new HashMap<>();
        step.put("label", label);
        step.put("service", "http");
        step.put("action", "get");
        step.put("params", new HashMap<>(params));
        return step;
    }

    private Map<String, Object> edge(String from, String to) {
        return Map.of("from", from, "to", to);
    }
}
