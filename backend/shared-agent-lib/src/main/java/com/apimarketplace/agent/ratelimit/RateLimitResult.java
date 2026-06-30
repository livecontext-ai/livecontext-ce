package com.apimarketplace.agent.ratelimit;

import java.time.Duration;

/**
 * Result of a rate limit check.
 * Used for non-blocking rate limiting (no exceptions thrown).
 */
public record RateLimitResult(
    /**
     * Whether the request is allowed to proceed.
     */
    boolean allowed,

    /**
     * Time to wait before retrying (if not allowed).
     * Duration.ZERO if allowed or if wait time cannot be calculated.
     */
    Duration waitTime,

    /**
     * Reason for blocking (if not allowed).
     * Null if allowed.
     */
    String reason,

    /**
     * Error code for logging/metrics.
     * Null if allowed.
     */
    String errorCode,

    /**
     * Current usage percentage (0-100).
     */
    double usagePercent,

    /**
     * Remaining capacity before limit.
     */
    int remainingCapacity
) {

    /**
     * Create an "allowed" result.
     */
    public static RateLimitResult allowed(double usagePercent, int remainingCapacity) {
        return new RateLimitResult(true, Duration.ZERO, null, null, usagePercent, remainingCapacity);
    }

    /**
     * Create a "blocked" result.
     */
    public static RateLimitResult blocked(Duration waitTime, String reason, String errorCode, double usagePercent) {
        return new RateLimitResult(false, waitTime, reason, errorCode, usagePercent, 0);
    }

    /**
     * Is the request allowed?
     */
    public boolean isAllowed() {
        return allowed;
    }

    /**
     * Is the request blocked?
     */
    public boolean isBlocked() {
        return !allowed;
    }

    /**
     * Should we warn about high usage (>70%)?
     */
    public boolean isWarning() {
        return usagePercent >= 70 && usagePercent < 90;
    }

    /**
     * Is usage critical (>90%)?
     */
    public boolean isCritical() {
        return usagePercent >= 90;
    }
}
