package com.apimarketplace.orchestrator.execution.v2.split;

import com.apimarketplace.orchestrator.execution.v2.nodes.BaseNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SplitContextManager")
class SplitContextManagerTest {

    private SplitContextManager manager;

    @BeforeEach
    void setUp() {
        manager = new SplitContextManager();
    }

    @Nested
    @DisplayName("toDisplayItemContext() - strips cross-pod restoration keys before the UI sees split_item_data")
    class ToDisplayItemContext {

        @Test
        @DisplayName("strips restoration keys, keeps display fields - object current_item is preserved (regression: UI showed splitNodeId)")
        void stripsRestorationKeys() {
            Map<String, Object> blob = new HashMap<>();
            blob.put("current_item", Map.of("name", "Alice"));
            blob.put("current_index", 0);
            blob.put("splitNodeId", "core:split_items");
            blob.put("items", List.of("a", "b", "c"));
            blob.put("itemIndex", 0);
            blob.put("workflowItemIndex", 0);

            Map<String, Object> view = SplitContextManager.toDisplayItemContext(blob);

            assertThat(view).containsOnlyKeys("current_item", "current_index");
            assertThat(view.get("current_item")).isEqualTo(Map.of("name", "Alice"));
            assertThat(view).doesNotContainKey("splitNodeId");
            assertThat(view).doesNotContainKey("items");
        }

        @Test
        @DisplayName("null and empty pass through unchanged")
        void nullEmptyPassthrough() {
            assertThat(SplitContextManager.toDisplayItemContext(null)).isNull();
            assertThat(SplitContextManager.toDisplayItemContext(Map.of())).isEmpty();
        }

        @Test
        @DisplayName("display-only blob (non-split / legacy) is returned with its display fields intact")
        void displayOnlyBlobUnchanged() {
            Map<String, Object> blob = new HashMap<>();
            blob.put("current_item", "Alice");
            blob.put("current_index", 2);

            Map<String, Object> view = SplitContextManager.toDisplayItemContext(blob);

            assertThat(view).containsOnlyKeys("current_item", "current_index");
            assertThat(view.get("current_item")).isEqualTo("Alice");
        }
    }

    @Nested
    @DisplayName("createContext()")
    class CreateContext {

        @Test
        @DisplayName("should create and store context")
        void shouldCreateAndStoreContext() {
            List<Object> items = List.of("a", "b", "c");

            // Legacy method uses workflowItemIndex=0, so context key is "core:split1:0"
            SplitContext context = manager.createContext("run1", "core:split1", items);

            assertThat(context).isNotNull();
            // Context key now includes workflow item index
            assertThat(context.splitNodeId()).isEqualTo("core:split1:0");
            assertThat(context.itemCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("should store context for retrieval")
        void shouldStoreContextForRetrieval() {
            manager.createContext("run1", "core:split1", List.of("a"));

            Optional<SplitContext> retrieved = manager.getContext("run1", "core:split1");

            assertThat(retrieved).isPresent();
            // Context key now includes workflow item index
            assertThat(retrieved.get().splitNodeId()).isEqualTo("core:split1:0");
        }

        @Test
        @DisplayName("should support multiple split nodes per run")
        void shouldSupportMultipleSplitPerRun() {
            manager.createContext("run1", "core:split1", List.of("a"));
            manager.createContext("run1", "core:split2", List.of("b", "c"));

            assertThat(manager.getContext("run1", "core:split1")).isPresent();
            assertThat(manager.getContext("run1", "core:split2")).isPresent();
        }

        @Test
        @DisplayName("should support multiple runs")
        void shouldSupportMultipleRuns() {
            manager.createContext("run1", "core:split1", List.of("a"));
            manager.createContext("run2", "core:split1", List.of("b"));

            assertThat(manager.getContext("run1", "core:split1").get().items())
                .containsExactly("a");
            assertThat(manager.getContext("run2", "core:split1").get().items())
                .containsExactly("b");
        }
    }

    @Nested
    @DisplayName("getContext()")
    class GetContext {

        @Test
        @DisplayName("should return empty for unknown run")
        void shouldReturnEmptyForUnknownRun() {
            Optional<SplitContext> result = manager.getContext("unknown", "core:split1");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty for unknown split")
        void shouldReturnEmptyForUnknownSplit() {
            manager.createContext("run1", "core:split1", List.of("a"));

            Optional<SplitContext> result = manager.getContext("run1", "core:unknown");

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("storeResults()")
    class StoreResults {

        @Test
        @DisplayName("should store results and update context")
        void shouldStoreResults() {
            manager.createContext("run1", "core:split1", List.of("a", "b"));
            List<Object> results = List.of("result1", "result2");

            Optional<SplitContext> updated = manager.storeResults(
                "run1", "core:split1", "mcp:step1", results);

            assertThat(updated).isPresent();
            assertThat(updated.get().getResults("mcp:step1")).containsExactly("result1", "result2");
        }

        @Test
        @DisplayName("should return empty for unknown context")
        void shouldReturnEmptyForUnknownContext() {
            Optional<SplitContext> result = manager.storeResults(
                "unknown", "core:split1", "mcp:step1", List.of());

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("storeItemResult()")
    class StoreItemResult {

        @Test
        @DisplayName("storing each item one slot at a time preserves all per-item values")
        void preservesAllPerItemSlots() {
            // The regression: chained downstream nodes (e.g. clean_email after read_email)
            // execute one item at a time inside a parallel split traversal. Without this method,
            // SplitAggregateHandler iterating splitContext.getAllResults() would never see
            // chained-downstream per-item values - yielding "same value N times" in the aggregate.
            manager.createContext("run1", "core:split1", List.of("a", "b", "c"));

            manager.storeItemResult("run1", "core:split1", 0, "core:clean", 0, 3, Map.of("id", "id-0"));
            manager.storeItemResult("run1", "core:split1", 0, "core:clean", 1, 3, Map.of("id", "id-1"));
            manager.storeItemResult("run1", "core:split1", 0, "core:clean", 2, 3, Map.of("id", "id-2"));

            List<Object> stored = manager.getContext("run1", "core:split1").orElseThrow().getResults("core:clean");
            assertThat(stored).hasSize(3);
            List<String> ids = stored.stream()
                .map(o -> (String) ((Map<?, ?>) o).get("id"))
                .toList();
            assertThat(ids).containsExactly("id-0", "id-1", "id-2");
        }

        @Test
        @DisplayName("should return empty when run/context is unknown")
        void shouldReturnEmptyForUnknownRun() {
            Optional<SplitContext> result = manager.storeItemResult(
                "unknown", "core:split1", 0, "core:clean", 0, 3, "x");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("storeItemResultByScopedKey routes by exact scoped key (no findScopedKey first-match ambiguity for nested splits)")
        void scopedKeyOverloadIsExactMatch() {
            // Two nested-split sibling contexts at the same workflowItemIndex=0:
            // - "core:inner:0/s0" spawned by outer item 0
            // - "core:inner:0/s1" spawned by outer item 1
            // Without an exact-key overload, base-key resolution + findScopedKey would route
            // both writers to whichever entry the iterator hits first. The overload prevents
            // that by demanding the caller pass the resolved scoped key.
            manager.createContext("run1", "core:inner", 0, "s0", List.of("a", "b"));
            manager.createContext("run1", "core:inner", 0, "s1", List.of("a", "b"));

            manager.storeItemResultByScopedKey("run1", "core:inner:0/s0",
                "core:clean", 0, 2, Map.of("id", "s0-i0"));
            manager.storeItemResultByScopedKey("run1", "core:inner:0/s1",
                "core:clean", 0, 2, Map.of("id", "s1-i0"));

            Map<String, SplitContext> all = manager.getAllContexts("run1");
            SplitContext s0 = all.get("core:inner:0/s0");
            SplitContext s1 = all.get("core:inner:0/s1");

            assertThat(((Map<?, ?>) s0.getResults("core:clean").get(0)).get("id")).isEqualTo("s0-i0");
            assertThat(((Map<?, ?>) s1.getResults("core:clean").get(0)).get("id")).isEqualTo("s1-i0");
        }

        @Test
        @DisplayName("storeItemResultByScopedKey returns empty when the scoped key isn't registered")
        void scopedKeyOverloadReturnsEmptyForUnknown() {
            assertThat(manager.storeItemResultByScopedKey(
                "run1", "core:missing:0", "core:clean", 0, 1, "x")).isEmpty();
        }

        @Test
        @DisplayName("parallel item storage produces a list with all slots populated (regression: race-loss)")
        void shouldNotLoseSlotsUnderConcurrency() throws Exception {
            // 31 items mirrors the production digest workflow that surfaced the bug.
            // Without atomic compute(), two threads writing different slots of the same
            // node would each base their list on a stale snapshot and the last writer wins,
            // leaving most slots null. A 31-item assertion would catch that immediately.
            manager.createContext("run1", "core:split1", java.util.Collections.nCopies(31, "x"));

            int n = 31;
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(n);
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
            try {
                for (int i = 0; i < n; i++) {
                    final int idx = i;
                    pool.submit(() -> {
                        try {
                            start.await();
                            manager.storeItemResult("run1", "core:split1", 0,
                                "core:clean", idx, n, Map.of("id", "id-" + idx));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                // Assert the latch reaches zero - without this, executor starvation/deadlock
                // would silently let the assertions below run on a partial state.
                assertThat(done.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            } finally {
                pool.shutdownNow();
            }

            List<Object> stored = manager.getContext("run1", "core:split1").orElseThrow().getResults("core:clean");
            assertThat(stored).hasSize(n);
            assertThat(stored).noneMatch(java.util.Objects::isNull);
            List<String> ids = stored.stream()
                .map(o -> (String) ((Map<?, ?>) o).get("id"))
                .toList();
            List<String> expectedIds = java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> "id-" + i).toList();
            assertThat(ids).containsExactlyInAnyOrderElementsOf(expectedIds);
        }
    }

    @Nested
    @DisplayName("findActiveContext()")
    class FindActiveContext {

        @Test
        @DisplayName("should find context when node is directly after split")
        void shouldFindContextDirectlyAfterSplit() {
            manager.createContext("run1", "core:split1", List.of("a"));

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:split1", createMockNode("core:split1", NodeType.SPLIT));
            nodeMap.put("mcp:step1", createMockNodeWithPredecessors("mcp:step1", NodeType.MCP, List.of("core:split1")));

            Optional<SplitContext> result = manager.findActiveContext("run1", "mcp:step1", nodeMap);

            assertThat(result).isPresent();
            // Context key now includes workflow item index
            assertThat(result.get().splitNodeId()).isEqualTo("core:split1:0");
        }

        @Test
        @DisplayName("should find context through multiple predecessors")
        void shouldFindContextThroughMultiplePredecessors() {
            manager.createContext("run1", "core:split1", List.of("a"));

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:split1", createMockNode("core:split1", NodeType.SPLIT));
            nodeMap.put("mcp:step1", createMockNodeWithPredecessors("mcp:step1", NodeType.MCP, List.of("core:split1")));
            nodeMap.put("mcp:step2", createMockNodeWithPredecessors("mcp:step2", NodeType.MCP, List.of("mcp:step1")));

            Optional<SplitContext> result = manager.findActiveContext("run1", "mcp:step2", nodeMap);

            assertThat(result).isPresent();
        }

        @Test
        @DisplayName("should return empty when hitting merge node")
        void shouldReturnEmptyWhenHittingMerge() {
            manager.createContext("run1", "core:split1", List.of("a"));

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:split1", createMockNode("core:split1", NodeType.SPLIT));
            nodeMap.put("mcp:step1", createMockNodeWithPredecessors("mcp:step1", NodeType.MCP, List.of("core:split1")));
            nodeMap.put("core:merge1", createMockNodeWithPredecessors("core:merge1", NodeType.MERGE, List.of("mcp:step1")));
            nodeMap.put("mcp:afterMerge", createMockNodeWithPredecessors("mcp:afterMerge", NodeType.MCP, List.of("core:merge1")));

            Optional<SplitContext> result = manager.findActiveContext("run1", "mcp:afterMerge", nodeMap);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty when no split ancestor")
        void shouldReturnEmptyWhenNoSplitAncestor() {
            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("trigger:webhook", createMockNode("trigger:webhook", NodeType.TRIGGER));
            nodeMap.put("mcp:step1", createMockNodeWithPredecessors("mcp:step1", NodeType.MCP, List.of("trigger:webhook")));

            Optional<SplitContext> result = manager.findActiveContext("run1", "mcp:step1", nodeMap);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty when no contexts exist")
        void shouldReturnEmptyWhenNoContexts() {
            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:split1", createMockNode("core:split1", NodeType.SPLIT));
            nodeMap.put("mcp:step1", createMockNodeWithPredecessors("mcp:step1", NodeType.MCP, List.of("core:split1")));

            // No context created
            Optional<SplitContext> result = manager.findActiveContext("run1", "mcp:step1", nodeMap);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("isInSplitScope()")
    class IsInSplitScope {

        @Test
        @DisplayName("should return true when in split scope")
        void shouldReturnTrueWhenInScope() {
            manager.createContext("run1", "core:split1", List.of("a"));

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:split1", createMockNode("core:split1", NodeType.SPLIT));
            nodeMap.put("mcp:step1", createMockNodeWithPredecessors("mcp:step1", NodeType.MCP, List.of("core:split1")));

            assertThat(manager.isInSplitScope("run1", "mcp:step1", nodeMap)).isTrue();
        }

        @Test
        @DisplayName("should return false when not in split scope")
        void shouldReturnFalseWhenNotInScope() {
            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("trigger:webhook", createMockNode("trigger:webhook", NodeType.TRIGGER));
            nodeMap.put("mcp:step1", createMockNodeWithPredecessors("mcp:step1", NodeType.MCP, List.of("trigger:webhook")));

            assertThat(manager.isInSplitScope("run1", "mcp:step1", nodeMap)).isFalse();
        }
    }

    @Nested
    @DisplayName("removeContext()")
    class RemoveContext {

        @Test
        @DisplayName("should remove specific context")
        void shouldRemoveContext() {
            manager.createContext("run1", "core:split1", List.of("a"));
            manager.createContext("run1", "core:split2", List.of("b"));

            manager.removeContext("run1", "core:split1");

            assertThat(manager.getContext("run1", "core:split1")).isEmpty();
            assertThat(manager.getContext("run1", "core:split2")).isPresent();
        }
    }

    @Nested
    @DisplayName("clearRun()")
    class ClearRun {

        @Test
        @DisplayName("should clear all contexts for run")
        void shouldClearAllContextsForRun() {
            manager.createContext("run1", "core:split1", List.of("a"));
            manager.createContext("run1", "core:split2", List.of("b"));
            manager.createContext("run2", "core:split1", List.of("c"));

            manager.clearRun("run1");

            assertThat(manager.hasContexts("run1")).isFalse();
            assertThat(manager.hasContexts("run2")).isTrue();
        }
    }

    @Nested
    @DisplayName("hasContexts()")
    class HasContexts {

        @Test
        @DisplayName("should return true when contexts exist")
        void shouldReturnTrueWhenContextsExist() {
            manager.createContext("run1", "core:split1", List.of("a"));

            assertThat(manager.hasContexts("run1")).isTrue();
        }

        @Test
        @DisplayName("should return false when no contexts")
        void shouldReturnFalseWhenNoContexts() {
            assertThat(manager.hasContexts("unknown")).isFalse();
        }
    }

    @Nested
    @DisplayName("findActiveContext() - merge traversal")
    class FindActiveContextMergeTraversal {

        @Test
        @DisplayName("should continue through branch-rejoin merge (fork → [A, B] → merge → next)")
        void shouldContinueThroughBranchRejoinMerge() {
            // Topology: split → fork → [stepA, stepB] → merge → nextStep
            // The merge rejoins fork branches (branch-rejoin) - split scope should continue through it.
            manager.createContext("run1", "core:split1", List.of("x", "y", "z"));

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:split1", createMockNode("core:split1", NodeType.SPLIT));
            nodeMap.put("core:fork1", createMockNodeWithPredecessors("core:fork1", NodeType.FORK, List.of("core:split1")));
            nodeMap.put("mcp:stepA", createMockNodeWithPredecessors("mcp:stepA", NodeType.MCP, List.of("core:fork1")));
            nodeMap.put("mcp:stepB", createMockNodeWithPredecessors("mcp:stepB", NodeType.MCP, List.of("core:fork1")));
            // Merge has two predecessors from the same fork → branch-rejoin
            nodeMap.put("core:merge1", createMockNodeWithPredecessors("core:merge1", NodeType.MERGE, List.of("mcp:stepA", "mcp:stepB")));
            nodeMap.put("mcp:next", createMockNodeWithPredecessors("mcp:next", NodeType.MCP, List.of("core:merge1")));

            // Node after branch-rejoin merge should still be in split scope
            Optional<SplitContext> result = manager.findActiveContext("run1", "mcp:next", 0, nodeMap);

            assertThat(result).isPresent();
            assertThat(result.get().itemCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("should stop at split-aggregation merge (split → [items] → merge)")
        void shouldStopAtSplitAggregationMerge() {
            // Topology: split → step → merge (single predecessor → aggregation merge)
            manager.createContext("run1", "core:split1", List.of("x", "y"));

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:split1", createMockNode("core:split1", NodeType.SPLIT));
            nodeMap.put("mcp:step1", createMockNodeWithPredecessors("mcp:step1", NodeType.MCP, List.of("core:split1")));
            // Merge with single predecessor → split-aggregation (not branch-rejoin)
            nodeMap.put("core:merge1", createMockNodeWithPredecessors("core:merge1", NodeType.MERGE, List.of("mcp:step1")));
            nodeMap.put("mcp:after_merge", createMockNodeWithPredecessors("mcp:after_merge", NodeType.MCP, List.of("core:merge1")));

            // Node after split-aggregation merge should NOT be in split scope
            Optional<SplitContext> result = manager.findActiveContext("run1", "mcp:after_merge", 0, nodeMap);

            assertThat(result).isEmpty();
        }
    }

    // Helper methods to create mock nodes

    private ExecutionNode createMockNode(String nodeId, NodeType type) {
        return new TestNode(nodeId, type, List.of());
    }

    private ExecutionNode createMockNodeWithPredecessors(String nodeId, NodeType type, List<String> predecessors) {
        return new TestNode(nodeId, type, predecessors);
    }

    /**
     * Simple test node implementation that properly delegates to type-based checks.
     */
    private static class TestNode extends BaseNode {
        TestNode(String nodeId, NodeType type, List<String> predecessors) {
            super(nodeId, type);
            this.setPredecessors(predecessors);
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
        public com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult execute(
                com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext context) {
            return null;
        }
    }
}
