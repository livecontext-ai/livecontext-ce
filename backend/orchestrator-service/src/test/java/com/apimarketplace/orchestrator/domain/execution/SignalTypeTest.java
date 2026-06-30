package com.apimarketplace.orchestrator.domain.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SignalType")
class SignalTypeTest {

    @Nested
    @DisplayName("Enum values")
    class EnumValueTests {

        @Test
        @DisplayName("Should have exactly 6 values (incl. BROWSER_USER_TAKEOVER)")
        void shouldHave6Values() {
            assertEquals(6, SignalType.values().length);
        }

        @Test
        @DisplayName("Should contain WAIT_TIMER")
        void shouldContainWaitTimer() {
            assertNotNull(SignalType.valueOf("WAIT_TIMER"));
        }

        @Test
        @DisplayName("Should contain USER_APPROVAL")
        void shouldContainUserApproval() {
            assertNotNull(SignalType.valueOf("USER_APPROVAL"));
        }

        @Test
        @DisplayName("Should contain WEBHOOK_WAIT")
        void shouldContainWebhookWait() {
            assertNotNull(SignalType.valueOf("WEBHOOK_WAIT"));
        }
    }

    @Nested
    @DisplayName("valueOf()")
    class ValueOfTests {

        @ParameterizedTest
        @DisplayName("All values should round-trip through name()")
        @EnumSource(SignalType.class)
        void allValuesShouldRoundTrip(SignalType type) {
            assertEquals(type, SignalType.valueOf(type.name()));
        }

        @Test
        @DisplayName("Should throw for invalid value")
        void shouldThrowForInvalid() {
            assertThrows(IllegalArgumentException.class, () -> SignalType.valueOf("INVALID"));
        }
    }
}
