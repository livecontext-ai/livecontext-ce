package com.apimarketplace.orchestrator.services.streaming.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AgentToolCallEvent")
class AgentToolCallEventTest {

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("Should create event with all fields")
        void shouldCreateEventWithAllFields() {
            Map<String, Object> payload = Map.of("arg1", "value1");
            AgentToolCallEvent event = new AgentToolCallEvent(
                "run-1", "agent:myAgent", "get_weather", "tc-001",
                AgentToolCallPhase.CALLING, payload, 0, 2, 12345L
            );

            assertEquals("run-1", event.runId());
            assertEquals("agent:myAgent", event.nodeId());
            assertEquals("get_weather", event.toolName());
            assertEquals("tc-001", event.toolCallId());
            assertEquals(AgentToolCallPhase.CALLING, event.phase());
            assertEquals(payload, event.payload());
            assertEquals(0, event.itemIndex());
            assertEquals(2, event.iteration());
            assertEquals(12345L, event.timestamp());
        }

        @Test
        @DisplayName("Should allow null optional fields")
        void shouldAllowNullOptionalFields() {
            AgentToolCallEvent event = new AgentToolCallEvent(
                "run-1", "agent:a", "tool", null,
                AgentToolCallPhase.COMPLETED, null, 0, null, 100L
            );
            assertNull(event.toolCallId());
            assertNull(event.payload());
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
                () -> new AgentToolCallEvent(null, "node", "tool", null,
                    AgentToolCallPhase.CALLING, null, 0, null, 100L));
        }

        @Test
        @DisplayName("Should throw when nodeId is null")
        void shouldThrowWhenNodeIdIsNull() {
            assertThrows(IllegalArgumentException.class,
                () -> new AgentToolCallEvent("run-1", null, "tool", null,
                    AgentToolCallPhase.CALLING, null, 0, null, 100L));
        }

        @Test
        @DisplayName("Should throw when toolName is null")
        void shouldThrowWhenToolNameIsNull() {
            assertThrows(IllegalArgumentException.class,
                () -> new AgentToolCallEvent("run-1", "node", null, null,
                    AgentToolCallPhase.CALLING, null, 0, null, 100L));
        }

        @Test
        @DisplayName("Should throw when phase is null")
        void shouldThrowWhenPhaseIsNull() {
            assertThrows(IllegalArgumentException.class,
                () -> new AgentToolCallEvent("run-1", "node", "tool", null,
                    null, null, 0, null, 100L));
        }
    }
}
