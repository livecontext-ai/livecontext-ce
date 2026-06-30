package com.apimarketplace.orchestrator.services.completion;

import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.workflow.CredentialSource;
import com.apimarketplace.orchestrator.domain.workflow.Step;
import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.services.WorkflowPersistenceService;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.orchestrator.services.merge.MergeIntegrationService;
import com.apimarketplace.orchestrator.services.merge.MergeResult;
import com.apimarketplace.orchestrator.services.persistence.StepPersistenceResult;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.persistence.WorkflowEntityResolverService;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import com.apimarketplace.orchestrator.services.streaming.events.StepLifecycle;
import com.apimarketplace.orchestrator.metrics.WorkflowMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for StepCompletionOrchestrator - the single entry point for step completion.
 *
 * Tests cover: normal completion, skip completion, duplicate detection,
 * streaming event emission, status counts, merge tracking, legacy methods,
 * and emit-only methods.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StepCompletionOrchestrator")
class StepCompletionOrchestratorTest {

    @Mock
    private WorkflowPersistenceService persistenceService;

    @Mock
    private WorkflowEventPublisher eventPublisher;

    @Mock
    private MergeIntegrationService mergeIntegrationService;

    @Mock
    private StateSnapshotService stateSnapshotService;

    @Mock
    private WorkflowEpochService workflowEpochService;

    @Mock
    private WorkflowEntityResolverService entityResolverService;

    @Mock
    private CreditConsumptionClient creditClient;

    @Mock
    private WorkflowMetrics workflowMetrics;

    @Mock
    private WorkflowExecution execution;

    private StepCompletionOrchestrator orchestrator;

    private static final String RUN_ID = "run-123";
    private static final String NODE_ID = "mcp:fetch_data";
    private static final String NODE_LABEL = "fetch_data";
    private static final StateSnapshot.NodeCounts ZERO_COUNTS = StateSnapshot.NodeCounts.zero();
    private static final StateSnapshot.NodeCounts ONE_COMPLETED = new StateSnapshot.NodeCounts(0, 1, 0, 0, 0L, 0L, 0L);
    private static final StateSnapshot.NodeCounts ONE_FAILED = new StateSnapshot.NodeCounts(0, 0, 1, 0, 0L, 0L, 0L);
    private static final StateSnapshot.NodeCounts ONE_SKIPPED = new StateSnapshot.NodeCounts(0, 0, 0, 1, 0L, 0L, 0L);

    @BeforeEach
    void setUp() {
        orchestrator = new StepCompletionOrchestrator(
                persistenceService, eventPublisher, mergeIntegrationService, stateSnapshotService,
                workflowEpochService, entityResolverService, creditClient, workflowMetrics);
        lenient().when(execution.getRunId()).thenReturn(RUN_ID);
    }

    // =========================================================================
    // complete() - Normal Step Completion
    // =========================================================================

    @Nested
    @DisplayName("complete() - Normal Step Completion")
    class CompleteTests {

        @Test
        @DisplayName("Should persist step and return persisted result")
        void shouldPersistStepAndReturnPersistedResult() {
            StepExecutionResult result = StepExecutionResult.success(
                    NODE_ID, Map.of("data", "value"), 100);
            StepCompletionContext ctx = StepCompletionContext.of(
                    execution, NODE_ID, NODE_LABEL, result, 0, 0);

            when(persistenceService.recordStep(eq(execution), eq(NODE_ID), eq(NODE_LABEL),
                    eq(NODE_ID), any(StepExecutionResult.class), anyInt(), any()))
                    .thenReturn(StepPersistenceResult.success(UUID.randomUUID()));
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(RUN_ID, NODE_ID, "COMPLETED", null, 0, 100L))
                    .thenReturn(ONE_COMPLETED);

            StepCompletionResult completionResult = orchestrator.complete(ctx);

            assertThat(completionResult.persisted()).isTrue();
            assertThat(completionResult.isDuplicate()).isFalse();
            assertThat(completionResult.statusCounts()).containsEntry("completed", 1);
            assertThat(completionResult.statusCounts()).containsEntry("failed", 0);
            assertThat(completionResult.statusCounts()).containsEntry("skipped", 0);
        }

        @Test
        @DisplayName("Should emit streaming event after persistence")
        void shouldEmitSseEventAfterPersistence() {
            StepExecutionResult result = StepExecutionResult.success(
                    NODE_ID, Map.of("data", "value"), 100);
            StepCompletionContext ctx = StepCompletionContext.of(
                    execution, NODE_ID, NODE_LABEL, result, 0, 0);

            when(persistenceService.recordStep(eq(execution), eq(NODE_ID), eq(NODE_LABEL),
                    eq(NODE_ID), any(StepExecutionResult.class), anyInt(), any()))
                    .thenReturn(StepPersistenceResult.success(UUID.randomUUID()));
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(RUN_ID, NODE_ID, "COMPLETED", null, 0, 100L))
                    .thenReturn(ONE_COMPLETED);

            orchestrator.complete(ctx);

            ArgumentCaptor<Map<String, Object>> eventCaptor = ArgumentCaptor.forClass(Map.class);
            verify(eventPublisher).emitStep(eq(RUN_ID), eq(NODE_ID), eventCaptor.capture(), eq(StepLifecycle.SUCCESS));

            Map<String, Object> event = eventCaptor.getValue();
            assertThat(event).containsEntry("type", "step_executed");
            assertThat(event).containsEntry("runId", RUN_ID);
            assertThat(event).containsEntry("nodeId", NODE_ID);
            assertThat(event).containsEntry("stepAlias", NODE_LABEL);
            assertThat(event).containsKey("statusCounts");
            assertThat(event).containsKey("timestamp");
        }

        @Test
        @DisplayName("Should handle duplicate step (not persisted)")
        void shouldHandleDuplicateStep() {
            StepExecutionResult result = StepExecutionResult.success(
                    NODE_ID, Map.of("data", "value"), 100);
            StepCompletionContext ctx = StepCompletionContext.of(
                    execution, NODE_ID, NODE_LABEL, result, 0, 0);

            when(persistenceService.recordStep(eq(execution), eq(NODE_ID), eq(NODE_LABEL),
                    eq(NODE_ID), any(StepExecutionResult.class), anyInt(), any()))
                    .thenReturn(StepPersistenceResult.notPersisted());
            // NodeCounts are always updated to stay in sync with EdgeCounts
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(RUN_ID, NODE_ID, "COMPLETED", null, 0, 100L))
                    .thenReturn(ONE_COMPLETED);

            StepCompletionResult completionResult = orchestrator.complete(ctx);

            assertThat(completionResult.persisted()).isFalse();
            assertThat(completionResult.isDuplicate()).isTrue();
            // NodeCounts ARE updated even for duplicates (to stay in sync with EdgeCounts)
            verify(stateSnapshotService).recordNodeCompletionAndGetCounts(RUN_ID, NODE_ID, "COMPLETED", null, 0, 100L);
        }

        @Test
        @DisplayName("Should enrich result with itemIndex and iteration")
        void shouldEnrichResultWithItemIndexAndIteration() {
            StepExecutionResult result = StepExecutionResult.success(
                    NODE_ID, Map.of("data", "value"), 100);
            StepCompletionContext ctx = StepCompletionContext.of(
                    execution, NODE_ID, NODE_LABEL, result, 5, 3);

            when(persistenceService.recordStep(eq(execution), eq(NODE_ID), eq(NODE_LABEL),
                    eq(NODE_ID), any(StepExecutionResult.class), anyInt(), any()))
                    .thenReturn(StepPersistenceResult.success(UUID.randomUUID()));
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(RUN_ID, NODE_ID, "COMPLETED", null, 0, 100L))
                    .thenReturn(ONE_COMPLETED);

            orchestrator.complete(ctx);

            ArgumentCaptor<StepExecutionResult> resultCaptor = ArgumentCaptor.forClass(StepExecutionResult.class);
            verify(persistenceService).recordStep(eq(execution), eq(NODE_ID), eq(NODE_LABEL),
                    eq(NODE_ID), resultCaptor.capture(), anyInt(), any());

            StepExecutionResult enriched = resultCaptor.getValue();
            assertThat(enriched.output()).containsEntry("item_index", 5);
            assertThat(enriched.output()).containsEntry("itemIndex", 5);
            assertThat(enriched.output()).containsEntry("iteration", 3);
            assertThat(enriched.output()).containsEntry("currentIteration", 3);
        }

        @Test
        @DisplayName("Should include output in event data")
        void shouldIncludeOutputInEventData() {
            Map<String, Object> output = Map.of("response", "OK");
            StepExecutionResult result = StepExecutionResult.success(NODE_ID, output, 100);
            StepCompletionContext ctx = StepCompletionContext.of(
                    execution, NODE_ID, NODE_LABEL, result, 0, 0);

            when(persistenceService.recordStep(any(), any(), any(), any(), any(), anyInt(), any()))
                    .thenReturn(StepPersistenceResult.success(UUID.randomUUID()));
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(any(), any(), any(), any(), anyInt(), anyLong()))
                    .thenReturn(ONE_COMPLETED);

            orchestrator.complete(ctx);

            ArgumentCaptor<Map<String, Object>> eventCaptor = ArgumentCaptor.forClass(Map.class);
            verify(eventPublisher).emitStep(any(), any(), eventCaptor.capture(), any());

            assertThat(eventCaptor.getValue()).containsKey("output");
        }

        @Test
        @DisplayName("Should include error message in event data on failure")
        void shouldIncludeErrorMessageInEventData() {
            StepExecutionResult result = StepExecutionResult.failure(
                    NODE_ID, "Connection timeout", new RuntimeException("Timeout"), 5000);
            StepCompletionContext ctx = StepCompletionContext.of(
                    execution, NODE_ID, NODE_LABEL, result, 0, 0);

            when(persistenceService.recordStep(any(), any(), any(), any(), any(), anyInt(), any()))
                    .thenReturn(StepPersistenceResult.success(UUID.randomUUID()));
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(RUN_ID, NODE_ID, "FAILED", null, 0, 5000L))
                    .thenReturn(ONE_FAILED);

            orchestrator.complete(ctx);

            ArgumentCaptor<Map<String, Object>> eventCaptor = ArgumentCaptor.forClass(Map.class);
            verify(eventPublisher).emitStep(any(), any(), eventCaptor.capture(), eq(StepLifecycle.FAILURE));

            assertThat(eventCaptor.getValue()).containsEntry("error", "Timeout");
        }

        @Test
        @DisplayName("Should include executionTime in event when positive")
        void shouldIncludeExecutionTimeInEvent() {
            StepExecutionResult result = StepExecutionResult.success(NODE_ID, Map.of(), 250);
            StepCompletionContext ctx = StepCompletionContext.of(
                    execution, NODE_ID, NODE_LABEL, result, 0, 0);

            when(persistenceService.recordStep(any(), any(), any(), any(), any(), anyInt(), any()))
                    .thenReturn(StepPersistenceResult.success(UUID.randomUUID()));
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(any(), any(), any(), any(), anyInt(), anyLong()))
                    .thenReturn(ONE_COMPLETED);

            orchestrator.complete(ctx);

            ArgumentCaptor<Map<String, Object>> eventCaptor = ArgumentCaptor.forClass(Map.class);
            verify(eventPublisher).emitStep(any(), any(), eventCaptor.capture(), any());

            assertThat(eventCaptor.getValue()).containsEntry("executionTime", 250L);
        }

        @Test
        @DisplayName("Should compute processed count correctly in statusCounts")
        void shouldComputeProcessedCountCorrectly() {
            StateSnapshot.NodeCounts counts = new StateSnapshot.NodeCounts(1, 5, 2, 1, 0L, 0L, 0L);
            StepExecutionResult result = StepExecutionResult.success(NODE_ID, Map.of(), 100);
            StepCompletionContext ctx = StepCompletionContext.of(
                    execution, NODE_ID, NODE_LABEL, result, 0, 0);

            when(persistenceService.recordStep(any(), any(), any(), any(), any(), anyInt(), any()))
                    .thenReturn(StepPersistenceResult.success(UUID.randomUUID()));
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(any(), any(), any(), any(), anyInt(), anyLong()))
                    .thenReturn(counts);

            StepCompletionResult completionResult = orchestrator.complete(ctx);

            Map<String, Object> statusCounts = completionResult.statusCounts();
            assertThat(statusCounts).containsEntry("running", 1);
            assertThat(statusCounts).containsEntry("completed", 5);
            assertThat(statusCounts).containsEntry("failed", 2);
            assertThat(statusCounts).containsEntry("skipped", 1);
            // processed = completed + failed + skipped = 5 + 2 + 1 = 8
            assertThat(statusCounts).containsEntry("processed", 8);
            // total = running + completed + failed + skipped = 1 + 5 + 2 + 1 = 9
            assertThat(statusCounts).containsEntry("total", 9);
        }

        @Test
        @DisplayName("Should record merge completion when persisted")
        void shouldRecordMergeCompletionWhenPersisted() {
            StepExecutionResult result = StepExecutionResult.success(NODE_ID, Map.of("data", "v"), 100);
            StepCompletionContext ctx = StepCompletionContext.of(
                    execution, NODE_ID, NODE_LABEL, result, 0, 0);

            when(persistenceService.recordStep(any(), any(), any(), any(), any(), anyInt(), any()))
                    .thenReturn(StepPersistenceResult.success(UUID.randomUUID()));
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(any(), any(), any(), any(), anyInt(), anyLong()))
                    .thenReturn(ONE_COMPLETED);
            when(mergeIntegrationService.recordCompletion(any(), any(), anyInt(), any(), any(), any()))
                    .thenReturn(MergeResult.waiting("merge-1", "default", null));

            orchestrator.complete(ctx);

            verify(mergeIntegrationService).recordCompletion(
                    eq(RUN_ID), eq(ctx.itemId()), eq(0), eq(NODE_ID), any(), eq(result));
        }

        @Test
        @DisplayName("Should not record merge completion when not persisted (duplicate)")
        void shouldNotRecordMergeCompletionWhenDuplicate() {
            StepExecutionResult result = StepExecutionResult.success(NODE_ID, Map.of(), 100);
            StepCompletionContext ctx = StepCompletionContext.of(
                    execution, NODE_ID, NODE_LABEL, result, 0, 0);

            when(persistenceService.recordStep(any(), any(), any(), any(), any(), anyInt(), any()))
                    .thenReturn(StepPersistenceResult.notPersisted());
            // NodeCounts always updated (even for duplicates)
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(any(), any(), any(), any(), anyInt(), anyLong()))
                    .thenReturn(ONE_COMPLETED);

            orchestrator.complete(ctx);

            verifyNoInteractions(mergeIntegrationService);
        }

        @Test
        @DisplayName("Should handle merge completion exception gracefully")
        void shouldHandleMergeCompletionExceptionGracefully() {
            StepExecutionResult result = StepExecutionResult.success(NODE_ID, Map.of(), 100);
            StepCompletionContext ctx = StepCompletionContext.of(
                    execution, NODE_ID, NODE_LABEL, result, 0, 0);

            when(persistenceService.recordStep(any(), any(), any(), any(), any(), anyInt(), any()))
                    .thenReturn(StepPersistenceResult.success(UUID.randomUUID()));
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(any(), any(), any(), any(), anyInt(), anyLong()))
                    .thenReturn(ONE_COMPLETED);
            when(mergeIntegrationService.recordCompletion(any(), any(), anyInt(), any(), any(), any()))
                    .thenThrow(new RuntimeException("Merge service error"));

            // Should not throw despite merge service failure
            StepCompletionResult completionResult = orchestrator.complete(ctx);
            assertThat(completionResult.persisted()).isTrue();
        }

        @Test
        @DisplayName("Should enrich result with empty output when original has null output")
        void shouldEnrichWithEmptyOutputWhenOriginalHasNullOutput() {
            StepExecutionResult result = StepExecutionResult.failure(
                    NODE_ID, "failed", null, 100);
            StepCompletionContext ctx = StepCompletionContext.of(
                    execution, NODE_ID, NODE_LABEL, result, 2, 1);

            when(persistenceService.recordStep(any(), any(), any(), any(), any(), anyInt(), any()))
                    .thenReturn(StepPersistenceResult.success(UUID.randomUUID()));
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(any(), any(), any(), any(), anyInt(), anyLong()))
                    .thenReturn(ONE_FAILED);

            orchestrator.complete(ctx);

            ArgumentCaptor<StepExecutionResult> resultCaptor = ArgumentCaptor.forClass(StepExecutionResult.class);
            verify(persistenceService).recordStep(any(), any(), any(), any(), resultCaptor.capture(), anyInt(), any());

            StepExecutionResult enriched = resultCaptor.getValue();
            assertThat(enriched.output()).containsEntry("item_index", 2);
            assertThat(enriched.output()).containsEntry("iteration", 1);
        }
    }

    // =========================================================================
    // complete() - Status Lifecycle Mapping
    // =========================================================================

    @Nested
    @DisplayName("Status to Lifecycle Mapping")
    class StatusLifecycleMappingTests {

        @Test
        @DisplayName("SUCCESS result should emit StepLifecycle.SUCCESS")
        void successResultShouldEmitSuccessLifecycle() {
            StepExecutionResult result = StepExecutionResult.success(NODE_ID, Map.of(), 100);
            StepCompletionContext ctx = StepCompletionContext.of(execution, NODE_ID, NODE_LABEL, result);

            when(persistenceService.recordStep(any(), any(), any(), any(), any(), anyInt(), any()))
                    .thenReturn(StepPersistenceResult.success(UUID.randomUUID()));
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(any(), any(), any(), any(), anyInt(), anyLong()))
                    .thenReturn(ONE_COMPLETED);

            orchestrator.complete(ctx);

            verify(eventPublisher).emitStep(any(), any(), any(), eq(StepLifecycle.SUCCESS));
        }

        @Test
        @DisplayName("FAILED result should emit StepLifecycle.FAILURE")
        void failedResultShouldEmitFailureLifecycle() {
            StepExecutionResult result = StepExecutionResult.failure(NODE_ID, "err", null, 100);
            StepCompletionContext ctx = StepCompletionContext.of(execution, NODE_ID, NODE_LABEL, result);

            when(persistenceService.recordStep(any(), any(), any(), any(), any(), anyInt(), any()))
                    .thenReturn(StepPersistenceResult.success(UUID.randomUUID()));
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(any(), any(), any(), any(), anyInt(), anyLong()))
                    .thenReturn(ONE_FAILED);

            orchestrator.complete(ctx);

            verify(eventPublisher).emitStep(any(), any(), any(), eq(StepLifecycle.FAILURE));
        }

        @Test
        @DisplayName("SKIPPED result should emit StepLifecycle.SKIPPED")
        void skippedResultShouldEmitSkippedLifecycle() {
            StepExecutionResult result = StepExecutionResult.skipped(NODE_ID, "Branch not taken");
            StepCompletionContext ctx = StepCompletionContext.of(execution, NODE_ID, NODE_LABEL, result);

            when(persistenceService.recordStep(any(), any(), any(), any(), any(), anyInt(), any()))
                    .thenReturn(StepPersistenceResult.success(UUID.randomUUID()));
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(any(), any(), any(), any(), anyInt(), anyLong()))
                    .thenReturn(ONE_SKIPPED);

            orchestrator.complete(ctx);

            verify(eventPublisher).emitStep(any(), any(), any(), eq(StepLifecycle.SKIPPED));
        }

        @Test
        @DisplayName("RUNNING result should emit StepLifecycle.RUNNING")
        void runningResultShouldEmitRunningLifecycle() {
            StepExecutionResult result = new StepExecutionResult(
                    NODE_ID, NodeStatus.RUNNING, "In progress", Map.of(), 0, null);
            StepCompletionContext ctx = StepCompletionContext.of(execution, NODE_ID, NODE_LABEL, result);

            when(persistenceService.recordStep(any(), any(), any(), any(), any(), anyInt(), any()))
                    .thenReturn(StepPersistenceResult.success(UUID.randomUUID()));
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(any(), any(), any(), any(), anyInt(), anyLong()))
                    .thenReturn(ZERO_COUNTS);

            orchestrator.complete(ctx);

            verify(eventPublisher).emitStep(any(), any(), any(), eq(StepLifecycle.RUNNING));
        }
    }

    // =========================================================================
    // completeSkipped()
    // =========================================================================

    @Nested
    @DisplayName("completeSkipped() - Skip Completion")
    class CompleteSkippedTests {

        @Test
        @DisplayName("Should persist skipped node and return persisted result")
        void shouldPersistSkippedNodeAndReturnPersistedResult() {
            SkipContext ctx = SkipContext.of(
                    execution, NODE_ID, NODE_LABEL,
                    "Decision branch not taken", "core:check", 0);

            when(persistenceService.recordSkippedNode(execution, NODE_ID, NODE_LABEL,
                    "Decision branch not taken", "core:check", 0, 0, null))
                    .thenReturn(true);
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(RUN_ID, NODE_ID, "SKIPPED", null, 0))
                    .thenReturn(ONE_SKIPPED);

            StepCompletionResult result = orchestrator.completeSkipped(ctx);

            assertThat(result.persisted()).isTrue();
            assertThat(result.statusCounts()).containsEntry("skipped", 1);
        }

        @Test
        @DisplayName("Should persist skipped node with loop iteration when present")
        void shouldPersistSkippedNodeWithLoopIterationWhenPresent() {
            SkipContext ctx = SkipContext.of(
                    execution, NODE_ID, NODE_LABEL,
                    "Decision branch not taken", "core:check", 0, 2, 4);

            when(persistenceService.recordSkippedNode(execution, NODE_ID, NODE_LABEL,
                    "Decision branch not taken", "core:check", 0, 2, 4, "trigger:cron"))
                    .thenReturn(true);
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(RUN_ID, NODE_ID, "SKIPPED", "trigger:cron", 4))
                    .thenReturn(ONE_SKIPPED);

            StepCompletionResult result = orchestrator.completeSkipped(ctx, "trigger:cron");

            assertThat(result.persisted()).isTrue();
            verify(persistenceService).recordSkippedNode(
                    execution, NODE_ID, NODE_LABEL,
                    "Decision branch not taken", "core:check", 0, 2, 4, "trigger:cron");
        }

        @Test
        @DisplayName("Should emit SKIPPED streaming event")
        void shouldEmitSkippedStreamingEvent() {
            SkipContext ctx = SkipContext.of(
                    execution, NODE_ID, NODE_LABEL,
                    "Branch skipped", "core:check", 0);

            when(persistenceService.recordSkippedNode(any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                    .thenReturn(true);
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(any(), any(), any(), any(), anyInt()))
                    .thenReturn(ONE_SKIPPED);

            orchestrator.completeSkipped(ctx);

            ArgumentCaptor<Map<String, Object>> eventCaptor = ArgumentCaptor.forClass(Map.class);
            verify(eventPublisher).emitStep(eq(RUN_ID), eq(NODE_ID), eventCaptor.capture(), eq(StepLifecycle.SKIPPED));

            Map<String, Object> event = eventCaptor.getValue();
            assertThat(event).containsEntry("type", "step_skipped");
            assertThat(event).containsEntry("status", "SKIPPED");
            assertThat(event).containsEntry("skipReason", "Branch skipped");
        }

        @Test
        @DisplayName("Should handle duplicate skipped node")
        void shouldHandleDuplicateSkippedNode() {
            SkipContext ctx = SkipContext.of(
                    execution, NODE_ID, NODE_LABEL,
                    "Branch skipped", "core:check", 0);

            when(persistenceService.recordSkippedNode(any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                    .thenReturn(false);
            when(stateSnapshotService.getNodeCounts(RUN_ID, NODE_ID)).thenReturn(ONE_SKIPPED);

            StepCompletionResult result = orchestrator.completeSkipped(ctx);

            assertThat(result.persisted()).isFalse();
            assertThat(result.isDuplicate()).isTrue();
            // Should NOT update snapshot for duplicates
            verify(stateSnapshotService, never()).recordNodeCompletionAndGetCounts(any(), any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("Should include itemIndex in skip event data")
        void shouldIncludeItemIndexInSkipEventData() {
            SkipContext ctx = SkipContext.of(
                    execution, NODE_ID, NODE_LABEL,
                    "Skipped", null, 5);

            when(persistenceService.recordSkippedNode(any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                    .thenReturn(true);
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(any(), any(), any(), any(), anyInt()))
                    .thenReturn(ONE_SKIPPED);

            orchestrator.completeSkipped(ctx);

            ArgumentCaptor<Map<String, Object>> eventCaptor = ArgumentCaptor.forClass(Map.class);
            verify(eventPublisher).emitStep(any(), any(), eventCaptor.capture(), any());

            assertThat(eventCaptor.getValue()).containsEntry("itemIndex", 5);
        }
    }

    // =========================================================================
    // completeSkippedStepWithoutStateUpdate()
    // =========================================================================

    @Nested
    @DisplayName("completeSkippedStepWithoutStateUpdate() - Per-Item Skip")
    class CompleteSkippedWithoutStateUpdateTests {

        @Test
        @DisplayName("Should persist but NOT update StateSnapshot")
        void shouldPersistButNotUpdateStateSnapshot() {
            when(stateSnapshotService.getNodeCounts(RUN_ID, NODE_ID)).thenReturn(ZERO_COUNTS);

            orchestrator.completeSkippedStepWithoutStateUpdate(
                    execution, NODE_ID, NODE_LABEL, "Item routed elsewhere", "core:decision", 3);

            // Internally delegates to the (epoch=0, triggerId=null) overload.
            verify(persistenceService).recordSkippedNode(
                    execution, NODE_ID, NODE_LABEL, "Item routed elsewhere", "core:decision", 3, 0, null);
            verify(stateSnapshotService, never()).recordNodeCompletionAndGetCounts(any(), any(), any(), any(), anyInt());
            verify(stateSnapshotService).getNodeCounts(RUN_ID, NODE_ID);
        }

        @Test
        @DisplayName("Should emit SKIPPED streaming event")
        void shouldEmitSkippedStreamingEvent() {
            when(stateSnapshotService.getNodeCounts(RUN_ID, NODE_ID)).thenReturn(ZERO_COUNTS);

            orchestrator.completeSkippedStepWithoutStateUpdate(
                    execution, NODE_ID, NODE_LABEL, "Routed", "core:switch", 2);

            verify(eventPublisher).emitStep(eq(RUN_ID), eq(NODE_ID), any(), eq(StepLifecycle.SKIPPED));
        }

        @Test
        @DisplayName("Regression: per-item skip records workflow_epochs row under real (epoch, triggerId), not (0, default)")
        void perItemSkipRecordsEpochCountUnderRealDagKey() {
            // Given - a classify successor skipped for item 3 in epoch 7, trigger "trigger:cron".
            // Pre-fix: the legacy 6-arg form bucketed this under (epoch=0, triggerId="trigger:default")
            // in workflow_epochs, so the per-epoch UI view showed null counts for that node.
            when(stateSnapshotService.getNodeCounts(RUN_ID, NODE_ID)).thenReturn(ZERO_COUNTS);
            UUID workflowRunId = UUID.randomUUID();
            when(execution.getWorkflowRunId()).thenReturn(workflowRunId);

            // When - caller invokes the (epoch, triggerId)-aware overload
            orchestrator.completeSkippedStepWithoutStateUpdate(
                    execution, NODE_ID, NODE_LABEL, "Routed elsewhere", "agent:classify", 3,
                    7, "trigger:cron");

            // Then - workflow_epochs row is written under the REAL (epoch=7, triggerId="trigger:cron")
            // and entityResolverService is NOT consulted (explicit epoch wins over the global fallback).
            verify(workflowEpochService).recordNodeCount(RUN_ID, 7, NODE_ID, "SKIPPED", "trigger:cron");
            verify(entityResolverService, never()).getCurrentEpochFromRun(any());
            // step_data is persisted under the same explicit epoch.
            verify(persistenceService).recordSkippedNode(
                    execution, NODE_ID, NODE_LABEL, "Routed elsewhere", "agent:classify", 3, 7, "trigger:cron");
            verify(persistenceService, never()).recordSkippedNode(
                    execution, NODE_ID, NODE_LABEL, "Routed elsewhere", "agent:classify", 3, 7);
            // Global state still untouched - only per-item record + per-epoch counter.
            verify(stateSnapshotService, never()).recordNodeCompletionAndGetCounts(any(), any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("Legacy 6-arg overload still routes through 7-arg recordSkippedNode with epoch=0")
        void legacyOverloadStillUsesEpochZero() {
            when(stateSnapshotService.getNodeCounts(RUN_ID, NODE_ID)).thenReturn(ZERO_COUNTS);
            UUID workflowRunId = UUID.randomUUID();
            when(execution.getWorkflowRunId()).thenReturn(workflowRunId);

            orchestrator.completeSkippedStepWithoutStateUpdate(
                    execution, NODE_ID, NODE_LABEL, "Legacy caller", "core:decision", 0);

            // Legacy form preserves prior behavior: epoch=0, triggerId=null → "trigger:default" bucket.
            verify(workflowEpochService).recordNodeCount(eq(RUN_ID), eq(0), eq(NODE_ID), eq("SKIPPED"), isNull());
        }
    }

    // =========================================================================
    // completeStep() - Legacy Method
    // =========================================================================

    @Nested
    @DisplayName("completeStep() - Legacy Method")
    class CompleteStepLegacyTests {

        @Test
        @DisplayName("Should delegate to complete() and return persisted boolean")
        void shouldDelegateToCompleteAndReturnBoolean() {
            StepExecutionResult result = StepExecutionResult.success(NODE_ID, Map.of(), 100);

            when(persistenceService.recordStep(any(), any(), any(), any(), any(), anyInt(), any()))
                    .thenReturn(StepPersistenceResult.success(UUID.randomUUID()));
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(any(), any(), any(), any(), anyInt(), anyLong()))
                    .thenReturn(ONE_COMPLETED);

            boolean persisted = orchestrator.completeStep(
                    execution, NODE_ID, NODE_LABEL, result, 0, 0);

            assertThat(persisted).isTrue();
        }

        @Test
        @DisplayName("Should handle null itemIndex and iteration")
        void shouldHandleNullItemIndexAndIteration() {
            StepExecutionResult result = StepExecutionResult.success(NODE_ID, Map.of(), 100);

            when(persistenceService.recordStep(any(), any(), any(), any(), any(), anyInt(), any()))
                    .thenReturn(StepPersistenceResult.success(UUID.randomUUID()));
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(any(), any(), any(), any(), anyInt(), anyLong()))
                    .thenReturn(ONE_COMPLETED);

            boolean persisted = orchestrator.completeStep(
                    execution, NODE_ID, NODE_LABEL, result, null, null);

            assertThat(persisted).isTrue();
        }
    }

    // =========================================================================
    // completeSkippedStep() - Legacy Method
    // =========================================================================

    @Nested
    @DisplayName("completeSkippedStep() - Legacy Method")
    class CompleteSkippedStepLegacyTests {

        @Test
        @DisplayName("Should delegate to completeSkipped() and return persisted boolean")
        void shouldDelegateToCompleteSkippedAndReturnBoolean() {
            when(persistenceService.recordSkippedNode(any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                    .thenReturn(true);
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(any(), any(), any(), any(), anyInt()))
                    .thenReturn(ONE_SKIPPED);

            boolean persisted = orchestrator.completeSkippedStep(
                    execution, NODE_ID, NODE_LABEL, "Skipped", "core:check", 0);

            assertThat(persisted).isTrue();
        }
    }

    // =========================================================================
    // emitEventOnly()
    // =========================================================================

    @Nested
    @DisplayName("emitEventOnly() - Streaming Only")
    class EmitEventOnlyTests {

        @Test
        @DisplayName("Should emit event without persistence")
        void shouldEmitEventWithoutPersistence() {
            StepExecutionResult result = StepExecutionResult.success(NODE_ID, Map.of(), 100);

            when(stateSnapshotService.getNodeCounts(RUN_ID, NODE_ID)).thenReturn(ONE_COMPLETED);

            orchestrator.emitEventOnly(execution, NODE_ID, NODE_LABEL, result);

            verify(eventPublisher).emitStep(eq(RUN_ID), eq(NODE_ID), any(), eq(StepLifecycle.SUCCESS));
            verifyNoInteractions(persistenceService);
        }

        @Test
        @DisplayName("Should read counts from DB for event")
        void shouldReadCountsFromDbForEvent() {
            StateSnapshot.NodeCounts counts = new StateSnapshot.NodeCounts(0, 3, 0, 0, 0L, 0L, 0L);
            StepExecutionResult result = StepExecutionResult.success(NODE_ID, Map.of(), 100);

            when(stateSnapshotService.getNodeCounts(RUN_ID, NODE_ID)).thenReturn(counts);

            orchestrator.emitEventOnly(execution, NODE_ID, NODE_LABEL, result);

            ArgumentCaptor<Map<String, Object>> eventCaptor = ArgumentCaptor.forClass(Map.class);
            verify(eventPublisher).emitStep(any(), any(), eventCaptor.capture(), any());

            @SuppressWarnings("unchecked")
            Map<String, Object> statusCounts = (Map<String, Object>) eventCaptor.getValue().get("statusCounts");
            assertThat(statusCounts).containsEntry("completed", 3);
        }
    }

    // =========================================================================
    // emitStepEventOnly() - Deprecated
    // =========================================================================

    @Nested
    @DisplayName("emitStepEventOnly() - Deprecated Delegate")
    class EmitStepEventOnlyTests {

        @Test
        @DisplayName("Should delegate to emitEventOnly()")
        void shouldDelegateToEmitEventOnly() {
            StepExecutionResult result = StepExecutionResult.success(NODE_ID, Map.of(), 100);
            when(stateSnapshotService.getNodeCounts(RUN_ID, NODE_ID)).thenReturn(ONE_COMPLETED);

            orchestrator.emitStepEventOnly(execution, NODE_ID, NODE_LABEL, result);

            verify(eventPublisher).emitStep(eq(RUN_ID), eq(NODE_ID), any(), eq(StepLifecycle.SUCCESS));
        }
    }

    // =========================================================================
    // recordAndEmitOnly()
    // =========================================================================

    @Nested
    @DisplayName("recordAndEmitOnly() - Legacy Streaming Only")
    class RecordAndEmitOnlyTests {

        @Test
        @DisplayName("Should emit event with counts from DB without persistence")
        void shouldEmitEventWithCountsWithoutPersistence() {
            StepExecutionResult result = StepExecutionResult.success(NODE_ID, Map.of(), 100);
            when(stateSnapshotService.getNodeCounts(RUN_ID, NODE_ID)).thenReturn(ONE_COMPLETED);

            orchestrator.recordAndEmitOnly(execution, NODE_ID, NODE_LABEL, result, 0, 0);

            verify(eventPublisher).emitStep(eq(RUN_ID), eq(NODE_ID), any(), eq(StepLifecycle.SUCCESS));
            verifyNoInteractions(persistenceService);
        }

        @Test
        @DisplayName("Should handle null itemIndex and iteration")
        void shouldHandleNullItemIndexAndIteration() {
            StepExecutionResult result = StepExecutionResult.success(NODE_ID, Map.of(), 100);
            when(stateSnapshotService.getNodeCounts(RUN_ID, NODE_ID)).thenReturn(ZERO_COUNTS);

            orchestrator.recordAndEmitOnly(execution, NODE_ID, NODE_LABEL, result, null, null);

            verify(eventPublisher).emitStep(any(), any(), any(), any());
        }
    }

    // =========================================================================
    // StepCompletionContext Validation
    // =========================================================================

    @Nested
    @DisplayName("StepCompletionContext Validation")
    class StepCompletionContextValidationTests {

        @Test
        @DisplayName("Should reject null execution")
        void shouldRejectNullExecution() {
            StepExecutionResult result = StepExecutionResult.success(NODE_ID, Map.of(), 100);

            assertThatThrownBy(() -> StepCompletionContext.of(null, NODE_ID, NODE_LABEL, result))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("execution");
        }

        @Test
        @DisplayName("Should reject null nodeId")
        void shouldRejectNullNodeId() {
            StepExecutionResult result = StepExecutionResult.success(NODE_ID, Map.of(), 100);

            assertThatThrownBy(() -> StepCompletionContext.of(execution, null, NODE_LABEL, result))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("nodeId");
        }

        @Test
        @DisplayName("Should reject null nodeLabel")
        void shouldRejectNullNodeLabel() {
            StepExecutionResult result = StepExecutionResult.success(NODE_ID, Map.of(), 100);

            assertThatThrownBy(() -> StepCompletionContext.of(execution, NODE_ID, null, result))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("nodeLabel");
        }

        @Test
        @DisplayName("Should reject null result")
        void shouldRejectNullResult() {
            assertThatThrownBy(() -> StepCompletionContext.of(execution, NODE_ID, NODE_LABEL, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("result");
        }

        @Test
        @DisplayName("Should reject negative itemIndex")
        void shouldRejectNegativeItemIndex() {
            StepExecutionResult result = StepExecutionResult.success(NODE_ID, Map.of(), 100);

            assertThatThrownBy(() -> StepCompletionContext.of(execution, NODE_ID, NODE_LABEL, result, -1, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("itemIndex");
        }

        @Test
        @DisplayName("Should reject negative iteration")
        void shouldRejectNegativeIteration() {
            StepExecutionResult result = StepExecutionResult.success(NODE_ID, Map.of(), 100);

            assertThatThrownBy(() -> StepCompletionContext.of(execution, NODE_ID, NODE_LABEL, result, 0, -1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("iteration");
        }

        @Test
        @DisplayName("Should derive itemId from itemIndex")
        void shouldDeriveItemIdFromItemIndex() {
            StepExecutionResult result = StepExecutionResult.success(NODE_ID, Map.of(), 100);
            StepCompletionContext ctx = StepCompletionContext.of(
                    execution, NODE_ID, NODE_LABEL, result, 5, 0);

            assertThat(ctx.itemId()).isEqualTo("5");
        }

        @Test
        @DisplayName("isLoopIteration should return true for iteration > 0")
        void isLoopIterationShouldReturnTrue() {
            StepExecutionResult result = StepExecutionResult.success(NODE_ID, Map.of(), 100);
            StepCompletionContext ctx = StepCompletionContext.of(
                    execution, NODE_ID, NODE_LABEL, result, 0, 3);

            assertThat(ctx.isLoopIteration()).isTrue();
        }

        @Test
        @DisplayName("isMultiItem should return true for itemIndex > 0")
        void isMultiItemShouldReturnTrue() {
            StepExecutionResult result = StepExecutionResult.success(NODE_ID, Map.of(), 100);
            StepCompletionContext ctx = StepCompletionContext.of(
                    execution, NODE_ID, NODE_LABEL, result, 3, 0);

            assertThat(ctx.isMultiItem()).isTrue();
        }
    }

    // =========================================================================
    // SkipContext Validation
    // =========================================================================

    @Nested
    @DisplayName("SkipContext Validation")
    class SkipContextValidationTests {

        @Test
        @DisplayName("Should reject null execution")
        void shouldRejectNullExecution() {
            assertThatThrownBy(() -> SkipContext.of(null, NODE_ID, NODE_LABEL, "reason", null, 0))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should reject null nodeId")
        void shouldRejectNullNodeId() {
            assertThatThrownBy(() -> SkipContext.of(execution, null, NODE_LABEL, "reason", null, 0))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should reject null skipReason")
        void shouldRejectNullSkipReason() {
            assertThatThrownBy(() -> SkipContext.of(execution, NODE_ID, NODE_LABEL, null, null, 0))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should reject negative itemIndex")
        void shouldRejectNegativeItemIndex() {
            assertThatThrownBy(() -> SkipContext.of(execution, NODE_ID, NODE_LABEL, "reason", null, -1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("runId() should return execution's runId")
        void runIdShouldReturnExecutionRunId() {
            SkipContext ctx = SkipContext.of(execution, NODE_ID, NODE_LABEL, "reason", null, 0);

            assertThat(ctx.runId()).isEqualTo(RUN_ID);
        }
    }

    // =========================================================================
    // StepCompletionResult
    // =========================================================================

    @Nested
    @DisplayName("StepCompletionResult")
    class StepCompletionResultTests {

        @Test
        @DisplayName("persisted() should create result with persisted=true")
        void persistedShouldCreatePersistedResult() {
            Map<String, Object> counts = Map.of("success", 1);
            Map<String, Object> event = Map.of("type", "step_executed");
            StepCompletionResult result = StepCompletionResult.persisted(counts, event);

            assertThat(result.persisted()).isTrue();
            assertThat(result.isDuplicate()).isFalse();
            assertThat(result.statusCounts()).isEqualTo(counts);
            assertThat(result.eventData()).isEqualTo(event);
        }

        @Test
        @DisplayName("duplicate() should create result with persisted=false")
        void duplicateShouldCreateDuplicateResult() {
            Map<String, Object> counts = Map.of("success", 1);
            Map<String, Object> event = Map.of("type", "step_executed");
            StepCompletionResult result = StepCompletionResult.duplicate(counts, event);

            assertThat(result.persisted()).isFalse();
            assertThat(result.isDuplicate()).isTrue();
        }
    }

    // =========================================================================
    // Credit Consumption - Platform fee per node
    // =========================================================================

    @Nested
    @DisplayName("consumeCreditForNode() - Platform fee")
    class CreditConsumptionTests {

        private static final String TENANT_ID = "tenant-abc";

        private WorkflowPlan mockPlan;

        @BeforeEach
        void setUpCreditMocks() {
            mockPlan = mock(WorkflowPlan.class);
            lenient().when(mockPlan.getTenantId()).thenReturn(TENANT_ID);
            lenient().when(execution.getPlan()).thenReturn(mockPlan);
            lenient().when(execution.getWorkflowRunId()).thenReturn(null);
        }

        @Test
        @DisplayName("Should consume credit for mcp: node on success")
        void shouldConsumeCreditForMcpNode() {
            StepExecutionResult result = StepExecutionResult.success(
                    NODE_ID, Map.of("data", "value"), 100);
            StepCompletionContext ctx = StepCompletionContext.of(
                    execution, NODE_ID, NODE_LABEL, result, 0, 0);

            when(persistenceService.recordStep(eq(execution), eq(NODE_ID), eq(NODE_LABEL),
                    eq(NODE_ID), any(StepExecutionResult.class), anyInt(), any()))
                    .thenReturn(StepPersistenceResult.success(UUID.randomUUID()));
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(RUN_ID, NODE_ID, "COMPLETED", null, 0, 100L))
                    .thenReturn(ONE_COMPLETED);

            orchestrator.complete(ctx);

            verify(creditClient).consumeCreditsAsync(
                    eq(TENANT_ID), eq("WORKFLOW_NODE"), contains(NODE_ID),
                    isNull(), isNull(), isNull(), isNull());
        }

        @Test
        @DisplayName("Should consume credit for agent: node on success")
        void shouldConsumeCreditForAgentNode() {
            String agentNodeId = "agent:my_agent";
            String agentLabel = "my_agent";
            StepExecutionResult result = StepExecutionResult.success(
                    agentNodeId, Map.of("response", "hello"), 200);
            StepCompletionContext ctx = StepCompletionContext.of(
                    execution, agentNodeId, agentLabel, result, 0, 0);

            when(persistenceService.recordStep(eq(execution), eq(agentNodeId), eq(agentLabel),
                    eq(agentNodeId), any(StepExecutionResult.class), anyInt(), any()))
                    .thenReturn(StepPersistenceResult.success(UUID.randomUUID()));
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(RUN_ID, agentNodeId, "COMPLETED", null, 0, 200L))
                    .thenReturn(ONE_COMPLETED);

            orchestrator.complete(ctx);

            verify(creditClient).consumeCreditsAsync(
                    eq(TENANT_ID), eq("WORKFLOW_NODE"), contains(agentNodeId),
                    isNull(), isNull(), isNull(), isNull());
        }

        @Test
        @DisplayName("Should consume credit for agent:classify node on success")
        void shouldConsumeCreditForClassifyNode() {
            String classifyNodeId = "agent:classify_intent";
            String classifyLabel = "classify_intent";
            StepExecutionResult result = StepExecutionResult.success(
                    classifyNodeId, Map.of("classification", "greeting"), 150);
            StepCompletionContext ctx = StepCompletionContext.of(
                    execution, classifyNodeId, classifyLabel, result, 0, 0);

            when(persistenceService.recordStep(eq(execution), eq(classifyNodeId), eq(classifyLabel),
                    eq(classifyNodeId), any(StepExecutionResult.class), anyInt(), any()))
                    .thenReturn(StepPersistenceResult.success(UUID.randomUUID()));
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(RUN_ID, classifyNodeId, "COMPLETED", null, 0, 150L))
                    .thenReturn(ONE_COMPLETED);

            orchestrator.complete(ctx);

            verify(creditClient).consumeCreditsAsync(
                    eq(TENANT_ID), eq("WORKFLOW_NODE"), contains(classifyNodeId),
                    isNull(), isNull(), isNull(), isNull());
        }

        @Test
        @DisplayName("Should consume credit for agent:guardrail node on success")
        void shouldConsumeCreditForGuardrailNode() {
            String guardrailNodeId = "agent:guardrail_check";
            String guardrailLabel = "guardrail_check";
            StepExecutionResult result = StepExecutionResult.success(
                    guardrailNodeId, Map.of("passed", true), 120);
            StepCompletionContext ctx = StepCompletionContext.of(
                    execution, guardrailNodeId, guardrailLabel, result, 0, 0);

            when(persistenceService.recordStep(eq(execution), eq(guardrailNodeId), eq(guardrailLabel),
                    eq(guardrailNodeId), any(StepExecutionResult.class), anyInt(), any()))
                    .thenReturn(StepPersistenceResult.success(UUID.randomUUID()));
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(RUN_ID, guardrailNodeId, "COMPLETED", null, 0, 120L))
                    .thenReturn(ONE_COMPLETED);

            orchestrator.complete(ctx);

            verify(creditClient).consumeCreditsAsync(
                    eq(TENANT_ID), eq("WORKFLOW_NODE"), contains(guardrailNodeId),
                    isNull(), isNull(), isNull(), isNull());
        }

        @Test
        @DisplayName("Should consume credit for core: node on success")
        void shouldConsumeCreditForCoreNode() {
            String coreNodeId = "core:decision_1";
            String coreLabel = "decision_1";
            StepExecutionResult result = StepExecutionResult.success(
                    coreNodeId, Map.of("branch", "if"), 50);
            StepCompletionContext ctx = StepCompletionContext.of(
                    execution, coreNodeId, coreLabel, result, 0, 0);

            when(persistenceService.recordStep(eq(execution), eq(coreNodeId), eq(coreLabel),
                    eq(coreNodeId), any(StepExecutionResult.class), anyInt(), any()))
                    .thenReturn(StepPersistenceResult.success(UUID.randomUUID()));
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(RUN_ID, coreNodeId, "COMPLETED", null, 0, 50L))
                    .thenReturn(ONE_COMPLETED);

            orchestrator.complete(ctx);

            verify(creditClient).consumeCreditsAsync(
                    eq(TENANT_ID), eq("WORKFLOW_NODE"), contains(coreNodeId),
                    isNull(), isNull(), isNull(), isNull());
        }

        @Test
        @DisplayName("Should NOT consume credit when tenantId is null")
        void shouldNotConsumeCreditWhenTenantIdNull() {
            when(mockPlan.getTenantId()).thenReturn(null);

            StepExecutionResult result = StepExecutionResult.success(
                    NODE_ID, Map.of("data", "value"), 100);
            StepCompletionContext ctx = StepCompletionContext.of(
                    execution, NODE_ID, NODE_LABEL, result, 0, 0);

            when(persistenceService.recordStep(eq(execution), eq(NODE_ID), eq(NODE_LABEL),
                    eq(NODE_ID), any(StepExecutionResult.class), anyInt(), any()))
                    .thenReturn(StepPersistenceResult.success(UUID.randomUUID()));
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(RUN_ID, NODE_ID, "COMPLETED", null, 0, 100L))
                    .thenReturn(ONE_COMPLETED);

            orchestrator.complete(ctx);

            verify(creditClient, never()).consumeCreditsAsync(
                    anyString(), anyString(), anyString(),
                    anyString(), anyString(), any(), any());
        }

        @Test
        @DisplayName("Should NOT consume credit when node billing is disabled (CE self-hosted)")
        void shouldNotConsumeCreditWhenNodeBillingDisabled() {
            // CE sets workflow.node-billing.enabled=false → the flat per-node platform fee is a
            // cloud-only monetization, so NO WORKFLOW_NODE ledger row is written (not even $0).
            ReflectionTestUtils.setField(orchestrator, "nodeBillingEnabled", false);

            StepExecutionResult result = StepExecutionResult.success(
                    NODE_ID, Map.of("data", "value"), 100);
            StepCompletionContext ctx = StepCompletionContext.of(
                    execution, NODE_ID, NODE_LABEL, result, 0, 0);

            when(persistenceService.recordStep(eq(execution), eq(NODE_ID), eq(NODE_LABEL),
                    eq(NODE_ID), any(StepExecutionResult.class), anyInt(), any()))
                    .thenReturn(StepPersistenceResult.success(UUID.randomUUID()));
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(RUN_ID, NODE_ID, "COMPLETED", null, 0, 100L))
                    .thenReturn(ONE_COMPLETED);

            orchestrator.complete(ctx);

            // The flat per-node fee is skipped entirely (gate is before the consume + metrics).
            verify(creditClient, never()).consumeCreditsAsync(
                    anyString(), anyString(), anyString(),
                    anyString(), anyString(), any(), any());
        }

        @Test
        @DisplayName("Should consume credit on failure (not just success)")
        void shouldConsumeCreditOnFailure() {
            String agentNodeId = "agent:my_agent";
            String agentLabel = "my_agent";
            StepExecutionResult result = StepExecutionResult.failure(
                    agentNodeId, "error occurred", new RuntimeException("test"), 0L);
            StepCompletionContext ctx = StepCompletionContext.of(
                    execution, agentNodeId, agentLabel, result, 0, 0);

            when(persistenceService.recordStep(eq(execution), eq(agentNodeId), eq(agentLabel),
                    eq(agentNodeId), any(StepExecutionResult.class), anyInt(), any()))
                    .thenReturn(StepPersistenceResult.success(UUID.randomUUID()));
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(RUN_ID, agentNodeId, "FAILED", null, 0, 0L))
                    .thenReturn(ONE_FAILED);

            orchestrator.complete(ctx);

            verify(creditClient).consumeCreditsAsync(
                    eq(TENANT_ID), eq("WORKFLOW_NODE"), contains(agentNodeId),
                    isNull(), isNull(), isNull(), isNull());
        }

        @Test
        @DisplayName("Credit failure should not block step completion")
        void creditFailureShouldNotBlockStepCompletion() {
            StepExecutionResult result = StepExecutionResult.success(
                    NODE_ID, Map.of("data", "value"), 100);
            StepCompletionContext ctx = StepCompletionContext.of(
                    execution, NODE_ID, NODE_LABEL, result, 0, 0);

            when(persistenceService.recordStep(eq(execution), eq(NODE_ID), eq(NODE_LABEL),
                    eq(NODE_ID), any(StepExecutionResult.class), anyInt(), any()))
                    .thenReturn(StepPersistenceResult.success(UUID.randomUUID()));
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(RUN_ID, NODE_ID, "COMPLETED", null, 0, 100L))
                    .thenReturn(ONE_COMPLETED);
            doThrow(new RuntimeException("Credit service unavailable"))
                    .when(creditClient).consumeCreditsAsync(
                            anyString(), anyString(), anyString(),
                            any(), any(), any(), any());

            StepCompletionResult completionResult = orchestrator.complete(ctx);

            assertThat(completionResult.persisted()).isTrue();
            assertThat(completionResult.isDuplicate()).isFalse();
        }
    }

    // =========================================================================
    // Regression: split-async suppressGlobalMark branch must still write
    // workflow_epochs counter rows. Bug introduced in commit 2e53d32b7
    // (Phase 2.E, 2026-04-29) and observed in prod run
    // run_<id> (Gmail Auto-Labeler) where the per-epoch
    // inspector showed completed=0, skipped=N for `agent:classify` while
    // workflow_step_data had K COMPLETED + (N-K) SKIPPED. Root cause:
    // recordEpochNodeCount was only called in the else branch when the
    // suppression was added, so split-async per-item COMPLETED/FAILED never
    // reached the workflow_epochs counter table that the inspector reads.
    // =========================================================================
    @Nested
    @DisplayName("complete() - split-async suppressGlobalMark writes workflow_epochs")
    class SplitAsyncEpochCountTests {

        private static final String CLASSIFY_NODE_ID = "agent:classify";
        private static final String CLASSIFY_LABEL = "classify";
        private static final String TRIGGER_ID = "trigger:cron";
        private static final int EPOCH = 66;
        private static final int ITEM_INDEX = 7;

        /**
         * Repro of run_<id> epoch 67: per-item COMPLETED never
         * reached workflow_epochs. Pre-fix: workflow_epochs.recordNodeCount is
         * never invoked. Post-fix: invoked once with status=COMPLETED.
         */
        @Test
        @DisplayName("Split-async COMPLETED writes workflow_epochs counter row")
        void splitAsyncCompletedRecordsEpochNodeCount() {
            StepExecutionResult result = StepExecutionResult.success(
                    CLASSIFY_NODE_ID, Map.of("selected_category", "Urgent"), 1500);
            StepCompletionContext ctx = new StepCompletionContext(
                    execution, CLASSIFY_NODE_ID, CLASSIFY_LABEL, result,
                    ITEM_INDEX, 0, null, EPOCH, /* suppressGlobalMark = */ true);

            when(persistenceService.recordStep(eq(execution), eq(CLASSIFY_NODE_ID), eq(CLASSIFY_LABEL),
                    eq(CLASSIFY_NODE_ID), any(StepExecutionResult.class), anyInt(), any()))
                    .thenReturn(StepPersistenceResult.success(UUID.randomUUID()));
            when(stateSnapshotService.incrementNodeCountsOnly(RUN_ID, CLASSIFY_NODE_ID, "COMPLETED", 1))
                    .thenReturn(ONE_COMPLETED);
            UUID workflowRunId = UUID.randomUUID();
            when(execution.getWorkflowRunId()).thenReturn(workflowRunId);

            orchestrator.complete(ctx, TRIGGER_ID);

            // The counter row MUST be written under the same (epoch, triggerId, status)
            // as the in-memory NodeCounts increment. Without this, the per-epoch
            // inspector sees completed=0 because workflow_epochs is its only source.
            verify(workflowEpochService).recordNodeCount(
                    RUN_ID, EPOCH, CLASSIFY_NODE_ID, "COMPLETED", TRIGGER_ID);
            // The global EpochState mark MUST still be suppressed - this is the
            // whole point of Phase 2.E: avoid poisoning failedNodeIds on the
            // first per-item failure. Re-asserting here so a future change can't
            // accidentally drag the suppression away.
            verify(stateSnapshotService, never()).recordNodeCompletionAndGetCounts(
                    anyString(), anyString(), anyString(), anyString(), anyInt(), anyLong());
            verify(stateSnapshotService).incrementNodeCountsOnly(RUN_ID, CLASSIFY_NODE_ID, "COMPLETED", 1);
        }

        /**
         * Symmetric to the COMPLETED case. The original Phase 2.E motivation was
         * specifically to avoid first-failure poisoning, so the FAILED path is
         * the most likely to regress. Asserts FAILED status reaches workflow_epochs.
         */
        @Test
        @DisplayName("Split-async FAILED writes workflow_epochs counter row")
        void splitAsyncFailedRecordsEpochNodeCount() {
            StepExecutionResult result = StepExecutionResult.failure(
                    CLASSIFY_NODE_ID, "Google 429", null, 800L);
            StepCompletionContext ctx = new StepCompletionContext(
                    execution, CLASSIFY_NODE_ID, CLASSIFY_LABEL, result,
                    ITEM_INDEX, 0, null, EPOCH, /* suppressGlobalMark = */ true);

            when(persistenceService.recordStep(eq(execution), eq(CLASSIFY_NODE_ID), eq(CLASSIFY_LABEL),
                    eq(CLASSIFY_NODE_ID), any(StepExecutionResult.class), anyInt(), any()))
                    .thenReturn(StepPersistenceResult.success(UUID.randomUUID()));
            when(stateSnapshotService.incrementNodeCountsOnly(RUN_ID, CLASSIFY_NODE_ID, "FAILED", 1))
                    .thenReturn(ONE_FAILED);
            UUID workflowRunId = UUID.randomUUID();
            when(execution.getWorkflowRunId()).thenReturn(workflowRunId);

            orchestrator.complete(ctx, TRIGGER_ID);

            verify(workflowEpochService).recordNodeCount(
                    RUN_ID, EPOCH, CLASSIFY_NODE_ID, "FAILED", TRIGGER_ID);
            // Suppression of the global EpochState mark preserved.
            verify(stateSnapshotService, never()).recordNodeCompletionAndGetCounts(
                    anyString(), anyString(), anyString(), anyString(), anyInt(), anyLong());
        }

        /**
         * Mixed batch - 4 COMPLETED items + 1 FAILED item across one epoch.
         * Asserts each per-item completion produces an additive counter row,
         * matching how the inspector aggregates per-status counts.
         */
        @Test
        @DisplayName("Split-async mixed COMPLETED+FAILED items produce per-status counter rows")
        void splitAsyncMixedItemsProducePerStatusCounterRows() {
            UUID workflowRunId = UUID.randomUUID();
            when(execution.getWorkflowRunId()).thenReturn(workflowRunId);
            when(persistenceService.recordStep(eq(execution), eq(CLASSIFY_NODE_ID), eq(CLASSIFY_LABEL),
                    eq(CLASSIFY_NODE_ID), any(StepExecutionResult.class), anyInt(), any()))
                    .thenReturn(StepPersistenceResult.success(UUID.randomUUID()));
            when(stateSnapshotService.incrementNodeCountsOnly(eq(RUN_ID), eq(CLASSIFY_NODE_ID), anyString(), eq(1)))
                    .thenReturn(ONE_COMPLETED);

            // 4 successful items
            for (int i = 0; i < 4; i++) {
                StepExecutionResult result = StepExecutionResult.success(
                        CLASSIFY_NODE_ID, Map.of("selected_category", "Urgent"), 1500);
                StepCompletionContext ctx = new StepCompletionContext(
                        execution, CLASSIFY_NODE_ID, CLASSIFY_LABEL, result,
                        i, 0, null, EPOCH, true);
                orchestrator.complete(ctx, TRIGGER_ID);
            }

            // 1 failed item
            StepExecutionResult failResult = StepExecutionResult.failure(CLASSIFY_NODE_ID, "Google 429", null, 800L);
            StepCompletionContext failCtx = new StepCompletionContext(
                    execution, CLASSIFY_NODE_ID, CLASSIFY_LABEL, failResult,
                    4, 0, null, EPOCH, true);
            orchestrator.complete(failCtx, TRIGGER_ID);

            // Each per-item completion writes its own counter row. The repository
            // upsert is additive on (run_id, trigger_id, epoch, entry_type, entry_key, status),
            // so 4 calls with status=COMPLETED accumulate to count=4 in DB; 1 call
            // with status=FAILED accumulates to count=1.
            verify(workflowEpochService, times(4)).recordNodeCount(
                    RUN_ID, EPOCH, CLASSIFY_NODE_ID, "COMPLETED", TRIGGER_ID);
            verify(workflowEpochService, times(1)).recordNodeCount(
                    RUN_ID, EPOCH, CLASSIFY_NODE_ID, "FAILED", TRIGGER_ID);
        }

        /**
         * Defensive - verifies the seal aggregate path (recordSplitAggregateIfMissing)
         * does NOT write to workflow_epochs. Per-item recordNodeCount is the single
         * source of additive counts; the seal only writes to EpochState (set-marker).
         * This test guards against accidentally adding a workflow_epochs write at the
         * seal that would double-count on top of the per-item rows.
         */
        @Test
        @DisplayName("Seal aggregate does NOT write workflow_epochs (per-item is the only counter source)")
        void sealAggregateDoesNotDoubleCountEpochNodeCount() {
            // Seal-only path: stub the dependencies recordSplitAggregateIfMissing reads from.
            when(stateSnapshotService.getSnapshot(RUN_ID))
                    .thenReturn(StateSnapshot.empty().openEpochForDag(TRIGGER_ID, EPOCH));

            // Need a stepDataRepository wired for the aggregate path to reach the count branch.
            StepCompletionOrchestrator orchestratorWithRepo = new StepCompletionOrchestrator(
                    persistenceService, eventPublisher, mergeIntegrationService, stateSnapshotService,
                    workflowEpochService, entityResolverService, creditClient, workflowMetrics,
                    mock(com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository.class));
            // Reuse the fresh repo mock to return a non-zero count so the seal proceeds
            // through the markCompleted branch.
            com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository repo =
                    (com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository)
                            ReflectionTestUtils.getField(orchestratorWithRepo, "stepDataRepository");
            when(repo.countByRunIdAndNormalizedKeyAndEpochAndStatus(RUN_ID, CLASSIFY_NODE_ID, EPOCH, "COMPLETED"))
                    .thenReturn(4L);
            when(repo.countByRunIdAndNormalizedKeyAndEpochAndStatus(RUN_ID, CLASSIFY_NODE_ID, EPOCH, "FAILED"))
                    .thenReturn(0L);

            orchestratorWithRepo.recordSplitAggregateIfMissing(RUN_ID, TRIGGER_ID, CLASSIFY_NODE_ID, EPOCH);

            // EpochState marker is written exactly once, as designed.
            verify(stateSnapshotService).markNodeCompletedEpochOnly(RUN_ID, TRIGGER_ID, EPOCH, CLASSIFY_NODE_ID);
            // The seal MUST NOT touch workflow_epochs - that's the per-item path's job
            // and adding a write here would double-count on top of the K per-item rows.
            verify(workflowEpochService, never()).recordNodeCount(
                    anyString(), anyInt(), anyString(), anyString(), anyString());
            verify(workflowEpochService, never()).recordNodeCount(
                    anyString(), anyInt(), anyString(), anyString());
        }
    }

    // =========================================================================
    // Invariant - incrementNodeCountsOnly ↔ recordEpochNodeCount must always
    // fire together for the same (run, node, status, epoch). The two stores
    // are parallel additive counters: NodeCounts in-memory (snapshot JSONB)
    // and workflow_epochs in DB (read by the per-epoch inspector). The
    // 2026-04-29 regression broke this invariant by suppressing only one
    // half. This test checks both paths through complete() to lock the
    // invariant going forward.
    // =========================================================================
    @Nested
    @DisplayName("Invariant: incrementNodeCountsOnly ⇔ recordEpochNodeCount")
    class CounterStoresInvariantTests {

        private static final String NODE = "agent:classify";
        private static final String LABEL = "classify";
        private static final String TRIGGER = "trigger:cron";
        private static final int EPOCH = 12;

        /**
         * Suppressed branch must call both stores. Pre-2026-05-06 fix it called
         * only incrementNodeCountsOnly. This is the asymmetry that produced the
         * production bug.
         */
        @Test
        @DisplayName("Suppressed branch increments BOTH NodeCounts and workflow_epochs")
        void suppressedBranchHitsBothStores() {
            StepExecutionResult result = StepExecutionResult.success(NODE, Map.of(), 100);
            StepCompletionContext ctx = new StepCompletionContext(
                    execution, NODE, LABEL, result, 0, 0, null, EPOCH, true);

            when(persistenceService.recordStep(any(), any(), any(), any(), any(), anyInt(), any()))
                    .thenReturn(StepPersistenceResult.success(UUID.randomUUID()));
            when(stateSnapshotService.incrementNodeCountsOnly(RUN_ID, NODE, "COMPLETED", 1))
                    .thenReturn(ONE_COMPLETED);
            when(execution.getWorkflowRunId()).thenReturn(UUID.randomUUID());

            orchestrator.complete(ctx, TRIGGER);

            // Both stores must move together - invariant.
            verify(stateSnapshotService).incrementNodeCountsOnly(RUN_ID, NODE, "COMPLETED", 1);
            verify(workflowEpochService).recordNodeCount(RUN_ID, EPOCH, NODE, "COMPLETED", TRIGGER);

            // 2026-05-21 CRITICAL 2 regression guard: pin that triggerId is threaded
            // through to persistenceService.recordStep. Without this strict eq() match,
            // a future refactor that drops triggerId at the orchestrator boundary would
            // re-introduce the workflow_step_data → trigger:default drift silently.
            verify(persistenceService).recordStep(eq(execution), eq(NODE), eq(LABEL),
                    eq(NODE), any(), eq(EPOCH), eq(TRIGGER));
        }

        /**
         * Non-suppressed branch must also call workflow_epochs (this was already
         * working pre-fix; the test pins it so a future "simplification" can't
         * regress it the other way).
         */
        @Test
        @DisplayName("Non-suppressed branch increments NodeCounts via recordNodeCompletionAndGetCounts AND workflow_epochs")
        void unsuppressedBranchHitsBothStores() {
            StepExecutionResult result = StepExecutionResult.success(NODE, Map.of(), 100);
            StepCompletionContext ctx = new StepCompletionContext(
                    execution, NODE, LABEL, result, 0, 0, null, EPOCH, false);

            when(persistenceService.recordStep(any(), any(), any(), any(), any(), anyInt(), any()))
                    .thenReturn(StepPersistenceResult.success(UUID.randomUUID()));
            when(stateSnapshotService.recordNodeCompletionAndGetCounts(RUN_ID, NODE, "COMPLETED", TRIGGER, EPOCH, 100L))
                    .thenReturn(ONE_COMPLETED);
            when(execution.getWorkflowRunId()).thenReturn(UUID.randomUUID());

            orchestrator.complete(ctx, TRIGGER);

            verify(stateSnapshotService).recordNodeCompletionAndGetCounts(RUN_ID, NODE, "COMPLETED", TRIGGER, EPOCH, 100L);
            verify(workflowEpochService).recordNodeCount(RUN_ID, EPOCH, NODE, "COMPLETED", TRIGGER);

            // 2026-05-21 CRITICAL 2 regression guard - same as suppressed branch above.
            verify(persistenceService).recordStep(eq(execution), eq(NODE), eq(LABEL),
                    eq(NODE), any(), eq(EPOCH), eq(TRIGGER));
        }
    }

}
