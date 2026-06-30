package com.apimarketplace.agent.retry;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Generic retry policy for transient external failures (LLM 429/5xx, MCP HTTP 5xx,
 * upstream timeouts).
 *
 * <p>Strategy: full-jitter exponential backoff (AWS recipe) with optional server-side
 * retry-after hint that overrides the computed backoff when present. Budget is
 * <strong>clock-deadline</strong> not cumulative sleep - a 60s budget allows multiple
 * retries even after long server-hint waits, so long as the wall clock hasn't passed.
 *
 * <p>Config (all under {@code orchestrator.llm-retry.*}):
 * <ul>
 *   <li>{@code enabled} (default true) - kill-switch</li>
 *   <li>{@code max-attempts} (default 3) - total attempts including the first call</li>
 *   <li>{@code total-budget-seconds} (default 60) - wall-clock cap</li>
 *   <li>{@code base-delay-ms} (default 500) - exponential base for jitter</li>
 *   <li>{@code max-delay-ms} (default 30000) - per-sleep ceiling</li>
 * </ul>
 *
 * <p><strong>Invariant - never wrap original exceptions.</strong> On internal
 * failure (Thread interrupt, MeterRegistry NPE on test fixtures), the last attempt's
 * exception propagates verbatim. Test:
 * {@code retryPolicyInternalErrorPropagatesOriginalException}.
 */
@Slf4j
@Component
public class RetryPolicy {

    private final boolean enabled;
    private final int maxAttempts;
    private final Duration baseDelay;
    private final Duration maxDelay;
    private final Duration totalBudget;
    private final MeterRegistry meters;

    @Autowired
    public RetryPolicy(
            @Value("${orchestrator.llm-retry.enabled:true}") boolean enabled,
            @Value("${orchestrator.llm-retry.max-attempts:3}") int maxAttempts,
            @Value("${orchestrator.llm-retry.total-budget-seconds:60}") int totalBudgetSeconds,
            @Value("${orchestrator.llm-retry.base-delay-ms:500}") long baseDelayMs,
            @Value("${orchestrator.llm-retry.max-delay-ms:30000}") long maxDelayMs,
            @Autowired(required = false) MeterRegistry meters) {
        this.enabled = enabled;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseDelay = Duration.ofMillis(Math.max(1, baseDelayMs));
        this.maxDelay = Duration.ofMillis(Math.max(baseDelayMs, maxDelayMs));
        this.totalBudget = Duration.ofSeconds(Math.max(1, totalBudgetSeconds));
        this.meters = meters;
        log.info("RetryPolicy initialized: enabled={}, maxAttempts={}, totalBudget={}s, baseDelay={}ms, maxDelay={}ms",
            enabled, this.maxAttempts, totalBudget.toSeconds(), baseDelay.toMillis(), maxDelay.toMillis());
    }

    /**
     * Execute {@code work}, retrying on retryable throwables up to the policy limits.
     *
     * @param opName              telemetry tag, e.g. {@code "llm.complete.google"}
     * @param work                the operation to execute
     * @param isRetryable         decides whether a thrown exception triggers retry
     * @param retryAfterHint      optional server-side hint per attempt's exception (header / body)
     */
    public <T> T execute(String opName, Supplier<T> work,
                         Predicate<Throwable> isRetryable,
                         Function<Throwable, Optional<Duration>> retryAfterHint) {
        if (!enabled) {
            return work.get();
        }
        Instant deadline = Instant.now().plus(totalBudget);
        Throwable last = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                T result = work.get();
                if (attempt > 0) {
                    incCounter("agent_retry_recovered_total", "op", opName, "attempt", String.valueOf(attempt));
                }
                return result;
            } catch (Throwable t) {
                last = t;
                String code = errorCode(t);
                incCounter("agent_retry_attempts_total", "op", opName, "code", code, "attempt", String.valueOf(attempt));
                boolean retryable;
                try {
                    retryable = isRetryable.test(t);
                } catch (Throwable predicateError) {
                    log.warn("[RetryPolicy] isRetryable predicate threw - treating as non-retryable: op={}, predicateErr={}",
                        opName, predicateError.getMessage());
                    retryable = false;
                }
                if (!retryable || attempt == maxAttempts - 1) {
                    break;
                }
                Optional<Duration> hint;
                try {
                    hint = retryAfterHint.apply(t);
                } catch (Throwable hintError) {
                    hint = Optional.empty();
                }
                final int attemptForBackoff = attempt;
                Duration sleep = hint.orElseGet(() -> jitteredBackoff(attemptForBackoff));
                Instant wakeAt = Instant.now().plus(sleep);
                if (wakeAt.isAfter(deadline)) {
                    log.info("[RetryPolicy] Budget would be exceeded - giving up: op={}, sleep={}ms, deadlineRemaining={}ms",
                        opName, sleep.toMillis(), Duration.between(Instant.now(), deadline).toMillis());
                    break;
                }
                if (hint.isPresent()) {
                    incCounter("agent_retry_after_hint_observed_total", "op", opName);
                }
                log.info("[RetryPolicy] Attempt {}/{} for op={} after {}ms (hint={}, code={}): retrying",
                    attempt + 1, maxAttempts, opName, sleep.toMillis(), hint.isPresent(), code);
                try {
                    Thread.sleep(sleep.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    incCounter("agent_retry_interrupted_total", "op", opName);
                    // Invariant: never wrap original exception
                    throwUnchecked(last);
                }
            }
        }
        incCounter("agent_retry_exhausted_total", "op", opName, "code", errorCode(last));
        log.warn("[RetryPolicy] Exhausted after {} attempts (budget={}s): op={}, lastError={}",
            maxAttempts, totalBudget.toSeconds(), opName, last != null ? last.getMessage() : "null");
        throwUnchecked(last);
        // Unreachable - throwUnchecked always throws
        return null;
    }

    Duration jitteredBackoff(int attempt) {
        long capMs = maxDelay.toMillis();
        long expMs;
        if (attempt >= 20) {
            expMs = capMs;
        } else {
            expMs = Math.min(capMs, baseDelay.toMillis() * (1L << attempt));
        }
        long jittered = ThreadLocalRandom.current().nextLong(0, expMs + 1);
        return Duration.ofMillis(jittered);
    }

    private static String errorCode(Throwable t) {
        if (t == null) return "null";
        try {
            // LLMProviderException-like getErrorCode() via reflection-light pattern: just classname
            String simple = t.getClass().getSimpleName();
            // If it has a method getErrorCode(), use that; otherwise fall back to classname
            try {
                var m = t.getClass().getMethod("getErrorCode");
                Object code = m.invoke(t);
                if (code instanceof String s && !s.isBlank()) return s;
            } catch (NoSuchMethodException ignored) {
                // not a typed-code exception
            }
            return simple;
        } catch (Throwable inner) {
            return "unknown";
        }
    }

    private void incCounter(String name, String... tags) {
        if (meters == null) return;
        try {
            meters.counter(name, Tags.of(tags)).increment();
        } catch (Throwable t) {
            // Metrics must never break the retry path
            log.debug("[RetryPolicy] metric '{}' update failed: {}", name, t.getMessage());
        }
    }

    private static void throwUnchecked(Throwable t) {
        if (t == null) {
            throw new IllegalStateException("RetryPolicy exhausted with no captured exception");
        }
        if (t instanceof RuntimeException re) throw re;
        if (t instanceof Error err) throw err;
        throw new RuntimeException(t);
    }
}
