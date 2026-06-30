package com.apimarketplace.agent.retry;

import com.apimarketplace.agent.provider.LLMProviderException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3 regression tests - RetryPolicy core behaviors.
 *
 * Cross-references:
 * - LLMProviderException.rateLimited(retryAfter): retryable=true, with optional hint
 * - LLMProviderException.transientFailure(message, retryAfter): retryable=true
 *
 * Bug this defends against:
 * Run run_<id> (2026-04-29) - Google API returned 4 sub-second 429s on
 * gemini-3-pro-preview. AbstractLLMProvider.handleHttpError threw retryable but no caller
 * read isRetryable(). Phase 3 wires AgentLoopExecutor to use this RetryPolicy.
 */
class RetryPolicyTest {

    private final Predicate<Throwable> isRetryable = t ->
        t instanceof LLMProviderException ex && ex.isRetryable();
    private final Function<Throwable, Optional<Duration>> retryAfterHint = t ->
        t instanceof LLMProviderException ex ? ex.retryAfter() : Optional.empty();

    private RetryPolicy newPolicy(int maxAttempts, int budgetSeconds) {
        return new RetryPolicy(true, maxAttempts, budgetSeconds, 10, 100, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("retry budget exhausted surfaces last exception, not a wrapper")
    void retryBudgetExhaustedSurfacesLastException() {
        RetryPolicy policy = newPolicy(3, 60);
        AtomicInteger attempts = new AtomicInteger();

        LLMProviderException thrown = assertThrows(LLMProviderException.class, () ->
            policy.execute("test.op", () -> {
                attempts.incrementAndGet();
                throw LLMProviderException.rateLimited("test", null);
            }, isRetryable, retryAfterHint));

        assertEquals(3, attempts.get(), "exactly maxAttempts attempts made");
        assertEquals("rate_limit", thrown.getErrorCode(),
            "original rate-limit code propagates verbatim");
    }

    @Test
    @DisplayName("retry policy internal error propagates the original LLM exception")
    void retryPolicyInternalErrorPropagatesOriginalException() {
        // Even when an internal failure (e.g. metrics misconfiguration) would otherwise
        // mask the root cause, the original exception MUST propagate verbatim.
        RetryPolicy policy = new RetryPolicy(true, 1, 60, 10, 100, null);
        LLMProviderException original = LLMProviderException.unauthorized("test");

        LLMProviderException thrown = assertThrows(LLMProviderException.class, () ->
            policy.execute("test.op", () -> { throw original; }, isRetryable, retryAfterHint));

        assertSame(original, thrown, "exact same exception instance propagates");
    }

    @Test
    @DisplayName("non-retryable exception is not retried - fails on first attempt")
    void httpFourZeroOneIsNotRetried() {
        RetryPolicy policy = newPolicy(3, 60);
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(LLMProviderException.class, () ->
            policy.execute("test.op", () -> {
                attempts.incrementAndGet();
                throw LLMProviderException.unauthorized("test");
            }, isRetryable, retryAfterHint));

        assertEquals(1, attempts.get(), "401 is not retryable; only one attempt");
    }

    @Test
    @DisplayName("retry recovers when work succeeds on second attempt")
    void retryRecoversOnSecondAttempt() {
        RetryPolicy policy = newPolicy(3, 60);
        AtomicInteger attempts = new AtomicInteger();

        String result = policy.execute("test.op", () -> {
            int n = attempts.incrementAndGet();
            if (n == 1) throw LLMProviderException.rateLimited("test", null);
            return "ok";
        }, isRetryable, retryAfterHint);

        assertEquals("ok", result);
        assertEquals(2, attempts.get());
    }

    @Test
    @DisplayName("retry-after hint is honored over default backoff")
    void retryAfterHintHonored() {
        // 1ms hint should be much faster than the 10ms baseDelay backoff
        RetryPolicy policy = newPolicy(2, 60);
        AtomicInteger attempts = new AtomicInteger();
        long start = System.currentTimeMillis();

        String result = policy.execute("test.op", () -> {
            int n = attempts.incrementAndGet();
            if (n == 1) throw LLMProviderException.rateLimited("test", Duration.ofMillis(1));
            return "ok";
        }, isRetryable, retryAfterHint);

        long elapsed = System.currentTimeMillis() - start;
        assertEquals("ok", result);
        assertTrue(elapsed < 500, "with 1ms hint, retry should be quick (got " + elapsed + "ms)");
    }

    @Test
    @DisplayName("budget-clock check skips retry when remaining wall-clock < required sleep")
    void budgetClockCheckSkipsLastRetryWhenSleepWouldExceedDeadline() {
        // Budget=1s; first attempt fails immediately with a 5-second hint. The retry
        // would push past the deadline, so the policy gives up without sleeping.
        RetryPolicy policy = newPolicy(3, 1);
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(LLMProviderException.class, () ->
            policy.execute("test.op", () -> {
                attempts.incrementAndGet();
                throw LLMProviderException.rateLimited("test", Duration.ofSeconds(5));
            }, isRetryable, retryAfterHint));

        assertEquals(1, attempts.get(),
            "deadline check prevents the retry sleep from happening");
    }

    @Test
    @DisplayName("transient HTTP 503 is classified as retryable and recovers")
    void httpFiveZeroThreeIsClassifiedAsTransientAndRetried() {
        RetryPolicy policy = newPolicy(3, 60);
        AtomicInteger attempts = new AtomicInteger();

        String result = policy.execute("test.op", () -> {
            int n = attempts.incrementAndGet();
            if (n == 1) throw LLMProviderException.transientFailure("google", "HTTP 503", null);
            return "ok";
        }, isRetryable, retryAfterHint);

        assertEquals("ok", result);
        assertEquals(2, attempts.get());
    }

    @Test
    @DisplayName("interrupted thread aborts loop and preserves interrupt flag")
    void interruptedThreadAbortsLoopAndPreservesInterruptFlag() {
        RetryPolicy policy = new RetryPolicy(true, 5, 60, 10_000, 30_000, new SimpleMeterRegistry());
        Thread.currentThread().interrupt();
        try {
            assertThrows(LLMProviderException.class, () ->
                policy.execute("test.op", () -> {
                    throw LLMProviderException.rateLimited("test", Duration.ofSeconds(10));
                }, isRetryable, retryAfterHint));
            assertTrue(Thread.currentThread().isInterrupted(),
                "interrupt flag preserved after exit");
        } finally {
            // Clear flag for subsequent tests
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("disabled policy executes work directly without retry instrumentation")
    void disabledPolicyExecutesDirectly() {
        RetryPolicy disabled = new RetryPolicy(false, 3, 60, 10, 100, new SimpleMeterRegistry());
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(LLMProviderException.class, () ->
            disabled.execute("test.op", () -> {
                attempts.incrementAndGet();
                throw LLMProviderException.rateLimited("test", null);
            }, isRetryable, retryAfterHint));

        assertEquals(1, attempts.get(), "no retry when disabled");
    }
}
