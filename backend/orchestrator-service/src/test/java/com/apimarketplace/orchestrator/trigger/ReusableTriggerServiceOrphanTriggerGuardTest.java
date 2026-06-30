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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the fire-time defense-in-depth guard in
 * {@link ReusableTriggerService#executeTriggerInternal}: an externally-fired trigger
 * (schedule / webhook / chat / form / datasource) whose key is NOT present in the
 * resolved plan must NOT open an epoch.
 *
 * <p>Context: a schedule whose trigger was deleted from the plan kept firing because the
 * stale trigger-service row was repeatedly resurrected by the bulk re-arm (fixed
 * separately in trigger-service). Before this guard there was NO plan-membership check at
 * fire time, so a stale registration of ANY external type could open epochs forever. This
 * guard is the type-agnostic backstop.
 *
 * <p>Scoping is asserted too: MANUAL fires (editor/inspector) are NOT gated, because their
 * trigger ids are not always declared in {@code plan.getTriggers()}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReusableTriggerService - orphan-trigger fire guard (trigger absent from plan)")
class ReusableTriggerServiceOrphanTriggerGuardTest {

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
    private static final String RUN_ID = "run-orphan-guard-1";
    private static final String TENANT_ID = "tenant-1";
    private static final int PINNED = 5;

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
    @DisplayName("SCHEDULE fire refused when its trigger was removed from the plan - no epoch opened")
    void orphanScheduleTriggerNotInPlan_refusedWithoutEpoch() {
        // Plan declares only 'other_poll'; the fired 'reponses_poll' was deleted.
        Map<String, Object> plan = planWithTriggers(
                List.of(triggerNode("schedule", "other_poll")),
                List.of(edge("trigger:other_poll", "mcp:step_a")));
        prime(plan);

        TriggerExecutionResult result = service.executeTriggerInternal(
                runFor(plan), "trigger:reponses_poll", TriggerType.SCHEDULE, Map.of(), false);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("no longer exists in the workflow plan");
        verify(epochManager, never()).incrementEpoch(any(WorkflowRunEntity.class), anyString());
    }

    @Test
    @DisplayName("WEBHOOK fire refused when its trigger was removed from the plan (guard is type-agnostic, not schedule-only)")
    void orphanWebhookTriggerNotInPlan_refusedWithoutEpoch() {
        Map<String, Object> plan = planWithTriggers(
                List.of(triggerNode("schedule", "other_poll")),
                List.of(edge("trigger:other_poll", "mcp:step_a")));
        prime(plan);

        TriggerExecutionResult result = service.executeTriggerInternal(
                runFor(plan), "trigger:gone_webhook", TriggerType.WEBHOOK, Map.of(), false);

        assertThat(result.success()).isFalse();
        verify(epochManager, never()).incrementEpoch(any(WorkflowRunEntity.class), anyString());
    }

    @Test
    @DisplayName("SCHEDULE fire refused when the plan declares NO triggers at all (the last trigger was deleted)")
    void scheduleFireRefusedWhenPlanHasNoTriggers() {
        // Empty triggers list is a fully-resolved "no triggers exist" plan: an external fire
        // against it is an orphan and must be refused (pins the dropped !isEmpty() guard).
        Map<String, Object> plan = planWithTriggers(List.of(), List.of());
        prime(plan);

        TriggerExecutionResult result = service.executeTriggerInternal(
                runFor(plan), "trigger:reponses_poll", TriggerType.SCHEDULE, Map.of(), false);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("no longer exists in the workflow plan");
        verify(epochManager, never()).incrementEpoch(any(WorkflowRunEntity.class), anyString());
    }

    @Test
    @DisplayName("SCHEDULE fire allowed when its trigger IS declared in the plan - reaches epoch increment")
    void presentScheduleTrigger_passesGuard_reachesEpoch() {
        Map<String, Object> plan = planWithTriggers(
                List.of(triggerNode("schedule", "reponses_poll")),
                List.of(edge("trigger:reponses_poll", "mcp:step_a")));
        prime(plan);
        // Stop right at the epoch section so we don't have to mock the whole downstream.
        when(epochManager.incrementEpoch(any(WorkflowRunEntity.class), eq("trigger:reponses_poll")))
                .thenThrow(new RuntimeException("reached epoch section"));

        service.executeTriggerInternal(
                runFor(plan), "trigger:reponses_poll", TriggerType.SCHEDULE, Map.of(), false);

        verify(epochManager).incrementEpoch(any(WorkflowRunEntity.class), eq("trigger:reponses_poll"));
    }

    @Test
    @DisplayName("MANUAL fire is NOT gated on plan membership (editor runs may use ids absent from plan.triggers)")
    void manualTriggerNotInPlan_notBlockedByGuard() {
        Map<String, Object> plan = planWithTriggers(
                List.of(triggerNode("schedule", "other_poll")),
                List.of(edge("trigger:other_poll", "mcp:step_a")));
        prime(plan);
        when(epochManager.incrementEpoch(any(WorkflowRunEntity.class), eq("trigger:adhoc")))
                .thenThrow(new RuntimeException("reached epoch section"));

        service.executeTriggerInternal(
                runFor(plan), "trigger:adhoc", TriggerType.MANUAL, Map.of(), false);

        // Reached the epoch section => the guard did not block the MANUAL fire.
        verify(epochManager).incrementEpoch(any(WorkflowRunEntity.class), eq("trigger:adhoc"));
    }

    // ---- helpers -----------------------------------------------------------

    /** Common mock priming to drive executeTriggerInternal through plan resolution (pinned path). */
    private void prime(Map<String, Object> plan) {
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(pinnedWorkflow(plan)));
    }

    private WorkflowEntity pinnedWorkflow(Map<String, Object> plan) {
        WorkflowEntity w = new WorkflowEntity();
        w.setId(WORKFLOW_ID);
        w.setTenantId(TENANT_ID);
        w.setPinnedVersion(PINNED);
        w.setPlan(plan);
        return w;
    }

    private WorkflowRunEntity runFor(Map<String, Object> plan) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID);
        run.setStatus(RunStatus.WAITING_TRIGGER);
        run.setExecutionMode(ExecutionMode.AUTOMATIC);
        run.setTenantId(TENANT_ID);
        run.setPlanVersion(PINNED);
        run.setPlan(new HashMap<>(plan));
        run.setMetadata(new HashMap<>());
        ReflectionTestUtils.setField(run, "workflow", pinnedWorkflow(plan));
        return run;
    }

    private Map<String, Object> planWithTriggers(List<Map<String, Object>> triggers,
                                                 List<Map<String, Object>> edges) {
        Map<String, Object> plan = new HashMap<>();
        plan.put("id", WORKFLOW_ID.toString());
        plan.put("triggers", triggers);
        plan.put("mcps", List.of(mcp()));
        plan.put("agents", List.of());
        plan.put("cores", List.of());
        plan.put("tables", List.of());
        plan.put("interfaces", List.of());
        plan.put("edges", edges);
        return plan;
    }

    private Map<String, Object> triggerNode(String type, String label) {
        Map<String, Object> t = new HashMap<>();
        t.put("id", label);
        t.put("label", label);
        t.put("type", type);
        return t;
    }

    private Map<String, Object> mcp() {
        Map<String, Object> step = new HashMap<>();
        step.put("label", "Step A");
        step.put("service", "http");
        step.put("action", "get");
        step.put("params", new HashMap<>());
        return step;
    }

    private Map<String, Object> edge(String from, String to) {
        return Map.of("from", from, "to", to);
    }
}
