package com.apimarketplace.orchestrator.services.streaming.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LoopEventType")
class LoopEventTypeTest {

    @Test
    @DisplayName("Should have expected values")
    void shouldHaveExpectedValues() {
        assertNotNull(LoopEventType.STARTED);
        assertNotNull(LoopEventType.ITERATION_COMPLETED);
        assertNotNull(LoopEventType.COMPLETED);
        assertNotNull(LoopEventType.CANCELLED);
    }

    @ParameterizedTest
    @EnumSource(LoopEventType.class)
    @DisplayName("Should round-trip via valueOf")
    void shouldRoundTripViaValueOf(LoopEventType type) {
        assertEquals(type, LoopEventType.valueOf(type.name()));
    }
}
