package com.apimarketplace.orchestrator.services.completion;

import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.services.WorkflowPersistenceService;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.orchestrator.services.merge.MergeIntegrationService;
import com.apimarketplace.orchestrator.services.persistence.StepPersistenceResult;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.persistence.WorkflowEntityResolverService;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import com.apimarketplace.orchestrator.metrics.WorkflowMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Epoch-specific tests for {@link StepCompletionOrchestrator}.
 *
 * Tests the epoch 0 bug fix and triggerId propagation to WorkflowEpochService.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StepCompletionOrchestrator - Epoch Fixes")
class StepCompletionOrchestratorEpochTest {

    @Mock private WorkflowPersistenceService persistenceService;
    @Mock private WorkflowEventPublisher eventPublisher;
    @Mock private MergeIntegrationService mergeIntegrationService;
    @Mock private StateSnapshotService stateSnapshotService;
    @Mock private WorkflowEpochService workflowEpochService;
    @Mock private WorkflowEntityResolverService entityResolverService;
    @Mock private CreditConsumptionClient creditClient;
    @Mock private WorkflowMetrics workflowMetrics;
    @Mock private WorkflowExecution execution;

    private StepCompletionOrchestrator orchestrator;

    private static final String RUN_ID = "run-epoch-test";
    private static final String NODE_ID = "mcp:fetch_data";
    private static final String NODE_LABEL = "fetch_data";
    private static final String TRIGGER_ID = "trigger:my_webhook";
    private static final StateSnapshot.NodeCounts ONE_COMPLETED = new StateSnapshot.NodeCounts(0, 1, 0, 0, 0L, 0L, 0L);
    private static final StateSnapshot.NodeCounts ONE_SKIPPED = new StateSnapshot.NodeCounts(0, 0, 0, 1, 0L, 0L, 0L);

    @BeforeEach
    void setUp() {
        orchestrator = new StepCompletionOrchestrator(
                persistenceService, eventPublisher, mergeIntegrationService, stateSnapshotService,
                workflowEpochService, entityResolverService, creditClient, workflowMetrics);
        lenient().when(execution.getRunId()).thenReturn(RUN_ID);
        lenient().when(execution.getWorkflowRunId()).thenReturn(UUID.randomUUID());
    }

    @Nested
    @DisplayName("Epoch 0 Bug Fix")
    class Epoch0BugFix {

        @Test
        @DisplayName("complete() at epoch 0 records to epoch 0 (not lost)")
        void complete_epoch0_recordsToEpoch0() {
            StepExecutionResult result = StepExecutionResult.success(NODE_ID, Map.of("data", "v"), 100);
            StepCompletionContext ctx = StepCompletionContext.of(
                    execution, NODE_ID, NODE_LABEL, result, 0, 0, 0);

            when(persistenceService.recordStep(any(), any(), any(), any(), any(), anyInt(), any()))
                    .thenReturn(StepPersistenceResult.success(UUID.randomUUID()));
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(any(), any(), any(), any(), anyInt(), anyLong()))
                    .thenReturn(ONE_COMPLETED);

            orchestrator.complete(ctx, null);

            // Epoch 0 should be passed directly (not trigger fallback to entityResolverService)
            verify(workflowEpochService).recordNodeCount(RUN_ID, 0, NODE_ID, "COMPLETED", null);
            // entityResolverService.getCurrentEpochFromRun should NOT be called
            verify(entityResolverService, never()).getCurrentEpochFromRun(any());
        }

        @Test
        @DisplayName("completeSkipped() at epoch 0 records to epoch 0")
        void completeSkipped_epoch0_recordsToEpoch0() {
            SkipContext ctx = SkipContext.of(
                    execution, NODE_ID, NODE_LABEL,
                    "Branch not taken", "core:check", 0, 0);

            when(persistenceService.recordSkippedNode(any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                    .thenReturn(true);
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(any(), any(), any(), any(), anyInt()))
                    .thenReturn(ONE_SKIPPED);

            orchestrator.completeSkipped(ctx, null);

            verify(workflowEpochService).recordNodeCount(RUN_ID, 0, NODE_ID, "SKIPPED", null);
            verify(entityResolverService, never()).getCurrentEpochFromRun(any());
        }
    }

    @Nested
    @DisplayName("TriggerId Propagation")
    class TriggerIdPropagation {

        @Test
        @DisplayName("complete() with triggerId passes it to workflowEpochService")
        void complete_withTriggerId_passesItToWorkflowEpochService() {
            StepExecutionResult result = StepExecutionResult.success(NODE_ID, Map.of("data", "v"), 100);
            StepCompletionContext ctx = StepCompletionContext.of(
                    execution, NODE_ID, NODE_LABEL, result, 0, 0, 1);

            when(persistenceService.recordStep(any(), any(), any(), any(), any(), anyInt(), any()))
                    .thenReturn(StepPersistenceResult.success(UUID.randomUUID()));
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(any(), any(), any(), any(), anyInt(), anyLong()))
                    .thenReturn(ONE_COMPLETED);

            orchestrator.complete(ctx, TRIGGER_ID);

            verify(workflowEpochService).recordNodeCount(RUN_ID, 1, NODE_ID, "COMPLETED", TRIGGER_ID);
        }

        @Test
        @DisplayName("regression: completeSkipped() threads triggerId to workflow_epochs and workflow_step_data")
        void completeSkipped_withTriggerId_passesItToWorkflowEpochService() {
            SkipContext ctx = SkipContext.of(
                    execution, NODE_ID, NODE_LABEL,
                    "Branch not taken", "core:check", 0, 2);

            when(persistenceService.recordSkippedNode(any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                    .thenReturn(true);
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(any(), any(), any(), any(), anyInt()))
                    .thenReturn(ONE_SKIPPED);

            orchestrator.completeSkipped(ctx, TRIGGER_ID);

            verify(workflowEpochService).recordNodeCount(RUN_ID, 2, NODE_ID, "SKIPPED", TRIGGER_ID);
            verify(persistenceService).recordSkippedNode(
                    eq(execution), eq(NODE_ID), eq(NODE_LABEL),
                    eq("Branch not taken"), eq("core:check"), eq(0), eq(2), eq(TRIGGER_ID));
            verify(persistenceService, never()).recordSkippedNode(
                    eq(execution), eq(NODE_ID), eq(NODE_LABEL),
                    eq("Branch not taken"), eq("core:check"), eq(0), eq(2));
        }

        @Test
        @DisplayName("completeStep() with epoch and triggerId passes both through")
        void completeStep_withEpochAndTriggerId_passesBothThrough() {
            StepExecutionResult result = StepExecutionResult.success(NODE_ID, Map.of(), 100);

            when(persistenceService.recordStep(any(), any(), any(), any(), any(), anyInt(), any()))
                    .thenReturn(StepPersistenceResult.success(UUID.randomUUID()));
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(any(), any(), any(), any(), anyInt(), anyLong()))
                    .thenReturn(ONE_COMPLETED);

            orchestrator.completeStep(execution, NODE_ID, NODE_LABEL, result, 0, 0, 3, TRIGGER_ID);

            verify(workflowEpochService).recordNodeCount(RUN_ID, 3, NODE_ID, "COMPLETED", TRIGGER_ID);
        }
    }
}
