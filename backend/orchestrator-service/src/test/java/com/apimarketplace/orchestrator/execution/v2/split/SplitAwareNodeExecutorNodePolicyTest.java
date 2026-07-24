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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.atLeastOnce;

/**
 * Phase-2 split-interplay tests: the per-node execution policy applies PER ITEM
 * inside the split fan-out - each item retries independently, failed attempts are
 * persisted through the same per-item pipeline, and continueOnFailure lets a failed
 * item's branch keep traversing while sibling items are untouched.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SplitAwareNodeExecutor - per-item execution policies (Phase 2)")
class SplitAwareNodeExecutorNodePolicyTest {

    private static final String RUN_ID = "run1";
    private static final String NODE_ID = "mcp:step1";
    private static final String SPLIT_KEY = "core:split1";

    @Mock private SplitContextManager contextManager;
    @Mock private NodeCompletionService nodeCompletionService;
    @Mock private ExecutionContext context;
    @Mock private WorkflowExecution execution;

    private SplitAwareNodeExecutor executor;
    private Map<String, ExecutionNode> nodeMap;

    @BeforeEach
    void setUp() {
        executor = new SplitAwareNodeExecutor(
            contextManager,
            nodeCompletionService,
            null,  // EdgeStatusEmitter - not needed
            null,  // SnapshotService - not needed
            null,  // WorkflowStepDataRepository - not needed
            null,  // StateSnapshotService - not needed
            Executors.newFixedThreadPool(2)
        );
        nodeMap = new HashMap<>();
        nodeMap.put(SPLIT_KEY, new TestNode(SPLIT_KEY, NodeType.SPLIT));
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    /** Plan whose ONLY policy is on {@code mcp:step1}. */
    private WorkflowPlan planWithPolicy(NodePolicy policy) {
        return new WorkflowPlan("11111111-1111-1111-1111-111111111111", "tenant-1",
            null, null, null, null, null, null, null, null,
            Map.of(NODE_ID, policy), Map.of());
    }

    private SplitContext stubSplitOfThreeItems() {
        SplitContext splitContext = SplitContext.create(SPLIT_KEY + ":0", List.of("a", "b", "c"));
        when(contextManager.findActiveContext(eq(RUN_ID), eq(NODE_ID), eq(0), any()))
            .thenReturn(Optional.of(splitContext));
        when(context.withGlobalData(any(), any())).thenReturn(context);
        when(context.withItemIndex(anyInt())).thenReturn(context);
        // Real state so per-item contexts can record results (successor traversal path)
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

    @Test
    @DisplayName("retry applies PER ITEM: only the failing item retries, sibling items execute exactly once, summary is COMPLETED")
    void retryAppliesPerItem() {
        stubSplitOfThreeItems();
        when(context.plan()).thenReturn(planWithPolicy(new NodePolicy(1, 0L, false)));

        TestNode node = new TestNode(NODE_ID, NodeType.MCP);
        node.setPredecessors(List.of(SPLIT_KEY));
        Map<Integer, AtomicInteger> attemptsByItem = new ConcurrentHashMap<>();
        node.setDynamicResult(ctx -> {
            int idx = currentIndexOf(ctx);
            int attempt = attemptsByItem.computeIfAbsent(idx, k -> new AtomicInteger()).incrementAndGet();
            // Item 1 fails on its FIRST attempt only; items 0 and 2 always succeed
            if (idx == 1 && attempt == 1) {
                return NodeExecutionResult.failure(NODE_ID, "transient item failure");
            }
            return NodeExecutionResult.success(NODE_ID, Map.of("item", idx));
        });
        nodeMap.put(NODE_ID, node);

        NodeExecutionResult result = executor.execute(node, context, RUN_ID, nodeMap);

        assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
        assertThat(result.output()).doesNotContainKey(ExecutionMetadataKeys.SPLIT_ERRORS);
        assertThat(attemptsByItem.get(0).get()).as("item 0 executes once").isEqualTo(1);
        assertThat(attemptsByItem.get(1).get()).as("item 1 retried once").isEqualTo(2);
        assertThat(attemptsByItem.get(2).get()).as("item 2 executes once").isEqualTo(1);
        assertThat(node.getExecuteCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("REGRESSION (no-policy = byte-identical): without a nodePolicy block a failing item executes once and the summary records the error")
    void defaultPolicyItemFailsOnce() {
        stubSplitOfThreeItems();
        lenient().when(context.plan()).thenReturn(null); // no plan → DEFAULT policy

        TestNode node = new TestNode(NODE_ID, NodeType.MCP);
        node.setPredecessors(List.of(SPLIT_KEY));
        Map<Integer, AtomicInteger> attemptsByItem = new ConcurrentHashMap<>();
        node.setDynamicResult(ctx -> {
            int idx = currentIndexOf(ctx);
            attemptsByItem.computeIfAbsent(idx, k -> new AtomicInteger()).incrementAndGet();
            return idx == 1
                ? NodeExecutionResult.failure(NODE_ID, "down")
                : NodeExecutionResult.success(NODE_ID, Map.of("item", idx));
        });
        nodeMap.put(NODE_ID, node);

        NodeExecutionResult result = executor.execute(node, context, RUN_ID, nodeMap);

        assertThat(attemptsByItem.get(1).get()).as("no retry without a policy").isEqualTo(1);
        assertThat(node.getExecuteCount()).isEqualTo(3);
        // Partial failure summary - existing semantics untouched
        assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
        assertThat(result.output()).containsEntry(ExecutionMetadataKeys.SPLIT_PARTIAL_FAILURE, true);
    }

    @Test
    @DisplayName("no silent attempts: each per-item failed attempt goes through the ATTEMPT pipeline (emitNodeFailedAttempt) with attempt metadata; ONLY the terminal item result goes through emitNodeComplete")
    void failedAttemptPersistedPerItem() {
        stubSplitOfThreeItems();
        when(context.plan()).thenReturn(planWithPolicy(new NodePolicy(1, 0L, false)));

        TestNode node = new TestNode(NODE_ID, NodeType.MCP);
        node.setPredecessors(List.of(SPLIT_KEY));
        Map<Integer, AtomicInteger> attemptsByItem = new ConcurrentHashMap<>();
        node.setDynamicResult(ctx -> {
            int idx = currentIndexOf(ctx);
            int attempt = attemptsByItem.computeIfAbsent(idx, k -> new AtomicInteger()).incrementAndGet();
            if (idx == 1 && attempt == 1) {
                return NodeExecutionResult.failure(NODE_ID, "transient item failure");
            }
            return NodeExecutionResult.success(NODE_ID, Map.of("item", idx));
        });
        nodeMap.put(NODE_ID, node);

        // 8-arg form with execution → canPersist=true (nodeCompletionService wired)
        executor.execute(node, context, RUN_ID, nodeMap,
            execution, new TriggerItem("item-1", 0, Map.of()), 0, null);

        // The intermediate FAILED attempt of item 1 went through the attempt-aware
        // pipeline (WS + row, NO snapshot/edge mutation), annotated attempt 1/2.
        ArgumentCaptor<NodeExecutionResult> attemptCaptor = ArgumentCaptor.forClass(NodeExecutionResult.class);
        verify(nodeCompletionService).emitNodeFailedAttempt(
            eq(execution), eq(node), attemptCaptor.capture(), any(), eq(1), any());
        NodeExecutionResult attemptEvent = attemptCaptor.getValue();
        assertThat(attemptEvent.status()).isEqualTo(NodeStatus.FAILED);
        assertThat(attemptEvent.metadata())
            .containsEntry(ExecutionMetadataKeys.POLICY_ATTEMPT, 1)
            .containsEntry(ExecutionMetadataKeys.POLICY_MAX_ATTEMPTS, 2);

        // ONLY the terminal per-item result of item 1 (COMPLETED retry) went through
        // the full completion pipeline - counts/edges mutate once per item.
        ArgumentCaptor<NodeExecutionResult> terminalCaptor = ArgumentCaptor.forClass(NodeExecutionResult.class);
        verify(nodeCompletionService, atLeastOnce()).emitNodeComplete(
            eq(execution), eq(node), terminalCaptor.capture(), any(), eq(1), any());
        List<NodeExecutionResult> item1Terminal = terminalCaptor.getAllValues();
        assertThat(item1Terminal).hasSize(1);
        assertThat(item1Terminal.get(0).status()).isEqualTo(NodeStatus.COMPLETED);
        assertThat(item1Terminal.get(0).metadata())
            .containsEntry(ExecutionMetadataKeys.POLICY_ATTEMPT, 2)
            .containsEntry(ExecutionMetadataKeys.POLICY_MAX_ATTEMPTS, 2);
    }

    @Test
    @DisplayName("continueOnFailure per item: the FAILED item's branch still traverses its successor; the summary keeps the partial-failure markers")
    void continueOnFailureTraversesFailedItemBranch() {
        stubSplitOfThreeItems();
        when(context.plan()).thenReturn(planWithPolicy(new NodePolicy(0, 0L, true)));

        TestNode successor = new TestNode("mcp:after", NodeType.MCP);
        TestNode node = new TestNode(NODE_ID, NodeType.MCP);
        node.setPredecessors(List.of(SPLIT_KEY));
        node.addSuccessor(successor);
        node.setDynamicResult(ctx -> {
            int idx = currentIndexOf(ctx);
            return idx == 1
                ? NodeExecutionResult.failure(NODE_ID, "always down")
                : NodeExecutionResult.success(NODE_ID, Map.of("item", idx));
        });
        nodeMap.put(NODE_ID, node);

        List<Integer> traversedItems = new CopyOnWriteArrayList<>();
        SplitAwareNodeExecutor.SuccessorTraverser traverser = (succ, ctx, subItemIndex) -> {
            traversedItems.add(subItemIndex);
            return ctx;
        };

        NodeExecutionResult result = executor.execute(node, context, RUN_ID, nodeMap,
            execution, new TriggerItem("item-1", 0, Map.of()), 0, traverser);

        // ALL items (including the FAILED item 1) launched their successor traversal
        assertThat(traversedItems).containsExactlyInAnyOrder(0, 1, 2);
        // The item stays FAILED in the summary accounting (statuses honest → PARTIAL_SUCCESS semantics)
        assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
        assertThat(result.output()).containsEntry(ExecutionMetadataKeys.SPLIT_PARTIAL_FAILURE, true);
        assertThat(result.output().get(ExecutionMetadataKeys.SPLIT_FAILED_ITEM_INDICES))
            .isEqualTo(List.of(1));
    }

    @Test
    @DisplayName("without continueOnFailure a FAILED item's branch does NOT traverse (today's behavior preserved)")
    void failedItemBranchNotTraversedByDefault() {
        stubSplitOfThreeItems();
        lenient().when(context.plan()).thenReturn(null);

        TestNode successor = new TestNode("mcp:after", NodeType.MCP);
        TestNode node = new TestNode(NODE_ID, NodeType.MCP);
        node.setPredecessors(List.of(SPLIT_KEY));
        node.addSuccessor(successor);
        node.setDynamicResult(ctx -> {
            int idx = currentIndexOf(ctx);
            return idx == 1
                ? NodeExecutionResult.failure(NODE_ID, "always down")
                : NodeExecutionResult.success(NODE_ID, Map.of("item", idx));
        });
        nodeMap.put(NODE_ID, node);

        List<Integer> traversedItems = new CopyOnWriteArrayList<>();
        SplitAwareNodeExecutor.SuccessorTraverser traverser = (succ, ctx, subItemIndex) -> {
            traversedItems.add(subItemIndex);
            return ctx;
        };

        executor.execute(node, context, RUN_ID, nodeMap,
            execution, new TriggerItem("item-1", 0, Map.of()), 0, traverser);

        assertThat(traversedItems).containsExactlyInAnyOrder(0, 2);
    }

    // =====================================================================
    // Payload-lost rewrite (tier 2) - per-item traversal truth
    // =====================================================================

    @Test
    @DisplayName("Payload-lost item: a SUCCESS item whose completion reports payloadLost does NOT traverse its successor and the summary records the failure")
    void payloadLostItemTreatedAsFailedForTraversalAndSummary() {
        stubSplitOfThreeItems();
        lenient().when(context.plan()).thenReturn(null);

        TestNode successor = new TestNode("mcp:after", NodeType.MCP);
        TestNode node = new TestNode(NODE_ID, NodeType.MCP);
        node.setPredecessors(List.of(SPLIT_KEY));
        node.addSuccessor(successor);
        node.setDynamicResult(ctx -> NodeExecutionResult.success(NODE_ID, Map.of("item", currentIndexOf(ctx))));
        nodeMap.put(NODE_ID, node);

        String lossMessage = "[storage] Output payload lost: storage write failed after retries";
        // Item 1's completion reports payloadLost (row flipped to FAILED - tier 1);
        // items 0 and 2 persist normally.
        when(nodeCompletionService.emitNodeComplete(eq(execution), eq(node), any(), any(), anyInt(), any()))
            .thenAnswer(inv -> {
                int subItemIndex = inv.getArgument(4);
                return subItemIndex == 1
                    ? com.apimarketplace.orchestrator.services.completion.StepCompletionResult
                        .persistedPayloadLost(Map.of(), Map.of(), lossMessage)
                    : com.apimarketplace.orchestrator.services.completion.StepCompletionResult
                        .persisted(Map.of(), Map.of());
            });

        List<Integer> traversedItems = new CopyOnWriteArrayList<>();
        SplitAwareNodeExecutor.SuccessorTraverser traverser = (succ, ctx, subItemIndex) -> {
            traversedItems.add(subItemIndex);
            return ctx;
        };

        NodeExecutionResult result = executor.execute(node, context, RUN_ID, nodeMap,
            execution, new TriggerItem("item-1", 0, Map.of()), 0, traverser);

        // Traversal truth: item 1's success path must NOT run - its output blob is gone.
        assertThat(traversedItems).containsExactlyInAnyOrder(0, 2);
        // Summary truth: the loss is a per-item failure, named by cause.
        assertThat(result.output()).containsEntry(ExecutionMetadataKeys.SPLIT_PARTIAL_FAILURE, true);
        @SuppressWarnings("unchecked")
        List<String> errors = (List<String>) result.output().get(ExecutionMetadataKeys.SPLIT_ERRORS);
        assertThat(errors).anySatisfy(e -> assertThat(e).contains("Output payload lost"));
    }

    // =====================================================================
    // Test node (mirrors SplitAwareNodeExecutorTest.TestNode)
    // =====================================================================

    private static class TestNode extends BaseNode {
        private java.util.function.Function<ExecutionContext, NodeExecutionResult> dynamicResult;
        private final AtomicInteger executeCount = new AtomicInteger(0);

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
