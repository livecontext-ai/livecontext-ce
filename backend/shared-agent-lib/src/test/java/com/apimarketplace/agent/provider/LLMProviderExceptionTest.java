package com.apimarketplace.agent.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for LLMProviderException.
 */
@DisplayName("LLMProviderException")
class LLMProviderExceptionTest {

    @Nested
    @DisplayName("Constructors")
    class ConstructorTests {

        @Test
        @DisplayName("should create with provider name and message")
        void shouldCreateWithProviderAndMessage() {
            LLMProviderException ex = new LLMProviderException("openai", "Connection failed");

            assertThat(ex.getProviderName()).isEqualTo("openai");
            assertThat(ex.getMessage()).isEqualTo("Connection failed");
            assertThat(ex.getErrorCode()).isNull();
            assertThat(ex.isRetryable()).isFalse();
        }

        @Test
        @DisplayName("should create with provider, message, and cause")
        void shouldCreateWithCause() {
            Exception cause = new RuntimeException("root cause");
            LLMProviderException ex = new LLMProviderException("anthropic", "API error", cause);

            assertThat(ex.getProviderName()).isEqualTo("anthropic");
            assertThat(ex.getCause()).isEqualTo(cause);
        }

        @Test
        @DisplayName("should create with all fields")
        void shouldCreateWithAllFields() {
            LLMProviderException ex = new LLMProviderException("google", "Rate limited", "rate_limit", true);

            assertThat(ex.getProviderName()).isEqualTo("google");
            assertThat(ex.getErrorCode()).isEqualTo("rate_limit");
            assertThat(ex.isRetryable()).isTrue();
        }

        @Test
        @DisplayName("should create with all fields and cause")
        void shouldCreateWithAllFieldsAndCause() {
            Throwable cause = new Exception("timeout");
            LLMProviderException ex = new LLMProviderException("mistral", "Timeout", "timeout", true, cause);

            assertThat(ex.getProviderName()).isEqualTo("mistral");
            assertThat(ex.getErrorCode()).isEqualTo("timeout");
            assertThat(ex.isRetryable()).isTrue();
            assertThat(ex.getCause()).isEqualTo(cause);
        }
    }

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethodTests {

        @Test
        @DisplayName("rateLimited() should create retryable exception")
        void rateLimitedShouldBeRetryable() {
            LLMProviderException ex = LLMProviderException.rateLimited("openai");

            assertThat(ex.getProviderName()).isEqualTo("openai");
            assertThat(ex.getErrorCode()).isEqualTo("rate_limit");
            assertThat(ex.isRetryable()).isTrue();
            assertThat(ex.getMessage()).contains("Rate limit");
        }

        @Test
        @DisplayName("unauthorized() should create non-retryable exception")
        void unauthorizedShouldNotBeRetryable() {
            LLMProviderException ex = LLMProviderException.unauthorized("anthropic");

            assertThat(ex.getProviderName()).isEqualTo("anthropic");
            assertThat(ex.getErrorCode()).isEqualTo("unauthorized");
            assertThat(ex.isRetryable()).isFalse();
            assertThat(ex.getMessage()).contains("Invalid API key");
        }

        @Test
        @DisplayName("modelNotFound() should include model name")
        void modelNotFoundShouldIncludeModel() {
            LLMProviderException ex = LLMProviderException.modelNotFound("openai", "gpt-5-turbo");

            assertThat(ex.getProviderName()).isEqualTo("openai");
            assertThat(ex.getErrorCode()).isEqualTo("model_not_found");
            assertThat(ex.isRetryable()).isFalse();
            assertThat(ex.getMessage()).contains("gpt-5-turbo");
        }

        @Test
        @DisplayName("streamingNotSupported() should create non-retryable exception")
        void streamingNotSupportedShouldCreate() {
            LLMProviderException ex = LLMProviderException.streamingNotSupported("mistral");

            assertThat(ex.getProviderName()).isEqualTo("mistral");
            assertThat(ex.getErrorCode()).isEqualTo("streaming_not_supported");
            assertThat(ex.isRetryable()).isFalse();
        }
    }
}
