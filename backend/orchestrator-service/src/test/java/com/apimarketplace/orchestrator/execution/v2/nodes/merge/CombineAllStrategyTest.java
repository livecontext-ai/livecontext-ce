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
@DisplayName("CombineAllStrategy Tests")
class CombineAllStrategyTest {

    private CombineAllStrategy strategy;

    @Mock
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        strategy = new CombineAllStrategy();
    }

    @Nested
    @DisplayName("name()")
    class NameTests {

        @Test
        @DisplayName("Should return COMBINE_ALL")
        void shouldReturnCorrectName() {
            assertEquals("COMBINE_ALL", strategy.name());
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
        @DisplayName("Should return true when all sources completed even if failed")
        void shouldReturnTrueWhenAllCompletedEvenIfFailed() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:step1", "mcp:step2");
            when(context.isCompleted("mcp:step1")).thenReturn(true);
            when(context.isCompleted("mcp:step2")).thenReturn(true);
            // Note: isCompleted returns true for failed/skipped too

            // When
            boolean result = strategy.canMerge(sourceNodeIds, context);

            // Then
            assertTrue(result);
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
        @DisplayName("Should combine data from all successful sources")
        void shouldCombineDataFromAllSuccessfulSources() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:step1", "mcp:step2");
            when(context.isSuccess("mcp:step1")).thenReturn(true);
            when(context.isSuccess("mcp:step2")).thenReturn(true);
            when(context.getStepOutput("mcp:step1")).thenReturn(Optional.of(List.of("a", "b")));
            when(context.getStepOutput("mcp:step2")).thenReturn(Optional.of(List.of("c", "d")));

            // When
            Map<String, Object> result = strategy.merge(sourceNodeIds, context);

            // Then
            assertEquals("COMBINE_ALL", result.get("strategy"));
            assertEquals(2, result.get("source_count"));
            assertEquals(2, result.get("success_count"));
            assertEquals(0, result.get("failed_count"));

            @SuppressWarnings("unchecked")
            List<Object> mergedItems = (List<Object>) result.get("merged_items");
            assertEquals(4, mergedItems.size());
            assertTrue(mergedItems.containsAll(List.of("a", "b", "c", "d")));
        }

        @Test
        @DisplayName("Should mark failed sources in output")
        void shouldMarkFailedSourcesInOutput() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:step1", "mcp:step2");
            when(context.isSuccess("mcp:step1")).thenReturn(true);
            when(context.isSuccess("mcp:step2")).thenReturn(false);
            when(context.getStepOutput("mcp:step1")).thenReturn(Optional.of("data1"));

            // When
            Map<String, Object> result = strategy.merge(sourceNodeIds, context);

            // Then
            assertEquals(1, result.get("success_count"));
            assertEquals(1, result.get("failed_count"));

            @SuppressWarnings("unchecked")
            Map<String, Object> sources = (Map<String, Object>) result.get("sources");
            assertEquals(2, sources.size());
            assertTrue(sources.containsKey("mcp:step2"));
            // Failed source should have status marker
            @SuppressWarnings("unchecked")
            Map<String, Object> failedSource = (Map<String, Object>) sources.get("mcp:step2");
            assertEquals("skipped_or_failed", failedSource.get("status"));
        }

        @Test
        @DisplayName("Should flatten nested 'items' key from maps")
        void shouldFlattenNestedItemsKey() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:step1");
            when(context.isSuccess("mcp:step1")).thenReturn(true);
            Map<String, Object> output = Map.of("items", List.of("x", "y"));
            when(context.getStepOutput("mcp:step1")).thenReturn(Optional.of(output));

            // When
            Map<String, Object> result = strategy.merge(sourceNodeIds, context);

            // Then
            @SuppressWarnings("unchecked")
            List<Object> mergedItems = (List<Object>) result.get("merged_items");
            assertEquals(2, mergedItems.size());
            assertTrue(mergedItems.contains("x"));
            assertTrue(mergedItems.contains("y"));
        }

        @Test
        @DisplayName("Should flatten nested 'data' key from maps")
        void shouldFlattenNestedDataKey() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:step1");
            when(context.isSuccess("mcp:step1")).thenReturn(true);
            Map<String, Object> output = Map.of("data", List.of("a", "b"));
            when(context.getStepOutput("mcp:step1")).thenReturn(Optional.of(output));

            // When
            Map<String, Object> result = strategy.merge(sourceNodeIds, context);

            // Then
            @SuppressWarnings("unchecked")
            List<Object> mergedItems = (List<Object>) result.get("merged_items");
            assertEquals(2, mergedItems.size());
            assertTrue(mergedItems.contains("a"));
            assertTrue(mergedItems.contains("b"));
        }

        @Test
        @DisplayName("Should flatten nested 'results' key from maps")
        void shouldFlattenNestedResultsKey() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:step1");
            when(context.isSuccess("mcp:step1")).thenReturn(true);
            Map<String, Object> output = Map.of("results", List.of(1, 2, 3));
            when(context.getStepOutput("mcp:step1")).thenReturn(Optional.of(output));

            // When
            Map<String, Object> result = strategy.merge(sourceNodeIds, context);

            // Then
            @SuppressWarnings("unchecked")
            List<Object> mergedItems = (List<Object>) result.get("merged_items");
            assertEquals(3, mergedItems.size());
        }

        @Test
        @DisplayName("Should flatten nested 'records' key from maps")
        void shouldFlattenNestedRecordsKey() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:step1");
            when(context.isSuccess("mcp:step1")).thenReturn(true);
            Map<String, Object> output = Map.of("records", List.of("r1", "r2"));
            when(context.getStepOutput("mcp:step1")).thenReturn(Optional.of(output));

            // When
            Map<String, Object> result = strategy.merge(sourceNodeIds, context);

            // Then
            @SuppressWarnings("unchecked")
            List<Object> mergedItems = (List<Object>) result.get("merged_items");
            assertEquals(2, mergedItems.size());
        }

        @Test
        @DisplayName("Should add map as single item when no nested list key found")
        void shouldAddMapAsItemWhenNoNestedListKey() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:step1");
            when(context.isSuccess("mcp:step1")).thenReturn(true);
            Map<String, Object> output = Map.of("name", "John", "age", 30);
            when(context.getStepOutput("mcp:step1")).thenReturn(Optional.of(output));

            // When
            Map<String, Object> result = strategy.merge(sourceNodeIds, context);

            // Then
            @SuppressWarnings("unchecked")
            List<Object> mergedItems = (List<Object>) result.get("merged_items");
            assertEquals(1, mergedItems.size());
            assertEquals(output, mergedItems.get(0));
        }

        @Test
        @DisplayName("Should handle null data gracefully")
        void shouldHandleNullDataGracefully() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:step1");
            when(context.isSuccess("mcp:step1")).thenReturn(true);
            when(context.getStepOutput("mcp:step1")).thenReturn(Optional.empty());

            // When
            Map<String, Object> result = strategy.merge(sourceNodeIds, context);

            // Then
            @SuppressWarnings("unchecked")
            List<Object> mergedItems = (List<Object>) result.get("merged_items");
            assertEquals(0, mergedItems.size());
        }

        @Test
        @DisplayName("Should handle scalar values")
        void shouldHandleScalarValues() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:step1");
            when(context.isSuccess("mcp:step1")).thenReturn(true);
            when(context.getStepOutput("mcp:step1")).thenReturn(Optional.of("simple_string"));

            // When
            Map<String, Object> result = strategy.merge(sourceNodeIds, context);

            // Then
            @SuppressWarnings("unchecked")
            List<Object> mergedItems = (List<Object>) result.get("merged_items");
            assertEquals(1, mergedItems.size());
            assertEquals("simple_string", mergedItems.get(0));
        }

        @Test
        @DisplayName("Should handle empty source list")
        void shouldHandleEmptySourceList() {
            // Given
            List<String> sourceNodeIds = List.of();

            // When
            Map<String, Object> result = strategy.merge(sourceNodeIds, context);

            // Then
            assertEquals("COMBINE_ALL", result.get("strategy"));
            assertEquals(0, result.get("source_count"));
            assertEquals(0, result.get("item_count"));
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
            assertTrue(reason.toLowerCase().contains("failed") || reason.toLowerCase().contains("skipped"));
        }
    }
}
