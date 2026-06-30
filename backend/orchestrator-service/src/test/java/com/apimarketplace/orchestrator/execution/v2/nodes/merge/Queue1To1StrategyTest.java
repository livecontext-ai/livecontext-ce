package com.apimarketplace.orchestrator.execution.v2.nodes.merge;

import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Queue1To1Strategy Tests")
class Queue1To1StrategyTest {

    private Queue1To1Strategy strategy;

    @Mock
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        strategy = new Queue1To1Strategy();
    }

    @Nested
    @DisplayName("name()")
    class NameTests {

        @Test
        @DisplayName("Should return QUEUE_1_TO_1")
        void shouldReturnCorrectName() {
            assertEquals("QUEUE_1_TO_1", strategy.name());
        }
    }

    @Nested
    @DisplayName("canMerge()")
    class CanMergeTests {

        @Test
        @DisplayName("Should return true when all sources completed")
        void shouldReturnTrueWhenAllSourcesCompleted() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:step1", "mcp:step2");
            when(context.isCompleted("mcp:step1")).thenReturn(true);
            when(context.isCompleted("mcp:step2")).thenReturn(true);

            // When
            boolean result = strategy.canMerge(sourceNodeIds, context);

            // Then
            assertTrue(result);
        }

        @Test
        @DisplayName("Should return false when some sources incomplete")
        void shouldReturnFalseWhenSourcesIncomplete() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:step1", "mcp:step2");
            when(context.isCompleted("mcp:step1")).thenReturn(true);
            when(context.isCompleted("mcp:step2")).thenReturn(false);

            // When
            boolean result = strategy.canMerge(sourceNodeIds, context);

            // Then
            assertFalse(result);
        }

        @Test
        @DisplayName("Should return false when first source incomplete")
        void shouldReturnFalseWhenFirstSourceIncomplete() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:step1", "mcp:step2");
            when(context.isCompleted("mcp:step1")).thenReturn(false);

            // When
            boolean result = strategy.canMerge(sourceNodeIds, context);

            // Then
            assertFalse(result);
        }

        @Test
        @DisplayName("Should return true for empty source list")
        void shouldReturnTrueForEmptySourceList() {
            // Given
            List<String> sourceNodeIds = List.of();

            // When
            boolean result = strategy.canMerge(sourceNodeIds, context);

            // Then
            assertTrue(result);
        }

        @Test
        @DisplayName("Should return true for single completed source")
        void shouldReturnTrueForSingleCompletedSource() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:step1");
            when(context.isCompleted("mcp:step1")).thenReturn(true);

            // When
            boolean result = strategy.canMerge(sourceNodeIds, context);

            // Then
            assertTrue(result);
        }
    }

    @Nested
    @DisplayName("shouldSkip()")
    class ShouldSkipTests {

        @Test
        @DisplayName("Should return false when at least one source succeeded")
        void shouldReturnFalseWhenOneSourceSucceeded() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:step1", "mcp:step2");
            when(context.isSuccess("mcp:step1")).thenReturn(true);

            // When
            boolean result = strategy.shouldSkip(sourceNodeIds, context);

            // Then
            assertFalse(result);
        }

        @Test
        @DisplayName("Should return true when all sources failed/skipped")
        void shouldReturnTrueWhenAllSourcesFailedOrSkipped() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:step1", "mcp:step2");
            when(context.isSuccess("mcp:step1")).thenReturn(false);
            when(context.isSuccess("mcp:step2")).thenReturn(false);

            // When
            boolean result = strategy.shouldSkip(sourceNodeIds, context);

            // Then
            assertTrue(result);
        }

        @Test
        @DisplayName("Should return false when second source succeeded")
        void shouldReturnFalseWhenSecondSourceSucceeded() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:step1", "mcp:step2");
            when(context.isSuccess("mcp:step1")).thenReturn(false);
            when(context.isSuccess("mcp:step2")).thenReturn(true);

            // When
            boolean result = strategy.shouldSkip(sourceNodeIds, context);

            // Then
            assertFalse(result);
        }

        @Test
        @DisplayName("Should return true for empty source list")
        void shouldReturnTrueForEmptySourceList() {
            // Given
            List<String> sourceNodeIds = List.of();

            // When
            boolean result = strategy.shouldSkip(sourceNodeIds, context);

            // Then
            assertTrue(result);
        }
    }

    @Nested
    @DisplayName("merge()")
    class MergeTests {

        @Test
        @DisplayName("Should merge data from successful sources only")
        void shouldMergeDataFromSuccessfulSourcesOnly() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:step1", "mcp:step2");
            when(context.isSuccess("mcp:step1")).thenReturn(true);
            when(context.isSuccess("mcp:step2")).thenReturn(false);
            when(context.getStepOutput("mcp:step1")).thenReturn(Optional.of(Map.of("data", "value1")));

            // When
            Map<String, Object> result = strategy.merge(sourceNodeIds, context);

            // Then
            assertEquals("QUEUE_1_TO_1", result.get("strategy"));
            assertEquals(2, result.get("source_count"));
            assertEquals(1, result.get("success_count"));
            assertNotNull(result.get("sources"));
            assertNotNull(result.get("merged_items"));
        }

        @Test
        @DisplayName("Should merge data from all successful sources")
        void shouldMergeDataFromAllSuccessfulSources() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:step1", "mcp:step2");
            when(context.isSuccess("mcp:step1")).thenReturn(true);
            when(context.isSuccess("mcp:step2")).thenReturn(true);
            when(context.getStepOutput("mcp:step1")).thenReturn(Optional.of(Map.of("data", "value1")));
            when(context.getStepOutput("mcp:step2")).thenReturn(Optional.of(Map.of("data", "value2")));

            // When
            Map<String, Object> result = strategy.merge(sourceNodeIds, context);

            // Then
            assertEquals(2, result.get("source_count"));
            assertEquals(2, result.get("success_count"));

            @SuppressWarnings("unchecked")
            Map<String, Object> sources = (Map<String, Object>) result.get("sources");
            assertEquals(2, sources.size());
            assertTrue(sources.containsKey("mcp:step1"));
            assertTrue(sources.containsKey("mcp:step2"));
        }

        @Test
        @DisplayName("Should flatten list outputs into merged_items")
        void shouldFlattenListOutputs() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:step1", "mcp:step2");
            when(context.isSuccess("mcp:step1")).thenReturn(true);
            when(context.isSuccess("mcp:step2")).thenReturn(true);
            when(context.getStepOutput("mcp:step1")).thenReturn(Optional.of(List.of("a", "b")));
            when(context.getStepOutput("mcp:step2")).thenReturn(Optional.of(List.of("c", "d")));

            // When
            Map<String, Object> result = strategy.merge(sourceNodeIds, context);

            // Then
            @SuppressWarnings("unchecked")
            List<Object> mergedItems = (List<Object>) result.get("merged_items");
            assertEquals(4, mergedItems.size());
            assertTrue(mergedItems.contains("a"));
            assertTrue(mergedItems.contains("b"));
            assertTrue(mergedItems.contains("c"));
            assertTrue(mergedItems.contains("d"));
            assertEquals(4, result.get("item_count"));
        }

        @Test
        @DisplayName("Should extract items from map with 'items' key")
        void shouldExtractItemsFromMap() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:step1");
            when(context.isSuccess("mcp:step1")).thenReturn(true);
            Map<String, Object> outputWithItems = Map.of("items", List.of("x", "y", "z"));
            when(context.getStepOutput("mcp:step1")).thenReturn(Optional.of(outputWithItems));

            // When
            Map<String, Object> result = strategy.merge(sourceNodeIds, context);

            // Then
            @SuppressWarnings("unchecked")
            List<Object> mergedItems = (List<Object>) result.get("merged_items");
            assertEquals(3, mergedItems.size(), "Should only contain extracted items, not the map itself");
            assertTrue(mergedItems.contains("x"));
            assertTrue(mergedItems.contains("y"));
            assertTrue(mergedItems.contains("z"));
        }

        @Test
        @DisplayName("Should not double-add when map contains 'items' key with list value")
        void shouldNotDoubleAddWhenMapContainsItemsKey() {
            // Given: a source output that is a Map with an "items" key containing a List
            List<String> sourceNodeIds = List.of("mcp:step1");
            when(context.isSuccess("mcp:step1")).thenReturn(true);
            Map<String, Object> outputWithItems = Map.of(
                "items", List.of("x", "y", "z"),
                "metadata", "some_info"
            );
            when(context.getStepOutput("mcp:step1")).thenReturn(Optional.of(outputWithItems));

            // When
            Map<String, Object> result = strategy.merge(sourceNodeIds, context);

            // Then: only the 3 extracted items should appear, NOT the entire map as a 4th element
            @SuppressWarnings("unchecked")
            List<Object> mergedItems = (List<Object>) result.get("merged_items");
            assertEquals(3, mergedItems.size(), "Should contain only extracted items, not the map itself");
            assertTrue(mergedItems.contains("x"));
            assertTrue(mergedItems.contains("y"));
            assertTrue(mergedItems.contains("z"));
            // Verify the map itself is NOT in the merged items
            assertFalse(mergedItems.stream().anyMatch(item -> item instanceof Map),
                "The entire map should not be added when items were already extracted");
        }

        @Test
        @DisplayName("Should extract items from map with `records` key (Airtable shape) - coverage added 2026-05-14 alongside SplitNode unification")
        void shouldExtractFromRecordsKey() {
            // Pre-refactor Queue1To1Strategy only recognized `items`; Airtable's {records:[…]} was
            // silently dropped (whole Map added as 1 element). After unifying with
            // OutputUnwrapper.tryUnwrapToList, records/results/data/etc. all unwrap.
            List<String> sourceNodeIds = List.of("mcp:airtable");
            when(context.isSuccess("mcp:airtable")).thenReturn(true);
            when(context.getStepOutput("mcp:airtable")).thenReturn(Optional.of(
                Map.of("records", List.of("r1", "r2", "r3"))));

            Map<String, Object> result = strategy.merge(sourceNodeIds, context);

            @SuppressWarnings("unchecked")
            List<Object> mergedItems = (List<Object>) result.get("merged_items");
            assertEquals(3, mergedItems.size(), "records array must be unwrapped, not added as a single Map");
            assertTrue(mergedItems.contains("r1"));
        }

        @Test
        @DisplayName("Should extract items from map with `results` key (generic search shape)")
        void shouldExtractFromResultsKey() {
            List<String> sourceNodeIds = List.of("mcp:search");
            when(context.isSuccess("mcp:search")).thenReturn(true);
            when(context.getStepOutput("mcp:search")).thenReturn(Optional.of(
                Map.of("results", List.of("hit1", "hit2"))));

            Map<String, Object> result = strategy.merge(sourceNodeIds, context);

            @SuppressWarnings("unchecked")
            List<Object> mergedItems = (List<Object>) result.get("merged_items");
            assertEquals(2, mergedItems.size());
        }

        @Test
        @DisplayName("Should extract items from map with `data` array (OpenAI-style envelope)")
        void shouldExtractFromDataArray() {
            List<String> sourceNodeIds = List.of("mcp:openai");
            when(context.isSuccess("mcp:openai")).thenReturn(true);
            when(context.getStepOutput("mcp:openai")).thenReturn(Optional.of(
                Map.of("data", List.of(Map.of("id", "obj1"), Map.of("id", "obj2")))));

            Map<String, Object> result = strategy.merge(sourceNodeIds, context);

            @SuppressWarnings("unchecked")
            List<Object> mergedItems = (List<Object>) result.get("merged_items");
            assertEquals(2, mergedItems.size());
        }

        @Test
        @DisplayName("Should prefer `items` over `data` when both present (priority pinning)")
        void shouldPreferItemsOverDataInPriorityOrder() {
            // items is more specific than data (data is often a single-row envelope). The
            // ordered ARRAY_BEARING_KEYS list pins this so merge stays consistent with Split.
            List<String> sourceNodeIds = List.of("mcp:ambiguous");
            when(context.isSuccess("mcp:ambiguous")).thenReturn(true);
            java.util.LinkedHashMap<String, Object> output = new java.util.LinkedHashMap<>();
            output.put("data", List.of("from-data"));
            output.put("items", List.of("from-items-A", "from-items-B"));
            when(context.getStepOutput("mcp:ambiguous")).thenReturn(Optional.of(output));

            Map<String, Object> result = strategy.merge(sourceNodeIds, context);

            @SuppressWarnings("unchecked")
            List<Object> mergedItems = (List<Object>) result.get("merged_items");
            assertEquals(2, mergedItems.size(), "items wins over data per ARRAY_BEARING_KEYS order");
            assertTrue(mergedItems.contains("from-items-A"));
            assertFalse(mergedItems.contains("from-data"));
        }

        @Test
        @DisplayName("Should add map to items when it does NOT contain 'items' key")
        void shouldAddMapWhenNoItemsKey() {
            // Given: a source output that is a Map without an "items" key
            List<String> sourceNodeIds = List.of("mcp:step1");
            when(context.isSuccess("mcp:step1")).thenReturn(true);
            Map<String, Object> outputWithoutItems = Map.of("data", "value1", "count", 42);
            when(context.getStepOutput("mcp:step1")).thenReturn(Optional.of(outputWithoutItems));

            // When
            Map<String, Object> result = strategy.merge(sourceNodeIds, context);

            // Then: the map itself should be added as an item
            @SuppressWarnings("unchecked")
            List<Object> mergedItems = (List<Object>) result.get("merged_items");
            assertEquals(1, mergedItems.size());
            assertTrue(mergedItems.get(0) instanceof Map);
        }

        @Test
        @DisplayName("Should handle empty output")
        void shouldHandleEmptyOutput() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:step1");
            when(context.isSuccess("mcp:step1")).thenReturn(true);
            when(context.getStepOutput("mcp:step1")).thenReturn(Optional.empty());

            // When
            Map<String, Object> result = strategy.merge(sourceNodeIds, context);

            // Then
            assertEquals(1, result.get("success_count"));
            @SuppressWarnings("unchecked")
            List<Object> mergedItems = (List<Object>) result.get("merged_items");
            assertEquals(0, mergedItems.size());
        }

        @Test
        @DisplayName("Should handle scalar output values")
        void shouldHandleScalarOutputValues() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:step1");
            when(context.isSuccess("mcp:step1")).thenReturn(true);
            when(context.getStepOutput("mcp:step1")).thenReturn(Optional.of("simple_value"));

            // When
            Map<String, Object> result = strategy.merge(sourceNodeIds, context);

            // Then
            @SuppressWarnings("unchecked")
            List<Object> mergedItems = (List<Object>) result.get("merged_items");
            assertEquals(1, mergedItems.size());
            assertEquals("simple_value", mergedItems.get(0));
        }

        @Test
        @DisplayName("Should handle empty source list")
        void shouldHandleEmptySourceList() {
            // Given
            List<String> sourceNodeIds = List.of();

            // When
            Map<String, Object> result = strategy.merge(sourceNodeIds, context);

            // Then
            assertEquals("QUEUE_1_TO_1", result.get("strategy"));
            assertEquals(0, result.get("source_count"));
            assertEquals(0, result.get("success_count"));
            assertEquals(0, result.get("item_count"));
        }

        @Test
        @DisplayName("Should preserve source order in output")
        void shouldPreserveSourceOrderInOutput() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:first", "mcp:second", "mcp:third");
            when(context.isSuccess("mcp:first")).thenReturn(true);
            when(context.isSuccess("mcp:second")).thenReturn(true);
            when(context.isSuccess("mcp:third")).thenReturn(true);
            when(context.getStepOutput("mcp:first")).thenReturn(Optional.of("a"));
            when(context.getStepOutput("mcp:second")).thenReturn(Optional.of("b"));
            when(context.getStepOutput("mcp:third")).thenReturn(Optional.of("c"));

            // When
            Map<String, Object> result = strategy.merge(sourceNodeIds, context);

            // Then
            @SuppressWarnings("unchecked")
            Map<String, Object> sources = (Map<String, Object>) result.get("sources");
            List<String> keys = sources.keySet().stream().toList();
            assertEquals("mcp:first", keys.get(0));
            assertEquals("mcp:second", keys.get(1));
            assertEquals("mcp:third", keys.get(2));
        }
    }

    @Nested
    @DisplayName("getSkipReason()")
    class GetSkipReasonTests {

        @Test
        @DisplayName("Should return descriptive skip reason")
        void shouldReturnDescriptiveSkipReason() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:step1");

            // When
            String reason = strategy.getSkipReason(sourceNodeIds, context);

            // Then
            assertNotNull(reason);
            assertTrue(reason.contains("QUEUE_1_TO_1"));
            assertTrue(reason.toLowerCase().contains("failed") || reason.toLowerCase().contains("skipped"));
        }
    }
}
