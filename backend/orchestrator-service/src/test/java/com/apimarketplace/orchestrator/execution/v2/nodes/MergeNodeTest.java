package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.nodes.merge.MergeStrategy;
import com.apimarketplace.orchestrator.execution.v2.nodes.merge.Queue1To1Strategy;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for MergeNode.
 * MergeNode waits for multiple branches to complete before continuing.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MergeNode")
class MergeNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    @Mock
    private MergeStrategy mockStrategy;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("data", "value");

        context = ExecutionContext.create(
            "run-1",
            "workflow-run-1",
            "tenant-1",
            "item-1",
            0,
            triggerData,
            mockPlan
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Constructor tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create MergeNode with all parameters")
        void shouldCreateMergeNodeWithAllParameters() {
            List<String> sources = List.of("mcp:step1", "mcp:step2");
            MergeNode node = new MergeNode("core:merge", sources, mockStrategy);

            assertEquals("core:merge", node.getNodeId());
            assertEquals(NodeType.MERGE, node.getType());
            assertEquals(2, node.getSourceNodeIds().size());
            assertEquals(mockStrategy, node.getStrategy());
        }

        @Test
        @DisplayName("Should create MergeNode with default Queue1To1Strategy")
        void shouldCreateMergeNodeWithDefaultQueue1To1Strategy() {
            List<String> sources = List.of("mcp:step1", "mcp:step2");
            MergeNode node = new MergeNode("core:merge", sources);

            assertNotNull(node.getStrategy());
            assertTrue(node.getStrategy() instanceof Queue1To1Strategy);
        }

        @Test
        @DisplayName("Should handle null sourceNodeIds")
        void shouldHandleNullSourceNodeIds() {
            MergeNode node = new MergeNode("core:merge", null, mockStrategy);

            assertNotNull(node.getSourceNodeIds());
            assertTrue(node.getSourceNodeIds().isEmpty());
        }

        @Test
        @DisplayName("Should handle null strategy by using default")
        void shouldHandleNullStrategyByUsingDefault() {
            List<String> sources = List.of("mcp:step1");
            MergeNode node = new MergeNode("core:merge", sources, null);

            assertNotNull(node.getStrategy());
            assertTrue(node.getStrategy() instanceof Queue1To1Strategy);
        }

        @Test
        @DisplayName("Should create MergeNode using builder")
        void shouldCreateMergeNodeUsingBuilder() {
            MergeNode node = MergeNode.builder()
                .nodeId("core:wait_all")
                .sourceNodeIds(List.of("mcp:a", "mcp:b", "mcp:c"))
                .strategy(mockStrategy)
                .build();

            assertEquals("core:wait_all", node.getNodeId());
            assertEquals(3, node.getSourceNodeIds().size());
        }

        @Test
        @DisplayName("Should add sources incrementally using builder")
        void shouldAddSourcesIncrementallyUsingBuilder() {
            MergeNode node = MergeNode.builder()
                .nodeId("core:merge")
                .addSource("mcp:step1")
                .addSource("mcp:step2")
                .build();

            assertEquals(2, node.getSourceNodeIds().size());
            assertTrue(node.getSourceNodeIds().contains("mcp:step1"));
            assertTrue(node.getSourceNodeIds().contains("mcp:step2"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // canExecute() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("canExecute()")
    class CanExecuteTests {

        @Test
        @DisplayName("Should return false when sources incomplete")
        void shouldReturnFalseWhenSourcesIncomplete() {
            when(mockStrategy.canMerge(any(), any())).thenReturn(false);

            MergeNode node = new MergeNode(
                "core:merge",
                List.of("mcp:step1", "mcp:step2"),
                mockStrategy
            );

            assertFalse(node.canExecute(context));
        }

        @Test
        @DisplayName("Should return true when all sources complete")
        void shouldReturnTrueWhenAllSourcesComplete() {
            when(mockStrategy.canMerge(any(), any())).thenReturn(true);

            MergeNode node = new MergeNode(
                "core:merge",
                List.of("mcp:step1", "mcp:step2"),
                mockStrategy
            );

            assertTrue(node.canExecute(context));
        }

        @Test
        @DisplayName("Should delegate to strategy for canMerge check")
        void shouldDelegateToStrategyForCanMergeCheck() {
            List<String> sources = List.of("mcp:step1", "mcp:step2");
            when(mockStrategy.canMerge(eq(sources), any())).thenReturn(true);

            MergeNode node = new MergeNode("core:merge", sources, mockStrategy);

            assertTrue(node.canExecute(context));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // canExecute() - With real Queue1To1Strategy tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("canExecute() - Queue1To1Strategy")
    class CanExecuteQueue1To1Tests {

        @Test
        @DisplayName("Should return false when not all sources completed")
        void shouldReturnFalseWhenNotAllSourcesCompleted() {
            MergeNode node = new MergeNode(
                "core:merge",
                List.of("mcp:step1", "mcp:step2")
            );

            // Mark only step1 as completed
            ExecutionContext ctx = context.withResult("mcp:step1",
                NodeExecutionResult.success("mcp:step1", Map.of("data", "value1")));

            assertFalse(node.canExecute(ctx));
        }

        @Test
        @DisplayName("Should return true when all sources completed (success)")
        void shouldReturnTrueWhenAllSourcesCompletedSuccess() {
            MergeNode node = new MergeNode(
                "core:merge",
                List.of("mcp:step1", "mcp:step2")
            );

            // Mark both steps as completed
            ExecutionContext ctx = context
                .withResult("mcp:step1", NodeExecutionResult.success("mcp:step1", Map.of("data", "value1")))
                .withResult("mcp:step2", NodeExecutionResult.success("mcp:step2", Map.of("data", "value2")));

            assertTrue(node.canExecute(ctx));
        }

        @Test
        @DisplayName("Should count SKIPPED as completed/resolved")
        void shouldCountSkippedAsCompleted() {
            MergeNode node = new MergeNode(
                "core:merge",
                List.of("mcp:step1", "mcp:step2")
            );

            // Mark step1 as completed, step2 as skipped
            ExecutionContext ctx = context
                .withResult("mcp:step1", NodeExecutionResult.success("mcp:step1", Map.of()))
                .withResult("mcp:step2", NodeExecutionResult.skipped("mcp:step2", "Branch not taken"));

            assertTrue(node.canExecute(ctx));
        }

        @Test
        @DisplayName("Should count FAILED as completed/resolved")
        void shouldCountFailedAsCompleted() {
            MergeNode node = new MergeNode(
                "core:merge",
                List.of("mcp:step1", "mcp:step2")
            );

            // Mark step1 as completed, step2 as failed
            ExecutionContext ctx = context
                .withResult("mcp:step1", NodeExecutionResult.success("mcp:step1", Map.of()))
                .withResult("mcp:step2", NodeExecutionResult.failure("mcp:step2", "Error occurred"));

            assertTrue(node.canExecute(ctx));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // execute() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute()")
    class ExecuteTests {

        @Test
        @DisplayName("Should return success when strategy merges successfully")
        void shouldReturnSuccessWhenStrategyMergesSuccessfully() {
            // Use HashMap (mutable) instead of Map.of() (immutable) because MergeNode.execute() adds keys
            Map<String, Object> mergedData = new HashMap<>();
            mergedData.put("strategy", "QUEUE_1_TO_1");
            mergedData.put("source_count", 2);
            mergedData.put("success_count", 2);
            mergedData.put("merged_items", List.of("a", "b"));
            mergedData.put("item_count", 2);

            when(mockStrategy.name()).thenReturn("QUEUE_1_TO_1");
            when(mockStrategy.shouldSkip(any(), any())).thenReturn(false);
            when(mockStrategy.merge(any(), any())).thenReturn(mergedData);

            MergeNode node = new MergeNode(
                "core:merge",
                List.of("mcp:step1", "mcp:step2"),
                mockStrategy
            );

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("QUEUE_1_TO_1", result.output().get("strategy"));
            assertEquals(2, result.output().get("source_count"));
        }

        @Test
        @DisplayName("Should return skipped when strategy says to skip")
        void shouldReturnSkippedWhenStrategySaysToSkip() {
            when(mockStrategy.name()).thenReturn("QUEUE_1_TO_1");
            when(mockStrategy.shouldSkip(any(), any())).thenReturn(true);
            when(mockStrategy.getSkipReason(any(), any())).thenReturn("All sources failed");

            MergeNode node = new MergeNode(
                "core:merge",
                List.of("mcp:step1", "mcp:step2"),
                mockStrategy
            );

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSkipped());
            assertEquals("All sources failed", result.errorMessage().orElse(""));
        }

        @Test
        @DisplayName("Should return failure when strategy throws exception")
        void shouldReturnFailureWhenStrategyThrowsException() {
            when(mockStrategy.name()).thenReturn("QUEUE_1_TO_1");
            when(mockStrategy.shouldSkip(any(), any())).thenReturn(false);
            when(mockStrategy.merge(any(), any())).thenThrow(new RuntimeException("Merge error"));

            MergeNode node = new MergeNode(
                "core:merge",
                List.of("mcp:step1", "mcp:step2"),
                mockStrategy
            );

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().orElse("").contains("Merge error"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // execute() - With real Queue1To1Strategy tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute() - Queue1To1Strategy")
    class ExecuteQueue1To1Tests {

        @Test
        @DisplayName("Should merge data from all successful sources")
        void shouldMergeDataFromAllSuccessfulSources() {
            MergeNode node = new MergeNode(
                "core:merge",
                List.of("mcp:step1", "mcp:step2")
            );

            ExecutionContext ctx = context
                .withResult("mcp:step1", NodeExecutionResult.success("mcp:step1", Map.of("data1", "value1")))
                .withResult("mcp:step2", NodeExecutionResult.success("mcp:step2", Map.of("data2", "value2")));

            NodeExecutionResult result = node.execute(ctx);

            assertTrue(result.isSuccess());
            assertEquals("QUEUE_1_TO_1", result.output().get("strategy"));
            assertEquals(2, result.output().get("success_count"));
        }

        @Test
        @DisplayName("Should skip if all sources failed")
        void shouldSkipIfAllSourcesFailed() {
            MergeNode node = new MergeNode(
                "core:merge",
                List.of("mcp:step1", "mcp:step2")
            );

            ExecutionContext ctx = context
                .withResult("mcp:step1", NodeExecutionResult.failure("mcp:step1", "Error 1"))
                .withResult("mcp:step2", NodeExecutionResult.failure("mcp:step2", "Error 2"));

            NodeExecutionResult result = node.execute(ctx);

            assertTrue(result.isSkipped());
        }

        @Test
        @DisplayName("Should skip if all sources skipped")
        void shouldSkipIfAllSourcesSkipped() {
            MergeNode node = new MergeNode(
                "core:merge",
                List.of("mcp:step1", "mcp:step2")
            );

            ExecutionContext ctx = context
                .withResult("mcp:step1", NodeExecutionResult.skipped("mcp:step1", "Branch not taken"))
                .withResult("mcp:step2", NodeExecutionResult.skipped("mcp:step2", "Branch not taken"));

            NodeExecutionResult result = node.execute(ctx);

            assertTrue(result.isSkipped());
        }

        @Test
        @DisplayName("Should succeed if at least one source succeeded")
        void shouldSucceedIfAtLeastOneSourceSucceeded() {
            MergeNode node = new MergeNode(
                "core:merge",
                List.of("mcp:step1", "mcp:step2")
            );

            ExecutionContext ctx = context
                .withResult("mcp:step1", NodeExecutionResult.success("mcp:step1", Map.of("data", "value")))
                .withResult("mcp:step2", NodeExecutionResult.failure("mcp:step2", "Error"));

            NodeExecutionResult result = node.execute(ctx);

            assertTrue(result.isSuccess());
            assertEquals(1, result.output().get("success_count"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getNextNodes() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getNextNodes()")
    class GetNextNodesTests {

        @Test
        @DisplayName("Should return all successors")
        void shouldReturnAllSuccessors() {
            MergeNode node = new MergeNode(
                "core:merge",
                List.of("mcp:step1", "mcp:step2")
            );

            ExecutionNode successor1 = createMockNode("mcp:after1");
            ExecutionNode successor2 = createMockNode("mcp:after2");
            node.addSuccessor(successor1);
            node.addSuccessor(successor2);

            NodeExecutionResult result = NodeExecutionResult.success("core:merge", Map.of());

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertEquals(2, nextNodes.size());
        }

        @Test
        @DisplayName("Should return successors even on skipped result")
        void shouldReturnSuccessorsEvenOnSkippedResult() {
            MergeNode node = new MergeNode(
                "core:merge",
                List.of("mcp:step1", "mcp:step2")
            );

            ExecutionNode successor = createMockNode("mcp:after");
            node.addSuccessor(successor);

            NodeExecutionResult result = NodeExecutionResult.skipped("core:merge", "Skipped");

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertEquals(1, nextNodes.size());
        }

        @Test
        @DisplayName("Should return empty list when no successors")
        void shouldReturnEmptyListWhenNoSuccessors() {
            MergeNode node = new MergeNode(
                "core:merge",
                List.of("mcp:step1", "mcp:step2")
            );

            NodeExecutionResult result = NodeExecutionResult.success("core:merge", Map.of());

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertTrue(nextNodes.isEmpty());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Implicit Merge Detection tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Implicit Merge Detection")
    class ImplicitMergeTests {

        @Test
        @DisplayName("isImplicitMerge() should return true when multiple predecessors")
        void isImplicitMergeShouldReturnTrueWhenMultiplePredecessors() {
            MergeNode node = new MergeNode(
                "core:merge",
                List.of("mcp:step1", "mcp:step2")
            );

            node.addPredecessor("mcp:step1");
            node.addPredecessor("mcp:step2");

            assertTrue(node.isImplicitMerge());
        }

        @Test
        @DisplayName("isImplicitMerge() should return false when single predecessor")
        void isImplicitMergeShouldReturnFalseWhenSinglePredecessor() {
            MergeNode node = new MergeNode(
                "core:merge",
                List.of("mcp:step1")
            );

            node.addPredecessor("mcp:step1");

            assertFalse(node.isImplicitMerge());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Getters tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Getters")
    class GettersTests {

        @Test
        @DisplayName("getSourceNodeIds() should return source node IDs")
        void getSourceNodeIdsShouldReturnSourceNodeIds() {
            List<String> sources = List.of("mcp:step1", "mcp:step2", "mcp:step3");
            MergeNode node = new MergeNode("core:merge", sources);

            assertEquals(3, node.getSourceNodeIds().size());
            assertTrue(node.getSourceNodeIds().contains("mcp:step1"));
            assertTrue(node.getSourceNodeIds().contains("mcp:step2"));
            assertTrue(node.getSourceNodeIds().contains("mcp:step3"));
        }

        @Test
        @DisplayName("getStrategy() should return the strategy")
        void getStrategyShouldReturnTheStrategy() {
            MergeNode node = new MergeNode(
                "core:merge",
                List.of("mcp:step1"),
                mockStrategy
            );

            assertEquals(mockStrategy, node.getStrategy());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // onComplete() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("onComplete()")
    class OnCompleteTests {

        @Test
        @DisplayName("Should not throw exception on success result")
        void shouldNotThrowExceptionOnSuccessResult() {
            MergeNode node = new MergeNode(
                "core:merge",
                List.of("mcp:step1")
            );

            NodeExecutionResult result = NodeExecutionResult.success("core:merge", Map.of());

            assertDoesNotThrow(() -> node.onComplete(context, result));
        }

        @Test
        @DisplayName("Should not throw exception on failure result")
        void shouldNotThrowExceptionOnFailureResult() {
            MergeNode node = new MergeNode(
                "core:merge",
                List.of("mcp:step1")
            );

            NodeExecutionResult result = NodeExecutionResult.failure("core:merge", "Error");

            assertDoesNotThrow(() -> node.onComplete(context, result));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helper methods
    // ═══════════════════════════════════════════════════════════════════════════

    private ExecutionNode createMockNode(String nodeId) {
        return new BaseNode(nodeId, NodeType.MCP) {
            @Override
            public NodeExecutionResult execute(ExecutionContext context) {
                return NodeExecutionResult.success(nodeId, Map.of());
            }
        };
    }
}
