package com.apimarketplace.orchestrator.services.streaming.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("StepLifecycle")
class StepLifecycleTest {

    @Test
    @DisplayName("Should have all expected values")
    void shouldHaveAllExpectedValues() {
        StepLifecycle[] values = StepLifecycle.values();
        assertEquals(8, values.length);

        assertNotNull(StepLifecycle.PENDING);
        assertNotNull(StepLifecycle.RUNNING);
        assertNotNull(StepLifecycle.SUCCESS);
        assertNotNull(StepLifecycle.FAILURE);
        assertNotNull(StepLifecycle.SKIPPED);
        assertNotNull(StepLifecycle.RETRYING);
        assertNotNull(StepLifecycle.CANCELLED);
        assertNotNull(StepLifecycle.AWAITING_SIGNAL);
    }

    @ParameterizedTest
    @EnumSource(StepLifecycle.class)
    @DisplayName("Should round-trip via valueOf")
    void shouldRoundTripViaValueOf(StepLifecycle lifecycle) {
        assertEquals(lifecycle, StepLifecycle.valueOf(lifecycle.name()));
    }
}
