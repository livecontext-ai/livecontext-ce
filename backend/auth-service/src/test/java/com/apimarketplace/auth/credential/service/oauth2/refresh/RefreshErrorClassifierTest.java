package com.apimarketplace.auth.credential.service.oauth2.refresh;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RefreshErrorClassifier")
class RefreshErrorClassifierTest {

    private RefreshErrorClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new RefreshErrorClassifier();
    }

    @Nested
    @DisplayName("priority order")
    class PriorityOrder {

        /**
         * ResourceAccessException wraps socket-level I/O. Must land on TRANSIENT before any other
         * branch - otherwise a network blip during a 4xx response read could be misclassified.
         */
        @Test
        @DisplayName("ResourceAccessException → TRANSIENT (socket I/O failure)")
        void resourceAccessExceptionIsTransient() {
            ResourceAccessException cause = new ResourceAccessException(
                    "I/O error", new SocketTimeoutException("read timed out"));

            RuntimeException result = classifier.classify(cause, 0);

            assertThat(result).isInstanceOf(RefreshTransientException.class);
            RefreshTransientException transient_ = (RefreshTransientException) result;
            assertThat(transient_.bucket()).isEqualTo(RefreshErrorBucket.TRANSIENT);
            assertThat(transient_.httpStatus()).isNull();
            assertThat(transient_.providerCode()).isNull();
            assertThat(transient_.getCause()).isSameAs(cause);
        }

        @Test
        @DisplayName("HttpServerErrorException (503) → TRANSIENT with status")
        void serverErrorIsTransient() {
            HttpServerErrorException cause = HttpServerErrorException.create(
                    HttpStatus.SERVICE_UNAVAILABLE, "unavailable",
                    new HttpHeaders(), new byte[0], null);

            RuntimeException result = classifier.classify(cause, 2);

            assertThat(result).isInstanceOf(RefreshTransientException.class);
            RefreshTransientException transient_ = (RefreshTransientException) result;
            assertThat(transient_.bucket()).isEqualTo(RefreshErrorBucket.TRANSIENT);
            assertThat(transient_.httpStatus()).isEqualTo(503);
            assertThat(transient_.attempt()).isEqualTo(2);
        }

        /**
         * Last-resort branch: any exception not matching the typed ladder must still produce a
         * TRANSIENT result, not a terminal. Better to retry a bug than to burn a user's credential.
         */
        @Test
        @DisplayName("unrecognised exception → TRANSIENT (never silently terminal)")
        void unknownExceptionFallsBackToTransient() {
            RuntimeException cause = new IllegalArgumentException("oops");

            RuntimeException result = classifier.classify(cause, 0);

            assertThat(result).isInstanceOf(RefreshTransientException.class);
            assertThat(((RefreshTransientException) result).bucket())
                    .isEqualTo(RefreshErrorBucket.TRANSIENT);
        }
    }

    @Nested
    @DisplayName("429 rate-limit handling")
    class RateLimit {

        @Test
        @DisplayName("429 with numeric Retry-After → RATE_LIMIT + parsed duration")
        void rateLimitWithNumericRetryAfter() {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Retry-After", "60");
            HttpClientErrorException cause = clientError(429, "{}", headers);

            RuntimeException result = classifier.classify(cause, 0);

            assertThat(result).isInstanceOf(RefreshTransientException.class);
            RefreshTransientException transient_ = (RefreshTransientException) result;
            assertThat(transient_.bucket()).isEqualTo(RefreshErrorBucket.RATE_LIMIT);
            assertThat(transient_.httpStatus()).isEqualTo(429);
            assertThat(transient_.retryAfter()).isEqualTo(Duration.ofSeconds(60));
        }

        @Test
        @DisplayName("429 without Retry-After → RATE_LIMIT + null retryAfter")
        void rateLimitWithoutRetryAfter() {
            HttpClientErrorException cause = clientError(429, "{}", new HttpHeaders());

            RuntimeException result = classifier.classify(cause, 0);

            RefreshTransientException transient_ = (RefreshTransientException) result;
            assertThat(transient_.bucket()).isEqualTo(RefreshErrorBucket.RATE_LIMIT);
            assertThat(transient_.retryAfter()).isNull();
        }
    }

    @Nested
    @DisplayName("RFC 6749 error-code → bucket matrix")
    class RfcCodeMatrix {

        @Test
        @DisplayName("invalid_grant → TERMINAL_USER (refresh_token is dead)")
        void invalidGrantIsTerminalUser() {
            HttpClientErrorException cause = clientError(
                    400, "{\"error\":\"invalid_grant\"}", new HttpHeaders());

            RuntimeException result = classifier.classify(cause, 0);

            assertThat(result).isInstanceOf(RefreshTerminalException.class);
            RefreshTerminalException terminal = (RefreshTerminalException) result;
            assertThat(terminal.bucket()).isEqualTo(RefreshErrorBucket.TERMINAL_USER);
            assertThat(terminal.providerCode()).isEqualTo("invalid_grant");
            assertThat(terminal.httpStatus()).isEqualTo(400);
        }

        @Test
        @DisplayName("unauthorized_client → TERMINAL_USER")
        void unauthorizedClientIsTerminalUser() {
            HttpClientErrorException cause = clientError(
                    400, "{\"error\":\"unauthorized_client\"}", new HttpHeaders());

            RuntimeException result = classifier.classify(cause, 0);

            assertThat(((RefreshTerminalException) result).bucket())
                    .isEqualTo(RefreshErrorBucket.TERMINAL_USER);
        }

        @Test
        @DisplayName("invalid_client → TERMINAL_CONFIG (admin must rotate secret)")
        void invalidClientIsTerminalConfig() {
            HttpClientErrorException cause = clientError(
                    401, "{\"error\":\"invalid_client\"}", new HttpHeaders());

            RuntimeException result = classifier.classify(cause, 0);

            RefreshTerminalException terminal = (RefreshTerminalException) result;
            assertThat(terminal.bucket()).isEqualTo(RefreshErrorBucket.TERMINAL_CONFIG);
            assertThat(terminal.providerCode()).isEqualTo("invalid_client");
        }

        @Test
        @DisplayName("invalid_scope → TERMINAL_CONFIG")
        void invalidScopeIsTerminalConfig() {
            HttpClientErrorException cause = clientError(
                    400, "{\"error\":\"invalid_scope\"}", new HttpHeaders());

            assertThat(((RefreshTerminalException) classifier.classify(cause, 0)).bucket())
                    .isEqualTo(RefreshErrorBucket.TERMINAL_CONFIG);
        }

        @Test
        @DisplayName("unsupported_grant_type → TERMINAL_CONFIG")
        void unsupportedGrantTypeIsTerminalConfig() {
            HttpClientErrorException cause = clientError(
                    400, "{\"error\":\"unsupported_grant_type\"}", new HttpHeaders());

            assertThat(((RefreshTerminalException) classifier.classify(cause, 0)).bucket())
                    .isEqualTo(RefreshErrorBucket.TERMINAL_CONFIG);
        }

        /**
         * invalid_request is a client-side bug (we sent a malformed body). The bucket is CLIENT_BUG
         * internally but surfaces as TERMINAL_CONFIG because retrying without a code change is
         * pointless and ops need to investigate.
         */
        @Test
        @DisplayName("invalid_request → TERMINAL_CONFIG (collapsed from CLIENT_BUG)")
        void invalidRequestCollapsesToTerminalConfig() {
            HttpClientErrorException cause = clientError(
                    400, "{\"error\":\"invalid_request\"}", new HttpHeaders());

            RuntimeException result = classifier.classify(cause, 0);

            assertThat(result).isInstanceOf(RefreshTerminalException.class);
            RefreshTerminalException terminal = (RefreshTerminalException) result;
            assertThat(terminal.bucket()).isEqualTo(RefreshErrorBucket.TERMINAL_CONFIG);
            assertThat(terminal.providerCode()).isEqualTo("invalid_request");
            assertThat(terminal.reason()).contains("malformed");
        }
    }

    @Nested
    @DisplayName("unknown provider code → HTTP-status fallback")
    class UnknownCodeFallback {

        /**
         * 401/403 with no RFC code almost always mean the refresh_token or client creds are no
         * longer honored. We treat it as user-terminal to force a re-OAuth rather than looping.
         */
        @Test
        @DisplayName("401 with empty body → TERMINAL_USER")
        void status401IsTerminalUser() {
            HttpClientErrorException cause = clientError(401, "", new HttpHeaders());

            RefreshErrorBucket bucket = classifier.bucketFor4xx(401, null);
            assertThat(bucket).isEqualTo(RefreshErrorBucket.TERMINAL_USER);

            RuntimeException result = classifier.classify(cause, 0);
            assertThat(((RefreshTerminalException) result).bucket())
                    .isEqualTo(RefreshErrorBucket.TERMINAL_USER);
        }

        @Test
        @DisplayName("403 with non-JSON body → TERMINAL_USER")
        void status403IsTerminalUser() {
            HttpClientErrorException cause = clientError(403, "forbidden", new HttpHeaders());

            RuntimeException result = classifier.classify(cause, 0);
            assertThat(((RefreshTerminalException) result).bucket())
                    .isEqualTo(RefreshErrorBucket.TERMINAL_USER);
        }

        @Test
        @DisplayName("400 with no RFC code → TERMINAL_CONFIG (template drift)")
        void status400IsTerminalConfig() {
            HttpClientErrorException cause = clientError(400, "{}", new HttpHeaders());

            RuntimeException result = classifier.classify(cause, 0);
            assertThat(((RefreshTerminalException) result).bucket())
                    .isEqualTo(RefreshErrorBucket.TERMINAL_CONFIG);
        }

        @Test
        @DisplayName("404 with no RFC code → TERMINAL_CONFIG")
        void status404IsTerminalConfig() {
            assertThat(classifier.bucketFor4xx(404, null))
                    .isEqualTo(RefreshErrorBucket.TERMINAL_CONFIG);
        }

        @Test
        @DisplayName("unknown provider code with 401 still maps to TERMINAL_USER via status")
        void unknownCodeWithStatusFallsBack() {
            assertThat(classifier.bucketFor4xx(401, "weird_provider_specific_code"))
                    .isEqualTo(RefreshErrorBucket.TERMINAL_USER);
        }
    }

    @Nested
    @DisplayName("providerCode body parsing")
    class ProviderCodeParsing {

        @Test
        @DisplayName("null body → null code")
        void nullBody() {
            assertThat(classifier.providerCode(null)).isNull();
        }

        @Test
        @DisplayName("empty body → null code")
        void emptyBody() {
            assertThat(classifier.providerCode("")).isNull();
        }

        @Test
        @DisplayName("non-JSON body → null code (no throw)")
        void nonJsonBody() {
            assertThat(classifier.providerCode("not json at all")).isNull();
        }

        @Test
        @DisplayName("JSON array → null code (must be object)")
        void jsonArrayBody() {
            assertThat(classifier.providerCode("[1,2,3]")).isNull();
        }

        @Test
        @DisplayName("valid RFC body → extracted error code")
        void validBody() {
            assertThat(classifier.providerCode("{\"error\":\"invalid_grant\"}"))
                    .isEqualTo("invalid_grant");
        }

        @Test
        @DisplayName("numeric error field → null (must be textual)")
        void nonTextualErrorField() {
            assertThat(classifier.providerCode("{\"error\":42}")).isNull();
        }

        @Test
        @DisplayName("empty error field → null")
        void emptyErrorField() {
            assertThat(classifier.providerCode("{\"error\":\"\"}")).isNull();
        }
    }

    @Nested
    @DisplayName("Retry-After parsing (RFC 7231 §7.1.3)")
    class RetryAfterParsing {

        @Test
        @DisplayName("null headers → null")
        void nullHeaders() {
            assertThat(classifier.parseRetryAfter(null)).isNull();
        }

        @Test
        @DisplayName("header absent → null")
        void headerAbsent() {
            assertThat(classifier.parseRetryAfter(new HttpHeaders())).isNull();
        }

        @Test
        @DisplayName("blank value → null")
        void blankValue() {
            HttpHeaders h = new HttpHeaders();
            h.set("Retry-After", "   ");
            assertThat(classifier.parseRetryAfter(h)).isNull();
        }

        @Test
        @DisplayName("delta-seconds integer → Duration")
        void deltaSeconds() {
            HttpHeaders h = new HttpHeaders();
            h.set("Retry-After", "120");
            assertThat(classifier.parseRetryAfter(h)).isEqualTo(Duration.ofSeconds(120));
        }

        @Test
        @DisplayName("negative seconds → null (invalid)")
        void negativeSeconds() {
            HttpHeaders h = new HttpHeaders();
            h.set("Retry-After", "-5");
            assertThat(classifier.parseRetryAfter(h)).isNull();
        }

        @Test
        @DisplayName("HTTP-date in the future → positive Duration")
        void httpDateFuture() {
            ZonedDateTime futureUtc = ZonedDateTime.now(ZoneId.of("UTC")).plusMinutes(5);
            HttpHeaders h = new HttpHeaders();
            h.set("Retry-After", futureUtc.format(DateTimeFormatter.RFC_1123_DATE_TIME));

            Duration result = classifier.parseRetryAfter(h);

            assertThat(result).isNotNull();
            // Allow a generous 10-second tolerance for wall-clock skew during the assertion.
            assertThat(result).isBetween(Duration.ofMinutes(4).plusSeconds(50), Duration.ofMinutes(5).plusSeconds(10));
        }

        @Test
        @DisplayName("HTTP-date in the past → Duration.ZERO (not negative)")
        void httpDatePastClampsToZero() {
            ZonedDateTime pastUtc = ZonedDateTime.now(ZoneId.of("UTC")).minusMinutes(5);
            HttpHeaders h = new HttpHeaders();
            h.set("Retry-After", pastUtc.format(DateTimeFormatter.RFC_1123_DATE_TIME));

            assertThat(classifier.parseRetryAfter(h)).isEqualTo(Duration.ZERO);
        }

        @Test
        @DisplayName("garbage value → null (unparseable, no throw)")
        void garbageValue() {
            HttpHeaders h = new HttpHeaders();
            h.set("Retry-After", "not-a-date-or-number");
            assertThat(classifier.parseRetryAfter(h)).isNull();
        }
    }

    // --- helpers ---

    private static HttpClientErrorException clientError(int status, String body, HttpHeaders headers) {
        return HttpClientErrorException.create(
                HttpStatus.valueOf(status),
                "status " + status,
                headers,
                body.getBytes(),
                null);
    }
}
