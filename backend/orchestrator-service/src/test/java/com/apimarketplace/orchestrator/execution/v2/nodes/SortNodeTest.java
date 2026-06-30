package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SortNode.
 * SortNode reorders items by one or more fields with ascending/descending direction.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SortNode")
class SortNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    @Mock
    private V2TemplateAdapter mockTemplateAdapter;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("name", "Test");

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

    // =========================================================================
    // Helper: build a SortNode with inputExpression and mock adapter
    // =========================================================================

    private SortNode buildNode(List<Core.SortField> fields, String inputExpr) {
        SortNode node = SortNode.builder()
            .nodeId("core:sort")
            .fields(fields)
            .inputExpression(inputExpr)
            .build();
        node.setTemplateAdapter(mockTemplateAdapter);
        return node;
    }

    private void mockResolvedItems(List<Map<String, Object>> items) {
        when(mockTemplateAdapter.resolveTemplates(anyMap(), any(ExecutionContext.class)))
            .thenReturn(Map.of("__input__", items));
    }

    // =========================================================================
    // Constructor tests
    // =========================================================================

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create SortNode with nodeId and fields")
        void shouldCreateSortNodeWithNodeIdAndFields() {
            List<Core.SortField> fields = List.of(
                new Core.SortField("name", "asc"),
                new Core.SortField("age", "desc")
            );

            SortNode node = new SortNode("core:sort_items", fields);

            assertEquals("core:sort_items", node.getNodeId());
            assertEquals(NodeType.SORT, node.getType());
            assertEquals(2, node.getFields().size());
        }

        @Test
        @DisplayName("Should handle null fields")
        void shouldHandleNullFields() {
            SortNode node = new SortNode("core:sort", null);

            assertNotNull(node.getFields());
            assertTrue(node.getFields().isEmpty());
        }

        @Test
        @DisplayName("Should create SortNode using builder with inputExpression")
        void shouldCreateSortNodeUsingBuilder() {
            List<Core.SortField> fields = List.of(
                new Core.SortField("price", "asc")
            );

            SortNode node = SortNode.builder()
                .nodeId("core:sort_by_price")
                .fields(fields)
                .inputExpression("{{mcp:step.output.items}}")
                .build();

            assertEquals("core:sort_by_price", node.getNodeId());
            assertEquals(1, node.getFields().size());
            assertEquals("price", node.getFields().get(0).field());
            assertEquals("{{mcp:step.output.items}}", node.getInputExpression());
        }
    }

    // =========================================================================
    // execute() - null/blank inputExpression
    // =========================================================================

    @Nested
    @DisplayName("execute() - Missing inputExpression")
    class ExecuteMissingInputExpressionTests {

        @Test
        @DisplayName("Should fail when inputExpression is null")
        void shouldFailWhenInputExpressionIsNull() {
            SortNode node = new SortNode("core:sort", List.of(new Core.SortField("name", "asc")));
            node.setTemplateAdapter(mockTemplateAdapter);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("Input expression is required"));
        }

        @Test
        @DisplayName("Should fail when inputExpression is blank")
        void shouldFailWhenInputExpressionIsBlank() {
            SortNode node = new SortNode("core:sort", List.of(new Core.SortField("name", "asc")), "   ");
            node.setTemplateAdapter(mockTemplateAdapter);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("Input expression is required"));
        }
    }

    // =========================================================================
    // execute() - Sort ascending via execute()
    // =========================================================================

    @Nested
    @DisplayName("execute() - Sort ascending")
    class ExecuteSortAscendingTests {

        @Test
        @DisplayName("Should sort items ascending by string field via execute()")
        void shouldSortAscendingByStringField() {
            SortNode node = buildNode(
                List.of(new Core.SortField("name", "asc")),
                "{{mcp:step.output.items}}"
            );
            mockResolvedItems(List.of(
                Map.of("name", "Charlie", "score", 85),
                Map.of("name", "Alice", "score", 92),
                Map.of("name", "Bob", "score", 78)
            ));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sorted = (List<Map<String, Object>>) result.output().get("sorted_items");
            assertEquals(3, sorted.size());
            assertEquals("Alice", sorted.get(0).get("name"));
            assertEquals("Bob", sorted.get(1).get("name"));
            assertEquals("Charlie", sorted.get(2).get("name"));
        }

        @Test
        @DisplayName("Should sort items ascending by numeric field via execute()")
        void shouldSortAscendingByNumericField() {
            SortNode node = buildNode(
                List.of(new Core.SortField("score", "asc")),
                "{{mcp:step.output.items}}"
            );
            mockResolvedItems(List.of(
                Map.of("name", "Charlie", "score", 85),
                Map.of("name", "Alice", "score", 92),
                Map.of("name", "Bob", "score", 78)
            ));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sorted = (List<Map<String, Object>>) result.output().get("sorted_items");
            assertEquals(78, sorted.get(0).get("score"));
            assertEquals(85, sorted.get(1).get("score"));
            assertEquals(92, sorted.get(2).get("score"));
        }
    }

    // =========================================================================
    // execute() - Sort descending via execute()
    // =========================================================================

    @Nested
    @DisplayName("execute() - Sort descending")
    class ExecuteSortDescendingTests {

        @Test
        @DisplayName("Should sort items descending by string field via execute()")
        void shouldSortDescendingByStringField() {
            SortNode node = buildNode(
                List.of(new Core.SortField("name", "desc")),
                "{{mcp:step.output.items}}"
            );
            mockResolvedItems(List.of(
                Map.of("name", "Alice"),
                Map.of("name", "Charlie"),
                Map.of("name", "Bob")
            ));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sorted = (List<Map<String, Object>>) result.output().get("sorted_items");
            assertEquals("Charlie", sorted.get(0).get("name"));
            assertEquals("Bob", sorted.get(1).get("name"));
            assertEquals("Alice", sorted.get(2).get("name"));
        }

        @Test
        @DisplayName("Should sort items descending by numeric field via execute()")
        void shouldSortDescendingByNumericField() {
            SortNode node = buildNode(
                List.of(new Core.SortField("score", "desc")),
                "{{mcp:step.output.items}}"
            );
            mockResolvedItems(List.of(
                Map.of("score", 85),
                Map.of("score", 92),
                Map.of("score", 78)
            ));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sorted = (List<Map<String, Object>>) result.output().get("sorted_items");
            assertEquals(92, sorted.get(0).get("score"));
            assertEquals(85, sorted.get(1).get("score"));
            assertEquals(78, sorted.get(2).get("score"));
        }
    }

    // =========================================================================
    // execute() - Null/missing field in items
    // =========================================================================

    @Nested
    @DisplayName("execute() - Null/missing fields in items")
    class ExecuteNullFieldTests {

        @Test
        @DisplayName("Should handle items with null field values gracefully")
        void shouldHandleNullFieldValues() {
            SortNode node = buildNode(
                List.of(new Core.SortField("name", "asc")),
                "{{mcp:step.output.items}}"
            );

            Map<String, Object> item1 = new HashMap<>();
            item1.put("name", "Alice");
            Map<String, Object> item2 = new HashMap<>();
            item2.put("name", null);
            Map<String, Object> item3 = new HashMap<>();
            item3.put("name", "Bob");

            mockResolvedItems(List.of(item1, item2, item3));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sorted = (List<Map<String, Object>>) result.output().get("sorted_items");
            assertEquals(3, sorted.size());
            // Nulls last
            assertEquals("Alice", sorted.get(0).get("name"));
            assertEquals("Bob", sorted.get(1).get("name"));
            assertNull(sorted.get(2).get("name"));
        }

        @Test
        @DisplayName("Should handle items with missing sort field gracefully")
        void shouldHandleMissingSortField() {
            SortNode node = buildNode(
                List.of(new Core.SortField("priority", "asc")),
                "{{mcp:step.output.items}}"
            );

            Map<String, Object> item1 = new HashMap<>();
            item1.put("name", "Alice");
            item1.put("priority", 2);
            Map<String, Object> item2 = new HashMap<>();
            item2.put("name", "Bob");
            // priority missing

            mockResolvedItems(List.of(item1, item2));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sorted = (List<Map<String, Object>>) result.output().get("sorted_items");
            assertEquals(2, sorted.size());
            // item with priority comes first, missing-field item last (null last)
            assertEquals("Alice", sorted.get(0).get("name"));
            assertEquals("Bob", sorted.get(1).get("name"));
        }
    }

    // =========================================================================
    // execute() - Empty list
    // =========================================================================

    @Nested
    @DisplayName("execute() - Empty list")
    class ExecuteEmptyListTests {

        @Test
        @DisplayName("Should return empty result for empty input list")
        void shouldReturnEmptyResultForEmptyList() {
            SortNode node = buildNode(
                List.of(new Core.SortField("name", "asc")),
                "{{mcp:step.output.items}}"
            );
            mockResolvedItems(List.of());

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sorted = (List<Map<String, Object>>) result.output().get("sorted_items");
            assertTrue(sorted.isEmpty());
            assertEquals(0, result.output().get("count"));
        }
    }

    // =========================================================================
    // execute() - templateAdapter throws
    // =========================================================================

    @Nested
    @DisplayName("execute() - templateAdapter throws")
    class ExecuteAdapterThrowsTests {

        @Test
        @DisplayName("Should return failure when templateAdapter throws")
        void shouldReturnFailureWhenAdapterThrows() {
            SortNode node = buildNode(
                List.of(new Core.SortField("name", "asc")),
                "{{mcp:step.output.items}}"
            );
            when(mockTemplateAdapter.resolveTemplates(anyMap(), any(ExecutionContext.class)))
                .thenThrow(new RuntimeException("Resolution failed"));

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("Resolution failed"));
        }
    }

    // =========================================================================
    // execute() - Output metadata
    // =========================================================================

    @Nested
    @DisplayName("execute() - Metadata")
    class ExecuteMetadataTests {

        @Test
        @DisplayName("Should include mandatory metadata fields in success result")
        void shouldIncludeMandatoryMetadataFields() {
            SortNode node = buildNode(
                List.of(new Core.SortField("name", "asc")),
                "{{mcp:step.output.items}}"
            );
            mockResolvedItems(List.of(
                Map.of("name", "Alice"),
                Map.of("name", "Bob")
            ));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("SORT", result.output().get("node_type"));
            assertEquals(0, result.output().get("item_index"));
            assertEquals(0, result.output().get("itemIndex"));
            assertEquals("item-1", result.output().get("item_id"));
            assertNotNull(result.output().get("resolved_params"));
        }

        @Test
        @DisplayName("Should include sorted_items and count in output")
        void shouldIncludeSortedItemsAndCountInOutput() {
            SortNode node = buildNode(
                List.of(new Core.SortField("name", "asc")),
                "{{mcp:step.output.items}}"
            );
            mockResolvedItems(List.of(
                Map.of("name", "Charlie"),
                Map.of("name", "Alice"),
                Map.of("name", "Bob")
            ));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertNotNull(result.output().get("sorted_items"));
            assertEquals(3, result.output().get("count"));
        }

        @Test
        @DisplayName("Should include resolved_params with sort field configuration")
        void shouldIncludeInputDataWithSortFieldConfig() {
            SortNode node = buildNode(
                List.of(
                    new Core.SortField("name", "asc"),
                    new Core.SortField("age", "desc")
                ),
                "{{mcp:step.output.items}}"
            );
            mockResolvedItems(List.of(
                Map.of("name", "Alice", "age", 25)
            ));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            Map<String, Object> inputData = (Map<String, Object>) result.output().get("resolved_params");
            assertNotNull(inputData);
            // Node stores structured fields list, not flattened "sort_field_N" strings
            @SuppressWarnings("unchecked")
            List<Map<String, String>> fields = (List<Map<String, String>>) inputData.get("fields");
            assertEquals(2, fields.size());
            assertEquals("name", fields.get(0).get("field"));
            assertEquals("asc", fields.get(0).get("direction"));
            assertEquals("age", fields.get(1).get("field"));
            assertEquals("desc", fields.get(1).get("direction"));
        }
    }

    // =========================================================================
    // buildComparator() tests (static, no execute/adapter needed)
    // =========================================================================

    @Nested
    @DisplayName("buildComparator() - Sort ascending")
    class SortAscendingTests {

        @Test
        @DisplayName("Should sort items ascending by string field")
        void shouldSortAscendingByStringField() {
            Comparator<Map<String, Object>> comparator = SortNode.buildComparator(
                List.of(new Core.SortField("name", "asc")));

            List<Map<String, Object>> items = new ArrayList<>(List.of(
                Map.of("name", "Charlie"),
                Map.of("name", "Alice"),
                Map.of("name", "Bob")
            ));

            items.sort(comparator);

            assertEquals("Alice", items.get(0).get("name"));
            assertEquals("Bob", items.get(1).get("name"));
            assertEquals("Charlie", items.get(2).get("name"));
        }

        @Test
        @DisplayName("Should sort items ascending by numeric field")
        void shouldSortAscendingByNumericField() {
            Comparator<Map<String, Object>> comparator = SortNode.buildComparator(
                List.of(new Core.SortField("age", "asc")));

            List<Map<String, Object>> items = new ArrayList<>(List.of(
                Map.of("age", 30),
                Map.of("age", 25),
                Map.of("age", 35)
            ));

            items.sort(comparator);

            assertEquals(25, items.get(0).get("age"));
            assertEquals(30, items.get(1).get("age"));
            assertEquals(35, items.get(2).get("age"));
        }

        @Test
        @DisplayName("Should default to ascending when direction is null")
        void shouldDefaultToAscendingWhenDirectionIsNull() {
            List<Core.SortField> fields = List.of(new Core.SortField("value", null));
            assertEquals("asc", fields.get(0).direction());
        }
    }

    @Nested
    @DisplayName("buildComparator() - Sort descending")
    class SortDescendingTests {

        @Test
        @DisplayName("Should sort items descending by string field")
        void shouldSortDescendingByStringField() {
            Comparator<Map<String, Object>> comparator = SortNode.buildComparator(
                List.of(new Core.SortField("name", "desc")));

            List<Map<String, Object>> items = new ArrayList<>(List.of(
                Map.of("name", "Alice"),
                Map.of("name", "Charlie"),
                Map.of("name", "Bob")
            ));

            items.sort(comparator);

            assertEquals("Charlie", items.get(0).get("name"));
            assertEquals("Bob", items.get(1).get("name"));
            assertEquals("Alice", items.get(2).get("name"));
        }

        @Test
        @DisplayName("Should sort items descending by numeric field")
        void shouldSortDescendingByNumericField() {
            Comparator<Map<String, Object>> comparator = SortNode.buildComparator(
                List.of(new Core.SortField("score", "desc")));

            List<Map<String, Object>> items = new ArrayList<>(List.of(
                Map.of("score", 85),
                Map.of("score", 92),
                Map.of("score", 78)
            ));

            items.sort(comparator);

            assertEquals(92, items.get(0).get("score"));
            assertEquals(85, items.get(1).get("score"));
            assertEquals(78, items.get(2).get("score"));
        }
    }

    @Nested
    @DisplayName("buildComparator() - Multi-field sort")
    class MultiFieldSortTests {

        @Test
        @DisplayName("Should sort by primary field then secondary field")
        void shouldSortByPrimaryThenSecondaryField() {
            Comparator<Map<String, Object>> comparator = SortNode.buildComparator(List.of(
                new Core.SortField("category", "asc"),
                new Core.SortField("name", "asc")
            ));

            List<Map<String, Object>> items = new ArrayList<>(List.of(
                Map.of("category", "B", "name", "Zebra"),
                Map.of("category", "A", "name", "Banana"),
                Map.of("category", "A", "name", "Apple"),
                Map.of("category", "B", "name", "Ant")
            ));

            items.sort(comparator);

            assertEquals("Apple", items.get(0).get("name"));
            assertEquals("Banana", items.get(1).get("name"));
            assertEquals("Ant", items.get(2).get("name"));
            assertEquals("Zebra", items.get(3).get("name"));
        }

        @Test
        @DisplayName("Should sort with mixed ascending and descending directions")
        void shouldSortWithMixedDirections() {
            Comparator<Map<String, Object>> comparator = SortNode.buildComparator(List.of(
                new Core.SortField("category", "asc"),
                new Core.SortField("priority", "desc")
            ));

            List<Map<String, Object>> items = new ArrayList<>(List.of(
                Map.of("category", "A", "priority", 1),
                Map.of("category", "A", "priority", 3),
                Map.of("category", "B", "priority", 2),
                Map.of("category", "A", "priority", 2)
            ));

            items.sort(comparator);

            assertEquals("A", items.get(0).get("category"));
            assertEquals(3, items.get(0).get("priority"));
            assertEquals("A", items.get(1).get("category"));
            assertEquals(2, items.get(1).get("priority"));
            assertEquals("A", items.get(2).get("category"));
            assertEquals(1, items.get(2).get("priority"));
            assertEquals("B", items.get(3).get("category"));
            assertEquals(2, items.get(3).get("priority"));
        }
    }

    @Nested
    @DisplayName("buildComparator() - Empty fields list")
    class EmptyFieldsTests {

        @Test
        @DisplayName("Should not change order when fields list is empty")
        void shouldNotChangeOrderWhenFieldsEmpty() {
            Comparator<Map<String, Object>> comparator = SortNode.buildComparator(List.of());

            List<Map<String, Object>> items = new ArrayList<>(List.of(
                Map.of("name", "Charlie"),
                Map.of("name", "Alice"),
                Map.of("name", "Bob")
            ));

            List<Map<String, Object>> original = new ArrayList<>(items);
            items.sort(comparator);

            assertEquals(original, items);
        }

        @Test
        @DisplayName("Should not change order when fields list is null")
        void shouldNotChangeOrderWhenFieldsNull() {
            Comparator<Map<String, Object>> comparator = SortNode.buildComparator(null);

            List<Map<String, Object>> items = new ArrayList<>(List.of(
                Map.of("name", "Charlie"),
                Map.of("name", "Alice")
            ));

            List<Map<String, Object>> original = new ArrayList<>(items);
            items.sort(comparator);

            assertEquals(original, items);
        }
    }

    // =========================================================================
    // compareValues() tests (static, no execute/adapter needed)
    // =========================================================================

    @Nested
    @DisplayName("compareValues() - Null values handling")
    class NullValuesTests {

        @Test
        @DisplayName("Should sort nulls last in ascending order")
        void shouldSortNullsLastAscending() {
            assertTrue(SortNode.compareValues(null, "value") > 0);
        }

        @Test
        @DisplayName("Should sort nulls last in both directions")
        void shouldSortNullsLastInBothDirections() {
            assertEquals(0, SortNode.compareValues(null, null));
            assertTrue(SortNode.compareValues(null, "a") > 0);
            assertTrue(SortNode.compareValues("a", null) < 0);
        }
    }

    @Nested
    @DisplayName("compareValues() - Numeric vs string sorting")
    class NumericVsStringSortingTests {

        @Test
        @DisplayName("Should compare numbers numerically")
        void shouldCompareNumbersNumerically() {
            assertEquals(-1, Integer.signum(SortNode.compareValues(1, 10)));
            assertEquals(1, Integer.signum(SortNode.compareValues(100, 10)));
            assertEquals(0, SortNode.compareValues(5, 5));
        }

        @Test
        @DisplayName("Should compare numeric strings numerically")
        void shouldCompareNumericStringsNumerically() {
            assertTrue(SortNode.compareValues("10", "2") > 0);
            assertTrue(SortNode.compareValues("2", "10") < 0);
        }

        @Test
        @DisplayName("Should compare strings lexicographically")
        void shouldCompareStringsLexicographically() {
            assertTrue(SortNode.compareValues("apple", "banana") < 0);
            assertTrue(SortNode.compareValues("banana", "apple") > 0);
            assertEquals(0, SortNode.compareValues("same", "same"));
        }

        @Test
        @DisplayName("Should compare mixed Number types")
        void shouldCompareMixedNumberTypes() {
            assertTrue(SortNode.compareValues(1, 2.5) < 0);
            assertTrue(SortNode.compareValues(3.0, 2) > 0);
            assertEquals(0, SortNode.compareValues(5, 5.0));
        }
    }

    // =========================================================================
    // getNextNodes() tests
    // =========================================================================

    @Nested
    @DisplayName("getNextNodes()")
    class GetNextNodesTests {

        @Test
        @DisplayName("Should return all successors on success")
        void shouldReturnAllSuccessorsOnSuccess() {
            SortNode node = new SortNode("core:sort", List.of());

            ExecutionNode successor1 = createMockNode("mcp:next1");
            ExecutionNode successor2 = createMockNode("mcp:next2");
            node.addSuccessor(successor1);
            node.addSuccessor(successor2);

            NodeExecutionResult result = NodeExecutionResult.success("core:sort", Map.of());

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertEquals(2, nextNodes.size());
        }

        @Test
        @DisplayName("Should return empty list on failure")
        void shouldReturnEmptyListOnFailure() {
            SortNode node = new SortNode("core:sort", List.of());

            ExecutionNode successor = createMockNode("mcp:next");
            node.addSuccessor(successor);

            NodeExecutionResult result = NodeExecutionResult.failure("core:sort", "Error");

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertTrue(nextNodes.isEmpty());
        }
    }

    // =========================================================================
    // Getters tests
    // =========================================================================

    @Nested
    @DisplayName("Getters")
    class GettersTests {

        @Test
        @DisplayName("getFields() should return sort fields")
        void getFieldsShouldReturnSortFields() {
            List<Core.SortField> fields = List.of(
                new Core.SortField("price", "asc"),
                new Core.SortField("name", "desc")
            );

            SortNode node = new SortNode("core:sort", fields);

            assertEquals(2, node.getFields().size());
            assertEquals("price", node.getFields().get(0).field());
            assertEquals("asc", node.getFields().get(0).direction());
            assertEquals("name", node.getFields().get(1).field());
            assertEquals("desc", node.getFields().get(1).direction());
        }
    }

    // =========================================================================
    // Helper methods
    // =========================================================================

    private ExecutionNode createMockNode(String nodeId) {
        return new BaseNode(nodeId, NodeType.MCP) {
            @Override
            public NodeExecutionResult execute(ExecutionContext context) {
                return NodeExecutionResult.success(nodeId, Map.of());
            }
        };
    }
}
