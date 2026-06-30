package com.apimarketplace.orchestrator.services.streaming.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MergeEvent")
class MergeEventTest {

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("Should create event with all fields")
        void shouldCreateEventWithAllFields() {
            Map<String, Object> payload = Map.of("source", "branch_0");
            MergeEvent event = new MergeEvent("run-1", "core:merge_1", MergeEventType.ENQUEUED, payload, 100L);

            assertEquals("run-1", event.runId());
            assertEquals("core:merge_1", event.mergeId());
            assertEquals(MergeEventType.ENQUEUED, event.type());
            assertEquals(payload, event.payload());
            assertEquals(100L, event.timestamp());
        }
    }

    @Nested
    @DisplayName("Validation")
    class ValidationTests {

        @Test
        @DisplayName("Should throw when runId is null")
        void shouldThrowWhenRunIdIsNull() {
            assertThrows(IllegalArgumentException.class,
                () -> new MergeEvent(null, "core:merge", MergeEventType.MERGED, null, 100L));
        }

        @Test
        @DisplayName("Should throw when mergeId is null")
        void shouldThrowWhenMergeIdIsNull() {
            assertThrows(IllegalArgumentException.class,
                () -> new MergeEvent("run-1", null, MergeEventType.MERGED, null, 100L));
        }

        @Test
        @DisplayName("Should throw when type is null")
        void shouldThrowWhenTypeIsNull() {
            assertThrows(IllegalArgumentException.class,
                () -> new MergeEvent("run-1", "core:merge", null, null, 100L));
        }
    }
}
