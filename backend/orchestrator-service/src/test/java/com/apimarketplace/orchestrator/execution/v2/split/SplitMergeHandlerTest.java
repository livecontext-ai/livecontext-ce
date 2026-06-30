package com.apimarketplace.orchestrator.execution.v2.split;

import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.nodes.BaseNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeType;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SplitMergeHandler")
class SplitMergeHandlerTest {

    @Mock
    private SplitContextManager contextManager;

    @Mock
    private ExecutionContext context;

    private SplitMergeHandler handler;
    private Map<String, ExecutionNode> nodeMap;

    private static final int WORKFLOW_ITEM_INDEX = 0;

    @BeforeEach
    void setUp() {
        handler = new SplitMergeHandler(contextManager);
        nodeMap = new HashMap<>();
    }

    @Nested
    @DisplayName("isSplitMerge()")
    class IsSplitMerge {

        @Test
        @DisplayName("should return true when SplitContext exists")
        void shouldReturnTrueWhenContextExists() {
            SplitContext splitContext = SplitContext.create("core:split1:0", List.of("a"));
            when(contextManager.findActiveContext(eq("run1"), eq("core:merge1"), eq(WORKFLOW_ITEM_INDEX), any()))
                .thenReturn(Optional.of(splitContext));

            boolean result = handler.isSplitMerge("run1", "core:merge1", WORKFLOW_ITEM_INDEX, nodeMap);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should return false when no SplitContext")
        void shouldReturnFalseWhenNoContext() {
            when(contextManager.findActiveContext(eq("run1"), eq("core:merge1"), eq(WORKFLOW_ITEM_INDEX), any()))
                .thenReturn(Optional.empty());

            boolean result = handler.isSplitMerge("run1", "core:merge1", WORKFLOW_ITEM_INDEX, nodeMap);

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("handleMerge()")
    class HandleMerge {

        @Test
        @DisplayName("should aggregate results and close context")
        @SuppressWarnings("unchecked")
        void shouldAggregateAndCloseContext() {
            SplitContext splitContext = SplitContext.create("core:split1:0", List.of("a", "b", "c"))
                .withResults("mcp:step1", List.of("r1", "r2", "r3"));

            when(contextManager.findActiveContext(eq("run1"), eq("core:merge1"), eq(WORKFLOW_ITEM_INDEX), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.itemIndex()).thenReturn(0);
            when(context.itemId()).thenReturn("item1");

            NodeExecutionResult result = handler.handleMerge("run1", "core:merge1", WORKFLOW_ITEM_INDEX, context, nodeMap);

            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(result.output().get("split_merge")).isEqualTo(true);
            // The split ID is extracted from the context key (core:split1:0 -> core:split1)
            assertThat(result.output().get("split_id")).isEqualTo("core:split1");
            assertThat(result.output().get("item_count")).isEqualTo(3);

            // Should contain aggregated results
            Map<String, Object> aggregated = (Map<String, Object>) result.output().get("aggregated_results");
            assertThat(aggregated).containsKey("mcp:step1");

            // Should have removed the context (with workflow item index)
            verify(contextManager).removeContext("run1", "core:split1", WORKFLOW_ITEM_INDEX);
        }

        @Test
        @DisplayName("should include original items in output")
        void shouldIncludeOriginalItems() {
            List<Object> items = List.of("item1", "item2");
            SplitContext splitContext = SplitContext.create("core:split1:0", items);

            when(contextManager.findActiveContext(eq("run1"), eq("core:merge1"), eq(WORKFLOW_ITEM_INDEX), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.itemIndex()).thenReturn(0);
            when(context.itemId()).thenReturn("item1");

            NodeExecutionResult result = handler.handleMerge("run1", "core:merge1", WORKFLOW_ITEM_INDEX, context, nodeMap);

            assertThat(result.output().get("items")).isEqualTo(items);
        }

        @Test
        @DisplayName("should return empty result when no context")
        void shouldReturnEmptyResultWhenNoContext() {
            when(contextManager.findActiveContext(eq("run1"), eq("core:merge1"), eq(WORKFLOW_ITEM_INDEX), any()))
                .thenReturn(Optional.empty());
            when(context.itemIndex()).thenReturn(0);
            when(context.itemId()).thenReturn("item1");

            NodeExecutionResult result = handler.handleMerge("run1", "core:merge1", WORKFLOW_ITEM_INDEX, context, nodeMap);

            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(result.output().get("split_merge")).isEqualTo(false);
            assertThat(result.output().get("item_count")).isEqualTo(0);
        }

        @Test
        @DisplayName("should aggregate results from multiple nodes")
        @SuppressWarnings("unchecked")
        void shouldAggregateFromMultipleNodes() {
            SplitContext splitContext = SplitContext.create("core:split1:0", List.of("a", "b"))
                .withResults("mcp:step1", List.of("r1-1", "r1-2"))
                .withResults("mcp:step2", List.of("r2-1", "r2-2"));

            when(contextManager.findActiveContext(eq("run1"), eq("core:merge1"), eq(WORKFLOW_ITEM_INDEX), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.itemIndex()).thenReturn(0);
            when(context.itemId()).thenReturn("item1");

            NodeExecutionResult result = handler.handleMerge("run1", "core:merge1", WORKFLOW_ITEM_INDEX, context, nodeMap);

            Map<String, Object> aggregated = (Map<String, Object>) result.output().get("aggregated_results");
            assertThat(aggregated).containsKey("mcp:step1");
            assertThat(aggregated).containsKey("mcp:step2");
            assertThat(aggregated.get("nodes_executed")).isEqualTo(2);
        }

        @Test
        @DisplayName("should include latest results for easy access")
        void shouldIncludeLatestResults() {
            List<Object> results = List.of("latest1", "latest2");
            SplitContext splitContext = SplitContext.create("core:split1:0", List.of("a", "b"))
                .withResults("mcp:step1", results);

            when(contextManager.findActiveContext(eq("run1"), eq("core:merge1"), eq(WORKFLOW_ITEM_INDEX), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.itemIndex()).thenReturn(0);
            when(context.itemId()).thenReturn("item1");

            NodeExecutionResult result = handler.handleMerge("run1", "core:merge1", WORKFLOW_ITEM_INDEX, context, nodeMap);

            assertThat(result.output().get("results")).isEqualTo(results);
        }

        @Test
        @DisplayName("should aggregate map fields from results")
        @SuppressWarnings("unchecked")
        void shouldAggregateMapFields() {
            List<Object> results = List.of(
                Map.of("id", 1, "value", "a"),
                Map.of("id", 2, "value", "b")
            );
            SplitContext splitContext = SplitContext.create("core:split1:0", List.of("x", "y"))
                .withResults("mcp:step1", results);

            when(contextManager.findActiveContext(eq("run1"), eq("core:merge1"), eq(WORKFLOW_ITEM_INDEX), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.itemIndex()).thenReturn(0);
            when(context.itemId()).thenReturn("item1");

            NodeExecutionResult result = handler.handleMerge("run1", "core:merge1", WORKFLOW_ITEM_INDEX, context, nodeMap);

            Map<String, Object> aggregated = (Map<String, Object>) result.output().get("aggregated_results");
            // Should have extracted and aggregated common fields
            assertThat(aggregated).containsKey("mcp:step1.id");
            assertThat(aggregated).containsKey("mcp:step1.value");
        }
    }

    @Nested
    @DisplayName("isBranchRejoinMerge()")
    class IsBranchRejoinMerge {

        @Test
        @DisplayName("should detect branch-rejoin when predecessors share common ancestor")
        void shouldDetectBranchRejoin() {
            // classify → [label_a, label_b, label_c] → merge
            BaseNode labelA = new TestNode("agent:label_a", NodeType.MCP);
            labelA.addPredecessor("agent:classify");
            BaseNode labelB = new TestNode("agent:label_b", NodeType.MCP);
            labelB.addPredecessor("agent:classify");
            BaseNode labelC = new TestNode("agent:label_c", NodeType.MCP);
            labelC.addPredecessor("agent:classify");

            BaseNode mergeNode = new TestNode("core:merge", NodeType.MERGE);
            mergeNode.addPredecessor("agent:label_a");
            mergeNode.addPredecessor("agent:label_b");
            mergeNode.addPredecessor("agent:label_c");

            nodeMap.put("agent:label_a", labelA);
            nodeMap.put("agent:label_b", labelB);
            nodeMap.put("agent:label_c", labelC);
            nodeMap.put("core:merge", mergeNode);

            assertThat(handler.isBranchRejoinMerge("core:merge", nodeMap)).isTrue();
        }

        @Test
        @DisplayName("should not detect branch-rejoin for single predecessor")
        void shouldNotDetectForSinglePredecessor() {
            BaseNode mergeNode = new TestNode("core:merge", NodeType.MERGE);
            mergeNode.addPredecessor("mcp:step1");
            nodeMap.put("core:merge", mergeNode);

            assertThat(handler.isBranchRejoinMerge("core:merge", nodeMap)).isFalse();
        }

        @Test
        @DisplayName("should not detect branch-rejoin when predecessors have different ancestors")
        void shouldNotDetectForDifferentAncestors() {
            // Two completely separate ancestor chains → no common ancestor
            // origin_x → branch_a → step_a → merge
            // origin_y → branch_b → step_b → merge
            BaseNode originX = new TestNode("mcp:origin_x", NodeType.MCP);
            BaseNode originY = new TestNode("mcp:origin_y", NodeType.MCP);
            BaseNode branchA = new TestNode("mcp:branch_a", NodeType.MCP);
            branchA.addPredecessor("mcp:origin_x");
            BaseNode branchB = new TestNode("mcp:branch_b", NodeType.MCP);
            branchB.addPredecessor("mcp:origin_y");
            BaseNode stepA = new TestNode("mcp:step_a", NodeType.MCP);
            stepA.addPredecessor("mcp:branch_a");
            BaseNode stepB = new TestNode("mcp:step_b", NodeType.MCP);
            stepB.addPredecessor("mcp:branch_b");

            BaseNode mergeNode = new TestNode("core:merge", NodeType.MERGE);
            mergeNode.addPredecessor("mcp:step_a");
            mergeNode.addPredecessor("mcp:step_b");

            nodeMap.put("mcp:origin_x", originX);
            nodeMap.put("mcp:origin_y", originY);
            nodeMap.put("mcp:branch_a", branchA);
            nodeMap.put("mcp:branch_b", branchB);
            nodeMap.put("mcp:step_a", stepA);
            nodeMap.put("mcp:step_b", stepB);
            nodeMap.put("core:merge", mergeNode);

            assertThat(handler.isBranchRejoinMerge("core:merge", nodeMap)).isFalse();
        }

        @Test
        @DisplayName("should return false for branch-rejoin when isSplitMerge is called")
        void shouldReturnFalseForBranchRejoinInSplitMerge() {
            // Split → classify → [label_a, label_b] → merge
            SplitContext splitContext = SplitContext.create("core:split:0", List.of("a", "b"));
            when(contextManager.findActiveContext(eq("run1"), eq("core:merge"), eq(WORKFLOW_ITEM_INDEX), any()))
                .thenReturn(Optional.of(splitContext));

            BaseNode labelA = new TestNode("agent:label_a", NodeType.MCP);
            labelA.addPredecessor("agent:classify");
            BaseNode labelB = new TestNode("agent:label_b", NodeType.MCP);
            labelB.addPredecessor("agent:classify");

            BaseNode mergeNode = new TestNode("core:merge", NodeType.MERGE);
            mergeNode.addPredecessor("agent:label_a");
            mergeNode.addPredecessor("agent:label_b");

            nodeMap.put("agent:label_a", labelA);
            nodeMap.put("agent:label_b", labelB);
            nodeMap.put("core:merge", mergeNode);

            boolean result = handler.isSplitMerge("run1", "core:merge", WORKFLOW_ITEM_INDEX, nodeMap);
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should detect branch-rejoin with port-suffixed predecessor IDs (production case)")
        void shouldDetectBranchRejoinWithPortSuffixedIds() {
            // Production wiring: classify → [category_0, category_1, category_2] → merge
            // Predecessor IDs have port suffixes like agent:classifier:category_0
            BaseNode catA = new TestNode("mcp:action_a", NodeType.MCP);
            catA.addPredecessor("agent:classifier:category_0"); // port-suffixed
            BaseNode catB = new TestNode("mcp:action_b", NodeType.MCP);
            catB.addPredecessor("agent:classifier:category_1"); // port-suffixed
            BaseNode catC = new TestNode("mcp:action_c", NodeType.MCP);
            catC.addPredecessor("agent:classifier:category_2"); // port-suffixed

            // The classify node itself exists in nodeMap as "agent:classifier" (no port)
            BaseNode classifier = new TestNode("agent:classifier", NodeType.MCP);
            nodeMap.put("agent:classifier", classifier);

            BaseNode mergeNode = new TestNode("core:merge", NodeType.MERGE);
            mergeNode.addPredecessor("mcp:action_a");
            mergeNode.addPredecessor("mcp:action_b");
            mergeNode.addPredecessor("mcp:action_c");

            nodeMap.put("mcp:action_a", catA);
            nodeMap.put("mcp:action_b", catB);
            nodeMap.put("mcp:action_c", catC);
            nodeMap.put("core:merge", mergeNode);

            assertThat(handler.isBranchRejoinMerge("core:merge", nodeMap)).isTrue();
        }

        @Test
        @DisplayName("should return false conservatively when predecessor not in nodeMap")
        void shouldReturnFalseWhenPredecessorNotResolvable() {
            // merge has 2 predecessors but one can't be resolved in nodeMap
            BaseNode labelA = new TestNode("agent:label_a", NodeType.MCP);
            labelA.addPredecessor("agent:classify");
            // label_b is NOT in nodeMap (unresolvable)

            BaseNode mergeNode = new TestNode("core:merge", NodeType.MERGE);
            mergeNode.addPredecessor("agent:label_a");
            mergeNode.addPredecessor("agent:label_b"); // not in nodeMap

            nodeMap.put("agent:label_a", labelA);
            nodeMap.put("core:merge", mergeNode);

            // Should return false conservatively (can't verify all predecessors)
            assertThat(handler.isBranchRejoinMerge("core:merge", nodeMap)).isFalse();
        }

        @Test
        @DisplayName("should detect branch-rejoin with deep topology (classify → intermediate → merge)")
        void shouldDetectBranchRejoinWithDeepTopology() {
            // classify → [label_a → step_x, label_b → step_y] → merge
            // step_x and step_y are merge's predecessors, but they trace back to classify
            BaseNode classify = new TestNode("agent:classify", NodeType.MCP);
            BaseNode labelA = new TestNode("agent:label_a", NodeType.MCP);
            labelA.addPredecessor("agent:classify");
            BaseNode labelB = new TestNode("agent:label_b", NodeType.MCP);
            labelB.addPredecessor("agent:classify");
            BaseNode stepX = new TestNode("mcp:step_x", NodeType.MCP);
            stepX.addPredecessor("agent:label_a");
            BaseNode stepY = new TestNode("mcp:step_y", NodeType.MCP);
            stepY.addPredecessor("agent:label_b");

            BaseNode mergeNode = new TestNode("core:merge", NodeType.MERGE);
            mergeNode.addPredecessor("mcp:step_x");
            mergeNode.addPredecessor("mcp:step_y");

            nodeMap.put("agent:classify", classify);
            nodeMap.put("agent:label_a", labelA);
            nodeMap.put("agent:label_b", labelB);
            nodeMap.put("mcp:step_x", stepX);
            nodeMap.put("mcp:step_y", stepY);
            nodeMap.put("core:merge", mergeNode);

            assertThat(handler.isBranchRejoinMerge("core:merge", nodeMap)).isTrue();
        }

        @Test
        @DisplayName("should return false when merge node not in nodeMap")
        void shouldReturnFalseWhenMergeNodeNotInNodeMap() {
            assertThat(handler.isBranchRejoinMerge("core:missing", nodeMap)).isFalse();
        }

        @Test
        @DisplayName("isSplitMerge should return true for genuine split-aggregation with merge in nodeMap")
        void shouldReturnTrueForSplitAggregationWithMergeInNodeMap() {
            // Split → step → merge (single predecessor = split-aggregation)
            SplitContext splitContext = SplitContext.create("core:split:0", List.of("a", "b", "c"));
            when(contextManager.findActiveContext(eq("run1"), eq("core:merge"), eq(WORKFLOW_ITEM_INDEX), any()))
                .thenReturn(Optional.of(splitContext));

            BaseNode step = new TestNode("mcp:step", NodeType.MCP);
            BaseNode mergeNode = new TestNode("core:merge", NodeType.MERGE);
            mergeNode.addPredecessor("mcp:step");

            nodeMap.put("mcp:step", step);
            nodeMap.put("core:merge", mergeNode);

            // Single predecessor → not branch-rejoin → true split-aggregation
            boolean result = handler.isSplitMerge("run1", "core:merge", WORKFLOW_ITEM_INDEX, nodeMap);
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should NOT detect branch-rejoin when common ancestor is the split node itself")
        void shouldNotDetectBranchRejoinWhenCommonAncestorIsSplitNode() {
            // split → [step_a, step_b] → merge
            // Both predecessors share split as ancestor, but this is split-aggregation, not branch-rejoin
            BaseNode splitNode = new TestNode("core:split", NodeType.SPLIT);
            BaseNode stepA = new TestNode("mcp:step_a", NodeType.MCP);
            stepA.addPredecessor("core:split");
            BaseNode stepB = new TestNode("mcp:step_b", NodeType.MCP);
            stepB.addPredecessor("core:split");

            BaseNode mergeNode = new TestNode("core:merge", NodeType.MERGE);
            mergeNode.addPredecessor("mcp:step_a");
            mergeNode.addPredecessor("mcp:step_b");

            nodeMap.put("core:split", splitNode);
            nodeMap.put("mcp:step_a", stepA);
            nodeMap.put("mcp:step_b", stepB);
            nodeMap.put("core:merge", mergeNode);

            // Split node is the only common ancestor → NOT branch-rejoin
            assertThat(handler.isBranchRejoinMerge("core:merge", nodeMap)).isFalse();
        }

        @Test
        @DisplayName("should detect branch-rejoin even when split is a transitive ancestor (classify is closer)")
        void shouldDetectBranchRejoinWithSplitAsDistantAncestor() {
            // split → classify → [label_a, label_b] → merge
            // Common ancestors include both classify AND split, but split is filtered out
            BaseNode splitNode = new TestNode("core:split", NodeType.SPLIT);
            BaseNode classify = new TestNode("agent:classify", NodeType.MCP);
            classify.addPredecessor("core:split");
            BaseNode labelA = new TestNode("agent:label_a", NodeType.MCP);
            labelA.addPredecessor("agent:classify");
            BaseNode labelB = new TestNode("agent:label_b", NodeType.MCP);
            labelB.addPredecessor("agent:classify");

            BaseNode mergeNode = new TestNode("core:merge", NodeType.MERGE);
            mergeNode.addPredecessor("agent:label_a");
            mergeNode.addPredecessor("agent:label_b");

            nodeMap.put("core:split", splitNode);
            nodeMap.put("agent:classify", classify);
            nodeMap.put("agent:label_a", labelA);
            nodeMap.put("agent:label_b", labelB);
            nodeMap.put("core:merge", mergeNode);

            // classify is non-split common ancestor → IS branch-rejoin
            assertThat(handler.isBranchRejoinMerge("core:merge", nodeMap)).isTrue();
        }
    }

    /**
     * Simple test node implementation for unit tests.
     */
    private static class TestNode extends BaseNode {
        TestNode(String nodeId, NodeType type) {
            super(nodeId, type);
        }

        @Override
        public boolean canExecute(ExecutionContext context) {
            return true;
        }

        @Override
        public NodeExecutionResult execute(ExecutionContext context) {
            return NodeExecutionResult.success(nodeId, Map.of());
        }

        @Override
        public boolean isMergeNode() {
            return type == NodeType.MERGE;
        }

        @Override
        public boolean isSplitNode() {
            return type == NodeType.SPLIT;
        }
    }

    @Nested
    @DisplayName("getSplitContext()")
    class GetSplitContext {

        @Test
        @DisplayName("should delegate to context manager")
        void shouldDelegateToContextManager() {
            SplitContext expected = SplitContext.create("core:split1:0", List.of());
            when(contextManager.getContext("run1", "core:split1", WORKFLOW_ITEM_INDEX))
                .thenReturn(Optional.of(expected));

            Optional<SplitContext> result = handler.getSplitContext("run1", "core:split1", WORKFLOW_ITEM_INDEX);

            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo(expected);
        }
    }
}
