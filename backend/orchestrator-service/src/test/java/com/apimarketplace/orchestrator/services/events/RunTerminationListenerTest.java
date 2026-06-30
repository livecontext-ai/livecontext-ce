package com.apimarketplace.orchestrator.services.events;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.services.WorkflowPinService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link RunTerminationListener} - the round-7 PR3 component that re-arms
 * a workflow's {@code production_run_id} when its production run terminates.
 *
 * <p>Contract under test:
 * <ol>
 *   <li>Run terminated is NOT the production run-of-record → no rearm call.</li>
 *   <li>Run terminated IS the production run, status COMPLETED → no rearm
 *       (deliberate stop semantics, see the project docs).</li>
 *   <li>Run terminated IS the production run, status CANCELLED/TIMEOUT/FAILED
 *       → rearm invoked.</li>
 *   <li>Workflow not found → no-op.</li>
 *   <li>Listener never throws (pinService.rearm exception swallowed).</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RunTerminationListener - PR3 production-run rearm")
class RunTerminationListenerTest {

    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowPinService pinService;

    @InjectMocks
    private RunTerminationListener listener;

    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final UUID PRODUCTION_RUN_ID = UUID.randomUUID();
    private static final UUID OTHER_RUN_ID = UUID.randomUUID();

    private WorkflowEntity workflowWithProductionRun(UUID prodRunId) {
        WorkflowEntity w = new WorkflowEntity();
        w.setId(WORKFLOW_ID);
        w.setPinnedVersion(5);
        w.setProductionRunId(prodRunId);
        return w;
    }

    private WorkflowRunTerminatedEvent event(UUID runId, RunStatus status) {
        return new WorkflowRunTerminatedEvent(runId, WORKFLOW_ID, status, 5);
    }

    @BeforeEach
    void setUp() {
        // Default: workflow exists with PRODUCTION_RUN_ID as the production run-of-record.
        // Tests override as needed.
    }

    @Test
    @DisplayName("Terminated run != production_run_id → no rearm")
    void otherRunDoesNotTriggerRearm() {
        when(workflowRepository.findById(WORKFLOW_ID))
            .thenReturn(Optional.of(workflowWithProductionRun(PRODUCTION_RUN_ID)));

        listener.onRunTerminated(event(OTHER_RUN_ID, RunStatus.CANCELLED));

        verify(pinService, never()).rearm(any());
    }

    @Test
    @DisplayName("Production run COMPLETED → no rearm (deliberate stop semantics)")
    void completedProductionRunDoesNotRearm() {
        when(workflowRepository.findById(WORKFLOW_ID))
            .thenReturn(Optional.of(workflowWithProductionRun(PRODUCTION_RUN_ID)));

        listener.onRunTerminated(event(PRODUCTION_RUN_ID, RunStatus.COMPLETED));

        verify(pinService, never()).rearm(any());
    }

    @Test
    @DisplayName("Production run CANCELLED → rearm invoked")
    void cancelledProductionRunTriggersRearm() {
        when(workflowRepository.findById(WORKFLOW_ID))
            .thenReturn(Optional.of(workflowWithProductionRun(PRODUCTION_RUN_ID)));
        when(pinService.rearm(WORKFLOW_ID)).thenReturn(true);

        listener.onRunTerminated(event(PRODUCTION_RUN_ID, RunStatus.CANCELLED));

        verify(pinService).rearm(WORKFLOW_ID);
    }

    @Test
    @DisplayName("Production run TIMEOUT → rearm invoked")
    void timeoutProductionRunTriggersRearm() {
        when(workflowRepository.findById(WORKFLOW_ID))
            .thenReturn(Optional.of(workflowWithProductionRun(PRODUCTION_RUN_ID)));
        when(pinService.rearm(WORKFLOW_ID)).thenReturn(true);

        listener.onRunTerminated(event(PRODUCTION_RUN_ID, RunStatus.TIMEOUT));

        verify(pinService).rearm(WORKFLOW_ID);
    }

    @Test
    @DisplayName("Production run FAILED → rearm invoked")
    void failedProductionRunTriggersRearm() {
        when(workflowRepository.findById(WORKFLOW_ID))
            .thenReturn(Optional.of(workflowWithProductionRun(PRODUCTION_RUN_ID)));
        when(pinService.rearm(WORKFLOW_ID)).thenReturn(false);

        listener.onRunTerminated(event(PRODUCTION_RUN_ID, RunStatus.FAILED));

        verify(pinService).rearm(WORKFLOW_ID);
    }

    @Test
    @DisplayName("Workflow not found → no-op (no rearm, no exception)")
    void missingWorkflowIsNoOp() {
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.empty());

        listener.onRunTerminated(event(PRODUCTION_RUN_ID, RunStatus.CANCELLED));

        verify(pinService, never()).rearm(any());
    }

    @Test
    @DisplayName("Workflow has no production_run_id → no rearm")
    void noProductionRunIdIsNoOp() {
        when(workflowRepository.findById(WORKFLOW_ID))
            .thenReturn(Optional.of(workflowWithProductionRun(null)));

        listener.onRunTerminated(event(PRODUCTION_RUN_ID, RunStatus.CANCELLED));

        verify(pinService, never()).rearm(any());
    }

    @Test
    @DisplayName("rearm throws → exception is swallowed, listener never throws")
    void rearmExceptionIsSwallowed() {
        when(workflowRepository.findById(WORKFLOW_ID))
            .thenReturn(Optional.of(workflowWithProductionRun(PRODUCTION_RUN_ID)));
        when(pinService.rearm(WORKFLOW_ID))
            .thenThrow(new RuntimeException("rearm boom"));

        // Must not throw - the originating run-completion transaction has already committed.
        listener.onRunTerminated(event(PRODUCTION_RUN_ID, RunStatus.CANCELLED));

        verify(pinService).rearm(WORKFLOW_ID);
    }

    @Test
    @DisplayName("Null fields in event → no-op")
    void nullEventFieldsAreNoOp() {
        listener.onRunTerminated(new WorkflowRunTerminatedEvent(null, WORKFLOW_ID, RunStatus.CANCELLED, 5));
        listener.onRunTerminated(new WorkflowRunTerminatedEvent(PRODUCTION_RUN_ID, null, RunStatus.CANCELLED, 5));
        listener.onRunTerminated(new WorkflowRunTerminatedEvent(PRODUCTION_RUN_ID, WORKFLOW_ID, null, 5));

        verify(pinService, never()).rearm(any());
    }
}
