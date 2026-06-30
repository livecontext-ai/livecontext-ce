package com.apimarketplace.agent.ratelimit;

/**
 * How to handle rate limit when exceeded.
 */
public enum RateLimitMode {
    /**
     * Throw exception immediately when limit exceeded.
     * Fast fail, caller must handle retry.
     */
    FAIL_FAST,

    /**
     * Wait (block thread) until rate limit allows.
     * Simple for caller but blocks thread resources.
     * Max wait time configurable.
     */
    WAIT,

    /**
     * Return result object indicating allowed/blocked.
     * Non-blocking, caller decides what to do.
     * Best for async/reactive code.
     */
    TRY_ACQUIRE,

    /**
     * Queue request and process when rate allows.
     * Returns immediately with a CompletableFuture.
     * Best for high throughput scenarios.
     */
    QUEUE
}
