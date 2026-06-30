package com.apimarketplace.orchestrator.services.streaming.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("EdgeStatusEvent")
class EdgeStatusEventTest {

    @Nested
    @DisplayName("Construction with all fields")
    class FullConstructionTests {

        @Test
        @DisplayName("Should create event with all fields including iteration")
        void shouldCreateEventWithAllFields() {
            EdgeStatusEvent event = new EdgeStatusEvent(
                "run-1", "edge-1", "mcp:a", "mcp:b",
                EdgeLifecycle.RUNNING, 0, 3, 12345L
            );

            assertEquals("run-1", event.runId());
            assertEquals("edge-1", event.edgeId());
            assertEquals("mcp:a", event.from());
            assertEquals("mcp:b", event.to());
            assertEquals(EdgeLifecycle.RUNNING, event.lifecycle());
            assertEquals(0, event.itemIndex());
            assertEquals(3, event.iteration());
            assertEquals(12345L, event.timestamp());
        }

        @Test
        @DisplayName("Should allow null optional fields")
        void shouldAllowNullOptionalFields() {
            EdgeStatusEvent event = new EdgeStatusEvent(
                "run-1", "edge-1", null, null,
                EdgeLifecycle.COMPLETED, null, null, 100L
            );
            assertNull(event.from());
            assertNull(event.to());
            assertNull(event.itemIndex());
            assertNull(event.iteration());
        }
    }

    @Nested
    @DisplayName("Backward-compatible constructor")
    class BackwardCompatibleConstructorTests {

        @Test
        @DisplayName("Should create event without iteration field")
        void shouldCreateEventWithoutIteration() {
            EdgeStatusEvent event = new EdgeStatusEvent(
                "run-1", "edge-1", "mcp:a", "mcp:b",
                EdgeLifecycle.COMPLETED, 5, 100L
            );

            assertEquals("run-1", event.runId());
            assertEquals("edge-1", event.edgeId());
            assertEquals(5, event.itemIndex());
            assertNull(event.iteration());
        }
    }

    @Nested
    @DisplayName("Validation")
    class ValidationTests {

        @Test
        @DisplayName("Should throw when runId is null")
        void shouldThrowWhenRunIdIsNull() {
            assertThrows(IllegalArgumentException.class,
                () -> new EdgeStatusEvent(null, "edge-1", "a", "b", EdgeLifecycle.RUNNING, 0, 100L));
        }

        @Test
        @DisplayName("Should throw when edgeId is null")
        void shouldThrowWhenEdgeIdIsNull() {
            assertThrows(IllegalArgumentException.class,
                () -> new EdgeStatusEvent("run-1", null, "a", "b", EdgeLifecycle.RUNNING, 0, 100L));
        }
    }
}
