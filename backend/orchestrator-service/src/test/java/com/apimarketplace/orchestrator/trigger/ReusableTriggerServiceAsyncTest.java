package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.repository.WorkflowPlanVersionRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.TriggerResolverService;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.credit.CreditBudgetService;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.SnapshotService;
import com.apimarketplace.orchestrator.trigger.queue.ExecutionQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the contract of {@link ReusableTriggerService#executeTriggerAsync}, the
 * fire-and-forget HTTP path added so the Tomcat thread doesn't block on the
 * full epoch cycle when the run is in AUTOMATIC mode.
 *
 * <p>Specifically: a tenant with insufficient credits must NOT consume a
 * worker-pool slot - the credit gate runs in the controller thread before
 * dispatch.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReusableTriggerService - executeTriggerAsync")
class ReusableTriggerServiceAsyncTest {

    @Mock private WorkflowRunRepository runRepository;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowPlanVersionRepository planVersionRepository;
    @Mock private TriggerEpochManager epochManager;
    @Mock private WorkflowStreamingService streamingService;
    @Mock private WorkflowExecutionService executionService;
    @Mock private TriggerResolverService triggerResolverService;
    @Mock private StateSnapshotService stateSnapshotService;
    @Mock private EpochConcurrencyLimiter epochConcurrencyLimiter;
    @Mock private ExecutionQueue executionQueueService;
    @Mock private UnifiedSignalService unifiedSignalService;
    @Mock private SnapshotService snapshotService;
    @Mock private WorkflowResumeService resumeService;
    @Mock private CreditConsumptionClient creditClient;
    @Mock private CreditBudgetService creditBudgetService;

    @Mock private WorkflowRunEntity run;

    private ReusableTriggerService service;

    private static final String TENANT_ID = "tenant-broke";
    private static final String RUN_ID = "run-broke";
    private static final String TRIGGER_ID = "trigger:manual";

    @BeforeEach
    void setUp() {
        service = new ReusableTriggerService(
                runRepository, workflowRepository, planVersionRepository,
                epochManager, streamingService,
                executionService, triggerResolverService, stateSnapshotService,
                epochConcurrencyLimiter, executionQueueService, creditClient, creditBudgetService);
    }

    @Test
    @DisplayName("Insufficient credits: returns failure WITHOUT enqueueing on the worker pool")
    void insufficientCreditsShortCircuitsBeforeEnqueue() {
        when(run.getTenantId()).thenReturn(TENANT_ID);
        when(run.getRunIdPublic()).thenReturn(RUN_ID);
        when(creditClient.checkCredits(TENANT_ID)).thenReturn(false);

        TriggerExecutionResult result = service.executeTriggerAsync(
                run, TRIGGER_ID, TriggerType.MANUAL, Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Insufficient credits");
        // Worker pool must NOT be touched - the controller thread does the
        // credit check before dispatch so a denied tenant burns no slot.
        verify(executionQueueService, never())
                .enqueueAsync(any(), anyString(), any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("Sufficient credits: dispatches to the queue and returns the queue's accepted ack")
    void sufficientCreditsDispatchesToQueue() {
        when(run.getTenantId()).thenReturn(TENANT_ID);
        when(run.getMetadata()).thenReturn(Map.of("userPlan", "PRO"));
        when(creditClient.checkCredits(TENANT_ID)).thenReturn(true);

        TriggerExecutionResult ack = TriggerExecutionResult.accepted(
                RUN_ID, TRIGGER_ID, TriggerType.MANUAL);
        when(executionQueueService.enqueueAsync(run, TRIGGER_ID, TriggerType.MANUAL, Map.of(), "PRO", null))
                .thenReturn(ack);

        TriggerExecutionResult result = service.executeTriggerAsync(
                run, TRIGGER_ID, TriggerType.MANUAL, Map.of());

        assertThat(result.success()).isTrue();
        assertThat(result.epoch()).isEqualTo(-1);
        assertThat(result.readySteps()).isEqualTo(Set.of());
        verify(creditBudgetService).refreshBudget(TENANT_ID);
        verify(executionQueueService).enqueueAsync(run, TRIGGER_ID, TriggerType.MANUAL, Map.of(), "PRO", null);
    }

    @Test
    @DisplayName("Binds run organizationId before async queue dispatch")
    void bindsRunOrganizationIdBeforeAsyncQueueDispatch() {
        AtomicReference<String> observedOrg = new AtomicReference<>();
        when(run.getOrganizationId()).thenReturn("org-1");
        when(run.getTenantId()).thenReturn(TENANT_ID);
        when(run.getMetadata()).thenReturn(Map.of("userPlan", "PRO"));
        when(creditClient.checkCredits(TENANT_ID)).thenReturn(true);

        TriggerExecutionResult ack = TriggerExecutionResult.accepted(
                RUN_ID, TRIGGER_ID, TriggerType.MANUAL);
        when(executionQueueService.enqueueAsync(run, TRIGGER_ID, TriggerType.MANUAL, Map.of(), "PRO", null))
                .thenAnswer(invocation -> {
                    observedOrg.set(TenantResolver.currentRequestOrganizationId());
                    return ack;
                });

        service.executeTriggerAsync(run, TRIGGER_ID, TriggerType.MANUAL, Map.of());

        assertThat(observedOrg).hasValue("org-1");
    }

    @Test
    @DisplayName("Binds run organizationId before sync queue dispatch")
    void bindsRunOrganizationIdBeforeSyncQueueDispatch() {
        AtomicReference<String> observedOrg = new AtomicReference<>();
        when(run.getOrganizationId()).thenReturn("org-1");
        when(run.getTenantId()).thenReturn(TENANT_ID);
        when(run.getMetadata()).thenReturn(Map.of("userPlan", "PRO"));
        when(creditClient.checkCredits(TENANT_ID)).thenReturn(true);

        TriggerExecutionResult ok = TriggerExecutionResult.accepted(
                RUN_ID, TRIGGER_ID, TriggerType.MANUAL);
        when(executionQueueService.enqueueAndWait(run, TRIGGER_ID, TriggerType.MANUAL, Map.of(), "PRO", null))
                .thenAnswer(invocation -> {
                    observedOrg.set(TenantResolver.currentRequestOrganizationId());
                    return ok;
                });

        service.executeTrigger(run, TRIGGER_ID, TriggerType.MANUAL, Map.of());

        assertThat(observedOrg).hasValue("org-1");
    }

    @Test
    @DisplayName("User payload cannot choose the internal execution queue request id")
    void payloadRequestIdMarkerIsIgnored() {
        when(run.getTenantId()).thenReturn(TENANT_ID);
        when(run.getMetadata()).thenReturn(Map.of("userPlan", "PRO"));
        when(creditClient.checkCredits(TENANT_ID)).thenReturn(true);
        Map<String, Object> payload = Map.of("__executionQueueRequestId", "attacker-controlled");
        TriggerExecutionResult ok = TriggerExecutionResult.accepted(RUN_ID, TRIGGER_ID, TriggerType.MANUAL);
        when(executionQueueService.enqueueAndWait(run, TRIGGER_ID, TriggerType.MANUAL, payload, "PRO", null))
                .thenReturn(ok);

        service.executeTrigger(run, TRIGGER_ID, TriggerType.MANUAL, payload);

        verify(executionQueueService).enqueueAndWait(
                org.mockito.ArgumentMatchers.eq(run),
                org.mockito.ArgumentMatchers.eq(TRIGGER_ID),
                org.mockito.ArgumentMatchers.eq(TriggerType.MANUAL),
                org.mockito.ArgumentMatchers.eq(payload),
                org.mockito.ArgumentMatchers.eq("PRO"),
                isNull());
    }
}
