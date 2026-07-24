package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.NodePolicy;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.lifecycle.V2WorkflowFinalizer;
import com.apimarketplace.orchestrator.execution.v2.nodes.BaseNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeType;
import com.apimarketplace.orchestrator.execution.v2.scheduler.V2AutoScheduler;
import com.apimarketplace.orchestrator.execution.v2.scheduler.V2StepByStepScheduler;
import com.apimarketplace.orchestrator.execution.v2.services.NodeSearchService;
import com.apimarketplace.orchestrator.execution.v2.services.ReadyNodeCalculator;
import com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService;
import com.apimarketplace.orchestrator.execution.v2.services.V2SkipPropagationService;
import com.apimarketplace.orchestrator.execution.v2.split.SplitAggregateHandler;
import com.apimarketplace.orchestrator.execution.v2.split.SplitAwareNodeExecutor;
import com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager;
import com.apimarketplace.orchestrator.execution.v2.split.SplitMergeHandler;
import com.apimarketplace.orchestrator.execution.v2.split.SplitNodeExecutor;
import com.apimarketplace.orchestrator.services.completion.StepCompletionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Engine-level tests for tier 2 of the payload-loss contract: a node whose
 * SUCCESS result could not be durably stored (persistence reported
 * {@code payloadLost}, row flipped to FAILED) must cascade through the
 * traversal EXACTLY like a failure - success-path successors do not run,
 * descendants are skip-cascaded, and NodePolicy retry does NOT re-execute the
 * node (the policy acts on the node's own result, not on storage hiccups).
 *
 * <p>Same harness as {@link UnifiedExecutionEngineNodePolicyTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UnifiedExecutionEngine - payload-lost completion cascades like a failure")
class UnifiedExecutionEnginePayloadLostTest {

    private static final String LOSS_MESSAGE =
            "[storage] Output payload lost: storage write failed after retries";

    @Mock private V2WorkflowFinalizer workflowFinalizer;
    @Mock private V2AutoScheduler autoScheduler;
    @Mock private V2StepByStepScheduler stepByStepScheduler;
    @Mock private ReadyNodeCalculator readyNodeCalculator;
    @Mock private BackEdgeHandler backEdgeHandler;
    @Mock private SplitNodeExecutor splitNodeExecutor;
    @Mock private SplitAwareNodeExecutor splitAwareExecutor;
    @Mock private SplitMergeHandler splitMergeHandler;
    @Mock private SplitAggregateHandler splitAggregateHandler;
    @Mock private SplitContextManager splitContextManager;
    @Mock private NodeSearchService nodeSearchService;
    @Mock private V2SkipPropagationService skipPropagationService;
    @Mock private V2ExecutionEventService eventService;
    @Mock private WorkflowExecution execution;

    private UnifiedExecutionEngine engine;
    private WorkflowPlan plan;

    @BeforeEach
    void setUp() {
        lenient().when(splitAwareExecutor.execute(any(), any(), any(), any(), any(), any(), anyInt(), any()))
            .thenAnswer(invocation -> {
                ExecutionNode node = invocation.getArgument(0);
                ExecutionContext context = invocation.getArgument(1);
                return node.execute(context);
            });

        engine = new UnifiedExecutionEngine(
            workflowFinalizer, autoScheduler, stepByStepScheduler,
            readyNodeCalculator, backEdgeHandler,
            splitNodeExecutor, splitAwareExecutor, splitMergeHandler,
            splitAggregateHandler, splitContextManager, nodeSearchService,
            skipPropagationService,
            null);

        plan = mock(WorkflowPlan.class);
    }

    private ExecutionContext createContext() {
        return ExecutionContext.create(
            "run-payload-lost", "workflow-run-payload-lost", "tenant-1",
            "item-1", 0, Map.of(), plan);
    }

    private TriggerItem item() {
        return new TriggerItem("item-1", 0, Map.of());
    }

    private BaseNode mockNode(String nodeId, NodeType type) {
        BaseNode node = mock(BaseNode.class);
        lenient().when(node.getNodeId()).thenReturn(nodeId);
        lenient().when(node.getType()).thenReturn(type);
        lenient().when(node.canExecute(any())).thenReturn(true);
        lenient().when(node.getSuccessors()).thenReturn(List.of());
        lenient().when(node.getPredecessorIds()).thenReturn(List.of());
        lenient().when(node.getNextNodes(any())).thenReturn(List.of());
        return node;
    }

    private BaseNode successNode(String nodeId) {
        BaseNode node = mockNode(nodeId, NodeType.MCP);
        lenient().when(node.execute(any()))
            .thenReturn(NodeExecutionResult.success(nodeId, Map.of("done", true)));
        return node;
    }

    /** Wire success-path successors: getNextNodes returns them ONLY for a success result. */
    private void wireSuccessSuccessor(BaseNode node, ExecutionNode successor) {
        lenient().when(node.getNextNodes(any())).thenAnswer(inv -> {
            NodeExecutionResult r = inv.getArgument(0);
            return (r != null && r.isSuccess()) ? List.of(successor) : List.of();
        });
        lenient().when(node.getSuccessors()).thenReturn(List.of(successor));
    }

    private void stubPayloadLostCompletion(BaseNode node) {
        when(eventService.emitNodeComplete(eq(execution), eq(node), any(), any(), anyInt(), any()))
            .thenReturn(StepCompletionResult.persistedPayloadLost(Map.of(), Map.of(), LOSS_MESSAGE));
    }

    @Nested
    @DisplayName("Traversal truth")
    class TraversalTruth {

        @Test
        @DisplayName("success-with-payloadLost cascades exactly like a failure: successor NOT executed, skip cascade fires, context records FAILED")
        void payloadLostCascadesLikeFailure() {
            BaseNode node = successNode("mcp:producer");
            BaseNode successor = successNode("mcp:consumer");
            wireSuccessSuccessor(node, successor);
            stubPayloadLostCompletion(node);

            ExecutionContext result = engine.traverseTree(node, createContext(), execution, eventService, item());

            assertThat(result.isFailed("mcp:producer"))
                .as("a step whose output is not durable is NOT COMPLETED")
                .isTrue();
            verify(successor, never()).execute(any());
            verify(skipPropagationService).cascadeFailureToSuccessors(
                eq(execution), eq(node), anyInt(), anyInt(), any(), eq(false),
                eq(V2SkipPropagationService.SOURCE_SYNC));
        }

        @Test
        @DisplayName("the rewritten failure carries the loss message so downstream diagnostics name the cause")
        void rewrittenFailureCarriesLossMessage() {
            BaseNode node = successNode("mcp:producer");
            stubPayloadLostCompletion(node);

            engine.traverseTree(node, createContext(), execution, eventService, item());

            // executeNodeCore hands the REWRITTEN result to the lifecycle callback.
            org.mockito.ArgumentCaptor<NodeExecutionResult> onCompleteResult =
                org.mockito.ArgumentCaptor.forClass(NodeExecutionResult.class);
            verify(node).onComplete(any(), onCompleteResult.capture());
            assertThat(onCompleteResult.getValue().isFailure()).isTrue();
            assertThat(onCompleteResult.getValue().errorMessage()).contains(LOSS_MESSAGE);
        }

        @Test
        @DisplayName("BEHAVIOUR GUARD: a normally persisted success traverses its successor and does NOT cascade")
        void normalSuccessStillTraverses() {
            BaseNode node = successNode("mcp:producer");
            BaseNode successor = successNode("mcp:consumer");
            wireSuccessSuccessor(node, successor);
            when(eventService.emitNodeComplete(eq(execution), eq(node), any(), any(), anyInt(), any()))
                .thenReturn(StepCompletionResult.persisted(Map.of(), Map.of()));

            ExecutionContext result = engine.traverseTree(node, createContext(), execution, eventService, item());

            assertThat(result.isSuccess("mcp:producer")).isTrue();
            verify(successor).execute(any());
            verify(skipPropagationService, never()).cascadeFailureToSuccessors(
                any(), any(), anyInt(), anyInt(), any(), anyBoolean(), any());
        }

        @Test
        @DisplayName("BEHAVIOUR GUARD: a null completion (legacy/duplicate paths) leaves the success untouched")
        void nullCompletionLeavesSuccessUntouched() {
            BaseNode node = successNode("mcp:producer");
            when(eventService.emitNodeComplete(eq(execution), eq(node), any(), any(), anyInt(), any()))
                .thenReturn(null);

            ExecutionContext result = engine.traverseTree(node, createContext(), execution, eventService, item());

            assertThat(result.isSuccess("mcp:producer")).isTrue();
        }
    }

    @Nested
    @DisplayName("NodePolicy interaction")
    class NodePolicyInteraction {

        @Test
        @DisplayName("PIN: NodePolicy retry does NOT re-execute a side-effectful node because storage lost its payload - the node executes exactly ONCE")
        void policyRetryDoesNotReExecuteOnPayloadLoss() {
            AtomicInteger executions = new AtomicInteger();
            BaseNode node = mockNode("mcp:side_effectful", NodeType.MCP);
            when(node.execute(any())).thenAnswer(inv -> {
                executions.incrementAndGet();
                return NodeExecutionResult.success("mcp:side_effectful", Map.of("done", true));
            });
            // A retry policy is DECLARED - it must act on the node's own result
            // (which is a success), never on the post-execution persist failure.
            lenient().when(plan.getNodePolicy("mcp:side_effectful"))
                .thenReturn(new NodePolicy(2, 0L, false));
            stubPayloadLostCompletion(node);

            ExecutionContext result = engine.traverseTree(node, createContext(), execution, eventService, item());

            assertThat(executions.get())
                .as("re-running a side-effectful node because storage hiccuped would "
                        + "duplicate its side effects - the policy wraps EXECUTION only")
                .isEqualTo(1);
            assertThat(result.isFailed("mcp:side_effectful")).isTrue();
            // No retry attempts were emitted - the execution itself never failed.
            verify(eventService, never()).emitNodeAttemptFailed(any(), any(), any(), any(), anyInt(), any());
            // The terminal completion pipeline ran exactly once.
            verify(eventService, times(1)).emitNodeComplete(eq(execution), eq(node), any(), any(), anyInt(), any());
        }
    }

    // =====================================================================
    // STEP_BY_STEP path - the SBS rewrite in executeSingleNode is a SEPARATE
    // code block from executeNodeCore's (both production-wired). This drives
    // the SBS entry point so the SBS branch is isolated, not asserted-by-
    // sharing with the AUTOMATIC path above (which only exercises traverseTree
    // -> executeNodeCore). Mutation-disabling ONLY the executeSingleNode block
    // must fail THESE tests and leave the TraversalTruth tests green.
    // =====================================================================

    private ExecutionTree treeWith(ExecutionNode node) {
        ExecutionTree tree = mock(ExecutionTree.class);
        lenient().when(tree.getRunId()).thenReturn("run-payload-lost");
        lenient().when(tree.getWorkflowRunId()).thenReturn("workflow-run-payload-lost");
        lenient().when(tree.getTenantId()).thenReturn("tenant-1");
        lenient().when(tree.getRootNode()).thenReturn(node);
        lenient().when(tree.getRootNodes()).thenReturn(List.of(node));
        lenient().when(tree.getPlan()).thenReturn(plan);
        return tree;
    }

    @Nested
    @DisplayName("Traversal truth - STEP_BY_STEP (executeSingleNode)")
    class StepByStepTraversalTruth {

        @Test
        @DisplayName("SBS: success-with-payloadLost flips the returned step result to FAILED and fires the skip cascade (successors get cascaded, not readied)")
        void sbsPayloadLostCascadesLikeFailure() {
            BaseNode node = successNode("mcp:producer");
            BaseNode successor = successNode("mcp:consumer");
            wireSuccessSuccessor(node, successor);
            ExecutionTree tree = treeWith(node);
            when(nodeSearchService.findNodeFromAllRoots(tree, "mcp:producer")).thenReturn(node);
            lenient().when(nodeSearchService.buildNodeMapFromAllRoots(tree)).thenReturn(Map.of("mcp:producer", node));
            when(readyNodeCalculator.calculateReadyNodes(any(), any())).thenReturn(java.util.Set.of());
            stubPayloadLostCompletion(node);

            StepByStepExecutionResult result = engine.executeSingleNode(
                "mcp:producer", tree, createContext(), execution, eventService, item());

            // The step the SBS caller reads back is FAILED (row + snapshot already say so).
            assertThat(result.nodeResult().status())
                .as("SBS: a step whose output is not durable is NOT COMPLETED")
                .isEqualTo(com.apimarketplace.orchestrator.domain.execution.NodeStatus.FAILED);
            // Successors are skip-cascaded (not offered as ready / not run) - identical
            // to a genuine failure in SBS mode.
            verify(skipPropagationService).cascadeFailureToSuccessors(
                eq(execution), eq(node), anyInt(), anyInt(), any(), eq(false),
                eq(V2SkipPropagationService.SOURCE_SYNC));
            verify(successor, never()).execute(any());
        }

        @Test
        @DisplayName("SBS BEHAVIOUR GUARD: a normally persisted success stays COMPLETED and does NOT cascade")
        void sbsNormalSuccessStaysCompleted() {
            BaseNode node = successNode("mcp:producer");
            BaseNode successor = successNode("mcp:consumer");
            wireSuccessSuccessor(node, successor);
            ExecutionTree tree = treeWith(node);
            when(nodeSearchService.findNodeFromAllRoots(tree, "mcp:producer")).thenReturn(node);
            lenient().when(nodeSearchService.buildNodeMapFromAllRoots(tree)).thenReturn(Map.of("mcp:producer", node));
            when(readyNodeCalculator.calculateReadyNodes(any(), any())).thenReturn(java.util.Set.of("mcp:consumer"));
            when(eventService.emitNodeComplete(eq(execution), eq(node), any(), any(), anyInt(), any()))
                .thenReturn(StepCompletionResult.persisted(Map.of(), Map.of()));

            StepByStepExecutionResult result = engine.executeSingleNode(
                "mcp:producer", tree, createContext(), execution, eventService, item());

            assertThat(result.nodeResult().status())
                .isEqualTo(com.apimarketplace.orchestrator.domain.execution.NodeStatus.COMPLETED);
            verify(skipPropagationService, never()).cascadeFailureToSuccessors(
                any(), any(), anyInt(), anyInt(), any(), anyBoolean(), any());
        }

        @Test
        @DisplayName("SBS BEHAVIOUR GUARD: a null completion (legacy/duplicate) leaves the success untouched")
        void sbsNullCompletionLeavesSuccessUntouched() {
            BaseNode node = successNode("mcp:producer");
            ExecutionTree tree = treeWith(node);
            when(nodeSearchService.findNodeFromAllRoots(tree, "mcp:producer")).thenReturn(node);
            lenient().when(nodeSearchService.buildNodeMapFromAllRoots(tree)).thenReturn(Map.of("mcp:producer", node));
            when(readyNodeCalculator.calculateReadyNodes(any(), any())).thenReturn(java.util.Set.of());
            when(eventService.emitNodeComplete(eq(execution), eq(node), any(), any(), anyInt(), any()))
                .thenReturn(null);

            StepByStepExecutionResult result = engine.executeSingleNode(
                "mcp:producer", tree, createContext(), execution, eventService, item());

            assertThat(result.nodeResult().status())
                .isEqualTo(com.apimarketplace.orchestrator.domain.execution.NodeStatus.COMPLETED);
        }
    }
}
