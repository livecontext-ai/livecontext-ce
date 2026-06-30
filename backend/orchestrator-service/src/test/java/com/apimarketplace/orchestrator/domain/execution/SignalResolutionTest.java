package com.apimarketplace.orchestrator.domain.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SignalResolution")
class SignalResolutionTest {

    @Nested
    @DisplayName("Enum values")
    class EnumValueTests {

        @Test
        @DisplayName("Should have exactly 9 values")
        void shouldHave9Values() {
            assertEquals(9, SignalResolution.values().length);
        }

        @Test
        @DisplayName("Should contain COMPLETED")
        void shouldContainCompleted() {
            assertNotNull(SignalResolution.valueOf("COMPLETED"));
        }

        @Test
        @DisplayName("Should contain APPROVED")
        void shouldContainApproved() {
            assertNotNull(SignalResolution.valueOf("APPROVED"));
        }

        @Test
        @DisplayName("Should contain REJECTED")
        void shouldContainRejected() {
            assertNotNull(SignalResolution.valueOf("REJECTED"));
        }

        @Test
        @DisplayName("Should contain TIMEOUT")
        void shouldContainTimeout() {
            assertNotNull(SignalResolution.valueOf("TIMEOUT"));
        }

        @Test
        @DisplayName("Should contain CANCELLED")
        void shouldContainCancelled() {
            assertNotNull(SignalResolution.valueOf("CANCELLED"));
        }
    }

    @Nested
    @DisplayName("valueOf()")
    class ValueOfTests {

        @ParameterizedTest
        @DisplayName("All values should round-trip through name()")
        @EnumSource(SignalResolution.class)
        void allValuesShouldRoundTrip(SignalResolution resolution) {
            assertEquals(resolution, SignalResolution.valueOf(resolution.name()));
        }

        @Test
        @DisplayName("Should throw for invalid value")
        void shouldThrowForInvalid() {
            assertThrows(IllegalArgumentException.class, () -> SignalResolution.valueOf("INVALID"));
        }
    }
}
