package com.apimarketplace.agent.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for RateLimitResult record.
 */
@DisplayName("RateLimitResult")
class RateLimitResultTest {

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethodTests {

        @Test
        @DisplayName("allowed() should create allowed result")
        void allowedShouldCreate() {
            RateLimitResult result = RateLimitResult.allowed(50.0, 500);

            assertThat(result.isAllowed()).isTrue();
            assertThat(result.isBlocked()).isFalse();
            assertThat(result.usagePercent()).isEqualTo(50.0);
            assertThat(result.remainingCapacity()).isEqualTo(500);
            assertThat(result.waitTime()).isEqualTo(Duration.ZERO);
            assertThat(result.reason()).isNull();
            assertThat(result.errorCode()).isNull();
        }

        @Test
        @DisplayName("blocked() should create blocked result")
        void blockedShouldCreate() {
            RateLimitResult result = RateLimitResult.blocked(
                    Duration.ofSeconds(30), "Global limit exceeded", "RATE_LIMIT", 100.0
            );

            assertThat(result.isAllowed()).isFalse();
            assertThat(result.isBlocked()).isTrue();
            assertThat(result.waitTime()).isEqualTo(Duration.ofSeconds(30));
            assertThat(result.reason()).isEqualTo("Global limit exceeded");
            assertThat(result.errorCode()).isEqualTo("RATE_LIMIT");
            assertThat(result.usagePercent()).isEqualTo(100.0);
            assertThat(result.remainingCapacity()).isZero();
        }
    }

    @Nested
    @DisplayName("Warning and critical thresholds")
    class ThresholdTests {

        @ParameterizedTest
        @CsvSource({
                "0, false, false",
                "50, false, false",
                "69.9, false, false",
                "70, true, false",
                "80, true, false",
                "89.9, true, false",
                "90, false, true",
                "95, false, true",
                "100, false, true"
        })
        @DisplayName("should correctly detect warning and critical levels")
        void shouldDetectLevels(double usagePercent, boolean isWarning, boolean isCritical) {
            RateLimitResult result = RateLimitResult.allowed(usagePercent, 100);

            assertThat(result.isWarning()).isEqualTo(isWarning);
            assertThat(result.isCritical()).isEqualTo(isCritical);
        }
    }
}
