package com.apimarketplace.orchestrator.execution.v2.split;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.TriggerItem;
import com.apimarketplace.orchestrator.execution.v2.nodes.BaseNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeType;
import com.apimarketplace.orchestrator.execution.v2.services.NodeCompletionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The node-level mark of a split fan-out on the STEP_BY_STEP path (no successor traverser: the path
 * production runs take, where the ready-loop dispatches the next nodes from a context rebuilt out of
 * the snapshot).
 *
 * <p>REGRESSION 2026-09-29 (found by the production-runs e2e): each item used to write the node-level
 * EpochState mark itself, so a fan-out with one failed item left the node in BOTH completedNodeIds
 * and failedNodeIds. The rebuild read FAILED, and the default continue-anyway split then sent NO
 * item to the next node, the successful ones included, while the epoch still closed COMPLETED. Every
 * item is now persisted per item and the node-level mark is written ONCE at the end, from the rows
 * ({@code recordSplitAggregate}). The AUTOMATIC traversal (a traverser is present), a fan-out with a
 * pending item (signal, async queue) and the per-item continuation walk keep their own paths.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SplitAwareNodeExecutor - node-level mark of a step-by-step fan-out")
class SplitAwareNodeExecutorStepByStepAggregateTest {

    private static final String RUN_ID = "run1";
    private static final String NODE_ID = "mcp:step1";
    private static final String SPLIT_KEY = "core:split1";
    private static final String TRIGGER_ID = "trigger:start";
    private static final int EPOCH = 4;

    @Mock private SplitContextManager contextManager;
    @Mock private NodeCompletionService nodeCompletionService;
    @Mock private ExecutionContext context;
    @Mock private WorkflowExecution execution;

    private SplitAwareNodeExecutor executor;
    private Map<String, ExecutionNode> nodeMap;

    @BeforeEach
    void setUp() {
        executor = new SplitAwareNodeExecutor(
            contextManager, nodeCompletionService, null, null, null, null, Executors.newFixedThreadPool(2));
        nodeMap = new HashMap<>();
        nodeMap.put(SPLIT_KEY, new TestNode(SPLIT_KEY, NodeType.SPLIT));

        SplitContext splitContext = SplitContext.create(SPLIT_KEY + ":0", List.of("a", "b", "c"));
        when(contextManager.findActiveContext(eq(RUN_ID), eq(NODE_ID), eq(0), any()))
            .thenReturn(Optional.of(splitContext));
        when(context.withGlobalData(any(), any())).thenReturn(context);
        when(context.withItemIndex(anyInt())).thenReturn(context);
        when(context.state()).thenReturn(com.apimarketplace.orchestrator.execution.v2.state.ExecutionState.create());
        when(context.triggerId()).thenReturn(TRIGGER_ID);
        when(context.epoch()).thenReturn(EPOCH);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    /** Reads the per-branch current_index injected by enrichContextWithItem. */
    @SuppressWarnings("unchecked")
    private static int currentIndexOf(ExecutionContext ctx) {
        Map<String, Object> wrapper = (Map<String, Object>) ctx.getAllStepOutputs().get(SPLIT_KEY);
        Map<String, Object> output = (Map<String, Object>) wrapper.get("output");
        return (Integer) output.get(ExecutionMetadataKeys.CURRENT_INDEX);
    }

    private TestNode node(Function<Integer, NodeExecutionResult> byItem) {
        TestNode node = new TestNode(NODE_ID, NodeType.MCP);
        node.setPredecessors(List.of(SPLIT_KEY));
        node.setDynamicResult(ctx -> byItem.apply(currentIndexOf(ctx)));
        nodeMap.put(NODE_ID, node);
        return node;
    }

    /** Items [ok, fail, ok]. */
    private TestNode nodeFailingItemOne() {
        return node(idx -> idx == 1
            ? NodeExecutionResult.failure(NODE_ID, "item down")
            : NodeExecutionResult.success(NODE_ID, Map.of("item", idx)));
    }

    @Test
    @DisplayName("REGRESSION: step-by-step fan-out [ok, fail, ok] persists every item per item and writes the node-level mark ONCE, for this trigger and epoch")
    void stepByStepFanOutAggregatesOnce() {
        TestNode node = nodeFailingItemOne();

        NodeExecutionResult summary = executor.execute(node, context, RUN_ID, nodeMap,
            execution, new TriggerItem("item-1", 0, Map.of()), 0, null);

        // Per item, WITHOUT the node-level mark: one item can no longer mark the node FAILED
        // while its siblings mark it COMPLETED.
        verify(nodeCompletionService, times(3)).emitNodeCompletePerItem(
            eq(execution), eq(node), any(NodeExecutionResult.class), any(), anyInt(), any());
        verify(nodeCompletionService).emitNodeCompletePerItem(
            eq(execution), eq(node), any(NodeExecutionResult.class), any(), eq(1), any());
        verify(nodeCompletionService, never()).emitNodeComplete(
            any(), any(), any(), any(), anyInt(), any());
        // The node-level mark, once, from THIS fan-out's outcome (2 completed, 1 failed): the rows
        // of the epoch would also count a previous spawn's items (a rerun).
        verify(nodeCompletionService, times(1)).recordSplitOutcome(RUN_ID, TRIGGER_ID, NODE_ID, EPOCH, 2L, 1L);
        // The summary still reports the partial failure (continue-anyway split).
        assertThat(summary.status()).isEqualTo(NodeStatus.COMPLETED);
        assertThat(summary.output()).containsEntry(ExecutionMetadataKeys.SPLIT_PARTIAL_FAILURE, true);
    }

    @Test
    @DisplayName("step-by-step fan-out where every item fails: same single aggregate (the rows then say FAILED)")
    void stepByStepAllFailedAggregatesOnce() {
        TestNode node = node(idx -> NodeExecutionResult.failure(NODE_ID, "item down"));

        NodeExecutionResult summary = executor.execute(node, context, RUN_ID, nodeMap,
            execution, new TriggerItem("item-1", 0, Map.of()), 0, null);

        assertThat(summary.status()).isEqualTo(NodeStatus.FAILED);
        verify(nodeCompletionService, times(3)).emitNodeCompletePerItem(
            eq(execution), eq(node), any(NodeExecutionResult.class), any(), anyInt(), any());
        verify(nodeCompletionService, never()).emitNodeComplete(
            any(), any(), any(), any(), anyInt(), any());
        verify(nodeCompletionService, times(1)).recordSplitOutcome(RUN_ID, TRIGGER_ID, NODE_ID, EPOCH, 0L, 3L);
    }

    @Test
    @DisplayName("a fan-out whose items ALL come back SKIPPED keeps the per-item marks, which resolve the node: no outcome to aggregate")
    void allSkippedKeepsPerItemMarks() {
        TestNode node = node(idx -> NodeExecutionResult.skipped(NODE_ID, "not for this item"));

        executor.execute(node, context, RUN_ID, nodeMap,
            execution, new TriggerItem("item-1", 0, Map.of()), 0, null);

        verify(nodeCompletionService, times(3)).emitNodeComplete(
            eq(execution), eq(node), any(NodeExecutionResult.class), any(), anyInt(), any());
        verify(nodeCompletionService, never()).emitNodeCompletePerItem(
            any(), any(), any(), any(), anyInt(), any());
        verify(nodeCompletionService, never()).recordSplitOutcome(anyString(), any(), anyString(), anyInt(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("REGRESSION: a failing node-level mark never fails the fan-out, so a policy never re-runs items that already ran")
    void failingMarkDoesNotFailTheFanOut() {
        TestNode node = nodeFailingItemOne();
        org.mockito.Mockito.doThrow(new IllegalStateException("snapshot write refused"))
            .when(nodeCompletionService).recordSplitOutcome(anyString(), any(), anyString(), anyInt(), anyLong(), anyLong());

        NodeExecutionResult summary = executor.execute(node, context, RUN_ID, nodeMap,
            execution, new TriggerItem("item-1", 0, Map.of()), 0, null);

        assertThat(summary.status()).isEqualTo(NodeStatus.COMPLETED);
        assertThat(node.getExecuteCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("AUTOMATIC traversal (a traverser is present): each item keeps the full per-item completion, no aggregate")
    void automaticTraversalKeepsPerItemMarks() {
        TestNode node = nodeFailingItemOne();
        SplitAwareNodeExecutor.SuccessorTraverser traverser = (successor, ctx, subItemIndex) -> ctx;

        executor.execute(node, context, RUN_ID, nodeMap,
            execution, new TriggerItem("item-1", 0, Map.of()), 0, traverser);

        verify(nodeCompletionService, times(3)).emitNodeComplete(
            eq(execution), eq(node), any(NodeExecutionResult.class), any(), anyInt(), any());
        verify(nodeCompletionService, never()).emitNodeCompletePerItem(
            any(), any(), any(), any(), anyInt(), any());
        verify(nodeCompletionService, never()).recordSplitOutcome(anyString(), any(), anyString(), anyInt(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("a fan-out with an item AWAITING_SIGNAL keeps the per-item marks and writes no aggregate: the pending item settles the node through its own path")
    void pendingItemKeepsPerItemMarks() {
        TestNode node = node(idx -> idx == 1
            ? NodeExecutionResult.awaitingSignal(NODE_ID, SignalType.USER_APPROVAL, Map.of())
            : NodeExecutionResult.success(NODE_ID, Map.of("item", idx)));

        executor.execute(node, context, RUN_ID, nodeMap,
            execution, new TriggerItem("item-1", 0, Map.of()), 0, null);

        // The two terminal items go through the full per-item completion, the pending one not at all.
        verify(nodeCompletionService, times(2)).emitNodeComplete(
            eq(execution), eq(node), any(NodeExecutionResult.class), any(), anyInt(), any());
        verify(nodeCompletionService, never()).emitNodeComplete(
            any(), any(), any(), any(), eq(1), any());
        verify(nodeCompletionService, never()).emitNodeCompletePerItem(
            any(), any(), any(), any(), anyInt(), any());
        verify(nodeCompletionService, never()).recordSplitOutcome(anyString(), any(), anyString(), anyInt(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("without an execution to persist into (nothing is written), no aggregate either")
    void nothingPersistedNoAggregate() {
        TestNode node = nodeFailingItemOne();

        executor.execute(node, context, RUN_ID, nodeMap);

        verify(nodeCompletionService, never()).recordSplitOutcome(anyString(), any(), anyString(), anyInt(), anyLong(), anyLong());
        verify(nodeCompletionService, never()).emitNodeCompletePerItem(
            any(), any(), any(), any(), anyInt(), any());
    }

    // =====================================================================
    // Test node (mirrors SplitAwareNodeExecutorTest.TestNode)
    // =====================================================================

    private static class TestNode extends BaseNode {
        private Function<ExecutionContext, NodeExecutionResult> dynamicResult;
        private final java.util.concurrent.atomic.AtomicInteger executeCount = new java.util.concurrent.atomic.AtomicInteger();

        int getExecuteCount() {
            return executeCount.get();
        }

        TestNode(String nodeId, NodeType type) {
            super(nodeId, type);
        }

        void setDynamicResult(Function<ExecutionContext, NodeExecutionResult> fn) {
            this.dynamicResult = fn;
        }

        @Override
        public boolean skipsSplitHandling() {
            return type == NodeType.SPLIT || type == NodeType.MERGE
                || type == NodeType.DECISION || type == NodeType.FORK
                || type == NodeType.LOOP || type == NodeType.TRIGGER
                || type == NodeType.SWITCH || type == NodeType.END;
        }

        @Override
        public boolean isSplitNode() {
            return type == NodeType.SPLIT;
        }

        @Override
        public boolean isMergeNode() {
            return type == NodeType.MERGE;
        }

        @Override
        public NodeExecutionResult execute(ExecutionContext context) {
            executeCount.incrementAndGet();
            if (dynamicResult != null) {
                return dynamicResult.apply(context);
            }
            return NodeExecutionResult.success(nodeId, Map.of());
        }
    }
}
