package com.apimarketplace.agent.integration;

import com.apimarketplace.agent.provider.LLMProviderException;
import com.apimarketplace.agent.ratelimit.RateLimitMode;
import com.apimarketplace.agent.ratelimit.RateLimitResult;
import com.apimarketplace.agent.ratelimit.RateLimitStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the LLM provider exception hierarchy
 * and rate limiting domain models.
 *
 * Tests exception factory methods, error codes, retryability,
 * rate limit result semantics, and enum behaviors.
 *
 * No Spring context needed - tests pure domain classes.
 */
@DisplayName("LLMProviderIntegrationTest - Provider exception and rate limit domain models")
class LLMProviderIntegrationTest {

    // -------------------------------------------------------------------------
    // LLMProviderException
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("LLMProviderException")
    class LLMProviderExceptionTests {

        @Test
        @DisplayName("should create basic exception with provider name")
        void shouldCreateBasicException() {
            LLMProviderException ex = new LLMProviderException("openai", "API error");

            assertThat(ex.getProviderName()).isEqualTo("openai");
            assertThat(ex.getMessage()).isEqualTo("API error");
            assertThat(ex.getErrorCode()).isNull();
            assertThat(ex.isRetryable()).isFalse();
        }

        @Test
        @DisplayName("should create exception with cause")
        void shouldCreateWithCause() {
            RuntimeException cause = new RuntimeException("Network timeout");
            LLMProviderException ex = new LLMProviderException("anthropic", "Connection failed", cause);

            assertThat(ex.getProviderName()).isEqualTo("anthropic");
            assertThat(ex.getCause()).isEqualTo(cause);
            assertThat(ex.isRetryable()).isFalse();
        }

        @Test
        @DisplayName("should create exception with error code and retryability")
        void shouldCreateWithErrorCodeAndRetryability() {
            LLMProviderException ex = new LLMProviderException(
                    "google", "Too many requests", "rate_limit", true
            );

            assertThat(ex.getProviderName()).isEqualTo("google");
            assertThat(ex.getErrorCode()).isEqualTo("rate_limit");
            assertThat(ex.isRetryable()).isTrue();
        }

        @Test
        @DisplayName("should create exception with all parameters including cause")
        void shouldCreateWithAllParams() {
            Exception cause = new Exception("underlying");
            LLMProviderException ex = new LLMProviderException(
                    "mistral", "Service unavailable", "service_unavailable", true, cause
            );

            assertThat(ex.getProviderName()).isEqualTo("mistral");
            assertThat(ex.getErrorCode()).isEqualTo("service_unavailable");
            assertThat(ex.isRetryable()).isTrue();
            assertThat(ex.getCause()).isEqualTo(cause);
        }

        @Test
        @DisplayName("should create rate limited exception via factory method")
        void shouldCreateRateLimitedException() {
            LLMProviderException ex = LLMProviderException.rateLimited("openai");

            assertThat(ex.getProviderName()).isEqualTo("openai");
            assertThat(ex.getMessage()).isEqualTo("Rate limit exceeded");
            assertThat(ex.getErrorCode()).isEqualTo("rate_limit");
            assertThat(ex.isRetryable()).isTrue();
        }

        @Test
        @DisplayName("should create unauthorized exception via factory method")
        void shouldCreateUnauthorizedException() {
            LLMProviderException ex = LLMProviderException.unauthorized("anthropic");

            assertThat(ex.getProviderName()).isEqualTo("anthropic");
            assertThat(ex.getMessage()).isEqualTo("Invalid API key");
            assertThat(ex.getErrorCode()).isEqualTo("unauthorized");
            assertThat(ex.isRetryable()).isFalse();
        }

        @Test
        @DisplayName("should create model not found exception via factory method")
        void shouldCreateModelNotFoundException() {
            LLMProviderException ex = LLMProviderException.modelNotFound("openai", "gpt-5-turbo");

            assertThat(ex.getProviderName()).isEqualTo("openai");
            assertThat(ex.getMessage()).contains("gpt-5-turbo");
            assertThat(ex.getErrorCode()).isEqualTo("model_not_found");
            assertThat(ex.isRetryable()).isFalse();
        }

        @Test
        @DisplayName("should create streaming not supported exception via factory method")
        void shouldCreateStreamingNotSupportedException() {
            LLMProviderException ex = LLMProviderException.streamingNotSupported("local-llm");

            assertThat(ex.getProviderName()).isEqualTo("local-llm");
            assertThat(ex.getMessage()).isEqualTo("Streaming not supported");
            assertThat(ex.getErrorCode()).isEqualTo("streaming_not_supported");
            assertThat(ex.isRetryable()).isFalse();
        }

        @Test
        @DisplayName("should be throwable and catchable")
        void shouldBeThrowableAndCatchable() {
            assertThatThrownBy(() -> {
                throw LLMProviderException.rateLimited("openai");
            }).isInstanceOf(LLMProviderException.class)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Rate limit exceeded");
        }
    }

    // -------------------------------------------------------------------------
    // RateLimitResult
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("RateLimitResult")
    class RateLimitResultTests {

        @Test
        @DisplayName("should create allowed result")
        void shouldCreateAllowedResult() {
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
        @DisplayName("should create blocked result")
        void shouldCreateBlockedResult() {
            RateLimitResult result = RateLimitResult.blocked(
                    Duration.ofSeconds(30),
                    "Token limit exceeded: 1000/1000 TPM",
                    "rate_limit_global_tpm",
                    100.0
            );

            assertThat(result.isBlocked()).isTrue();
            assertThat(result.isAllowed()).isFalse();
            assertThat(result.waitTime()).isEqualTo(Duration.ofSeconds(30));
            assertThat(result.reason()).contains("Token limit exceeded");
            assertThat(result.errorCode()).isEqualTo("rate_limit_global_tpm");
            assertThat(result.usagePercent()).isEqualTo(100.0);
            assertThat(result.remainingCapacity()).isEqualTo(0);
        }

        @Test
        @DisplayName("should detect warning level (70-89%)")
        void shouldDetectWarningLevel() {
            RateLimitResult at70 = RateLimitResult.allowed(70.0, 100);
            RateLimitResult at85 = RateLimitResult.allowed(85.0, 50);
            RateLimitResult at69 = RateLimitResult.allowed(69.0, 200);

            assertThat(at70.isWarning()).isTrue();
            assertThat(at85.isWarning()).isTrue();
            assertThat(at69.isWarning()).isFalse();
        }

        @Test
        @DisplayName("should detect critical level (90+%)")
        void shouldDetectCriticalLevel() {
            RateLimitResult at90 = RateLimitResult.allowed(90.0, 10);
            RateLimitResult at99 = RateLimitResult.allowed(99.0, 1);
            RateLimitResult at89 = RateLimitResult.allowed(89.0, 50);

            assertThat(at90.isCritical()).isTrue();
            assertThat(at99.isCritical()).isTrue();
            assertThat(at89.isCritical()).isFalse();
        }

        @Test
        @DisplayName("critical should not be warning (exclusive)")
        void criticalShouldNotBeWarning() {
            RateLimitResult critical = RateLimitResult.allowed(95.0, 5);

            assertThat(critical.isCritical()).isTrue();
            // Warning is 70-89%, so critical at 95% should not be warning
            assertThat(critical.isWarning()).isFalse();
        }
    }

    // -------------------------------------------------------------------------
    // RateLimitMode enum
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("RateLimitMode")
    class RateLimitModeTests {

        @Test
        @DisplayName("should have all expected modes")
        void shouldHaveAllModes() {
            assertThat(RateLimitMode.values()).containsExactlyInAnyOrder(
                    RateLimitMode.FAIL_FAST,
                    RateLimitMode.WAIT,
                    RateLimitMode.TRY_ACQUIRE,
                    RateLimitMode.QUEUE
            );
        }

        @Test
        @DisplayName("should be usable in switch expressions")
        void shouldWorkInSwitch() {
            for (RateLimitMode mode : RateLimitMode.values()) {
                String description = switch (mode) {
                    case FAIL_FAST -> "Throw immediately";
                    case WAIT -> "Block until allowed";
                    case TRY_ACQUIRE -> "Return result";
                    case QUEUE -> "Queue for later";
                };
                assertThat(description).isNotBlank();
            }
        }
    }

    // -------------------------------------------------------------------------
    // RateLimitStrategy enum
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("RateLimitStrategy")
    class RateLimitStrategyTests {

        @Test
        @DisplayName("should have all expected strategies")
        void shouldHaveAllStrategies() {
            assertThat(RateLimitStrategy.values()).containsExactlyInAnyOrder(
                    RateLimitStrategy.GLOBAL,
                    RateLimitStrategy.PER_TENANT,
                    RateLimitStrategy.HYBRID
            );
        }
    }
}
