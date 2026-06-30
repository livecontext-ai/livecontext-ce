package com.apimarketplace.orchestrator.services.streaming.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RetryEvent")
class RetryEventTest {

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("Should create event with all fields")
        void shouldCreateEventWithAllFields() {
            Map<String, Object> payload = Map.of("attempt", 2);
            RetryEvent event = new RetryEvent("run-1", "mcp:step1", 42L, 1, "timeout", payload, 100L);

            assertEquals("run-1", event.runId());
            assertEquals("mcp:step1", event.stepId());
            assertEquals(42L, event.itemId());
            assertEquals(1, event.retryIndex());
            assertEquals("timeout", event.cause());
            assertEquals(payload, event.payload());
            assertEquals(100L, event.timestamp());
        }

        @Test
        @DisplayName("Should allow retryIndex of 0")
        void shouldAllowRetryIndexOfZero() {
            RetryEvent event = new RetryEvent("run-1", "mcp:step1", 1L, 0, null, null, 100L);
            assertEquals(0, event.retryIndex());
        }
    }

    @Nested
    @DisplayName("Validation")
    class ValidationTests {

        @Test
        @DisplayName("Should throw when runId is null")
        void shouldThrowWhenRunIdIsNull() {
            assertThrows(IllegalArgumentException.class,
                () -> new RetryEvent(null, "step1", 1L, 0, null, null, 100L));
        }

        @Test
        @DisplayName("Should throw when stepId is null")
        void shouldThrowWhenStepIdIsNull() {
            assertThrows(IllegalArgumentException.class,
                () -> new RetryEvent("run-1", null, 1L, 0, null, null, 100L));
        }

        @Test
        @DisplayName("Should throw when retryIndex is negative")
        void shouldThrowWhenRetryIndexIsNegative() {
            assertThrows(IllegalArgumentException.class,
                () -> new RetryEvent("run-1", "step1", 1L, -1, null, null, 100L));
        }
    }
}
