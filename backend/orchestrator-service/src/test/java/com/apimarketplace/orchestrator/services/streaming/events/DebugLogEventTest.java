package com.apimarketplace.orchestrator.services.streaming.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DebugLogEvent")
class DebugLogEventTest {

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("Should create event with all fields")
        void shouldCreateEventWithAllFields() {
            DebugLogEvent event = new DebugLogEvent("run-1", "INFO", "Step completed", 100L);

            assertEquals("run-1", event.runId());
            assertEquals("INFO", event.level());
            assertEquals("Step completed", event.message());
            assertEquals(100L, event.timestamp());
        }

        @Test
        @DisplayName("Should allow null level")
        void shouldAllowNullLevel() {
            DebugLogEvent event = new DebugLogEvent("run-1", null, "msg", 100L);
            assertNull(event.level());
        }
    }

    @Nested
    @DisplayName("Validation")
    class ValidationTests {

        @Test
        @DisplayName("Should throw when runId is null")
        void shouldThrowWhenRunIdIsNull() {
            assertThrows(IllegalArgumentException.class,
                () -> new DebugLogEvent(null, "INFO", "msg", 100L));
        }

        @Test
        @DisplayName("Should throw when message is null")
        void shouldThrowWhenMessageIsNull() {
            assertThrows(IllegalArgumentException.class,
                () -> new DebugLogEvent("run-1", "INFO", null, 100L));
        }
    }
}
