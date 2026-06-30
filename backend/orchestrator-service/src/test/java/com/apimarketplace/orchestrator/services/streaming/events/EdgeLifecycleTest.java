package com.apimarketplace.orchestrator.services.streaming.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("EdgeLifecycle")
class EdgeLifecycleTest {

    @Test
    @DisplayName("Should have all expected values")
    void shouldHaveAllExpectedValues() {
        EdgeLifecycle[] values = EdgeLifecycle.values();
        assertEquals(4, values.length);

        assertNotNull(EdgeLifecycle.REGISTERED);
        assertNotNull(EdgeLifecycle.RUNNING);
        assertNotNull(EdgeLifecycle.COMPLETED);
        assertNotNull(EdgeLifecycle.SKIPPED);
    }

    @ParameterizedTest
    @EnumSource(EdgeLifecycle.class)
    @DisplayName("Should round-trip via valueOf")
    void shouldRoundTripViaValueOf(EdgeLifecycle lifecycle) {
        assertEquals(lifecycle, EdgeLifecycle.valueOf(lifecycle.name()));
    }
}
