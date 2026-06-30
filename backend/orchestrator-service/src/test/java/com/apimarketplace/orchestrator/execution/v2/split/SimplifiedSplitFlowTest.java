package com.apimarketplace.orchestrator.execution.v2.split;

import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.nodes.BaseNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeType;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Integration tests for the simplified split flow.
 *
 * <p>These tests verify the complete flow:
 * <ol>
 *   <li>Split spawns items and completes immediately</li>
 *   <li>Downstream nodes execute for all items in parallel</li>
 *   <li>Merge aggregates results and closes context</li>
 *   <li>Nodes after merge execute only once</li>
 * </ol>
 *
 * <p>Core principle: "Split is a PRODUCER of data, nothing else."
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Simplified Split Flow")
class SimplifiedSplitFlowTest {

    @Mock
    private V2TemplateAdapter templateAdapter;

    @Mock
    private ExecutionContext context;

    private SplitContextManager contextManager;
    private SplitNodeExecutor splitExecutor;
    private SplitAwareNodeExecutor awareExecutor;
    private SplitMergeHandler mergeHandler;
    private Map<String, ExecutionNode> nodeMap;

    private static final String RUN_ID = "test-run-1";

    @BeforeEach
    void setUp() {
        contextManager = new SplitContextManager();
        splitExecutor = new SplitNodeExecutor(contextManager, templateAdapter);
        awareExecutor = new SplitAwareNodeExecutor(contextManager, null, null, null, null, null, Executors.newFixedThreadPool(2));
        mergeHandler = new SplitMergeHandler(contextManager);
        nodeMap = new HashMap<>();

        // Setup mock context (lenient because not all tests use this)
        lenient().when(context.withGlobalData(any(), any())).thenReturn(context);
        lenient().when(context.withResult(any(), any())).thenReturn(context);
        lenient().when(context.withItemIndex(org.mockito.ArgumentMatchers.anyInt())).thenReturn(context);
        lenient().when(context.itemIndex()).thenReturn(0);
    }

    @AfterEach
    void tearDown() {
        awareExecutor.shutdown();
    }

    @Nested
    @DisplayName("Complete Flow: split → step → merge → afterMerge")
    class CompleteFlow {

        @Test
        @DisplayName("should process all items and aggregate at merge")
        @SuppressWarnings("unchecked")
        void shouldProcessAllItemsAndAggregate() {
            // Setup: split → step1 → merge → afterMerge
            List<Object> items = List.of("item1", "item2", "item3");
            setupNodeMap(items);

            // Step 1: Split spawns items and completes immediately
            when(templateAdapter.evaluateTemplate(any(), any())).thenReturn(items);
            NodeExecutionResult splitResult = splitExecutor.execute(
                RUN_ID, "core:split1", "{{items}}", 0, 0, context);

            assertThat(splitResult.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(splitResult.output().get("item_count")).isEqualTo(3);
            assertThat(splitResult.output().get("terminated")).isEqualTo(true);

            // Verify split context was created
            assertThat(contextManager.hasContexts(RUN_ID)).isTrue();

            // Step 2: Downstream node executes for ALL items in parallel
            TestNode step1Node = (TestNode) nodeMap.get("mcp:step1");
            NodeExecutionResult step1Result = awareExecutor.execute(step1Node, context, RUN_ID, nodeMap);

            assertThat(step1Result.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(step1Result.output().get("split_item_count")).isEqualTo(3);
            assertThat(step1Node.getExecuteCount()).isEqualTo(3); // Executed 3 times

            // Step 3: Merge aggregates results
            boolean isMerge = mergeHandler.isSplitMerge(RUN_ID, "core:merge1", 0, nodeMap);
            assertThat(isMerge).isTrue();

            NodeExecutionResult mergeResult = mergeHandler.handleMerge(RUN_ID, "core:merge1", 0, context, nodeMap);

            assertThat(mergeResult.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(mergeResult.output().get("split_merge")).isEqualTo(true);
            assertThat(mergeResult.output().get("item_count")).isEqualTo(3);

            // Verify split context was closed
            assertThat(contextManager.getContext(RUN_ID, "core:split1")).isEmpty();

            // Step 4: Node after merge executes only once (no split context)
            TestNode afterMergeNode = (TestNode) nodeMap.get("mcp:after_merge");
            NodeExecutionResult afterMergeResult = awareExecutor.execute(afterMergeNode, context, RUN_ID, nodeMap);

            assertThat(afterMergeResult.status()).isEqualTo(NodeStatus.COMPLETED);
            // Node executes normally after merge (no split handling)
            assertThat(afterMergeNode.getExecuteCount()).isEqualTo(1); // Executed only once
        }

        @Test
        @DisplayName("should handle empty array - completes immediately with 0 items")
        void shouldHandleEmptyArray() {
            List<Object> items = List.of();
            setupNodeMap(items);

            when(templateAdapter.evaluateTemplate(any(), any())).thenReturn(items);
            NodeExecutionResult splitResult = splitExecutor.execute(
                RUN_ID, "core:split1", "{{items}}", 0, 0, context);

            assertThat(splitResult.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(splitResult.output().get("item_count")).isEqualTo(0);
            assertThat(splitResult.output().get("spawn_reason")).isEqualTo("empty_list");
            assertThat(splitResult.output().get("terminated")).isEqualTo(true);

            // Downstream node should execute 0 times and be marked SKIPPED so the
            // engine clears the running tracker (otherwise ReadyNodeCalculator loops).
            TestNode step1Node = (TestNode) nodeMap.get("mcp:step1");
            NodeExecutionResult step1Result = awareExecutor.execute(step1Node, context, RUN_ID, nodeMap);

            assertThat(step1Result.status()).isEqualTo(NodeStatus.SKIPPED);
            assertThat(step1Node.getExecuteCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("should handle single item")
        void shouldHandleSingleItem() {
            List<Object> items = List.of("only_item");
            setupNodeMap(items);

            when(templateAdapter.evaluateTemplate(any(), any())).thenReturn(items);
            splitExecutor.execute(RUN_ID, "core:split1", "{{items}}", 0, 0, context);

            TestNode step1Node = (TestNode) nodeMap.get("mcp:step1");
            NodeExecutionResult step1Result = awareExecutor.execute(step1Node, context, RUN_ID, nodeMap);

            assertThat(step1Result.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(step1Result.output().get("split_item_count")).isEqualTo(1);
            assertThat(step1Node.getExecuteCount()).isEqualTo(1);
        }

        private void setupNodeMap(List<Object> items) {
            // Create split node
            nodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT, List.of()));

            // Create step1 node (after split)
            TestNode step1 = new TestNode("mcp:step1", NodeType.MCP, List.of("core:split1"));
            step1.setDynamicResult(ctx -> NodeExecutionResult.success("mcp:step1", Map.of("processed", true)));
            nodeMap.put("mcp:step1", step1);

            // Create merge node
            nodeMap.put("core:merge1", new TestNode("core:merge1", NodeType.MERGE, List.of("mcp:step1")));

            // Create afterMerge node
            TestNode afterMerge = new TestNode("mcp:after_merge", NodeType.MCP, List.of("core:merge1"));
            afterMerge.setExecuteResult(NodeExecutionResult.success("mcp:after_merge", Map.of("final", true)));
            nodeMap.put("mcp:after_merge", afterMerge);
        }
    }

    @Nested
    @DisplayName("Rerun Scenario")
    class RerunScenario {

        @Test
        @DisplayName("should clear context and allow rerun")
        void shouldClearContextAndAllowRerun() {
            List<Object> items = List.of("a", "b");

            // First run
            when(templateAdapter.evaluateTemplate(any(), any())).thenReturn(items);
            splitExecutor.execute(RUN_ID, "core:split1", "{{items}}", 0, 0, context);

            assertThat(contextManager.hasContexts(RUN_ID)).isTrue();

            // Clear for rerun
            splitExecutor.clearContext(RUN_ID, "core:split1", 0);

            assertThat(contextManager.getContext(RUN_ID, "core:split1")).isEmpty();

            // Second run with different items
            List<Object> newItems = List.of("x", "y", "z");
            when(templateAdapter.evaluateTemplate(any(), any())).thenReturn(newItems);
            NodeExecutionResult rerunResult = splitExecutor.execute(
                RUN_ID, "core:split1", "{{items}}", 0, 0, context);

            assertThat(rerunResult.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(rerunResult.output().get("item_count")).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandling {

        @Test
        @DisplayName("should fail when expression evaluation fails")
        void shouldFailOnExpressionError() {
            when(templateAdapter.evaluateTemplate(any(), any()))
                .thenThrow(new RuntimeException("Expression error"));

            NodeExecutionResult result = splitExecutor.execute(
                RUN_ID, "core:split1", "{{invalid}}", 0, 0, context);

            assertThat(result.status()).isEqualTo(NodeStatus.FAILED);
            assertThat(result.errorMessage()).isPresent();
        }

        @Test
        @DisplayName("Phase 2.B: partial failure (some items fail, some succeed) returns COMPLETED with marker")
        void shouldReturnCompletedWithPartialMarkerOnMixedFailure() {
            // Phase 2.B (2026-04-29 prod-incident fix): partial failures no longer poison
            // the global node status. The summary returns COMPLETED with
            // split_partial_failure=true so successful items' downstream can fire.
            List<Object> items = List.of("a", "b", "c");

            when(templateAdapter.evaluateTemplate(any(), any())).thenReturn(items);
            splitExecutor.execute(RUN_ID, "core:split1", "{{items}}", 0, 0, context);

            int[] itemIndex = {0};
            TestNode failingNode = new TestNode("mcp:failing", NodeType.MCP, List.of("core:split1"));
            failingNode.setDynamicResult(ctx -> {
                int currentIndex = itemIndex[0]++;
                if (currentIndex == 1) {
                    return NodeExecutionResult.failure("mcp:failing", "Item 1 failed");
                }
                return NodeExecutionResult.success("mcp:failing", Map.of());
            });

            nodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT, List.of()));
            nodeMap.put("mcp:failing", failingNode);

            NodeExecutionResult result = awareExecutor.execute(failingNode, context, RUN_ID, nodeMap);

            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(result.output()).containsEntry("split_partial_failure", true);
        }
    }

    @Nested
    @DisplayName("Multiple Downstream Nodes")
    class MultipleDownstreamNodes {

        @Test
        @DisplayName("should execute multiple downstream nodes in sequence")
        void shouldExecuteMultipleDownstreamNodes() {
            List<Object> items = List.of("x", "y");

            when(templateAdapter.evaluateTemplate(any(), any())).thenReturn(items);
            splitExecutor.execute(RUN_ID, "core:split1", "{{items}}", 0, 0, context);

            // Setup node chain: split → step1 → step2 → merge
            nodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT, List.of()));

            TestNode step1 = new TestNode("mcp:step1", NodeType.MCP, List.of("core:split1"));
            step1.setExecuteResult(NodeExecutionResult.success("mcp:step1", Map.of("step", 1)));
            nodeMap.put("mcp:step1", step1);

            TestNode step2 = new TestNode("mcp:step2", NodeType.MCP, List.of("mcp:step1"));
            step2.setExecuteResult(NodeExecutionResult.success("mcp:step2", Map.of("step", 2)));
            nodeMap.put("mcp:step2", step2);

            // Execute step1 - first direct successor of split
            NodeExecutionResult result1 = awareExecutor.execute(step1, context, RUN_ID, nodeMap);
            assertThat(result1.output().get("split_item_count")).isEqualTo(2);
            assertThat(step1.getExecuteCount()).isEqualTo(2);

            // Execute step2 - in step-by-step mode (no successor traverser), ALL nodes in split scope
            // execute N times. The BFS traversal finds the split context through the predecessor chain.
            NodeExecutionResult result2 = awareExecutor.execute(step2, context, RUN_ID, nodeMap);
            assertThat(step2.getExecuteCount()).isEqualTo(2); // Executes for each split item in step-by-step mode
        }
    }

    /**
     * Test node implementation that properly delegates type-based checks.
     */
    private static class TestNode extends BaseNode {
        private volatile NodeExecutionResult executeResult;
        private volatile java.util.function.Function<ExecutionContext, NodeExecutionResult> dynamicResult;
        private final AtomicInteger executeCount = new AtomicInteger(0);

        TestNode(String nodeId, NodeType type, List<String> predecessors) {
            super(nodeId, type);
            this.setPredecessors(predecessors);
        }

        void setExecuteResult(NodeExecutionResult result) {
            this.executeResult = result;
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
            return executeResult != null ? executeResult : NodeExecutionResult.success(nodeId, Map.of());
        }
    }
}
