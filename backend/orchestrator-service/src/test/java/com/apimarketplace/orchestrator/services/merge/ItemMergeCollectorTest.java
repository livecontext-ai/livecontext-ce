package com.apimarketplace.orchestrator.services.merge;

import com.apimarketplace.orchestrator.services.cache.RunScopedCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ItemMergeCollector")
class ItemMergeCollectorTest {

    private ItemMergeCollector collector;

    @BeforeEach
    void setUp() {
        collector = new ItemMergeCollector();
    }

    @Nested
    @DisplayName("initializeMergePoint()")
    class InitializeMergePointTests {

        @Test
        @DisplayName("Should initialize merge point with source nodes")
        void shouldInitializeMergePoint() {
            ItemMergeState state = collector.initializeMergePoint(
                "run-1", "core:merge_1", "0", Set.of("mcp:step_a", "mcp:step_b")
            );

            assertNotNull(state);
            assertEquals("core:merge_1", state.getMergePointId());
            assertEquals("0", state.getScope());
            assertEquals(2, state.getSourceNodeIds().size());
        }

        @Test
        @DisplayName("Should return existing state if already initialized")
        void shouldReturnExistingState() {
            ItemMergeState first = collector.initializeMergePoint(
                "run-1", "core:merge_1", "0", Set.of("mcp:step_a")
            );

            ItemMergeState second = collector.initializeMergePoint(
                "run-1", "core:merge_1", "0", Set.of("mcp:step_b")
            );

            // Should return original, not create new with different sources
            assertSame(first, second);
        }

        @Test
        @DisplayName("Should allow different scopes for same merge point")
        void shouldAllowDifferentScopes() {
            ItemMergeState state0 = collector.initializeMergePoint(
                "run-1", "core:merge_1", "0", Set.of("mcp:step_a")
            );

            ItemMergeState state1 = collector.initializeMergePoint(
                "run-1", "core:merge_1", "1", Set.of("mcp:step_a")
            );

            assertEquals("0", state0.getScope());
            assertEquals("1", state1.getScope());
        }
    }

    @Nested
    @DisplayName("setExpectedCount()")
    class SetExpectedCountTests {

        @Test
        @DisplayName("Should update expected count for source")
        void shouldUpdateExpectedCount() {
            collector.initializeMergePoint(
                "run-1", "core:merge_1", "0", Set.of("mcp:split")
            );

            ItemMergeState updated = collector.setExpectedCount(
                "run-1", "core:merge_1", "0", "mcp:split", 5
            );

            assertNotNull(updated);
            assertEquals(5, updated.getExpectedCounts().get("mcp:split"));
        }

        @Test
        @DisplayName("Should return null when merge point not initialized")
        void shouldReturnNullWhenNotInitialized() {
            ItemMergeState result = collector.setExpectedCount(
                "run-1", "core:merge_1", "0", "mcp:split", 5
            );

            assertNull(result);
        }
    }

    @Nested
    @DisplayName("recordSuccess()")
    class RecordSuccessTests {

        @Test
        @DisplayName("Should record success and return WAITING when incomplete")
        void shouldRecordSuccessAndReturnWaiting() {
            collector.initializeMergePoint(
                "run-1", "core:merge_1", "0", Set.of("mcp:step_a", "mcp:step_b")
            );

            MergeResult result = collector.recordSuccess(
                "run-1", "core:merge_1", "0", 0, "mcp:step_a", Map.of("key", "val")
            );

            assertTrue(result.isWaiting());
        }

        @Test
        @DisplayName("Should return COMPLETE when all entries received")
        void shouldReturnCompleteWhenAllReceived() {
            collector.initializeMergePoint(
                "run-1", "core:merge_1", "0", Set.of("mcp:step_a")
            );

            MergeResult result = collector.recordSuccess(
                "run-1", "core:merge_1", "0", 0, "mcp:step_a", Map.of("key", "val")
            );

            assertTrue(result.isReady());
            assertEquals(MergeResult.Status.COMPLETE, result.status());
            assertNotNull(result.data());
            assertEquals(1, result.data().getTotalCount());
        }

        @Test
        @DisplayName("Should return FAILED when merge point not initialized")
        void shouldReturnFailedWhenNotInitialized() {
            MergeResult result = collector.recordSuccess(
                "run-1", "core:merge_1", "0", 0, "mcp:step_a", Map.of()
            );

            assertEquals(MergeResult.Status.FAILED, result.status());
        }
    }

    @Nested
    @DisplayName("recordFailure()")
    class RecordFailureTests {

        @Test
        @DisplayName("Should record failure and return PARTIAL when complete with failures")
        void shouldReturnPartialWithFailures() {
            collector.initializeMergePoint(
                "run-1", "core:merge_1", "0", Set.of("mcp:step_a")
            );

            MergeResult result = collector.recordFailure(
                "run-1", "core:merge_1", "0", 0, "mcp:step_a", "Connection refused"
            );

            assertTrue(result.isReady());
            assertEquals(MergeResult.Status.PARTIAL, result.status());
        }
    }

    @Nested
    @DisplayName("recordSkipped()")
    class RecordSkippedTests {

        @Test
        @DisplayName("Should record skipped entry")
        void shouldRecordSkippedEntry() {
            collector.initializeMergePoint(
                "run-1", "core:merge_1", "0", Set.of("mcp:step_a", "mcp:step_b")
            );

            MergeResult result = collector.recordSkipped(
                "run-1", "core:merge_1", "0", 0, "mcp:step_a", "Condition not met"
            );

            assertTrue(result.isWaiting());
        }
    }

    @Nested
    @DisplayName("Split merge scenario")
    class SplitMergeScenarioTests {

        @Test
        @DisplayName("Should handle complete split merge workflow")
        void shouldHandleCompleteSplitMerge() {
            // Initialize merge point with split source
            collector.initializeMergePoint(
                "run-1", "core:merge_1", "0", Set.of("mcp:split_step")
            );

            // Set expected count when split evaluates
            collector.setExpectedCount("run-1", "core:merge_1", "0", "mcp:split_step", 3);

            // Record split child results
            MergeResult result1 = collector.recordSuccess(
                "run-1", "core:merge_1", "0.1", 1, "mcp:split_step", Map.of("item", 1)
            );
            assertTrue(result1.isWaiting());

            MergeResult result2 = collector.recordSuccess(
                "run-1", "core:merge_1", "0.2", 2, "mcp:split_step", Map.of("item", 2)
            );
            assertTrue(result2.isWaiting());

            MergeResult result3 = collector.recordSuccess(
                "run-1", "core:merge_1", "0.3", 3, "mcp:split_step", Map.of("item", 3)
            );
            assertTrue(result3.isReady());
            assertEquals(MergeResult.Status.COMPLETE, result3.status());
            assertEquals(3, result3.data().getSplitCount());
        }
    }

    @Nested
    @DisplayName("getMergeState()")
    class GetMergeStateTests {

        @Test
        @DisplayName("Should return state when initialized")
        void shouldReturnStateWhenInitialized() {
            collector.initializeMergePoint(
                "run-1", "core:merge_1", "0", Set.of("mcp:step")
            );

            ItemMergeState state = collector.getMergeState("run-1", "core:merge_1", "0");

            assertNotNull(state);
        }

        @Test
        @DisplayName("Should return null when not initialized")
        void shouldReturnNullWhenNotInitialized() {
            assertNull(collector.getMergeState("run-1", "core:merge_1", "0"));
        }
    }

    @Nested
    @DisplayName("getProgress()")
    class GetProgressTests {

        @Test
        @DisplayName("Should return progress when initialized")
        void shouldReturnProgress() {
            collector.initializeMergePoint(
                "run-1", "core:merge_1", "0", Set.of("mcp:step")
            );

            Map<String, ItemMergeState.SourceProgress> progress =
                collector.getProgress("run-1", "core:merge_1", "0");

            assertNotNull(progress);
            assertTrue(progress.containsKey("mcp:step"));
        }

        @Test
        @DisplayName("Should return null when not initialized")
        void shouldReturnNullWhenNotInitialized() {
            assertNull(collector.getProgress("run-1", "core:merge_1", "0"));
        }
    }

    @Nested
    @DisplayName("isComplete()")
    class IsCompleteTests {

        @Test
        @DisplayName("Should return false when not initialized")
        void shouldReturnFalseWhenNotInitialized() {
            assertFalse(collector.isComplete("run-1", "core:merge_1", "0"));
        }

        @Test
        @DisplayName("Should return true when all entries received")
        void shouldReturnTrueWhenComplete() {
            collector.initializeMergePoint(
                "run-1", "core:merge_1", "0", Set.of("mcp:step")
            );
            collector.recordSuccess(
                "run-1", "core:merge_1", "0", 0, "mcp:step", Map.of()
            );

            assertTrue(collector.isComplete("run-1", "core:merge_1", "0"));
        }
    }

    @Nested
    @DisplayName("cleanupRun()")
    class CleanupRunTests {

        @Test
        @DisplayName("Should remove all merge states for a run")
        void shouldRemoveAllMergeStates() {
            collector.initializeMergePoint(
                "run-1", "core:merge_1", "0", Set.of("mcp:step")
            );
            collector.initializeMergePoint(
                "run-1", "core:merge_2", "0", Set.of("mcp:step")
            );

            collector.cleanupRun("run-1");

            assertNull(collector.getMergeState("run-1", "core:merge_1", "0"));
            assertNull(collector.getMergeState("run-1", "core:merge_2", "0"));
        }

        @Test
        @DisplayName("Should not affect other runs")
        void shouldNotAffectOtherRuns() {
            collector.initializeMergePoint(
                "run-1", "core:merge_1", "0", Set.of("mcp:step")
            );
            collector.initializeMergePoint(
                "run-2", "core:merge_1", "0", Set.of("mcp:step")
            );

            collector.cleanupRun("run-1");

            assertNull(collector.getMergeState("run-1", "core:merge_1", "0"));
            assertNotNull(collector.getMergeState("run-2", "core:merge_1", "0"));
        }
    }

    @Nested
    @DisplayName("getAllStates()")
    class GetAllStatesTests {

        @Test
        @DisplayName("Should return all active merge states")
        void shouldReturnAllStates() {
            collector.initializeMergePoint(
                "run-1", "core:merge_1", "0", Set.of("mcp:step")
            );
            collector.initializeMergePoint(
                "run-1", "core:merge_2", "1", Set.of("mcp:step")
            );

            Map<String, ItemMergeState> all = collector.getAllStates();

            assertEquals(2, all.size());
        }

        @Test
        @DisplayName("Should return empty map when no states")
        void shouldReturnEmptyMapWhenNoStates() {
            assertTrue(collector.getAllStates().isEmpty());
        }
    }

    @Nested
    @DisplayName("RunScopedCache implementation")
    class RunScopedCacheTests {

        @Test
        @DisplayName("Should implement RunScopedCache")
        void shouldImplementInterface() {
            assertInstanceOf(RunScopedCache.class, collector);
        }

        @Test
        @DisplayName("Should return correct cache name")
        void shouldReturnCacheName() {
            assertEquals("ItemMergeCache", collector.getCacheName());
        }

        @Test
        @DisplayName("Should return CONTROL_FLOW domain")
        void shouldReturnDomain() {
            assertEquals(RunScopedCache.CacheDomain.CONTROL_FLOW, collector.getDomain());
        }

        @Test
        @DisplayName("Should return correct cache size")
        void shouldReturnCacheSize() {
            assertEquals(0, collector.getCacheSize());

            collector.initializeMergePoint(
                "run-1", "core:merge_1", "0", Set.of("mcp:step")
            );

            assertEquals(1, collector.getCacheSize());
        }
    }
}
