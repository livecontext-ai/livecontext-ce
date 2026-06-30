package com.apimarketplace.orchestrator.execution.v2.state;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MergeState.
 * Tests merge node state for collecting items from multiple sources.
 */
class MergeStateTest {

    private MergeState state;

    @BeforeEach
    void setUp() {
        state = new MergeState();
    }

    // ========================================================================
    // Constructor Tests
    // ========================================================================

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create empty state")
        void shouldCreateEmptyState() {
            MergeState newState = new MergeState();

            assertTrue(newState.getAllItems().isEmpty());
            assertEquals(0, newState.getTotalItemCount());
        }
    }

    // ========================================================================
    // addItem() Tests
    // ========================================================================

    @Nested
    @DisplayName("addItem()")
    class AddItemTests {

        @Test
        @DisplayName("Should add item to source")
        void shouldAddItemToSource() {
            MergeState newState = state.addItem("source1", "item1");

            List<Object> items = newState.getItems("source1");

            assertEquals(1, items.size());
            assertEquals("item1", items.get(0));
        }

        @Test
        @DisplayName("Should return new state (immutable)")
        void shouldReturnNewState() {
            MergeState newState = state.addItem("source1", "item1");

            // Original unchanged
            assertTrue(state.getItems("source1").isEmpty());

            // New state has item
            assertEquals(1, newState.getItems("source1").size());
        }

        @Test
        @DisplayName("Should add multiple items to same source")
        void shouldAddMultipleItemsToSameSource() {
            MergeState newState = state
                    .addItem("source1", "item1")
                    .addItem("source1", "item2")
                    .addItem("source1", "item3");

            List<Object> items = newState.getItems("source1");

            assertEquals(3, items.size());
            assertEquals("item1", items.get(0));
            assertEquals("item2", items.get(1));
            assertEquals("item3", items.get(2));
        }

        @Test
        @DisplayName("Should add items to different sources")
        void shouldAddItemsToDifferentSources() {
            MergeState newState = state
                    .addItem("source1", "item1")
                    .addItem("source2", "item2")
                    .addItem("source3", "item3");

            assertEquals(1, newState.getItems("source1").size());
            assertEquals(1, newState.getItems("source2").size());
            assertEquals(1, newState.getItems("source3").size());
        }

        @Test
        @DisplayName("Should handle various object types")
        void shouldHandleVariousObjectTypes() {
            MergeState newState = state
                    .addItem("source1", "string")
                    .addItem("source1", 42)
                    .addItem("source1", List.of(1, 2, 3))
                    .addItem("source1", Map.of("key", "value"));

            List<Object> items = newState.getItems("source1");

            assertEquals(4, items.size());
            assertEquals("string", items.get(0));
            assertEquals(42, items.get(1));
            assertEquals(List.of(1, 2, 3), items.get(2));
            assertEquals(Map.of("key", "value"), items.get(3));
        }

        @Test
        @DisplayName("Should handle null items")
        void shouldHandleNullItems() {
            MergeState newState = state.addItem("source1", null);

            List<Object> items = newState.getItems("source1");

            assertEquals(1, items.size());
            assertNull(items.get(0));
        }
    }

    // ========================================================================
    // getItems() Tests
    // ========================================================================

    @Nested
    @DisplayName("getItems()")
    class GetItemsTests {

        @Test
        @DisplayName("Should return empty list for unknown source")
        void shouldReturnEmptyListForUnknownSource() {
            List<Object> items = state.getItems("unknown");

            assertNotNull(items);
            assertTrue(items.isEmpty());
        }

        @Test
        @DisplayName("Should return items for known source")
        void shouldReturnItemsForKnownSource() {
            MergeState newState = state
                    .addItem("source1", "item1")
                    .addItem("source1", "item2");

            List<Object> items = newState.getItems("source1");

            assertEquals(2, items.size());
        }
    }

    // ========================================================================
    // getAllItems() Tests
    // ========================================================================

    @Nested
    @DisplayName("getAllItems()")
    class GetAllItemsTests {

        @Test
        @DisplayName("Should return empty map for empty state")
        void shouldReturnEmptyMapForEmptyState() {
            Map<String, List<Object>> allItems = state.getAllItems();

            assertTrue(allItems.isEmpty());
        }

        @Test
        @DisplayName("Should return all items grouped by source")
        void shouldReturnAllItemsGroupedBySource() {
            MergeState newState = state
                    .addItem("source1", "item1a")
                    .addItem("source1", "item1b")
                    .addItem("source2", "item2a");

            Map<String, List<Object>> allItems = newState.getAllItems();

            assertEquals(2, allItems.size());
            assertEquals(2, allItems.get("source1").size());
            assertEquals(1, allItems.get("source2").size());
        }

        @Test
        @DisplayName("Should return defensive copy")
        void shouldReturnDefensiveCopy() {
            MergeState newState = state.addItem("source1", "item1");

            Map<String, List<Object>> allItems = newState.getAllItems();
            allItems.put("hacked", List.of("malicious"));

            // Original state should not be affected
            assertFalse(newState.getAllItems().containsKey("hacked"));
        }
    }

    // ========================================================================
    // hasItemsFromAllSources() Tests
    // ========================================================================

    @Nested
    @DisplayName("hasItemsFromAllSources()")
    class HasItemsFromAllSourcesTests {

        @Test
        @DisplayName("Should return true when all sources have items")
        void shouldReturnTrueWhenAllSourcesHaveItems() {
            MergeState newState = state
                    .addItem("source1", "item1")
                    .addItem("source2", "item2")
                    .addItem("source3", "item3");

            boolean result = newState.hasItemsFromAllSources(List.of("source1", "source2", "source3"));

            assertTrue(result);
        }

        @Test
        @DisplayName("Should return false when some sources missing")
        void shouldReturnFalseWhenSomeSourcesMissing() {
            MergeState newState = state
                    .addItem("source1", "item1")
                    .addItem("source2", "item2");

            boolean result = newState.hasItemsFromAllSources(List.of("source1", "source2", "source3"));

            assertFalse(result); // source3 missing
        }

        @Test
        @DisplayName("Should return true for empty expected sources")
        void shouldReturnTrueForEmptyExpectedSources() {
            boolean result = state.hasItemsFromAllSources(List.of());

            assertTrue(result);
        }

        @Test
        @DisplayName("Should return false when source has no items")
        void shouldReturnFalseWhenSourceHasNoItems() {
            // This scenario is unlikely since addItem always adds, but test the logic
            MergeState newState = state.addItem("source1", "item1");

            boolean result = newState.hasItemsFromAllSources(List.of("source1", "source2"));

            assertFalse(result); // source2 not present
        }
    }

    // ========================================================================
    // getTotalItemCount() Tests
    // ========================================================================

    @Nested
    @DisplayName("getTotalItemCount()")
    class GetTotalItemCountTests {

        @Test
        @DisplayName("Should return 0 for empty state")
        void shouldReturnZeroForEmptyState() {
            assertEquals(0, state.getTotalItemCount());
        }

        @Test
        @DisplayName("Should count items from single source")
        void shouldCountItemsFromSingleSource() {
            MergeState newState = state
                    .addItem("source1", "item1")
                    .addItem("source1", "item2")
                    .addItem("source1", "item3");

            assertEquals(3, newState.getTotalItemCount());
        }

        @Test
        @DisplayName("Should count items from multiple sources")
        void shouldCountItemsFromMultipleSources() {
            MergeState newState = state
                    .addItem("source1", "item1")
                    .addItem("source1", "item2")
                    .addItem("source2", "item3")
                    .addItem("source3", "item4")
                    .addItem("source3", "item5");

            assertEquals(5, newState.getTotalItemCount());
        }
    }

    // ========================================================================
    // Thread Safety Tests
    // ========================================================================

    @Nested
    @DisplayName("Thread Safety")
    class ThreadSafetyTests {

        @Test
        @DisplayName("Should handle concurrent operations")
        void shouldHandleConcurrentOperations() throws InterruptedException {
            MergeState sharedState = new MergeState();

            // Create multiple threads adding items
            Thread[] threads = new Thread[10];
            MergeState[] results = new MergeState[10];

            for (int i = 0; i < 10; i++) {
                final int index = i;
                threads[i] = new Thread(() -> {
                    results[index] = sharedState.addItem("source" + index, "item" + index);
                });
            }

            for (Thread thread : threads) {
                thread.start();
            }

            for (Thread thread : threads) {
                thread.join();
            }

            // Each result should have exactly one item
            for (int i = 0; i < 10; i++) {
                assertEquals(1, results[i].getTotalItemCount());
            }
        }
    }

    // ========================================================================
    // Immutability Tests
    // ========================================================================

    @Nested
    @DisplayName("Immutability")
    class ImmutabilityTests {

        @Test
        @DisplayName("addItem should not modify original state")
        void addItemShouldNotModifyOriginal() {
            int originalCount = state.getTotalItemCount();

            state.addItem("source1", "item1");

            assertEquals(originalCount, state.getTotalItemCount());
        }

        @Test
        @DisplayName("Chained operations should produce correct final state")
        void chainedOperationsShouldProduceCorrectFinalState() {
            MergeState finalState = state
                    .addItem("branch1", "result1")
                    .addItem("branch2", "result2")
                    .addItem("branch1", "result1b");

            assertEquals(3, finalState.getTotalItemCount());
            assertEquals(2, finalState.getItems("branch1").size());
            assertEquals(1, finalState.getItems("branch2").size());
        }
    }
}
