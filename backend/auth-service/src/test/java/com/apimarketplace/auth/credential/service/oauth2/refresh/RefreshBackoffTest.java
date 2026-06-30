package com.apimarketplace.auth.credential.service.oauth2.refresh;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RefreshBackoff - full-jitter exponential")
class RefreshBackoffTest {

    private final RefreshBackoff backoff = new RefreshBackoff();

    /**
     * Full-jitter must always return a value in {@code [0, min(cap, base * 2^attempt)]}.
     * We sample heavily rather than mock ThreadLocalRandom because the guarantee is statistical -
     * any single draw that exceeds the ceiling is a bug.
     */
    @Test
    @DisplayName("nextSleep(0) stays within [0, base] (1000 samples)")
    void attemptZeroStaysWithinBaseCeiling() {
        long baseMs = backoff.base().toMillis();
        for (int i = 0; i < 1000; i++) {
            Duration sleep = backoff.nextSleep(0);
            assertThat(sleep.toMillis()).isBetween(0L, baseMs);
        }
    }

    @Test
    @DisplayName("nextSleep(2) stays within [0, base * 4] (1000 samples)")
    void attemptTwoRespectsExponentialCeiling() {
        long ceiling = backoff.base().toMillis() * 4;
        assertThat(ceiling).isLessThan(backoff.cap().toMillis());
        for (int i = 0; i < 1000; i++) {
            Duration sleep = backoff.nextSleep(2);
            assertThat(sleep.toMillis()).isBetween(0L, ceiling);
        }
    }

    /**
     * The saturation point: at some attempt the exponential term (base * 2^a) exceeds the cap,
     * so the ceiling must clamp to cap. For base=15m and cap=24h, cap is hit around attempt≈7.
     */
    @Test
    @DisplayName("high attempt clamps ceiling to cap (never exceeds 24h)")
    void highAttemptClampsCeilingToCap() {
        long capMs = backoff.cap().toMillis();
        for (int i = 0; i < 1000; i++) {
            assertThat(backoff.nextSleep(15).toMillis()).isBetween(0L, capMs);
            assertThat(backoff.nextSleep(20).toMillis()).isBetween(0L, capMs);
            assertThat(backoff.nextSleep(50).toMillis()).isBetween(0L, capMs);
        }
    }

    /**
     * Negative or weird attempt values must be tolerated - the caller may pass an uninitialised
     * counter and we'd rather clamp than NPE or overflow.
     */
    @Test
    @DisplayName("negative attempt is clamped to 0 - stays within base")
    void negativeAttemptClamps() {
        long baseMs = backoff.base().toMillis();
        for (int i = 0; i < 100; i++) {
            assertThat(backoff.nextSleep(-5).toMillis()).isBetween(0L, baseMs);
        }
    }

    @Test
    @DisplayName("isExhausted is true at MAX_ATTEMPTS and above, false below")
    void isExhaustedBoundary() {
        int max = backoff.maxAttempts();
        assertThat(backoff.isExhausted(0)).isFalse();
        assertThat(backoff.isExhausted(max - 1)).isFalse();
        assertThat(backoff.isExhausted(max)).isTrue();
        assertThat(backoff.isExhausted(max + 100)).isTrue();
    }

    /**
     * Sanity check on the published constants. The 15-minute base / 24-hour cap was chosen so a
     * single transient blip resolves within ~30 min but a multi-day outage doesn't drown us in
     * retries - changing these numbers requires re-thinking the ops runbook, not just this test.
     */
    @Test
    @DisplayName("defaults are base=15m, cap=24h, max=5")
    void defaultsMatchDesign() {
        assertThat(backoff.base()).isEqualTo(Duration.ofMinutes(15));
        assertThat(backoff.cap()).isEqualTo(Duration.ofHours(24));
        assertThat(backoff.maxAttempts()).isEqualTo(5);
    }

    /**
     * Statistical spread check - 200 samples at attempt=2 should span at least 25% of the range
     * (max - min > baseMs). Full-jitter guarantees uniform distribution; without jitter, all N
     * pods would retry at the same deadline (thundering herd).
     */
    @Test
    @DisplayName("attempt=2 samples spread across the ceiling range (jitter is alive)")
    void jitterSpreadsUniformly() {
        long ceiling = backoff.base().toMillis() * 4;
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (int i = 0; i < 200; i++) {
            long ms = backoff.nextSleep(2).toMillis();
            min = Math.min(min, ms);
            max = Math.max(max, ms);
        }
        // Expect at least a quarter of the range spanned - a much weaker assertion than exact
        // distribution, but strong enough to catch a bug where jitter is accidentally removed.
        assertThat(max - min).isGreaterThan(ceiling / 4);
    }
}
