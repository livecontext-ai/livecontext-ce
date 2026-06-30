package com.apimarketplace.orchestrator.services.streaming.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WorkflowStatusEvent")
class WorkflowStatusEventTest {

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("Should create event with all fields")
        void shouldCreateEventWithAllFields() {
            Map<String, Object> payload = Map.of("progress", 50);
            WorkflowStatusEvent event = new WorkflowStatusEvent(
                "run-1", "RUNNING", "In progress", payload, 12345L, false
            );

            assertEquals("run-1", event.runId());
            assertEquals("RUNNING", event.status());
            assertEquals("In progress", event.message());
            assertEquals(payload, event.payload());
            assertEquals(12345L, event.timestamp());
            assertFalse(event.terminal());
        }

        @Test
        @DisplayName("Should create terminal event")
        void shouldCreateTerminalEvent() {
            WorkflowStatusEvent event = new WorkflowStatusEvent(
                "run-1", "COMPLETED", "Done", null, 100L, true
            );
            assertTrue(event.terminal());
        }

        @Test
        @DisplayName("Should allow null message and payload")
        void shouldAllowNullMessageAndPayload() {
            WorkflowStatusEvent event = new WorkflowStatusEvent(
                "run-1", "RUNNING", null, null, 100L, false
            );
            assertNull(event.message());
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
                () -> new WorkflowStatusEvent(null, "RUNNING", "msg", null, 100L, false));
        }

        @Test
        @DisplayName("Should throw when status is null")
        void shouldThrowWhenStatusIsNull() {
            assertThrows(IllegalArgumentException.class,
                () -> new WorkflowStatusEvent("run-1", null, "msg", null, 100L, false));
        }
    }
}
