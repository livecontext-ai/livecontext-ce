package com.apimarketplace.auth.web;

import com.stripe.exception.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StripeExceptionHandler Tests")
class StripeExceptionHandlerTest {

    private StripeExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new StripeExceptionHandler();
    }

    @Nested
    @DisplayName("handleCardException()")
    class HandleCardExceptionTests {

        @Test
        @DisplayName("should return PAYMENT_REQUIRED status")
        void shouldReturnPaymentRequiredStatus() {
            CardException ex = new CardException("Card declined", "req_123", "card_declined", null, "generic_decline", null, 402, null);

            ResponseEntity<Map<String, Object>> response = handler.handleCardException(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        }

        @Test
        @DisplayName("should include error type 'payment_failed'")
        void shouldIncludePaymentFailedError() {
            CardException ex = new CardException("Card declined", "req_123", "card_declined", null, "generic_decline", null, 402, null);

            ResponseEntity<Map<String, Object>> response = handler.handleCardException(ex);

            assertThat(response.getBody()).containsEntry("error", "payment_failed");
        }

        @Test
        @DisplayName("should include decline code")
        void shouldIncludeDeclineCode() {
            CardException ex = new CardException("Card declined", "req_123", "card_declined", null, "generic_decline", null, 402, null);

            ResponseEntity<Map<String, Object>> response = handler.handleCardException(ex);

            assertThat(response.getBody()).containsEntry("declineCode", "generic_decline");
        }

        @ParameterizedTest
        @ValueSource(strings = {"card_declined", "insufficient_funds", "expired_card", "incorrect_cvc", "incorrect_number", "processing_error"})
        @DisplayName("should return specific message for known card error codes")
        void shouldReturnSpecificMessageForKnownCodes(String code) {
            CardException ex = new CardException("Error", "req_123", code, null, null, null, 402, null);

            ResponseEntity<Map<String, Object>> response = handler.handleCardException(ex);

            assertThat(response.getBody().get("message")).isNotNull();
            assertThat((String) response.getBody().get("message")).isNotEmpty();
        }

        @Test
        @DisplayName("should return default message for null code")
        void shouldReturnDefaultMessageForNullCode() {
            CardException ex = new CardException("Error", "req_123", null, null, null, null, 402, null);

            ResponseEntity<Map<String, Object>> response = handler.handleCardException(ex);

            assertThat(response.getBody().get("message")).isNotNull();
        }
    }

    @Nested
    @DisplayName("handleRateLimitException()")
    class HandleRateLimitExceptionTests {

        @Test
        @DisplayName("should return TOO_MANY_REQUESTS status")
        void shouldReturnTooManyRequestsStatus() {
            RateLimitException ex = new RateLimitException("Rate limit", "req_123", null, null, 429, null);

            ResponseEntity<Map<String, Object>> response = handler.handleRateLimitException(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }

        @Test
        @DisplayName("should include retryAfter value")
        void shouldIncludeRetryAfter() {
            RateLimitException ex = new RateLimitException("Rate limit", "req_123", null, null, 429, null);

            ResponseEntity<Map<String, Object>> response = handler.handleRateLimitException(ex);

            assertThat(response.getBody()).containsEntry("retryAfter", 60);
        }

        @Test
        @DisplayName("should include error type 'rate_limit_exceeded'")
        void shouldIncludeRateLimitError() {
            RateLimitException ex = new RateLimitException("Rate limit", "req_123", null, null, 429, null);

            ResponseEntity<Map<String, Object>> response = handler.handleRateLimitException(ex);

            assertThat(response.getBody()).containsEntry("error", "rate_limit_exceeded");
        }
    }

    @Nested
    @DisplayName("handleInvalidRequestException()")
    class HandleInvalidRequestExceptionTests {

        @Test
        @DisplayName("should return NOT_FOUND for resource_missing code")
        void shouldReturnNotFoundForResourceMissing() {
            InvalidRequestException ex = new InvalidRequestException("Not found", "param", "req_123", "resource_missing", 404, null);

            ResponseEntity<Map<String, Object>> response = handler.handleInvalidRequestException(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).containsEntry("error", "not_found");
        }

        @Test
        @DisplayName("should return NOT_FOUND for 'No such customer' message")
        void shouldReturnNotFoundForNoSuchCustomer() {
            InvalidRequestException ex = new InvalidRequestException("No such customer: cus_123", "customer", "req_123", "invalid_request_error", 404, null);

            ResponseEntity<Map<String, Object>> response = handler.handleInvalidRequestException(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).containsEntry("error", "customer_not_found");
        }

        @Test
        @DisplayName("should return NOT_FOUND for 'No such subscription' message")
        void shouldReturnNotFoundForNoSuchSubscription() {
            InvalidRequestException ex = new InvalidRequestException("No such subscription: sub_123", "subscription", "req_123", "invalid_request_error", 404, null);

            ResponseEntity<Map<String, Object>> response = handler.handleInvalidRequestException(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).containsEntry("error", "subscription_not_found");
        }

        @Test
        @DisplayName("should return BAD_REQUEST for generic invalid request")
        void shouldReturnBadRequestForGenericInvalidRequest() {
            InvalidRequestException ex = new InvalidRequestException("Invalid param", "amount", "req_123", "parameter_invalid", 400, null);

            ResponseEntity<Map<String, Object>> response = handler.handleInvalidRequestException(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsEntry("error", "invalid_request");
        }
    }

    @Nested
    @DisplayName("handleAuthenticationException()")
    class HandleAuthenticationExceptionTests {

        @Test
        @DisplayName("should return INTERNAL_SERVER_ERROR status")
        void shouldReturnInternalServerErrorStatus() {
            AuthenticationException ex = new AuthenticationException("Invalid API key", "req_123", null, 401);

            ResponseEntity<Map<String, Object>> response = handler.handleAuthenticationException(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("should include error type 'configuration_error'")
        void shouldIncludeConfigurationError() {
            AuthenticationException ex = new AuthenticationException("Invalid API key", "req_123", null, 401);

            ResponseEntity<Map<String, Object>> response = handler.handleAuthenticationException(ex);

            assertThat(response.getBody()).containsEntry("error", "configuration_error");
        }
    }

    @Nested
    @DisplayName("handleApiConnectionException()")
    class HandleApiConnectionExceptionTests {

        @Test
        @DisplayName("should return SERVICE_UNAVAILABLE status")
        void shouldReturnServiceUnavailableStatus() {
            ApiConnectionException ex = new ApiConnectionException("Connection timeout");

            ResponseEntity<Map<String, Object>> response = handler.handleApiConnectionException(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }

        @Test
        @DisplayName("should indicate retryable error")
        void shouldIndicateRetryable() {
            ApiConnectionException ex = new ApiConnectionException("Connection timeout");

            ResponseEntity<Map<String, Object>> response = handler.handleApiConnectionException(ex);

            assertThat(response.getBody()).containsEntry("retryable", true);
        }

        @Test
        @DisplayName("should include error type 'payment_service_unavailable'")
        void shouldIncludePaymentServiceUnavailableError() {
            ApiConnectionException ex = new ApiConnectionException("Connection timeout");

            ResponseEntity<Map<String, Object>> response = handler.handleApiConnectionException(ex);

            assertThat(response.getBody()).containsEntry("error", "payment_service_unavailable");
        }
    }

    @Nested
    @DisplayName("handleSignatureVerificationException()")
    class HandleSignatureVerificationExceptionTests {

        @Test
        @DisplayName("should return BAD_REQUEST status")
        void shouldReturnBadRequestStatus() {
            SignatureVerificationException ex = new SignatureVerificationException("Invalid signature", "sig_header");

            ResponseEntity<Map<String, Object>> response = handler.handleSignatureVerificationException(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("should include error type 'invalid_signature'")
        void shouldIncludeInvalidSignatureError() {
            SignatureVerificationException ex = new SignatureVerificationException("Invalid signature", "sig_header");

            ResponseEntity<Map<String, Object>> response = handler.handleSignatureVerificationException(ex);

            assertThat(response.getBody()).containsEntry("error", "invalid_signature");
        }
    }

    @Nested
    @DisplayName("handleApiException()")
    class HandleApiExceptionTests {

        @Test
        @DisplayName("should return INTERNAL_SERVER_ERROR status")
        void shouldReturnInternalServerErrorStatus() {
            ApiException ex = new ApiException("Server error", "req_123", "server_error", 500, null);

            ResponseEntity<Map<String, Object>> response = handler.handleApiException(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("should include error type 'payment_error'")
        void shouldIncludePaymentError() {
            ApiException ex = new ApiException("Server error", "req_123", "server_error", 500, null);

            ResponseEntity<Map<String, Object>> response = handler.handleApiException(ex);

            assertThat(response.getBody()).containsEntry("error", "payment_error");
        }
    }

    @Nested
    @DisplayName("handleStripeException()")
    class HandleStripeExceptionTests {

        @Test
        @DisplayName("should return INTERNAL_SERVER_ERROR status")
        void shouldReturnInternalServerErrorStatus() {
            // StripeException is abstract, use a concrete subclass
            ApiException ex = new ApiException("Unexpected error", "req_123", "unknown", 500, null);

            ResponseEntity<Map<String, Object>> response = handler.handleStripeException(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("should include error type 'stripe_error'")
        void shouldIncludeStripeError() {
            ApiException ex = new ApiException("Unexpected error", "req_123", "unknown", 500, null);

            ResponseEntity<Map<String, Object>> response = handler.handleStripeException(ex);

            assertThat(response.getBody()).containsEntry("error", "stripe_error");
        }
    }
}
