package com.apimarketplace.orchestrator.services.completion;

import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.metrics.WorkflowMetrics;
import com.apimarketplace.orchestrator.services.WorkflowPersistenceService;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.merge.MergeIntegrationService;
import com.apimarketplace.orchestrator.services.persistence.PayloadFailureCause;
import com.apimarketplace.orchestrator.services.persistence.StepPersistenceResult;
import com.apimarketplace.orchestrator.services.persistence.WorkflowEntityResolverService;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import com.apimarketplace.orchestrator.services.streaming.events.StepLifecycle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tier 2 of the payload-loss contract at the orchestrator level: when
 * persistence reports {@code payloadLost} for a SUCCESS result (the row
 * already landed as FAILED - tier 1), {@code complete()} must rewrite the
 * in-memory result to FAILED BEFORE the snapshot write, so NodeCounts,
 * workflow_epochs, the WS event and billing all follow the row truth, and the
 * returned {@link StepCompletionResult} must surface the rewrite to engine
 * callers.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StepCompletionOrchestrator - payload-lost rewrite (tier 2)")
class StepCompletionOrchestratorPayloadLostTest {

    @Mock private WorkflowPersistenceService persistenceService;
    @Mock private WorkflowEventPublisher eventPublisher;
    @Mock private MergeIntegrationService mergeIntegrationService;
    @Mock private StateSnapshotService stateSnapshotService;
    @Mock private WorkflowEpochService workflowEpochService;
    @Mock private WorkflowEntityResolverService entityResolverService;
    @Mock private CreditConsumptionClient creditClient;
    @Mock private WorkflowMetrics workflowMetrics;
    @Mock private WorkflowExecution execution;
    @Mock private WorkflowPlan plan;

    private StepCompletionOrchestrator orchestrator;

    private static final String RUN_ID = "run-lost";
    private static final String NODE_ID = "mcp:fetch_data";
    private static final String NODE_LABEL = "fetch_data";
    private static final UUID WF_RUN_ID = UUID.randomUUID();
    private static final StateSnapshot.NodeCounts ONE_FAILED =
            new StateSnapshot.NodeCounts(0, 0, 1, 0, 0L, 0L, 0L);

    @BeforeEach
    void setUp() {
        orchestrator = new StepCompletionOrchestrator(
                persistenceService, eventPublisher, mergeIntegrationService, stateSnapshotService,
                workflowEpochService, entityResolverService, creditClient, workflowMetrics);
        lenient().when(execution.getRunId()).thenReturn(RUN_ID);
        lenient().when(execution.getPlan()).thenReturn(plan);
        lenient().when(execution.getWorkflowRunId()).thenReturn(WF_RUN_ID);
        lenient().when(plan.getTenantId()).thenReturn("tenant-1");
    }

    private StepCompletionContext successCtx() {
        StepExecutionResult result = StepExecutionResult.success(
                NODE_ID, Map.of("data", "value"), 100);
        return StepCompletionContext.of(execution, NODE_ID, NODE_LABEL, result, 0, 0);
    }

    @Test
    @DisplayName("SUCCESS + payloadLost: the snapshot write records FAILED, not COMPLETED (counts/epochs follow the row truth)")
    void snapshotWriteRecordsFailed() {
        when(persistenceService.recordStep(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(StepPersistenceResult.payloadLost(PayloadFailureCause.TRANSIENT_EXHAUSTED));
        when(stateSnapshotService.recordNodeCompletionAndGetCounts(
                eq(RUN_ID), eq(NODE_ID), eq("FAILED"), any(), anyInt(), anyLong()))
                .thenReturn(ONE_FAILED);

        orchestrator.complete(successCtx());

        verify(stateSnapshotService).recordNodeCompletionAndGetCounts(
                eq(RUN_ID), eq(NODE_ID), eq("FAILED"), any(), anyInt(), anyLong());
        verify(stateSnapshotService, never()).recordNodeCompletionAndGetCounts(
                eq(RUN_ID), eq(NODE_ID), eq("COMPLETED"), any(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("SUCCESS + payloadLost: the WS step event carries FAILURE lifecycle and the loss-cause status")
    void wsEventCarriesFailure() {
        when(persistenceService.recordStep(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(StepPersistenceResult.payloadLost(PayloadFailureCause.QUOTA_EXCEEDED));
        when(stateSnapshotService.recordNodeCompletionAndGetCounts(
                any(), any(), any(), any(), anyInt(), anyLong())).thenReturn(ONE_FAILED);

        orchestrator.complete(successCtx());

        ArgumentCaptor<Map<String, Object>> eventCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventPublisher).emitStep(eq(RUN_ID), eq(NODE_ID), eventCaptor.capture(),
                eq(StepLifecycle.FAILURE));
        assertThat(String.valueOf(eventCaptor.getValue().get("status")))
                .as("the frontend must render the node as failed")
                .isNotEqualToIgnoringCase("completed");
    }

    @Test
    @DisplayName("SUCCESS + payloadLost: returned StepCompletionResult surfaces payloadLost + the cause message for engine callers")
    void returnedResultSurfacesPayloadLost() {
        when(persistenceService.recordStep(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(StepPersistenceResult.payloadLost(PayloadFailureCause.TRANSIENT_EXHAUSTED));
        when(stateSnapshotService.recordNodeCompletionAndGetCounts(
                any(), any(), any(), any(), anyInt(), anyLong())).thenReturn(ONE_FAILED);

        StepCompletionResult completion = orchestrator.complete(successCtx());

        assertThat(completion.persisted()).as("a FAILED row DID land").isTrue();
        assertThat(completion.payloadLost()).isTrue();
        assertThat(completion.payloadLostMessage())
                .contains("storage write failed after retries");
    }

    @Test
    @DisplayName("SUCCESS + payloadLost still BILLS the node (a failed traversal is billed like any failure)")
    void payloadLostStillBills() {
        when(persistenceService.recordStep(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(StepPersistenceResult.payloadLost(PayloadFailureCause.TRANSIENT_EXHAUSTED));
        when(stateSnapshotService.recordNodeCompletionAndGetCounts(
                any(), any(), any(), any(), anyInt(), anyLong())).thenReturn(ONE_FAILED);

        orchestrator.complete(successCtx());

        verify(creditClient).consumeCreditsAsync(eq("tenant-1"), eq("WORKFLOW_NODE"),
                any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("SUCCESS + payloadLost: the merge collector observes the FAILED result, never the vanished success")
    void mergeCollectorObservesFailure() {
        when(persistenceService.recordStep(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(StepPersistenceResult.payloadLost(PayloadFailureCause.TRANSIENT_EXHAUSTED));
        when(stateSnapshotService.recordNodeCompletionAndGetCounts(
                any(), any(), any(), any(), anyInt(), anyLong())).thenReturn(ONE_FAILED);

        orchestrator.complete(successCtx());

        ArgumentCaptor<StepExecutionResult> mergeResult = ArgumentCaptor.forClass(StepExecutionResult.class);
        verify(mergeIntegrationService).recordCompletion(
                eq(RUN_ID), any(), anyInt(), eq(NODE_ID), any(), mergeResult.capture());
        assertThat(mergeResult.getValue().isFailure())
                .as("aggregating a success whose output blob is gone would poison the merge")
                .isTrue();
    }

    @Test
    @DisplayName("BEHAVIOUR GUARD: an ALREADY-FAILED result with payloadLost is not double-rewritten and reports payloadLost=false (no caller rewrite needed)")
    void alreadyFailedResultNotRewritten() {
        StepExecutionResult failed = StepExecutionResult.failure(NODE_ID, "real failure", null, 100);
        StepCompletionContext ctx = StepCompletionContext.of(execution, NODE_ID, NODE_LABEL, failed, 0, 0);

        when(persistenceService.recordStep(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(StepPersistenceResult.payloadLost(PayloadFailureCause.TRANSIENT_EXHAUSTED));
        when(stateSnapshotService.recordNodeCompletionAndGetCounts(
                any(), any(), any(), any(), anyInt(), anyLong())).thenReturn(ONE_FAILED);

        StepCompletionResult completion = orchestrator.complete(ctx);

        // The result was already FAILED - the traversal already treats it as a
        // failure, no rewrite is needed and callers must not double-handle it.
        assertThat(completion.payloadLost()).isFalse();
        verify(stateSnapshotService).recordNodeCompletionAndGetCounts(
                eq(RUN_ID), eq(NODE_ID), eq("FAILED"), any(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("BEHAVIOUR GUARD: a normally persisted success stays COMPLETED with payloadLost=false")
    void normalSuccessUnaffected() {
        StateSnapshot.NodeCounts oneCompleted = new StateSnapshot.NodeCounts(0, 1, 0, 0, 0L, 0L, 0L);
        when(persistenceService.recordStep(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(StepPersistenceResult.success(UUID.randomUUID()));
        when(stateSnapshotService.recordNodeCompletionAndGetCounts(
                eq(RUN_ID), eq(NODE_ID), eq("COMPLETED"), any(), anyInt(), anyLong()))
                .thenReturn(oneCompleted);

        StepCompletionResult completion = orchestrator.complete(successCtx());

        assertThat(completion.payloadLost()).isFalse();
        verify(stateSnapshotService).recordNodeCompletionAndGetCounts(
                eq(RUN_ID), eq(NODE_ID), eq("COMPLETED"), any(), anyInt(), anyLong());
        verify(eventPublisher).emitStep(eq(RUN_ID), eq(NODE_ID), any(), eq(StepLifecycle.SUCCESS));
    }
}
