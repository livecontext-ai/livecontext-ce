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
import static org.mockito.Mockito.when;

/**
 * Unit tests for CompareDatasetsNode.
 * CompareDatasetsNode compares two datasets and returns matched, only-in-A, and only-in-B items.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CompareDatasetsNode")
class CompareDatasetsNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    @Mock
    private V2TemplateAdapter mockTemplateAdapter;

    private ExecutionContext baseContext;

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        baseContext = ExecutionContext.create(
            "run-1",
            "workflow-run-1",
            "tenant-1",
            "item-1",
            0,
            triggerData,
            mockPlan
        );
    }

    // ===============================================================
    // Template resolution regressions
    // ===============================================================

    @Test
    @DisplayName("keeps full-template list values structured instead of stringifying them")
    void keepsFullTemplateListValuesStructured() {
        List<Map<String, Object>> datasetA = List.of(
            Map.of("id", "1", "name", "Ada"),
            Map.of("id", "2", "name", "Grace")
        );
        List<Map<String, Object>> datasetB = List.of(
            Map.of("id", "2", "name", "Grace"),
            Map.of("id", "3", "name", "Katherine")
        );
        when(mockTemplateAdapter.resolveTemplates(anyMap(), any(ExecutionContext.class)))
            .thenReturn(Map.of("__expr__", datasetA))
            .thenReturn(Map.of("__expr__", datasetB));

        Core.CompareDatasetsConfig config = new Core.CompareDatasetsConfig(
            "{{core:extract.output.items}}",
            "{{trigger:start.output.expectedRecords}}",
            List.of("id"),
            true,
            true,
            true
        );
        CompareDatasetsNode node = new CompareDatasetsNode("core:compare", config);
        node.setTemplateAdapter(mockTemplateAdapter);

        NodeExecutionResult result = node.execute(baseContext);

        assertTrue(result.isSuccess());
        assertEquals(1, result.output().get("matchedCount"));
        assertEquals(1, result.output().get("onlyInACount"));
        assertEquals(1, result.output().get("onlyInBCount"));
        assertEquals(2, result.output().get("totalA"));
        assertEquals(2, result.output().get("totalB"));
    }

    /**
     * Helper to create an execution context with pre-populated step outputs containing two datasets.
     * The datasets are stored directly as the step output value so that extractDataset()
     * finds them via stepOutputs.get(resolvedInput) → extractListFromObject(List).
     */
    private ExecutionContext contextWithDatasets(List<Map<String, Object>> datasetA,
                                                 List<Map<String, Object>> datasetB) {
        ExecutionContext ctx = baseContext;
        // Store datasets as Map with "items" key - extractDataset does:
        // stepOutputs.get(key) → extractListFromObject(Map) → looks for "items" key
        Map<String, Object> wrapA = new HashMap<>();
        wrapA.put("items", datasetA);
        ctx = ctx.withStepOutput("datasetA", wrapA);

        Map<String, Object> wrapB = new HashMap<>();
        wrapB.put("items", datasetB);
        ctx = ctx.withStepOutput("datasetB", wrapB);
        return ctx;
    }

    // ===============================================================
    // Constructor tests
    // ===============================================================

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create CompareDatasetsNode with nodeId and config")
        void shouldCreateNodeWithNodeIdAndConfig() {
            Core.CompareDatasetsConfig config = new Core.CompareDatasetsConfig(
                "datasetA", "datasetB", List.of("id"), true, true, true
            );

            CompareDatasetsNode node = new CompareDatasetsNode("core:compare", config);

            assertEquals("core:compare", node.getNodeId());
            assertEquals(NodeType.COMPARE_DATASETS, node.getType());
            assertNotNull(node.getCompareDatasetsConfig());
            assertEquals("datasetA", node.getCompareDatasetsConfig().inputA());
            assertEquals("datasetB", node.getCompareDatasetsConfig().inputB());
        }

        @Test
        @DisplayName("Should handle null config without error")
        void shouldHandleNullConfig() {
            CompareDatasetsNode node = new CompareDatasetsNode("core:compare", null);

            assertEquals("core:compare", node.getNodeId());
            assertEquals(NodeType.COMPARE_DATASETS, node.getType());
            assertNull(node.getCompareDatasetsConfig());
        }

        @Test
        @DisplayName("Should default matchFields to empty list when null in config")
        void shouldDefaultMatchFieldsToEmptyList() {
            Core.CompareDatasetsConfig config = new Core.CompareDatasetsConfig(
                "datasetA", "datasetB", null, true, true, true
            );

            CompareDatasetsNode node = new CompareDatasetsNode("core:compare", config);

            assertNotNull(node.getCompareDatasetsConfig().matchFields());
            assertTrue(node.getCompareDatasetsConfig().matchFields().isEmpty());
        }

        @Test
        @DisplayName("Should preserve matchFields when provided")
        void shouldPreserveMatchFields() {
            Core.CompareDatasetsConfig config = new Core.CompareDatasetsConfig(
                "datasetA", "datasetB", List.of("id", "name"), true, true, true
            );

            CompareDatasetsNode node = new CompareDatasetsNode("core:compare", config);

            assertEquals(2, node.getCompareDatasetsConfig().matchFields().size());
            assertEquals("id", node.getCompareDatasetsConfig().matchFields().get(0));
            assertEquals("name", node.getCompareDatasetsConfig().matchFields().get(1));
        }
    }

    // ===============================================================
    // Matching by specific fields
    // ===============================================================

    @Nested
    @DisplayName("execute() - Matching by specific fields")
    class MatchBySpecificFieldsTests {

        @Test
        @DisplayName("Should find matched items by id field")
        void shouldFindMatchedItemsByIdField() {
            List<Map<String, Object>> datasetA = List.of(
                Map.of("id", "1", "name", "Alice"),
                Map.of("id", "2", "name", "Bob"),
                Map.of("id", "3", "name", "Charlie")
            );
            List<Map<String, Object>> datasetB = List.of(
                Map.of("id", "2", "name", "Robert"),
                Map.of("id", "3", "name", "Charles"),
                Map.of("id", "4", "name", "Diana")
            );

            ExecutionContext ctx = contextWithDatasets(datasetA, datasetB);
            Core.CompareDatasetsConfig config = new Core.CompareDatasetsConfig(
                "datasetA", "datasetB", List.of("id"), true, true, true
            );

            CompareDatasetsNode node = new CompareDatasetsNode("core:compare", config);
            NodeExecutionResult result = node.execute(ctx);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> matched = (List<Map<String, Object>>) result.output().get("matched");
            assertEquals(2, matched.size());
            // Matched items use A's version
            assertEquals("Bob", matched.get(0).get("name"));
            assertEquals("Charlie", matched.get(1).get("name"));
        }

        @Test
        @DisplayName("Should find items only in A by specific field")
        void shouldFindItemsOnlyInA() {
            List<Map<String, Object>> datasetA = List.of(
                Map.of("id", "1", "name", "Alice"),
                Map.of("id", "2", "name", "Bob")
            );
            List<Map<String, Object>> datasetB = List.of(
                Map.of("id", "2", "name", "Robert")
            );

            ExecutionContext ctx = contextWithDatasets(datasetA, datasetB);
            Core.CompareDatasetsConfig config = new Core.CompareDatasetsConfig(
                "datasetA", "datasetB", List.of("id"), true, true, true
            );

            CompareDatasetsNode node = new CompareDatasetsNode("core:compare", config);
            NodeExecutionResult result = node.execute(ctx);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> onlyInA = (List<Map<String, Object>>) result.output().get("onlyInA");
            assertEquals(1, onlyInA.size());
            assertEquals("Alice", onlyInA.get(0).get("name"));
        }

        @Test
        @DisplayName("Should find items only in B by specific field")
        void shouldFindItemsOnlyInB() {
            List<Map<String, Object>> datasetA = List.of(
                Map.of("id", "1", "name", "Alice")
            );
            List<Map<String, Object>> datasetB = List.of(
                Map.of("id", "1", "name", "Alice B"),
                Map.of("id", "2", "name", "Bob")
            );

            ExecutionContext ctx = contextWithDatasets(datasetA, datasetB);
            Core.CompareDatasetsConfig config = new Core.CompareDatasetsConfig(
                "datasetA", "datasetB", List.of("id"), true, true, true
            );

            CompareDatasetsNode node = new CompareDatasetsNode("core:compare", config);
            NodeExecutionResult result = node.execute(ctx);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> onlyInB = (List<Map<String, Object>>) result.output().get("onlyInB");
            assertEquals(1, onlyInB.size());
            assertEquals("Bob", onlyInB.get(0).get("name"));
        }

        @Test
        @DisplayName("Should match by multiple fields")
        void shouldMatchByMultipleFields() {
            List<Map<String, Object>> datasetA = List.of(
                Map.of("id", "1", "region", "US", "name", "Alice"),
                Map.of("id", "1", "region", "EU", "name", "Alice EU")
            );
            List<Map<String, Object>> datasetB = List.of(
                Map.of("id", "1", "region", "US", "name", "Alice Updated"),
                Map.of("id", "2", "region", "US", "name", "Bob")
            );

            ExecutionContext ctx = contextWithDatasets(datasetA, datasetB);
            Core.CompareDatasetsConfig config = new Core.CompareDatasetsConfig(
                "datasetA", "datasetB", List.of("id", "region"), true, true, true
            );

            CompareDatasetsNode node = new CompareDatasetsNode("core:compare", config);
            NodeExecutionResult result = node.execute(ctx);

            assertTrue(result.isSuccess());
            assertEquals(1, result.output().get("matchedCount"));
            assertEquals(1, result.output().get("onlyInACount"));
            assertEquals(1, result.output().get("onlyInBCount"));
        }

        @Test
        @DisplayName("Should return correct counts")
        void shouldReturnCorrectCounts() {
            List<Map<String, Object>> datasetA = List.of(
                Map.of("id", "1"), Map.of("id", "2"), Map.of("id", "3")
            );
            List<Map<String, Object>> datasetB = List.of(
                Map.of("id", "2"), Map.of("id", "4")
            );

            ExecutionContext ctx = contextWithDatasets(datasetA, datasetB);
            Core.CompareDatasetsConfig config = new Core.CompareDatasetsConfig(
                "datasetA", "datasetB", List.of("id"), true, true, true
            );

            CompareDatasetsNode node = new CompareDatasetsNode("core:compare", config);
            NodeExecutionResult result = node.execute(ctx);

            assertTrue(result.isSuccess());
            assertEquals(1, result.output().get("matchedCount"));
            assertEquals(2, result.output().get("onlyInACount"));
            assertEquals(1, result.output().get("onlyInBCount"));
            assertEquals(3, result.output().get("totalA"));
            assertEquals(2, result.output().get("totalB"));
        }
    }

    // ===============================================================
    // Matching by all fields (empty matchFields)
    // ===============================================================

    @Nested
    @DisplayName("execute() - Matching by all fields")
    class MatchByAllFieldsTests {

        @Test
        @DisplayName("Should match by all fields when matchFields is empty")
        void shouldMatchByAllFieldsWhenEmpty() {
            List<Map<String, Object>> datasetA = List.of(
                new LinkedHashMap<>(Map.of("id", "1", "name", "Alice")),
                new LinkedHashMap<>(Map.of("id", "2", "name", "Bob"))
            );
            List<Map<String, Object>> datasetB = List.of(
                new LinkedHashMap<>(Map.of("id", "1", "name", "Alice")),
                new LinkedHashMap<>(Map.of("id", "3", "name", "Charlie"))
            );

            ExecutionContext ctx = contextWithDatasets(datasetA, datasetB);
            Core.CompareDatasetsConfig config = new Core.CompareDatasetsConfig(
                "datasetA", "datasetB", List.of(), true, true, true
            );

            CompareDatasetsNode node = new CompareDatasetsNode("core:compare", config);
            NodeExecutionResult result = node.execute(ctx);

            assertTrue(result.isSuccess());
            assertEquals(1, result.output().get("matchedCount"));
            assertEquals(1, result.output().get("onlyInACount"));
            assertEquals(1, result.output().get("onlyInBCount"));
        }

        @Test
        @DisplayName("Should not match items with different values on any field")
        void shouldNotMatchItemsWithDifferentValues() {
            List<Map<String, Object>> datasetA = List.of(
                new LinkedHashMap<>(Map.of("id", "1", "name", "Alice"))
            );
            List<Map<String, Object>> datasetB = List.of(
                new LinkedHashMap<>(Map.of("id", "1", "name", "Alice Updated"))
            );

            ExecutionContext ctx = contextWithDatasets(datasetA, datasetB);
            Core.CompareDatasetsConfig config = new Core.CompareDatasetsConfig(
                "datasetA", "datasetB", List.of(), true, true, true
            );

            CompareDatasetsNode node = new CompareDatasetsNode("core:compare", config);
            NodeExecutionResult result = node.execute(ctx);

            assertTrue(result.isSuccess());
            assertEquals(0, result.output().get("matchedCount"));
            assertEquals(1, result.output().get("onlyInACount"));
            assertEquals(1, result.output().get("onlyInBCount"));
        }

        @Test
        @DisplayName("Should match identical items with all fields comparison")
        void shouldMatchIdenticalItems() {
            Map<String, Object> item = new LinkedHashMap<>(Map.of("id", "1", "name", "Alice", "age", 30));
            List<Map<String, Object>> datasetA = List.of(item);
            List<Map<String, Object>> datasetB = List.of(new LinkedHashMap<>(item));

            ExecutionContext ctx = contextWithDatasets(datasetA, datasetB);
            Core.CompareDatasetsConfig config = new Core.CompareDatasetsConfig(
                "datasetA", "datasetB", List.of(), true, true, true
            );

            CompareDatasetsNode node = new CompareDatasetsNode("core:compare", config);
            NodeExecutionResult result = node.execute(ctx);

            assertTrue(result.isSuccess());
            assertEquals(1, result.output().get("matchedCount"));
            assertEquals(0, result.output().get("onlyInACount"));
            assertEquals(0, result.output().get("onlyInBCount"));
        }
    }

    // ===============================================================
    // Only-in-A detection
    // ===============================================================

    @Nested
    @DisplayName("execute() - Only-in-A detection")
    class OnlyInATests {

        @Test
        @DisplayName("Should detect all items as only-in-A when B is empty")
        void shouldDetectAllAsOnlyInAWhenBIsEmpty() {
            List<Map<String, Object>> datasetA = List.of(
                Map.of("id", "1"), Map.of("id", "2"), Map.of("id", "3")
            );
            List<Map<String, Object>> datasetB = List.of();

            ExecutionContext ctx = contextWithDatasets(datasetA, datasetB);
            Core.CompareDatasetsConfig config = new Core.CompareDatasetsConfig(
                "datasetA", "datasetB", List.of("id"), true, true, true
            );

            CompareDatasetsNode node = new CompareDatasetsNode("core:compare", config);
            NodeExecutionResult result = node.execute(ctx);

            assertTrue(result.isSuccess());
            assertEquals(0, result.output().get("matchedCount"));
            assertEquals(3, result.output().get("onlyInACount"));
            assertEquals(0, result.output().get("onlyInBCount"));
        }

        @Test
        @DisplayName("Should detect no items as only-in-A when all match")
        void shouldDetectNoOnlyInAWhenAllMatch() {
            List<Map<String, Object>> datasetA = List.of(Map.of("id", "1"));
            List<Map<String, Object>> datasetB = List.of(Map.of("id", "1"));

            ExecutionContext ctx = contextWithDatasets(datasetA, datasetB);
            Core.CompareDatasetsConfig config = new Core.CompareDatasetsConfig(
                "datasetA", "datasetB", List.of("id"), true, true, true
            );

            CompareDatasetsNode node = new CompareDatasetsNode("core:compare", config);
            NodeExecutionResult result = node.execute(ctx);

            assertTrue(result.isSuccess());
            assertEquals(0, result.output().get("onlyInACount"));
        }

        @Test
        @DisplayName("Should preserve original item data for only-in-A items")
        void shouldPreserveOriginalDataForOnlyInA() {
            List<Map<String, Object>> datasetA = List.of(
                Map.of("id", "1", "name", "Alice", "score", 95)
            );
            List<Map<String, Object>> datasetB = List.of();

            ExecutionContext ctx = contextWithDatasets(datasetA, datasetB);
            Core.CompareDatasetsConfig config = new Core.CompareDatasetsConfig(
                "datasetA", "datasetB", List.of("id"), true, true, true
            );

            CompareDatasetsNode node = new CompareDatasetsNode("core:compare", config);
            NodeExecutionResult result = node.execute(ctx);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> onlyInA = (List<Map<String, Object>>) result.output().get("onlyInA");
            assertEquals(1, onlyInA.size());
            assertEquals("Alice", onlyInA.get(0).get("name"));
            assertEquals(95, onlyInA.get(0).get("score"));
        }
    }

    // ===============================================================
    // Only-in-B detection
    // ===============================================================

    @Nested
    @DisplayName("execute() - Only-in-B detection")
    class OnlyInBTests {

        @Test
        @DisplayName("Should detect all items as only-in-B when A is empty")
        void shouldDetectAllAsOnlyInBWhenAIsEmpty() {
            List<Map<String, Object>> datasetA = List.of();
            List<Map<String, Object>> datasetB = List.of(
                Map.of("id", "1"), Map.of("id", "2")
            );

            ExecutionContext ctx = contextWithDatasets(datasetA, datasetB);
            Core.CompareDatasetsConfig config = new Core.CompareDatasetsConfig(
                "datasetA", "datasetB", List.of("id"), true, true, true
            );

            CompareDatasetsNode node = new CompareDatasetsNode("core:compare", config);
            NodeExecutionResult result = node.execute(ctx);

            assertTrue(result.isSuccess());
            assertEquals(0, result.output().get("matchedCount"));
            assertEquals(0, result.output().get("onlyInACount"));
            assertEquals(2, result.output().get("onlyInBCount"));
        }

        @Test
        @DisplayName("Should detect no items as only-in-B when all match")
        void shouldDetectNoOnlyInBWhenAllMatch() {
            List<Map<String, Object>> both = List.of(Map.of("id", "1"), Map.of("id", "2"));

            ExecutionContext ctx = contextWithDatasets(both, both);
            Core.CompareDatasetsConfig config = new Core.CompareDatasetsConfig(
                "datasetA", "datasetB", List.of("id"), true, true, true
            );

            CompareDatasetsNode node = new CompareDatasetsNode("core:compare", config);
            NodeExecutionResult result = node.execute(ctx);

            assertTrue(result.isSuccess());
            assertEquals(0, result.output().get("onlyInBCount"));
        }

        @Test
        @DisplayName("Should preserve original item data for only-in-B items")
        void shouldPreserveOriginalDataForOnlyInB() {
            List<Map<String, Object>> datasetA = List.of();
            List<Map<String, Object>> datasetB = List.of(
                Map.of("id", "5", "name", "Eve", "role", "admin")
            );

            ExecutionContext ctx = contextWithDatasets(datasetA, datasetB);
            Core.CompareDatasetsConfig config = new Core.CompareDatasetsConfig(
                "datasetA", "datasetB", List.of("id"), true, true, true
            );

            CompareDatasetsNode node = new CompareDatasetsNode("core:compare", config);
            NodeExecutionResult result = node.execute(ctx);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> onlyInB = (List<Map<String, Object>>) result.output().get("onlyInB");
            assertEquals(1, onlyInB.size());
            assertEquals("Eve", onlyInB.get(0).get("name"));
            assertEquals("admin", onlyInB.get(0).get("role"));
        }
    }

    // ===============================================================
    // Empty datasets
    // ===============================================================

    @Nested
    @DisplayName("execute() - Empty datasets")
    class EmptyDatasetsTests {

        @Test
        @DisplayName("Should handle both datasets empty")
        void shouldHandleBothDatasetsEmpty() {
            ExecutionContext ctx = contextWithDatasets(List.of(), List.of());
            Core.CompareDatasetsConfig config = new Core.CompareDatasetsConfig(
                "datasetA", "datasetB", List.of("id"), true, true, true
            );

            CompareDatasetsNode node = new CompareDatasetsNode("core:compare", config);
            NodeExecutionResult result = node.execute(ctx);

            assertTrue(result.isSuccess());
            assertEquals(0, result.output().get("matchedCount"));
            assertEquals(0, result.output().get("onlyInACount"));
            assertEquals(0, result.output().get("onlyInBCount"));
            assertEquals(0, result.output().get("totalA"));
            assertEquals(0, result.output().get("totalB"));
        }

        @Test
        @DisplayName("Should handle null input expressions gracefully")
        void shouldHandleNullInputExpressions() {
            Core.CompareDatasetsConfig config = new Core.CompareDatasetsConfig(
                null, null, List.of(), true, true, true
            );

            CompareDatasetsNode node = new CompareDatasetsNode("core:compare", config);
            NodeExecutionResult result = node.execute(baseContext);

            assertTrue(result.isSuccess());
            assertEquals(0, result.output().get("totalA"));
            assertEquals(0, result.output().get("totalB"));
        }

        @Test
        @DisplayName("Should handle non-existent step output keys")
        void shouldHandleNonExistentStepOutputKeys() {
            Core.CompareDatasetsConfig config = new Core.CompareDatasetsConfig(
                "nonExistentA", "nonExistentB", List.of("id"), true, true, true
            );

            CompareDatasetsNode node = new CompareDatasetsNode("core:compare", config);
            NodeExecutionResult result = node.execute(baseContext);

            assertTrue(result.isSuccess());
            assertEquals(0, result.output().get("totalA"));
            assertEquals(0, result.output().get("totalB"));
        }

        @Test
        @DisplayName("Should always set success to true on empty datasets")
        void shouldSetSuccessOnEmptyDatasets() {
            ExecutionContext ctx = contextWithDatasets(List.of(), List.of());
            Core.CompareDatasetsConfig config = new Core.CompareDatasetsConfig(
                "datasetA", "datasetB", List.of(), true, true, true
            );

            CompareDatasetsNode node = new CompareDatasetsNode("core:compare", config);
            NodeExecutionResult result = node.execute(ctx);

            assertTrue(result.isSuccess());
            assertEquals(true, result.output().get("success"));
        }
    }

    // ===============================================================
    // Null config
    // ===============================================================

    @Nested
    @DisplayName("execute() - Null config")
    class NullConfigTests {

        @Test
        @DisplayName("Should handle null config without throwing")
        void shouldHandleNullConfigWithoutThrowing() {
            CompareDatasetsNode node = new CompareDatasetsNode("core:compare", null);
            NodeExecutionResult result = node.execute(baseContext);

            assertTrue(result.isSuccess());
            assertEquals(true, result.output().get("success"));
        }

        @Test
        @DisplayName("Should return empty results with null config")
        void shouldReturnEmptyResultsWithNullConfig() {
            CompareDatasetsNode node = new CompareDatasetsNode("core:compare", null);
            NodeExecutionResult result = node.execute(baseContext);

            assertTrue(result.isSuccess());
            assertEquals(0, result.output().get("matchedCount"));
            assertEquals(0, result.output().get("onlyInACount"));
            assertEquals(0, result.output().get("onlyInBCount"));
            assertEquals(0, result.output().get("totalA"));
            assertEquals(0, result.output().get("totalB"));
        }

        @Test
        @DisplayName("Should include mandatory metadata with null config")
        void shouldIncludeMandatoryMetadataWithNullConfig() {
            CompareDatasetsNode node = new CompareDatasetsNode("core:compare", null);
            NodeExecutionResult result = node.execute(baseContext);

            assertEquals("COMPARE_DATASETS", result.output().get("node_type"));
            assertEquals(0, result.output().get("item_index"));
            assertEquals(0, result.output().get("itemIndex"));
            assertEquals("item-1", result.output().get("item_id"));
            assertNotNull(result.output().get("resolved_params"));
        }
    }

    // ===============================================================
    // Return flags (disable matched/onlyA/onlyB)
    // ===============================================================

    @Nested
    @DisplayName("execute() - Return flags")
    class ReturnFlagsTests {

        @Test
        @DisplayName("Should return empty matched when returnMatched is false")
        void shouldReturnEmptyMatchedWhenFlagIsFalse() {
            List<Map<String, Object>> datasetA = List.of(Map.of("id", "1"));
            List<Map<String, Object>> datasetB = List.of(Map.of("id", "1"));

            ExecutionContext ctx = contextWithDatasets(datasetA, datasetB);
            Core.CompareDatasetsConfig config = new Core.CompareDatasetsConfig(
                "datasetA", "datasetB", List.of("id"), false, true, true
            );

            CompareDatasetsNode node = new CompareDatasetsNode("core:compare", config);
            NodeExecutionResult result = node.execute(ctx);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<?> matched = (List<?>) result.output().get("matched");
            assertTrue(matched.isEmpty());
            assertEquals(0, result.output().get("matchedCount"));
        }

        @Test
        @DisplayName("Should return empty onlyInA when returnOnlyA is false")
        void shouldReturnEmptyOnlyInAWhenFlagIsFalse() {
            List<Map<String, Object>> datasetA = List.of(Map.of("id", "1"));
            List<Map<String, Object>> datasetB = List.of();

            ExecutionContext ctx = contextWithDatasets(datasetA, datasetB);
            Core.CompareDatasetsConfig config = new Core.CompareDatasetsConfig(
                "datasetA", "datasetB", List.of("id"), true, false, true
            );

            CompareDatasetsNode node = new CompareDatasetsNode("core:compare", config);
            NodeExecutionResult result = node.execute(ctx);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<?> onlyInA = (List<?>) result.output().get("onlyInA");
            assertTrue(onlyInA.isEmpty());
            assertEquals(0, result.output().get("onlyInACount"));
        }

        @Test
        @DisplayName("Should return empty onlyInB when returnOnlyB is false")
        void shouldReturnEmptyOnlyInBWhenFlagIsFalse() {
            List<Map<String, Object>> datasetA = List.of();
            List<Map<String, Object>> datasetB = List.of(Map.of("id", "1"));

            ExecutionContext ctx = contextWithDatasets(datasetA, datasetB);
            Core.CompareDatasetsConfig config = new Core.CompareDatasetsConfig(
                "datasetA", "datasetB", List.of("id"), true, true, false
            );

            CompareDatasetsNode node = new CompareDatasetsNode("core:compare", config);
            NodeExecutionResult result = node.execute(ctx);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<?> onlyInB = (List<?>) result.output().get("onlyInB");
            assertTrue(onlyInB.isEmpty());
            assertEquals(0, result.output().get("onlyInBCount"));
        }

        @Test
        @DisplayName("Should disable all return flags and still return counts zero")
        void shouldDisableAllReturnFlags() {
            List<Map<String, Object>> datasetA = List.of(
                Map.of("id", "1"), Map.of("id", "2")
            );
            List<Map<String, Object>> datasetB = List.of(
                Map.of("id", "1"), Map.of("id", "3")
            );

            ExecutionContext ctx = contextWithDatasets(datasetA, datasetB);
            Core.CompareDatasetsConfig config = new Core.CompareDatasetsConfig(
                "datasetA", "datasetB", List.of("id"), false, false, false
            );

            CompareDatasetsNode node = new CompareDatasetsNode("core:compare", config);
            NodeExecutionResult result = node.execute(ctx);

            assertTrue(result.isSuccess());
            assertEquals(0, result.output().get("matchedCount"));
            assertEquals(0, result.output().get("onlyInACount"));
            assertEquals(0, result.output().get("onlyInBCount"));
            // totalA and totalB still reflect actual dataset sizes
            assertEquals(2, result.output().get("totalA"));
            assertEquals(2, result.output().get("totalB"));
        }

        @Test
        @DisplayName("Should still report totalA and totalB even when flags are disabled")
        void shouldReportTotalsEvenWhenFlagsDisabled() {
            List<Map<String, Object>> datasetA = List.of(Map.of("id", "1"));
            List<Map<String, Object>> datasetB = List.of(Map.of("id", "2"), Map.of("id", "3"));

            ExecutionContext ctx = contextWithDatasets(datasetA, datasetB);
            Core.CompareDatasetsConfig config = new Core.CompareDatasetsConfig(
                "datasetA", "datasetB", List.of("id"), false, false, false
            );

            CompareDatasetsNode node = new CompareDatasetsNode("core:compare", config);
            NodeExecutionResult result = node.execute(ctx);

            assertEquals(1, result.output().get("totalA"));
            assertEquals(2, result.output().get("totalB"));
        }
    }

    // ===============================================================
    // Metadata tests
    // ===============================================================

    @Nested
    @DisplayName("execute() - Metadata")
    class MetadataTests {

        @Test
        @DisplayName("Should include mandatory metadata fields")
        void shouldIncludeMandatoryMetadata() {
            ExecutionContext ctx = contextWithDatasets(List.of(), List.of());
            Core.CompareDatasetsConfig config = new Core.CompareDatasetsConfig(
                "datasetA", "datasetB", List.of("id"), true, true, true
            );

            CompareDatasetsNode node = new CompareDatasetsNode("core:compare", config);
            NodeExecutionResult result = node.execute(ctx);

            assertEquals("COMPARE_DATASETS", result.output().get("node_type"));
            assertEquals(0, result.output().get("item_index"));
            assertEquals(0, result.output().get("itemIndex"));
            assertEquals("item-1", result.output().get("item_id"));
            assertNotNull(result.output().get("resolved_params"));
            assertEquals(true, result.output().get("success"));
        }

        @Test
        @DisplayName("Should include matchFields in output")
        void shouldIncludeMatchFieldsInOutput() {
            ExecutionContext ctx = contextWithDatasets(List.of(), List.of());
            Core.CompareDatasetsConfig config = new Core.CompareDatasetsConfig(
                "datasetA", "datasetB", List.of("id", "email"), true, true, true
            );

            CompareDatasetsNode node = new CompareDatasetsNode("core:compare", config);
            NodeExecutionResult result = node.execute(ctx);

            @SuppressWarnings("unchecked")
            List<String> matchFields = (List<String>) result.output().get("matchFields");
            assertEquals(2, matchFields.size());
            assertEquals("id", matchFields.get(0));
            assertEquals("email", matchFields.get(1));
        }

        @Test
        @DisplayName("Should include resolved_params with config details")
        void shouldIncludeInputDataWithConfigDetails() {
            ExecutionContext ctx = contextWithDatasets(List.of(), List.of());
            Core.CompareDatasetsConfig config = new Core.CompareDatasetsConfig(
                "datasetA", "datasetB", List.of("id"), false, true, false
            );

            CompareDatasetsNode node = new CompareDatasetsNode("core:compare", config);
            NodeExecutionResult result = node.execute(ctx);

            @SuppressWarnings("unchecked")
            Map<String, Object> inputData = (Map<String, Object>) result.output().get("resolved_params");
            assertNotNull(inputData);
            // Node stores actual datasets (lists), not config string names
            assertEquals(List.of(), inputData.get("datasetA"));
            assertEquals(List.of(), inputData.get("datasetB"));
            assertEquals(0, inputData.get("datasetA_count"));
            assertEquals(0, inputData.get("datasetB_count"));
            assertEquals(List.of("id"), inputData.get("matchFields"));
            assertEquals(false, inputData.get("returnMatched"));
            assertEquals(true, inputData.get("returnOnlyA"));
            assertEquals(false, inputData.get("returnOnlyB"));
        }
    }

    // ===============================================================
    // Builder tests
    // ===============================================================

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("Should create node using builder")
        void shouldCreateNodeUsingBuilder() {
            Core.CompareDatasetsConfig config = new Core.CompareDatasetsConfig(
                "inputA", "inputB", List.of("id"), true, true, true
            );

            CompareDatasetsNode node = CompareDatasetsNode.builder()
                .nodeId("core:compare_data")
                .config(config)
                .build();

            assertEquals("core:compare_data", node.getNodeId());
            assertEquals(NodeType.COMPARE_DATASETS, node.getType());
            assertNotNull(node.getCompareDatasetsConfig());
        }

        @Test
        @DisplayName("Should create node with null config using builder")
        void shouldCreateNodeWithNullConfigUsingBuilder() {
            CompareDatasetsNode node = CompareDatasetsNode.builder()
                .nodeId("core:compare")
                .config(null)
                .build();

            assertEquals("core:compare", node.getNodeId());
            assertNull(node.getCompareDatasetsConfig());
        }

        @Test
        @DisplayName("Should return builder instance for fluent chaining")
        void shouldReturnBuilderInstanceForFluentChaining() {
            CompareDatasetsNode.Builder builder = CompareDatasetsNode.builder();
            assertSame(builder, builder.nodeId("test"));
            assertSame(builder, builder.config(null));
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
            CompareDatasetsNode node = new CompareDatasetsNode("core:compare", null);

            ExecutionNode successor1 = createMockNode("mcp:next1");
            ExecutionNode successor2 = createMockNode("mcp:next2");
            node.addSuccessor(successor1);
            node.addSuccessor(successor2);

            NodeExecutionResult result = NodeExecutionResult.success("core:compare", Map.of());

            List<ExecutionNode> nextNodes = node.getNextNodes(result);
            assertEquals(2, nextNodes.size());
        }

        @Test
        @DisplayName("Should return empty list on failure")
        void shouldReturnEmptyListOnFailure() {
            CompareDatasetsNode node = new CompareDatasetsNode("core:compare", null);

            ExecutionNode successor = createMockNode("mcp:next");
            node.addSuccessor(successor);

            NodeExecutionResult result = NodeExecutionResult.failure("core:compare", "Error");

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
            CompareDatasetsNode node = new CompareDatasetsNode("core:compare", null);
            NodeExecutionResult result = NodeExecutionResult.success("core:compare", Map.of());
            assertDoesNotThrow(() -> node.onComplete(baseContext, result));
        }

        @Test
        @DisplayName("Should not throw exception on failure result")
        void shouldNotThrowExceptionOnFailureResult() {
            CompareDatasetsNode node = new CompareDatasetsNode("core:compare", null);
            NodeExecutionResult result = NodeExecutionResult.failure("core:compare", "Error");
            assertDoesNotThrow(() -> node.onComplete(baseContext, result));
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
