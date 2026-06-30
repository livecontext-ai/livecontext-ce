package com.apimarketplace.agent.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for RateLimitConfig - configuration for rate limiting per LLM provider.
 */
@DisplayName("RateLimitConfig")
class RateLimitConfigTest {

    private RateLimitConfig config;

    @BeforeEach
    void setUp() {
        config = new RateLimitConfig();
    }

    @Nested
    @DisplayName("Default values")
    class DefaultValueTests {

        @Test
        @DisplayName("should be enabled by default")
        void shouldBeEnabledByDefault() {
            assertThat(config.isEnabled()).isTrue();
        }

        @Test
        @DisplayName("should use GLOBAL strategy by default")
        void shouldUseGlobalStrategy() {
            assertThat(config.getStrategy()).isEqualTo(RateLimitStrategy.GLOBAL);
        }

        @Test
        @DisplayName("should use WAIT mode by default")
        void shouldUseWaitMode() {
            assertThat(config.getDefaultMode()).isEqualTo(RateLimitMode.WAIT);
        }

        @Test
        @DisplayName("should have 60 second max wait time by default")
        void shouldHaveDefaultWaitTime() {
            assertThat(config.getMaxWaitTimeSeconds()).isEqualTo(60);
        }

        @Test
        @DisplayName("should have empty providers map by default")
        void shouldHaveEmptyProviders() {
            assertThat(config.getProviders()).isEmpty();
        }

        @Test
        @DisplayName("should have default limits")
        void shouldHaveDefaultLimits() {
            RateLimitConfig.ProviderLimit defaults = config.getDefaults();
            assertThat(defaults.getTokensPerMinute()).isEqualTo(10_000_000);
            assertThat(defaults.getRequestsPerMinute()).isEqualTo(10_000);
            assertThat(defaults.getTokensPerMinutePerTenant()).isEqualTo(1_000_000);
            assertThat(defaults.getRequestsPerMinutePerTenant()).isEqualTo(1_000);
        }
    }

    @Nested
    @DisplayName("Setters")
    class SetterTests {

        @Test
        @DisplayName("should set enabled flag")
        void shouldSetEnabled() {
            config.setEnabled(false);
            assertThat(config.isEnabled()).isFalse();
        }

        @Test
        @DisplayName("should set strategy")
        void shouldSetStrategy() {
            config.setStrategy(RateLimitStrategy.PER_TENANT);
            assertThat(config.getStrategy()).isEqualTo(RateLimitStrategy.PER_TENANT);
        }

        @Test
        @DisplayName("should set default mode")
        void shouldSetDefaultMode() {
            config.setDefaultMode(RateLimitMode.FAIL_FAST);
            assertThat(config.getDefaultMode()).isEqualTo(RateLimitMode.FAIL_FAST);
        }

        @Test
        @DisplayName("should set max wait time")
        void shouldSetMaxWaitTime() {
            config.setMaxWaitTimeSeconds(120);
            assertThat(config.getMaxWaitTimeSeconds()).isEqualTo(120);
        }

        @Test
        @DisplayName("should set providers map")
        void shouldSetProviders() {
            RateLimitConfig.ProviderLimit openaiLimit = new RateLimitConfig.ProviderLimit(90000, 3500);
            config.setProviders(Map.of("openai", openaiLimit));

            assertThat(config.getProviders()).hasSize(1);
            assertThat(config.getProviders()).containsKey("openai");
        }

        @Test
        @DisplayName("should set defaults")
        void shouldSetDefaults() {
            RateLimitConfig.ProviderLimit newDefaults = new RateLimitConfig.ProviderLimit(5000, 500);
            config.setDefaults(newDefaults);

            assertThat(config.getDefaults().getTokensPerMinute()).isEqualTo(5000);
            assertThat(config.getDefaults().getRequestsPerMinute()).isEqualTo(500);
        }
    }

    @Nested
    @DisplayName("getProviderLimit()")
    class GetProviderLimitTests {

        @Test
        @DisplayName("should return configured limit for known provider")
        void shouldReturnConfiguredLimit() {
            RateLimitConfig.ProviderLimit openaiLimit = new RateLimitConfig.ProviderLimit(90000, 3500);
            config.setProviders(Map.of("openai", openaiLimit));

            RateLimitConfig.ProviderLimit result = config.getProviderLimit("openai");
            assertThat(result.getTokensPerMinute()).isEqualTo(90000);
            assertThat(result.getRequestsPerMinute()).isEqualTo(3500);
        }

        @Test
        @DisplayName("should return default limit for unknown provider")
        void shouldReturnDefaultForUnknown() {
            RateLimitConfig.ProviderLimit result = config.getProviderLimit("unknown");
            assertThat(result).isEqualTo(config.getDefaults());
        }

        @Test
        @DisplayName("should be case-insensitive")
        void shouldBeCaseInsensitive() {
            RateLimitConfig.ProviderLimit openaiLimit = new RateLimitConfig.ProviderLimit(90000, 3500);
            config.setProviders(Map.of("openai", openaiLimit));

            // getProviderLimit lowercases the input, so "OpenAI" -> "openai" finds the configured limit
            RateLimitConfig.ProviderLimit result = config.getProviderLimit("OpenAI");
            assertThat(result.getTokensPerMinute()).isEqualTo(90000);
            assertThat(result.getRequestsPerMinute()).isEqualTo(3500);
        }
    }

    @Nested
    @DisplayName("ProviderLimit")
    class ProviderLimitTests {

        @Test
        @DisplayName("no-arg constructor should set all limits to -1")
        void noArgConstructorShouldSetMinusOne() {
            RateLimitConfig.ProviderLimit limit = new RateLimitConfig.ProviderLimit();

            assertThat(limit.getTokensPerMinute()).isEqualTo(-1);
            assertThat(limit.getRequestsPerMinute()).isEqualTo(-1);
            assertThat(limit.getTokensPerMinutePerTenant()).isEqualTo(-1);
            assertThat(limit.getRequestsPerMinutePerTenant()).isEqualTo(-1);
        }

        @Test
        @DisplayName("two-arg constructor should set global limits and disable tenant limits")
        void twoArgConstructorShouldSetGlobalOnly() {
            RateLimitConfig.ProviderLimit limit = new RateLimitConfig.ProviderLimit(90000, 3500);

            assertThat(limit.getTokensPerMinute()).isEqualTo(90000);
            assertThat(limit.getRequestsPerMinute()).isEqualTo(3500);
            assertThat(limit.getTokensPerMinutePerTenant()).isEqualTo(-1);
            assertThat(limit.getRequestsPerMinutePerTenant()).isEqualTo(-1);
        }

        @Test
        @DisplayName("four-arg constructor should set all limits")
        void fourArgConstructorShouldSetAll() {
            RateLimitConfig.ProviderLimit limit = new RateLimitConfig.ProviderLimit(90000, 3500, 10000, 100);

            assertThat(limit.getTokensPerMinute()).isEqualTo(90000);
            assertThat(limit.getRequestsPerMinute()).isEqualTo(3500);
            assertThat(limit.getTokensPerMinutePerTenant()).isEqualTo(10000);
            assertThat(limit.getRequestsPerMinutePerTenant()).isEqualTo(100);
        }

        @Test
        @DisplayName("hasGlobalTokenLimit should return true when positive")
        void shouldDetectGlobalTokenLimit() {
            RateLimitConfig.ProviderLimit limit = new RateLimitConfig.ProviderLimit(90000, -1);
            assertThat(limit.hasGlobalTokenLimit()).isTrue();
            assertThat(limit.hasTokenLimit()).isTrue(); // Legacy alias
        }

        @Test
        @DisplayName("hasGlobalTokenLimit should return false when disabled (-1)")
        void shouldDetectNoGlobalTokenLimit() {
            RateLimitConfig.ProviderLimit limit = new RateLimitConfig.ProviderLimit();
            assertThat(limit.hasGlobalTokenLimit()).isFalse();
        }

        @Test
        @DisplayName("hasGlobalRequestLimit should return true when positive")
        void shouldDetectGlobalRequestLimit() {
            RateLimitConfig.ProviderLimit limit = new RateLimitConfig.ProviderLimit(-1, 3500);
            assertThat(limit.hasGlobalRequestLimit()).isTrue();
            assertThat(limit.hasRequestLimit()).isTrue(); // Legacy alias
        }

        @Test
        @DisplayName("hasGlobalRequestLimit should return false when disabled")
        void shouldDetectNoGlobalRequestLimit() {
            RateLimitConfig.ProviderLimit limit = new RateLimitConfig.ProviderLimit();
            assertThat(limit.hasGlobalRequestLimit()).isFalse();
        }

        @Test
        @DisplayName("hasTenantTokenLimit should return true when positive")
        void shouldDetectTenantTokenLimit() {
            RateLimitConfig.ProviderLimit limit = new RateLimitConfig.ProviderLimit(-1, -1, 10000, -1);
            assertThat(limit.hasTenantTokenLimit()).isTrue();
        }

        @Test
        @DisplayName("hasTenantRequestLimit should return true when positive")
        void shouldDetectTenantRequestLimit() {
            RateLimitConfig.ProviderLimit limit = new RateLimitConfig.ProviderLimit(-1, -1, -1, 100);
            assertThat(limit.hasTenantRequestLimit()).isTrue();
        }

        @Test
        @DisplayName("setters should update values")
        void shouldUpdateViaSetters() {
            RateLimitConfig.ProviderLimit limit = new RateLimitConfig.ProviderLimit();

            limit.setTokensPerMinute(50000);
            limit.setRequestsPerMinute(2000);
            limit.setTokensPerMinutePerTenant(5000);
            limit.setRequestsPerMinutePerTenant(200);

            assertThat(limit.getTokensPerMinute()).isEqualTo(50000);
            assertThat(limit.getRequestsPerMinute()).isEqualTo(2000);
            assertThat(limit.getTokensPerMinutePerTenant()).isEqualTo(5000);
            assertThat(limit.getRequestsPerMinutePerTenant()).isEqualTo(200);
        }
    }
}
