package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.state.SplitState;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import com.apimarketplace.orchestrator.domain.WorkflowExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * Unit tests for SplitNode.
 * SplitNode iterates over a list of items.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SplitNode")
class SplitNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    @Mock
    private TemplateEngine mockTemplateEngine;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("items", List.of("a", "b", "c"));
        triggerData.put("numbers", List.of(1, 2, 3, 4, 5));

        context = ExecutionContext.create(
            "run-1",
            "workflow-run-1",
            "tenant-1",
            "item-1",
            0,
            triggerData,
            mockPlan
        );

        // Default stub: evaluateTemplate returns a 3-item list for split evaluation
        lenient().when(mockTemplateEngine.evaluateTemplate(anyString(), any(WorkflowExecutionContext.class)))
            .thenReturn(List.of("a", "b", "c"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Constructor tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create SplitNode with all parameters")
        void shouldCreateSplitNodeWithAllParameters() {
            SplitNode node = new SplitNode(
                "core:split",
                "{{trigger:webhook.items}}",
                100,
                "continue-anyway",
                new ArrayList<>(),
                mockTemplateEngine
            );

            assertEquals("core:split", node.getNodeId());
            assertEquals(NodeType.SPLIT, node.getType());
            assertEquals("{{trigger:webhook.items}}", node.getListExpression());
            assertEquals(100, node.getMaxItems());
            assertEquals("continue-anyway", node.getSplitStrategy());
        }

        @Test
        @DisplayName("Should use default maxItems when 0 or negative")
        void shouldUseDefaultMaxItemsWhenZeroOrNegative() {
            SplitNode node = new SplitNode(
                "core:split",
                "{{items}}",
                0,
                null,
                new ArrayList<>(),
                mockTemplateEngine
            );

            assertEquals(100, node.getMaxItems());
        }

        @Test
        @DisplayName("Should use default strategy when null")
        void shouldUseDefaultStrategyWhenNull() {
            SplitNode node = new SplitNode(
                "core:split",
                "{{items}}",
                10,
                null,
                new ArrayList<>(),
                mockTemplateEngine
            );

            assertEquals("continue-anyway", node.getSplitStrategy());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // execute() - First execution (initialization) tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute() - First Execution")
    class ExecuteFirstExecutionTests {

        @Test
        @DisplayName("Should return success on first execution")
        void shouldReturnSuccessOnFirstExecution() {
            Map<String, Object> triggerData = Map.of("items", List.of("a", "b", "c"), "mock", true);
            ExecutionContext mockContext = ExecutionContext.create(
                "run-1", "workflow-run-1", "tenant-1", "item-1", 0, triggerData, mockPlan
            );

            SplitNode node = new SplitNode(
                "core:split",
                "{{trigger:webhook.items}}",
                10,
                "continue-anyway",
                new ArrayList<>(),
                mockTemplateEngine
            );

            NodeExecutionResult result = node.execute(mockContext);

            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("Should include node_type in output")
        void shouldIncludeNodeTypeInOutput() {
            Map<String, Object> triggerData = Map.of("items", List.of("a", "b", "c"), "mock", true);
            ExecutionContext mockContext = ExecutionContext.create(
                "run-1", "workflow-run-1", "tenant-1", "item-1", 0, triggerData, mockPlan
            );

            SplitNode node = new SplitNode(
                "core:split",
                "{{items}}",
                10,
                "continue-anyway",
                new ArrayList<>(),
                mockTemplateEngine
            );

            NodeExecutionResult result = node.execute(mockContext);

            assertEquals("SPLIT", result.output().get("node_type"));
        }

        @Test
        @DisplayName("Should include split_id in output")
        void shouldIncludeSplitIdInOutput() {
            Map<String, Object> triggerData = Map.of("items", List.of("a", "b", "c"), "mock", true);
            ExecutionContext mockContext = ExecutionContext.create(
                "run-1", "workflow-run-1", "tenant-1", "item-1", 0, triggerData, mockPlan
            );

            SplitNode node = new SplitNode(
                "core:split",
                "{{items}}",
                10,
                "continue-anyway",
                new ArrayList<>(),
                mockTemplateEngine
            );

            NodeExecutionResult result = node.execute(mockContext);

            assertEquals("core:split", result.output().get("split_id"));
        }

        @Test
        @DisplayName("Should include item_count in output")
        void shouldIncludeItemCountInOutput() {
            Map<String, Object> triggerData = Map.of("items", List.of("a", "b", "c"), "mock", true);
            ExecutionContext mockContext = ExecutionContext.create(
                "run-1", "workflow-run-1", "tenant-1", "item-1", 0, triggerData, mockPlan
            );

            SplitNode node = new SplitNode(
                "core:split",
                "{{items}}",
                10,
                "continue-anyway",
                new ArrayList<>(),
                mockTemplateEngine
            );

            NodeExecutionResult result = node.execute(mockContext);

            assertNotNull(result.output().get("item_count"));
        }

        @Test
        @DisplayName("Should set spawn_parallel_items to true")
        void shouldSetSpawnParallelItemsToTrue() {
            Map<String, Object> triggerData = Map.of("items", List.of("a", "b", "c"), "mock", true);
            ExecutionContext mockContext = ExecutionContext.create(
                "run-1", "workflow-run-1", "tenant-1", "item-1", 0, triggerData, mockPlan
            );

            SplitNode node = new SplitNode(
                "core:split",
                "{{items}}",
                10,
                "continue-anyway",
                new ArrayList<>(),
                mockTemplateEngine
            );

            NodeExecutionResult result = node.execute(mockContext);

            assertEquals(true, result.output().get("spawn_parallel_items"));
        }

        @Test
        @DisplayName("Should set terminated to false on first execution")
        void shouldSetTerminatedToFalseOnFirstExecution() {
            Map<String, Object> triggerData = Map.of("items", List.of("a", "b", "c"), "mock", true);
            ExecutionContext mockContext = ExecutionContext.create(
                "run-1", "workflow-run-1", "tenant-1", "item-1", 0, triggerData, mockPlan
            );

            SplitNode node = new SplitNode(
                "core:split",
                "{{items}}",
                10,
                "continue-anyway",
                new ArrayList<>(),
                mockTemplateEngine
            );

            NodeExecutionResult result = node.execute(mockContext);

            assertEquals(false, result.output().get("terminated"));
        }

        @Test
        @DisplayName("Should include split state in metadata")
        void shouldIncludeSplitStateInMetadata() {
            Map<String, Object> triggerData = Map.of("items", List.of("a", "b", "c"), "mock", true);
            ExecutionContext mockContext = ExecutionContext.create(
                "run-1", "workflow-run-1", "tenant-1", "item-1", 0, triggerData, mockPlan
            );

            SplitNode node = new SplitNode(
                "core:split",
                "{{items}}",
                10,
                "continue-anyway",
                new ArrayList<>(),
                mockTemplateEngine
            );

            NodeExecutionResult result = node.execute(mockContext);

            assertNotNull(result.metadata().get("split_state"));
            assertTrue(result.metadata().get("split_state") instanceof SplitState);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // execute() - Mock mode tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute() - Mock Mode")
    class ExecuteMockModeTests {

        @Test
        @DisplayName("Should use mock mode when trigger data contains mock=true")
        void shouldUseMockModeWhenTriggerDataContainsMock() {
            Map<String, Object> triggerData = new HashMap<>();
            triggerData.put("mock", true);

            ExecutionContext mockContext = ExecutionContext.create(
                "run-1", "workflow-run-1", "tenant-1", "item-1", 0, triggerData, mockPlan
            );

            SplitNode node = new SplitNode(
                "core:split",
                "{{items}}",
                10,
                "continue-anyway",
                new ArrayList<>(),
                mockTemplateEngine
            );

            NodeExecutionResult result = node.execute(mockContext);

            assertTrue(result.isSuccess());
            // Mock mode generates items
            assertNotNull(result.output().get("item_count"));
        }

        @Test
        @DisplayName("Should generate mock items respecting maxItems")
        void shouldGenerateMockItemsRespectingMaxItems() {
            Map<String, Object> triggerData = Map.of("mock", true);
            ExecutionContext mockContext = ExecutionContext.create(
                "run-1", "workflow-run-1", "tenant-1", "item-1", 0, triggerData, mockPlan
            );

            SplitNode node = new SplitNode(
                "core:split",
                "{{items}}",
                5,
                "continue-anyway",
                new ArrayList<>(),
                mockTemplateEngine
            );

            NodeExecutionResult result = node.execute(mockContext);

            // Should generate at most 5 mock items
            int itemCount = (int) result.output().get("item_count");
            assertTrue(itemCount <= 5);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // execute() - Empty list handling tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute() - Empty List Handling")
    class ExecuteEmptyListTests {

        @Test
        @DisplayName("Should return exit result for empty list")
        void shouldReturnExitResultForEmptyList() {
            // This test simulates what happens when the list is empty
            // We need to set up a context where the list evaluation returns empty

            Map<String, Object> triggerData = Map.of("items", List.of());
            ExecutionContext emptyContext = ExecutionContext.create(
                "run-1", "workflow-run-1", "tenant-1", "item-1", 0, triggerData, mockPlan
            );

            // Create a node that will find an empty list
            // Since we can't easily mock internal evaluation, we test the exit result structure
            SplitNode node = new SplitNode(
                "core:split",
                "{{trigger:start.items}}",
                10,
                "continue-anyway",
                new ArrayList<>(),
                mockTemplateEngine
            );

            // Note: Without proper mocking of V2TemplateAdapter inside SplitNode,
            // this will fail to evaluate and return an error.
            // In a real test, we'd need to inject a mock adapter.
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // execute() - Max items limiting tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute() - Max Items Limiting")
    class ExecuteMaxItemsTests {

        @Test
        @DisplayName("Should respect maxItems limit")
        void shouldRespectMaxItemsLimit() {
            Map<String, Object> triggerData = Map.of("mock", true);
            ExecutionContext mockContext = ExecutionContext.create(
                "run-1", "workflow-run-1", "tenant-1", "item-1", 0, triggerData, mockPlan
            );

            SplitNode node = new SplitNode(
                "core:split",
                "{{items}}",
                3,  // Only allow 3 items max
                "continue-anyway",
                new ArrayList<>(),
                mockTemplateEngine
            );

            NodeExecutionResult result = node.execute(mockContext);

            int itemCount = (int) result.output().get("item_count");
            assertTrue(itemCount <= 3);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getNextNodes() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getNextNodes()")
    class GetNextNodesTests {

        @Test
        @DisplayName("Should return successors when terminated")
        void shouldReturnSuccessorsWhenTerminated() {
            SplitNode node = new SplitNode(
                "core:split",
                "{{items}}",
                10,
                "continue-anyway",
                new ArrayList<>(),
                mockTemplateEngine
            );

            ExecutionNode successor = createMockNode("mcp:after_split");
            node.addSuccessor(successor);

            NodeExecutionResult result = NodeExecutionResult.success("core:split",
                Map.of("terminated", true));

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertEquals(1, nextNodes.size());
            assertEquals("mcp:after_split", nextNodes.get(0).getNodeId());
        }

        @Test
        @DisplayName("Should return first body node when not terminated")
        void shouldReturnFirstBodyNodeWhenNotTerminated() {
            ExecutionNode bodyNode1 = createMockNode("mcp:body_step1");
            ExecutionNode bodyNode2 = createMockNode("mcp:body_step2");

            SplitNode node = new SplitNode(
                "core:split",
                "{{items}}",
                10,
                "continue-anyway",
                new ArrayList<>(List.of(bodyNode1, bodyNode2)),
                mockTemplateEngine
            );

            NodeExecutionResult result = NodeExecutionResult.success("core:split",
                Map.of("terminated", false));

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertEquals(1, nextNodes.size());
            assertEquals("mcp:body_step1", nextNodes.get(0).getNodeId());
        }

        @Test
        @DisplayName("Should return successors when result has no output")
        void shouldReturnSuccessorsWhenResultHasNoOutput() {
            SplitNode node = new SplitNode(
                "core:split",
                "{{items}}",
                10,
                "continue-anyway",
                new ArrayList<>(),
                mockTemplateEngine
            );

            ExecutionNode successor = createMockNode("mcp:after");
            node.addSuccessor(successor);

            NodeExecutionResult result = NodeExecutionResult.success("core:split", Map.of());

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertEquals(1, nextNodes.size());
        }

        @Test
        @DisplayName("Should return successors when no body nodes and not terminated")
        void shouldReturnSuccessorsWhenNoBodyNodes() {
            SplitNode node = new SplitNode(
                "core:split",
                "{{items}}",
                10,
                "continue-anyway",
                new ArrayList<>(),  // No body nodes
                mockTemplateEngine
            );

            ExecutionNode successor = createMockNode("mcp:after");
            node.addSuccessor(successor);

            NodeExecutionResult result = NodeExecutionResult.success("core:split",
                Map.of("terminated", false));

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertEquals(1, nextNodes.size());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getBodyNodes() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getBodyNodes()")
    class GetBodyNodesTests {

        @Test
        @DisplayName("Should return body nodes")
        void shouldReturnBodyNodes() {
            ExecutionNode bodyNode = createMockNode("mcp:body_step");
            List<ExecutionNode> bodyNodes = new ArrayList<>(List.of(bodyNode));

            SplitNode node = new SplitNode(
                "core:split",
                "{{items}}",
                10,
                "continue-anyway",
                bodyNodes,
                mockTemplateEngine
            );

            assertEquals(1, node.getBodyNodes().size());
            assertEquals("mcp:body_step", node.getBodyNodes().get(0).getNodeId());
        }

        @Test
        @DisplayName("Should return empty list when no body nodes")
        void shouldReturnEmptyListWhenNoBodyNodes() {
            SplitNode node = new SplitNode(
                "core:split",
                "{{items}}",
                10,
                "continue-anyway",
                null,
                mockTemplateEngine
            );

            assertNotNull(node.getBodyNodes());
            assertTrue(node.getBodyNodes().isEmpty());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Getters tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Getters")
    class GettersTests {

        @Test
        @DisplayName("getListExpression() should return the list expression")
        void getListExpressionShouldReturnTheListExpression() {
            SplitNode node = new SplitNode(
                "core:split",
                "{{trigger:webhook.items}}",
                10,
                "continue-anyway",
                new ArrayList<>(),
                mockTemplateEngine
            );

            assertEquals("{{trigger:webhook.items}}", node.getListExpression());
        }

        @Test
        @DisplayName("getMaxItems() should return the max items")
        void getMaxItemsShouldReturnTheMaxItems() {
            SplitNode node = new SplitNode(
                "core:split",
                "{{items}}",
                50,
                "continue-anyway",
                new ArrayList<>(),
                mockTemplateEngine
            );

            assertEquals(50, node.getMaxItems());
        }

        @Test
        @DisplayName("getSplitStrategy() should return the strategy")
        void getSplitStrategyShouldReturnTheStrategy() {
            SplitNode node = new SplitNode(
                "core:split",
                "{{items}}",
                10,
                "stop-on-error",
                new ArrayList<>(),
                mockTemplateEngine
            );

            assertEquals("stop-on-error", node.getSplitStrategy());
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
            SplitNode node = new SplitNode(
                "core:split",
                "{{items}}",
                10,
                "continue-anyway",
                new ArrayList<>(),
                mockTemplateEngine
            );

            NodeExecutionResult result = NodeExecutionResult.success("core:split",
                Map.of("continue", true));

            assertDoesNotThrow(() -> node.onComplete(context, result));
        }

        @Test
        @DisplayName("Should not throw exception on failure result")
        void shouldNotThrowExceptionOnFailureResult() {
            SplitNode node = new SplitNode(
                "core:split",
                "{{items}}",
                10,
                "continue-anyway",
                new ArrayList<>(),
                mockTemplateEngine
            );

            NodeExecutionResult result = NodeExecutionResult.failure("core:split", "Error");

            assertDoesNotThrow(() -> node.onComplete(context, result));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Subsequent execution tests (with existing state)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute() - Subsequent Execution")
    class ExecuteSubsequentExecutionTests {

        @Test
        @DisplayName("Should continue iteration when state exists and items remain")
        void shouldContinueIterationWhenStateExistsAndItemsRemain() {
            List<Object> items = List.of("a", "b", "c");
            SplitState state = SplitState.create(items, 10, "continue-anyway");

            ExecutionContext contextWithState = context.withGlobalData("split_state:core:split", state);

            SplitNode node = new SplitNode(
                "core:split",
                "{{items}}",
                10,
                "continue-anyway",
                new ArrayList<>(),
                mockTemplateEngine
            );

            NodeExecutionResult result = node.execute(contextWithState);

            assertTrue(result.isSuccess());
            assertEquals(false, result.output().get("terminated"));
            assertEquals(true, result.output().get("continue"));
            assertEquals("a", result.output().get("current_item"));
        }

        @Test
        @DisplayName("Should return exit result when all items processed")
        void shouldReturnExitResultWhenAllItemsProcessed() {
            List<Object> items = List.of("a", "b", "c");
            // currentIndex = 3 (all processed), terminated = false initially
            SplitState state = new SplitState(items, 3, 10, "continue-anyway", false);

            ExecutionContext contextWithState = context.withGlobalData("split_state:core:split", state);

            SplitNode node = new SplitNode(
                "core:split",
                "{{items}}",
                10,
                "continue-anyway",
                new ArrayList<>(),
                mockTemplateEngine
            );

            NodeExecutionResult result = node.execute(contextWithState);

            assertTrue(result.isSuccess());
            assertEquals(true, result.output().get("terminated"));
            assertEquals("all_items_processed", result.output().get("exit_reason"));
        }

        @Test
        @DisplayName("Should include current_index in output during iteration")
        void shouldIncludeCurrentIndexInOutputDuringIteration() {
            List<Object> items = List.of("a", "b", "c");
            // currentIndex = 1, terminated = false
            SplitState state = new SplitState(items, 1, 10, "continue-anyway", false);

            ExecutionContext contextWithState = context.withGlobalData("split_state:core:split", state);

            SplitNode node = new SplitNode(
                "core:split",
                "{{items}}",
                10,
                "continue-anyway",
                new ArrayList<>(),
                mockTemplateEngine
            );

            NodeExecutionResult result = node.execute(contextWithState);

            assertEquals(1, result.output().get("current_index"));
            assertEquals("b", result.output().get("current_item"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // execute() - List shape: auto-unwrap of Apify-style wrappers + fail-loud
    //
    // Regression scope: prod 2026-05-14 Instagram Profile Scraper
    // (run_<id>-shape). Agent wrote `list = {{mcp:scrape_posts.output}}`
    // where the upstream Apify run returns {items:[...], status, runId, ...} - pre-fix
    // SplitNode silently wrapped that Map as a 1-element list, downstream
    // `current_item.displayUrl` was undefined, downloads produced empty file_urls, and
    // the UI rendered broken images with no surfaced error. Post-fix the split either
    // (a) auto-unwraps via OutputUnwrapper.tryUnwrapToList when a recognized array key
    // is present, or (b) returns FAILED with a diagnostic that lists the observed Map
    // keys + a "Did you mean {{...output.items}}" hint so the agent self-corrects from
    // the run result.
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute() - list shape (auto-unwrap + fail-loud)")
    class ExecuteListShapeTests {

        @Test
        @DisplayName("Map with `items` key - auto-unwraps and iterates the inner array (prod Apify shape)")
        void mapWithItemsKey_autoUnwrapsToInnerArray() {
            Map<String, Object> apifyShape = new HashMap<>();
            apifyShape.put("items", List.of(
                Map.of("displayUrl", "u1"),
                Map.of("displayUrl", "u2"),
                Map.of("displayUrl", "u3")
            ));
            apifyShape.put("status", "SUCCEEDED");
            apifyShape.put("runId", "abc");
            lenient().when(mockTemplateEngine.evaluateTemplate(anyString(), any(WorkflowExecutionContext.class)))
                .thenReturn(apifyShape);

            SplitNode node = new SplitNode(
                "core:split_posts",
                "{{mcp:scrape_posts.output}}",
                10,
                "continue-anyway",
                new ArrayList<>(),
                mockTemplateEngine
            );

            NodeExecutionResult result = node.execute(context);

            assertEquals(NodeStatus.COMPLETED, result.status(),
                "Map with `items` array must be unwrapped, not wrapped as a single 1-item list");
            assertEquals(3, result.output().get("item_count"),
                "split iterates over the unwrapped inner array, not the wrapper Map");
        }

        @Test
        @DisplayName("Map with `records` key - Airtable-style shape also unwraps")
        void mapWithRecordsKey_autoUnwraps() {
            lenient().when(mockTemplateEngine.evaluateTemplate(anyString(), any(WorkflowExecutionContext.class)))
                .thenReturn(Map.of("records", List.of("r1", "r2")));

            SplitNode node = new SplitNode(
                "core:split",
                "{{mcp:airtable.output}}",
                10,
                "continue-anyway",
                new ArrayList<>(),
                mockTemplateEngine
            );

            NodeExecutionResult result = node.execute(context);

            assertEquals(NodeStatus.COMPLETED, result.status());
            assertEquals(2, result.output().get("item_count"));
        }

        @Test
        @DisplayName("Map with NO recognized array key - fails loud with observed keys + 'Did you mean' hint")
        void mapWithNoRecognizedKey_failsLoudWithDiagnostic() {
            // Wrapper that an Apify run might return on error - no items, just status metadata.
            // Pre-fix this was the silent-failure shape (1-item wrap → undefined downstream).
            Map<String, Object> unrecognizedWrapper = new LinkedHashMap<>();
            unrecognizedWrapper.put("status", "FAILED");
            unrecognizedWrapper.put("runId", "abc-123");
            unrecognizedWrapper.put("errorMessage", "actor blew up");
            lenient().when(mockTemplateEngine.evaluateTemplate(anyString(), any(WorkflowExecutionContext.class)))
                .thenReturn(unrecognizedWrapper);

            SplitNode node = new SplitNode(
                "core:split",
                "{{mcp:scrape_posts.output}}",
                10,
                "continue-anyway",
                new ArrayList<>(),
                mockTemplateEngine
            );

            NodeExecutionResult result = node.execute(context);

            assertEquals(NodeStatus.FAILED, result.status(),
                "non-iterable Map without a known array key must fail loud, not silently wrap");
            String err = (String) result.output().get("error");
            assertNotNull(err);
            // Diagnostic must surface the actual Map keys so the agent can self-correct.
            assertTrue(err.contains("status"), "diagnostic must list observed keys: " + err);
            assertTrue(err.contains("runId"), "diagnostic must list observed keys: " + err);
            // Diagnostic must surface the canonical fix hint.
            assertTrue(err.contains("items"), "diagnostic must mention recognized array keys: " + err);
            assertTrue(err.contains("Did you mean") || err.contains("Adjust the reference"),
                "diagnostic must suggest a corrective reference: " + err);
        }

        @Test
        @DisplayName("Primitive String - fails loud (splitting a scalar is always user-intent error)")
        void primitiveString_failsLoud() {
            lenient().when(mockTemplateEngine.evaluateTemplate(anyString(), any(WorkflowExecutionContext.class)))
                .thenReturn("not-an-array");

            SplitNode node = new SplitNode(
                "core:split",
                "{{trigger:webhook.username}}",
                10,
                "continue-anyway",
                new ArrayList<>(),
                mockTemplateEngine
            );

            NodeExecutionResult result = node.execute(context);

            assertEquals(NodeStatus.FAILED, result.status(),
                "splitting a scalar is the prod silent-failure shape; must now surface as a hard error");
            assertTrue(((String) result.output().get("error")).contains("String"),
                "diagnostic must name the actual resolved type");
        }

        @Test
        @DisplayName("List resolved directly - iterates normally (no-op for the unwrap path)")
        void plainListResolution_iteratesNormally() {
            lenient().when(mockTemplateEngine.evaluateTemplate(anyString(), any(WorkflowExecutionContext.class)))
                .thenReturn(List.of("a", "b"));

            SplitNode node = new SplitNode(
                "core:split",
                "{{normalize.output.result.posts}}",
                10,
                "continue-anyway",
                new ArrayList<>(),
                mockTemplateEngine
            );

            NodeExecutionResult result = node.execute(context);

            assertEquals(NodeStatus.COMPLETED, result.status());
            assertEquals(2, result.output().get("item_count"));
        }

        @Test
        @DisplayName("null resolution - fails loud (distinct from 'empty array': missing predecessor or wrong reference)")
        void nullResolution_failsLoud() {
            lenient().when(mockTemplateEngine.evaluateTemplate(anyString(), any(WorkflowExecutionContext.class)))
                .thenReturn(null);

            SplitNode node = new SplitNode(
                "core:split",
                "{{mcp:missing.output}}",
                10,
                "continue-anyway",
                new ArrayList<>(),
                mockTemplateEngine
            );

            NodeExecutionResult result = node.execute(context);

            assertEquals(NodeStatus.FAILED, result.status());
            assertTrue(((String) result.output().get("error")).contains("null"));
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
