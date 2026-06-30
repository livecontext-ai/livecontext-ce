package com.apimarketplace.trigger.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PlanLimitHelper")
class PlanLimitHelperTest {

    // ==================== Cloud Mode (limits enabled - default) ====================

    @Nested
    @DisplayName("Cloud Mode (plan-limits.enabled=true)")
    class CloudModeTests {

        private final PlanLimitHelper helper = new PlanLimitHelper(true);

        @ParameterizedTest
        @CsvSource({
                "FREE, 3",
                "STARTER, 10",
                "PRO, 50",
                "TEAM, 100",
                "PAYG, 100",
                "ENTERPRISE_BASIC, 100",
                "ENTERPRISE_STANDARD, 100",
                "ENTERPRISE_PREMIUM, 100",
                "ENTERPRISE_ULTIMATE, 100"
        })
        @DisplayName("returns correct limit for each plan tier")
        void returnsCorrectLimitForPlan(String plan, int expected) {
            assertThat(helper.getMaxEndpoints(plan)).isEqualTo(expected);
        }

        @Test
        @DisplayName("ENTERPRISE shorthand normalizes to ENTERPRISE_BASIC (100)")
        void enterpriseShorthand() {
            assertThat(helper.getMaxEndpoints("ENTERPRISE")).isEqualTo(100);
        }

        @ParameterizedTest
        @ValueSource(strings = {"free", "Free", "pro", "Pro", "enterprise", "Enterprise"})
        @DisplayName("handles case-insensitive plan codes")
        void caseInsensitive(String plan) {
            assertThat(helper.getMaxEndpoints(plan)).isGreaterThan(0);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  ", "\t"})
        @DisplayName("returns default (10) for null, empty, or blank plan")
        void returnsDefaultForNullOrBlank(String plan) {
            assertThat(helper.getMaxEndpoints(plan)).isEqualTo(10);
        }

        @Test
        @DisplayName("returns default (10) for unknown plan code")
        void returnsDefaultForUnknown() {
            assertThat(helper.getMaxEndpoints("UNKNOWN_PLAN")).isEqualTo(10);
        }

        @Test
        @DisplayName("checkLimit throws when at limit")
        void checkLimitThrowsAtLimit() {
            assertThatThrownBy(() -> helper.checkLimit("FREE", 3))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Resource limit reached: 3/3");
        }

        @Test
        @DisplayName("checkLimit throws when over limit")
        void checkLimitThrowsOverLimit() {
            assertThatThrownBy(() -> helper.checkLimit("FREE", 10))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Resource limit reached: 10/3");
        }

        @Test
        @DisplayName("checkLimit passes when under limit")
        void checkLimitPassesUnderLimit() {
            // Should not throw
            helper.checkLimit("FREE", 2);
            helper.checkLimit("PRO", 49);
            helper.checkLimit("TEAM", 99);
        }

        @ParameterizedTest
        @CsvSource({
                "FREE, 2, false",
                "FREE, 3, true",
                "STARTER, 9, false",
                "STARTER, 10, true",
                "PRO, 49, false",
                "PRO, 50, true",
                "TEAM, 99, false",
                "TEAM, 100, true"
        })
        @DisplayName("boundary: one below vs at limit")
        void boundaryTests(String plan, long count, boolean shouldThrow) {
            if (shouldThrow) {
                assertThatThrownBy(() -> helper.checkLimit(plan, count))
                        .isInstanceOf(IllegalStateException.class);
            } else {
                helper.checkLimit(plan, count); // should not throw
            }
        }
    }

    // ==================== CE Mode (limits disabled) ====================

    @Nested
    @DisplayName("CE Mode (plan-limits.enabled=false)")
    class CeModeTests {

        private final PlanLimitHelper helper = new PlanLimitHelper(false);

        @Test
        @DisplayName("getMaxEndpoints returns 9999 for any plan")
        void returnsMaxValueForAnyPlan() {
            assertThat(helper.getMaxEndpoints("FREE")).isEqualTo(9999);
            assertThat(helper.getMaxEndpoints("STARTER")).isEqualTo(9999);
            assertThat(helper.getMaxEndpoints("PRO")).isEqualTo(9999);
            assertThat(helper.getMaxEndpoints("TEAM")).isEqualTo(9999);
            assertThat(helper.getMaxEndpoints("ENTERPRISE")).isEqualTo(9999);
            assertThat(helper.getMaxEndpoints(null)).isEqualTo(9999);
            assertThat(helper.getMaxEndpoints("")).isEqualTo(9999);
        }

        @Test
        @DisplayName("checkLimit never throws regardless of count")
        void checkLimitNeverThrows() {
            // Even with absurdly high counts, no exception in CE mode
            helper.checkLimit("FREE", 0);
            helper.checkLimit("FREE", 3);
            helper.checkLimit("FREE", 1_000_000);
            helper.checkLimit(null, Long.MAX_VALUE);
            helper.checkLimit("UNKNOWN", 999);
        }

        @ParameterizedTest
        @CsvSource({
                "FREE, 100",
                "STARTER, 1000",
                "PRO, 10000",
                "TEAM, 100000",
                "ENTERPRISE, 1000000"
        })
        @DisplayName("no plan is ever blocked in CE mode")
        void noPlanEverBlocked(String plan, long count) {
            // Should not throw for any plan/count combination
            helper.checkLimit(plan, count);
            assertThat(helper.getMaxEndpoints(plan)).isEqualTo(9999);
        }
    }

    // ==================== Cross-mode verification ====================

    @Nested
    @DisplayName("Cross-Mode Verification")
    class CrossModeTests {

        @Test
        @DisplayName("same plan returns different limits based on enabled flag")
        void samePlanDifferentModes() {
            PlanLimitHelper cloud = new PlanLimitHelper(true);
            PlanLimitHelper ce = new PlanLimitHelper(false);

            assertThat(cloud.getMaxEndpoints("FREE")).isEqualTo(3);
            assertThat(ce.getMaxEndpoints("FREE")).isEqualTo(9999);

            assertThat(cloud.getMaxEndpoints("PRO")).isEqualTo(50);
            assertThat(ce.getMaxEndpoints("PRO")).isEqualTo(9999);
        }

        @Test
        @DisplayName("cloud blocks at limit while CE allows")
        void cloudBlocksCeAllows() {
            PlanLimitHelper cloud = new PlanLimitHelper(true);
            PlanLimitHelper ce = new PlanLimitHelper(false);

            // Cloud: FREE at 3 → blocked
            assertThatThrownBy(() -> cloud.checkLimit("FREE", 3))
                    .isInstanceOf(IllegalStateException.class);

            // CE: FREE at 3 → allowed
            ce.checkLimit("FREE", 3); // no exception
        }
    }
}
