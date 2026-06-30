package com.apimarketplace.orchestrator.services.streaming.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AgentToolCallPhase")
class AgentToolCallPhaseTest {

    @Test
    @DisplayName("Should have expected values")
    void shouldHaveExpectedValues() {
        assertNotNull(AgentToolCallPhase.CALLING);
        assertNotNull(AgentToolCallPhase.COMPLETED);
    }

    @ParameterizedTest
    @EnumSource(AgentToolCallPhase.class)
    @DisplayName("Should round-trip via valueOf")
    void shouldRoundTripViaValueOf(AgentToolCallPhase phase) {
        assertEquals(phase, AgentToolCallPhase.valueOf(phase.name()));
    }
}
