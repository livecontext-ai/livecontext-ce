package com.apimarketplace.orchestrator.execution.v2.nodes;

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
import static org.mockito.Mockito.when;

/**
 * Unit tests for RemoveDuplicatesNode.
 * RemoveDuplicatesNode removes duplicate items based on specified fields,
 * keeping first or last occurrence.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RemoveDuplicatesNode")
class RemoveDuplicatesNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    @Mock
    private V2TemplateAdapter mockTemplateAdapter;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("name", "test");
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

    /**
     * Helper: build a RemoveDuplicatesNode with inputExpression and a wired templateAdapter
     * that resolves to the given items.
     */
    private RemoveDuplicatesNode buildNode(List<String> fields, String keep, List<Map<String, Object>> resolvedItems) {
        RemoveDuplicatesNode node = RemoveDuplicatesNode.builder()
            .nodeId("core:dedup")
            .fields(fields)
            .keep(keep)
            .inputExpression("{{items}}")
            .build();
        node.setTemplateAdapter(mockTemplateAdapter);

        when(mockTemplateAdapter.resolveTemplates(anyMap(), any()))
            .thenReturn(Map.of("__input__", resolvedItems));

        return node;
    }

    // =====================================================================
    // Constructor tests
    // =====================================================================

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create RemoveDuplicatesNode with fields and keep")
        void shouldCreateWithFieldsAndKeep() {
            RemoveDuplicatesNode node = new RemoveDuplicatesNode(
                "core:dedup", List.of("email"), "first", null);

            assertEquals("core:dedup", node.getNodeId());
            assertEquals(NodeType.REMOVE_DUPLICATES, node.getType());
            assertEquals(List.of("email"), node.getFields());
            assertEquals("first", node.getKeep());
        }

        @Test
        @DisplayName("Should handle null fields")
        void shouldHandleNullFields() {
            RemoveDuplicatesNode node = new RemoveDuplicatesNode("core:dedup", null, "first", null);

            assertNotNull(node.getFields());
            assertTrue(node.getFields().isEmpty());
        }

        @Test
        @DisplayName("Should default keep to first when null")
        void shouldDefaultKeepToFirst() {
            RemoveDuplicatesNode node = new RemoveDuplicatesNode("core:dedup", List.of(), null, null);

            assertEquals("first", node.getKeep());
        }

        @Test
        @DisplayName("Should create using builder")
        void shouldCreateUsingBuilder() {
            RemoveDuplicatesNode node = RemoveDuplicatesNode.builder()
                .nodeId("core:dedup_users")
                .fields(List.of("email", "name"))
                .keep("last")
                .inputExpression("{{data}}")
                .build();

            assertEquals("core:dedup_users", node.getNodeId());
            assertEquals(List.of("email", "name"), node.getFields());
            assertEquals("last", node.getKeep());
            assertEquals("{{data}}", node.getInputExpression());
        }
    }

    // =====================================================================
    // Input expression validation
    // =====================================================================

    @Nested
    @DisplayName("execute() - Input Expression Validation")
    class InputExpressionValidationTests {

        @Test
        @DisplayName("Should fail when inputExpression is null")
        void shouldFailWhenInputExpressionIsNull() {
            RemoveDuplicatesNode node = new RemoveDuplicatesNode("core:dedup", List.of("email"), "first", null);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("Input expression is required"));
        }

        @Test
        @DisplayName("Should fail when inputExpression is blank")
        void shouldFailWhenInputExpressionIsBlank() {
            RemoveDuplicatesNode node = RemoveDuplicatesNode.builder()
                .nodeId("core:dedup")
                .fields(List.of("email"))
                .inputExpression("   ")
                .build();

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("Input expression is required"));
        }
    }

    // =====================================================================
    // All unique items
    // =====================================================================

    @Nested
    @DisplayName("execute() - All Unique Items")
    class AllUniqueTests {

        @Test
        @DisplayName("Should return all items when no duplicates exist")
        void shouldReturnAllItemsWhenNoDuplicates() {
            List<Map<String, Object>> items = List.of(
                Map.of("email", "a@test.com"),
                Map.of("email", "b@test.com"),
                Map.of("email", "c@test.com")
            );
            RemoveDuplicatesNode node = buildNode(List.of("email"), "first", items);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(3, ((List<?>) result.output().get("items")).size());
            assertEquals(0, result.output().get("removed_count"));
        }
    }

    // =====================================================================
    // All duplicates
    // =====================================================================

    @Nested
    @DisplayName("execute() - All Duplicates")
    class AllDuplicatesTests {

        @Test
        @DisplayName("Should keep one item when all are duplicates")
        void shouldKeepOneItemWhenAllDuplicates() {
            List<Map<String, Object>> items = List.of(
                Map.of("email", "a@test.com", "name", "First"),
                Map.of("email", "a@test.com", "name", "Second"),
                Map.of("email", "a@test.com", "name", "Third")
            );
            RemoveDuplicatesNode node = buildNode(List.of("email"), "first", items);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> deduped = (List<Map<String, Object>>) result.output().get("items");
            assertEquals(1, deduped.size());
            assertEquals("First", deduped.get(0).get("name"));
            assertEquals(2, result.output().get("removed_count"));
        }
    }

    // =====================================================================
    // Partial duplicates
    // =====================================================================

    @Nested
    @DisplayName("execute() - Partial Duplicates")
    class PartialDuplicatesTests {

        @Test
        @DisplayName("Should remove duplicates by single field keeping first")
        void shouldRemoveDuplicatesBySingleFieldKeepFirst() {
            List<Map<String, Object>> items = List.of(
                Map.of("email", "a@test.com", "name", "Alice"),
                Map.of("email", "b@test.com", "name", "Bob"),
                Map.of("email", "a@test.com", "name", "Alice2")
            );
            RemoveDuplicatesNode node = buildNode(List.of("email"), "first", items);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> deduped = (List<Map<String, Object>>) result.output().get("items");
            assertEquals(2, deduped.size());
            assertEquals("Alice", deduped.get(0).get("name")); // first occurrence kept
            assertEquals("Bob", deduped.get(1).get("name"));
        }

        @Test
        @DisplayName("Should deduplicate by composite key")
        void shouldDeduplicateByCompositeKey() {
            List<Map<String, Object>> items = List.of(
                Map.of("name", "Alice", "city", "Paris"),
                Map.of("name", "Alice", "city", "London"),
                Map.of("name", "Alice", "city", "Paris")  // duplicate
            );
            RemoveDuplicatesNode node = buildNode(List.of("name", "city"), "first", items);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> deduped = (List<Map<String, Object>>) result.output().get("items");
            assertEquals(2, deduped.size());
        }
    }

    // =====================================================================
    // Empty fields list (compare all)
    // =====================================================================

    @Nested
    @DisplayName("execute() - Empty Fields (Compare All)")
    class EmptyFieldsTests {

        @Test
        @DisplayName("Should compare all fields when fields list is empty")
        void shouldCompareAllFieldsWhenEmpty() {
            List<Map<String, Object>> items = List.of(
                Map.of("name", "Alice", "age", 30),
                Map.of("name", "Alice", "age", 30),  // exact duplicate
                Map.of("name", "Alice", "age", 31)   // different
            );
            RemoveDuplicatesNode node = buildNode(List.of(), "first", items);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> deduped = (List<Map<String, Object>>) result.output().get("items");
            assertEquals(2, deduped.size());
        }
    }

    // =====================================================================
    // keep="last"
    // =====================================================================

    @Nested
    @DisplayName("execute() - Keep Last")
    class KeepLastTests {

        @Test
        @DisplayName("Should keep last occurrence when keep=last")
        void shouldKeepLastOccurrence() {
            List<Map<String, Object>> items = List.of(
                Map.of("email", "a@test.com", "name", "Alice"),
                Map.of("email", "b@test.com", "name", "Bob"),
                Map.of("email", "a@test.com", "name", "Alice2")
            );
            RemoveDuplicatesNode node = buildNode(List.of("email"), "last", items);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> deduped = (List<Map<String, Object>>) result.output().get("items");
            assertEquals(2, deduped.size());
            // "last" keeps the last occurrence of each key
            // LinkedHashMap preserves insertion order of keys, so a@test.com (first inserted) comes before b@test.com
            assertEquals("Alice2", deduped.get(0).get("name"));
            assertEquals("Bob", deduped.get(1).get("name"));
        }
    }

    // =====================================================================
    // Empty list input
    // =====================================================================

    @Nested
    @DisplayName("execute() - Empty List Input")
    class EmptyListInputTests {

        @Test
        @DisplayName("Should return empty result when input list is empty")
        void shouldReturnEmptyResultWhenInputListIsEmpty() {
            RemoveDuplicatesNode node = buildNode(List.of("email"), "first", List.of());

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(0, result.output().get("original_count"));
            assertEquals(0, result.output().get("deduplicated_count"));
            assertEquals(0, result.output().get("removed_count"));
            assertEquals(List.of(), result.output().get("items"));
        }
    }

    // =====================================================================
    // Metadata fields
    // =====================================================================

    @Nested
    @DisplayName("execute() - Metadata Fields")
    class MetadataFieldsTests {

        @Test
        @DisplayName("Should include mandatory metadata fields in output")
        void shouldIncludeMandatoryMetadataFields() {
            List<Map<String, Object>> items = List.of(Map.of("email", "a@test.com"));
            RemoveDuplicatesNode node = buildNode(List.of("email"), "first", items);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("REMOVE_DUPLICATES", result.output().get("node_type"));
            assertEquals(0, result.output().get("item_index"));
            assertEquals(0, result.output().get("itemIndex"));
            assertEquals("item-1", result.output().get("item_id"));
            assertNotNull(result.output().get("resolved_params"));
        }

        @Test
        @DisplayName("Should include count fields in output")
        void shouldIncludeCountFieldsInOutput() {
            List<Map<String, Object>> items = List.of(
                Map.of("email", "a@test.com"),
                Map.of("email", "a@test.com"),
                Map.of("email", "b@test.com")
            );
            RemoveDuplicatesNode node = buildNode(List.of("email"), "first", items);

            NodeExecutionResult result = node.execute(context);

            assertEquals(3, result.output().get("original_count"));
            assertEquals(2, result.output().get("deduplicated_count"));
            assertEquals(1, result.output().get("removed_count"));
        }
    }

    // =====================================================================
    // getNextNodes() tests
    // =====================================================================

    @Nested
    @DisplayName("getNextNodes()")
    class GetNextNodesTests {

        @Test
        @DisplayName("Should return all successors on success")
        void shouldReturnAllSuccessorsOnSuccess() {
            RemoveDuplicatesNode node = new RemoveDuplicatesNode("core:dedup", List.of(), "first", null);

            ExecutionNode successor = createMockNode("mcp:next");
            node.addSuccessor(successor);

            NodeExecutionResult result = NodeExecutionResult.success("core:dedup", Map.of());
            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertEquals(1, nextNodes.size());
        }

        @Test
        @DisplayName("Should return empty list on failure")
        void shouldReturnEmptyListOnFailure() {
            RemoveDuplicatesNode node = new RemoveDuplicatesNode("core:dedup", List.of(), "first", null);

            ExecutionNode successor = createMockNode("mcp:next");
            node.addSuccessor(successor);

            NodeExecutionResult result = NodeExecutionResult.failure("core:dedup", "Error");
            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertTrue(nextNodes.isEmpty());
        }
    }

    // =====================================================================
    // Helper methods
    // =====================================================================

    private ExecutionNode createMockNode(String nodeId) {
        return new BaseNode(nodeId, NodeType.MCP) {
            @Override
            public NodeExecutionResult execute(ExecutionContext context) {
                return NodeExecutionResult.success(nodeId, Map.of());
            }
        };
    }
}
