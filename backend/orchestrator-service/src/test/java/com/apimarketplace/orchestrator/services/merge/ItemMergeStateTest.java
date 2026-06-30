package com.apimarketplace.orchestrator.services.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ItemMergeState.
 *
 * This class is immutable state for a merge point scoped by parent item.
 * It tracks all entries that need to be collected before merge can proceed.
 */
@DisplayName("ItemMergeState")
class ItemMergeStateTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // create() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("create()")
    class CreateTests {

        @Test
        @DisplayName("Should create state with default expected count of 1 for each source")
        void shouldCreateStateWithDefaultExpectedCountOfOneForEachSource() {
            Set<String> sources = Set.of("mcp:step1", "mcp:step2");

            ItemMergeState state = ItemMergeState.create("merge-1", "0", sources);

            assertEquals("merge-1", state.getMergePointId());
            assertEquals("0", state.getScope());
            assertEquals(sources, state.getSourceNodeIds());
            assertEquals(1, state.getExpectedCounts().get("mcp:step1"));
            assertEquals(1, state.getExpectedCounts().get("mcp:step2"));
        }

        @Test
        @DisplayName("Should start with empty entries")
        void shouldStartWithEmptyEntries() {
            Set<String> sources = Set.of("mcp:step1");

            ItemMergeState state = ItemMergeState.create("merge-1", "0", sources);

            assertTrue(state.getEntriesForSource("mcp:step1").isEmpty());
            assertEquals(0.0, state.getProgress());
        }

        @Test
        @DisplayName("Should not be complete initially")
        void shouldNotBeCompleteInitially() {
            Set<String> sources = Set.of("mcp:step1");

            ItemMergeState state = ItemMergeState.create("merge-1", "0", sources);

            assertFalse(state.isComplete());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // withExpectedCount() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("withExpectedCount()")
    class WithExpectedCountTests {

        @Test
        @DisplayName("Should update expected count in-place and return same instance")
        void shouldUpdateExpectedCountInPlace() {
            ItemMergeState state = ItemMergeState.create("merge-1", "0", Set.of("core:foreach"));

            ItemMergeState returned = state.withExpectedCount("core:foreach", 5);

            assertSame(state, returned);
            assertEquals(5, state.getExpectedCounts().get("core:foreach"));
        }

        @Test
        @DisplayName("getExpectedCounts returns defensive copy - external mutation does not affect state")
        void getExpectedCountsReturnsDefensiveCopy() {
            ItemMergeState state = ItemMergeState.create("merge-1", "0", Set.of("mcp:step"));
            Map<String, Integer> snapshot = state.getExpectedCounts();

            assertThrows(UnsupportedOperationException.class, () -> snapshot.put("mcp:step", 99));
            assertEquals(1, state.getExpectedCounts().get("mcp:step"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // withEntry() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("withEntry()")
    class WithEntryTests {

        @Test
        @DisplayName("Should add entry in-place and return same instance")
        void shouldAddEntryInPlace() {
            ItemMergeState state = ItemMergeState.create("merge-1", "0", Set.of("mcp:step"));
            ItemMergeEntry entry = ItemMergeEntry.success("0", 0, "mcp:step", Map.of("data", "value"));

            ItemMergeState returned = state.withEntry(entry);

            assertSame(state, returned);
            assertEquals(1, state.getEntriesForSource("mcp:step").size());
        }

        @Test
        @DisplayName("Should throw when entry scope does not match")
        void shouldThrowWhenEntryScopeDoesNotMatch() {
            ItemMergeState state = ItemMergeState.create("merge-1", "0", Set.of("mcp:step"));
            ItemMergeEntry entry = ItemMergeEntry.success("1.1", 0, "mcp:step", Map.of());

            assertThrows(IllegalArgumentException.class, () -> state.withEntry(entry));
        }

        @Test
        @DisplayName("Mutation is visible through the same instance after withEntry")
        void mutationVisibleThroughSameInstance() {
            ItemMergeState state = ItemMergeState.create("merge-1", "0", Set.of("mcp:step"));

            assertTrue(state.getEntriesForSource("mcp:step").isEmpty());

            ItemMergeEntry entry = ItemMergeEntry.success("0", 0, "mcp:step", Map.of());
            state.withEntry(entry);

            assertEquals(1, state.getEntriesForSource("mcp:step").size());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // isComplete() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("isComplete()")
    class IsCompleteTests {

        @Test
        @DisplayName("Should be complete when all expected entries received")
        void shouldBeCompleteWhenAllExpectedEntriesReceived() {
            ItemMergeState state = ItemMergeState.create("merge-1", "0", Set.of("mcp:step"));
            ItemMergeEntry entry = ItemMergeEntry.success("0", 0, "mcp:step", Map.of());

            ItemMergeState updated = state.withEntry(entry);

            assertTrue(updated.isComplete());
        }

        @Test
        @DisplayName("Should not be complete when still waiting for entries")
        void shouldNotBeCompleteWhenStillWaitingForEntries() {
            ItemMergeState state = ItemMergeState.create("merge-1", "0", Set.of("mcp:step1", "mcp:step2"));
            ItemMergeEntry entry = ItemMergeEntry.success("0", 0, "mcp:step1", Map.of());

            ItemMergeState updated = state.withEntry(entry);

            assertFalse(updated.isComplete());
        }

        @Test
        @DisplayName("Should require expected count entries for ForEach sources")
        void shouldRequireExpectedCountEntriesForForEachSources() {
            ItemMergeState state = ItemMergeState.create("merge-1", "0", Set.of("core:foreach"))
                    .withExpectedCount("core:foreach", 3);

            ItemMergeEntry entry1 = ItemMergeEntry.success("0.1", 1, "core:foreach", Map.of());
            ItemMergeEntry entry2 = ItemMergeEntry.success("0.2", 2, "core:foreach", Map.of());

            ItemMergeState withTwo = state.withEntry(entry1).withEntry(entry2);

            assertFalse(withTwo.isComplete());

            ItemMergeEntry entry3 = ItemMergeEntry.success("0.3", 3, "core:foreach", Map.of());
            ItemMergeState withThree = withTwo.withEntry(entry3);

            assertTrue(withThree.isComplete());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getProgress() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getProgress()")
    class GetProgressTests {

        @Test
        @DisplayName("Should return 0 when no entries received")
        void shouldReturnZeroWhenNoEntriesReceived() {
            ItemMergeState state = ItemMergeState.create("merge-1", "0", Set.of("mcp:step"));

            assertEquals(0.0, state.getProgress(), 0.001);
        }

        @Test
        @DisplayName("Should return 1 when complete")
        void shouldReturnOneWhenComplete() {
            ItemMergeState state = ItemMergeState.create("merge-1", "0", Set.of("mcp:step"));
            ItemMergeEntry entry = ItemMergeEntry.success("0", 0, "mcp:step", Map.of());

            ItemMergeState updated = state.withEntry(entry);

            assertEquals(1.0, updated.getProgress(), 0.001);
        }

        @Test
        @DisplayName("Should return fractional progress")
        void shouldReturnFractionalProgress() {
            ItemMergeState state = ItemMergeState.create("merge-1", "0", Set.of("core:foreach"))
                    .withExpectedCount("core:foreach", 4);

            ItemMergeEntry entry = ItemMergeEntry.success("0.1", 1, "core:foreach", Map.of());
            ItemMergeState updated = state.withEntry(entry);

            assertEquals(0.25, updated.getProgress(), 0.001);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getSplitResults() / getNormalResults() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getSplitResults() and getNormalResults()")
    class ResultTypesTests {

        @Test
        @DisplayName("Should separate Split results from normal results")
        void shouldSeparateSplitResultsFromNormalResults() {
            ItemMergeState state = ItemMergeState.create("merge-1", "0", Set.of("mcp:normal", "core:split"))
                    .withExpectedCount("core:split", 2);

            ItemMergeEntry normalEntry = ItemMergeEntry.success("0", 0, "mcp:normal", Map.of("type", "normal"));
            ItemMergeEntry splitEntry1 = ItemMergeEntry.success("0.1", 1, "core:split", Map.of("type", "split"));
            ItemMergeEntry splitEntry2 = ItemMergeEntry.success("0.2", 2, "core:split", Map.of("type", "split"));

            ItemMergeState updated = state
                    .withEntry(normalEntry)
                    .withEntry(splitEntry1)
                    .withEntry(splitEntry2);

            List<ItemMergeEntry> normalResults = updated.getNormalResults();
            List<ItemMergeEntry> splitResults = updated.getSplitResults();

            assertEquals(1, normalResults.size());
            assertEquals(2, splitResults.size());
            assertEquals("normal", normalResults.get(0).data().get("type"));
        }

        @Test
        @DisplayName("Should sort Split results by item index")
        void shouldSortSplitResultsByItemIndex() {
            ItemMergeState state = ItemMergeState.create("merge-1", "0", Set.of("core:split"))
                    .withExpectedCount("core:split", 3);

            // Add out of order
            ItemMergeEntry entry3 = ItemMergeEntry.success("0.3", 3, "core:split", Map.of("index", 3));
            ItemMergeEntry entry1 = ItemMergeEntry.success("0.1", 1, "core:split", Map.of("index", 1));
            ItemMergeEntry entry2 = ItemMergeEntry.success("0.2", 2, "core:split", Map.of("index", 2));

            ItemMergeState updated = state.withEntry(entry3).withEntry(entry1).withEntry(entry2);

            List<ItemMergeEntry> results = updated.getSplitResults();

            assertEquals(1, results.get(0).itemIndex());
            assertEquals(2, results.get(1).itemIndex());
            assertEquals(3, results.get(2).itemIndex());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // hasFailures() / getSuccessCount() / getFailedCount() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Failure Tracking")
    class FailureTrackingTests {

        @Test
        @DisplayName("Should detect failures")
        void shouldDetectFailures() {
            ItemMergeState state = ItemMergeState.create("merge-1", "0", Set.of("mcp:step"));
            ItemMergeEntry failedEntry = ItemMergeEntry.failed("0", 0, "mcp:step", "Error");

            ItemMergeState updated = state.withEntry(failedEntry);

            assertTrue(updated.hasFailures());
            assertEquals(0, updated.getSuccessCount());
            assertEquals(1, updated.getFailedCount());
        }

        @Test
        @DisplayName("Should count successes correctly")
        void shouldCountSuccessesCorrectly() {
            ItemMergeState state = ItemMergeState.create("merge-1", "0", Set.of("core:foreach"))
                    .withExpectedCount("core:foreach", 3);

            ItemMergeEntry success1 = ItemMergeEntry.success("0.1", 1, "core:foreach", Map.of());
            ItemMergeEntry success2 = ItemMergeEntry.success("0.2", 2, "core:foreach", Map.of());
            ItemMergeEntry failed = ItemMergeEntry.failed("0.3", 3, "core:foreach", "Error");

            ItemMergeState updated = state.withEntry(success1).withEntry(success2).withEntry(failed);

            assertEquals(2, updated.getSuccessCount());
            assertEquals(1, updated.getFailedCount());
            assertTrue(updated.hasFailures());
        }

        @Test
        @DisplayName("Should report no failures when all succeed")
        void shouldReportNoFailuresWhenAllSucceed() {
            ItemMergeState state = ItemMergeState.create("merge-1", "0", Set.of("mcp:step"));
            ItemMergeEntry successEntry = ItemMergeEntry.success("0", 0, "mcp:step", Map.of());

            ItemMergeState updated = state.withEntry(successEntry);

            assertFalse(updated.hasFailures());
            assertEquals(1, updated.getSuccessCount());
            assertEquals(0, updated.getFailedCount());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getDetailedProgress() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getDetailedProgress()")
    class DetailedProgressTests {

        @Test
        @DisplayName("Should provide detailed progress per source")
        void shouldProvideDetailedProgressPerSource() {
            ItemMergeState state = ItemMergeState.create("merge-1", "0", Set.of("mcp:normal", "core:foreach"))
                    .withExpectedCount("core:foreach", 3);

            ItemMergeEntry normalEntry = ItemMergeEntry.success("0", 0, "mcp:normal", Map.of());
            ItemMergeEntry forEachEntry1 = ItemMergeEntry.success("0.1", 1, "core:foreach", Map.of());
            ItemMergeEntry forEachEntry2 = ItemMergeEntry.failed("0.2", 2, "core:foreach", "Error");

            ItemMergeState updated = state.withEntry(normalEntry).withEntry(forEachEntry1).withEntry(forEachEntry2);

            Map<String, ItemMergeState.SourceProgress> progress = updated.getDetailedProgress();

            ItemMergeState.SourceProgress normalProgress = progress.get("mcp:normal");
            assertEquals(1, normalProgress.expected());
            assertEquals(1, normalProgress.received());
            assertEquals(1, normalProgress.success());
            assertTrue(normalProgress.isComplete());

            ItemMergeState.SourceProgress forEachProgress = progress.get("core:foreach");
            assertEquals(3, forEachProgress.expected());
            assertEquals(2, forEachProgress.received());
            assertEquals(1, forEachProgress.success());
            assertEquals(1, forEachProgress.failed());
            assertFalse(forEachProgress.isComplete());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SourceProgress tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("SourceProgress")
    class SourceProgressTests {

        @Test
        @DisplayName("Should calculate progress correctly")
        void shouldCalculateProgressCorrectly() {
            ItemMergeState.SourceProgress progress = new ItemMergeState.SourceProgress(4, 2, 1, 1, 0);

            assertEquals(0.5, progress.getProgress(), 0.001);
            assertFalse(progress.isComplete());
        }

        @Test
        @DisplayName("Should be complete when received equals expected")
        void shouldBeCompleteWhenReceivedEqualsExpected() {
            ItemMergeState.SourceProgress progress = new ItemMergeState.SourceProgress(3, 3, 2, 1, 0);

            assertEquals(1.0, progress.getProgress(), 0.001);
            assertTrue(progress.isComplete());
        }
    }
}
