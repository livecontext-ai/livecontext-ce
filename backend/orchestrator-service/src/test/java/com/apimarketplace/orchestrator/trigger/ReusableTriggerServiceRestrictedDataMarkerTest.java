package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.common.classification.DataSensitivity;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.StepByStepExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.execution.v2.services.V2StepByStepService;
import com.apimarketplace.orchestrator.repository.WorkflowPlanVersionRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.credit.CreditBudgetService;
import com.apimarketplace.orchestrator.services.persistence.StepPayloadService;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.SnapshotService;
import com.apimarketplace.orchestrator.trigger.queue.QueuedExecutionMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CASA LC-066: a fire that forwards Gmail / Drive content from elsewhere (a table row derived
 * from Gmail, the outputs of a restricted upstream run) carries
 * {@link ReusableTriggerService#RESTRICTED_DATA_MARKER}, and the replica that RUNS the fire marks
 * the run restricted before the fire writes its first payload.
 *
 * <p>The marker is honoured the same way whatever the trigger type, so these fires use a webhook
 * trigger: it needs no table configuration in the plan.
 *
 * <p>Why the marker exists at all: the dispatcher used to mark the run in its own memory, but the
 * production execution queue hands the fire to whichever replica claims it first. On the other
 * replica the run was unmarked, so the trigger payload holding the Gmail-derived row and every
 * later payload of that fire were stored NORMAL: kept past the 30-day limit and readable by any
 * model.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReusableTriggerService - a fire carrying restricted data marks its run on the executing replica (LC-066)")
class ReusableTriggerServiceRestrictedDataMarkerTest {

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
    @Mock private CreditBudgetService creditBudgetService;
    @Mock private StepPayloadService stepPayloadService;

    private ReusableTriggerService service;

    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final String RUN_ID = "run-restricted-fire-1";
    private static final String TRIGGER_ID = "trigger:on_row";
    private static final String TENANT_ID = "tenant-1";
    private static final String MARKER = ReusableTriggerService.RESTRICTED_DATA_MARKER;

    @BeforeEach
    void setUp() {
        service = new ReusableTriggerService(
                runRepository, workflowRepository, planVersionRepository,
                epochManager, streamingService,
                executionService, triggerResolverService, stateSnapshotService,
                epochConcurrencyLimiter, executionQueueService, creditBudgetService);
        ReflectionTestUtils.setField(service, "resumeService", resumeService);
        ReflectionTestUtils.setField(service, "v2StepByStepService", v2StepByStepService);
        ReflectionTestUtils.setField(service, "unifiedSignalService", unifiedSignalService);
        ReflectionTestUtils.setField(service, "snapshotService", snapshotService);
        ReflectionTestUtils.setField(service, "stepPayloadService", stepPayloadService);
        ReflectionTestUtils.setField(service, "self", service);
    }

    @Nested
    @DisplayName("executeTriggerInternal, on the replica that runs the fire")
    class OnTheExecutingReplica {

        @Test
        @DisplayName("regression: a marked fire marks its run restricted BEFORE the trigger node writes the first payload")
        void markedFireMarksTheRunBeforeTheTriggerNodeRuns() {
            WorkflowRunEntity run = fireThroughTheTriggerNode();
            Map<String, Object> payload = new HashMap<>();
            payload.put("subject", "Wire approved");
            payload.put(MARKER, Boolean.TRUE);

            TriggerExecutionResult result = service.executeTriggerInternal(
                    run, TRIGGER_ID, TriggerType.WEBHOOK, payload, false);

            assertThat(result.success()).isTrue();
            InOrder order = inOrder(stepPayloadService, v2StepByStepService);
            order.verify(stepPayloadService).markRunRestricted(RUN_ID);
            order.verify(v2StepByStepService).executeNode(RUN_ID, TRIGGER_ID, "0", 1, TRIGGER_ID);
        }

        @Test
        @DisplayName("the marker never reaches the trigger's payload; the row's own fields do")
        void markerIsStrippedFromTheTriggerPayload() {
            WorkflowRunEntity run = fireThroughTheTriggerNode();
            Map<String, Object> payload = new HashMap<>();
            payload.put("subject", "Wire approved");
            payload.put(MARKER, Boolean.TRUE);

            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, payload, false);

            Map<String, Object> cached = cachedTriggerPayload();
            assertThat(cached).doesNotContainKey(MARKER);
            assertThat(cached).containsEntry("subject", "Wire approved");
        }

        @Test
        @DisplayName("a fire without the marker never marks its run (the ordinary webhook / schedule fire)")
        void unmarkedFireNeverMarksTheRun() {
            WorkflowRunEntity run = fireThroughTheTriggerNode();

            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK,
                    new HashMap<>(Map.of("subject", "Weekly report")), false);

            verify(stepPayloadService, never()).markRunRestricted(anyString());
        }

        @Test
        @DisplayName("a marker whose value is not TRUE is stripped and not honoured")
        void nonTrueMarkerIsStrippedAndIgnored() {
            WorkflowRunEntity run = fireThroughTheTriggerNode();
            Map<String, Object> payload = new HashMap<>();
            payload.put("subject", "Weekly report");
            payload.put(MARKER, "yes");

            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK, payload, false);

            verify(stepPayloadService, never()).markRunRestricted(anyString());
            assertThat(cachedTriggerPayload()).doesNotContainKey(MARKER);
        }

        @Test
        @DisplayName("the caller's payload map is not mutated (the queue hands in an unmodifiable map)")
        void callersMapIsNotMutated() {
            WorkflowRunEntity run = fireThroughTheTriggerNode();
            Map<String, Object> payload = Map.of("subject", "Wire approved", MARKER, Boolean.TRUE);

            TriggerExecutionResult result = service.executeTriggerInternal(
                    run, TRIGGER_ID, TriggerType.WEBHOOK, payload, false);

            assertThat(result.success()).isTrue();
            assertThat(payload).containsEntry(MARKER, Boolean.TRUE);
        }
    }

    @Nested
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("re-audit: only a fire that runs marks, and a restricted table load marks too")
    class WhenTheRunIsMarked {

        @Test
        @DisplayName("regression: a marked fire the budget gate refuses leaves the run unmarked")
        void refusedMarkedFireLeavesTheRunUnmarked() {
            WorkflowRunEntity run = fireThroughTheTriggerNode();
            ((WorkflowEntity) ReflectionTestUtils.getField(run, "workflow")).setBudgetCredits(java.math.BigDecimal.TEN);
            ReusableTriggerService refusing = spy(service);
            doReturn("Workflow budget reached for this period").when(refusing)
                    .refuseFireIfBudgetReached(eq(RUN_ID), any());
            Map<String, Object> payload = new HashMap<>(Map.of("subject", "Wire approved", MARKER, Boolean.TRUE));

            TriggerExecutionResult result = refusing.executeTriggerInternal(
                    run, TRIGGER_ID, TriggerType.WEBHOOK, payload, false);

            assertThat(result.success()).isFalse();
            // The run never received this fire's data: marking it would restrict every later fire.
            verify(stepPayloadService, never()).markRunRestricted(anyString());
            verify(v2StepByStepService, never()).executeNode(anyString(), anyString(), anyString(), anyInt(), anyString());
        }

        @Test
        @DisplayName("a marked fire the production pin gate refuses leaves the run unmarked too")
        void pinRefusedMarkedFireLeavesTheRunUnmarked() {
            WorkflowRunEntity run = fireThroughTheTriggerNode();
            com.apimarketplace.orchestrator.trigger.ProductionRunResolver resolver =
                    org.mockito.Mockito.mock(com.apimarketplace.orchestrator.trigger.ProductionRunResolver.class);
            when(resolver.isAllowedForProduction(any(), any())).thenReturn(false);
            ReflectionTestUtils.setField(service, "productionRunResolver", resolver);

            TriggerExecutionResult result = service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.WEBHOOK,
                    new HashMap<>(Map.of("subject", "Wire approved", MARKER, Boolean.TRUE)), false);

            assertThat(result.success()).isFalse();
            verify(stepPayloadService, never()).markRunRestricted(anyString());
        }

        @Test
        @DisplayName("regression: a table trigger that loads RESTRICTED rows marks its run before the trigger node runs")
        void restrictedTableLoadMarksTheRun() {
            WorkflowRunEntity run = fireThroughTheTriggerNode("datasource");
            when(triggerResolverService.resolveTrigger(any(), eq(TENANT_ID), any())).thenReturn(Map.of(
                    "data", List.of(Map.of("id", 7, "data", Map.of("subject", "Wire approved"))),
                    DataSensitivity.CREDENTIAL_KEY, "RESTRICTED"));

            TriggerExecutionResult result = service.executeTriggerInternal(
                    run, TRIGGER_ID, TriggerType.DATASOURCE, new HashMap<>(), false);

            assertThat(result.success()).isTrue();
            InOrder order = inOrder(stepPayloadService, v2StepByStepService);
            order.verify(stepPayloadService).markRunRestricted(RUN_ID);
            order.verify(v2StepByStepService).executeNode(RUN_ID, TRIGGER_ID, "0", 1, TRIGGER_ID);
        }

        @Test
        @DisplayName("an ordinary table load leaves the run unmarked")
        void ordinaryTableLoadLeavesTheRunUnmarked() {
            WorkflowRunEntity run = fireThroughTheTriggerNode("datasource");
            when(triggerResolverService.resolveTrigger(any(), eq(TENANT_ID), any())).thenReturn(Map.of(
                    "data", List.of(Map.of("id", 7, "data", Map.of("subject", "Weekly report")))));

            service.executeTriggerInternal(run, TRIGGER_ID, TriggerType.DATASOURCE, new HashMap<>(), false);

            verify(stepPayloadService, never()).markRunRestricted(anyString());
        }
    }

    @Nested
    @DisplayName("how the marker is set and carried")
    class SettingAndCarrying {

        @Test
        @DisplayName("it survives the Redis execution queue's JSON round trip as Boolean TRUE")
        void markerSurvivesTheQueueRoundTrip() throws Exception {
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            WorkflowRunEntity run = new WorkflowRunEntity();
            run.setRunIdPublic(RUN_ID);
            run.setTenantId(TENANT_ID);
            QueuedExecutionMessage sent = QueuedExecutionMessage.fromRun(
                    run, TRIGGER_ID, TriggerType.DATASOURCE,
                    ReusableTriggerService.withRestrictedDataMarker(Map.of("subject", "Wire approved")),
                    "FREE", "req-1", true, Duration.ofSeconds(30), Clock.systemUTC());

            QueuedExecutionMessage received = mapper.readValue(
                    mapper.writeValueAsString(sent), QueuedExecutionMessage.class);

            assertThat(received.payload().get(MARKER)).isEqualTo(Boolean.TRUE);
        }

        @Test
        @DisplayName("withRestrictedDataMarker returns a marked copy and leaves the original untouched")
        void withRestrictedDataMarkerCopies() {
            Map<String, Object> original = new HashMap<>(Map.of("row_id", 7));

            Map<String, Object> marked = ReusableTriggerService.withRestrictedDataMarker(original);

            assertThat(marked).containsEntry(MARKER, Boolean.TRUE).containsEntry("row_id", 7);
            assertThat(original).doesNotContainKey(MARKER);
            assertThat(ReusableTriggerService.withRestrictedDataMarker(null)).containsOnlyKeys(MARKER);
        }

        @Test
        @DisplayName("regression: sanitizePlanMarker also strips the __dataSensitivity__ tag a public caller could write to restrict a run for good")
        void inboundSanitizationStripsTheSensitivityTag() {
            Map<String, Object> inbound = new HashMap<>(Map.of("field", "value",
                    DataSensitivity.CREDENTIAL_KEY, "RESTRICTED"));

            assertThat(ReusableTriggerService.sanitizePlanMarker(inbound))
                    .doesNotContainKey(DataSensitivity.CREDENTIAL_KEY).containsEntry("field", "value");
        }

        @Test
        @DisplayName("sanitizePlanMarker strips it from an inbound payload, so a caller cannot set it")
        void inboundSanitizationStripsTheMarker() {
            Map<String, Object> inbound = new HashMap<>(Map.of("field", "value", MARKER, Boolean.TRUE));

            Map<String, Object> cleaned = ReusableTriggerService.sanitizePlanMarker(inbound);

            assertThat(cleaned).doesNotContainKey(MARKER).containsEntry("field", "value");
            assertThat(inbound).containsKey(MARKER);
        }
    }

    // ==================== Helpers ====================

    /** A step-by-step run whose fire goes all the way through the trigger node. */
    private WorkflowRunEntity fireThroughTheTriggerNode() {
        return fireThroughTheTriggerNode("webhook");
    }

    private WorkflowRunEntity fireThroughTheTriggerNode(String triggerType) {
        Map<String, Object> plan = new HashMap<>();
        plan.put("id", WORKFLOW_ID.toString());
        plan.put("triggers", List.of("datasource".equals(triggerType)
                ? Map.of("type", "datasource", "label", "on_row", "id", "19")
                : Map.of("type", triggerType, "label", "on_row")));
        plan.put("mcps", List.of());
        plan.put("agents", List.of());
        plan.put("cores", List.of());
        plan.put("tables", List.of());
        plan.put("interfaces", List.of());
        plan.put("edges", List.of());

        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(WORKFLOW_ID);
        workflow.setTenantId(TENANT_ID);
        workflow.setPlan(plan);

        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID);
        run.setStatus(RunStatus.WAITING_TRIGGER);
        run.setTenantId(TENANT_ID);
        run.setPlan(new HashMap<>(plan));
        run.setPlanVersion(7);
        run.setMetadata(new HashMap<>());
        run.setExecutionMode(ExecutionMode.STEP_BY_STEP);
        ReflectionTestUtils.setField(run, "workflow", workflow);

        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
        lenient().when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        lenient().when(planVersionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(7));
        when(stateSnapshotService.closeAllActiveEpochs(RUN_ID, TRIGGER_ID)).thenReturn(Set.of());
        when(epochManager.getCurrentEpoch(any(WorkflowRunEntity.class), eq(TRIGGER_ID))).thenReturn(0);
        when(epochManager.incrementEpoch(any(WorkflowRunEntity.class), eq(TRIGGER_ID))).thenReturn(1);
        when(resumeService.getExecutionMode(RUN_ID)).thenReturn(ExecutionMode.STEP_BY_STEP);
        ExecutionContext context = ExecutionContext.create(
                RUN_ID, null, TENANT_ID, "0", 0, TRIGGER_ID, 0, 0, Map.of(), null);
        when(v2StepByStepService.executeNode(RUN_ID, TRIGGER_ID, "0", 1, TRIGGER_ID))
                .thenReturn(StepByStepExecutionResult.success(
                        context, NodeExecutionResult.success(TRIGGER_ID, Map.of()), Set.of()));
        lenient().when(stateSnapshotService.getReadyNodeIds(RUN_ID)).thenReturn(Set.of());
        return run;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cachedTriggerPayload() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(v2StepByStepService).cacheTriggerPayload(eq(RUN_ID), eq(1), captor.capture());
        return captor.getValue();
    }
}
