package com.apimarketplace.orchestrator.services.streaming.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("StepStatusEvent")
class StepStatusEventTest {

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("Should create event with all fields")
        void shouldCreateEventWithAllFields() {
            Map<String, Object> payload = Map.of("key", "value");
            StepStatusEvent event = new StepStatusEvent("run-1", "mcp:step1", payload, StepLifecycle.RUNNING, 12345L);

            assertEquals("run-1", event.runId());
            assertEquals("mcp:step1", event.normalizedStepId());
            assertEquals(payload, event.payload());
            assertEquals(StepLifecycle.RUNNING, event.lifecycle());
            assertEquals(12345L, event.timestamp());
        }

        @Test
        @DisplayName("Should allow null payload")
        void shouldAllowNullPayload() {
            StepStatusEvent event = new StepStatusEvent("run-1", "mcp:step1", null, StepLifecycle.SUCCESS, 100L);
            assertNull(event.payload());
        }

        @Test
        @DisplayName("Should allow null lifecycle")
        void shouldAllowNullLifecycle() {
            StepStatusEvent event = new StepStatusEvent("run-1", "mcp:step1", null, null, 100L);
            assertNull(event.lifecycle());
        }

        @Test
        @DisplayName("Should throw when runId is null")
        void shouldThrowWhenRunIdIsNull() {
            assertThrows(IllegalArgumentException.class,
                () -> new StepStatusEvent(null, "mcp:step1", null, StepLifecycle.RUNNING, 100L));
        }

        @Test
        @DisplayName("Should throw when normalizedStepId is null")
        void shouldThrowWhenNormalizedStepIdIsNull() {
            assertThrows(IllegalArgumentException.class,
                () -> new StepStatusEvent("run-1", null, null, StepLifecycle.RUNNING, 100L));
        }
    }

    @Nested
    @DisplayName("WorkflowEvent interface")
    class WorkflowEventInterfaceTests {

        @Test
        @DisplayName("Should implement WorkflowEvent")
        void shouldImplementWorkflowEvent() {
            StepStatusEvent event = new StepStatusEvent("run-1", "mcp:step1", null, StepLifecycle.RUNNING, 100L);
            assertInstanceOf(WorkflowEvent.class, event);
        }

        @Test
        @DisplayName("Should return runId via interface method")
        void shouldReturnRunIdViaInterface() {
            WorkflowEvent event = new StepStatusEvent("run-1", "mcp:step1", null, StepLifecycle.RUNNING, 42L);
            assertEquals("run-1", event.runId());
            assertEquals(42L, event.timestamp());
        }
    }
}
