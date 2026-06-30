package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.OutputFieldDef;
import com.apimarketplace.orchestrator.domain.workflow.Core;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SummarizeNode.
 * SummarizeNode aggregates data using operations like sum, avg, count, min, max,
 * countDistinct, concatenate - with optional group-by fields.
 *
 * The node requires config.input() to be non-null/non-blank, and uses
 * templateAdapter to resolve the input expression into a list of items.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SummarizeNode")
class SummarizeNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    @Mock
    private V2TemplateAdapter mockTemplateAdapter;

    private ExecutionContext context;

    /** Test data: 5 employees across 2 departments and 2 cities. */
    private List<Map<String, Object>> testItems;

    @BeforeEach
    void setUp() {
        context = ExecutionContext.create(
            "run-1",
            "workflow-run-1",
            "tenant-1",
            "item-1",
            0,
            new HashMap<>(),
            mockPlan
        );

        testItems = List.of(
            Map.of("name", "Alice", "department", "Engineering", "salary", 90000, "city", "Paris"),
            Map.of("name", "Bob", "department", "Engineering", "salary", 85000, "city", "London"),
            Map.of("name", "Charlie", "department", "Marketing", "salary", 75000, "city", "Paris"),
            Map.of("name", "Diana", "department", "Marketing", "salary", 80000, "city", "London"),
            Map.of("name", "Eve", "department", "Engineering", "salary", 95000, "city", "Paris")
        );
    }

    /**
     * Helper: build a SummarizeNode with the given config, set templateAdapter,
     * and stub the adapter to return the provided items.
     */
    private SummarizeNode buildNode(Core.SummarizeConfig config, List<Map<String, Object>> items) {
        SummarizeNode node = SummarizeNode.builder()
            .nodeId("core:summarize")
            .summarizeConfig(config)
            .build();
        node.setTemplateAdapter(mockTemplateAdapter);
        if (items != null) {
            when(mockTemplateAdapter.resolveTemplates(anyMap(), any()))
                .thenReturn(Map.of("__input__", items));
        }
        return node;
    }

    // ===============================================================
    // Constructor tests
    // ===============================================================

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create SummarizeNode with nodeId and config")
        void shouldCreateSummarizeNodeWithNodeIdAndConfig() {
            Core.SummarizeConfig config = new Core.SummarizeConfig(
                List.of(new Core.SummarizeAggregation("salary", "sum", "total_salary")),
                List.of("department"),
                "{{items}}"
            );

            SummarizeNode node = new SummarizeNode("core:summarize", config);

            assertEquals("core:summarize", node.getNodeId());
            assertEquals(NodeType.SUMMARIZE, node.getType());
            assertNotNull(node.getConfig());
            assertEquals(1, node.getConfig().aggregations().size());
            assertEquals(1, node.getConfig().groupBy().size());
        }

        @Test
        @DisplayName("Should handle null config")
        void shouldHandleNullConfig() {
            SummarizeNode node = new SummarizeNode("core:summarize", null);

            assertNotNull(node.getConfig());
            assertTrue(node.getConfig().aggregations().isEmpty());
            assertTrue(node.getConfig().groupBy().isEmpty());
        }

        @Test
        @DisplayName("Should handle empty config")
        void shouldHandleEmptyConfig() {
            Core.SummarizeConfig config = new Core.SummarizeConfig(List.of(), List.of(), "{{items}}");

            SummarizeNode node = new SummarizeNode("core:summarize", config);

            assertNotNull(node.getConfig());
            assertTrue(node.getConfig().aggregations().isEmpty());
            assertTrue(node.getConfig().groupBy().isEmpty());
        }

        @Test
        @DisplayName("Should create SummarizeNode using builder")
        void shouldCreateSummarizeNodeUsingBuilder() {
            Core.SummarizeConfig config = new Core.SummarizeConfig(
                List.of(new Core.SummarizeAggregation("salary", "avg", "avg_salary")),
                List.of(),
                "{{items}}"
            );

            SummarizeNode node = SummarizeNode.builder()
                .nodeId("core:my_summarize")
                .summarizeConfig(config)
                .build();

            assertEquals("core:my_summarize", node.getNodeId());
            assertEquals(1, node.getConfig().aggregations().size());
        }
    }

    // ===============================================================
    // execute() - Input validation
    // ===============================================================

    @Nested
    @DisplayName("execute() - Input validation")
    class ExecuteInputValidationTests {

        @Test
        @DisplayName("Should fail when input is null")
        void shouldFailWhenInputIsNull() {
            Core.SummarizeConfig config = new Core.SummarizeConfig(
                List.of(new Core.SummarizeAggregation(null, "count", "total_count")),
                List.of(),
                null
            );

            SummarizeNode node = new SummarizeNode("core:summarize", config);
            node.setTemplateAdapter(mockTemplateAdapter);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("Input expression is required"));
        }

        @Test
        @DisplayName("Should fail when input is blank")
        void shouldFailWhenInputIsBlank() {
            Core.SummarizeConfig config = new Core.SummarizeConfig(
                List.of(new Core.SummarizeAggregation(null, "count", "total_count")),
                List.of(),
                "   "
            );

            SummarizeNode node = new SummarizeNode("core:summarize", config);
            node.setTemplateAdapter(mockTemplateAdapter);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("Input expression is required"));
        }
    }

    // ===============================================================
    // execute() - Count operation
    // ===============================================================

    @Nested
    @DisplayName("execute() - Count operation")
    class ExecuteCountTests {

        @Test
        @DisplayName("Should count all items")
        void shouldCountAllItems() {
            Core.SummarizeConfig config = new Core.SummarizeConfig(
                List.of(new Core.SummarizeAggregation(null, "count", "total_count")),
                List.of(),
                "{{items}}"
            );

            SummarizeNode node = buildNode(config, testItems);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(5, result.output().get("total_count"));
        }

        @Test
        @DisplayName("Should count items per group")
        void shouldCountItemsPerGroup() {
            Core.SummarizeConfig config = new Core.SummarizeConfig(
                List.of(new Core.SummarizeAggregation(null, "count", "dept_count")),
                List.of("department"),
                "{{items}}"
            );

            SummarizeNode node = buildNode(config, testItems);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> groups = (List<Map<String, Object>>) result.output().get("groups");
            assertNotNull(groups);
            assertEquals(2, groups.size());

            // Find Engineering group
            Map<String, Object> engGroup = groups.stream()
                .filter(g -> "Engineering".equals(g.get("department")))
                .findFirst().orElseThrow();
            assertEquals(3, engGroup.get("dept_count"));

            // Find Marketing group
            Map<String, Object> mktGroup = groups.stream()
                .filter(g -> "Marketing".equals(g.get("department")))
                .findFirst().orElseThrow();
            assertEquals(2, mktGroup.get("dept_count"));
        }
    }

    // ===============================================================
    // execute() - Sum operation
    // ===============================================================

    @Nested
    @DisplayName("execute() - Sum operation")
    class ExecuteSumTests {

        @Test
        @DisplayName("Should sum numeric field across all items")
        void shouldSumNumericFieldAcrossAllItems() {
            Core.SummarizeConfig config = new Core.SummarizeConfig(
                List.of(new Core.SummarizeAggregation("salary", "sum", "total_salary")),
                List.of(),
                "{{items}}"
            );

            SummarizeNode node = buildNode(config, testItems);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            // 90000 + 85000 + 75000 + 80000 + 95000 = 425000
            assertEquals(425000.0, result.output().get("total_salary"));
        }

        @Test
        @DisplayName("Should sum per group")
        void shouldSumPerGroup() {
            Core.SummarizeConfig config = new Core.SummarizeConfig(
                List.of(new Core.SummarizeAggregation("salary", "sum", "total_salary")),
                List.of("department"),
                "{{items}}"
            );

            SummarizeNode node = buildNode(config, testItems);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> groups = (List<Map<String, Object>>) result.output().get("groups");

            Map<String, Object> engGroup = groups.stream()
                .filter(g -> "Engineering".equals(g.get("department")))
                .findFirst().orElseThrow();
            // 90000 + 85000 + 95000 = 270000
            assertEquals(270000.0, engGroup.get("total_salary"));
        }

        @Test
        @DisplayName("Should treat non-numeric values as 0 in sum")
        void shouldTreatNonNumericValuesAsZeroInSum() {
            List<Map<String, Object>> nonNumericItems = List.of(
                Map.of("name", "Alice", "score", "not_a_number")
            );

            Core.SummarizeConfig config = new Core.SummarizeConfig(
                List.of(new Core.SummarizeAggregation("score", "sum", "total_score")),
                List.of(),
                "{{items}}"
            );

            SummarizeNode node = buildNode(config, nonNumericItems);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            // Non-numeric treated as 0
            assertEquals(0.0, result.output().get("total_score"));
        }
    }

    // ===============================================================
    // execute() - Avg operation
    // ===============================================================

    @Nested
    @DisplayName("execute() - Avg operation")
    class ExecuteAvgTests {

        @Test
        @DisplayName("Should calculate average across all items")
        void shouldCalculateAverageAcrossAllItems() {
            Core.SummarizeConfig config = new Core.SummarizeConfig(
                List.of(new Core.SummarizeAggregation("salary", "avg", "avg_salary")),
                List.of(),
                "{{items}}"
            );

            SummarizeNode node = buildNode(config, testItems);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            // 425000 / 5 = 85000
            assertEquals(85000.0, result.output().get("avg_salary"));
        }

        @Test
        @DisplayName("Should calculate average per group")
        void shouldCalculateAveragePerGroup() {
            Core.SummarizeConfig config = new Core.SummarizeConfig(
                List.of(new Core.SummarizeAggregation("salary", "avg", "avg_salary")),
                List.of("department"),
                "{{items}}"
            );

            SummarizeNode node = buildNode(config, testItems);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> groups = (List<Map<String, Object>>) result.output().get("groups");

            Map<String, Object> engGroup = groups.stream()
                .filter(g -> "Engineering".equals(g.get("department")))
                .findFirst().orElseThrow();
            // 270000 / 3 = 90000
            assertEquals(90000.0, engGroup.get("avg_salary"));
        }

        @Test
        @DisplayName("Should return 0 for avg with empty resolved list")
        void shouldReturnZeroForAvgWithEmptyItems() {
            Core.SummarizeConfig config = new Core.SummarizeConfig(
                List.of(new Core.SummarizeAggregation("salary", "avg", "avg_salary")),
                List.of(),
                "{{items}}"
            );

            SummarizeNode node = buildNode(config, List.of());
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(0, result.output().get("total_items"));
        }
    }

    // ===============================================================
    // execute() - Min operation
    // ===============================================================

    @Nested
    @DisplayName("execute() - Min operation")
    class ExecuteMinTests {

        @Test
        @DisplayName("Should find minimum numeric value")
        void shouldFindMinimumNumericValue() {
            Core.SummarizeConfig config = new Core.SummarizeConfig(
                List.of(new Core.SummarizeAggregation("salary", "min", "min_salary")),
                List.of(),
                "{{items}}"
            );

            SummarizeNode node = buildNode(config, testItems);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(75000.0, result.output().get("min_salary"));
        }

        @Test
        @DisplayName("Should find minimum string value when non-numeric")
        void shouldFindMinimumStringValue() {
            Core.SummarizeConfig config = new Core.SummarizeConfig(
                List.of(new Core.SummarizeAggregation("name", "min", "first_name")),
                List.of(),
                "{{items}}"
            );

            SummarizeNode node = buildNode(config, testItems);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            // Alice < Bob < Charlie < Diana < Eve
            assertEquals("Alice", result.output().get("first_name"));
        }
    }

    // ===============================================================
    // execute() - Max operation
    // ===============================================================

    @Nested
    @DisplayName("execute() - Max operation")
    class ExecuteMaxTests {

        @Test
        @DisplayName("Should find maximum numeric value")
        void shouldFindMaximumNumericValue() {
            Core.SummarizeConfig config = new Core.SummarizeConfig(
                List.of(new Core.SummarizeAggregation("salary", "max", "max_salary")),
                List.of(),
                "{{items}}"
            );

            SummarizeNode node = buildNode(config, testItems);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(95000.0, result.output().get("max_salary"));
        }

        @Test
        @DisplayName("Should find maximum string value when non-numeric")
        void shouldFindMaximumStringValue() {
            Core.SummarizeConfig config = new Core.SummarizeConfig(
                List.of(new Core.SummarizeAggregation("name", "max", "last_name")),
                List.of(),
                "{{items}}"
            );

            SummarizeNode node = buildNode(config, testItems);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("Eve", result.output().get("last_name"));
        }
    }

    // ===============================================================
    // execute() - CountDistinct operation
    // ===============================================================

    @Nested
    @DisplayName("execute() - CountDistinct operation")
    class ExecuteCountDistinctTests {

        @Test
        @DisplayName("Should count distinct values in a field")
        void shouldCountDistinctValuesInField() {
            Core.SummarizeConfig config = new Core.SummarizeConfig(
                List.of(new Core.SummarizeAggregation("department", "countDistinct", "unique_depts")),
                List.of(),
                "{{items}}"
            );

            SummarizeNode node = buildNode(config, testItems);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            // Engineering, Marketing = 2
            assertEquals(2L, result.output().get("unique_depts"));
        }

        @Test
        @DisplayName("Should count distinct cities")
        void shouldCountDistinctCities() {
            Core.SummarizeConfig config = new Core.SummarizeConfig(
                List.of(new Core.SummarizeAggregation("city", "countDistinct", "unique_cities")),
                List.of(),
                "{{items}}"
            );

            SummarizeNode node = buildNode(config, testItems);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            // Paris, London = 2
            assertEquals(2L, result.output().get("unique_cities"));
        }
    }

    // ===============================================================
    // execute() - Concatenate operation
    // ===============================================================

    @Nested
    @DisplayName("execute() - Concatenate operation")
    class ExecuteConcatenateTests {

        @Test
        @DisplayName("Should concatenate string values")
        void shouldConcatenateStringValues() {
            Core.SummarizeConfig config = new Core.SummarizeConfig(
                List.of(new Core.SummarizeAggregation("name", "concatenate", "all_names")),
                List.of(),
                "{{items}}"
            );

            SummarizeNode node = buildNode(config, testItems);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            String names = (String) result.output().get("all_names");
            assertNotNull(names);
            assertTrue(names.contains("Alice"));
            assertTrue(names.contains("Bob"));
            assertTrue(names.contains("Charlie"));
            assertTrue(names.contains("Diana"));
            assertTrue(names.contains("Eve"));
        }

        @Test
        @DisplayName("Should concatenate per group")
        void shouldConcatenatePerGroup() {
            Core.SummarizeConfig config = new Core.SummarizeConfig(
                List.of(new Core.SummarizeAggregation("name", "concatenate", "names")),
                List.of("department"),
                "{{items}}"
            );

            SummarizeNode node = buildNode(config, testItems);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> groups = (List<Map<String, Object>>) result.output().get("groups");

            Map<String, Object> engGroup = groups.stream()
                .filter(g -> "Engineering".equals(g.get("department")))
                .findFirst().orElseThrow();
            String engNames = (String) engGroup.get("names");
            assertTrue(engNames.contains("Alice"));
            assertTrue(engNames.contains("Bob"));
            assertTrue(engNames.contains("Eve"));
            assertFalse(engNames.contains("Charlie"));
        }
    }

    // ===============================================================
    // execute() - Multiple aggregations
    // ===============================================================

    @Nested
    @DisplayName("execute() - Multiple aggregations")
    class ExecuteMultipleAggregationsTests {

        @Test
        @DisplayName("Should apply multiple aggregations simultaneously")
        void shouldApplyMultipleAggregationsSimultaneously() {
            Core.SummarizeConfig config = new Core.SummarizeConfig(
                List.of(
                    new Core.SummarizeAggregation("salary", "sum", "total_salary"),
                    new Core.SummarizeAggregation("salary", "avg", "avg_salary"),
                    new Core.SummarizeAggregation(null, "count", "headcount"),
                    new Core.SummarizeAggregation("salary", "min", "min_salary"),
                    new Core.SummarizeAggregation("salary", "max", "max_salary")
                ),
                List.of(),
                "{{items}}"
            );

            SummarizeNode node = buildNode(config, testItems);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(425000.0, result.output().get("total_salary"));
            assertEquals(85000.0, result.output().get("avg_salary"));
            assertEquals(5, result.output().get("headcount"));
            assertEquals(75000.0, result.output().get("min_salary"));
            assertEquals(95000.0, result.output().get("max_salary"));
        }

        @Test
        @DisplayName("Should apply multiple aggregations with group by")
        void shouldApplyMultipleAggregationsWithGroupBy() {
            Core.SummarizeConfig config = new Core.SummarizeConfig(
                List.of(
                    new Core.SummarizeAggregation("salary", "sum", "total_salary"),
                    new Core.SummarizeAggregation(null, "count", "headcount")
                ),
                List.of("department"),
                "{{items}}"
            );

            SummarizeNode node = buildNode(config, testItems);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(2, result.output().get("total_groups"));
            assertEquals(5, result.output().get("total_items"));
        }
    }

    // ===============================================================
    // execute() - Group by tests
    // ===============================================================

    @Nested
    @DisplayName("execute() - Group by")
    class ExecuteGroupByTests {

        @Test
        @DisplayName("Should group by single field")
        void shouldGroupBySingleField() {
            Core.SummarizeConfig config = new Core.SummarizeConfig(
                List.of(new Core.SummarizeAggregation(null, "count", "count")),
                List.of("department"),
                "{{items}}"
            );

            SummarizeNode node = buildNode(config, testItems);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(2, result.output().get("total_groups"));
        }

        @Test
        @DisplayName("Should group by multiple fields")
        void shouldGroupByMultipleFields() {
            Core.SummarizeConfig config = new Core.SummarizeConfig(
                List.of(new Core.SummarizeAggregation(null, "count", "count")),
                List.of("department", "city"),
                "{{items}}"
            );

            SummarizeNode node = buildNode(config, testItems);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            // Engineering+Paris(Alice,Eve), Engineering+London(Bob), Marketing+Paris(Charlie), Marketing+London(Diana)
            assertEquals(4, result.output().get("total_groups"));
        }

        @Test
        @DisplayName("Should include group-by field values in group results")
        void shouldIncludeGroupByFieldValuesInGroupResults() {
            Core.SummarizeConfig config = new Core.SummarizeConfig(
                List.of(new Core.SummarizeAggregation(null, "count", "count")),
                List.of("department"),
                "{{items}}"
            );

            SummarizeNode node = buildNode(config, testItems);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> groups = (List<Map<String, Object>>) result.output().get("groups");

            for (Map<String, Object> group : groups) {
                assertNotNull(group.get("department"), "Each group should have the department field");
                assertNotNull(group.get("group_key"));
                assertNotNull(group.get("group_count"));
            }
        }
    }

    // ===============================================================
    // execute() - Empty resolved list
    // ===============================================================

    @Nested
    @DisplayName("execute() - Empty resolved list")
    class ExecuteEmptyListTests {

        @Test
        @DisplayName("Should return zero total_items for empty resolved list")
        void shouldReturnZeroTotalItemsForEmptyList() {
            Core.SummarizeConfig config = new Core.SummarizeConfig(
                List.of(new Core.SummarizeAggregation("salary", "sum", "total_salary")),
                List.of(),
                "{{items}}"
            );

            SummarizeNode node = buildNode(config, List.of());
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(0, result.output().get("total_items"));
            assertEquals(0.0, result.output().get("total_salary"));
        }

        @Test
        @DisplayName("Should return zero count for empty resolved list")
        void shouldReturnZeroCountForEmptyList() {
            Core.SummarizeConfig config = new Core.SummarizeConfig(
                List.of(new Core.SummarizeAggregation(null, "count", "total_count")),
                List.of(),
                "{{items}}"
            );

            SummarizeNode node = buildNode(config, List.of());
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(0, result.output().get("total_count"));
        }
    }

    // ===============================================================
    // execute() - Error handling
    // ===============================================================

    @Nested
    @DisplayName("execute() - Error handling")
    class ExecuteErrorHandlingTests {

        @Test
        @DisplayName("Should return null for unknown operation")
        void shouldReturnNullForUnknownOperation() {
            Core.SummarizeConfig config = new Core.SummarizeConfig(
                List.of(new Core.SummarizeAggregation("salary", "median", "median_salary")),
                List.of(),
                "{{items}}"
            );

            SummarizeNode node = buildNode(config, testItems);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertNull(result.output().get("median_salary"));
        }

        @Test
        @DisplayName("Should handle empty aggregations list")
        void shouldHandleEmptyAggregationsList() {
            Core.SummarizeConfig config = new Core.SummarizeConfig(List.of(), List.of(), "{{items}}");

            SummarizeNode node = buildNode(config, testItems);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(0, result.output().get("aggregation_count"));
        }

        @Test
        @DisplayName("Should default alias from operation and field when alias is null")
        void shouldDefaultAliasFromOperationAndField() {
            Core.SummarizeConfig config = new Core.SummarizeConfig(
                List.of(new Core.SummarizeAggregation("salary", "sum", null)),
                List.of(),
                "{{items}}"
            );

            SummarizeNode node = buildNode(config, testItems);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            // Default alias: "sum_salary"
            assertNotNull(result.output().get("sum_salary"));
            assertEquals(425000.0, result.output().get("sum_salary"));
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
            Core.SummarizeConfig config = new Core.SummarizeConfig(List.of(), List.of(), "{{items}}");

            SummarizeNode node = buildNode(config, testItems);
            NodeExecutionResult result = node.execute(context);

            assertEquals("SUMMARIZE", result.output().get("node_type"));
            assertEquals(0, result.output().get("item_index"));
            assertEquals(0, result.output().get("itemIndex"));
            assertEquals("item-1", result.output().get("item_id"));
            assertNotNull(result.output().get("resolved_params"));
        }

        @Test
        @DisplayName("Should include total_groups and total_items in output")
        void shouldIncludeTotalGroupsAndTotalItemsInOutput() {
            Core.SummarizeConfig config = new Core.SummarizeConfig(
                List.of(new Core.SummarizeAggregation(null, "count", "count")),
                List.of("department"),
                "{{items}}"
            );

            SummarizeNode node = buildNode(config, testItems);
            NodeExecutionResult result = node.execute(context);

            assertNotNull(result.output().get("total_groups"));
            assertNotNull(result.output().get("total_items"));
            assertNotNull(result.output().get("aggregation_count"));
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
            SummarizeNode node = new SummarizeNode("core:summarize",
                new Core.SummarizeConfig(List.of(), List.of(), "{{items}}"));

            ExecutionNode successor1 = createMockNode("mcp:next1");
            ExecutionNode successor2 = createMockNode("mcp:next2");
            node.addSuccessor(successor1);
            node.addSuccessor(successor2);

            NodeExecutionResult result = NodeExecutionResult.success("core:summarize", Map.of());

            List<ExecutionNode> nextNodes = node.getNextNodes(result);
            assertEquals(2, nextNodes.size());
        }

        @Test
        @DisplayName("Should return empty list on failure")
        void shouldReturnEmptyListOnFailure() {
            SummarizeNode node = new SummarizeNode("core:summarize",
                new Core.SummarizeConfig(List.of(), List.of(), "{{items}}"));

            ExecutionNode successor = createMockNode("mcp:next");
            node.addSuccessor(successor);

            NodeExecutionResult result = NodeExecutionResult.failure("core:summarize", "Error");

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
            SummarizeNode node = new SummarizeNode("core:summarize",
                new Core.SummarizeConfig(List.of(), List.of(), "{{items}}"));
            NodeExecutionResult result = NodeExecutionResult.success("core:summarize", Map.of());
            assertDoesNotThrow(() -> node.onComplete(context, result));
        }

        @Test
        @DisplayName("Should not throw exception on failure result")
        void shouldNotThrowExceptionOnFailureResult() {
            SummarizeNode node = new SummarizeNode("core:summarize",
                new Core.SummarizeConfig(List.of(), List.of(), "{{items}}"));
            NodeExecutionResult result = NodeExecutionResult.failure("core:summarize", "Error");
            assertDoesNotThrow(() -> node.onComplete(context, result));
        }
    }

    // ===============================================================
    // Builder tests
    // ===============================================================

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("Should build node with all fields")
        void shouldBuildNodeWithAllFields() {
            Core.SummarizeConfig config = new Core.SummarizeConfig(
                List.of(
                    new Core.SummarizeAggregation("salary", "sum", "total"),
                    new Core.SummarizeAggregation("salary", "avg", "average")
                ),
                List.of("department"),
                "{{items}}"
            );

            SummarizeNode node = SummarizeNode.builder()
                .nodeId("core:stats")
                .summarizeConfig(config)
                .build();

            assertEquals("core:stats", node.getNodeId());
            assertEquals(2, node.getConfig().aggregations().size());
            assertEquals(1, node.getConfig().groupBy().size());
        }

        @Test
        @DisplayName("Should build node with null config")
        void shouldBuildNodeWithNullConfig() {
            SummarizeNode node = SummarizeNode.builder()
                .nodeId("core:summarize")
                .summarizeConfig(null)
                .build();

            assertNotNull(node.getConfig());
            assertTrue(node.getConfig().aggregations().isEmpty());
        }
    }

    // ===============================================================
    // SummarizeNodeSpec - NodeDefinition output documentation
    // ===============================================================

    @Nested
    @DisplayName("SummarizeNodeSpec - NodeDefinition documents dynamic alias hint")
    class SummarizeNodeSpecDocumentationTests {

        @Test
        @DisplayName("node-contracts.schema.json declares a '{alias}' placeholder for the summarize node - confirms the dynamic alias contract is visible to contract consumers")
        void nodeContractsSchemaContainsSummarizeDynamicAliasPlaceholder() throws IOException {
            // Walk up from the test class location to find the repo root, then read the
            // shared contracts file.  The file is at shared/contracts/node-contracts.schema.json
            // relative to the repository root (two levels above backend/).
            Path repoRoot = Paths.get(System.getProperty("user.dir"))  // backend/orchestrator-service
                    .getParent()  // backend/
                    .getParent(); // repo root
            Path schemaPath = repoRoot.resolve("shared/contracts/node-contracts.schema.json");

            assertThat(schemaPath.toFile().exists())
                    .as("node-contracts.schema.json must exist at %s", schemaPath)
                    .isTrue();

            String schemaContent = Files.readString(schemaPath);

            // The summarize section must declare a {alias} placeholder output so that
            // contract consumers (codegen, frontend) know aggregation aliases are dynamic.
            assertThat(schemaContent)
                    .as("node-contracts.schema.json must contain a '{alias}' placeholder entry in the summarize outputs section")
                    .contains("\"{alias}\"");
        }

        @Test
        @DisplayName("customTransform passes through flattened aggregation aliases - regression: aliases must reach the DB")
        void customTransformPreservesAliases() {
            SummarizeNodeSpec spec = new SummarizeNodeSpec();

            Map<String, Object> backendOutput = new HashMap<>();
            backendOutput.put("groups", List.of());
            backendOutput.put("total_groups", 1);
            backendOutput.put("total_items", 5);
            backendOutput.put("aggregation_count", 2);
            // Flattened aliases written by SummarizeNode when no groupBy
            backendOutput.put("total_salary", 425000.0);
            backendOutput.put("avg_salary", 85000.0);
            // Engine metadata - must be stripped
            backendOutput.put("node_type", "SUMMARIZE");
            backendOutput.put("item_index", 0);
            backendOutput.put("resolved_params", Map.of());

            Map<String, Object> dbOutput = spec.customTransform(backendOutput);

            assertThat(dbOutput).containsEntry("total_salary", 425000.0);
            assertThat(dbOutput).containsEntry("avg_salary", 85000.0);
            assertThat(dbOutput).containsEntry("groups", List.of());
            assertThat(dbOutput).containsEntry("total_groups", 1);
            // Engine metadata must NOT be in the DB output
            assertThat(dbOutput).doesNotContainKey("node_type");
            assertThat(dbOutput).doesNotContainKey("item_index");
        }

        @Test
        @DisplayName("SummarizeNode with no groupBy flattens alias to root level - runtime shape matches DB shape")
        void noGroupByFlattensAliasesToRoot() {
            Core.SummarizeConfig config = new Core.SummarizeConfig(
                List.of(new Core.SummarizeAggregation("salary", "sum", "total_salary")),
                List.of(),
                "{{items}}"
            );

            SummarizeNode node = SummarizeNode.builder()
                .nodeId("core:summarize")
                .summarizeConfig(config)
                .build();
            node.setTemplateAdapter(mockTemplateAdapter);
            when(mockTemplateAdapter.resolveTemplates(anyMap(), any()))
                .thenReturn(Map.of("__input__", testItems));

            NodeExecutionResult result = node.execute(context);

            // Runtime output has the alias at root - this is what customTransform must preserve
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.output()).containsKey("total_salary");

            // customTransform must yield the same shape for the DB
            SummarizeNodeSpec spec = new SummarizeNodeSpec();
            Map<String, Object> dbOutput = spec.customTransform(result.output());

            assertThat(dbOutput).containsEntry("total_salary", result.output().get("total_salary"));
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
