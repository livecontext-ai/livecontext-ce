package com.apimarketplace.catalog.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Decides what to do with a provider response the catalog could not turn into a success.
 *
 * <p>Two inputs, in this order:
 *
 * <ol>
 *   <li><b>The API's declared {@code errorPolicy}</b> (catalog column {@code apis.error_policy},
 *       fed by the seed file). Rules are evaluated in declaration order, first match wins, so an
 *       author can override the built-in rule below for a provider that means something unusual
 *       by a given status.</li>
 *   <li><b>The built-in rules</b>, which need no seed change and therefore cover the whole
 *       catalogue: {@code 429} for any method, and {@code 503} carrying a {@code Retry-After} for
 *       a <em>safe</em> method only (see {@link #isSafeMethod}, which is deliberately narrower
 *       than "idempotent").</li>
 * </ol>
 *
 * <h3>Why retrying a POST here is safe, and where it stops being safe</h3>
 * A {@code 429} is a REJECTION: the provider refused the request, so it was not applied and
 * re-sending it cannot duplicate anything, whatever the method. A timeout or a socket error is
 * never retried, because there the outcome is unknown and a re-send could publish twice.
 *
 * <p>Any {@code 5xx} sits between the two and is treated accordingly: an intermediary can answer
 * 502/503/504 <em>after</em> the origin processed the request. So <b>no</b> retry, built-in or
 * declared, is ever performed on a 5xx for a non-idempotent method. A seed author can assert that
 * a provider's error code means "rejected", which is knowledge this engine does not have, but they
 * cannot assert it about a status that structurally cannot prove it.
 *
 * <h3>Relationship with the workflow-level retry</h3>
 * {@code NodePolicyRunner} already retries a whole failed node when the author configured a
 * {@code NodePolicy}. This engine is deliberately narrower and complementary: it retries the
 * single HTTP call, for the duration the provider itself specified, without the node knowing.
 *
 * <p>{@link #getMaxWaitMs()} is the TOTAL time a call may spend SLEEPING, across all its retries,
 * not a per-wait cap - the caller enforces it by tracking what it has already slept. When a wait
 * would exceed what is left, nothing is retried and the provider's failure is returned as it
 * stands. It bounds the waiting only: a retried call also pays for the extra request, and this
 * service's read timeout is generous, so the budget caps deliberate sleeping rather than total
 * thread occupancy.
 */
@Slf4j
@Component
public class ErrorPolicyEngine {

    /** What the execution path should do with the response. */
    public enum Action {
        /** Re-send the same request after {@link Verdict#waitMs()}. */
        RETRY,
        /** Stop, and replace the provider's raw body with a message written for the reader. */
        USER_ERROR,
        /** Nothing declared: the existing error handling applies unchanged. */
        NONE
    }

    /**
     * @param action  what to do
     * @param message reader-facing message ({@code USER_ERROR}), or null
     * @param waitMs  how long to wait before re-sending ({@code RETRY}), else 0
     */
    public record Verdict(Action action, String message, long waitMs) {

        public static Verdict none() {
            return new Verdict(Action.NONE, null, 0L);
        }

        public static Verdict retry(long waitMs, String message) {
            return new Verdict(Action.RETRY, message, waitMs);
        }

        public static Verdict userError(String message) {
            return new Verdict(Action.USER_ERROR, message, 0L);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Backoff used when a retryable status arrives without a usable {@code Retry-After}. */
    private static final long[] DEFAULT_BACKOFF_MS = {1_000L, 3_000L};

    /** A day. Any Retry-After at or above this declines the retry anyway, so the value only has
     * to be far past {@link #maxWaitMs} and far below overflow. */
    private static final long MAX_SANE_RETRY_AFTER_SECONDS = 86_400L;

    /** Floor under any provider-specified wait, so "come back now" is still a pause. */
    private static final long MIN_WAIT_MS = 250L;

    /** Mirrors the validator's floor: shorter than this identifies no provider error code. */
    private static final int MIN_BODY_NEEDLE_LENGTH = 4;

    private final int maxRetries;

    /**
     * Total time a single call may spend waiting, across every retry. Enforced by the caller,
     * which knows what it has already slept; this class refuses any single wait above it.
     */
    private final long maxWaitMs;

    public ErrorPolicyEngine(
            @Value("${catalog.error-policy.max-retries:2}") int maxRetries,
            @Value("${catalog.error-policy.max-wait-ms:10000}") long maxWaitMs) {
        this.maxRetries = maxRetries;
        this.maxWaitMs = maxWaitMs;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public long getMaxWaitMs() {
        return maxWaitMs;
    }

    /**
     * Classify one failed response.
     *
     * @param status          HTTP status the provider returned
     * @param responseBody    raw response body (may be null)
     * @param headers         response headers (may be null)
     * @param errorPolicyJson the API's {@code errorPolicy} array as stored in the catalog, or null
     * @param attemptIndex    0 on the first call, 1 after one retry, and so on
     */
    public Verdict classify(int status, String responseBody, HttpHeaders headers,
                            String errorPolicyJson, int attemptIndex, String httpMethod) {
        Long retryAfterSeconds = parseRetryAfterSeconds(headers);

        Verdict declared = matchDeclaredRule(status, responseBody, errorPolicyJson, retryAfterSeconds,
                attemptIndex, httpMethod);
        if (declared != null) {
            return declared;
        }

        // Built-in rule 1: 429 means the provider REJECTED the request. It was not applied,
        // whatever the method, so re-sending it cannot duplicate anything.
        if (status == 429) {
            return retryVerdict(retryAfterSeconds, null, attemptIndex, null);
        }

        // Built-in rule 2: a 503 carrying a Retry-After usually means the same thing, but not
        // provably: a gateway can answer 503 AFTER the origin has processed the request. So it is
        // retried only for a method that is idempotent by definition. A POST publishing a video
        // is exactly the case where guessing wrong posts it twice.
        if (status == 503 && retryAfterSeconds != null && isSafeMethod(httpMethod)) {
            return retryVerdict(retryAfterSeconds, null, attemptIndex, null);
        }

        return Verdict.none();
    }

    /**
     * Message-only classification, for a call that has already run out of attempts. The method
     * passed is the strictest one (a write), so that if this ever gains a live attempt index it
     * refuses a retry rather than granting one it should not: never the other way round.
     */
    public Verdict classifyForMessage(int status, String responseBody, HttpHeaders headers,
                                      String errorPolicyJson) {
        return classify(status, responseBody, headers, errorPolicyJson, maxRetries, "POST");
    }

    /**
     * Statuses that do NOT prove the provider refused the request, so a state-changing method
     * must never be re-sent on one: every 5xx (an intermediary can answer after the origin
     * applied the write), plus 408 Request Timeout and 425 Too Early, where the server may have
     * read the request before answering. Every other 4xx means "refused, nothing happened".
     */
    private static boolean isAmbiguousStatus(int status) {
        return status >= 500 || status == 408 || status == 425;
    }

    /**
     * The RFC 9110 <em>safe</em> methods, deliberately NOT the idempotent ones. PUT and DELETE are
     * idempotent by the spec, yet they change state, and re-sending a state change on a status
     * that does not prove rejection is exactly what this guards against. Do not "fix" this by
     * adding them.
     */
    private static boolean isSafeMethod(String httpMethod) {
        if (httpMethod == null) {
            return false;
        }
        return switch (httpMethod.toUpperCase(Locale.ROOT)) {
            case "GET", "HEAD", "OPTIONS", "TRACE" -> true;
            default -> false;
        };
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Declared rules
    // ─────────────────────────────────────────────────────────────────────────────

    private Verdict matchDeclaredRule(int status, String responseBody, String errorPolicyJson,
                                      Long retryAfterSeconds, int attemptIndex, String httpMethod) {
        if (errorPolicyJson == null || errorPolicyJson.isBlank()) {
            return null;
        }
        JsonNode rules;
        try {
            rules = MAPPER.readTree(errorPolicyJson);
        } catch (Exception e) {
            // A malformed policy must never take an execution down: the call already failed, and
            // the built-in rule below still applies.
            log.warn("Ignoring malformed errorPolicy: {}", e.getMessage());
            return null;
        }
        if (!rules.isArray()) {
            log.warn("Ignoring errorPolicy: expected an array, got {}", rules.getNodeType());
            return null;
        }

        String haystack = responseBody == null ? "" : responseBody.toLowerCase(Locale.ROOT);
        for (JsonNode rule : rules) {
            if (!matches(rule.path("match"), status, haystack)) {
                continue;
            }
            String message = rule.path("message").isTextual() ? rule.path("message").asText() : null;
            // Normalised once: a blank message reaching a verdict would replace the provider's
            // body with an empty string, which is worse than the body it replaced.
            if (message != null && message.isBlank()) {
                message = null;
            }
            String action = rule.path("action").asText("");
            if ("retry".equalsIgnoreCase(action)) {
                // A declared rule asserts "this provider's code means rejected", which is
                // knowledge this engine does not have - EXCEPT that no provider can make a 5xx
                // prove rejection: an intermediary can answer 502/503/504 after the origin
                // already applied the write. The schema refuses `retry` on a declared 5xx
                // STATUS, but the commonest rule shape matches on the body alone and carries no
                // status at all, so it would otherwise fire on any status the provider returns.
                // Publishing a video twice is the one failure this feature must never cause, so
                // the last word is here rather than in the validator.
                if (isAmbiguousStatus(status) && !isSafeMethod(httpMethod)) {
                    log.warn("errorPolicy rule matched a {} on a {} request: not re-sending, "
                                    + "because a 5xx does not prove the request was rejected",
                            status, httpMethod);
                    return message == null ? Verdict.none()
                            : Verdict.userError(message);
                }
                Long declaredWait = rule.hasNonNull("waitMs") ? rule.path("waitMs").asLong() : null;
                return retryVerdict(retryAfterSeconds, declaredWait, attemptIndex, message);
            }
            if ("user_error".equalsIgnoreCase(action)) {
                if (status == 429) {
                    // Legitimate (some providers answer 429 for "blocked", where retrying digs
                    // the hole deeper), but it turns off the platform-wide retry for this API,
                    // and that is worth being able to find in a log rather than deduce.
                    log.info("errorPolicy rule reports a 429 as a user error, so the built-in "
                            + "retry does not apply to it for this API");
                }
                if (message == null) {
                    // The validator refuses this shape in a seed, so it can only arrive from a
                    // bundle or a hand-edited row. Honouring it would stop the built-in 429 retry
                    // AND show the reader nothing new. Skip THIS rule and keep scanning: an
                    // unusable rule must not take the executable rules after it down with it.
                    log.warn("Ignoring errorPolicy rule with action 'user_error' and no message");
                    continue;
                }
                return Verdict.userError(message);
            }
            // An action this build has no code for: skip this rule and keep scanning, so a
            // self-hosted install applying a bundle from a newer cloud keeps every rule it CAN
            // execute, and falls back to the built-in behaviour only for the one it cannot.
            log.warn("Ignoring errorPolicy rule with unknown action '{}'", action);
        }
        return null;
    }

    /**
     * Every criterion present must match. An empty {@code match} matches nothing: a rule that
     * fired on every failure of the API would be a trap, not a shortcut.
     */
    private boolean matches(JsonNode match, int status, String lowercasedBody) {
        if (!match.isObject()) {
            return false;
        }
        // No accumulator shortcut for an empty object: the sawCriterion check below already
        // rejects it, and a second guard doing the same thing lets a test pass while the rule it
        // names is gone.
        boolean sawCriterion = false;

        if (match.has("status")) {
            sawCriterion = true;
            // has(), not hasNonNull(): an explicit null would otherwise drop the criterion and
            // widen the rule to every status, which is what the statusIn branch below refuses.
            if (!match.path("status").isInt() || match.path("status").asInt() != status) {
                return false;
            }
        }
        if (match.has("statusIn")) {
            sawCriterion = true;
            if (!match.path("statusIn").isArray()) {
                // Skipping it would silently widen the rule to whatever criteria remain, which is
                // the opposite of what a malformed declaration should do.
                return false;
            }
            boolean hit = false;
            for (JsonNode s : match.path("statusIn")) {
                // isInt(), not asInt(): "429" as a string parses, and honouring a shape the
                // validator refuses lets a bundle express what a seed cannot.
                if (s.isInt() && s.asInt() == status) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                return false;
            }
        }
        if (match.has("bodyContains")) {
            sawCriterion = true;
            String needle = match.path("bodyContains").isTextual()
                    ? match.path("bodyContains").asText("").toLowerCase(Locale.ROOT)
                    : "";
            // The same floor the validator enforces on a seed. A one or two character needle
            // matches nearly every error body, so a rule carrying one would rewrite EVERY failed
            // call of the API with a single wording - the trap the empty-match guard exists for,
            // arrived at from the other side. Checked here too because a bundle or a hand-edited
            // row never passed through the validator.
            if (needle.length() < MIN_BODY_NEEDLE_LENGTH || !lowercasedBody.contains(needle)) {
                return false;
            }
        }
        return sawCriterion;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Retry arithmetic
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Turn a retryable match into a verdict, applying the two bounds. Exhausted attempts and a
     * wait longer than the cap both degrade to a non-retry that still carries the rule's message
     * and the provider's own delay, so the reader is told what happened rather than seeing a bare
     * 429 body.
     */
    private Verdict retryVerdict(Long retryAfterSeconds, Long declaredWaitMs, int attemptIndex,
                                 String message) {
        if (attemptIndex >= maxRetries) {
            return message == null ? Verdict.none() : Verdict.userError(message);
        }

        long waitMs = resolveWaitMs(retryAfterSeconds, declaredWaitMs, attemptIndex);
        if (waitMs > maxWaitMs) {
            return message == null ? Verdict.none() : Verdict.userError(message);
        }
        return Verdict.retry(waitMs, message);
    }

    /**
     * The provider's {@code Retry-After} wins over anything the seed declared: it is the only value
     * that knows when this particular key stops being throttled.
     */
    private long resolveWaitMs(Long retryAfterSeconds, Long declaredWaitMs, int attemptIndex) {
        if (retryAfterSeconds != null) {
            // Clamped before the multiplication: a hostile or broken Retry-After near Long.MAX
            // would overflow to a negative, and a max(0, ...) would then turn "wait forever" into
            // "retry immediately" - the exact inversion of the invariant.
            long asked = Math.min(retryAfterSeconds, MAX_SANE_RETRY_AFTER_SECONDS) * 1000L;
            // A provider that answers "retry after 0 seconds" while throttling, or a Retry-After
            // date already in the past, would otherwise be re-sent with no pause at all - the
            // hardest possible hammering of the one endpoint that just asked us to stop.
            return Math.max(asked, MIN_WAIT_MS);
        }
        if (declaredWaitMs != null && declaredWaitMs > 0) {
            return declaredWaitMs;
        }
        int idx = Math.min(attemptIndex, DEFAULT_BACKOFF_MS.length - 1);
        return DEFAULT_BACKOFF_MS[idx];
    }

    /**
     * {@code Retry-After} comes in two shapes (RFC 9110): delta-seconds, or an HTTP-date. Both are
     * in the wild, so both are read; a date already in the past yields 0, never a negative wait.
     */
    Long parseRetryAfterSeconds(HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        String raw = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            long seconds = Long.parseLong(value);
            return Math.max(0L, seconds);
        } catch (NumberFormatException ignored) {
            // fall through to the date form
        }
        try {
            ZonedDateTime when = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
            long seconds = Duration.between(Instant.now(), when.toInstant()).getSeconds();
            return Math.max(0L, seconds);
        } catch (Exception e) {
            log.debug("Unparseable Retry-After header '{}'", value);
            return null;
        }
    }
}
