package com.apimarketplace.orchestrator.execution.v2.split;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.workflow.NodePolicy;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.TriggerItem;
import com.apimarketplace.orchestrator.execution.v2.nodes.BaseNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeType;
import com.apimarketplace.orchestrator.execution.v2.services.NodeCompletionService;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code nodePolicy.executeOnce} under the split fan-out + the per-item timeout
 * interplay - both applied GENERICALLY by {@link SplitAwareNodeExecutor} (zero
 * per-node-type logic):
 *
 * <ul>
 *   <li>fan-out: only split item 0 executes; the others are persisted SKIPPED with
 *       the explicit executeOnce reason through the SAME pipeline as branch-unrouted
 *       items (rows + batched counts), so downstream merge/aggregate readiness holds;</li>
 *   <li>strict index-0 rule when routing sends item 0 elsewhere;</li>
 *   <li>chained nodes inside per-item traversals (auto mode) skip-with-cascade for
 *       items != 0;</li>
 *   <li>outside a split: NO-OP;</li>
 *   <li>timeoutMs bounds each ITEM's attempt, never the fan-out summary.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SplitAwareNodeExecutor - executeOnce policy + per-item timeout")
@Timeout(60)
class SplitAwareNodeExecutorExecuteOncePolicyTest {

    private static final String RUN_ID = "run1";
    private static final String NODE_ID = "mcp:step1";
    private static final String SPLIT_KEY = "core:split1";

    @Mock private SplitContextManager contextManager;
    @Mock private NodeCompletionService nodeCompletionService;
    @Mock private WorkflowStepDataRepository stepDataRepository;
    @Mock private ExecutionContext context;
    @Mock private WorkflowExecution execution;

    private SplitAwareNodeExecutor executor;
    private Map<String, ExecutionNode> nodeMap;

    /** Released after each test so abandoned (timed-out) item bodies exit. */
    private final CountDownLatch blockForever = new CountDownLatch(1);

    @BeforeEach
    void setUp() {
        executor = new SplitAwareNodeExecutor(
            contextManager,
            nodeCompletionService,
            null,  // EdgeStatusEmitter - not needed
            null,  // SnapshotService - not needed
            stepDataRepository,
            null,  // StateSnapshotService - not needed
            Executors.newFixedThreadPool(2)
        );
        nodeMap = new HashMap<>();
        nodeMap.put(SPLIT_KEY, new TestNode(SPLIT_KEY, NodeType.SPLIT));
    }

    @AfterEach
    void tearDown() {
        blockForever.countDown();
        executor.shutdown();
    }

    /** Plan whose ONLY policy is on {@code mcp:step1}. */
    private WorkflowPlan planWithPolicy(NodePolicy policy) {
        return new WorkflowPlan("11111111-1111-1111-1111-111111111111", "tenant-1",
            null, null, null, null, null, null, null, null,
            Map.of(NODE_ID, policy), Map.of());
    }

    private static NodePolicy executeOncePolicy() {
        return new NodePolicy(0, 0L, false, 0L, true);
    }

    private SplitContext stubSplitOfThreeItems() {
        SplitContext splitContext = SplitContext.create(SPLIT_KEY + ":0", List.of("a", "b", "c"));
        when(contextManager.findActiveContext(eq(RUN_ID), eq(NODE_ID), eq(0), any()))
            .thenReturn(Optional.of(splitContext));
        lenient().when(context.withGlobalData(any(), any())).thenReturn(context);
        lenient().when(context.withItemIndex(anyInt())).thenReturn(context);
        lenient().when(context.state())
            .thenReturn(com.apimarketplace.orchestrator.execution.v2.state.ExecutionState.create());
        return splitContext;
    }

    /** Reads the per-branch current_index injected by enrichContextWithItem. */
    @SuppressWarnings("unchecked")
    private static int currentIndexOf(ExecutionContext ctx) {
        Map<String, Object> wrapper = (Map<String, Object>) ctx.getAllStepOutputs().get(SPLIT_KEY);
        Map<String, Object> output = (Map<String, Object>) wrapper.get("output");
        return (Integer) output.get(ExecutionMetadataKeys.CURRENT_INDEX);
    }

    // =====================================================================
    // executeOnce - split fan-out
    // =====================================================================

    @Test
    @DisplayName("executeOnce in a split fan-out: ONLY item 0 executes; items 1..N-1 get SKIPPED rows with the executeOnce reason + ONE batched count emit; summary COMPLETED")
    void executeOnceRunsItemZeroOnlyAndSkipsTheRest() {
        stubSplitOfThreeItems();
        when(context.plan()).thenReturn(planWithPolicy(executeOncePolicy()));

        TestNode node = new TestNode(NODE_ID, NodeType.MCP);
        node.setPredecessors(List.of(SPLIT_KEY));
        List<Integer> executedItems = new CopyOnWriteArrayList<>();
        node.setDynamicResult(ctx -> {
            executedItems.add(currentIndexOf(ctx));
            return NodeExecutionResult.success(NODE_ID, Map.of("ok", true));
        });
        nodeMap.put(NODE_ID, node);

        NodeExecutionResult result = executor.execute(node, context, RUN_ID, nodeMap,
            execution, new TriggerItem("item-1", 0, Map.of()), 0, null);

        // Item 0 only - the node body never ran for items 1 and 2
        assertThat(executedItems).containsExactly(0);
        assertThat(node.getExecuteCount()).isEqualTo(1);
        assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);

        // Items 1 and 2: per-item SKIPPED rows with the EXPLICIT executeOnce reason
        // (NOT "Not routed to this branch")
        verify(nodeCompletionService).emitNodeSkippedForItem(
            eq(execution), eq(node), eq(1), eq(SplitAwareNodeExecutor.EXECUTE_ONCE_SKIP_REASON), anyInt(), any());
        verify(nodeCompletionService).emitNodeSkippedForItem(
            eq(execution), eq(node), eq(2), eq(SplitAwareNodeExecutor.EXECUTE_ONCE_SKIP_REASON), anyInt(), any());
        verify(nodeCompletionService, never()).emitNodeSkippedForItem(
            eq(execution), eq(node), eq(0), anyString(), anyInt(), any());

        // Counts coherent: ONE batched increment+emit for the 2 suppressed items -
        // the same pipeline merge/aggregate readiness consumes downstream.
        verify(nodeCompletionService).batchIncrementSkippedCountsAndEmit(
            eq(execution), eq(NODE_ID), anyString(), eq(2), anyInt(), any());

        // Item 0's terminal result went through the normal completion pipeline
        verify(nodeCompletionService).emitNodeComplete(
            eq(execution), eq(node), any(), any(), eq(0), any());
    }

    @Test
    @DisplayName("executeOnce: successor traversal launches for item 0 ONLY (downstream inherits the item-0 subset; merge fires on resolved SKIPPED siblings)")
    void executeOnceTraversesOnlyItemZero() {
        stubSplitOfThreeItems();
        when(context.plan()).thenReturn(planWithPolicy(executeOncePolicy()));

        TestNode successor = new TestNode("mcp:after", NodeType.MCP);
        TestNode node = new TestNode(NODE_ID, NodeType.MCP);
        node.setPredecessors(List.of(SPLIT_KEY));
        node.addSuccessor(successor);
        node.setDynamicResult(ctx -> NodeExecutionResult.success(NODE_ID, Map.of("ok", true)));
        nodeMap.put(NODE_ID, node);

        List<Integer> traversedItems = new CopyOnWriteArrayList<>();
        SplitAwareNodeExecutor.SuccessorTraverser traverser = (succ, ctx, subItemIndex) -> {
            traversedItems.add(subItemIndex);
            return ctx;
        };

        NodeExecutionResult result = executor.execute(node, context, RUN_ID, nodeMap,
            execution, new TriggerItem("item-1", 0, Map.of()), 0, traverser);

        assertThat(traversedItems).containsExactly(0);
        assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
        // Suppressed items still got their SKIPPED rows - convergence stays coherent
        verify(nodeCompletionService).emitNodeSkippedForItem(
            eq(execution), eq(node), eq(1), eq(SplitAwareNodeExecutor.EXECUTE_ONCE_SKIP_REASON), anyInt(), any());
        verify(nodeCompletionService).emitNodeSkippedForItem(
            eq(execution), eq(node), eq(2), eq(SplitAwareNodeExecutor.EXECUTE_ONCE_SKIP_REASON), anyInt(), any());
    }

    @Test
    @DisplayName("executeOnce × branch routing (mixed): routed={0,1} → item 0 executes, item 1 skipped with the executeOnce reason, item 2 skipped with the unrouted reason (two disjoint sets, two honest reasons)")
    void executeOnceMixedWithRoutingUsesBothReasons() {
        stubSplitOfThreeItems();
        when(context.plan()).thenReturn(planWithPolicy(executeOncePolicy()));

        TestNode node = new TestNode(NODE_ID, NodeType.MCP);
        // Port predecessor → branch routing path queries the repository
        node.setPredecessors(List.of("agent:classify:category_x"));
        List<Integer> executedItems = new CopyOnWriteArrayList<>();
        node.setDynamicResult(ctx -> {
            executedItems.add(currentIndexOf(ctx));
            return NodeExecutionResult.success(NODE_ID, Map.of("ok", true));
        });
        nodeMap.put(NODE_ID, node);

        when(stepDataRepository.findItemIndicesBySelectedBranchAndEpoch(
            eq(RUN_ID), eq("agent:classify"), eq("category_x"), anyInt()))
            .thenReturn(List.of(0, 1));

        executor.execute(node, context, RUN_ID, nodeMap,
            execution, new TriggerItem("item-1", 0, Map.of()), 0, null);

        assertThat(executedItems).containsExactly(0);
        // Item 1: routed here but suppressed by executeOnce
        verify(nodeCompletionService).emitNodeSkippedForItem(
            eq(execution), eq(node), eq(1), eq(SplitAwareNodeExecutor.EXECUTE_ONCE_SKIP_REASON), anyInt(), any());
        // Item 2: never routed here - keeps today's reason
        verify(nodeCompletionService).emitNodeSkippedForItem(
            eq(execution), eq(node), eq(2), eq("Not routed to this branch"), anyInt(), any());
        // Two disjoint batched increments of 1 each (unrouted + suppressed)
        verify(nodeCompletionService, org.mockito.Mockito.times(2)).batchIncrementSkippedCountsAndEmit(
            eq(execution), eq(NODE_ID), anyString(), eq(1), anyInt(), any());
    }

    @Test
    @DisplayName("executeOnce strict index-0 rule: when routing sends item 0 to another branch the node executes for NO item and is SKIPPED with an executeOnce reason")
    void executeOnceWithoutItemZeroExecutesNothing() {
        stubSplitOfThreeItems();
        when(context.plan()).thenReturn(planWithPolicy(executeOncePolicy()));

        TestNode node = new TestNode(NODE_ID, NodeType.MCP);
        node.setPredecessors(List.of("agent:classify:category_x"));
        node.setDynamicResult(ctx -> NodeExecutionResult.success(NODE_ID, Map.of("ok", true)));
        nodeMap.put(NODE_ID, node);

        // Items 1 and 2 routed here - item 0 went to a sibling branch
        when(stepDataRepository.findItemIndicesBySelectedBranchAndEpoch(
            eq(RUN_ID), eq("agent:classify"), eq("category_x"), anyInt()))
            .thenReturn(List.of(1, 2));

        NodeExecutionResult result = executor.execute(node, context, RUN_ID, nodeMap,
            execution, new TriggerItem("item-1", 0, Map.of()), 0, null);

        assertThat(node.getExecuteCount()).isZero();
        assertThat(result.status()).isEqualTo(NodeStatus.SKIPPED);
        assertThat(result.errorMessage().orElse("")).contains("executeOnce");
        // The suppressed items got rows with the executeOnce reason (choice-branch target path)
        verify(nodeCompletionService).emitNodeSkippedForItem(
            eq(execution), eq(node), eq(1), eq(SplitAwareNodeExecutor.EXECUTE_ONCE_SKIP_REASON), anyInt(), any());
        verify(nodeCompletionService).emitNodeSkippedForItem(
            eq(execution), eq(node), eq(2), eq(SplitAwareNodeExecutor.EXECUTE_ONCE_SKIP_REASON), anyInt(), any());
    }

    // =====================================================================
    // executeOnce - chained node inside per-item split traversals (auto mode)
    // =====================================================================

    @Test
    @DisplayName("executeOnce on a CHAINED node (auto mode): the item-2 traversal terminates with a cascading SKIPPED carrying the executeOnce reason")
    void executeOnceChainedNodeSkipsNonZeroItems() {
        stubSplitOfThreeItems();
        when(context.plan()).thenReturn(planWithPolicy(executeOncePolicy()));
        when(context.itemIndex()).thenReturn(2);

        TestNode node = new TestNode(NODE_ID, NodeType.MCP);
        node.setPredecessors(List.of("mcp:previous")); // NOT a direct split successor
        nodeMap.put(NODE_ID, node);

        SplitAwareNodeExecutor.SuccessorTraverser traverser = (succ, ctx, subItemIndex) -> ctx;

        NodeExecutionResult result = executor.execute(node, context, RUN_ID, nodeMap,
            execution, new TriggerItem("item-1", 0, Map.of()), 0, traverser);

        assertThat(node.getExecuteCount()).as("node body never runs for item 2").isZero();
        assertThat(result.status()).isEqualTo(NodeStatus.SKIPPED);
        assertThat(result.errorMessage().orElse(""))
            .isEqualTo(SplitAwareNodeExecutor.EXECUTE_ONCE_SKIP_REASON);
        // Cascading terminal skip - descendants get per-item SKIPPED, merges stay resolvable
        assertThat(result.metadata())
            .containsEntry(ExecutionMetadataKeys.CASCADE_SKIP_TO_SUCCESSORS, Boolean.TRUE);
    }

    @Test
    @DisplayName("executeOnce on a CHAINED node (auto mode): the item-0 traversal executes normally")
    void executeOnceChainedNodeRunsItemZero() {
        stubSplitOfThreeItems();
        when(context.plan()).thenReturn(planWithPolicy(executeOncePolicy()));
        when(context.itemIndex()).thenReturn(0);

        TestNode node = new TestNode(NODE_ID, NodeType.MCP);
        node.setPredecessors(List.of("mcp:previous"));
        node.setDynamicResult(ctx -> NodeExecutionResult.success(NODE_ID, Map.of("ok", true)));
        nodeMap.put(NODE_ID, node);

        SplitAwareNodeExecutor.SuccessorTraverser traverser = (succ, ctx, subItemIndex) -> ctx;

        NodeExecutionResult result = executor.execute(node, context, RUN_ID, nodeMap,
            execution, new TriggerItem("item-1", 0, Map.of()), 0, traverser);

        assertThat(node.getExecuteCount()).isEqualTo(1);
        assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
    }

    // =====================================================================
    // executeOnce - NO-OP outside a split context
    // =====================================================================

    @Test
    @DisplayName("REGRESSION (no-op outside split): executeOnce never suppresses execution without a split context - even with a non-zero workflow item index")
    void executeOnceIsNoOpOutsideSplit() {
        when(contextManager.findActiveContext(eq(RUN_ID), eq(NODE_ID), anyInt(), any()))
            .thenReturn(Optional.empty());
        lenient().when(contextManager.getAllContexts(RUN_ID)).thenReturn(Map.of());
        when(context.plan()).thenReturn(planWithPolicy(executeOncePolicy()));
        // Outside a split, itemIndex is the WORKFLOW item index - must NOT be filtered
        lenient().when(context.itemIndex()).thenReturn(3);

        TestNode node = new TestNode(NODE_ID, NodeType.MCP);
        node.setPredecessors(List.of("mcp:previous"));
        NodeExecutionResult original = NodeExecutionResult.success(NODE_ID, Map.of("ok", true));
        node.setDynamicResult(ctx -> original);
        nodeMap.put(NODE_ID, node);

        NodeExecutionResult result = executor.execute(node, context, RUN_ID, nodeMap, execution,
            new TriggerItem("item-1", 3, Map.of()), 3, null);

        assertThat(node.getExecuteCount()).isEqualTo(1);
        assertThat(result).isSameAs(original);
        verifyNoInteractions(nodeCompletionService);
    }

    // =====================================================================
    // timeoutMs - per ITEM inside the fan-out, never the summary
    // =====================================================================

    @Test
    @DisplayName("timeoutMs under a split applies PER ITEM: the hanging item times out (policy_timeout FAILED), sibling items complete, the summary stays a partial-failure COMPLETED (never bounded itself)")
    void timeoutAppliesPerItemNotToSummary() {
        stubSplitOfThreeItems();
        when(context.plan()).thenReturn(planWithPolicy(new NodePolicy(0, 0L, false, 150L, false)));

        TestNode node = new TestNode(NODE_ID, NodeType.MCP);
        node.setPredecessors(List.of(SPLIT_KEY));
        node.setDynamicResult(ctx -> {
            int idx = currentIndexOf(ctx);
            if (idx == 1) {
                try {
                    blockForever.await(); // latch-controlled hang - released in tearDown
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
            return NodeExecutionResult.success(NODE_ID, Map.of("item", idx));
        });
        nodeMap.put(NODE_ID, node);

        NodeExecutionResult result = executor.execute(node, context, RUN_ID, nodeMap);

        // The summary is a partial failure (items 0 and 2 succeeded, item 1 timed out)
        // and is COMPLETED - the fan-out itself was NOT killed by the 150ms bound even
        // though the overall fan-out waited on the timed-out item.
        assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
        assertThat(result.output()).containsEntry(ExecutionMetadataKeys.SPLIT_PARTIAL_FAILURE, true);
        assertThat(result.output().get(ExecutionMetadataKeys.SPLIT_FAILED_ITEM_INDICES))
            .isEqualTo(List.of(1));

        @SuppressWarnings("unchecked")
        List<String> errors = (List<String>) result.output().get(ExecutionMetadataKeys.SPLIT_ERRORS);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("Item 1").contains("TIMEOUT");
    }

    // =====================================================================
    // Test node (mirrors SplitAwareNodeExecutorNodePolicyTest.TestNode)
    // =====================================================================

    private static class TestNode extends BaseNode {
        private java.util.function.Function<ExecutionContext, NodeExecutionResult> dynamicResult;
        private final java.util.concurrent.atomic.AtomicInteger executeCount =
            new java.util.concurrent.atomic.AtomicInteger(0);

        TestNode(String nodeId, NodeType type) {
            super(nodeId, type);
        }

        void setDynamicResult(java.util.function.Function<ExecutionContext, NodeExecutionResult> fn) {
            this.dynamicResult = fn;
        }

        int getExecuteCount() {
            return executeCount.get();
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
