package com.apimarketplace.orchestrator.services.streaming.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WorkflowStatisticsEvent")
class WorkflowStatisticsEventTest {

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("Should create event with all fields")
        void shouldCreateEventWithAllFields() {
            Map<String, Object> payload = Map.of("completedSteps", 5, "totalSteps", 10);
            WorkflowStatisticsEvent event = new WorkflowStatisticsEvent("run-1", payload, 100L);

            assertEquals("run-1", event.runId());
            assertEquals(payload, event.payload());
            assertEquals(100L, event.timestamp());
        }

        @Test
        @DisplayName("Should allow null payload")
        void shouldAllowNullPayload() {
            WorkflowStatisticsEvent event = new WorkflowStatisticsEvent("run-1", null, 100L);
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
                () -> new WorkflowStatisticsEvent(null, null, 100L));
        }
    }
}
