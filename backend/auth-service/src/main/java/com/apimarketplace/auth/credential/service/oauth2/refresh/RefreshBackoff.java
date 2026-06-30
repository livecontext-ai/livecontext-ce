package com.apimarketplace.auth.credential.service.oauth2.refresh;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Full-jitter exponential backoff (AWS Architecture Blog, "Exponential Backoff And Jitter").
 *
 * <p>Formula: {@code sleep = rand(0, min(cap, base * 2^attempt))}.
 *
 * <p>Why full-jitter rather than decorrelated-jitter or plain-exponential: with N pods each
 * observing the same provider outage, plain-exponential causes all pods to retry at identical
 * deadlines (thundering herd resumes after the outage). Full-jitter spreads retries uniformly
 * across {@code [0, cap]}, flattening the recovery curve.
 *
 * <p>Defaults: base 15 min, cap 24 h, max 5 attempts. Tuned so a provider hiccup resolves in
 * one or two attempts, while a week-long outage doesn't drown us in futile retries. After
 * {@link #MAX_ATTEMPTS} the caller must promote the credential to TERMINAL_CONFIG with
 * {@code reason=max_transient_retries_exceeded}.
 */
@Component
public class RefreshBackoff {

    static final Duration BASE = Duration.ofMinutes(15);
    static final Duration CAP = Duration.ofHours(24);
    static final int MAX_ATTEMPTS = 5;

    /**
     * Compute the next sleep duration for the given zero-based attempt counter.
     * Attempts ≥ {@link #MAX_ATTEMPTS} return {@link #CAP} but the caller is expected to treat
     * that as terminal - the number is only returned so tests and metrics have a value.
     *
     * @param attempt zero-based, i.e. 0 for the first retry after the initial failure.
     */
    public Duration nextSleep(int attempt) {
        int safeAttempt = Math.max(0, attempt);
        // 2^attempt saturates quickly; clamp by cap before multiplying to avoid overflow.
        long cappedCeilingMillis;
        if (safeAttempt >= 20) {
            cappedCeilingMillis = CAP.toMillis();
        } else {
            long exponential = BASE.toMillis() * (1L << safeAttempt);
            cappedCeilingMillis = Math.min(CAP.toMillis(), exponential);
        }
        long jittered = ThreadLocalRandom.current().nextLong(0, cappedCeilingMillis + 1);
        return Duration.ofMillis(jittered);
    }

    /** Returns true if the caller should promote to terminal rather than schedule a retry. */
    public boolean isExhausted(int attempt) {
        return attempt >= MAX_ATTEMPTS;
    }

    public int maxAttempts() {
        return MAX_ATTEMPTS;
    }

    public Duration base() {
        return BASE;
    }

    public Duration cap() {
        return CAP;
    }
}
