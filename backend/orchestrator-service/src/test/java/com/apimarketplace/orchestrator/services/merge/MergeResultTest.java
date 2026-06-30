package com.apimarketplace.orchestrator.services.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MergeResult")
class MergeResultTest {

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethodTests {

        @Test
        @DisplayName("complete() should create COMPLETE result")
        void completeShouldCreateCompleteResult() {
            MergeResult.MergedData data = MergeResult.MergedData.empty();
            MergeResult.MergeMetadata metadata = new MergeResult.MergeMetadata(
                5, 5, 5, 0, 1.0, null, Instant.now()
            );

            MergeResult result = MergeResult.complete("merge_1", "0", data, metadata);

            assertEquals(MergeResult.Status.COMPLETE, result.status());
            assertEquals("merge_1", result.mergePointId());
            assertEquals("0", result.scope());
            assertNotNull(result.data());
            assertNotNull(result.metadata());
        }

        @Test
        @DisplayName("partial() should create PARTIAL result")
        void partialShouldCreatePartialResult() {
            MergeResult.MergedData data = MergeResult.MergedData.empty();
            MergeResult.MergeMetadata metadata = new MergeResult.MergeMetadata(
                5, 5, 3, 2, 1.0, null, Instant.now()
            );

            MergeResult result = MergeResult.partial("merge_1", "0", data, metadata);

            assertEquals(MergeResult.Status.PARTIAL, result.status());
        }

        @Test
        @DisplayName("waiting() should create WAITING result with null data")
        void waitingShouldCreateWaitingResult() {
            MergeResult.MergeMetadata metadata = new MergeResult.MergeMetadata(
                5, 2, 2, 0, 0.4, null, Instant.now()
            );

            MergeResult result = MergeResult.waiting("merge_1", "0", metadata);

            assertEquals(MergeResult.Status.WAITING, result.status());
            assertNull(result.data());
        }

        @Test
        @DisplayName("failed() should create FAILED result with error message")
        void failedShouldCreateFailedResult() {
            MergeResult result = MergeResult.failed("merge_1", "0", "All sources failed");

            assertEquals(MergeResult.Status.FAILED, result.status());
            assertNull(result.data());
            assertNotNull(result.metadata());
            assertEquals("All sources failed", result.metadata().errorMessage());
            assertEquals(0, result.metadata().totalExpected());
        }
    }

    @Nested
    @DisplayName("isReady()")
    class IsReadyTests {

        @Test
        @DisplayName("Should return true for COMPLETE status")
        void shouldReturnTrueForComplete() {
            MergeResult result = MergeResult.complete("m", "0",
                MergeResult.MergedData.empty(),
                new MergeResult.MergeMetadata(1, 1, 1, 0, 1.0, null, Instant.now()));
            assertTrue(result.isReady());
        }

        @Test
        @DisplayName("Should return true for PARTIAL status")
        void shouldReturnTrueForPartial() {
            MergeResult result = MergeResult.partial("m", "0",
                MergeResult.MergedData.empty(),
                new MergeResult.MergeMetadata(2, 2, 1, 1, 1.0, null, Instant.now()));
            assertTrue(result.isReady());
        }

        @Test
        @DisplayName("Should return false for WAITING status")
        void shouldReturnFalseForWaiting() {
            MergeResult result = MergeResult.waiting("m", "0",
                new MergeResult.MergeMetadata(5, 2, 2, 0, 0.4, null, Instant.now()));
            assertFalse(result.isReady());
        }

        @Test
        @DisplayName("Should return false for FAILED status")
        void shouldReturnFalseForFailed() {
            MergeResult result = MergeResult.failed("m", "0", "error");
            assertFalse(result.isReady());
        }
    }

    @Nested
    @DisplayName("isWaiting()")
    class IsWaitingTests {

        @Test
        @DisplayName("Should return true for WAITING status")
        void shouldReturnTrueForWaiting() {
            MergeResult result = MergeResult.waiting("m", "0",
                new MergeResult.MergeMetadata(5, 2, 2, 0, 0.4, null, Instant.now()));
            assertTrue(result.isWaiting());
        }

        @Test
        @DisplayName("Should return false for COMPLETE status")
        void shouldReturnFalseForComplete() {
            MergeResult result = MergeResult.complete("m", "0",
                MergeResult.MergedData.empty(),
                new MergeResult.MergeMetadata(1, 1, 1, 0, 1.0, null, Instant.now()));
            assertFalse(result.isWaiting());
        }
    }

    @Nested
    @DisplayName("MergedData record")
    class MergedDataTests {

        @Test
        @DisplayName("empty() should create with all empty collections")
        void emptyShouldCreateWithEmptyCollections() {
            MergeResult.MergedData data = MergeResult.MergedData.empty();

            assertTrue(data.normalResults().isEmpty());
            assertTrue(data.splitResults().isEmpty());
            assertTrue(data.combined().isEmpty());
            assertTrue(data.bySource().isEmpty());
            assertTrue(data.byItem().isEmpty());
        }

        @Test
        @DisplayName("getTotalCount() should return combined size")
        void getTotalCountShouldReturnCombinedSize() {
            MergeResult.MergedData data = new MergeResult.MergedData(
                List.of(Map.of("a", 1)),
                List.of(),
                List.of(Map.of("a", 1), Map.of("b", 2)),
                Map.of(),
                Map.of()
            );

            assertEquals(2, data.getTotalCount());
        }

        @Test
        @DisplayName("getSplitCount() should return split results size")
        void getSplitCountShouldReturnSplitSize() {
            MergeResult.SplitItemResult split1 = new MergeResult.SplitItemResult(
                "0.1", 1, Map.of(), true, "mcp:step"
            );

            MergeResult.MergedData data = new MergeResult.MergedData(
                List.of(), List.of(split1), List.of(), Map.of(), Map.of()
            );

            assertEquals(1, data.getSplitCount());
        }

        @Test
        @DisplayName("hasSplitResults() should return true when split results exist")
        void hasSplitResultsShouldReturnTrue() {
            MergeResult.SplitItemResult split = new MergeResult.SplitItemResult(
                "0.1", 1, Map.of(), true, "mcp:step"
            );

            MergeResult.MergedData data = new MergeResult.MergedData(
                List.of(), List.of(split), List.of(), Map.of(), Map.of()
            );

            assertTrue(data.hasSplitResults());
        }

        @Test
        @DisplayName("hasSplitResults() should return false when empty")
        void hasSplitResultsShouldReturnFalse() {
            assertTrue(MergeResult.MergedData.empty().splitResults().isEmpty());
            assertFalse(MergeResult.MergedData.empty().hasSplitResults());
        }
    }

    @Nested
    @DisplayName("SplitItemResult record")
    class SplitItemResultTests {

        @Test
        @DisplayName("Should create with all fields")
        void shouldCreateWithAllFields() {
            MergeResult.SplitItemResult result = new MergeResult.SplitItemResult(
                "0.1", 1, Map.of("key", "val"), true, "mcp:process"
            );

            assertEquals("0.1", result.itemId());
            assertEquals(1, result.index());
            assertEquals(Map.of("key", "val"), result.data());
            assertTrue(result.success());
            assertEquals("mcp:process", result.sourceNodeId());
        }
    }

    @Nested
    @DisplayName("MergeMetadata record")
    class MergeMetadataTests {

        @Test
        @DisplayName("Should create with all fields")
        void shouldCreateWithAllFields() {
            Instant now = Instant.now();
            MergeResult.MergeMetadata metadata = new MergeResult.MergeMetadata(
                10, 8, 7, 1, 0.8, null, now
            );

            assertEquals(10, metadata.totalExpected());
            assertEquals(8, metadata.totalReceived());
            assertEquals(7, metadata.successCount());
            assertEquals(1, metadata.failedCount());
            assertEquals(0.8, metadata.progress(), 0.001);
            assertNull(metadata.errorMessage());
            assertEquals(now, metadata.completedAt());
        }
    }

    @Nested
    @DisplayName("Status enum")
    class StatusEnumTests {

        @Test
        @DisplayName("Should have four statuses")
        void shouldHaveFourStatuses() {
            assertEquals(4, MergeResult.Status.values().length);
        }

        @Test
        @DisplayName("Should contain COMPLETE, PARTIAL, WAITING, FAILED")
        void shouldContainExpectedValues() {
            assertNotNull(MergeResult.Status.valueOf("COMPLETE"));
            assertNotNull(MergeResult.Status.valueOf("PARTIAL"));
            assertNotNull(MergeResult.Status.valueOf("WAITING"));
            assertNotNull(MergeResult.Status.valueOf("FAILED"));
        }
    }
}
