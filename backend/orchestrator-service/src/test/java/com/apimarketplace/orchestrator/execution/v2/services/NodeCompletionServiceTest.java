package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.workflow.Edge;
import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.TriggerItem;
import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeType;
import com.apimarketplace.orchestrator.execution.v2.state.BackEdgeState;
import com.apimarketplace.conversation.client.ConversationClient;
import com.apimarketplace.orchestrator.services.completion.StepCompletionOrchestrator;
import com.apimarketplace.orchestrator.services.streaming.NodeEventEmitterService;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NodeCompletionService")
class NodeCompletionServiceTest {

    @Mock private StepCompletionOrchestrator stepCompletionOrchestrator;
    @Mock private NodeEventEmitterService nodeEventEmitterService;
    @Mock private WorkflowEventPublisher eventPublisher;
    @Mock private RunningNodeTracker runningNodeTracker;
    @Mock private ConversationClient conversationServiceClient;
    @Mock private WorkflowExecution execution;
    @Mock private ExecutionNode node;
    @Mock private TriggerItem triggerItem;
    @Mock private ExecutionContext context;

    private NodeCompletionService service;

    @BeforeEach
    void setUp() {
        service = new NodeCompletionService(stepCompletionOrchestrator, nodeEventEmitterService, eventPublisher, runningNodeTracker, conversationServiceClient);
    }

    @Nested
    @DisplayName("emitNodeStart()")
    class EmitNodeStartTests {
        @Test
        @DisplayName("Should emit RUNNING status event and track running in-memory")
        void shouldEmitRunningStatusEvent() {
            when(execution.getRunId()).thenReturn("run-1");
            when(node.getNodeId()).thenReturn("mcp:step1");

            service.emitNodeStart(execution, node, triggerItem, 0, 0);

            verify(nodeEventEmitterService).recordNodeExecution(eq("run-1"), eq("step1"), eq(0), eq(0), eq("RUNNING"));
            verify(eventPublisher).emitStep(eq("run-1"), eq("mcp:step1"), anyMap(), any());
            // P2.3.1: legacy 4-arg emitNodeStart delegates to per-epoch overload with epoch=0
            verify(runningNodeTracker).markRunning("run-1", 0, "mcp:step1");
        }

        @Test
        @DisplayName("Should not track running when execution is null")
        void shouldNotCallStateManagerWhenExecutionIsNull() {
            service.emitNodeStart(null, node, triggerItem, 0, 0);

            verifyNoInteractions(runningNodeTracker);
            verifyNoInteractions(nodeEventEmitterService);
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("Should not track running when node is null")
        void shouldNotCallStateManagerWhenNodeIsNull() {
            service.emitNodeStart(execution, null, triggerItem, 0, 0);

            verifyNoInteractions(runningNodeTracker);
            verifyNoInteractions(nodeEventEmitterService);
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("Should track RUNNING in-memory via RunningNodeTracker")
        void shouldTrackRunningInMemory() {
            when(execution.getRunId()).thenReturn("run-1");
            when(node.getNodeId()).thenReturn("mcp:step1");

            service.emitNodeStart(execution, node, triggerItem, 0, 0);

            // P2.3.1: legacy overload delegates to per-epoch with epoch=0
            verify(runningNodeTracker).markRunning("run-1", 0, "mcp:step1");
        }

        @Test
        @DisplayName("Should call runningNodeTracker with correct nodeId for different prefixes")
        void shouldCallRunningNodeTrackerWithCorrectNodeIdForDifferentPrefixes() {
            when(execution.getRunId()).thenReturn("run-1");
            when(node.getNodeId()).thenReturn("core:decision1");

            service.emitNodeStart(execution, node, triggerItem, 0, 0);

            // P2.3.1: legacy overload delegates to per-epoch with epoch=0
            verify(runningNodeTracker).markRunning("run-1", 0, "core:decision1");
        }
    }

    @Nested
    @DisplayName("emitNodeComplete()")
    class EmitNodeCompleteTests {
        @Test
        @DisplayName("Should delegate to StepCompletionOrchestrator")
        void shouldDelegateToStepCompletionOrchestrator() {
            when(node.getNodeId()).thenReturn("mcp:step1");
            lenient().when(node.getType()).thenReturn(NodeType.MCP);
            NodeExecutionResult result = NodeExecutionResult.success("mcp:step1", Map.of("data", "value"));

            service.emitNodeComplete(execution, node, result, triggerItem, 0, context, null);

            verify(stepCompletionOrchestrator).completeStep(eq(execution), eq("mcp:step1"), eq("step1"), any(), eq(0), isNull());
        }

        @Test
        @DisplayName("Should include error info for failed steps")
        void shouldIncludeErrorInfoForFailedSteps() {
            when(node.getNodeId()).thenReturn("mcp:step1");
            lenient().when(node.getType()).thenReturn(NodeType.MCP);
            NodeExecutionResult result = NodeExecutionResult.failure("mcp:step1", "Connection failed");

            service.emitNodeComplete(execution, node, result, triggerItem, 0, context, null);

            verify(stepCompletionOrchestrator).completeStep(eq(execution), any(), any(), argThat(r ->
                r.output() != null && r.output().containsKey("error")), anyInt(), any());
        }

        @Test
        @DisplayName("Should preserve explicit failure output status while adding error metadata")
        void shouldPreserveExplicitFailureOutputStatus() {
            when(node.getNodeId()).thenReturn("core:hard_stop");
            lenient().when(node.getType()).thenReturn(NodeType.STOP_ON_ERROR);
            NodeExecutionResult result = NodeExecutionResult.failureWithOutput(
                "core:hard_stop",
                "Blocked route",
                Map.of("error_message", "Blocked route", "status", "failed"),
                0L
            );

            service.emitNodeComplete(execution, node, result, triggerItem, 0, context, null);

            verify(stepCompletionOrchestrator).completeStep(eq(execution), any(), any(), argThat(r ->
                r.output() != null
                    && "failed".equals(r.output().get("status"))
                    && "Blocked route".equals(r.output().get("error"))), anyInt(), any());
        }

        @Test
        @DisplayName("P2.3.1 - markCompleted threads context.epoch() into the per-epoch Redis key (non-zero epoch)")
        void shouldThreadContextEpochIntoMarkCompleted() {
            // Pins the contract: emitNodeComplete must call markCompleted with the
            // epoch from the ExecutionContext (NOT a hardcoded 0). A regression
            // that hardcoded 0 would leave the deferred-reset gate at
            // ReusableTriggerService:1614 reading an empty per-epoch key for any
            // epoch > 0 - premature close.
            when(execution.getRunId()).thenReturn("run-1");
            when(node.getNodeId()).thenReturn("mcp:step1");
            lenient().when(node.getType()).thenReturn(NodeType.MCP);
            when(context.epoch()).thenReturn(5);
            NodeExecutionResult result = NodeExecutionResult.success("mcp:step1", Map.of());

            service.emitNodeComplete(execution, node, result, triggerItem, 0, context, null);

            verify(runningNodeTracker).markCompleted("run-1", 5, "mcp:step1");
        }

        @Test
        @DisplayName("P2.3.1 - markCompleted falls back to epoch=0 when context is null (defensive)")
        void shouldFallbackToZeroEpochWhenContextNull() {
            when(execution.getRunId()).thenReturn("run-1");
            when(node.getNodeId()).thenReturn("mcp:step1");
            lenient().when(node.getType()).thenReturn(NodeType.MCP);
            NodeExecutionResult result = NodeExecutionResult.success("mcp:step1", Map.of());

            service.emitNodeComplete(execution, node, result, triggerItem, 0, null, null);

            verify(runningNodeTracker).markCompleted("run-1", 0, "mcp:step1");
        }
    }

    @Nested
    @DisplayName("convertToStepResult()")
    class ConvertToStepResultTests {
        @Test
        @DisplayName("Should convert success result")
        void shouldConvertSuccessResult() {
            NodeExecutionResult result = NodeExecutionResult.success("mcp:test", Map.of("key", "value"));
            StepExecutionResult stepResult = service.convertToStepResult("mcp:test", result);
            assertEquals(NodeStatus.COMPLETED, stepResult.status());
        }

        @Test
        @DisplayName("Should convert failure result")
        void shouldConvertFailureResult() {
            NodeExecutionResult result = NodeExecutionResult.failure("mcp:test", "Error occurred");
            StepExecutionResult stepResult = service.convertToStepResult("mcp:test", result);
            assertEquals(NodeStatus.FAILED, stepResult.status());
        }

        @Test
        @DisplayName("Should convert skipped result to SKIPPED (not FAILED)")
        void shouldConvertSkippedResult() {
            NodeExecutionResult result = NodeExecutionResult.skipped("core:sync_1",
                "All sources failed or were skipped (QUEUE_1_TO_1 requires at least one successful source)");
            StepExecutionResult stepResult = service.convertToStepResult("core:sync_1", result);

            assertEquals(NodeStatus.SKIPPED, stepResult.status());
            assertEquals("core:sync_1", stepResult.stepId());
            assertNotNull(stepResult.message());
            assertTrue(stepResult.message().contains("All sources failed"));
        }

        @Test
        @DisplayName("Should preserve skip reason in message")
        void shouldPreserveSkipReasonInMessage() {
            NodeExecutionResult result = NodeExecutionResult.skipped("core:merge", "All predecessors skipped");
            StepExecutionResult stepResult = service.convertToStepResult("core:merge", result);

            assertEquals(NodeStatus.SKIPPED, stepResult.status());
            assertEquals("All predecessors skipped", stepResult.message());
        }

        @Test
        @DisplayName("regression: skipped conversion preserves skip_reason output for persistence and inspector")
        void shouldPreserveSkipReasonOutputForSkippedResult() {
            NodeExecutionResult result = NodeExecutionResult.skipped("core:merge", "All predecessors skipped");

            StepExecutionResult stepResult = service.convertToStepResult("core:merge", result);

            assertEquals(NodeStatus.SKIPPED, stepResult.status());
            assertNotNull(stepResult.output());
            assertEquals("All predecessors skipped", stepResult.output().get("skip_reason"));
        }

        @Test
        @DisplayName("regression: skipped conversion forwards split deferred aggregate marker")
        void shouldPreserveDeferredAggregateMarkerForSkippedResult() {
            Map<String, Object> metadata = Map.of(
                "skip_reason", "No items routed to this branch",
                ExecutionMetadataKeys.DEFER_SKIPPED_AGGREGATE_EVENT, true
            );
            NodeExecutionResult result = new NodeExecutionResult(
                "core:apply_ops", NodeStatus.SKIPPED, metadata,
                Optional.of("No items routed to this branch"), metadata, 0);

            StepExecutionResult stepResult = service.convertToStepResult("core:apply_ops", result);

            assertEquals(NodeStatus.SKIPPED, stepResult.status());
            assertEquals("No items routed to this branch", stepResult.output().get("skip_reason"));
            assertEquals(true, stepResult.output().get(ExecutionMetadataKeys.DEFER_SKIPPED_AGGREGATE_EVENT));
        }

        @Test
        @DisplayName("Should use default message for skipped result without reason")
        void shouldUseDefaultMessageForSkippedWithoutReason() {
            // Create a SKIPPED result manually without an errorMessage
            NodeExecutionResult result = new NodeExecutionResult(
                "core:merge", NodeStatus.SKIPPED, Map.of(), Optional.empty(), Map.of(), 0);
            StepExecutionResult stepResult = service.convertToStepResult("core:merge", result);

            assertEquals(NodeStatus.SKIPPED, stepResult.status());
            assertEquals("Skipped", stepResult.message());
        }

        @Test
        @DisplayName("Success output should be preserved")
        void shouldPreserveSuccessOutput() {
            Map<String, Object> output = Map.of("data", "value", "count", 42);
            NodeExecutionResult result = NodeExecutionResult.success("mcp:test", output);
            StepExecutionResult stepResult = service.convertToStepResult("mcp:test", result);

            assertEquals(NodeStatus.COMPLETED, stepResult.status());
            assertNotNull(stepResult.output());
            assertEquals("value", stepResult.output().get("data"));
        }

        @Test
        @DisplayName("Failure output should be preserved")
        void shouldPreserveFailureOutput() {
            NodeExecutionResult result = NodeExecutionResult.failure("mcp:test", "Connection refused");
            StepExecutionResult stepResult = service.convertToStepResult("mcp:test", result);

            assertEquals(NodeStatus.FAILED, stepResult.status());
            assertTrue(stepResult.message().contains("Connection refused"));
        }
    }

    @Nested
    @DisplayName("emitNodeComplete() with SKIPPED merge")
    class EmitNodeCompleteSkippedTests {

        @Test
        @DisplayName("Should persist SKIPPED status (not FAILED) when merge node returns SKIPPED")
        void shouldPersistSkippedStatusForMergeNode() {
            when(node.getNodeId()).thenReturn("core:sync_1");
            lenient().when(node.getType()).thenReturn(NodeType.MERGE);
            when(execution.getRunId()).thenReturn("run-1");
            NodeExecutionResult result = NodeExecutionResult.skipped("core:sync_1",
                "All sources failed or were skipped");

            service.emitNodeComplete(execution, node, result, triggerItem, 0, context, null);

            // Verify that StepCompletionOrchestrator receives a SKIPPED result, NOT FAILED
            verify(stepCompletionOrchestrator).completeStep(
                eq(execution), eq("core:sync_1"), eq("sync_1"),
                argThat(r -> r.status() == NodeStatus.SKIPPED),
                eq(0), isNull());
        }

        @Test
        @DisplayName("Should NOT include error metadata for SKIPPED status")
        void shouldNotIncludeErrorMetadataForSkippedStatus() {
            when(node.getNodeId()).thenReturn("core:sync_1");
            lenient().when(node.getType()).thenReturn(NodeType.MERGE);
            when(execution.getRunId()).thenReturn("run-1");
            NodeExecutionResult result = NodeExecutionResult.skipped("core:sync_1", "All predecessors skipped");

            service.emitNodeComplete(execution, node, result, triggerItem, 0, context, null);

            // SKIPPED results should be treated as non-error: isSuccess() is false but
            // the output should NOT contain "error" and "status":"error" metadata
            // because the node intentionally skipped, it didn't fail
            verify(stepCompletionOrchestrator).completeStep(
                any(), any(), any(),
                argThat(r -> r.status() == NodeStatus.SKIPPED
                    && r.output() != null
                    && !r.output().containsKey("error")
                    && !"error".equals(r.output().get("status"))),
                anyInt(), any());
        }
    }

    @Nested
    @DisplayName("extractLabel()")
    class ExtractLabelTests {
        @Test
        @DisplayName("Should extract label from prefixed nodeId")
        void shouldExtractLabelFromPrefixedNodeId() {
            assertEquals("my_step", service.extractLabel("mcp:my_step"));
            assertEquals("webhook", service.extractLabel("trigger:webhook"));
            assertEquals("decision", service.extractLabel("core:decision"));
        }

        @Test
        @DisplayName("Should return full nodeId if no colon")
        void shouldReturnFullNodeIdIfNoColon() {
            assertEquals("simple_node", service.extractLabel("simple_node"));
        }

        @Test
        @DisplayName("Should return 'unknown' for null")
        void shouldReturnUnknownForNull() {
            assertEquals("unknown", service.extractLabel(null));
        }
    }

    @Nested
    @DisplayName("extractCurrentIteration()")
    class ExtractCurrentIterationTests {
        @Test
        @DisplayName("Should return null for null context")
        void shouldReturnNullForNullContext() {
            assertNull(service.extractCurrentIteration(null, node, null));
        }

        @Test
        @DisplayName("Should get iteration from active back-edge state")
        void shouldGetIterationFromActiveBackEdgeState() {
            BackEdgeState backEdgeState = BackEdgeState.create("mcp:source->mcp:target", 10, "true");
            // Simulate iteration 5: create(0) + 5 increments = 5
            BackEdgeState atIteration5 = backEdgeState;
            for (int i = 0; i < 5; i++) {
                atIteration5 = atIteration5.incrementIteration();
            }
            when(context.getGlobalDataKeys()).thenReturn(Set.of("back_edge_state:mcp:source->mcp:target"));
            when(context.getGlobalData("back_edge_state:mcp:source->mcp:target")).thenReturn(Optional.of(atIteration5));

            assertEquals(5, service.extractCurrentIteration(context, node, null));
        }

        @Test
        @DisplayName("Should return null when no active back-edge state exists")
        void shouldReturnNullWhenNoActiveBackEdgeState() {
            when(context.getGlobalDataKeys()).thenReturn(Set.of());

            assertNull(service.extractCurrentIteration(context, node, null));
        }

        @Test
        @DisplayName("Bug B regression - should return the iteration of the LAST body run (where shouldContinue() == false but terminated == false)")
        void shouldReturnIterationOnLastBodyRunBeforeTerminate() {
            // Repro of the bug observed in prod loop e2e: with maxIterations=3,
            // body iter=2 is the last body run. At that moment, the back-edge
            // state in globalData has iteration=2, terminated=false. shouldContinue()
            // = (2+1)<3 = false. The OLD impl filtered on shouldContinue(),
            // returned null, the storage row stamped iteration=0 by default and
            // collided with body iter=0 via idx_workflow_step_data_unique_v6
            // (ON CONFLICT DO NOTHING dropped the row silently).
            //
            // Contract pinned by this test: extractCurrentIteration must return
            // the iteration value as long as the state is not terminated, even when
            // no further iteration would fit.
            BackEdgeState lastBodyState = BackEdgeState.create("mcp:source->mcp:target", 3, "true")
                    .incrementIteration()   // 0 -> 1
                    .incrementIteration();  // 1 -> 2 (last body iter for max=3)

            assertFalse(lastBodyState.shouldContinue(),
                "Pre-condition: at iter=2 with max=3, shouldContinue() must be false");
            assertFalse(lastBodyState.terminated(),
                "Pre-condition: terminate() not yet called - body iter=2 is currently running");

            when(context.getGlobalDataKeys()).thenReturn(Set.of("back_edge_state:mcp:source->mcp:target"));
            when(context.getGlobalData("back_edge_state:mcp:source->mcp:target"))
                    .thenReturn(Optional.of(lastBodyState));

            assertEquals(2, service.extractCurrentIteration(context, node, null),
                "Last body iteration must be reported so its storage row stamps with the correct iteration "
              + "and does not collide with body iter=0 via the unique constraint");
        }

        @Test
        @DisplayName("regression: should choose the active loop state that owns the completing node")
        void shouldChooseOwningLoopStateWhenParallelLoopsAreActive() {
            String b1EdgeId = "mcp:process_b1->core:loop_b1:iterate";
            String b0EdgeId = "mcp:process_b0->core:loop_b0:iterate";
            BackEdgeState branchOneState = BackEdgeState.create(b1EdgeId, 5, "true")
                .incrementIteration();
            BackEdgeState branchZeroState = BackEdgeState.create(b0EdgeId, 5, "true")
                .incrementIteration()
                .incrementIteration();
            Edge branchZeroIterateEdge = new Edge("mcp:process_b0", "core:loop_b0:iterate");
            WorkflowPlan plan = mock(WorkflowPlan.class);

            when(node.getNodeId()).thenReturn("mcp:process_b0");
            when(context.getGlobalDataKeys()).thenReturn(new LinkedHashSet<>(List.of(
                "back_edge_state:" + b1EdgeId,
                "back_edge_state:" + b0EdgeId
            )));
            when(context.getGlobalData("back_edge_state:" + b1EdgeId)).thenReturn(Optional.of(branchOneState));
            when(context.getGlobalData("back_edge_state:" + b0EdgeId)).thenReturn(Optional.of(branchZeroState));
            when(context.plan()).thenReturn(plan);
            when(plan.getIterateEdgesForSource("mcp:process_b0")).thenReturn(List.of(branchZeroIterateEdge));

            assertEquals(2, service.extractCurrentIteration(context, node, null));
        }

        @Test
        @DisplayName("regression: should not borrow an unrelated loop state when the plan can scope ownership")
        void shouldNotBorrowUnrelatedLoopStateWhenPlanCanScopeOwnership() {
            String b1EdgeId = "mcp:process_b1->core:loop_b1:iterate";
            BackEdgeState branchOneState = BackEdgeState.create(b1EdgeId, 5, "true")
                .incrementIteration();
            WorkflowPlan plan = mock(WorkflowPlan.class);

            when(node.getNodeId()).thenReturn("mcp:process_b0");
            when(context.getGlobalDataKeys()).thenReturn(Set.of("back_edge_state:" + b1EdgeId));
            when(context.getGlobalData("back_edge_state:" + b1EdgeId)).thenReturn(Optional.of(branchOneState));
            when(context.plan()).thenReturn(plan);
            when(plan.getIterateEdgesForSource("mcp:process_b0")).thenReturn(List.of());
            when(plan.getIterateEdges()).thenReturn(List.of());

            assertNull(service.extractCurrentIteration(context, node, null));
        }

        @Test
        @DisplayName("Should return null once the back-edge state is terminated")
        void shouldReturnNullWhenStateTerminated() {
            // After the back-edge fires its terminate() call (post-last-body),
            // we are no longer inside a body run - exit-path nodes execute next
            // and they shouldn't get a loop iteration stamp.
            BackEdgeState terminatedState = BackEdgeState.create("mcp:source->mcp:target", 3, "true")
                    .incrementIteration()
                    .incrementIteration()
                    .terminate();

            when(context.getGlobalDataKeys()).thenReturn(Set.of("back_edge_state:mcp:source->mcp:target"));
            when(context.getGlobalData("back_edge_state:mcp:source->mcp:target"))
                    .thenReturn(Optional.of(terminatedState));

            assertNull(service.extractCurrentIteration(context, node, null));
        }
    }

    @Nested
    @DisplayName("initializeTotalItems()")
    class InitializeTotalItemsTests {
        @Test
        @DisplayName("Should initialize total items")
        void shouldInitializeTotalItems() {
            when(execution.getRunId()).thenReturn("run-1");
            service.initializeTotalItems(execution, 10);
            verify(nodeEventEmitterService).initializeTotalItems("run-1", 10);
        }

        @Test
        @DisplayName("Should skip for null execution")
        void shouldSkipForNullExecution() {
            service.initializeTotalItems(null, 10);
            verifyNoInteractions(nodeEventEmitterService);
        }

        @Test
        @DisplayName("Should skip for zero or negative total")
        void shouldSkipForZeroOrNegativeTotal() {
            service.initializeTotalItems(execution, 0);
            service.initializeTotalItems(execution, -1);
            verifyNoInteractions(nodeEventEmitterService);
        }
    }

    @Nested
    @DisplayName("Response-to-conversation persistence")
    class ResponseConversationTests {

        private void setupResponseNode() {
            when(node.getNodeId()).thenReturn("core:response1");
            when(node.getType()).thenReturn(NodeType.RESPONSE);
            when(execution.getRunId()).thenReturn("run-1");
        }

        private ExecutionContext createContext(Map<String, Object> triggerData, int spawn, int itemIndex) {
            return ExecutionContext.create(
                "run-1", "wfRun-1", "tenant-1",
                "item-0", itemIndex,
                "trigger:chat", 0, spawn,
                triggerData, null
            );
        }

        @Test
        @DisplayName("Should save response message to conversation on RESPONSE node completion")
        void shouldSaveResponseToConversation() {
            setupResponseNode();
            Map<String, Object> triggerData = new HashMap<>();
            triggerData.put("conversationId", "conv-123");
            ExecutionContext ctx = createContext(triggerData, 0, 0);

            Map<String, Object> output = new HashMap<>();
            output.put("message", "Hello, how can I help?");
            output.put("node_type", "RESPONSE");
            NodeExecutionResult result = NodeExecutionResult.success("core:response1", output);

            service.emitNodeComplete(execution, node, result, triggerItem, 0, ctx, null);

            verify(conversationServiceClient).saveMessage(
                eq("conv-123"), eq("assistant"), eq("Hello, how can I help?"),
                isNull(), eq("tenant-1"), eq("wfRun-1")
            );
        }

        @Test
        @DisplayName("Should NOT save to conversation when node type is not RESPONSE")
        void shouldNotSaveForNonResponseNode() {
            when(node.getNodeId()).thenReturn("mcp:step1");
            when(node.getType()).thenReturn(NodeType.MCP);
            when(execution.getRunId()).thenReturn("run-1");

            Map<String, Object> triggerData = new HashMap<>();
            triggerData.put("conversationId", "conv-123");
            ExecutionContext ctx = createContext(triggerData, 0, 0);

            NodeExecutionResult result = NodeExecutionResult.success("mcp:step1", Map.of("data", "value"));

            service.emitNodeComplete(execution, node, result, triggerItem, 0, ctx, null);

            verifyNoInteractions(conversationServiceClient);
        }

        @Test
        @DisplayName("Should NOT save to conversation when conversationId is absent")
        void shouldNotSaveWhenNoConversationId() {
            setupResponseNode();
            Map<String, Object> triggerData = new HashMap<>();
            triggerData.put("message", "hello");
            ExecutionContext ctx = createContext(triggerData, 0, 0);

            Map<String, Object> output = new HashMap<>();
            output.put("message", "Response text");
            NodeExecutionResult result = NodeExecutionResult.success("core:response1", output);

            service.emitNodeComplete(execution, node, result, triggerItem, 0, ctx, null);

            verifyNoInteractions(conversationServiceClient);
        }

        @Test
        @DisplayName("Should NOT save to conversation on spawn > 0 (re-execution)")
        void shouldNotSaveOnReExecution() {
            setupResponseNode();
            Map<String, Object> triggerData = new HashMap<>();
            triggerData.put("conversationId", "conv-123");
            ExecutionContext ctx = createContext(triggerData, 1, 0); // spawn=1

            Map<String, Object> output = new HashMap<>();
            output.put("message", "Response text");
            NodeExecutionResult result = NodeExecutionResult.success("core:response1", output);

            service.emitNodeComplete(execution, node, result, triggerItem, 0, ctx, null);

            verifyNoInteractions(conversationServiceClient);
        }

        @Test
        @DisplayName("Should NOT save to conversation for split items beyond first (itemIndex > 0)")
        void shouldNotSaveForSplitItemsBeyondFirst() {
            setupResponseNode();
            Map<String, Object> triggerData = new HashMap<>();
            triggerData.put("conversationId", "conv-123");
            ExecutionContext ctx = createContext(triggerData, 0, 2); // itemIndex=2

            Map<String, Object> output = new HashMap<>();
            output.put("message", "Response text");
            NodeExecutionResult result = NodeExecutionResult.success("core:response1", output);

            service.emitNodeComplete(execution, node, result, triggerItem, 2, ctx, null);

            verifyNoInteractions(conversationServiceClient);
        }

        @Test
        @DisplayName("Should NOT save to conversation when message is blank")
        void shouldNotSaveWhenMessageIsBlank() {
            setupResponseNode();
            Map<String, Object> triggerData = new HashMap<>();
            triggerData.put("conversationId", "conv-123");
            ExecutionContext ctx = createContext(triggerData, 0, 0);

            Map<String, Object> output = new HashMap<>();
            output.put("message", "   ");
            NodeExecutionResult result = NodeExecutionResult.success("core:response1", output);

            service.emitNodeComplete(execution, node, result, triggerItem, 0, ctx, null);

            verifyNoInteractions(conversationServiceClient);
        }

        @Test
        @DisplayName("Should NOT save to conversation on failed RESPONSE node")
        void shouldNotSaveOnFailedResponseNode() {
            setupResponseNode();
            Map<String, Object> triggerData = new HashMap<>();
            triggerData.put("conversationId", "conv-123");
            ExecutionContext ctx = createContext(triggerData, 0, 0);

            NodeExecutionResult result = NodeExecutionResult.failure("core:response1", "Template error");

            service.emitNodeComplete(execution, node, result, triggerItem, 0, ctx, null);

            verifyNoInteractions(conversationServiceClient);
        }

        @Test
        @DisplayName("Should not fail workflow when conversation save throws exception")
        void shouldNotFailWorkflowOnConversationError() {
            setupResponseNode();
            Map<String, Object> triggerData = new HashMap<>();
            triggerData.put("conversationId", "conv-123");
            ExecutionContext ctx = createContext(triggerData, 0, 0);

            Map<String, Object> output = new HashMap<>();
            output.put("message", "Hello");
            NodeExecutionResult result = NodeExecutionResult.success("core:response1", output);

            doThrow(new RuntimeException("Connection refused"))
                .when(conversationServiceClient).saveMessage(any(), any(), any(), any(), any(), any());

            // Should not throw - the exception is swallowed
            assertDoesNotThrow(() ->
                service.emitNodeComplete(execution, node, result, triggerItem, 0, ctx, null)
            );
        }
    }

    @Nested
    @DisplayName("emitNodeSkippedForItem() - per-item SKIPPED with epoch+triggerId")
    class EmitNodeSkippedForItemTests {

        @Test
        @DisplayName("Regression: 6-arg overload propagates real (epoch, triggerId) to orchestrator instead of (0, default)")
        void epochAndTriggerIdAreForwardedToOrchestrator() {
            // Given - split successor skipped for item 5 in epoch 4 of trigger:cron.
            // Pre-fix: the legacy 4-arg form bucketed every per-item SKIPPED row under
            // (epoch=0, triggerId="trigger:default") in workflow_epochs, so the per-epoch
            // UI view rendered statusCounts=null for split successors (classify, mcp:apply_*,
            // table:record_*, exit, …).
            when(node.getNodeId()).thenReturn("mcp:apply_tech");

            // When - caller passes the real DAG coordinates
            service.emitNodeSkippedForItem(
                execution, node, 5, "Not routed to this branch", 4, "trigger:cron");

            // Then - orchestrator receives the same (epoch, triggerId) it must use
            // for both the workflow_step_data row and the workflow_epochs counter.
            verify(stepCompletionOrchestrator).completeSkippedStepWithoutStateUpdate(
                eq(execution),
                eq("mcp:apply_tech"),
                anyString(),
                eq("Not routed to this branch"),
                eq("mcp:apply_tech"),
                eq(5),
                eq(4),
                eq("trigger:cron"));
        }

        @Test
        @DisplayName("Legacy 4-arg overload still delegates with epoch=0 and triggerId=null (back-compat)")
        void legacyOverloadKeepsDefaultBucket() {
            when(node.getNodeId()).thenReturn("mcp:apply_tech");

            service.emitNodeSkippedForItem(execution, node, 2, "Routed elsewhere");

            verify(stepCompletionOrchestrator).completeSkippedStepWithoutStateUpdate(
                eq(execution),
                eq("mcp:apply_tech"),
                anyString(),
                eq("Routed elsewhere"),
                eq("mcp:apply_tech"),
                eq(2),
                eq(0),
                isNull());
        }

        @Test
        @DisplayName("Returns silently when execution is null (no NPE, no orchestrator call)")
        void nullExecutionIsSafe() {
            service.emitNodeSkippedForItem(null, node, 0, "any", 1, "trigger:x");

            verifyNoInteractions(stepCompletionOrchestrator);
        }
    }
}
