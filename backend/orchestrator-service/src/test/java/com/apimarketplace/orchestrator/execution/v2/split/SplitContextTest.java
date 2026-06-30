package com.apimarketplace.orchestrator.execution.v2.split;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SplitContext")
class SplitContextTest {

    @Nested
    @DisplayName("create()")
    class Create {

        @Test
        @DisplayName("should create context with items")
        void shouldCreateContextWithItems() {
            List<Object> items = List.of("item1", "item2", "item3");

            SplitContext context = SplitContext.create("core:split1", items);

            assertThat(context.splitNodeId()).isEqualTo("core:split1");
            assertThat(context.items()).hasSize(3);
            assertThat(context.itemCount()).isEqualTo(3);
            assertThat(context.isEmpty()).isFalse();
        }

        @Test
        @DisplayName("should create empty context when items is null")
        void shouldCreateEmptyContextWhenItemsNull() {
            SplitContext context = SplitContext.create("core:split1", null);

            assertThat(context.items()).isEmpty();
            assertThat(context.itemCount()).isEqualTo(0);
            assertThat(context.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("should create defensive copy of items")
        void shouldCreateDefensiveCopy() {
            List<Object> items = new java.util.ArrayList<>(List.of("item1", "item2"));
            SplitContext context = SplitContext.create("core:split1", items);

            items.add("item3"); // Modify original list

            assertThat(context.items()).hasSize(2); // Context unchanged
        }
    }

    @Nested
    @DisplayName("getItem()")
    class GetItem {

        @Test
        @DisplayName("should return item at index")
        void shouldReturnItemAtIndex() {
            List<Object> items = List.of("a", "b", "c");
            SplitContext context = SplitContext.create("core:split1", items);

            assertThat(context.getItem(0)).isEqualTo("a");
            assertThat(context.getItem(1)).isEqualTo("b");
            assertThat(context.getItem(2)).isEqualTo("c");
        }

        @Test
        @DisplayName("should throw on invalid index")
        void shouldThrowOnInvalidIndex() {
            SplitContext context = SplitContext.create("core:split1", List.of("a"));

            assertThatThrownBy(() -> context.getItem(5))
                .isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    @Nested
    @DisplayName("withResults()")
    class WithResults {

        @Test
        @DisplayName("should store results for node")
        void shouldStoreResultsForNode() {
            SplitContext context = SplitContext.create("core:split1", List.of("a", "b"));
            List<Object> results = List.of("result1", "result2");

            SplitContext updated = context.withResults("mcp:step1", results);

            assertThat(updated.getResults("mcp:step1")).containsExactly("result1", "result2");
            assertThat(context.getResults("mcp:step1")).isEmpty(); // Original unchanged
        }

        @Test
        @DisplayName("should store results for multiple nodes")
        void shouldStoreResultsForMultipleNodes() {
            SplitContext context = SplitContext.create("core:split1", List.of("a", "b"));

            context = context.withResults("mcp:step1", List.of("r1", "r2"));
            context = context.withResults("mcp:step2", List.of("r3", "r4"));

            assertThat(context.getResults("mcp:step1")).containsExactly("r1", "r2");
            assertThat(context.getResults("mcp:step2")).containsExactly("r3", "r4");
        }

        @Test
        @DisplayName("should handle null results as empty list")
        void shouldHandleNullResults() {
            SplitContext context = SplitContext.create("core:split1", List.of("a"));

            SplitContext updated = context.withResults("mcp:step1", null);

            assertThat(updated.getResults("mcp:step1")).isEmpty();
        }
    }

    @Nested
    @DisplayName("getResults()")
    class GetResults {

        @Test
        @DisplayName("should return empty list for unknown node")
        void shouldReturnEmptyListForUnknownNode() {
            SplitContext context = SplitContext.create("core:split1", List.of("a"));

            assertThat(context.getResults("mcp:unknown")).isEmpty();
        }

        @Test
        @DisplayName("should check if results exist")
        void shouldCheckIfResultsExist() {
            SplitContext context = SplitContext.create("core:split1", List.of("a"))
                .withResults("mcp:step1", List.of("result"));

            assertThat(context.hasResults("mcp:step1")).isTrue();
            assertThat(context.hasResults("mcp:unknown")).isFalse();
        }
    }

    @Nested
    @DisplayName("clearResults()")
    class ClearResults {

        @Test
        @DisplayName("should clear results for specific node")
        void shouldClearResultsForNode() {
            SplitContext context = SplitContext.create("core:split1", List.of("a"))
                .withResults("mcp:step1", List.of("r1"))
                .withResults("mcp:step2", List.of("r2"));

            SplitContext cleared = context.clearResults("mcp:step1");

            assertThat(cleared.hasResults("mcp:step1")).isFalse();
            assertThat(cleared.hasResults("mcp:step2")).isTrue();
        }

        @Test
        @DisplayName("should clear all results")
        void shouldClearAllResults() {
            SplitContext context = SplitContext.create("core:split1", List.of("a", "b"))
                .withResults("mcp:step1", List.of("r1", "r2"))
                .withResults("mcp:step2", List.of("r3", "r4"));

            SplitContext cleared = context.clearAllResults();

            assertThat(cleared.items()).hasSize(2); // Items preserved
            assertThat(cleared.getAllResults()).isEmpty(); // Results cleared
        }
    }

    @Nested
    @DisplayName("getAllResults()")
    class GetAllResults {

        @Test
        @DisplayName("should return all stored results")
        void shouldReturnAllResults() {
            SplitContext context = SplitContext.create("core:split1", List.of("a"))
                .withResults("mcp:step1", List.of("r1"))
                .withResults("mcp:step2", List.of("r2"));

            Map<String, List<Object>> allResults = context.getAllResults();

            assertThat(allResults).hasSize(2);
            assertThat(allResults).containsKeys("mcp:step1", "mcp:step2");
        }

        @Test
        @DisplayName("should return unmodifiable map")
        void shouldReturnUnmodifiableMap() {
            SplitContext context = SplitContext.create("core:split1", List.of("a"))
                .withResults("mcp:step1", List.of("r1"));

            Map<String, List<Object>> allResults = context.getAllResults();

            assertThatThrownBy(() -> allResults.put("new", List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("immutability")
    class Immutability {

        @Test
        @DisplayName("items list should be immutable")
        void itemsShouldBeImmutable() {
            SplitContext context = SplitContext.create("core:split1", List.of("a", "b"));

            assertThatThrownBy(() -> context.items().add("c"))
                .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("withResultAtIndex()")
    class WithResultAtIndex {

        @Test
        @DisplayName("should pre-size list with nulls and set the slot when no list exists yet")
        void shouldPresizeListAndSetSlot() {
            SplitContext context = SplitContext.create("core:split1", List.of("a", "b", "c"));

            SplitContext updated = context.withResultAtIndex("mcp:read", 1, 3, "result-1");

            assertThat(updated.getResults("mcp:read")).containsExactly(null, "result-1", null);
        }

        @Test
        @DisplayName("should preserve previously written slots when storing new ones (the regression case)")
        void shouldPreservePreviousSlots() {
            SplitContext context = SplitContext.create("core:split1", List.of("a", "b", "c"))
                .withResultAtIndex("mcp:read", 0, 3, "r0")
                .withResultAtIndex("mcp:read", 2, 3, "r2");

            // The bug we're guarding against was: per-item evaluation seeing the same value N
            // times because chained-downstream nodes had no per-item slot in the SplitContext.
            // After writing slots 0 and 2 in two separate calls, both must survive - anything
            // less means a future SplitAggregateHandler iteration would read a stale value.
            assertThat(context.getResults("mcp:read")).containsExactly("r0", null, "r2");
        }

        @Test
        @DisplayName("should pad existing list when totalItems grows beyond current size")
        void shouldPadExistingList() {
            SplitContext context = SplitContext.create("core:split1", List.of("a", "b"))
                .withResults("mcp:read", List.of("r0", "r1"));

            SplitContext updated = context.withResultAtIndex("mcp:read", 4, 5, "r4");

            assertThat(updated.getResults("mcp:read")).containsExactly("r0", "r1", null, null, "r4");
        }

        @Test
        @DisplayName("should be a no-op for null nodeId, negative itemIndex, or null result")
        void shouldNoOpOnInvalidInput() {
            SplitContext context = SplitContext.create("core:split1", List.of("a"));

            assertThat(context.withResultAtIndex(null, 0, 1, "x")).isSameAs(context);
            assertThat(context.withResultAtIndex("mcp:read", -1, 1, "x")).isSameAs(context);
            // Null result is rejected so a chained-downstream failed-after-success retry can't
            // zero out a previously-good slot - failure handling lives in the skip cascade.
            assertThat(context.withResultAtIndex("mcp:read", 0, 1, null)).isSameAs(context);
        }

        @Test
        @DisplayName("should not mutate the original context (immutability invariant)")
        void shouldNotMutateOriginal() {
            SplitContext original = SplitContext.create("core:split1", List.of("a", "b", "c"))
                .withResultAtIndex("mcp:read", 0, 3, "r0");

            SplitContext derived = original.withResultAtIndex("mcp:read", 1, 3, "r1");

            assertThat(original.getResults("mcp:read")).containsExactly("r0", null, null);
            assertThat(derived.getResults("mcp:read")).containsExactly("r0", "r1", null);
        }

        @Test
        @DisplayName("should grow the list to itemIndex+1 when totalItems is too small")
        void shouldGrowToItemIndexPlusOne() {
            SplitContext context = SplitContext.create("core:split1", List.of("a"));

            // Pathological: caller passes totalItems=1 but itemIndex=2. We grow to fit rather
            // than throw, because callers downstream of split don't always know totalItems
            // at compile time and silent NPE would hide bugs.
            SplitContext updated = context.withResultAtIndex("mcp:read", 2, 1, "r2");

            assertThat(updated.getResults("mcp:read")).containsExactly(null, null, "r2");
        }
    }
}
