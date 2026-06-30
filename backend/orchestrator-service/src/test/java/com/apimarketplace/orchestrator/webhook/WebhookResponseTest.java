package com.apimarketplace.orchestrator.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for WebhookResponse record.
 */
@DisplayName("WebhookResponse")
class WebhookResponseTest {

    @Nested
    @DisplayName("factory methods")
    class FactoryMethodTests {

        @Test
        @DisplayName("accepted should create response with accepted status")
        void acceptedShouldCreateCorrectResponse() {
            WebhookResponse response = WebhookResponse.accepted("exec-123");

            assertThat(response.executionId()).isEqualTo("exec-123");
            assertThat(response.status()).isEqualTo("accepted");
            assertThat(response.message()).isEqualTo("Workflow execution started");
            assertThat(response.result()).isNull();
        }

        @Test
        @DisplayName("completed should create response with result")
        void completedShouldCreateCorrectResponse() {
            Map<String, Object> result = Map.of("data", "value");
            WebhookResponse response = WebhookResponse.completed("exec-456", result);

            assertThat(response.executionId()).isEqualTo("exec-456");
            assertThat(response.status()).isEqualTo("completed");
            assertThat(response.message()).isEqualTo("Workflow execution completed");
            assertThat(response.result()).isEqualTo(result);
        }

        @Test
        @DisplayName("error should create response with error message")
        void errorShouldCreateCorrectResponse() {
            WebhookResponse response = WebhookResponse.error("Something went wrong");

            assertThat(response.executionId()).isNull();
            assertThat(response.status()).isEqualTo("error");
            assertThat(response.message()).isEqualTo("Something went wrong");
            assertThat(response.result()).isNull();
        }

        @Test
        @DisplayName("notFound should create response with not_found status")
        void notFoundShouldCreateCorrectResponse() {
            WebhookResponse response = WebhookResponse.notFound();

            assertThat(response.executionId()).isNull();
            assertThat(response.status()).isEqualTo("not_found");
        }

        @Test
        @DisplayName("notActive should create response with not_active status")
        void notActiveShouldCreateCorrectResponse() {
            WebhookResponse response = WebhookResponse.notActive();

            assertThat(response.executionId()).isNull();
            assertThat(response.status()).isEqualTo("not_active");
        }

        @Test
        @DisplayName("insufficientCredits should create response with insufficient_credits status and pricing link")
        void insufficientCreditsShouldCreateCorrectResponse() {
            WebhookResponse response = WebhookResponse.insufficientCredits();

            assertThat(response.executionId()).isNull();
            assertThat(response.status()).isEqualTo("insufficient_credits");
            assertThat(response.message()).contains("Insufficient credits");
            assertThat(response.message()).contains("/app/settings/pricing");
        }

        @Test
        @DisplayName("rateLimited should create response with rate_limited status")
        void rateLimitedShouldCreateCorrectResponse() {
            WebhookResponse response = WebhookResponse.rateLimited();

            assertThat(response.executionId()).isNull();
            assertThat(response.status()).isEqualTo("rate_limited");
        }

        @Test
        @DisplayName("triggered should create response with triggered status")
        void triggeredShouldCreateCorrectResponse() {
            WebhookResponse response = WebhookResponse.triggered("exec-789");

            assertThat(response.executionId()).isEqualTo("exec-789");
            assertThat(response.status()).isEqualTo("triggered");
        }
    }
}
