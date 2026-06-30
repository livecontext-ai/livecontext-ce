package com.apimarketplace.agent.ratelimit;

import com.apimarketplace.agent.provider.LLMProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression for F1.3 - when {@code acquireWithWait} is queued behind a saturated
 * window, a STOP signal must be honored within ~100ms instead of waiting for the
 * next slot (which can be 60s+ on a heavily-throttled tenant).
 *
 * <p>Pre-fix: a single {@code Thread.sleep(waitTime)} blocked uninterruptibly until
 * the deadline elapsed; clicking STOP did nothing - the LLM provider stayed parked
 * here, then proceeded with the (now-cancelled) request anyway.
 *
 * <p>Post-fix: the sleep is sliced into 100ms chunks and {@code stopCheck} is
 * polled each slice. When it flips true, we throw
 * {@link LLMProviderException} with code {@code rate_limit_stopped} so the caller
 * (e.g. {@code AbstractLLMProvider.completeStreaming}) can surface a
 * STOPPED_BY_USER outcome to the loop.
 */
@DisplayName("ProviderRateLimiter - STOP interrupts blocking wait (F1.3)")
class RateLimiterStopInterruptsWaitTest {

    private RateLimitConfig config;
    private ProviderRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        config = new RateLimitConfig();
        config.setEnabled(true);
        config.setDefaultMode(RateLimitMode.WAIT);
        config.setMaxWaitTimeSeconds(60);  // long deadline so the slice loop is the only exit path

        RateLimitConfig.ProviderLimit limit = new RateLimitConfig.ProviderLimit();
        limit.setTokensPerMinute(100);     // tight window
        limit.setRequestsPerMinute(2);
        config.getProviders().put("test-prov", limit);

        rateLimiter = new ProviderRateLimiter(config);
    }

    @Test
    @DisplayName("Pre-flight stopCheck=true short-circuits before any sleep - fast cancel")
    void preFlightStopShortCircuits() {
        // Saturate the window first so the next request would have to wait
        rateLimiter.checkRateLimit("test-prov", null, "tenant-1", 100, RateLimitMode.WAIT);

        long start = System.currentTimeMillis();
        assertThatThrownBy(() ->
            rateLimiter.acquireWithWait("test-prov", null, "tenant-1", 50, () -> true))
            .isInstanceOf(LLMProviderException.class)
            .satisfies(ex -> {
                LLMProviderException llm = (LLMProviderException) ex;
                assertThat(llm.getErrorCode()).isEqualTo("rate_limit_stopped");
                assertThat(llm.isRetryable()).isFalse();
            });
        long elapsed = System.currentTimeMillis() - start;
        assertThat(elapsed).as("pre-flight stop must not sleep").isLessThan(200);
    }

    @Test
    @DisplayName("Stop flag flipped during slice loop - request unparks within ~100ms")
    void stopMidSleepUnparksWithin100ms() throws InterruptedException {
        // Saturate the window so the next acquireWithWait blocks
        rateLimiter.checkRateLimit("test-prov", null, "tenant-2", 100, RateLimitMode.WAIT);

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicBoolean threwStopped = new AtomicBoolean(false);
        AtomicBoolean threwOther = new AtomicBoolean(false);

        Thread waiter = new Thread(() -> {
            try {
                rateLimiter.acquireWithWait("test-prov", null, "tenant-2", 50, stop::get);
            } catch (LLMProviderException e) {
                if ("rate_limit_stopped".equals(e.getErrorCode())) {
                    threwStopped.set(true);
                } else {
                    threwOther.set(true);
                }
            }
        });
        waiter.start();

        // Let the waiter enter the sleep loop, then flip stop
        Thread.sleep(150);
        long flipAt = System.currentTimeMillis();
        stop.set(true);

        waiter.join(2000);
        long elapsed = System.currentTimeMillis() - flipAt;

        assertThat(waiter.isAlive()).as("waiter must have exited").isFalse();
        assertThat(threwOther.get()).as("must not throw timeout/other code").isFalse();
        assertThat(threwStopped.get()).as("must throw rate_limit_stopped").isTrue();
        assertThat(elapsed)
            .as("STOP must be honored within ~one slice (100ms) plus jitter")
            .isLessThan(500);
    }

    @Test
    @DisplayName("stopCheck=null is tolerated - defaults to never-stop, preserves legacy behavior")
    void nullStopCheckDefaultsToNeverStop() {
        // Should not NPE; should behave like the no-stopCheck overload
        assertThat(config.isEnabled()).isTrue();  // sanity
        rateLimiter.checkRateLimit("test-prov", null, "tenant-3", 50, RateLimitMode.WAIT, null);
        // Reaching here without exception means the null-guard worked
    }

    @Test
    @DisplayName("Uncontended acquisition bypasses the slice loop entirely (no spurious latency)")
    void uncontendedPathBypassesSliceLoop() {
        // Plenty of capacity → tryAcquire passes on the first attempt → no sleep
        long start = System.currentTimeMillis();
        rateLimiter.acquireWithWait("test-prov", null, "tenant-4", 10, () -> false);
        long elapsed = System.currentTimeMillis() - start;
        assertThat(elapsed)
            .as("uncontended path must not enter the slice sleep loop")
            .isLessThan(100);
    }
}
