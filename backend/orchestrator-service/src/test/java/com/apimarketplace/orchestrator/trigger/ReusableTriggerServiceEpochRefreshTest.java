package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.StepByStepExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.services.V2StepByStepService;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
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
import com.apimarketplace.common.credit.CreditConsumptionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 * Verifies the epoch-level plan refresh + topology guard added to
 * {@link ReusableTriggerService#executeTriggerInternal}.
 *
 * <p>Rule under test: on each trigger fire, if the resolved plan differs from
 * the run's frozen plan and the two are topology-compatible (same node ids,
 * same directed edges), the run adopts the live-edited plan. If topology
 * diverges, the run stays on its frozen plan and a WARN is logged.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReusableTriggerService - Epoch-Level Plan Refresh")
class ReusableTriggerServiceEpochRefreshTest {

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

    private ReusableTriggerService service;

    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final String RUN_ID = "run-refresh-1";
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

    // Topology comparator has its own dedicated unit test -
    // see PlanTopologyTest for structural-equivalence coverage.

    // ====================================================================
    // executeTriggerInternal - refresh behavior end-to-end
    // ====================================================================

    @Test
    @DisplayName("Epoch > 0 + params edit: run.plan is refreshed to live workflow plan")
    void refreshesRunPlanWhenParamsChangeBetweenEpochs() {
        Map<String, Object> oldPlan = planWith("my_webhook", List.of(mcp("Step A", Map.of("url", "v1"))),
                List.of(edge("trigger:my_webhook", "mcp:step_a")));
        Map<String, Object> liveEditedPlan = planWith("my_webhook", List.of(mcp("Step A", Map.of("url", "v2"))),
                List.of(edge("trigger:my_webhook", "mcp:step_a")));

        WorkflowEntity workflow = unpinnedWorkflow(liveEditedPlan);
        WorkflowRunEntity run = runWith(oldPlan, workflow);

        setupThroughPlanResolution(run, workflow, oldPlan);

        service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

        // The run entity should now hold the live-edited plan (v2 url), not the old one (v1).
        Object stepANode = ((List<?>) run.getPlan().get("mcps")).get(0);
        Map<?, ?> stepAParams = (Map<?, ?>) ((Map<?, ?>) stepANode).get("params");
        assertThat(stepAParams.get("url")).isEqualTo("v2");
    }

    @Test
    @DisplayName("Epoch > 0 + node added: topology change → run stays on frozen plan, WARN logged")
    void keepsFrozenPlanWhenTopologyChangesBetweenEpochs() {
        Map<String, Object> frozenPlan = planWith("my_webhook", List.of(mcp("Step A", Map.of("url", "v1"))),
                List.of(edge("trigger:my_webhook", "mcp:step_a")));
        Map<String, Object> structurallyChangedPlan = planWith("my_webhook",
                List.of(mcp("Step A", Map.of("url", "v1")), mcp("Step B", Map.of())),
                List.of(edge("trigger:my_webhook", "mcp:step_a"), edge("mcp:step_a", "mcp:step_b")));

        WorkflowEntity workflow = unpinnedWorkflow(structurallyChangedPlan);
        WorkflowRunEntity run = runWith(frozenPlan, workflow);

        setupThroughPlanResolution(run, workflow, frozenPlan);

        service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

        // Frozen plan must be preserved - still a single node.
        List<?> mcps = (List<?>) run.getPlan().get("mcps");
        assertThat(mcps).hasSize(1);
        assertThat(((Map<?, ?>) mcps.get(0)).get("label")).isEqualTo("Step A");
    }

    @Test
    @DisplayName("Pinned + matching version: run plan is never refreshed from draft edits")
    void doesNotRefreshWhenPinnedVersionMatches() {
        Map<String, Object> pinnedPlan = planWith("my_webhook", List.of(mcp("Step A", Map.of("url", "v1"))),
                List.of(edge("trigger:my_webhook", "mcp:step_a")));
        Map<String, Object> draftEdits = planWith("my_webhook", List.of(mcp("Step A", Map.of("url", "v2"))),
                List.of(edge("trigger:my_webhook", "mcp:step_a")));

        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(WORKFLOW_ID);
        workflow.setTenantId(TENANT_ID);
        workflow.setPinnedVersion(5);
        workflow.setPlan(draftEdits); // draft has been edited

        WorkflowRunEntity run = runWith(pinnedPlan, workflow);
        run.setPlanVersion(5); // run is ON the pinned version

        setupThroughPlanResolution(run, workflow, pinnedPlan);

        service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

        // Pinning is immutable - draft edits (v2) must NOT leak into the pinned run.
        Map<?, ?> params = (Map<?, ?>) ((Map<?, ?>) ((List<?>) run.getPlan().get("mcps")).get(0)).get("params");
        assertThat(params.get("url")).isEqualTo("v1");
        verify(planVersionRepository, never()).findByWorkflowIdAndVersion(any(), anyInt());
    }

    @Test
    @DisplayName("Pinned + version mismatch + safety-net returns a plan with different topology: run stays on frozen plan")
    void pinnedMismatchSafetyNetReloadWithTopologyChangeKeepsFrozenPlan() {
        Map<String, Object> frozenPlan = planWith("my_webhook", List.of(mcp("Step A", Map.of("url", "v1"))),
                List.of(edge("trigger:my_webhook", "mcp:step_a")));
        Map<String, Object> pinnedVersionPlan = planWith("my_webhook",
                List.of(mcp("Step A", Map.of("url", "v1")), mcp("Step B", Map.of())),
                List.of(edge("trigger:my_webhook", "mcp:step_a"), edge("mcp:step_a", "mcp:step_b")));

        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(WORKFLOW_ID);
        workflow.setTenantId(TENANT_ID);
        workflow.setPinnedVersion(9);
        workflow.setPlan(pinnedVersionPlan);

        WorkflowRunEntity run = runWith(frozenPlan, workflow);
        run.setPlanVersion(3); // mismatch → safety-net branch fires

        setupThroughPlanResolution(run, workflow, frozenPlan);
        // Latest run query used by safety-net - return empty so it falls through to version table.
        lenient().when(runRepository.findFirstByWorkflowIdAndPlanVersionOrderByStartedAtDesc(WORKFLOW_ID, 9))
                .thenReturn(Optional.empty());
        com.apimarketplace.orchestrator.domain.WorkflowPlanVersionEntity versionEntity =
                new com.apimarketplace.orchestrator.domain.WorkflowPlanVersionEntity();
        versionEntity.setPlan(pinnedVersionPlan);
        lenient().when(planVersionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 9))
                .thenReturn(Optional.of(versionEntity));

        service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

        // Topology guard must preserve the frozen plan + its version - the safety-net
        // reload produced a structurally different plan that would corrupt StateSnapshot.
        List<?> mcps = (List<?>) run.getPlan().get("mcps");
        assertThat(mcps).hasSize(1);
        assertThat(run.getPlanVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("Inspector edit via PLAN_FROM_PAYLOAD_MARKER: run.plan is preserved, workflow.plan ignored")
    void payloadMarkerSkipsUnpinnedOverride() {
        Map<String, Object> userInspectorEdit = planWith("my_webhook",
                List.of(mcp("Step A", Map.of("provider", "google"))),
                List.of(edge("trigger:my_webhook", "mcp:step_a")));
        Map<String, Object> stalerWorkflowPlan = planWith("my_webhook",
                List.of(mcp("Step A", Map.of("provider", "anthropic"))),
                List.of(edge("trigger:my_webhook", "mcp:step_a")));

        WorkflowEntity workflow = unpinnedWorkflow(stalerWorkflowPlan);
        // run.plan was just written by updateRunPlan with the user's payload (provider=google).
        WorkflowRunEntity run = runWith(userInspectorEdit, workflow);

        // state.plan() reflects what updateRunPlan persisted, NOT workflow.plan.
        setupThroughPlanResolution(run, workflow, userInspectorEdit);

        Map<String, Object> payload = new HashMap<>();
        payload.put(ReusableTriggerService.PLAN_FROM_PAYLOAD_MARKER, Boolean.TRUE);

        service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.MANUAL, payload, false);

        // The marker must steer execution to the user's plan, not the stale workflow.plan.
        Map<?, ?> params = (Map<?, ?>) ((Map<?, ?>) ((List<?>) run.getPlan().get("mcps")).get(0)).get("params");
        assertThat(params.get("provider")).isEqualTo("google");
    }

    @Test
    @DisplayName("Caller's payload map is never mutated when the marker is stripped (defensive copy)")
    void callerPayloadMapNeverMutated() {
        Map<String, Object> samePlan = planWith("my_webhook",
                List.of(mcp("Step A", Map.of("provider", "google"))),
                List.of(edge("trigger:my_webhook", "mcp:step_a")));

        WorkflowEntity workflow = unpinnedWorkflow(samePlan);
        WorkflowRunEntity run = runWith(samePlan, workflow);
        setupThroughPlanResolution(run, workflow, samePlan);

        Map<String, Object> payload = new HashMap<>();
        payload.put(ReusableTriggerService.PLAN_FROM_PAYLOAD_MARKER, Boolean.TRUE);
        payload.put("user_field", "kept");

        service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.MANUAL, payload, false);

        // executeTriggerInternal must defensive-copy before stripping; the
        // caller's reference must come back unchanged.
        assertThat(payload).containsEntry(ReusableTriggerService.PLAN_FROM_PAYLOAD_MARKER, Boolean.TRUE);
        assertThat(payload).containsEntry("user_field", "kept");
    }

    @Test
    @DisplayName("Forged marker (no companion plan) is honored at service layer - controller-side sanitization is the real defense")
    void forgedMarkerAtServiceLayerStillSkipsWorkflowPlanRefresh() {
        // The marker is set ONLY by TriggerController, ONLY after a successful
        // updateRunPlan. If a payload reaches executeTriggerInternal with the
        // marker but no actual plan was written, the service layer trusts the
        // marker and skips the workflow.plan refresh. The defense against this
        // forge vector lives at the controller boundary (sanitizePlanMarker),
        // tested separately. This test pins the service-layer contract: the
        // gate is steered solely by the marker presence, not by re-checking
        // who set it.
        Map<String, Object> staleRunPlan = planWith("my_webhook",
                List.of(mcp("Step A", Map.of("provider", "anthropic"))),
                List.of(edge("trigger:my_webhook", "mcp:step_a")));
        Map<String, Object> editorSavedPlan = planWith("my_webhook",
                List.of(mcp("Step A", Map.of("provider", "google"))),
                List.of(edge("trigger:my_webhook", "mcp:step_a")));

        WorkflowEntity workflow = unpinnedWorkflow(editorSavedPlan);
        WorkflowRunEntity run = runWith(staleRunPlan, workflow);
        setupThroughPlanResolution(run, workflow, staleRunPlan);

        Map<String, Object> payload = new HashMap<>();
        payload.put(ReusableTriggerService.PLAN_FROM_PAYLOAD_MARKER, Boolean.TRUE);

        service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.MANUAL, payload, false);

        // Marker honored → workflow.plan refresh skipped → stale run.plan preserved.
        // If someone ever moves the strip block AFTER the gate evaluation, this
        // assertion will flip to "google" and the test will catch it.
        Map<?, ?> params = (Map<?, ?>) ((Map<?, ?>) ((List<?>) run.getPlan().get("mcps")).get(0)).get("params");
        assertThat(params.get("provider")).isEqualTo("anthropic");
    }

    @Test
    @DisplayName("sanitizePlanMarker helper strips the internal marker from any payload")
    void sanitizePlanMarkerHelperStripsKey() {
        Map<String, Object> dirty = new HashMap<>();
        dirty.put(ReusableTriggerService.PLAN_FROM_PAYLOAD_MARKER, Boolean.TRUE);
        dirty.put("legit_field", "value");

        Map<String, Object> cleaned = ReusableTriggerService.sanitizePlanMarker(dirty);

        assertThat(cleaned).doesNotContainKey(ReusableTriggerService.PLAN_FROM_PAYLOAD_MARKER);
        assertThat(cleaned).containsEntry("legit_field", "value");
        // Original is untouched (defensive copy contract).
        assertThat(dirty).containsKey(ReusableTriggerService.PLAN_FROM_PAYLOAD_MARKER);
    }

    @Test
    @DisplayName("sanitizePlanMarker is a no-allocation pass-through when marker is absent")
    void sanitizePlanMarkerHelperPassThroughWhenMarkerAbsent() {
        Map<String, Object> clean = new HashMap<>();
        clean.put("only_user_data", "value");

        Map<String, Object> result = ReusableTriggerService.sanitizePlanMarker(clean);

        assertThat(result).isSameAs(clean);
    }

    @Test
    @DisplayName("sanitizePlanMarker tolerates a null payload")
    void sanitizePlanMarkerHelperTolerantOfNull() {
        assertThat(ReusableTriggerService.sanitizePlanMarker(null)).isNull();
    }

    @Test
    @DisplayName("Passive fire (no marker) on unpinned still picks up workflow.plan - regression-safe for webhook/schedule")
    void passiveFireWithoutMarkerStillReloadsWorkflowPlan() {
        Map<String, Object> staleRunPlan = planWith("my_webhook",
                List.of(mcp("Step A", Map.of("provider", "anthropic"))),
                List.of(edge("trigger:my_webhook", "mcp:step_a")));
        Map<String, Object> editorSavedPlan = planWith("my_webhook",
                List.of(mcp("Step A", Map.of("provider", "google"))),
                List.of(edge("trigger:my_webhook", "mcp:step_a")));

        WorkflowEntity workflow = unpinnedWorkflow(editorSavedPlan);
        WorkflowRunEntity run = runWith(staleRunPlan, workflow);

        setupThroughPlanResolution(run, workflow, staleRunPlan);

        // No marker → passive-fire semantics → run adopts workflow.plan.
        service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

        Map<?, ?> params = (Map<?, ?>) ((Map<?, ?>) ((List<?>) run.getPlan().get("mcps")).get(0)).get("params");
        assertThat(params.get("provider")).isEqualTo("google");
    }

    @Test
    @DisplayName("Every trigger fire stamps WorkflowEntity.lastExecutedAt - workflow board refresh fix")
    void triggerFireStampsWorkflowLastExecutedAt() {
        Map<String, Object> plan = planWith("my_webhook", List.of(mcp("Step A", Map.of("url", "v1"))),
                List.of(edge("trigger:my_webhook", "mcp:step_a")));

        WorkflowEntity workflow = unpinnedWorkflow(plan);
        // Old timestamp simulating run-creation stamp from days ago.
        Instant oldStamp = Instant.now().minusSeconds(86_400);
        workflow.setLastExecutedAt(oldStamp);

        WorkflowRunEntity run = runWith(plan, workflow);

        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        WorkflowRunState state = new WorkflowRunState(
                RUN_ID, WORKFLOW_ID.toString(), RunStatus.RUNNING, ExecutionMode.AUTOMATIC,
                Instant.now(), null, plan, List.of(), List.of(),
                Set.of(), Set.of(), Set.of(), Set.of(), Map.of());

        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
        lenient().when(planVersionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(7));
        // Let epoch management run normally.
        when(epochManager.getCurrentEpoch(any(WorkflowRunEntity.class), anyString())).thenReturn(0);
        when(epochManager.incrementEpoch(any(WorkflowRunEntity.class), anyString())).thenReturn(1);
        // Stop right after the workflow.lastExecutedAt bulk update (next external call).
        doThrow(new RuntimeException("stop after lastExecutedAt bulk update"))
                .when(stateSnapshotService).openEpoch(anyString(), anyString(), anyInt());

        Instant before = Instant.now();
        service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

        // The repository must receive a fresh lastExecutedAt without mutating the detached workflow entity.
        ArgumentCaptor<Instant> stampCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(workflowRepository).updateLastExecutedAt(eq(WORKFLOW_ID), stampCaptor.capture());
        assertThat(stampCaptor.getValue()).isAfterOrEqualTo(before);
        assertThat(workflow.getLastExecutedAt()).isEqualTo(oldStamp);
        verify(workflowRepository, never()).save(workflow);
    }

    @Test
    @DisplayName("Trigger fire MUST NOT touch updatedAt - workflows tab Modified column reflects real edits only")
    void triggerFireDoesNotPolluteUpdatedAt() {
        // Regression: a previous fix (commit 2a083618b) stamped both lastExecutedAt
        // AND updatedAt on every fire, causing the workflows tab "Modified" column
        // to track trigger fires instead of real user edits. updatedAt must remain
        // untouched here - only WorkflowManagementService / WorkflowEntityResolverService
        // (real edits) are allowed to bump it.
        Map<String, Object> plan = planWith("my_webhook", List.of(mcp("Step A", Map.of("url", "v1"))),
                List.of(edge("trigger:my_webhook", "mcp:step_a")));

        WorkflowEntity workflow = unpinnedWorkflow(plan);
        Instant editStamp = Instant.now().minusSeconds(2 * 86_400);  // last real edit 2 days ago
        workflow.setLastExecutedAt(Instant.now().minusSeconds(86_400));
        workflow.setUpdatedAt(editStamp);

        WorkflowRunEntity run = runWith(plan, workflow);

        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
        lenient().when(planVersionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(7));
        when(epochManager.getCurrentEpoch(any(WorkflowRunEntity.class), anyString())).thenReturn(0);
        when(epochManager.incrementEpoch(any(WorkflowRunEntity.class), anyString())).thenReturn(1);
        // Stop right after the workflow.lastExecutedAt bulk update so we observe state mid-fire.
        doThrow(new RuntimeException("stop after lastExecutedAt bulk update"))
                .when(stateSnapshotService).openEpoch(anyString(), anyString(), anyInt());

        Instant before = Instant.now();
        service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

        ArgumentCaptor<Instant> stampCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(workflowRepository).updateLastExecutedAt(eq(WORKFLOW_ID), stampCaptor.capture());
        assertThat(stampCaptor.getValue()).isAfterOrEqualTo(before);
        // updatedAt MUST remain pinned to the original edit stamp - no fire pollution.
        assertThat(workflow.getUpdatedAt()).isEqualTo(editStamp);
    }

    @Test
    @DisplayName("Plans identical: refresh is a no-op, no reversion, no WARN")
    void refreshIsNoopWhenPlansIdentical() {
        Map<String, Object> samePlan = planWith("my_webhook", List.of(mcp("Step A", Map.of("url", "v1"))),
                List.of(edge("trigger:my_webhook", "mcp:step_a")));

        WorkflowEntity workflow = unpinnedWorkflow(samePlan);
        WorkflowRunEntity run = runWith(samePlan, workflow);

        setupThroughPlanResolution(run, workflow, samePlan);

        service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of(), false);

        // Identity preserved - nothing to refresh, nothing to revert.
        Map<?, ?> params = (Map<?, ?>) ((Map<?, ?>) ((List<?>) run.getPlan().get("mcps")).get(0)).get("params");
        assertThat(params.get("url")).isEqualTo("v1");
    }

    @Test
    @DisplayName("Concurrent trigger fire uses the epoch captured at increment time")
    void concurrentTriggerFireUsesCapturedEpochInsteadOfLatestDagEpoch() {
        Map<String, Object> plan = planWith("my_webhook", List.of(mcp("Step A", Map.of("url", "v1"))),
                List.of(edge("trigger:my_webhook", "mcp:step_a")));
        WorkflowEntity workflow = unpinnedWorkflow(plan);
        WorkflowRunEntity run = runWith(plan, workflow);
        run.setExecutionMode(ExecutionMode.STEP_BY_STEP);

        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
        lenient().when(planVersionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(7));
        when(stateSnapshotService.closeAllActiveEpochs(RUN_ID, TRIGGER_ID)).thenReturn(Set.of());
        when(epochManager.getCurrentEpoch(any(WorkflowRunEntity.class), eq(TRIGGER_ID))).thenReturn(0);
        when(epochManager.incrementEpoch(any(WorkflowRunEntity.class), eq(TRIGGER_ID))).thenReturn(1);
        lenient().when(epochManager.getGlobalEpochForDag(RUN_ID, TRIGGER_ID)).thenReturn(99);
        when(resumeService.getExecutionMode(RUN_ID)).thenReturn(ExecutionMode.STEP_BY_STEP);
        when(v2StepByStepService.executeNode(RUN_ID, TRIGGER_ID, "0", 1, TRIGGER_ID))
                .thenReturn(triggerSuccess(Set.of("core:after")));
        when(stateSnapshotService.getReadyNodeIds(RUN_ID)).thenReturn(Set.of("core:after"));

        TriggerExecutionResult result = service.executeTriggerInternal(
                run, TRIGGER_ID, TriggerType.WEBHOOK, Map.of("name", "Ada"), false);

        assertThat(result.success()).isTrue();
        assertThat(result.epoch()).isEqualTo(1);
        assertThat(result.readySteps()).containsExactly("core:after");
        verify(v2StepByStepService).cacheTriggerPayload(
                eq(RUN_ID), eq(1), argThat(payload -> "Ada".equals(payload.get("name"))));
        verify(v2StepByStepService, never()).cacheTriggerPayload(
                eq(RUN_ID), any(Map.class));
        verify(v2StepByStepService).executeNode(RUN_ID, TRIGGER_ID, "0", 1, TRIGGER_ID);
        verify(v2StepByStepService, never()).executeNode(RUN_ID, TRIGGER_ID, "0", 99, TRIGGER_ID);
    }

    // ====================================================================
    // Helpers
    // ====================================================================

    private void setupThroughPlanResolution(
            WorkflowRunEntity run, WorkflowEntity workflow, Map<String, Object> statePlan) {
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        WorkflowRunState state = new WorkflowRunState(
                RUN_ID, WORKFLOW_ID.toString(), RunStatus.RUNNING, ExecutionMode.AUTOMATIC,
                Instant.now(), null, statePlan, List.of(), List.of(),
                Set.of(), Set.of(), Set.of(), Set.of(), Map.of());

        if (workflow != null) {
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
        }
        lenient().when(runRepository.findByRunIdPublicForUpdate(RUN_ID)).thenReturn(Optional.of(run));
        lenient().when(planVersionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(7));
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
        run.setTenantId(TENANT_ID);
        run.setPlan(new HashMap<>(plan));
        run.setPlanVersion(7);
        run.setMetadata(new HashMap<>());
        ReflectionTestUtils.setField(run, "workflow", workflow);
        return run;
    }

    private Map<String, Object> planWith(String webhookLabel,
                                         List<Map<String, Object>> mcps,
                                         List<Map<String, Object>> edges) {
        Map<String, Object> plan = new HashMap<>();
        plan.put("id", WORKFLOW_ID.toString());
        plan.put("triggers", List.of(Map.of("type", "webhook", "label", webhookLabel)));
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

    private StepByStepExecutionResult triggerSuccess(Set<String> readyNodes) {
        ExecutionContext context = ExecutionContext.create(
                RUN_ID, null, TENANT_ID, "0", 0, TRIGGER_ID, 0, 0,
                Map.of(), null);
        return StepByStepExecutionResult.success(
                context,
                NodeExecutionResult.success(TRIGGER_ID, Map.of()),
                readyNodes);
    }
}
