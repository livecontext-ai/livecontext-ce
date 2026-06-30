package com.apimarketplace.orchestrator.execution.v2.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SplitState.
 * Tests immutable split iteration state.
 */
class SplitStateTest {

    // ========================================================================
    // create() Tests
    // ========================================================================

    @Nested
    @DisplayName("create()")
    class CreateTests {

        @Test
        @DisplayName("Should create initial state with index 0")
        void shouldCreateInitialStateWithIndexZero() {
            List<Object> items = List.of("a", "b", "c");

            SplitState state = SplitState.create(items, 100, "stop-on-error");

            assertEquals(0, state.currentIndex());
            assertEquals(3, state.items().size());
            assertEquals(100, state.maxItems());
            assertEquals("stop-on-error", state.splitStrategy());
            assertFalse(state.terminated());
        }

        @Test
        @DisplayName("Should handle empty items list")
        void shouldHandleEmptyItemsList() {
            SplitState state = SplitState.create(List.of(), 100, "continue-anyway");

            assertEquals(0, state.currentIndex());
            assertTrue(state.items().isEmpty());
            assertFalse(state.hasMoreItems());
        }
    }

    // ========================================================================
    // incrementIndex() Tests
    // ========================================================================

    @Nested
    @DisplayName("incrementIndex()")
    class IncrementIndexTests {

        @Test
        @DisplayName("Should increment current index")
        void shouldIncrementCurrentIndex() {
            SplitState state = SplitState.create(List.of("a", "b", "c"), 100, "stop-on-error");

            SplitState newState = state.incrementIndex();

            assertEquals(0, state.currentIndex()); // Original unchanged
            assertEquals(1, newState.currentIndex());
        }

        @Test
        @DisplayName("Should preserve other fields when incrementing")
        void shouldPreserveOtherFieldsWhenIncrementing() {
            List<Object> items = List.of("a", "b", "c");
            SplitState state = SplitState.create(items, 50, "continue-anyway");

            SplitState newState = state.incrementIndex();

            assertEquals(items, newState.items());
            assertEquals(50, newState.maxItems());
            assertEquals("continue-anyway", newState.splitStrategy());
            assertFalse(newState.terminated());
        }

        @Test
        @DisplayName("Should allow multiple increments")
        void shouldAllowMultipleIncrements() {
            SplitState state = SplitState.create(List.of("a", "b", "c"), 100, "stop-on-error");

            SplitState state1 = state.incrementIndex();
            SplitState state2 = state1.incrementIndex();
            SplitState state3 = state2.incrementIndex();

            assertEquals(0, state.currentIndex());
            assertEquals(1, state1.currentIndex());
            assertEquals(2, state2.currentIndex());
            assertEquals(3, state3.currentIndex());
        }
    }

    // ========================================================================
    // terminate() Tests
    // ========================================================================

    @Nested
    @DisplayName("terminate()")
    class TerminateTests {

        @Test
        @DisplayName("Should mark state as terminated")
        void shouldMarkStateAsTerminated() {
            SplitState state = SplitState.create(List.of("a", "b", "c"), 100, "stop-on-error");

            SplitState newState = state.terminate("error occurred");

            assertFalse(state.terminated()); // Original unchanged
            assertTrue(newState.terminated());
        }

        @Test
        @DisplayName("Should preserve current index when terminating")
        void shouldPreserveCurrentIndexWhenTerminating() {
            SplitState state = SplitState.create(List.of("a", "b", "c"), 100, "stop-on-error")
                    .incrementIndex();

            SplitState terminatedState = state.terminate("done");

            assertEquals(1, terminatedState.currentIndex());
        }
    }

    // ========================================================================
    // hasMoreItems() Tests
    // ========================================================================

    @Nested
    @DisplayName("hasMoreItems()")
    class HasMoreItemsTests {

        @Test
        @DisplayName("Should return true when more items exist")
        void shouldReturnTrueWhenMoreItemsExist() {
            SplitState state = SplitState.create(List.of("a", "b", "c"), 100, "stop-on-error");

            assertTrue(state.hasMoreItems());
        }

        @Test
        @DisplayName("Should return false when all items processed")
        void shouldReturnFalseWhenAllItemsProcessed() {
            SplitState state = SplitState.create(List.of("a", "b"), 100, "stop-on-error")
                    .incrementIndex()
                    .incrementIndex();

            assertFalse(state.hasMoreItems());
        }

        @Test
        @DisplayName("Should return false when terminated")
        void shouldReturnFalseWhenTerminated() {
            SplitState state = SplitState.create(List.of("a", "b", "c"), 100, "stop-on-error")
                    .terminate("early exit");

            assertFalse(state.hasMoreItems());
        }

        @Test
        @DisplayName("Should respect maxItems limit")
        void shouldRespectMaxItemsLimit() {
            SplitState state = SplitState.create(List.of("a", "b", "c", "d", "e"), 2, "stop-on-error")
                    .incrementIndex()
                    .incrementIndex();

            assertFalse(state.hasMoreItems()); // maxItems=2 reached
        }

        @Test
        @DisplayName("Should return false for empty items list")
        void shouldReturnFalseForEmptyItemsList() {
            SplitState state = SplitState.create(List.of(), 100, "stop-on-error");

            assertFalse(state.hasMoreItems());
        }
    }

    // ========================================================================
    // getCurrentItem() Tests
    // ========================================================================

    @Nested
    @DisplayName("getCurrentItem()")
    class GetCurrentItemTests {

        @Test
        @DisplayName("Should return first item at index 0")
        void shouldReturnFirstItemAtIndexZero() {
            SplitState state = SplitState.create(List.of("first", "second", "third"), 100, "stop-on-error");

            assertEquals("first", state.getCurrentItem());
        }

        @Test
        @DisplayName("Should return correct item after increment")
        void shouldReturnCorrectItemAfterIncrement() {
            SplitState state = SplitState.create(List.of("first", "second", "third"), 100, "stop-on-error")
                    .incrementIndex();

            assertEquals("second", state.getCurrentItem());
        }

        @Test
        @DisplayName("Should return null when index out of bounds")
        void shouldReturnNullWhenIndexOutOfBounds() {
            SplitState state = SplitState.create(List.of("a"), 100, "stop-on-error")
                    .incrementIndex()
                    .incrementIndex(); // index=2, items.size()=1

            assertNull(state.getCurrentItem());
        }

        @Test
        @DisplayName("Should return null for empty items list")
        void shouldReturnNullForEmptyItemsList() {
            SplitState state = SplitState.create(List.of(), 100, "stop-on-error");

            assertNull(state.getCurrentItem());
        }

        @Test
        @DisplayName("Should work with different object types")
        void shouldWorkWithDifferentObjectTypes() {
            SplitState state = SplitState.create(List.of(1, "string", 3.14), 100, "stop-on-error");

            assertEquals(1, state.getCurrentItem());
            assertEquals("string", state.incrementIndex().getCurrentItem());
            assertEquals(3.14, state.incrementIndex().incrementIndex().getCurrentItem());
        }
    }

    // ========================================================================
    // getItemCount() Tests
    // ========================================================================

    @Nested
    @DisplayName("getItemCount()")
    class GetItemCountTests {

        @Test
        @DisplayName("Should return items size when less than maxItems")
        void shouldReturnItemsSizeWhenLessThanMaxItems() {
            SplitState state = SplitState.create(List.of("a", "b", "c"), 100, "stop-on-error");

            assertEquals(3, state.getItemCount());
        }

        @Test
        @DisplayName("Should return maxItems when items exceed limit")
        void shouldReturnMaxItemsWhenItemsExceedLimit() {
            SplitState state = SplitState.create(List.of("a", "b", "c", "d", "e"), 3, "stop-on-error");

            assertEquals(3, state.getItemCount());
        }

        @Test
        @DisplayName("Should return 0 for empty items list")
        void shouldReturnZeroForEmptyItemsList() {
            SplitState state = SplitState.create(List.of(), 100, "stop-on-error");

            assertEquals(0, state.getItemCount());
        }
    }

    // ========================================================================
    // Immutability Tests
    // ========================================================================

    @Nested
    @DisplayName("Immutability")
    class ImmutabilityTests {

        @Test
        @DisplayName("incrementIndex should not modify original state")
        void incrementIndexShouldNotModifyOriginal() {
            SplitState original = SplitState.create(List.of("a", "b"), 100, "stop-on-error");
            int originalIndex = original.currentIndex();

            original.incrementIndex();

            assertEquals(originalIndex, original.currentIndex());
        }

        @Test
        @DisplayName("terminate should not modify original state")
        void terminateShouldNotModifyOriginal() {
            SplitState original = SplitState.create(List.of("a", "b"), 100, "stop-on-error");

            original.terminate("reason");

            assertFalse(original.terminated());
        }
    }

    // ========================================================================
    // Strategy Tests
    // ========================================================================

    @Nested
    @DisplayName("Split Strategy")
    class StrategyTests {

        @Test
        @DisplayName("Should store stop-on-error strategy")
        void shouldStoreStopOnErrorStrategy() {
            SplitState state = SplitState.create(List.of("a"), 100, "stop-on-error");

            assertEquals("stop-on-error", state.splitStrategy());
        }

        @Test
        @DisplayName("Should store continue-anyway strategy")
        void shouldStoreContinueAnywayStrategy() {
            SplitState state = SplitState.create(List.of("a"), 100, "continue-anyway");

            assertEquals("continue-anyway", state.splitStrategy());
        }
    }
}
