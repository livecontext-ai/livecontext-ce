package com.apimarketplace.orchestrator.services.streaming;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("StreamingEventUtils")
class StreamingEventUtilsTest {

    @Nested
    @DisplayName("mapToUIStatus()")
    class MapToUIStatusTests {

        @ParameterizedTest
        @CsvSource({
            "completed, completed",
            "success, completed",
            "done, completed",
            "COMPLETED, completed",
            "SUCCESS, completed"
        })
        @DisplayName("Should map completed statuses")
        void shouldMapCompletedStatuses(String input, String expected) {
            assertEquals(expected, StreamingEventUtils.mapToUIStatus(input));
        }

        @ParameterizedTest
        @CsvSource({
            "running, running",
            "in_progress, running",
            "pending, running",
            "dispatched, running",
            "RUNNING, running"
        })
        @DisplayName("Should map running statuses")
        void shouldMapRunningStatuses(String input, String expected) {
            assertEquals(expected, StreamingEventUtils.mapToUIStatus(input));
        }

        @ParameterizedTest
        @CsvSource({
            "failure, failed",
            "failed, failed",
            "error, failed",
            "timeout, failed",
            "FAILED, failed"
        })
        @DisplayName("Should map error statuses")
        void shouldMapErrorStatuses(String input, String expected) {
            assertEquals(expected, StreamingEventUtils.mapToUIStatus(input));
        }

        @ParameterizedTest
        @CsvSource({
            "skipped, skipped",
            "ignored, skipped",
            "cancelled, skipped",
            "canceled, skipped"
        })
        @DisplayName("Should map skipped statuses")
        void shouldMapSkippedStatuses(String input, String expected) {
            assertEquals(expected, StreamingEventUtils.mapToUIStatus(input));
        }

        @Test
        @DisplayName("Should return 'pending' for null")
        void shouldReturnPendingForNull() {
            assertEquals("pending", StreamingEventUtils.mapToUIStatus(null));
        }

        @Test
        @DisplayName("Should lowercase unknown statuses")
        void shouldLowercaseUnknown() {
            assertEquals("custom_status", StreamingEventUtils.mapToUIStatus("CUSTOM_STATUS"));
        }
    }

    @Nested
    @DisplayName("canonicalizeStatusCountMap()")
    class CanonicalizeStatusCountMapTests {

        @Test
        @DisplayName("Should normalize keys to canonical form")
        void shouldNormalizeKeys() {
            Map<String, Object> raw = new HashMap<>();
            raw.put("completed", 5);
            raw.put("failed", 2);
            raw.put("skipped", 1);
            raw.put("running", 3);

            Map<String, Object> result = StreamingEventUtils.canonicalizeStatusCountMap(raw);

            assertNotNull(result);
            assertEquals(5L, result.get("completed"));
            assertEquals(2L, result.get("failed"));
            assertEquals(1L, result.get("skipped"));
            assertEquals(3L, result.get("running"));
        }

        @Test
        @DisplayName("Should handle alternate key names")
        void shouldHandleAlternateKeys() {
            Map<String, Object> raw = new HashMap<>();
            raw.put("successful", 10);
            raw.put("error", 1);
            raw.put("ignored", 2);

            Map<String, Object> result = StreamingEventUtils.canonicalizeStatusCountMap(raw);

            assertNotNull(result);
            assertEquals(10L, result.get("completed"));
            assertEquals(1L, result.get("failed"));
            assertEquals(2L, result.get("skipped"));
        }

        @Test
        @DisplayName("Should return null for null input")
        void shouldReturnNullForNull() {
            assertNull(StreamingEventUtils.canonicalizeStatusCountMap(null));
        }

        @Test
        @DisplayName("Should return null for empty map")
        void shouldReturnNullForEmpty() {
            assertNull(StreamingEventUtils.canonicalizeStatusCountMap(Map.of()));
        }

        @Test
        @DisplayName("Should skip non-Number values")
        void shouldSkipNonNumberValues() {
            Map<String, Object> raw = new HashMap<>();
            raw.put("completed", "not_a_number");
            raw.put("running", 5);

            Map<String, Object> result = StreamingEventUtils.canonicalizeStatusCountMap(raw);

            assertNotNull(result);
            assertFalse(result.containsKey("completed"));
            assertEquals(5L, result.get("running"));
        }

        @Test
        @DisplayName("Should skip null keys")
        void shouldSkipNullKeys() {
            Map<String, Object> raw = new HashMap<>();
            raw.put(null, 5);
            raw.put("running", 3);

            Map<String, Object> result = StreamingEventUtils.canonicalizeStatusCountMap(raw);

            assertNotNull(result);
            assertEquals(3L, result.get("running"));
        }

        @Test
        @DisplayName("Should convert all values to long")
        void shouldConvertToLong() {
            Map<String, Object> raw = new HashMap<>();
            raw.put("completed", 42);

            Map<String, Object> result = StreamingEventUtils.canonicalizeStatusCountMap(raw);

            assertNotNull(result);
            assertInstanceOf(Long.class, result.get("completed"));
        }

        @Test
        @DisplayName("Should handle 'processed' and 'total' keys")
        void shouldHandleProcessedAndTotal() {
            Map<String, Object> raw = new HashMap<>();
            raw.put("processed", 8);
            raw.put("total", 10);

            Map<String, Object> result = StreamingEventUtils.canonicalizeStatusCountMap(raw);

            assertNotNull(result);
            assertEquals(8L, result.get("processed"));
            assertEquals(10L, result.get("total"));
        }
    }
}
