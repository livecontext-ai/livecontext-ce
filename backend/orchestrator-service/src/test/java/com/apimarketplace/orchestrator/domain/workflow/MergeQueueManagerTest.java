package com.apimarketplace.orchestrator.domain.workflow;

import com.apimarketplace.orchestrator.domain.workflow.MergeQueueManager.MergeQueueEntry;
import com.apimarketplace.orchestrator.domain.workflow.MergeQueueManager.MergeQueueResult;
import com.apimarketplace.orchestrator.domain.workflow.MergeQueueManager.MergeQueueState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MergeQueueManager")
class MergeQueueManagerTest {

    private MergeQueueManager manager;

    @BeforeEach
    void setUp() {
        manager = new MergeQueueManager();
    }

    @Nested
    @DisplayName("getOrCreate()")
    class GetOrCreateTests {
        @Test
        @DisplayName("Should create new state if not exists")
        void shouldCreateNewStateIfNotExists() {
            MergeQueueState state = manager.getOrCreate("merge1", List.of("branch_a", "branch_b"));
            assertNotNull(state);
            assertEquals(2, state.getExpectedBranchesCount());
        }

        @Test
        @DisplayName("Should return existing state")
        void shouldReturnExistingState() {
            MergeQueueState state1 = manager.getOrCreate("merge1", List.of("branch_a"));
            MergeQueueState state2 = manager.getOrCreate("merge1", List.of("branch_b"));
            assertSame(state1, state2);
        }

        @Test
        @DisplayName("Should throw for null mergeStepId")
        void shouldThrowForNullMergeStepId() {
            assertThrows(IllegalArgumentException.class, () ->
                manager.getOrCreate(null, List.of()));
        }
    }

    @Nested
    @DisplayName("get()")
    class GetTests {
        @Test
        @DisplayName("Should return null for non-existent")
        void shouldReturnNullForNonExistent() {
            assertNull(manager.get("nonexistent"));
        }

        @Test
        @DisplayName("Should return null for null")
        void shouldReturnNullForNull() {
            assertNull(manager.get(null));
        }

        @Test
        @DisplayName("Should return existing state")
        void shouldReturnExistingState() {
            manager.getOrCreate("merge1", List.of("branch_a"));
            assertNotNull(manager.get("merge1"));
        }
    }

    @Nested
    @DisplayName("clear()")
    class ClearTests {
        @Test
        @DisplayName("Should clear all states")
        void shouldClearAllStates() {
            manager.getOrCreate("merge1", List.of());
            manager.getOrCreate("merge2", List.of());
            manager.clear();
            assertNull(manager.get("merge1"));
            assertNull(manager.get("merge2"));
        }
    }

    @Nested
    @DisplayName("MergeQueueState.enqueue()")
    class EnqueueTests {
        @Test
        @DisplayName("Should wait when not all branches ready")
        void shouldWaitWhenNotAllBranchesReady() {
            MergeQueueState state = manager.getOrCreate("merge1", List.of("branch_a", "branch_b"));
            MergeQueueEntry entry = new MergeQueueEntry("branch_a", "item1", "trigger1", 0, "tenant1", Map.of());

            MergeQueueResult result = state.enqueue("branch_a", entry);

            assertFalse(result.merged());
            assertEquals(0, result.mergedCount());
        }

        @Test
        @DisplayName("Should merge when all branches ready")
        void shouldMergeWhenAllBranchesReady() {
            MergeQueueState state = manager.getOrCreate("merge1", List.of("branch_a", "branch_b"));
            MergeQueueEntry entryA = new MergeQueueEntry("branch_a", "item1", "trigger1", 0, "tenant1", Map.of("from", "A"));
            MergeQueueEntry entryB = new MergeQueueEntry("branch_b", "item1", "trigger1", 0, "tenant1", Map.of("from", "B"));

            state.enqueue("branch_a", entryA);
            MergeQueueResult result = state.enqueue("branch_b", entryB);

            assertTrue(result.merged());
            assertEquals(1, result.mergedCount());
            assertEquals(2, result.entries().size());
        }

        @Test
        @DisplayName("Should not merge same item twice")
        void shouldNotMergeSameItemTwice() {
            MergeQueueState state = manager.getOrCreate("merge1", List.of("branch_a", "branch_b"));
            MergeQueueEntry entryA = new MergeQueueEntry("branch_a", "item1", "trigger1", 0, "tenant1", Map.of());
            MergeQueueEntry entryB = new MergeQueueEntry("branch_b", "item1", "trigger1", 0, "tenant1", Map.of());

            state.enqueue("branch_a", entryA);
            state.enqueue("branch_b", entryB);

            // Try to enqueue again for same item
            MergeQueueEntry entryA2 = new MergeQueueEntry("branch_a", "item1", "trigger1", 0, "tenant1", Map.of());
            MergeQueueResult result = state.enqueue("branch_a", entryA2);

            assertFalse(result.merged());
        }
    }

    @Nested
    @DisplayName("MergeQueueState.isItemReadyForMerge()")
    class IsItemReadyForMergeTests {
        @Test
        @DisplayName("Should return false when not all branches present")
        void shouldReturnFalseWhenNotAllBranchesPresent() {
            MergeQueueState state = manager.getOrCreate("merge1", List.of("branch_a", "branch_b"));
            MergeQueueEntry entry = new MergeQueueEntry("branch_a", "item1", "trigger1", 0, "tenant1", Map.of());
            state.enqueue("branch_a", entry);

            assertFalse(state.isItemReadyForMerge("item1"));
        }

        @Test
        @DisplayName("Should return true when all branches present")
        void shouldReturnTrueWhenAllBranchesPresent() {
            MergeQueueState state = manager.getOrCreate("merge1", List.of("branch_a", "branch_b"));
            state.enqueue("branch_a", new MergeQueueEntry("branch_a", "item1", "trigger1", 0, "tenant1", Map.of()));
            state.enqueue("branch_b", new MergeQueueEntry("branch_b", "item1", "trigger1", 0, "tenant1", Map.of()));

            assertTrue(state.isItemMerged("item1"));
        }
    }

    @Nested
    @DisplayName("MergeQueueState.getPendingBranchesForItem()")
    class GetPendingBranchesForItemTests {
        @Test
        @DisplayName("Should return all branches when none received")
        void shouldReturnAllBranchesWhenNoneReceived() {
            MergeQueueState state = manager.getOrCreate("merge1", List.of("branch_a", "branch_b"));
            var pending = state.getPendingBranchesForItem("item1");
            assertEquals(2, pending.size());
        }

        @Test
        @DisplayName("Should return only missing branches")
        void shouldReturnOnlyMissingBranches() {
            // Note: Branch IDs are normalized by adding "mcp:" prefix if no prefix exists
            MergeQueueState state = manager.getOrCreate("merge1", List.of("branch_a", "branch_b"));
            state.enqueue("branch_a", new MergeQueueEntry("branch_a", "item1", "trigger1", 0, "tenant1", Map.of()));

            var pending = state.getPendingBranchesForItem("item1");
            assertEquals(1, pending.size());
            // Normalized: "branch_b" -> "mcp:branch_b"
            assertTrue(pending.contains("mcp:branch_b"));
        }

        @Test
        @DisplayName("Should return empty for merged item")
        void shouldReturnEmptyForMergedItem() {
            MergeQueueState state = manager.getOrCreate("merge1", List.of("branch_a", "branch_b"));
            state.enqueue("branch_a", new MergeQueueEntry("branch_a", "item1", "trigger1", 0, "tenant1", Map.of()));
            state.enqueue("branch_b", new MergeQueueEntry("branch_b", "item1", "trigger1", 0, "tenant1", Map.of()));

            var pending = state.getPendingBranchesForItem("item1");
            assertTrue(pending.isEmpty());
        }
    }

    @Nested
    @DisplayName("MergeQueueState.forceMergeForItem()")
    class ForceMergeForItemTests {
        @Test
        @DisplayName("Should force merge with skipped entries for missing branches")
        void shouldForceMergeWithSkippedEntries() {
            // Note: Branch IDs are normalized by adding "mcp:" prefix if no prefix exists
            MergeQueueState state = manager.getOrCreate("merge1", List.of("branch_a", "branch_b"));
            state.enqueue("branch_a", new MergeQueueEntry("branch_a", "item1", "trigger1", 0, "tenant1", Map.of("data", "A")));

            MergeQueueResult result = state.forceMergeForItem("item1", "trigger1", 0, "tenant1");

            assertTrue(result.merged());
            assertEquals(2, result.entries().size());
            // Normalized: "branch_b" -> "mcp:branch_b"
            assertTrue(result.entries().get("mcp:branch_b").payload().containsKey("_skipped"));
        }
    }

    @Nested
    @DisplayName("MergeQueueEntry")
    class MergeQueueEntryTests {
        @Test
        @DisplayName("Should create entry with all fields")
        void shouldCreateEntryWithAllFields() {
            MergeQueueEntry entry = new MergeQueueEntry("branch_a", "item1", "trigger1", 5, "tenant1", Map.of("key", "value"));

            assertEquals("branch_a", entry.branchId());
            assertEquals("item1", entry.itemId());
            assertEquals("trigger1", entry.triggerId());
            assertEquals(5, entry.absoluteIndex());
            assertEquals("tenant1", entry.tenantId());
            assertEquals("value", entry.payload().get("key"));
            assertTrue(entry.enqueuedAt() > 0);
        }

        @Test
        @DisplayName("Should handle null payload")
        void shouldHandleNullPayload() {
            MergeQueueEntry entry = new MergeQueueEntry("branch_a", "item1", "trigger1", 0, "tenant1", null);
            assertNotNull(entry.payload());
            assertTrue(entry.payload().isEmpty());
        }
    }

    @Nested
    @DisplayName("snapshot and restore")
    class SnapshotRestoreTests {
        @Test
        @DisplayName("Should snapshot and restore state")
        void shouldSnapshotAndRestoreState() {
            MergeQueueState state = manager.getOrCreate("merge1", List.of("branch_a", "branch_b"));
            state.enqueue("branch_a", new MergeQueueEntry("branch_a", "item1", "trigger1", 0, "tenant1", Map.of()));

            Map<String, Object> snapshot = manager.getSnapshot();
            manager.clear();
            manager.restore(snapshot);

            MergeQueueState restored = manager.get("merge1");
            assertNotNull(restored);
            assertEquals(1, restored.getReceivedBranchesCount("item1"));
        }
    }
}
