package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.credit.CreditBudgetService;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.state.patch.AdvisoryLockHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A cycle close must decide "is any epoch still active?" under the per-run advisory lock.
 *
 * <p>A step rerun reopens an epoch under that same lock. If a close could read the active
 * epochs before taking it, a rerun committing in between would leave the close writing
 * WAITING_TRIGGER over the reopened epoch: the rerun's own close then skips (the run already
 * looks re-armed), nothing ever closes that epoch, and the run sticks in RUNNING after its
 * next fire. That interleaving was observed in E2E, where the test profile disables the lock.
 *
 * <p>A unit test can only pin the CALL ORDER (lock first, then the reads it protects); that the
 * lock is transaction-scoped and really serialises the two paths is AdvisoryLockHelper's
 * contract. The rerun side of the same order is pinned in StepRerunServiceTest.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReusableTriggerService - cycle close under the advisory lock")
class ReusableTriggerServiceCycleCloseLockTest {

    private static final String RUN_ID = "run-1";
    private static final String TRIGGER_ID = "trigger:side";
    private static final int EPOCH = 2;

    @Mock private WorkflowRunRepository runRepository;
    @Mock private TriggerEpochManager epochManager;
    @Mock private WorkflowStreamingService streamingService;
    @Mock private WorkflowExecutionService executionService;
    @Mock private com.apimarketplace.orchestrator.services.TriggerResolverService triggerResolverService;
    @Mock private StateSnapshotService stateSnapshotService;
    @Mock private EpochConcurrencyLimiter epochConcurrencyLimiter;
    @Mock private com.apimarketplace.orchestrator.trigger.queue.ExecutionQueue executionQueueService;
    @Mock private UnifiedSignalService unifiedSignalService;
    @Mock private com.apimarketplace.orchestrator.execution.v2.async.PendingAgentRegistry pendingAgentRegistry;
    @Mock private CreditBudgetService creditBudgetService;
    @Mock private AdvisoryLockHelper advisoryLockHelper;

    private ReusableTriggerService service;
    private WorkflowRunEntity run;
    private WorkflowPlan plan;
    private WorkflowExecution execution;

    @BeforeEach
    void setUp() {
        service = new ReusableTriggerService(
                runRepository, mock(com.apimarketplace.orchestrator.repository.WorkflowRepository.class),
                mock(com.apimarketplace.orchestrator.repository.WorkflowPlanVersionRepository.class),
                epochManager, streamingService,
                executionService, triggerResolverService, stateSnapshotService,
                epochConcurrencyLimiter, executionQueueService, creditBudgetService);
        ReflectionTestUtils.setField(service, "unifiedSignalService", unifiedSignalService);
        ReflectionTestUtils.setField(service, "pendingAgentRegistry", pendingAgentRegistry);
        ReflectionTestUtils.setField(service, "advisoryLockHelper", advisoryLockHelper);
        ReflectionTestUtils.setField(service, "self", service);

        run = new WorkflowRunEntity();
        ReflectionTestUtils.setField(run, "runIdPublic", RUN_ID);
        run.setStatus(RunStatus.RUNNING);
        run.setUpdatedAt(Instant.now());

        plan = new WorkflowPlan(null, null, List.of(new Trigger("s1", "Side", "receive_one", "manual")),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of());
        execution = new WorkflowExecution(RUN_ID, plan, Map.of());

        when(runRepository.findByRunIdPublicForUpdate(RUN_ID)).thenReturn(Optional.of(run));
        when(unifiedSignalService.hasBlockingSignalsForDagAndEpoch(RUN_ID, TRIGGER_ID, EPOCH)).thenReturn(false);
        // Read by the in-flight node check; without it that check NPEs into a warning.
        org.mockito.Mockito.lenient().when(stateSnapshotService.getSnapshot(RUN_ID))
                .thenReturn(com.apimarketplace.orchestrator.domain.execution.StateSnapshot.empty());
    }

    private void closeCycle() {
        service.resetForNextCycle(run, execution, plan, RUN_ID, TriggerType.MANUAL, TRIGGER_ID, false, EPOCH);
    }

    @Test
    @DisplayName("takes the advisory lock before the row lock and before reading the active epochs")
    void takesTheLockBeforeReadingActiveEpochs() {
        when(stateSnapshotService.hasAnyActiveEpoch(RUN_ID)).thenReturn(false);

        closeCycle();

        InOrder order = inOrder(advisoryLockHelper, runRepository, stateSnapshotService);
        order.verify(advisoryLockHelper).acquireForRun(RUN_ID);
        order.verify(runRepository).findByRunIdPublicForUpdate(RUN_ID);
        order.verify(stateSnapshotService).closeEpoch(RUN_ID, TRIGGER_ID, EPOCH);
        order.verify(stateSnapshotService).hasAnyActiveEpoch(RUN_ID);
    }

    @Test
    @DisplayName("an epoch a rerun reopened keeps the run RUNNING, so the rerun can still close it")
    void reopenedEpochKeepsTheRunRunning() {
        // The order the lock enforces: the rerun committed first, so its epoch is visible here.
        when(stateSnapshotService.hasAnyActiveEpoch(RUN_ID)).thenReturn(true);

        closeCycle();

        ArgumentCaptor<WorkflowRunEntity> saved = ArgumentCaptor.forClass(WorkflowRunEntity.class);
        verify(runRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(RunStatus.RUNNING);
        // No next cycle is staged while another epoch is open; the last close does it.
        verify(epochManager, never()).resetDagWithRerunPattern(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("with no other epoch active the run re-arms and the next cycle is staged")
    void noOtherEpochReArmsTheRun() {
        when(stateSnapshotService.hasAnyActiveEpoch(RUN_ID)).thenReturn(false);

        closeCycle();

        ArgumentCaptor<WorkflowRunEntity> saved = ArgumentCaptor.forClass(WorkflowRunEntity.class);
        verify(runRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(RunStatus.WAITING_TRIGGER);
        verify(epochManager).resetDagWithRerunPattern(RUN_ID, plan, TRIGGER_ID);
    }
}
