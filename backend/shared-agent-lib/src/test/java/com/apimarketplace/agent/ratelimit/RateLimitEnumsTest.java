package com.apimarketplace.agent.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for RateLimitMode and RateLimitStrategy enums.
 */
@DisplayName("Rate limit enums")
class RateLimitEnumsTest {

    @Nested
    @DisplayName("RateLimitMode")
    class RateLimitModeTests {

        @Test
        @DisplayName("should have 4 modes")
        void shouldHaveFourModes() {
            assertThat(RateLimitMode.values()).hasSize(4);
        }

        @Test
        @DisplayName("should contain expected modes")
        void shouldContainExpectedModes() {
            assertThat(RateLimitMode.values()).containsExactly(
                    RateLimitMode.FAIL_FAST,
                    RateLimitMode.WAIT,
                    RateLimitMode.TRY_ACQUIRE,
                    RateLimitMode.QUEUE
            );
        }
    }

    @Nested
    @DisplayName("RateLimitStrategy")
    class RateLimitStrategyTests {

        @Test
        @DisplayName("should have 3 strategies")
        void shouldHaveThreeStrategies() {
            assertThat(RateLimitStrategy.values()).hasSize(3);
        }

        @Test
        @DisplayName("should contain expected strategies")
        void shouldContainExpectedStrategies() {
            assertThat(RateLimitStrategy.values()).containsExactly(
                    RateLimitStrategy.GLOBAL,
                    RateLimitStrategy.PER_TENANT,
                    RateLimitStrategy.HYBRID
            );
        }
    }
}
