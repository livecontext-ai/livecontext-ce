package com.apimarketplace.orchestrator.services.streaming.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LoopEvent")
class LoopEventTest {

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("Should create event with all fields")
        void shouldCreateEventWithAllFields() {
            Map<String, Object> payload = Map.of("currentIteration", 5);
            LoopEvent event = new LoopEvent("run-1", "core:while", LoopEventType.ITERATION_COMPLETED, payload, 100L);

            assertEquals("run-1", event.runId());
            assertEquals("core:while", event.loopId());
            assertEquals(LoopEventType.ITERATION_COMPLETED, event.type());
            assertEquals(payload, event.payload());
            assertEquals(100L, event.timestamp());
        }

        @Test
        @DisplayName("Should allow null payload")
        void shouldAllowNullPayload() {
            LoopEvent event = new LoopEvent("run-1", "core:while", LoopEventType.STARTED, null, 100L);
            assertNull(event.payload());
        }
    }

    @Nested
    @DisplayName("Validation")
    class ValidationTests {

        @Test
        @DisplayName("Should throw when runId is null")
        void shouldThrowWhenRunIdIsNull() {
            assertThrows(IllegalArgumentException.class,
                () -> new LoopEvent(null, "core:while", LoopEventType.STARTED, null, 100L));
        }

        @Test
        @DisplayName("Should throw when loopId is null")
        void shouldThrowWhenLoopIdIsNull() {
            assertThrows(IllegalArgumentException.class,
                () -> new LoopEvent("run-1", null, LoopEventType.STARTED, null, 100L));
        }

        @Test
        @DisplayName("Should throw when type is null")
        void shouldThrowWhenTypeIsNull() {
            assertThrows(IllegalArgumentException.class,
                () -> new LoopEvent("run-1", "core:while", null, null, 100L));
        }
    }
}
