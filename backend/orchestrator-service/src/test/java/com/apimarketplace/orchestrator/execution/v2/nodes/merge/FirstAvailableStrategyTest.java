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
@DisplayName("FirstAvailableStrategy Tests")
class FirstAvailableStrategyTest {

    private FirstAvailableStrategy strategy;

    @Mock
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        strategy = new FirstAvailableStrategy();
    }

    @Nested
    @DisplayName("name()")
    class NameTests {

        @Test
        @DisplayName("Should return FIRST_AVAILABLE")
        void shouldReturnCorrectName() {
            assertEquals("FIRST_AVAILABLE", strategy.name());
        }
    }

    @Nested
    @DisplayName("canMerge()")
    class CanMergeTests {

        @Test
        @DisplayName("Should return true when at least one source succeeded")
        void shouldReturnTrueWhenOneSourceSucceeded() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:step1", "mcp:step2");
            when(context.isSuccess("mcp:step1")).thenReturn(true);

            // When
            boolean result = strategy.canMerge(sourceNodeIds, context);

            // Then
            assertTrue(result);
        }

        @Test
        @DisplayName("Should return true when all sources completed even if none succeeded")
        void shouldReturnTrueWhenAllCompletedEvenIfNoneSucceeded() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:step1", "mcp:step2");
            when(context.isSuccess("mcp:step1")).thenReturn(false);
            when(context.isSuccess("mcp:step2")).thenReturn(false);
            when(context.isCompleted("mcp:step1")).thenReturn(true);
            when(context.isCompleted("mcp:step2")).thenReturn(true);

            // When
            boolean result = strategy.canMerge(sourceNodeIds, context);

            // Then
            assertTrue(result);
        }

        @Test
        @DisplayName("Should return false when no success and not all completed")
        void shouldReturnFalseWhenNoSuccessAndNotAllCompleted() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:step1", "mcp:step2");
            when(context.isSuccess("mcp:step1")).thenReturn(false);
            when(context.isSuccess("mcp:step2")).thenReturn(false);
            when(context.isCompleted("mcp:step1")).thenReturn(true);
            when(context.isCompleted("mcp:step2")).thenReturn(false);

            // When
            boolean result = strategy.canMerge(sourceNodeIds, context);

            // Then
            assertFalse(result);
        }

        @Test
        @DisplayName("Should return true immediately when first source succeeded")
        void shouldReturnTrueImmediatelyWhenFirstSourceSucceeded() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:step1", "mcp:step2", "mcp:step3");
            when(context.isSuccess("mcp:step1")).thenReturn(true);

            // When
            boolean result = strategy.canMerge(sourceNodeIds, context);

            // Then
            assertTrue(result);
            // Should not check other sources when first succeeds
        }

        @Test
        @DisplayName("Should return true for empty source list with all completed check")
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
            when(context.isCompleted("mcp:step1")).thenReturn(true);
            when(context.isCompleted("mcp:step2")).thenReturn(true);
            when(context.isSuccess("mcp:step1")).thenReturn(false);
            when(context.isSuccess("mcp:step2")).thenReturn(true);

            // When
            boolean result = strategy.shouldSkip(sourceNodeIds, context);

            // Then
            assertFalse(result);
        }

        @Test
        @DisplayName("Should return true when all completed and none succeeded")
        void shouldReturnTrueWhenAllCompletedAndNoneSucceeded() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:step1", "mcp:step2");
            when(context.isCompleted("mcp:step1")).thenReturn(true);
            when(context.isCompleted("mcp:step2")).thenReturn(true);
            when(context.isSuccess("mcp:step1")).thenReturn(false);
            when(context.isSuccess("mcp:step2")).thenReturn(false);

            // When
            boolean result = strategy.shouldSkip(sourceNodeIds, context);

            // Then
            assertTrue(result);
        }

        @Test
        @DisplayName("Should return false when not all completed yet")
        void shouldReturnFalseWhenNotAllCompleted() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:step1", "mcp:step2");
            when(context.isCompleted("mcp:step1")).thenReturn(true);
            when(context.isCompleted("mcp:step2")).thenReturn(false);

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
        @DisplayName("Should use first successful source")
        void shouldUseFirstSuccessfulSource() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:step1", "mcp:step2", "mcp:step3");
            when(context.isSuccess("mcp:step1")).thenReturn(false);
            when(context.isSuccess("mcp:step2")).thenReturn(true);
            when(context.getStepOutput("mcp:step2")).thenReturn(Optional.of(Map.of("data", "value2")));

            // When
            Map<String, Object> result = strategy.merge(sourceNodeIds, context);

            // Then
            assertEquals("FIRST_AVAILABLE", result.get("strategy"));
            assertEquals(3, result.get("source_count"));
            assertEquals("mcp:step2", result.get("selected_source"));
            assertTrue((Boolean) result.get("has_data"));
        }

        @Test
        @DisplayName("Should stop searching after first success")
        void shouldStopSearchingAfterFirstSuccess() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:step1", "mcp:step2", "mcp:step3");
            when(context.isSuccess("mcp:step1")).thenReturn(true);
            when(context.getStepOutput("mcp:step1")).thenReturn(Optional.of("data1"));

            // When
            Map<String, Object> result = strategy.merge(sourceNodeIds, context);

            // Then
            assertEquals("mcp:step1", result.get("selected_source"));
            // Should not check step2 or step3
            verify(context, times(1)).isSuccess(anyString());
        }

        @Test
        @DisplayName("Should handle no successful sources")
        void shouldHandleNoSuccessfulSources() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:step1", "mcp:step2");
            when(context.isSuccess("mcp:step1")).thenReturn(false);
            when(context.isSuccess("mcp:step2")).thenReturn(false);

            // When
            Map<String, Object> result = strategy.merge(sourceNodeIds, context);

            // Then
            assertNull(result.get("selected_source"));
            assertFalse((Boolean) result.get("has_data"));
            assertEquals(0, result.get("item_count"));
        }

        @Test
        @DisplayName("Should handle list data output")
        void shouldHandleListDataOutput() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:step1");
            when(context.isSuccess("mcp:step1")).thenReturn(true);
            when(context.getStepOutput("mcp:step1")).thenReturn(Optional.of(List.of("a", "b", "c")));

            // When
            Map<String, Object> result = strategy.merge(sourceNodeIds, context);

            // Then
            @SuppressWarnings("unchecked")
            List<Object> mergedItems = (List<Object>) result.get("merged_items");
            assertEquals(3, mergedItems.size());
            assertEquals(3, result.get("item_count"));
        }

        @Test
        @DisplayName("Should handle map data output")
        void shouldHandleMapDataOutput() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:step1");
            when(context.isSuccess("mcp:step1")).thenReturn(true);
            Map<String, Object> mapData = Map.of("key", "value");
            when(context.getStepOutput("mcp:step1")).thenReturn(Optional.of(mapData));

            // When
            Map<String, Object> result = strategy.merge(sourceNodeIds, context);

            // Then
            assertEquals(mapData, result.get("data"));
            @SuppressWarnings("unchecked")
            List<Object> mergedItems = (List<Object>) result.get("merged_items");
            assertEquals(1, mergedItems.size());
            assertEquals(1, result.get("item_count"));
        }

        @Test
        @DisplayName("Should handle empty output from successful source")
        void shouldHandleEmptyOutputFromSuccessfulSource() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:step1");
            when(context.isSuccess("mcp:step1")).thenReturn(true);
            when(context.getStepOutput("mcp:step1")).thenReturn(Optional.empty());

            // When
            Map<String, Object> result = strategy.merge(sourceNodeIds, context);

            // Then
            assertNull(result.get("selected_source"));
            assertFalse((Boolean) result.get("has_data"));
        }

        @Test
        @DisplayName("Should handle empty source list")
        void shouldHandleEmptySourceList() {
            // Given
            List<String> sourceNodeIds = List.of();

            // When
            Map<String, Object> result = strategy.merge(sourceNodeIds, context);

            // Then
            assertEquals("FIRST_AVAILABLE", result.get("strategy"));
            assertEquals(0, result.get("source_count"));
            assertNull(result.get("selected_source"));
            assertFalse((Boolean) result.get("has_data"));
        }

        @Test
        @DisplayName("Should preserve data type in output")
        void shouldPreserveDataTypeInOutput() {
            // Given
            List<String> sourceNodeIds = List.of("mcp:step1");
            when(context.isSuccess("mcp:step1")).thenReturn(true);
            when(context.getStepOutput("mcp:step1")).thenReturn(Optional.of(42));

            // When
            Map<String, Object> result = strategy.merge(sourceNodeIds, context);

            // Then
            assertEquals(42, result.get("data"));
            assertTrue((Boolean) result.get("has_data"));
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
            assertTrue(reason.toLowerCase().contains("no source") || reason.toLowerCase().contains("success"));
        }
    }
}
