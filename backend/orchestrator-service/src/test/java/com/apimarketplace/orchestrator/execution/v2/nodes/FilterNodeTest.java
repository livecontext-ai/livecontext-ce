package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.state.ExecutionState;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

/**
 * Unit tests for FilterNode.
 * FilterNode keeps only items matching specified conditions with AND/OR mode.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FilterNode")
class FilterNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    @Mock
    private V2TemplateAdapter mockTemplateAdapter;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("name", "John");

        context = new ExecutionContext(
            "run-1",
            "workflow-run-1",
            "tenant-1",
            "item-1",
            0,
            null, 0, 0,
            new HashMap<>(),
            new HashMap<>(triggerData),
            ExecutionState.create(),
            mockPlan
        );
    }

    // ===============================================================
    // Helper: build a FilterNode with inputExpression and mock adapter
    // ===============================================================

    private FilterNode buildNode(List<Core.FilterCondition> conditions, String mode, String inputExpr) {
        FilterNode node = FilterNode.builder()
            .nodeId("core:filter")
            .conditions(conditions)
            .mode(mode)
            .inputExpression(inputExpr)
            .build();
        node.setTemplateAdapter(mockTemplateAdapter);
        return node;
    }

    private void mockResolvedItems(List<Map<String, Object>> items) {
        when(mockTemplateAdapter.resolveTemplates(anyMap(), any(ExecutionContext.class)))
            .thenReturn(Map.of("__input__", items));
    }

    // ===============================================================
    // Constructor tests
    // ===============================================================

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create FilterNode with nodeId, conditions and mode")
        void shouldCreateFilterNodeWithNodeIdConditionsAndMode() {
            List<Core.FilterCondition> conditions = List.of(
                new Core.FilterCondition("status", "equals", "active")
            );

            FilterNode node = new FilterNode("core:filter", conditions, "and");

            assertEquals("core:filter", node.getNodeId());
            assertEquals(NodeType.FILTER, node.getType());
            assertEquals(1, node.getConditions().size());
            assertEquals("and", node.getMode());
        }

        @Test
        @DisplayName("Should handle null conditions")
        void shouldHandleNullConditions() {
            FilterNode node = new FilterNode("core:filter", null, "and");

            assertNotNull(node.getConditions());
            assertTrue(node.getConditions().isEmpty());
        }

        @Test
        @DisplayName("Should default mode to 'and' when null")
        void shouldDefaultModeToAndWhenNull() {
            FilterNode node = new FilterNode("core:filter", List.of(), null);

            assertEquals("and", node.getMode());
        }

        @Test
        @DisplayName("Should create FilterNode using builder with inputExpression")
        void shouldCreateFilterNodeUsingBuilder() {
            List<Core.FilterCondition> conditions = List.of(
                new Core.FilterCondition("name", "equals", "John")
            );

            FilterNode node = FilterNode.builder()
                .nodeId("core:my_filter")
                .conditions(conditions)
                .mode("or")
                .inputExpression("{{mcp:step.output.items}}")
                .build();

            assertEquals("core:my_filter", node.getNodeId());
            assertEquals(1, node.getConditions().size());
            assertEquals("or", node.getMode());
            assertEquals("{{mcp:step.output.items}}", node.getInputExpression());
        }
    }

    // ===============================================================
    // execute() - null/blank inputExpression
    // ===============================================================

    @Nested
    @DisplayName("execute() - Missing inputExpression")
    class ExecuteMissingInputExpressionTests {

        @Test
        @DisplayName("Should fail when inputExpression is null")
        void shouldFailWhenInputExpressionIsNull() {
            FilterNode node = new FilterNode("core:filter", List.of(), "and", null);
            node.setTemplateAdapter(mockTemplateAdapter);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("Input expression is required"));
        }

        @Test
        @DisplayName("Should fail when inputExpression is blank")
        void shouldFailWhenInputExpressionIsBlank() {
            FilterNode node = new FilterNode("core:filter", List.of(), "and", "   ");
            node.setTemplateAdapter(mockTemplateAdapter);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("Input expression is required"));
        }
    }

    // ===============================================================
    // execute() - AND mode with inputExpression
    // ===============================================================

    @Nested
    @DisplayName("execute() - AND mode")
    class ExecuteAndModeTests {

        @Test
        @DisplayName("Should keep items matching all conditions in AND mode")
        void shouldKeepItemsMatchingAllConditions() {
            List<Core.FilterCondition> conditions = List.of(
                new Core.FilterCondition("status", "equals", "active"),
                new Core.FilterCondition("name", "equals", "Alice")
            );

            FilterNode node = buildNode(conditions, "and", "{{mcp:step.output.items}}");
            mockResolvedItems(List.of(
                Map.of("name", "Alice", "status", "active", "score", 90),
                Map.of("name", "Bob", "status", "active", "score", 50),
                Map.of("name", "Alice", "status", "inactive", "score", 70)
            ));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
            assertEquals(1, items.size());
            assertEquals("Alice", items.get(0).get("name"));
            assertEquals("active", items.get(0).get("status"));
        }

        @Test
        @DisplayName("Should reject items not matching all conditions in AND mode")
        void shouldRejectItemsNotMatchingAllConditions() {
            List<Core.FilterCondition> conditions = List.of(
                new Core.FilterCondition("status", "equals", "active"),
                new Core.FilterCondition("score", "greaterThan", "80")
            );

            FilterNode node = buildNode(conditions, "and", "{{mcp:step.output.items}}");
            mockResolvedItems(List.of(
                Map.of("name", "Alice", "status", "active", "score", "90"),
                Map.of("name", "Bob", "status", "active", "score", "50")
            ));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rejected = (List<Map<String, Object>>) result.output().get("rejected_items");
            assertEquals(1, items.size());
            assertEquals("Alice", items.get(0).get("name"));
            assertEquals(1, rejected.size());
            assertEquals("Bob", rejected.get(0).get("name"));
        }
    }

    // ===============================================================
    // execute() - OR mode with inputExpression
    // ===============================================================

    @Nested
    @DisplayName("execute() - OR mode")
    class ExecuteOrModeTests {

        @Test
        @DisplayName("Should keep items matching any condition in OR mode")
        void shouldKeepItemsMatchingAnyCondition() {
            List<Core.FilterCondition> conditions = List.of(
                new Core.FilterCondition("name", "equals", "Alice"),
                new Core.FilterCondition("score", "greaterThan", "80")
            );

            FilterNode node = buildNode(conditions, "or", "{{mcp:step.output.items}}");
            mockResolvedItems(List.of(
                Map.of("name", "Alice", "score", "60"),
                Map.of("name", "Bob", "score", "90"),
                Map.of("name", "Charlie", "score", "50")
            ));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
            assertEquals(2, items.size()); // Alice (name match) + Bob (score match)
        }

        @Test
        @DisplayName("Should reject items matching no conditions in OR mode")
        void shouldRejectItemsMatchingNoConditions() {
            List<Core.FilterCondition> conditions = List.of(
                new Core.FilterCondition("name", "equals", "Nobody"),
                new Core.FilterCondition("score", "greaterThan", "100")
            );

            FilterNode node = buildNode(conditions, "or", "{{mcp:step.output.items}}");
            mockResolvedItems(List.of(
                Map.of("name", "Alice", "score", "60"),
                Map.of("name", "Bob", "score", "90")
            ));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
            assertTrue(items.isEmpty());
            assertEquals(false, result.output().get("matched"));
        }
    }

    // ===============================================================
    // execute() - No items match
    // ===============================================================

    @Nested
    @DisplayName("execute() - No items match")
    class ExecuteNoMatchTests {

        @Test
        @DisplayName("Should return empty items when no items match")
        void shouldReturnEmptyItemsWhenNoMatch() {
            List<Core.FilterCondition> conditions = List.of(
                new Core.FilterCondition("status", "equals", "deleted")
            );

            FilterNode node = buildNode(conditions, "and", "{{mcp:step.output.items}}");
            mockResolvedItems(List.of(
                Map.of("name", "Alice", "status", "active"),
                Map.of("name", "Bob", "status", "inactive")
            ));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
            assertTrue(items.isEmpty());
            assertEquals(false, result.output().get("matched"));
            assertEquals(0, result.output().get("count"));
            assertEquals(2, result.output().get("rejected_count"));
        }
    }

    // ===============================================================
    // execute() - All items match
    // ===============================================================

    @Nested
    @DisplayName("execute() - All items match")
    class ExecuteAllMatchTests {

        @Test
        @DisplayName("Should return all items when all match")
        void shouldReturnAllItemsWhenAllMatch() {
            List<Core.FilterCondition> conditions = List.of(
                new Core.FilterCondition("status", "equals", "active")
            );

            FilterNode node = buildNode(conditions, "and", "{{mcp:step.output.items}}");
            mockResolvedItems(List.of(
                Map.of("name", "Alice", "status", "active"),
                Map.of("name", "Bob", "status", "active")
            ));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
            assertEquals(2, items.size());
            assertEquals(true, result.output().get("matched"));
            assertEquals(2, result.output().get("count"));
            assertEquals(0, result.output().get("rejected_count"));
        }
    }

    // ===============================================================
    // execute() - Empty list
    // ===============================================================

    @Nested
    @DisplayName("execute() - Empty list")
    class ExecuteEmptyListTests {

        @Test
        @DisplayName("Should return empty result for empty input list")
        void shouldReturnEmptyResultForEmptyList() {
            List<Core.FilterCondition> conditions = List.of(
                new Core.FilterCondition("name", "equals", "Alice")
            );

            FilterNode node = buildNode(conditions, "and", "{{mcp:step.output.items}}");
            mockResolvedItems(List.of());

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
            assertTrue(items.isEmpty());
            assertEquals(false, result.output().get("matched"));
            assertEquals(0, result.output().get("count"));
            assertEquals(0, result.output().get("original_count"));
        }
    }

    // ===============================================================
    // execute() - templateAdapter throws
    // ===============================================================

    @Nested
    @DisplayName("execute() - templateAdapter throws")
    class ExecuteAdapterThrowsTests {

        @Test
        @DisplayName("Should return failure when templateAdapter throws")
        void shouldReturnFailureWhenAdapterThrows() {
            FilterNode node = buildNode(List.of(), "and", "{{mcp:step.output.items}}");
            when(mockTemplateAdapter.resolveTemplates(anyMap(), any(ExecutionContext.class)))
                .thenThrow(new RuntimeException("Resolution failed"));

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("Resolution failed"));
        }
    }

    // ===============================================================
    // execute() - Output metadata
    // ===============================================================

    @Nested
    @DisplayName("execute() - Metadata")
    class ExecuteMetadataTests {

        @Test
        @DisplayName("Should include mandatory metadata fields")
        void shouldIncludeMandatoryMetadataFields() {
            List<Core.FilterCondition> conditions = List.of(
                new Core.FilterCondition("name", "equals", "Alice")
            );

            FilterNode node = buildNode(conditions, "and", "{{mcp:step.output.items}}");
            mockResolvedItems(List.of(
                Map.of("name", "Alice")
            ));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("FILTER", result.output().get("node_type"));
            assertEquals(0, result.output().get("item_index"));
            assertEquals(0, result.output().get("itemIndex"));
            assertEquals("item-1", result.output().get("item_id"));
            assertNotNull(result.output().get("resolved_params"));
        }

        @Test
        @DisplayName("Should include filter mode and conditions count in output")
        void shouldIncludeFilterModeAndConditionsCountInOutput() {
            List<Core.FilterCondition> conditions = List.of(
                new Core.FilterCondition("name", "equals", "Alice"),
                new Core.FilterCondition("status", "equals", "active")
            );

            FilterNode node = buildNode(conditions, "or", "{{mcp:step.output.items}}");
            mockResolvedItems(List.of(
                Map.of("name", "Alice", "status", "active")
            ));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("or", result.output().get("filter_mode"));
            assertEquals(2, result.output().get("conditions_evaluated"));
        }

        @Test
        @DisplayName("Should include item count and original count")
        void shouldIncludeItemCountAndOriginalCount() {
            List<Core.FilterCondition> conditions = List.of(
                new Core.FilterCondition("status", "equals", "active")
            );

            FilterNode node = buildNode(conditions, "and", "{{mcp:step.output.items}}");
            mockResolvedItems(List.of(
                Map.of("name", "Alice", "status", "active"),
                Map.of("name", "Bob", "status", "inactive"),
                Map.of("name", "Charlie", "status", "active")
            ));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(2, result.output().get("count"));
            assertEquals(1, result.output().get("rejected_count"));
            assertEquals(3, result.output().get("original_count"));
        }
    }

    // ===============================================================
    // execute() - Operator tests (via list items)
    // ===============================================================

    @Nested
    @DisplayName("execute() - Operators")
    class ExecuteOperatorTests {

        @Test
        @DisplayName("Should evaluate 'equals' operator on list items")
        void shouldEvaluateEqualsOperator() {
            FilterNode node = buildNode(
                List.of(new Core.FilterCondition("name", "equals", "Alice")),
                "and", "{{mcp:step.output.items}}");
            mockResolvedItems(List.of(
                Map.of("name", "Alice"),
                Map.of("name", "Bob")
            ));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(1, result.output().get("count"));
        }

        @Test
        @DisplayName("Should evaluate 'contains' operator on list items")
        void shouldEvaluateContainsOperator() {
            FilterNode node = buildNode(
                List.of(new Core.FilterCondition("email", "contains", "example")),
                "and", "{{mcp:step.output.items}}");
            mockResolvedItems(List.of(
                Map.of("email", "alice@example.com"),
                Map.of("email", "bob@test.com")
            ));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(1, result.output().get("count"));
        }

        @Test
        @DisplayName("Should evaluate 'greaterThan' with numeric values on list items")
        void shouldEvaluateGreaterThanNumeric() {
            FilterNode node = buildNode(
                List.of(new Core.FilterCondition("score", "greaterThan", "80")),
                "and", "{{mcp:step.output.items}}");
            mockResolvedItems(List.of(
                Map.of("name", "Alice", "score", "90"),
                Map.of("name", "Bob", "score", "50")
            ));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(1, result.output().get("count"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
            assertEquals("Alice", items.get(0).get("name"));
        }

        @Test
        @DisplayName("Should evaluate 'isEmpty' operator for missing field in items")
        void shouldEvaluateIsEmptyForMissingField() {
            FilterNode node = buildNode(
                List.of(new Core.FilterCondition("phone", "isEmpty", null)),
                "and", "{{mcp:step.output.items}}");

            Map<String, Object> item1 = new HashMap<>();
            item1.put("name", "Alice");
            // phone is missing

            Map<String, Object> item2 = new HashMap<>();
            item2.put("name", "Bob");
            item2.put("phone", "555-1234");

            mockResolvedItems(List.of(item1, item2));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(1, result.output().get("count"));
        }

        @Test
        @DisplayName("Should return false for unknown operator")
        void shouldReturnFalseForUnknownOperator() {
            FilterNode node = buildNode(
                List.of(new Core.FilterCondition("name", "unknownOp", "Alice")),
                "and", "{{mcp:step.output.items}}");
            mockResolvedItems(List.of(
                Map.of("name", "Alice")
            ));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(0, result.output().get("count"));
        }
    }

    // ===============================================================
    // execute() - Empty conditions with inputExpression
    // ===============================================================

    @Nested
    @DisplayName("execute() - Empty conditions")
    class ExecuteEmptyConditionsTests {

        @Test
        @DisplayName("Should pass all items with empty conditions in AND mode (vacuous truth)")
        void shouldPassAllItemsWithEmptyConditionsInAndMode() {
            FilterNode node = buildNode(List.of(), "and", "{{mcp:step.output.items}}");
            mockResolvedItems(List.of(
                Map.of("name", "Alice"),
                Map.of("name", "Bob")
            ));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(2, result.output().get("count"));
            assertEquals(true, result.output().get("matched"));
        }

        @Test
        @DisplayName("Should pass all items with empty conditions in OR mode")
        void shouldPassAllItemsWithEmptyConditionsInOrMode() {
            FilterNode node = buildNode(List.of(), "or", "{{mcp:step.output.items}}");
            mockResolvedItems(List.of(
                Map.of("name", "Alice"),
                Map.of("name", "Bob")
            ));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(2, result.output().get("count"));
            assertEquals(true, result.output().get("matched"));
        }
    }

    // ===============================================================
    // getNextNodes() tests
    // ===============================================================

    @Nested
    @DisplayName("getNextNodes()")
    class GetNextNodesTests {

        @Test
        @DisplayName("Should return all successors on success")
        void shouldReturnAllSuccessorsOnSuccess() {
            FilterNode node = new FilterNode("core:filter", List.of(), "and");

            ExecutionNode successor1 = createMockNode("mcp:next1");
            ExecutionNode successor2 = createMockNode("mcp:next2");
            node.addSuccessor(successor1);
            node.addSuccessor(successor2);

            NodeExecutionResult result = NodeExecutionResult.success("core:filter", Map.of());

            List<ExecutionNode> nextNodes = node.getNextNodes(result);
            assertEquals(2, nextNodes.size());
        }

        @Test
        @DisplayName("Should return empty list on failure")
        void shouldReturnEmptyListOnFailure() {
            FilterNode node = new FilterNode("core:filter", List.of(), "and");

            ExecutionNode successor = createMockNode("mcp:next");
            node.addSuccessor(successor);

            NodeExecutionResult result = NodeExecutionResult.failure("core:filter", "Error");

            List<ExecutionNode> nextNodes = node.getNextNodes(result);
            assertTrue(nextNodes.isEmpty());
        }
    }

    // ===============================================================
    // onComplete() tests
    // ===============================================================

    @Nested
    @DisplayName("onComplete()")
    class OnCompleteTests {

        @Test
        @DisplayName("Should not throw exception on success result")
        void shouldNotThrowExceptionOnSuccessResult() {
            FilterNode node = new FilterNode("core:filter", List.of(), "and");
            NodeExecutionResult result = NodeExecutionResult.success("core:filter", Map.of());
            assertDoesNotThrow(() -> node.onComplete(context, result));
        }

        @Test
        @DisplayName("Should not throw exception on failure result")
        void shouldNotThrowExceptionOnFailureResult() {
            FilterNode node = new FilterNode("core:filter", List.of(), "and");
            NodeExecutionResult result = NodeExecutionResult.failure("core:filter", "Error");
            assertDoesNotThrow(() -> node.onComplete(context, result));
        }
    }

    // ===============================================================
    // Helper methods
    // ===============================================================

    private ExecutionNode createMockNode(String nodeId) {
        return new BaseNode(nodeId, NodeType.MCP) {
            @Override
            public NodeExecutionResult execute(ExecutionContext context) {
                return NodeExecutionResult.success(nodeId, Map.of());
            }
        };
    }
}
