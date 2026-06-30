package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.repository.WorkflowPlanVersionRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.credit.CreditBudgetService;
import com.apimarketplace.orchestrator.services.events.WorkflowEpochFailedEvent;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P0 (notification V2): publisher-side regression tests for
 * {@link ReusableTriggerService#resetForNextCycle}.
 *
 * <p>The {@code WorkflowEpochFailedEvent} is published from
 * {@code resetForNextCycle} when {@code hasFailures=true}. Without these
 * tests the V172 documented gap (reusable-trigger workflows produce zero
 * notifications) is fixed in code but unguarded - a future refactor could
 * silently drop the {@code eventPublisher.publishEvent(...)} call and only
 * be caught in prod.
 *
 * <p>Audited 3-Opus convergent finding: "the actual P0 fix lives in the
 * publisher side, not the listener side; the 6 listener tests cannot fail
 * on pre-fix code." This class closes that gap.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReusableTriggerService - WorkflowEpochFailedEvent publish branch")
class ReusableTriggerServiceEpochFailedPublishTest {

    @Mock private WorkflowRunRepository runRepository;
    @Mock private TriggerEpochManager epochManager;
    @Mock private StateSnapshotService stateSnapshotService;
    @Mock private EpochConcurrencyLimiter epochConcurrencyLimiter;
    @Mock private UnifiedSignalService unifiedSignalService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ReusableTriggerService service;

    private static final String RUN_ID_PUBLIC = "run-public-1";
    private static final UUID RUN_DB_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORKFLOW_DB_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String TENANT_ID = "tenant-7";
    private static final Integer PLAN_VERSION = 5;
    private static final String TRIGGER_ID = "trigger:webhook";
    private static final int EPOCH = 3;

    @BeforeEach
    void setUp() {
        service = new ReusableTriggerService(
                runRepository,
                mock(WorkflowRepository.class),
                mock(WorkflowPlanVersionRepository.class),
                epochManager,
                mock(WorkflowStreamingService.class),
                mock(WorkflowExecutionService.class),
                mock(com.apimarketplace.orchestrator.services.TriggerResolverService.class),
                stateSnapshotService,
                epochConcurrencyLimiter,
                mock(com.apimarketplace.orchestrator.trigger.queue.ExecutionQueue.class),
                mock(CreditConsumptionClient.class),
                mock(CreditBudgetService.class));
        ReflectionTestUtils.setField(service, "unifiedSignalService", unifiedSignalService);
        ReflectionTestUtils.setField(service, "self", service);
        ReflectionTestUtils.setField(service, "eventPublisher", eventPublisher);

        // No active signals - let the reset proceed.
        lenient().when(unifiedSignalService.hasBlockingSignalsForDag(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(false);
        lenient().when(stateSnapshotService.hasAnyActiveEpoch(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(false);
        lenient().when(epochManager.getCurrentEpoch(
                org.mockito.ArgumentMatchers.any(WorkflowRunEntity.class),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(EPOCH);
        lenient().when(epochManager.getCurrentEpoch(
                org.mockito.ArgumentMatchers.any(WorkflowRunEntity.class))).thenReturn(EPOCH);
    }

    private WorkflowRunEntity makeRun(RunStatus status) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        // setId is not exposed - use reflection (mirrors entity test pattern elsewhere).
        ReflectionTestUtils.setField(run, "id", RUN_DB_ID);
        run.setRunIdPublic(RUN_ID_PUBLIC);
        run.setStatus(status);
        run.setTenantId(TENANT_ID);
        run.setPlanVersion(PLAN_VERSION);

        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(WORKFLOW_DB_ID);
        workflow.setName("Schedule WF");
        run.setWorkflow(workflow);
        return run;
    }

    @Test
    @DisplayName("hasFailures=true → publishes WorkflowEpochFailedEvent with all required fields")
    void hasFailuresPublishesEvent() {
        WorkflowRunEntity run = makeRun(RunStatus.RUNNING);
        when(runRepository.findByRunIdPublicForUpdate(RUN_ID_PUBLIC)).thenReturn(Optional.of(run));
        when(runRepository.save(org.mockito.ArgumentMatchers.any())).thenReturn(run);

        service.resetForNextCycle(run, null, null, RUN_ID_PUBLIC, null,
                TRIGGER_ID, true, EPOCH);

        ArgumentCaptor<WorkflowEpochFailedEvent> captor =
                ArgumentCaptor.forClass(WorkflowEpochFailedEvent.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());
        WorkflowEpochFailedEvent emitted = captor.getValue();
        assertThat(emitted.runId()).isEqualTo(RUN_DB_ID);
        assertThat(emitted.workflowId()).isEqualTo(WORKFLOW_DB_ID);
        assertThat(emitted.epoch()).isEqualTo(EPOCH);
        assertThat(emitted.planVersion()).isEqualTo(PLAN_VERSION);
        assertThat(emitted.tenantId()).isEqualTo(TENANT_ID);
        assertThat(emitted.runIdPublic()).isEqualTo(RUN_ID_PUBLIC);
        assertThat(emitted.endedAt()).isNotNull();
    }

    @Test
    @DisplayName("hasFailures=false → does NOT publish (the negative gate)")
    void hasFailuresFalseSkipsPublish() {
        WorkflowRunEntity run = makeRun(RunStatus.RUNNING);
        when(runRepository.findByRunIdPublicForUpdate(RUN_ID_PUBLIC)).thenReturn(Optional.of(run));
        when(runRepository.save(org.mockito.ArgumentMatchers.any())).thenReturn(run);

        service.resetForNextCycle(run, null, null, RUN_ID_PUBLIC, null,
                TRIGGER_ID, false, EPOCH);

        verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("Run already terminal (FAILED/CANCELLED) → short-circuit; no publish - prevents double-emit with WorkflowRunTerminatedEvent")
    void terminalRunShortCircuitsBeforePublish() {
        WorkflowRunEntity terminalRun = makeRun(RunStatus.FAILED);
        when(runRepository.findByRunIdPublicForUpdate(RUN_ID_PUBLIC))
                .thenReturn(Optional.of(terminalRun));

        service.resetForNextCycle(terminalRun, null, null, RUN_ID_PUBLIC, null,
                TRIGGER_ID, true, EPOCH);

        // The early return at "if (lockedRun.getStatus().isTerminal())" must
        // run before the publish branch. Otherwise WorkflowRunTerminatedEvent
        // (which fires on terminal status) and WorkflowEpochFailedEvent would
        // both produce a notification for the same run failure.
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("Run already WAITING_TRIGGER → no-op early return; no publish")
    void waitingTriggerShortCircuitsBeforePublish() {
        WorkflowRunEntity waitingRun = makeRun(RunStatus.WAITING_TRIGGER);
        when(runRepository.findByRunIdPublicForUpdate(RUN_ID_PUBLIC))
                .thenReturn(Optional.of(waitingRun));

        service.resetForNextCycle(waitingRun, null, null, RUN_ID_PUBLIC, null,
                TRIGGER_ID, true, EPOCH);

        // Already-reset guard at "if (lockedRun.getStatus() == WAITING_TRIGGER)"
        // returns the current epoch without any side effects - including no
        // event publish.
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("eventPublisher null (test bypassing Spring) → no NPE; method completes")
    void nullEventPublisherDoesNotNpe() {
        // Simulate a unit test that didn't wire the optional field.
        ReflectionTestUtils.setField(service, "eventPublisher", null);

        WorkflowRunEntity run = makeRun(RunStatus.RUNNING);
        when(runRepository.findByRunIdPublicForUpdate(RUN_ID_PUBLIC)).thenReturn(Optional.of(run));
        when(runRepository.save(org.mockito.ArgumentMatchers.any())).thenReturn(run);

        // Must not throw. The null-guard `if (hasFailures && eventPublisher != null)`
        // is the fallback for tests that don't bootstrap Spring.
        service.resetForNextCycle(run, null, null, RUN_ID_PUBLIC, null,
                TRIGGER_ID, true, EPOCH);
    }
}
