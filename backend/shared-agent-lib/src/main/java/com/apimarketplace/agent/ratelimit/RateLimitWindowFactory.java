package com.apimarketplace.agent.ratelimit;

import java.util.function.Supplier;

/**
 * Factory for creating {@link RateLimitWindow} instances.
 *
 * <p>Default implementation creates {@link InMemoryRateLimitWindow}.
 * When {@code scaling.backend=redis}, the {@link RateLimitRedisConfig} provides
 * a factory that creates {@link RedisRateLimitWindow} instances instead.</p>
 */
@FunctionalInterface
public interface RateLimitWindowFactory {

    /**
     * Create a new rate limit window.
     *
     * @param windowId          unique logical identifier for this window
     *                          (e.g., "global:openai:tokens" or "tenant:t1:openai:rpm").
     *                          In-memory impl ignores this; Redis impl uses it as the ZSET key.
     * @param windowSizeSeconds sliding window duration in seconds
     * @return a new RateLimitWindow instance
     */
    RateLimitWindow create(String windowId, int windowSizeSeconds);

    /**
     * Runs a check-and-reserve critical section under a backend-specific lock.
     *
     * <p>The default is a no-op because {@link ProviderRateLimiter} also keeps a
     * JVM-local lock. Redis-backed factories override this to serialize the
     * compound operation across horizontally scaled instances.</p>
     */
    default <T> T withAtomicReservationLock(String lockKey, Supplier<T> operation) {
        return operation.get();
    }
}
