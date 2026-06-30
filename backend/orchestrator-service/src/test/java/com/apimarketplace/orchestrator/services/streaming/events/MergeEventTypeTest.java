package com.apimarketplace.orchestrator.services.streaming.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MergeEventType")
class MergeEventTypeTest {

    @Test
    @DisplayName("Should have expected values")
    void shouldHaveExpectedValues() {
        assertNotNull(MergeEventType.ENQUEUED);
        assertNotNull(MergeEventType.MERGED);
    }

    @ParameterizedTest
    @EnumSource(MergeEventType.class)
    @DisplayName("Should round-trip via valueOf")
    void shouldRoundTripViaValueOf(MergeEventType type) {
        assertEquals(type, MergeEventType.valueOf(type.name()));
    }
}
