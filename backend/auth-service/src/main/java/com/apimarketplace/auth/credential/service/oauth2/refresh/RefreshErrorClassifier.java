package com.apimarketplace.auth.credential.service.oauth2.refresh;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Set;

/**
 * Maps raw refresh-token failures to {@link RefreshErrorBucket} + emits typed exceptions.
 *
 * <p>Priority order:
 * <ol>
 *   <li>{@link ResourceAccessException} (Spring wrapper for socket I/O failures) → TRANSIENT.</li>
 *   <li>{@link HttpServerErrorException} (5xx) → TRANSIENT.</li>
 *   <li>{@link HttpClientErrorException} (4xx):
 *     <ul>
 *       <li>429 → RATE_LIMIT (with parsed {@code Retry-After}).</li>
 *       <li>Parse RFC 6749 {@code error} field from body:
 *         <ul>
 *           <li>{@code invalid_grant}, {@code unauthorized_client} → TERMINAL_USER.</li>
 *           <li>{@code invalid_client}, {@code invalid_scope}, {@code unsupported_grant_type} → TERMINAL_CONFIG.</li>
 *           <li>{@code invalid_request} → CLIENT_BUG (collapsed to TERMINAL_CONFIG).</li>
 *           <li>Unknown code: classify by HTTP status - 401/403 → TERMINAL_USER, else TERMINAL_CONFIG.</li>
 *         </ul>
 *       </li>
 *     </ul>
 *   </li>
 *   <li>Anything else → TRANSIENT (last resort - better to retry a parse error than fail the user).</li>
 * </ol>
 */
@Component
public class RefreshErrorClassifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** RFC 6749 §5.2 codes that unambiguously mean "user must re-OAuth". */
    private static final Set<String> TERMINAL_USER_CODES = Set.of(
            "invalid_grant",
            "unauthorized_client"
    );

    /** RFC 6749 §5.2 codes that mean "admin must fix config (template, secret, scope)". */
    private static final Set<String> TERMINAL_CONFIG_CODES = Set.of(
            "invalid_client",
            "invalid_scope",
            "unsupported_grant_type"
    );

    /** RFC 6749 §5.2 code indicating our request was malformed - client bug. */
    private static final Set<String> CLIENT_BUG_CODES = Set.of(
            "invalid_request"
    );

    /**
     * RFC 7231 §7.1.1.1 preferred date format. Providers almost always use this, but we also
     * tolerate RFC 850 and asctime formats via {@link DateTimeFormatter#RFC_1123_DATE_TIME}.
     */
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME;

    /**
     * Turn a caught exception into a typed refresh exception. Attempt counter is threaded from
     * the caller so the jitter schedule can grow with each retry.
     */
    public RuntimeException classify(Throwable cause, int attempt) {
        if (cause instanceof ResourceAccessException socketFailure) {
            return new RefreshTransientException(
                    RefreshErrorBucket.TRANSIENT, null, null, null, attempt, socketFailure);
        }
        if (cause instanceof HttpServerErrorException serverError) {
            return new RefreshTransientException(
                    RefreshErrorBucket.TRANSIENT,
                    null,
                    serverError.getStatusCode().value(),
                    null,
                    attempt,
                    serverError);
        }
        if (cause instanceof HttpClientErrorException clientError) {
            int status = clientError.getStatusCode().value();
            if (status == 429) {
                Duration retryAfter = parseRetryAfter(clientError.getResponseHeaders());
                return new RefreshTransientException(
                        RefreshErrorBucket.RATE_LIMIT,
                        providerCode(clientError.getResponseBodyAsString()),
                        status,
                        retryAfter,
                        attempt,
                        clientError);
            }
            String code = providerCode(clientError.getResponseBodyAsString());
            RefreshErrorBucket bucket = bucketFor4xx(status, code);
            return switch (bucket) {
                case TERMINAL_USER, TERMINAL_CONFIG -> new RefreshTerminalException(
                        bucket, code, status, bucket == RefreshErrorBucket.TERMINAL_USER
                        ? "provider rejected refresh_token - user must re-authorize"
                        : "provider rejected client config - admin must fix template/secret/scope");
                case CLIENT_BUG -> new RefreshTerminalException(
                        RefreshErrorBucket.TERMINAL_CONFIG, code, status,
                        "client sent a malformed refresh request - see logs");
                case RATE_LIMIT -> new RefreshTransientException(
                        RefreshErrorBucket.RATE_LIMIT, code, status, null, attempt, clientError);
                case TRANSIENT -> new RefreshTransientException(
                        RefreshErrorBucket.TRANSIENT, code, status, null, attempt, clientError);
            };
        }
        // Last resort: anything else (JSON parse blow-up, NPE, …). Transient so the user gets a
        // retry rather than a permanent terminal on a bug on our side.
        return new RefreshTransientException(
                RefreshErrorBucket.TRANSIENT, null, null, null, attempt, cause);
    }

    /**
     * Visible for testing. Apply the 4xx code→bucket matrix (excluding 429 which is handled
     * upstream).
     */
    RefreshErrorBucket bucketFor4xx(int httpStatus, String providerCode) {
        if (providerCode != null && !providerCode.isBlank()) {
            if (TERMINAL_USER_CODES.contains(providerCode)) return RefreshErrorBucket.TERMINAL_USER;
            if (TERMINAL_CONFIG_CODES.contains(providerCode)) return RefreshErrorBucket.TERMINAL_CONFIG;
            if (CLIENT_BUG_CODES.contains(providerCode)) return RefreshErrorBucket.CLIENT_BUG;
        }
        // Unknown provider code. Fall back to HTTP status semantics: 401/403 almost always mean
        // the refresh_token or client credentials are no longer honored.
        if (httpStatus == 401 || httpStatus == 403) {
            return RefreshErrorBucket.TERMINAL_USER;
        }
        // 400 with no RFC code is usually a template drift (scope mismatch, missing field).
        return RefreshErrorBucket.TERMINAL_CONFIG;
    }

    /**
     * Parse the {@code error} field from an RFC 6749 token-endpoint error body. Returns
     * {@code null} on empty/non-JSON/absent field.
     */
    String providerCode(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root == null || !root.isObject()) return null;
            JsonNode errorNode = root.path("error");
            if (!errorNode.isTextual()) return null;
            String value = errorNode.asText();
            return value.isEmpty() ? null : value;
        } catch (Exception parseFailure) {
            return null;
        }
    }

    /**
     * Parse {@code Retry-After} per RFC 7231 §7.1.3 - either delta-seconds (int) or HTTP-date.
     * Returns {@code null} when absent or unparseable.
     */
    Duration parseRetryAfter(HttpHeaders headers) {
        if (headers == null) return null;
        String raw = headers.getFirst("Retry-After");
        if (raw == null || raw.isBlank()) return null;
        raw = raw.trim();
        try {
            long seconds = Long.parseLong(raw);
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException notInt) {
            // Fall through to HTTP-date parsing.
        }
        try {
            ZonedDateTime target = ZonedDateTime.parse(raw, HTTP_DATE);
            Duration delta = Duration.between(ZonedDateTime.now(ZoneId.of("UTC")), target);
            return delta.isNegative() ? Duration.ZERO : delta;
        } catch (DateTimeParseException unparseable) {
            return null;
        }
    }
}
