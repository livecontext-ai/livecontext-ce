package com.apimarketplace.orchestrator.services.streaming.state;

import com.apimarketplace.orchestrator.services.streaming.events.EdgeLifecycle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("EdgeCounters")
class EdgeCountersTest {

    private EdgeCounters counters;

    @BeforeEach
    void setUp() {
        counters = new EdgeCounters("mcp:a", "mcp:b");
    }

    @Nested
    @DisplayName("applyLifecycleForItem()")
    class ApplyLifecycleForItemTests {

        @Test
        @DisplayName("Should count a single RUNNING item")
        void shouldCountSingleRunningItem() {
            counters.applyLifecycleForItem(EdgeLifecycle.RUNNING, 0, null);

            Map<String, Object> payload = counters.toPayload("edge-1");
            assertEquals(1L, payload.get("running"));
            assertEquals(0L, payload.get("completed"));
            assertEquals(0L, payload.get("skipped"));
        }

        @Test
        @DisplayName("Should count a single COMPLETED item")
        void shouldCountSingleCompletedItem() {
            counters.applyLifecycleForItem(EdgeLifecycle.COMPLETED, 0, null);

            Map<String, Object> payload = counters.toPayload("edge-1");
            assertEquals(0L, payload.get("running"));
            assertEquals(1L, payload.get("completed"));
        }

        @Test
        @DisplayName("Should transition RUNNING to COMPLETED for same item")
        void shouldTransitionRunningToCompleted() {
            counters.applyLifecycleForItem(EdgeLifecycle.RUNNING, 0, 0);
            counters.applyLifecycleForItem(EdgeLifecycle.COMPLETED, 0, 0);

            Map<String, Object> payload = counters.toPayload("edge-1");
            assertEquals(0L, payload.get("running"));
            assertEquals(1L, payload.get("completed"));
        }

        @Test
        @DisplayName("Should count multiple items independently")
        void shouldCountMultipleItems() {
            counters.applyLifecycleForItem(EdgeLifecycle.COMPLETED, 0, 0);
            counters.applyLifecycleForItem(EdgeLifecycle.COMPLETED, 1, 0);
            counters.applyLifecycleForItem(EdgeLifecycle.SKIPPED, 2, 0);

            Map<String, Object> payload = counters.toPayload("edge-1");
            assertEquals(2L, payload.get("completed"));
            assertEquals(1L, payload.get("skipped"));
        }

        @Test
        @DisplayName("Should deduplicate same item at same iteration")
        void shouldDeduplicateSameItem() {
            counters.applyLifecycleForItem(EdgeLifecycle.COMPLETED, 0, 0);
            counters.applyLifecycleForItem(EdgeLifecycle.COMPLETED, 0, 0);

            Map<String, Object> payload = counters.toPayload("edge-1");
            assertEquals(1L, payload.get("completed"));
        }

        @Test
        @DisplayName("Should not override terminal status with RUNNING")
        void shouldNotOverrideTerminalWithRunning() {
            counters.applyLifecycleForItem(EdgeLifecycle.COMPLETED, 0, 0);
            counters.applyLifecycleForItem(EdgeLifecycle.RUNNING, 0, 0);

            Map<String, Object> payload = counters.toPayload("edge-1");
            assertEquals(1L, payload.get("completed"));
            assertEquals(0L, payload.get("running"));
        }

        @Test
        @DisplayName("COMPLETED should override SKIPPED")
        void completedShouldOverrideSkipped() {
            counters.applyLifecycleForItem(EdgeLifecycle.SKIPPED, 0, 0);
            counters.applyLifecycleForItem(EdgeLifecycle.COMPLETED, 0, 0);

            Map<String, Object> payload = counters.toPayload("edge-1");
            assertEquals(1L, payload.get("completed"));
            assertEquals(0L, payload.get("skipped"));
        }

        @Test
        @DisplayName("Should ignore REGISTERED lifecycle")
        void shouldIgnoreRegistered() {
            counters.applyLifecycleForItem(EdgeLifecycle.REGISTERED, 0, 0);

            Map<String, Object> payload = counters.toPayload("edge-1");
            assertEquals(0L, payload.get("running"));
            assertEquals(0L, payload.get("completed"));
        }

        @Test
        @DisplayName("Should ignore null lifecycle")
        void shouldIgnoreNullLifecycle() {
            counters.applyLifecycleForItem(null, 0, 0);

            Map<String, Object> payload = counters.toPayload("edge-1");
            assertEquals(0L, payload.get("running"));
        }

        @Test
        @DisplayName("Should treat null itemIndex as 0")
        void shouldTreatNullItemIndexAsZero() {
            counters.applyLifecycleForItem(EdgeLifecycle.COMPLETED, null, null);

            Map<String, Object> payload = counters.toPayload("edge-1");
            assertEquals(1L, payload.get("completed"));
        }

        @Test
        @DisplayName("Should count different iterations separately")
        void shouldCountDifferentIterationsSeparately() {
            counters.applyLifecycleForItem(EdgeLifecycle.COMPLETED, 0, 0);
            counters.applyLifecycleForItem(EdgeLifecycle.COMPLETED, 0, 1);
            counters.applyLifecycleForItem(EdgeLifecycle.COMPLETED, 0, 2);

            Map<String, Object> payload = counters.toPayload("edge-1");
            assertEquals(3L, payload.get("completed"));
        }

        @Test
        @DisplayName("Should skip negative item indices that are not synthetic")
        void shouldSkipNegativeNonSyntheticIndices() {
            counters.applyLifecycleForItem(EdgeLifecycle.COMPLETED, -5, 0);

            Map<String, Object> payload = counters.toPayload("edge-1");
            assertEquals(0L, payload.get("completed"));
        }
    }

    @Nested
    @DisplayName("prePopulateCounts()")
    class PrePopulateCountsTests {

        @Test
        @DisplayName("Should pre-populate completed counts")
        void shouldPrePopulateCompletedCounts() {
            counters.prePopulateCounts(Map.of("completed", 5));

            Map<String, Object> payload = counters.toPayload("edge-1");
            assertEquals(5L, payload.get("completed"));
        }

        @Test
        @DisplayName("Should pre-populate skipped counts")
        void shouldPrePopulateSkippedCounts() {
            counters.prePopulateCounts(Map.of("skipped", 3));

            Map<String, Object> payload = counters.toPayload("edge-1");
            assertEquals(3L, payload.get("skipped"));
        }

        @Test
        @DisplayName("Should pre-populate both completed and skipped")
        void shouldPrePopulateBoth() {
            counters.prePopulateCounts(Map.of("completed", 10, "skipped", 2));

            Map<String, Object> payload = counters.toPayload("edge-1");
            assertEquals(10L, payload.get("completed"));
            assertEquals(2L, payload.get("skipped"));
        }

        @Test
        @DisplayName("Should recognize alternate key names")
        void shouldRecognizeAlternateKeys() {
            counters.prePopulateCounts(Map.of("success", 7));

            Map<String, Object> payload = counters.toPayload("edge-1");
            assertEquals(7L, payload.get("completed"));
        }
    }

    @Nested
    @DisplayName("toPayload()")
    class ToPayloadTests {

        @Test
        @DisplayName("Should include edge metadata in payload")
        void shouldIncludeEdgeMetadata() {
            Map<String, Object> payload = counters.toPayload("edge-1");

            assertEquals("edge-1", payload.get("id"));
            assertEquals("mcp:a", payload.get("from"));
            assertEquals("mcp:b", payload.get("to"));
        }

        @Test
        @DisplayName("Should include statusCounts sub-map")
        void shouldIncludeStatusCounts() {
            counters.applyLifecycleForItem(EdgeLifecycle.COMPLETED, 0, 0);

            Map<String, Object> payload = counters.toPayload("edge-1");

            @SuppressWarnings("unchecked")
            Map<String, Object> counts = (Map<String, Object>) payload.get("statusCounts");
            assertNotNull(counts);
            assertEquals(1L, counts.get("COMPLETED"));
            assertEquals(0L, counts.get("RUNNING"));
            assertEquals(0L, counts.get("FAILED"));
        }

        @Test
        @DisplayName("Should calculate PROCESSED and TOTAL correctly")
        void shouldCalculateProcessedAndTotal() {
            counters.applyLifecycleForItem(EdgeLifecycle.COMPLETED, 0, 0);
            counters.applyLifecycleForItem(EdgeLifecycle.SKIPPED, 1, 0);
            counters.applyLifecycleForItem(EdgeLifecycle.RUNNING, 2, 0);

            Map<String, Object> payload = counters.toPayload("edge-1");

            @SuppressWarnings("unchecked")
            Map<String, Object> counts = (Map<String, Object>) payload.get("statusCounts");
            assertEquals(2L, counts.get("PROCESSED")); // completed + skipped
            assertEquals(3L, counts.get("TOTAL")); // processed + running
        }
    }
}
