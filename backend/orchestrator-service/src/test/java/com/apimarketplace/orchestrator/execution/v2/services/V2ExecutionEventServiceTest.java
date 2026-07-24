package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionStatistics;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.TriggerItem;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.streaming.NodeEventEmitterService;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.SnapshotService;
import com.apimarketplace.orchestrator.services.streaming.StepByStepEventService;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import com.apimarketplace.orchestrator.services.streaming.EdgeStatusService;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.persistence.WorkflowEntityResolverService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("V2ExecutionEventService")
class V2ExecutionEventServiceTest {

    @Mock
    private WorkflowStreamingService streamingService;

    @Mock
    private NodeEventEmitterService nodeEventEmitterService;

    @Mock
    private WorkflowEventPublisher eventPublisher;

    @Mock
    private EdgeStatusService edgeStatusService;

    @Mock
    private SnapshotService snapshotService;

    @Mock
    private StateSnapshotService stateSnapshotService;

    @Mock
    private NodeCompletionService nodeCompletionService;

    @Mock
    private EdgeStatusEmitter edgeStatusEmitter;

    @Mock
    private StepByStepEventService stepByStepEventService;

    @Mock
    private WorkflowEpochService workflowEpochService;

    @Mock
    private WorkflowEntityResolverService entityResolverService;

    @Mock
    private RunningNodeTracker runningNodeTracker;

    @Mock
    private WorkflowExecution execution;

    @Mock
    private ExecutionNode node;

    @Mock
    private TriggerItem triggerItem;

    @Mock
    private ExecutionContext context;

    private V2ExecutionEventService service;

    @BeforeEach
    void setUp() {
        service = new V2ExecutionEventService(
            streamingService,
            nodeEventEmitterService,
            eventPublisher,
            edgeStatusService,
            snapshotService,
            stateSnapshotService,
            nodeCompletionService,
            edgeStatusEmitter,
            stepByStepEventService,
            workflowEpochService,
            entityResolverService,
            runningNodeTracker
        );
        lenient().when(execution.getRunId()).thenReturn("run-123");
        lenient().when(execution.getStatistics()).thenReturn(ExecutionStatistics.empty());
        lenient().when(edgeStatusService.flushEdgeBatch(any())).thenReturn(Map.of());
    }

    @Nested
    @DisplayName("initializeExecution()")
    class InitializeExecutionTests {
        @Test
        @DisplayName("Should initialize all services")
        void shouldInitializeAllServices() {
            service.initializeExecution(execution);

            verify(streamingService).initializeStreaming(execution);
            verify(edgeStatusService).registerWorkflowEdges(execution);
        }

        @Test
        @DisplayName("Should initialize with step-by-step mode")
        void shouldInitializeWithStepByStepMode() {
            service.initializeExecution(execution, true);

            verify(streamingService).initializeStreaming(execution);
            verify(edgeStatusService).registerWorkflowEdges(execution);
        }
    }

    @Nested
    @DisplayName("rePublishNodeOutput() - payload-lost acknowledgement")
    class RePublishNodeOutputTests {

        private ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> attachAppender() {
            ch.qos.logback.classic.Logger log =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(V2ExecutionEventService.class);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
            appender.start();
            log.addAppender(appender);
            return appender;
        }

        @Test
        @DisplayName("payload-lost re-persist logs a WARN naming the cause (return NOT silently dropped) but does NOT rewrite - the loop already exited")
        void payloadLostRePersistLogsWarn() {
            when(node.getNodeId()).thenReturn("core:my_loop");
            NodeExecutionResult terminationResult = NodeExecutionResult.success(
                "core:my_loop", Map.of("terminated", true));
            when(nodeCompletionService.emitNodeComplete(eq(execution), eq(node), any(), any(), anyInt(), any(), any()))
                .thenReturn(com.apimarketplace.orchestrator.services.completion.StepCompletionResult
                    .persistedPayloadLost(Map.of(), Map.of(),
                        "[storage] Output payload lost: storage write failed after retries"));

            var appender = attachAppender();
            service.rePublishNodeOutput(execution, node, terminationResult, triggerItem, 0, context, 3);

            boolean warned = appender.list.stream().anyMatch(e ->
                e.getLevel() == ch.qos.logback.classic.Level.WARN
                    && e.getFormattedMessage().contains("Loop-termination re-persist lost its output payload")
                    && e.getFormattedMessage().contains("storage write failed after retries"));
            assertThat(warned)
                .as("the completion return must not be silently dropped - a payload loss on the "
                        + "loop-termination re-persist must surface a WARN naming the cause")
                .isTrue();
        }

        @Test
        @DisplayName("BEHAVIOUR GUARD: a normally persisted re-persist logs NO payload-loss WARN")
        void normalRePersistDoesNotWarn() {
            NodeExecutionResult terminationResult = NodeExecutionResult.success(
                "core:my_loop", Map.of("terminated", true));
            when(nodeCompletionService.emitNodeComplete(eq(execution), eq(node), any(), any(), anyInt(), any(), any()))
                .thenReturn(com.apimarketplace.orchestrator.services.completion.StepCompletionResult
                    .persisted(Map.of(), Map.of()));

            var appender = attachAppender();
            service.rePublishNodeOutput(execution, node, terminationResult, triggerItem, 0, context, 3);

            boolean warned = appender.list.stream().anyMatch(e ->
                e.getFormattedMessage().contains("Loop-termination re-persist lost its output payload"));
            assertThat(warned).isFalse();
        }

        @Test
        @DisplayName("BEHAVIOUR GUARD: a null completion (legacy/duplicate path) logs NO payload-loss WARN and does not throw")
        void nullCompletionDoesNotWarn() {
            NodeExecutionResult terminationResult = NodeExecutionResult.success(
                "core:my_loop", Map.of("terminated", true));
            when(nodeCompletionService.emitNodeComplete(eq(execution), eq(node), any(), any(), anyInt(), any(), any()))
                .thenReturn(null);

            var appender = attachAppender();
            service.rePublishNodeOutput(execution, node, terminationResult, triggerItem, 0, context, 3);

            boolean warned = appender.list.stream().anyMatch(e ->
                e.getFormattedMessage().contains("Loop-termination re-persist lost its output payload"));
            assertThat(warned).isFalse();
        }
    }

    @Nested
    @DisplayName("initializeTotalItems()")
    class InitializeTotalItemsTests {
        @Test
        @DisplayName("Should delegate to nodeCompletionService")
        void shouldDelegateToNodeCompletionService() {
            service.initializeTotalItems(execution, 10);

            verify(nodeCompletionService).initializeTotalItems(execution, 10);
        }
    }

    @Nested
    @DisplayName("emitNodeStart()")
    class EmitNodeStartTests {
        @Test
        @DisplayName("Should remove from ready, emit incoming edges and node start, then send snapshot")
        void shouldRemoveFromReadyEmitIncomingEdgesAndNodeStartThenSendSnapshot() {
            when(node.getNodeId()).thenReturn("trigger:manual");
            service.emitNodeStart(execution, node, triggerItem, 0, 0);

            InOrder inOrder = inOrder(stateSnapshotService, edgeStatusEmitter, nodeCompletionService, snapshotService);
            inOrder.verify(stateSnapshotService).removeReadyNode("run-123", "trigger:manual");
            inOrder.verify(edgeStatusEmitter).emitIncomingEdges(execution, node, 0);
            // P2.3.1: legacy 4-arg emitNodeStart delegates to per-epoch overload with epoch=0
            inOrder.verify(nodeCompletionService).emitNodeStart(execution, node, triggerItem, 0, 0);
            inOrder.verify(snapshotService).sendSnapshot("run-123");
        }
    }

    @Nested
    @DisplayName("emitNodeComplete()")
    class EmitNodeCompleteTests {
        @Test
        @DisplayName("Should emit node completion")
        void shouldEmitNodeCompletion() {
            when(nodeCompletionService.extractCurrentIteration(any(), any(), any())).thenReturn(0);
            NodeExecutionResult result = NodeExecutionResult.success("test-node", Map.of());

            service.emitNodeComplete(execution, node, result, triggerItem, 0, context);

            verify(nodeCompletionService).emitNodeComplete(execution, node, result, triggerItem, 0, context);
        }

        @Test
        @DisplayName("Should emit outgoing edges")
        void shouldEmitOutgoingEdges() {
            when(nodeCompletionService.extractCurrentIteration(any(), any(), any())).thenReturn(0);
            NodeExecutionResult result = NodeExecutionResult.success("test-node", Map.of());

            service.emitNodeComplete(execution, node, result, triggerItem, 0, context);

            verify(edgeStatusEmitter).emitOutgoingEdges(eq(execution), eq(node), eq(0), eq(0), eq(result), eq(false), anyInt(), any());
        }

        @Test
        @DisplayName("Should use standard path for decision nodes (no separate persistence)")
        void shouldUseStandardPathForDecisionNodes() {
            when(node.isDecisionNode()).thenReturn(true);
            when(nodeCompletionService.extractCurrentIteration(any(), any(), any())).thenReturn(1);
            NodeExecutionResult result = NodeExecutionResult.success("test-node", Map.of());

            service.emitNodeComplete(execution, node, result, triggerItem, 0, context);

            // Decision nodes now go through the standard emitNodeComplete path
            verify(nodeCompletionService).emitNodeComplete(execution, node, result, triggerItem, 0, context);
        }

        @Test
        @DisplayName("Should handle explicit iteration")
        void shouldHandleExplicitIteration() {
            NodeExecutionResult result = NodeExecutionResult.success("test-node", Map.of());

            service.emitNodeComplete(execution, node, result, triggerItem, 0, context, 5);

            verify(nodeCompletionService).emitNodeComplete(execution, node, result, triggerItem, 0, context, 5);
            verify(edgeStatusEmitter).emitOutgoingEdges(eq(execution), eq(node), eq(0), eq(5), eq(result), eq(false), anyInt(), any());
        }
    }

    @Nested
    @DisplayName("emitNodeAwaitingSignal()")
    class EmitNodeAwaitingSignalTests {
        @Test
        @DisplayName("P2.3.1 - threads context.epoch() into the per-epoch markCompleted (non-zero epoch)")
        void shouldThreadContextEpochIntoMarkCompleted() {
            // Pin V2ExecutionEventService:226 - when a node yields AWAITING_SIGNAL, the
            // running count must be cleared under the per-epoch Redis key
            // (orchestrator:running:{runId}:{epoch}). If we cleared under epoch=0 instead,
            // the deferred-reset gate at ReusableTriggerService:1614 would observe a stale
            // running count for the current epoch and refuse to close it (or, conversely,
            // close it on the wrong epoch). The mark/markCompleted invariant requires the
            // SAME epoch source on both sides - context.epoch().
            when(node.getNodeId()).thenReturn("core:wait_user_approval");
            when(context.epoch()).thenReturn(7);
            NodeExecutionResult result = NodeExecutionResult.awaitingSignal(
                    "core:wait_user_approval",
                    com.apimarketplace.orchestrator.domain.execution.SignalType.USER_APPROVAL,
                    Map.of("expires_at", "2026-01-01T00:00:00Z"));

            service.emitNodeAwaitingSignal(execution, node, result, triggerItem, 0, context);

            verify(runningNodeTracker).markCompleted("run-123", 7, "core:wait_user_approval");
            verify(snapshotService).sendSnapshot("run-123");
        }

        @Test
        @DisplayName("regression: AWAITING_SIGNAL step events include post-yield statusCounts for frontend badges")
        void shouldEmitAwaitingSignalStatusCounts() {
            when(node.getNodeId()).thenReturn("core:manager_approval");
            when(context.epoch()).thenReturn(2);
            NodeExecutionResult result = NodeExecutionResult.awaitingSignal(
                    "core:manager_approval",
                    com.apimarketplace.orchestrator.domain.execution.SignalType.USER_APPROVAL,
                    Map.of("expires_at", "2026-01-01T00:00:00Z"));

            service.emitNodeAwaitingSignal(execution, node, result, triggerItem, 0, context);

            ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(eventPublisher).emitStep(
                eq("run-123"),
                eq("core:manager_approval"),
                payloadCaptor.capture(),
                eq(com.apimarketplace.orchestrator.services.streaming.events.StepLifecycle.AWAITING_SIGNAL));

            assertThat(payloadCaptor.getValue().get("statusCounts"))
                .as("AWAITING_SIGNAL transition must carry the same badge signal that snapshots expose")
                .isEqualTo(Map.of(
                    "running", 0,
                    "completed", 0,
                    "failed", 0,
                    "skipped", 0,
                    "awaitingSignal", 1,
                    "processed", 0,
                    "total", 0));
        }

        @Test
        @DisplayName("P2.3.1 - falls back to epoch=0 when context is null (defensive)")
        void shouldFallbackToZeroEpochWhenContextNull() {
            when(node.getNodeId()).thenReturn("core:wait_timer");
            NodeExecutionResult result = NodeExecutionResult.awaitingSignal(
                    "core:wait_timer",
                    com.apimarketplace.orchestrator.domain.execution.SignalType.WAIT_TIMER,
                    Map.of());

            service.emitNodeAwaitingSignal(execution, node, result, triggerItem, 0, null);

            verify(runningNodeTracker).markCompleted("run-123", 0, "core:wait_timer");
        }
    }

    @Nested
    @DisplayName("emitPostPersistenceCompletionForSplitBatch()")
    class EmitPostPersistenceCompletionForSplitBatchTests {
        @Test
        @DisplayName("Should begin/flush edge batch ONCE PER ITEM so per-edge counts match item count")
        void shouldBeginAndFlushEdgeBatchPerItem() {
            // The edge batch is a Map<edgeKey, lifecycle> with overwrite semantics: if a
            // single batch spanned N items all traversing the same edge, the map would
            // dedupe to a single entry and the frontend edge statusCounts would show
            // completed=1 instead of completed=N. Scoping begin/flush per item is the
            // load-bearing behavior - this test pins it so a future "optimization" back
            // to a single outer batch can't silently reintroduce the regression.
            NodeExecutionResult item0 = NodeExecutionResult.success("agent:check_safety", Map.of("passed", true));
            NodeExecutionResult item1 = NodeExecutionResult.success("agent:check_safety", Map.of("passed", true));
            NodeExecutionResult item2 = NodeExecutionResult.success("agent:check_safety", Map.of("passed", false));
            List<com.apimarketplace.orchestrator.execution.v2.async.IndexedNodeResult> batch = List.of(
                new com.apimarketplace.orchestrator.execution.v2.async.IndexedNodeResult(0, item0),
                new com.apimarketplace.orchestrator.execution.v2.async.IndexedNodeResult(1, item1),
                new com.apimarketplace.orchestrator.execution.v2.async.IndexedNodeResult(2, item2));

            service.emitPostPersistenceCompletionForSplitBatch(execution, node, batch, 1, "trigger:start");

            // Three items → three per-item batch scopes.
            verify(edgeStatusService, times(3)).beginEdgeBatch();
            verify(edgeStatusService, times(3)).flushEdgeBatch("run-123");
            // splitScope=true: per-item skips must not poison the epoch's global skippedNodeIds.
            verify(edgeStatusEmitter, times(3)).emitOutgoingEdges(
                eq(execution), eq(node), anyInt(), isNull(), any(NodeExecutionResult.class), eq(false), eq(1), eq("trigger:start"), eq(true));
            // Snapshot is pushed once at the end, not per item.
            verify(snapshotService, times(1)).sendSnapshot("run-123");
        }

        @Test
        @DisplayName("Should use the absolute itemIndex from IndexedNodeResult (not the batch position)")
        void shouldUseAbsoluteItemIndex() {
            // Regression: sparse batches (e.g. items {0, 1, 2, 7} after upstream filtering)
            // must emit edges at their real itemIndex. Iterating by position 0..3 attributed
            // the 4th item to itemIndex=3 and lost the actual index 7, making edge counts
            // off by one and record_X not see the right item.
            NodeExecutionResult item0 = NodeExecutionResult.success("agent:classify", Map.of());
            NodeExecutionResult item7 = NodeExecutionResult.success("agent:classify", Map.of());
            List<com.apimarketplace.orchestrator.execution.v2.async.IndexedNodeResult> batch = List.of(
                new com.apimarketplace.orchestrator.execution.v2.async.IndexedNodeResult(0, item0),
                new com.apimarketplace.orchestrator.execution.v2.async.IndexedNodeResult(7, item7));

            service.emitPostPersistenceCompletionForSplitBatch(execution, node, batch, 1, "trigger:start");

            // Each emitOutgoingEdges must carry the real itemIndex (0 and 7), not 0 and 1.
            verify(edgeStatusEmitter).emitOutgoingEdges(
                eq(execution), eq(node), eq(0), isNull(), eq(item0), eq(false), eq(1), eq("trigger:start"), eq(true));
            verify(edgeStatusEmitter).emitOutgoingEdges(
                eq(execution), eq(node), eq(7), isNull(), eq(item7), eq(false), eq(1), eq("trigger:start"), eq(true));
        }

        @Test
        @DisplayName("FAILED branching split items suppress emitter-side recursive propagation")
        void failedBranchingItemsLeaveRecursivePropagationToPerItemCascade() {
            when(node.isBranchingNode()).thenReturn(true);
            NodeExecutionResult failedItem = NodeExecutionResult.failure(
                "agent:check_safety", "Guardrail provider failed");
            NodeExecutionResult passedItem = NodeExecutionResult.success(
                "agent:check_safety", Map.of("passed", true));
            List<com.apimarketplace.orchestrator.execution.v2.async.IndexedNodeResult> batch = List.of(
                new com.apimarketplace.orchestrator.execution.v2.async.IndexedNodeResult(3, failedItem),
                new com.apimarketplace.orchestrator.execution.v2.async.IndexedNodeResult(4, passedItem));

            service.emitPostPersistenceCompletionForSplitBatch(execution, node, batch, 8, "trigger:start");

            verify(edgeStatusEmitter).emitOutgoingEdges(
                eq(execution), eq(node), eq(3), isNull(), eq(failedItem),
                eq(true), eq(8), eq("trigger:start"), eq(true));
            verify(edgeStatusEmitter).emitOutgoingEdges(
                eq(execution), eq(node), eq(4), isNull(), eq(passedItem),
                eq(false), eq(8), eq("trigger:start"), eq(true));
        }

        @Test
        @DisplayName("Should skip null items in the batch (defensive)")
        void shouldSkipNullItemsInBatch() {
            NodeExecutionResult item0 = NodeExecutionResult.success("agent:check_safety", Map.of());
            NodeExecutionResult item2 = NodeExecutionResult.success("agent:check_safety", Map.of());
            List<com.apimarketplace.orchestrator.execution.v2.async.IndexedNodeResult> batch = java.util.Arrays.asList(
                new com.apimarketplace.orchestrator.execution.v2.async.IndexedNodeResult(0, item0),
                new com.apimarketplace.orchestrator.execution.v2.async.IndexedNodeResult(1, null),
                new com.apimarketplace.orchestrator.execution.v2.async.IndexedNodeResult(2, item2));

            service.emitPostPersistenceCompletionForSplitBatch(execution, node, batch, 0, "trigger:start");

            // Null slot contributes nothing - only the two real items open a batch scope.
            verify(edgeStatusService, times(2)).beginEdgeBatch();
            verify(edgeStatusService, times(2)).flushEdgeBatch("run-123");
            verify(edgeStatusEmitter, times(2)).emitOutgoingEdges(
                eq(execution), eq(node), anyInt(), isNull(), any(NodeExecutionResult.class), eq(false), eq(0), eq("trigger:start"), eq(true));
            verify(snapshotService).sendSnapshot("run-123");
        }

        @Test
        @DisplayName("Should warn and no-op for empty batch")
        void shouldNoOpForEmptyBatch() {
            service.emitPostPersistenceCompletionForSplitBatch(execution, node, List.of(), 0, "trigger:start");

            verify(edgeStatusService, never()).beginEdgeBatch();
            verify(edgeStatusEmitter, never()).emitOutgoingEdges(
                any(), any(), anyInt(), any(), any(), anyBoolean(), anyInt(), any(), anyBoolean());
            verify(snapshotService, never()).sendSnapshot(anyString());
        }
    }

    @Nested
    @DisplayName("emitWorkflowComplete()")
    class EmitWorkflowCompleteTests {
        @Test
        @DisplayName("Should emit workflow status event")
        void shouldEmitWorkflowStatusEvent() {
            service.emitWorkflowComplete(execution, true, "Success message");

            verify(eventPublisher).emitWorkflowStatus(
                eq("run-123"),
                eq("COMPLETED"),
                eq("Success message"),
                any(Map.class),
                eq(true)
            );
        }

        @Test
        @DisplayName("Should emit failed status when not success")
        void shouldEmitFailedStatusWhenNotSuccess() {
            service.emitWorkflowComplete(execution, false, "Failure message");

            verify(eventPublisher).emitWorkflowStatus(
                eq("run-123"),
                eq("FAILED"),
                eq("Failure message"),
                any(Map.class),
                eq(true)
            );
        }

        @Test
        @DisplayName("Should send workflow statistics")
        void shouldSendWorkflowStatistics() {
            service.emitWorkflowComplete(execution, true, "Done");

            verify(streamingService).sendWorkflowStatisticsEvent(execution);
        }
    }

    @Nested
    @DisplayName("emitStepByStepReady()")
    class EmitStepByStepReadyTests {
        @Test
        @DisplayName("Should emit step-by-step ready event")
        void shouldEmitStepByStepReadyEvent() {
            Set<String> readyNodes = Set.of("mcp:step1", "mcp:step2");
            when(stateSnapshotService.getSnapshot("run-123")).thenReturn(StateSnapshot.empty());

            service.emitStepByStepReady(execution, readyNodes, "mcp:completed", false);

            verify(eventPublisher).emitWorkflowStatus(
                eq("run-123"),
                eq("STEP_BY_STEP_READY"),
                anyString(),
                argThat(data -> {
                    @SuppressWarnings("unchecked")
                    Set<String> nodes = (Set<String>) data.get("readyNodes");
                    return nodes.equals(readyNodes) &&
                           Boolean.FALSE.equals(data.get("workflowComplete")) &&
                           "mcp:completed".equals(data.get("completedNodeId"));
                }),
                eq(false)
            );
        }

        @Test
        @DisplayName("Should mark terminal when workflow complete")
        void shouldMarkTerminalWhenWorkflowComplete() {
            when(stateSnapshotService.getSnapshot("run-123")).thenReturn(StateSnapshot.empty());
            service.emitStepByStepReady(execution, Set.of(), null, true);

            verify(eventPublisher).emitWorkflowStatus(
                anyString(), anyString(), anyString(), any(), eq(true)
            );
        }
    }

    @Nested
    @DisplayName("emitStepByStepPaused()")
    class EmitStepByStepPausedTests {
        @Test
        @DisplayName("Should emit step-by-step paused event")
        void shouldEmitStepByStepPausedEvent() {
            Set<String> readyNodes = Set.of("mcp:step1");

            service.emitStepByStepPaused(execution, readyNodes);

            verify(eventPublisher).emitWorkflowStatus(
                eq("run-123"),
                eq("STEP_BY_STEP_PAUSED"),
                contains("Waiting"),
                any(Map.class),
                eq(false)
            );
        }
    }

    @Nested
    @DisplayName("cleanupExecution()")
    class CleanupExecutionTests {
        @Test
        @DisplayName("Should complete without error")
        void shouldCompleteWithoutError() {
            // Should not throw - StateManager cleanup was removed; the only remaining
            // work is purging the RunningNodeTracker overlay (asserted below).
            service.cleanupExecution("run-123");
        }

        @Test
        @DisplayName("Should purge the RunningNodeTracker overlay so a finished run leaves no node painted running")
        void shouldPurgeRunningOverlayOnCleanup() {
            // cleanupExecution is the single end-of-run chokepoint (all callers are
            // terminal). Before the fix it was a no-op and cleanupRun had no caller,
            // so a dropped markCompleted left the per-run overlay alive until the 1h
            // TTL - the snapshot kept painting the node "running" long after COMPLETED.
            service.cleanupExecution("run-123");

            verify(runningNodeTracker).cleanupRun("run-123");
        }
    }

    /**
     * Audit MF-2 (2026-05-08) - pin the per-epoch recording lifecycle.
     *
     * <p>The fix for the user-reported "split body edges missing in epoch viewer"
     * bug routes through {@link com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService#emitItemOutgoingEdgesInSplit}
     * and {@link com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService#recordSkipEdgesPerEpoch}.
     * Without these tests, a future regression that drops the
     * {@code workflowEpochService.recordEdgeCounts} call would leave SplitAware
     * tests green but silently restore the bug. These tests pin the load-bearing
     * cross-service call directly.
     */
    @Nested
    @DisplayName("Per-epoch edge recording - split body lifecycle (audit MF-2)")
    class PerEpochEdgeRecordingTests {

        @Test
        @DisplayName("emitItemOutgoingEdgesInSplit drives the begin → emit → flush → record-per-epoch lifecycle end-to-end")
        void emitItemOutgoingEdgesInSplitRecordsPerEpoch() {
            // Stub flushEdgeBatch to return a non-empty batch - simulates one
            // outgoing edge that emit accumulated. The bug being pinned is that
            // recordEdgeCounts must be called with these counts, with the right
            // epoch and triggerId.
            when(execution.getRunId()).thenReturn("run-pin-1");
            when(execution.getWorkflowRunId()).thenReturn(java.util.UUID.randomUUID());
            Map<String, Map.Entry<String, Integer>> batch = new java.util.LinkedHashMap<>();
            batch.put("table:check_memory->core:is_new", Map.entry("COMPLETED", 1));
            when(edgeStatusService.flushEdgeBatch("run-pin-1")).thenReturn(batch);

            NodeExecutionResult result = NodeExecutionResult.success("table:check_memory", Map.of());

            service.emitItemOutgoingEdgesInSplit(
                execution, node, /*itemIndex*/ 0, /*iteration*/ null,
                result, /*suppressSkipPropagation*/ false,
                /*epoch*/ 7, /*triggerId*/ "trigger:cron");

            // Lifecycle pin: every step of the contract is observed.
            verify(edgeStatusService).beginEdgeBatch();
            verify(edgeStatusEmitter).emitOutgoingEdges(
                eq(execution), eq(node), eq(0), isNull(), eq(result),
                eq(false), eq(7), eq("trigger:cron"), eq(true));
            verify(edgeStatusService).flushEdgeBatch("run-pin-1");
            // The load-bearing assertion: recordEdgeCounts MUST be invoked with
            // the exact epoch and triggerId from the caller AND must carry the
            // edge key that emit accumulated. Two calls happen (one for COMPLETED,
            // one for SKIPPED, both flat maps) - atLeastOnce matches the COMPLETED
            // call carrying our edge key.
            verify(workflowEpochService, atLeastOnce()).recordEdgeCounts(
                eq("run-pin-1"), eq(7),
                org.mockito.ArgumentMatchers.argThat(m ->
                    m != null && m.containsKey("table:check_memory->core:is_new")),
                eq("trigger:cron"));
        }

        @Test
        @DisplayName("recordSkipEdgesPerEpoch lands skipped-edge counts in workflow_epochs (audit MF-1 sibling fix)")
        void recordSkipEdgesPerEpochInvokesEpochService() {
            // The user-reported scenario: decision branch SKIPPED edges (e.g.
            // is_new:if→exit for items that took the else branch) MUST be
            // recorded per-epoch so the epoch viewer surfaces them.
            when(execution.getRunId()).thenReturn("run-skip-1");
            when(execution.getWorkflowRunId()).thenReturn(java.util.UUID.randomUUID());

            Map<String, Map.Entry<String, Integer>> skipIncrements = new java.util.LinkedHashMap<>();
            skipIncrements.put("core:is_new:if->core:exit", Map.entry("SKIPPED", 3));

            service.recordSkipEdgesPerEpoch(execution, skipIncrements,
                /*epoch*/ 12, /*triggerId*/ "trigger:cron");

            // Two calls happen (completed + skipped flat maps); the SKIPPED one
            // carries our edge key.
            verify(workflowEpochService, atLeastOnce()).recordEdgeCounts(
                eq("run-skip-1"), eq(12),
                org.mockito.ArgumentMatchers.argThat(m ->
                    m != null && m.containsKey("core:is_new:if->core:exit")),
                eq("trigger:cron"));
        }

        @Test
        @DisplayName("recordSkipEdgesPerEpoch is a no-op for empty edge map (defensive)")
        void recordSkipEdgesPerEpochNoOpOnEmpty() {
            service.recordSkipEdgesPerEpoch(execution, Map.of(), 5, "trigger:cron");
            verify(workflowEpochService, never()).recordEdgeCounts(
                anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.<Map<String, String>>any(),
                org.mockito.ArgumentMatchers.<String>any());
        }
    }
}
