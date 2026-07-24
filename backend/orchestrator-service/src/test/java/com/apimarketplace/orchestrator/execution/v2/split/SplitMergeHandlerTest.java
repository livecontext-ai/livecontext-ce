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
        @DisplayName("Regression: recovers a predecessor ABSENT from in-memory resultsByNode from the durable store (cross-pod / restart collapse fix)")
        @SuppressWarnings("unchecked")
        void durableBackfillRecoversAbsentPredecessorPerItem() {
            // resultsByNode is EMPTY (cross-pod async/signal resume rebuilt items only, or a restart
            // emptied the cache). Pre-fix the aggregation merge would drop agent:classify entirely.
            SplitContext splitContext = SplitContext.create("core:split1:0", List.of("a", "b", "c"));

            // Topology: split1 -> classify -> merge1 (classify is the merge's split-subgraph ancestor).
            BaseNode split = new TestNode("core:split1", NodeType.SPLIT);
            BaseNode classify = new TestNode("agent:classify", NodeType.MCP);
            classify.addPredecessor("core:split1");
            BaseNode merge = new TestNode("core:merge1", NodeType.MERGE);
            merge.addPredecessor("agent:classify");
            nodeMap.put("core:split1", split);
            nodeMap.put("agent:classify", classify);
            nodeMap.put("core:merge1", merge);

            com.apimarketplace.orchestrator.services.StepOutputService stepOutputService =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.services.StepOutputService.class);
            SplitMergeHandler handlerWithDurable = new SplitMergeHandler(contextManager);
            handlerWithDurable.setStepOutputService(stepOutputService);

            when(contextManager.findActiveContext(eq("run1"), eq("core:merge1"), eq(WORKFLOW_ITEM_INDEX), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.itemIndex()).thenReturn(0);
            when(context.itemId()).thenReturn("item1");
            when(context.runId()).thenReturn("run1");
            when(context.epoch()).thenReturn(0);
            when(context.tenantId()).thenReturn("tenant1");

            Map<String, Map<Integer, Object>> durable = Map.of(
                "agent:classify", Map.of(
                    0, Map.of("selected_category", "billing"),
                    1, Map.of("selected_category", "bug"),
                    2, Map.of("selected_category", "refund_clear")));
            when(stepOutputService.loadPerItemOutputsByStepKey("run1", 0, "tenant1")).thenReturn(durable);

            NodeExecutionResult result =
                handlerWithDurable.handleMerge("run1", "core:merge1", WORKFLOW_ITEM_INDEX, context, nodeMap);

            Map<String, Object> aggregated = (Map<String, Object>) result.output().get("aggregated_results");
            assertThat(aggregated)
                .as("classify absent in memory must be recovered from the durable store")
                .containsKey("agent:classify");
            assertThat((List<Object>) aggregated.get("agent:classify"))
                .as("each item keeps its OWN classification, not item 0's")
                .containsExactly(
                    Map.of("selected_category", "billing"),
                    Map.of("selected_category", "bug"),
                    Map.of("selected_category", "refund_clear"));
        }

        @Test
        @DisplayName("durable is NOT read when the whole subgraph is warm in memory (no unnecessary query)")
        @SuppressWarnings("unchecked")
        void noDurableReadWhenWarm() {
            // Topology split1 -> step1 -> merge1; step1 fully present in memory (all slots non-null).
            SplitContext splitContext = SplitContext.create("core:split1:0", List.of("a", "b"))
                .withResults("mcp:step1", List.of(Map.of("v", "mem-0"), Map.of("v", "mem-1")));
            BaseNode split = new TestNode("core:split1", NodeType.SPLIT);
            BaseNode step1 = new TestNode("mcp:step1", NodeType.MCP);
            step1.addPredecessor("core:split1");
            BaseNode merge = new TestNode("core:merge1", NodeType.MERGE);
            merge.addPredecessor("mcp:step1");
            nodeMap.put("core:split1", split);
            nodeMap.put("mcp:step1", step1);
            nodeMap.put("core:merge1", merge);

            com.apimarketplace.orchestrator.services.StepOutputService stepOutputService =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.services.StepOutputService.class);
            SplitMergeHandler handlerWithDurable = new SplitMergeHandler(contextManager);
            handlerWithDurable.setStepOutputService(stepOutputService);

            when(contextManager.findActiveContext(eq("run1"), eq("core:merge1"), eq(WORKFLOW_ITEM_INDEX), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.itemIndex()).thenReturn(0);
            when(context.itemId()).thenReturn("item1");

            NodeExecutionResult result =
                handlerWithDurable.handleMerge("run1", "core:merge1", WORKFLOW_ITEM_INDEX, context, nodeMap);

            org.mockito.Mockito.verify(stepOutputService, org.mockito.Mockito.never())
                .loadPerItemOutputsByStepKey(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString());
            Map<String, Object> aggregated = (Map<String, Object>) result.output().get("aggregated_results");
            assertThat((List<Object>) aggregated.get("mcp:step1"))
                .containsExactly(Map.of("v", "mem-0"), Map.of("v", "mem-1"));
        }

        @Test
        @DisplayName("durable backfills an absent node yet NEVER overrides a present in-memory slot")
        @SuppressWarnings("unchecked")
        void backfillsAbsentButKeepsMemory() {
            // split1 -> [step1 (in memory), classify (absent -> forces durable read)] -> merge1
            SplitContext splitContext = SplitContext.create("core:split1:0", List.of("a", "b"))
                .withResults("mcp:step1", List.of(Map.of("v", "mem-0"), Map.of("v", "mem-1")));
            BaseNode split = new TestNode("core:split1", NodeType.SPLIT);
            BaseNode step1 = new TestNode("mcp:step1", NodeType.MCP);
            step1.addPredecessor("core:split1");
            BaseNode classify = new TestNode("agent:classify", NodeType.MCP);
            classify.addPredecessor("core:split1");
            BaseNode merge = new TestNode("core:merge1", NodeType.MERGE);
            merge.addPredecessor("mcp:step1");
            merge.addPredecessor("agent:classify");
            nodeMap.put("core:split1", split);
            nodeMap.put("mcp:step1", step1);
            nodeMap.put("agent:classify", classify);
            nodeMap.put("core:merge1", merge);

            com.apimarketplace.orchestrator.services.StepOutputService stepOutputService =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.services.StepOutputService.class);
            SplitMergeHandler handlerWithDurable = new SplitMergeHandler(contextManager);
            handlerWithDurable.setStepOutputService(stepOutputService);

            when(contextManager.findActiveContext(eq("run1"), eq("core:merge1"), eq(WORKFLOW_ITEM_INDEX), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.itemIndex()).thenReturn(0);
            when(context.itemId()).thenReturn("item1");
            when(context.runId()).thenReturn("run1");
            when(context.epoch()).thenReturn(0);
            when(context.tenantId()).thenReturn("tenant1");
            // Durable carries a STALE step1 (must NOT override memory) and the real absent classify.
            when(stepOutputService.loadPerItemOutputsByStepKey("run1", 0, "tenant1")).thenReturn(Map.of(
                "mcp:step1", Map.of(0, Map.of("v", "STALE"), 1, Map.of("v", "STALE")),
                "agent:classify", Map.of(0, Map.of("cat", "a"), 1, Map.of("cat", "b"))));

            NodeExecutionResult result =
                handlerWithDurable.handleMerge("run1", "core:merge1", WORKFLOW_ITEM_INDEX, context, nodeMap);

            Map<String, Object> aggregated = (Map<String, Object>) result.output().get("aggregated_results");
            assertThat((List<Object>) aggregated.get("mcp:step1"))
                .as("live in-memory slots must not be overwritten by durable rows")
                .containsExactly(Map.of("v", "mem-0"), Map.of("v", "mem-1"));
            assertThat((List<Object>) aggregated.get("agent:classify"))
                .as("absent node recovered from durable")
                .containsExactly(Map.of("cat", "a"), Map.of("cat", "b"));
        }

        @Test
        @DisplayName("durable fold EXCLUDES unrelated epoch nodes (trigger / pre-split), only split-subgraph ancestors")
        @SuppressWarnings("unchecked")
        void durableFoldExcludesUnrelatedEpochNodes() {
            SplitContext splitContext = SplitContext.create("core:split1:0", List.of("a", "b", "c"));
            // trigger:manual -> pre:fetch -> split1 -> classify -> merge1
            BaseNode trigger = new TestNode("trigger:manual", NodeType.MCP);
            BaseNode preFetch = new TestNode("mcp:pre_fetch", NodeType.MCP);
            preFetch.addPredecessor("trigger:manual");
            BaseNode split = new TestNode("core:split1", NodeType.SPLIT);
            split.addPredecessor("mcp:pre_fetch");
            BaseNode classify = new TestNode("agent:classify", NodeType.MCP);
            classify.addPredecessor("core:split1");
            BaseNode merge = new TestNode("core:merge1", NodeType.MERGE);
            merge.addPredecessor("agent:classify");
            nodeMap.put("trigger:manual", trigger);
            nodeMap.put("mcp:pre_fetch", preFetch);
            nodeMap.put("core:split1", split);
            nodeMap.put("agent:classify", classify);
            nodeMap.put("core:merge1", merge);

            com.apimarketplace.orchestrator.services.StepOutputService stepOutputService =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.services.StepOutputService.class);
            SplitMergeHandler handlerWithDurable = new SplitMergeHandler(contextManager);
            handlerWithDurable.setStepOutputService(stepOutputService);

            when(contextManager.findActiveContext(eq("run1"), eq("core:merge1"), eq(WORKFLOW_ITEM_INDEX), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.itemIndex()).thenReturn(0);
            when(context.itemId()).thenReturn("item1");
            when(context.runId()).thenReturn("run1");
            when(context.epoch()).thenReturn(0);
            when(context.tenantId()).thenReturn("tenant1");
            // Durable epoch snapshot ALSO contains pre-split and trigger rows - these must be excluded.
            when(stepOutputService.loadPerItemOutputsByStepKey("run1", 0, "tenant1")).thenReturn(Map.of(
                "agent:classify", Map.of(0, Map.of("cat", "a"), 1, Map.of("cat", "b"), 2, Map.of("cat", "c")),
                "mcp:pre_fetch", Map.of(0, Map.of("junk", 1)),
                "trigger:manual", Map.of(0, Map.of("junk", 2))));

            NodeExecutionResult result =
                handlerWithDurable.handleMerge("run1", "core:merge1", WORKFLOW_ITEM_INDEX, context, nodeMap);

            Map<String, Object> aggregated = (Map<String, Object>) result.output().get("aggregated_results");
            assertThat(aggregated).containsKey("agent:classify");
            assertThat(aggregated)
                .as("pre-split / trigger epoch nodes must NOT pollute the aggregate")
                .doesNotContainKey("mcp:pre_fetch")
                .doesNotContainKey("trigger:manual");
            assertThat(aggregated.get("nodes_executed"))
                .as("nodes_executed counts only the split-subgraph nodes folded in")
                .isEqualTo(1);
        }

        /**
         * Regression for the root fix (2026-07-16): the merge-scoping ancestor walk used to cap
         * at a CONSTANT 50 processed nodes, so a split body longer than 50 nodes made the
         * eligible set PARTIAL and every ancestor beyond the cap was silently excluded from the
         * durable backfill (its per-item values lost from the aggregate, WARN only). The walk is
         * now bounded by the actual graph size, so the complete eligible set is computed and no
         * ancestor goes missing. Fails on the pre-fix constant-50 code (core:n0..core:n9 absent,
         * nodes_executed 50).
         */
        @Test
        @DisplayName("Regression: a 60-node split body backfills EVERY ancestor from durable - no per-item node silently dropped beyond a 50-node cap")
        @SuppressWarnings("unchecked")
        void durableBackfillCoversSplitBodyBeyondFiftyAncestors() {
            // trigger:manual -> core:split1 -> core:n0 -> ... -> core:n59 -> core:merge1
            BaseNode trigger = new TestNode("trigger:manual", NodeType.MCP);
            BaseNode split = new TestNode("core:split1", NodeType.SPLIT);
            split.addPredecessor("trigger:manual");
            nodeMap.put("trigger:manual", trigger);
            nodeMap.put("core:split1", split);
            int chainLength = 60;
            for (int i = 0; i < chainLength; i++) {
                BaseNode n = new TestNode("core:n" + i, NodeType.MCP);
                n.addPredecessor(i == 0 ? "core:split1" : "core:n" + (i - 1));
                nodeMap.put("core:n" + i, n);
            }
            BaseNode merge = new TestNode("core:merge1", NodeType.MERGE);
            merge.addPredecessor("core:n" + (chainLength - 1));
            nodeMap.put("core:merge1", merge);

            // In-memory resultsByNode EMPTY (cross-pod / restart state): EVERY per-item value
            // must come from the durable store, so an ancestor missing from the eligible set is
            // observably absent from the aggregate.
            SplitContext splitContext = SplitContext.create("core:split1:0", List.of("a", "b"));

            com.apimarketplace.orchestrator.services.StepOutputService stepOutputService =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.services.StepOutputService.class);
            SplitMergeHandler handlerWithDurable = new SplitMergeHandler(contextManager);
            handlerWithDurable.setStepOutputService(stepOutputService);

            when(contextManager.findActiveContext(eq("run1"), eq("core:merge1"), eq(WORKFLOW_ITEM_INDEX), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.itemIndex()).thenReturn(0);
            when(context.itemId()).thenReturn("item1");
            when(context.runId()).thenReturn("run1");
            when(context.epoch()).thenReturn(0);
            when(context.tenantId()).thenReturn("tenant1");

            Map<String, Map<Integer, Object>> durable = new HashMap<>();
            for (int i = 0; i < chainLength; i++) {
                durable.put("core:n" + i, Map.of(
                    0, Map.of("v", i + "-0"),
                    1, Map.of("v", i + "-1")));
            }
            when(stepOutputService.loadPerItemOutputsByStepKey("run1", 0, "tenant1")).thenReturn(durable);

            NodeExecutionResult result =
                handlerWithDurable.handleMerge("run1", "core:merge1", WORKFLOW_ITEM_INDEX, context, nodeMap);

            Map<String, Object> aggregated = (Map<String, Object>) result.output().get("aggregated_results");
            for (int i = 0; i < chainLength; i++) {
                assertThat(aggregated)
                    .as("every split-subgraph ancestor must be in the aggregate; core:n" + i
                      + " missing means the walk bound silently dropped it")
                    .containsKey("core:n" + i);
            }
            // The node FURTHEST from the merge is the one a truncated merge walk loses first.
            assertThat((List<Object>) aggregated.get("core:n0"))
                .as("core:n0 keeps its own per-item values, recovered from the durable store")
                .containsExactly(Map.of("v", "0-0"), Map.of("v", "0-1"));
            assertThat(aggregated.get("nodes_executed"))
                .as("all 60 split-subgraph nodes folded in, none dropped beyond a cap")
                .isEqualTo(60);
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

        /**
         * Latent bug fixed in passing by the 2026-07-15 ancestor-walk refactor: the BFS used to
         * resolve a null predecessor id to null and ADD it to the ancestor set. Two predecessors
         * each carrying a null pred therefore "shared" null as a common ancestor, and the
         * split-node filter could not drop it (nodeMap.get(null) is null, so the removeIf guard
         * `ancestorNode != null` is false and null survives) - yielding a bogus branch-rejoin for
         * two genuinely unrelated paths. The walk now skips null ids at the source.
         */
        @Test
        @DisplayName("Regression: a null predecessor id is not collected as a common ancestor (no bogus branch-rejoin)")
        void shouldNotTreatNullPredecessorAsCommonAncestor() {
            // Two unrelated paths, each with a null predecessor id and no real shared ancestor.
            BaseNode stepA = new TestNode("mcp:a", NodeType.MCP);
            stepA.addPredecessor(null);
            BaseNode stepB = new TestNode("mcp:b", NodeType.MCP);
            stepB.addPredecessor(null);

            BaseNode mergeNode = new TestNode("core:merge", NodeType.MERGE);
            mergeNode.addPredecessor("mcp:a");
            mergeNode.addPredecessor("mcp:b");

            nodeMap.put("mcp:a", stepA);
            nodeMap.put("mcp:b", stepB);
            nodeMap.put("core:merge", mergeNode);

            assertThat(handler.isBranchRejoinMerge("core:merge", nodeMap))
                .as("null is not a real shared ancestor: these paths do not rejoin a branch")
                .isFalse();
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

        /**
         * Regression pin for the third consumer of the graph-size walk bound (2026-07-16):
         * classification of a merge as branch-rejoin vs split-aggregation intersects the ancestor
         * CLOSURES of its predecessors. With the old constant 50-node cap, a common ancestor
         * deeper than 50 nodes in each branch was never reached, the closures intersected to
         * empty, and a genuine branch-rejoin was MISCLASSIFIED as a split-aggregation. The
         * graph-size bound walks the full closure, so the deep common ancestor is found and the
         * classification is topologically correct on plans of any size. Fails on the pre-fix
         * constant-50 code (returns false).
         */
        @Test
        @DisplayName("Regression: a common ancestor 60 nodes deep in BOTH branches is still found -> branch-rejoin correctly classified on a >50-node plan")
        void shouldDetectBranchRejoinWithCommonAncestorBeyondFiftyNodes() {
            // classify -> a0 -> ... -> a59 -> merge
            // classify -> b0 -> ... -> b59 -> merge
            // The only shared ancestor (classify) sits 60 hops behind each merge predecessor.
            BaseNode classify = new TestNode("agent:classify", NodeType.MCP);
            nodeMap.put("agent:classify", classify);
            int depth = 60;
            for (String branch : List.of("a", "b")) {
                for (int i = 0; i < depth; i++) {
                    BaseNode n = new TestNode("mcp:" + branch + i, NodeType.MCP);
                    n.addPredecessor(i == 0 ? "agent:classify" : "mcp:" + branch + (i - 1));
                    nodeMap.put("mcp:" + branch + i, n);
                }
            }
            BaseNode mergeNode = new TestNode("core:merge", NodeType.MERGE);
            mergeNode.addPredecessor("mcp:a" + (depth - 1));
            mergeNode.addPredecessor("mcp:b" + (depth - 1));
            nodeMap.put("core:merge", mergeNode);

            assertThat(handler.isBranchRejoinMerge("core:merge", nodeMap))
                .as("the full-closure walk reaches agent:classify 60 hops back in both branches; "
                  + "a 50-capped walk missed it and misclassified this rejoin as an aggregation")
                .isTrue();
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

    @Nested
    @DisplayName("collectTransitiveAncestors() graph-size bound")
    class CollectTransitiveAncestorsBound {

        /**
         * Regression for the root fix (2026-07-16): the walk's processing bound was a CONSTANT
         * 50, smaller than legitimate large workflows, so a real 60-node chain truncated. The
         * bound is now {@code max(50, nodeMap.size() + 1)}; with the visited-dedup each distinct
         * node is processed at most once, so a plan whose predecessors all resolve in the nodeMap
         * can never exhaust it. Fails on the pre-fix constant-50 code (truncated=true, 50
         * ancestors).
         */
        @Test
        @DisplayName("Regression: a 60-node chain with a FULL nodeMap is never truncated and returns every ancestor")
        void fullNodeMapChainOfSixtyIsNeverTruncated() {
            // core:n0 <- core:n1 <- ... <- core:n59 <- core:reader, all resolvable.
            int chainLength = 60;
            for (int i = 0; i < chainLength; i++) {
                BaseNode n = new TestNode("core:n" + i, NodeType.MCP);
                if (i > 0) {
                    n.addPredecessor("core:n" + (i - 1));
                }
                nodeMap.put("core:n" + i, n);
            }
            BaseNode reader = new TestNode("core:reader", NodeType.MCP);
            reader.addPredecessor("core:n" + (chainLength - 1));
            nodeMap.put("core:reader", reader);

            SplitMergeHandler.AncestorWalk walk = SplitMergeHandler.collectTransitiveAncestors(
                "core:reader", nodeMap, java.util.Set.of());

            assertThat(walk.truncated())
                .as("visited-dedup + a bound >= the graph size: truncation is unreachable on a "
                  + "real plan, whatever its length")
                .isFalse();
            assertThat(walk.ancestors())
                .as("the COMPLETE ancestor set, including the node furthest from the start")
                .hasSize(chainLength)
                .contains("core:n0", "core:n59");
        }

        /**
         * Defensive net kept alive: the truncated flag must still fire when the bound is
         * genuinely exceeded. A real plan cannot do that (every distinct resolvable node is
         * processed at most once), so the only trigger is a pathological input: predecessor
         * lists referencing more distinct ids than the nodeMap resolves. Unresolvable ids ARE
         * enqueued and counted (they just cannot expand), so 60 of them on a 1-node map exceed
         * the 50 floor.
         */
        @Test
        @DisplayName("Defensive net: 60 unresolvable predecessor ids on a tiny nodeMap exceed the 50 floor -> truncated=true with a partial set")
        void pathologicalUnresolvableFanInStillTruncates() {
            BaseNode reader = new TestNode("core:reader", NodeType.MCP);
            int fanIn = 60;
            for (int i = 0; i < fanIn; i++) {
                reader.addPredecessor("core:ghost" + i);
            }
            nodeMap.put("core:reader", reader);
            // Bound = max(50, 1 + 1) = 50: the floor holds, it does not shrink with the map.

            SplitMergeHandler.AncestorWalk walk = SplitMergeHandler.collectTransitiveAncestors(
                "core:reader", nodeMap, java.util.Set.of());

            assertThat(walk.truncated())
                .as("60 distinct enqueued ids against the 50 floor: the walk stops with work "
                  + "still queued and must say so")
                .isTrue();
            assertThat(walk.ancestors())
                .as("exactly the floor's worth of ancestors was processed - proving the floor "
                  + "did not collapse to nodeMap.size() + 1 = 2")
                .hasSize(50);
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
