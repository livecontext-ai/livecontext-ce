package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.repository.WorkflowPlanVersionRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.TriggerResolverService;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.credit.CreditBudgetService;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.trigger.queue.ExecutionQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Regression tests for the cancel-race guards in ReusableTriggerService.
 *
 * <p>Bug: after cancelWorkflow sets CANCELLED, resetRunOnFailure and
 * handleStepByStepMode would reload the run and unconditionally overwrite
 * the status with RUNNING/WAITING_TRIGGER or PAUSED, silently un-cancelling it.
 *
 * <p>Fix: both methods now check freshRun.getStatus().isTerminal() after
 * reloading and return early if the run is already terminal.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReusableTriggerService - cancel race guards")
class ReusableTriggerServiceCancelRaceTest {

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
    @Mock private CreditConsumptionClient creditClient;
    @Mock private CreditBudgetService creditBudgetService;

    private ReusableTriggerService service;

    private static final String RUN_ID = "run-cancel-race";
    private static final String TRIGGER_ID = "trigger:webhook";

    @BeforeEach
    void setUp() throws Exception {
        service = new ReusableTriggerService(
                runRepository, workflowRepository, planVersionRepository,
                epochManager, streamingService,
                executionService, triggerResolverService, stateSnapshotService,
                epochConcurrencyLimiter, executionQueueService, creditClient, creditBudgetService);

        Field signalField = ReusableTriggerService.class.getDeclaredField("unifiedSignalService");
        signalField.setAccessible(true);
        signalField.set(service, unifiedSignalService);
    }

    @Test
    @DisplayName("resetRunOnFailure skips status overwrite when run is already CANCELLED")
    void resetRunOnFailureSkipsTerminalRun() throws Exception {
        // Arrange: run is CANCELLED when reloaded from DB (cancel won the race)
        WorkflowRunEntity cancelledRun = mock(WorkflowRunEntity.class);
        when(cancelledRun.getStatus()).thenReturn(RunStatus.CANCELLED);
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(cancelledRun));

        WorkflowRunEntity staleRun = mock(WorkflowRunEntity.class);
        when(epochManager.getCurrentEpoch(staleRun, TRIGGER_ID)).thenReturn(1);

        // Act: invoke resetRunOnFailure via reflection (private method)
        Method resetMethod = ReusableTriggerService.class.getDeclaredMethod(
                "resetRunOnFailure", WorkflowRunEntity.class, String.class, String.class);
        resetMethod.setAccessible(true);
        resetMethod.invoke(service, staleRun, RUN_ID, TRIGGER_ID);

        // Assert: status was NOT overwritten - the terminal guard returned early
        verify(cancelledRun, never()).setStatus(any());
        verify(runRepository, never()).save(cancelledRun);
    }

    @Test
    @DisplayName("resetRunOnFailure proceeds normally when run is NOT terminal")
    void resetRunOnFailureProceeds() throws Exception {
        // Arrange: run is still RUNNING (no cancel happened)
        WorkflowRunEntity freshRun = mock(WorkflowRunEntity.class);
        when(freshRun.getStatus()).thenReturn(RunStatus.RUNNING);
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(freshRun));

        WorkflowRunEntity staleRun = mock(WorkflowRunEntity.class);
        when(epochManager.getCurrentEpoch(staleRun, TRIGGER_ID)).thenReturn(1);
        when(stateSnapshotService.hasAnyActiveEpoch(RUN_ID)).thenReturn(false);

        Method resetMethod = ReusableTriggerService.class.getDeclaredMethod(
                "resetRunOnFailure", WorkflowRunEntity.class, String.class, String.class);
        resetMethod.setAccessible(true);
        resetMethod.invoke(service, staleRun, RUN_ID, TRIGGER_ID);

        // Assert: status was updated (normal path)
        verify(freshRun).setStatus(RunStatus.WAITING_TRIGGER);
        verify(runRepository).save(freshRun);
    }

    @Test
    @DisplayName("handleStepByStepMode skips status overwrite when run is already CANCELLED")
    void handleStepByStepModeSkipsTerminalRun() throws Exception {
        // Arrange: run is CANCELLED when reloaded from DB
        WorkflowRunEntity cancelledRun = mock(WorkflowRunEntity.class);
        when(cancelledRun.getStatus()).thenReturn(RunStatus.CANCELLED);
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(cancelledRun));
        when(stateSnapshotService.getReadyNodeIds(RUN_ID)).thenReturn(Set.of("mcp:step1"));

        WorkflowRunEntity staleRun = mock(WorkflowRunEntity.class);
        var execution = mock(com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution.class);

        // Act: invoke handleStepByStepMode via reflection
        Method method = ReusableTriggerService.class.getDeclaredMethod(
                "handleStepByStepMode",
                WorkflowRunEntity.class,
                com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution.class,
                Set.class, String.class, TriggerType.class, String.class, int.class);
        method.setAccessible(true);
        TriggerExecutionResult result = (TriggerExecutionResult) method.invoke(
                service, staleRun, execution, Set.of("mcp:step1"),
                TRIGGER_ID, TriggerType.WEBHOOK, RUN_ID, 1);

        // Assert: status was NOT overwritten, but result is still success
        verify(cancelledRun, never()).setStatus(any());
        verify(runRepository, never()).save(cancelledRun);
        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("already terminal");
    }
}
