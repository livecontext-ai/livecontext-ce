package com.apimarketplace.orchestrator.services.merge;

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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ItemAwareMergeStrategy.
 *
 * This strategy uses the ItemMergeCollector to track and merge
 * results from multiple sources, including ForEach child items.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ItemAwareMergeStrategy")
class ItemAwareMergeStrategyTest {

    @Mock
    private ItemMergeCollector collector;

    @Mock
    private ExecutionContext context;

    private ItemAwareMergeStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new ItemAwareMergeStrategy(collector);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // name() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("name()")
    class NameTests {

        @Test
        @DisplayName("Should return ITEM_AWARE")
        void shouldReturnItemAware() {
            assertEquals("ITEM_AWARE", strategy.name());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // canMerge() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("canMerge()")
    class CanMergeTests {

        @Test
        @DisplayName("Should return false when merge point ID cannot be determined")
        void shouldReturnFalseWhenMergePointIdCannotBeDetermined() {
            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");
            when(context.getGlobalData("current_merge_point_id")).thenReturn(Optional.empty());

            List<String> sources = List.of("mcp:step1", "mcp:step2");

            boolean canMerge = strategy.canMerge(sources, context);

            assertFalse(canMerge);
        }

        @Test
        @DisplayName("Should return false when merge state not initialized")
        void shouldReturnFalseWhenMergeStateNotInitialized() {
            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");
            when(context.getGlobalData("current_merge_point_id")).thenReturn(Optional.of("merge-1"));
            when(collector.getMergeState("run-1", "merge-1", "0")).thenReturn(null);

            List<String> sources = List.of("mcp:step");

            boolean canMerge = strategy.canMerge(sources, context);

            assertFalse(canMerge);
        }

        @Test
        @DisplayName("Should return false when merge not complete")
        void shouldReturnFalseWhenMergeNotComplete() {
            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");
            when(context.getGlobalData("current_merge_point_id")).thenReturn(Optional.of("merge-1"));

            ItemMergeState state = ItemMergeState.create("merge-1", "0", Set.of("mcp:step1", "mcp:step2"));
            when(collector.getMergeState("run-1", "merge-1", "0")).thenReturn(state);

            List<String> sources = List.of("mcp:step1", "mcp:step2");

            boolean canMerge = strategy.canMerge(sources, context);

            assertFalse(canMerge);
        }

        @Test
        @DisplayName("Should return true when merge complete")
        void shouldReturnTrueWhenMergeComplete() {
            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");
            when(context.getGlobalData("current_merge_point_id")).thenReturn(Optional.of("merge-1"));

            ItemMergeState state = ItemMergeState.create("merge-1", "0", Set.of("mcp:step"))
                    .withEntry(ItemMergeEntry.success("0", 0, "mcp:step", Map.of()));
            when(collector.getMergeState("run-1", "merge-1", "0")).thenReturn(state);

            List<String> sources = List.of("mcp:step");

            boolean canMerge = strategy.canMerge(sources, context);

            assertTrue(canMerge);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // merge() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("merge()")
    class MergeTests {

        @Test
        @DisplayName("Should return error when merge point ID not found")
        void shouldReturnErrorWhenMergePointIdNotFound() {
            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");
            when(context.getGlobalData("current_merge_point_id")).thenReturn(Optional.empty());

            Map<String, Object> result = strategy.merge(List.of("mcp:step"), context);

            assertTrue(result.containsKey("error"));
        }

        @Test
        @DisplayName("Should return error when merge state not found")
        void shouldReturnErrorWhenMergeStateNotFound() {
            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");
            when(context.getGlobalData("current_merge_point_id")).thenReturn(Optional.of("merge-1"));
            when(collector.getMergeState("run-1", "merge-1", "0")).thenReturn(null);

            Map<String, Object> result = strategy.merge(List.of("mcp:step"), context);

            assertTrue(result.containsKey("error"));
        }

        @Test
        @DisplayName("Should merge results correctly")
        void shouldMergeResultsCorrectly() {
            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");
            when(context.getGlobalData("current_merge_point_id")).thenReturn(Optional.of("merge-1"));

            ItemMergeState state = ItemMergeState.create("merge-1", "0", Set.of("mcp:step"))
                    .withEntry(ItemMergeEntry.success("0", 0, "mcp:step", Map.of("data", "value")));
            when(collector.getMergeState("run-1", "merge-1", "0")).thenReturn(state);

            Map<String, Object> result = strategy.merge(List.of("mcp:step"), context);

            assertNotNull(result.get("merged_results"));
            assertEquals(1, result.get("item_count"));
        }

        @Test
        @DisplayName("Should separate normal and ForEach results")
        void shouldSeparateNormalAndForEachResults() {
            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");
            when(context.getGlobalData("current_merge_point_id")).thenReturn(Optional.of("merge-1"));

            ItemMergeState state = ItemMergeState.create("merge-1", "0", Set.of("mcp:normal", "core:split"))
                    .withExpectedCount("core:split", 2)
                    .withEntry(ItemMergeEntry.success("0", 0, "mcp:normal", Map.of("type", "normal")))
                    .withEntry(ItemMergeEntry.success("0.1", 1, "core:split", Map.of("type", "split")))
                    .withEntry(ItemMergeEntry.success("0.2", 2, "core:split", Map.of("type", "split")));

            when(collector.getMergeState("run-1", "merge-1", "0")).thenReturn(state);

            Map<String, Object> result = strategy.merge(List.of("mcp:normal", "core:split"), context);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> normalResults = (List<Map<String, Object>>) result.get("normal_results");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> splitResults = (List<Map<String, Object>>) result.get("split_results");

            assertEquals(1, normalResults.size());
            assertEquals(2, splitResults.size());
            assertEquals(1, result.get("normal_count"));
            assertEquals(2, result.get("split_count"));
        }

        @Test
        @DisplayName("Should include failure counts in result")
        void shouldIncludeFailureCountsInResult() {
            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");
            when(context.getGlobalData("current_merge_point_id")).thenReturn(Optional.of("merge-1"));

            ItemMergeState state = ItemMergeState.create("merge-1", "0", Set.of("core:foreach"))
                    .withExpectedCount("core:foreach", 2)
                    .withEntry(ItemMergeEntry.success("0.1", 1, "core:foreach", Map.of()))
                    .withEntry(ItemMergeEntry.failed("0.2", 2, "core:foreach", "Error"));

            when(collector.getMergeState("run-1", "merge-1", "0")).thenReturn(state);

            Map<String, Object> result = strategy.merge(List.of("core:foreach"), context);

            assertEquals(1, result.get("success_count"));
            assertEquals(1, result.get("failed_count"));
            assertEquals(true, result.get("has_failures"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // shouldSkip() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("shouldSkip()")
    class ShouldSkipTests {

        @Test
        @DisplayName("Should return false when merge point ID not found")
        void shouldReturnFalseWhenMergePointIdNotFound() {
            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");
            when(context.getGlobalData("current_merge_point_id")).thenReturn(Optional.empty());

            boolean shouldSkip = strategy.shouldSkip(List.of("mcp:step"), context);

            assertFalse(shouldSkip);
        }

        @Test
        @DisplayName("Should return false when merge state not found")
        void shouldReturnFalseWhenMergeStateNotFound() {
            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");
            when(context.getGlobalData("current_merge_point_id")).thenReturn(Optional.of("merge-1"));
            when(collector.getMergeState("run-1", "merge-1", "0")).thenReturn(null);

            boolean shouldSkip = strategy.shouldSkip(List.of("mcp:step"), context);

            assertFalse(shouldSkip);
        }

        @Test
        @DisplayName("Should return true when all entries are skipped")
        void shouldReturnTrueWhenAllEntriesAreSkipped() {
            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");
            when(context.getGlobalData("current_merge_point_id")).thenReturn(Optional.of("merge-1"));

            ItemMergeState state = ItemMergeState.create("merge-1", "0", Set.of("mcp:step"))
                    .withEntry(ItemMergeEntry.skipped("0", 0, "mcp:step", "Branch not taken"));

            when(collector.getMergeState("run-1", "merge-1", "0")).thenReturn(state);

            boolean shouldSkip = strategy.shouldSkip(List.of("mcp:step"), context);

            assertTrue(shouldSkip);
        }

        @Test
        @DisplayName("Should return false when has success entries")
        void shouldReturnFalseWhenHasSuccessEntries() {
            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");
            when(context.getGlobalData("current_merge_point_id")).thenReturn(Optional.of("merge-1"));

            ItemMergeState state = ItemMergeState.create("merge-1", "0", Set.of("mcp:step"))
                    .withEntry(ItemMergeEntry.success("0", 0, "mcp:step", Map.of()));

            when(collector.getMergeState("run-1", "merge-1", "0")).thenReturn(state);

            boolean shouldSkip = strategy.shouldSkip(List.of("mcp:step"), context);

            assertFalse(shouldSkip);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getSkipReason() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getSkipReason()")
    class GetSkipReasonTests {

        @Test
        @DisplayName("Should return skip reason")
        void shouldReturnSkipReason() {
            String reason = strategy.getSkipReason(List.of("mcp:step"), context);

            assertEquals("All source branches were skipped", reason);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // create() factory tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("create()")
    class CreateTests {

        @Test
        @DisplayName("Should create strategy with collector")
        void shouldCreateStrategyWithCollector() {
            ItemMergeCollector collector = new ItemMergeCollector();

            ItemAwareMergeStrategy strategy = ItemAwareMergeStrategy.create(collector);

            assertNotNull(strategy);
            assertEquals("ITEM_AWARE", strategy.name());
        }
    }
}
