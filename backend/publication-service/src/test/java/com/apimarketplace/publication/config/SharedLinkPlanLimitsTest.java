package com.apimarketplace.publication.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SharedLinkPlanLimits")
class SharedLinkPlanLimitsTest {

    @AfterEach
    void resetEnabled() {
        // Always restore default state to prevent test pollution
        SharedLinkPlanLimits.setEnabled(true);
    }

    // ==================== Cloud Mode (limits enabled - default) ====================

    @Nested
    @DisplayName("Cloud Mode (enabled=true, default)")
    class CloudModeTests {

        @ParameterizedTest
        @CsvSource({
                "FREE, 5",
                "STARTER, 20",
                "PRO, 50",
                "TEAM, 100",
                "PAYG, 100",
                "ENTERPRISE_BASIC, 200",
                "ENTERPRISE_STANDARD, 200",
                "ENTERPRISE_PREMIUM, 200",
                "ENTERPRISE_ULTIMATE, 200"
        })
        @DisplayName("returns correct limit for each plan tier")
        void returnsCorrectLimitForPlan(String plan, int expected) {
            assertThat(SharedLinkPlanLimits.getMaxSharedLinks(plan)).isEqualTo(expected);
        }

        @Test
        @DisplayName("normalizes ENTERPRISE shorthand to ENTERPRISE_BASIC")
        void normalizesEnterprise() {
            assertThat(SharedLinkPlanLimits.getMaxSharedLinks("ENTERPRISE")).isEqualTo(200);
        }

        @ParameterizedTest
        @ValueSource(strings = {"free", "Free", "pro", "Pro", "enterprise", "Enterprise"})
        @DisplayName("handles case-insensitive plan codes")
        void caseInsensitive(String plan) {
            assertThat(SharedLinkPlanLimits.getMaxSharedLinks(plan)).isGreaterThan(0);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  ", "\t"})
        @DisplayName("returns default for null, empty, or blank plan")
        void returnsDefaultForNullOrBlank(String plan) {
            assertThat(SharedLinkPlanLimits.getMaxSharedLinks(plan)).isEqualTo(10);
        }

        @Test
        @DisplayName("returns default for unknown plan code")
        void returnsDefaultForUnknown() {
            assertThat(SharedLinkPlanLimits.getMaxSharedLinks("UNKNOWN_PLAN")).isEqualTo(10);
        }

        @Test
        @DisplayName("trims whitespace from plan code")
        void trimsWhitespace() {
            assertThat(SharedLinkPlanLimits.getMaxSharedLinks("  PRO  ")).isEqualTo(50);
        }

        @Test
        @DisplayName("isEnabled returns true by default")
        void enabledByDefault() {
            assertThat(SharedLinkPlanLimits.isEnabled()).isTrue();
        }
    }

    // ==================== CE Mode (limits disabled) ====================

    @Nested
    @DisplayName("CE Mode (enabled=false)")
    class CeModeTests {

        @Test
        @DisplayName("returns 9999 for any plan when disabled")
        void returnsMaxValueForAnyPlan() {
            SharedLinkPlanLimits.setEnabled(false);

            assertThat(SharedLinkPlanLimits.getMaxSharedLinks("FREE")).isEqualTo(9999);
            assertThat(SharedLinkPlanLimits.getMaxSharedLinks("STARTER")).isEqualTo(9999);
            assertThat(SharedLinkPlanLimits.getMaxSharedLinks("PRO")).isEqualTo(9999);
            assertThat(SharedLinkPlanLimits.getMaxSharedLinks("TEAM")).isEqualTo(9999);
            assertThat(SharedLinkPlanLimits.getMaxSharedLinks("ENTERPRISE")).isEqualTo(9999);
            assertThat(SharedLinkPlanLimits.getMaxSharedLinks("ENTERPRISE_BASIC")).isEqualTo(9999);
        }

        @Test
        @DisplayName("returns 9999 for null/blank plan when disabled")
        void returnsMaxValueForNullOrBlank() {
            SharedLinkPlanLimits.setEnabled(false);

            assertThat(SharedLinkPlanLimits.getMaxSharedLinks(null)).isEqualTo(9999);
            assertThat(SharedLinkPlanLimits.getMaxSharedLinks("")).isEqualTo(9999);
            assertThat(SharedLinkPlanLimits.getMaxSharedLinks("  ")).isEqualTo(9999);
        }

        @Test
        @DisplayName("returns 9999 for unknown plan when disabled")
        void returnsMaxValueForUnknown() {
            SharedLinkPlanLimits.setEnabled(false);

            assertThat(SharedLinkPlanLimits.getMaxSharedLinks("UNKNOWN")).isEqualTo(9999);
        }

        @Test
        @DisplayName("isEnabled returns false after setEnabled(false)")
        void isEnabledReturnsFalse() {
            SharedLinkPlanLimits.setEnabled(false);
            assertThat(SharedLinkPlanLimits.isEnabled()).isFalse();
        }
    }

    // ==================== Cross-mode verification ====================

    @Nested
    @DisplayName("Cross-Mode Verification")
    class CrossModeTests {

        @Test
        @DisplayName("same plan returns different limits based on enabled flag")
        void samePlanDifferentModes() {
            // Cloud mode
            SharedLinkPlanLimits.setEnabled(true);
            assertThat(SharedLinkPlanLimits.getMaxSharedLinks("FREE")).isEqualTo(5);
            assertThat(SharedLinkPlanLimits.getMaxSharedLinks("PRO")).isEqualTo(50);

            // CE mode
            SharedLinkPlanLimits.setEnabled(false);
            assertThat(SharedLinkPlanLimits.getMaxSharedLinks("FREE")).isEqualTo(9999);
            assertThat(SharedLinkPlanLimits.getMaxSharedLinks("PRO")).isEqualTo(9999);
        }

        @Test
        @DisplayName("re-enabling restores original limits")
        void reEnablingRestoresLimits() {
            SharedLinkPlanLimits.setEnabled(false);
            assertThat(SharedLinkPlanLimits.getMaxSharedLinks("FREE")).isEqualTo(9999);

            SharedLinkPlanLimits.setEnabled(true);
            assertThat(SharedLinkPlanLimits.getMaxSharedLinks("FREE")).isEqualTo(5);
        }
    }
}
