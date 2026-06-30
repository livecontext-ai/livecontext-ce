package com.apimarketplace.agent.ratelimit;

/**
 * Abstraction for a sliding window used in rate limiting.
 * Implementations track timestamped values within a configurable time window.
 *
 * Two implementations:
 * - {@link InMemoryRateLimitWindow}: In-process LinkedList (single-instance default)
 * - {@link RedisRateLimitWindow}: Redis ZSET (multi-instance, activated by scaling.backend=redis)
 */
public interface RateLimitWindow {

    /**
     * Add a timestamped entry to the window.
     *
     * @param timestamp epoch millis
     * @param value     the value (e.g., token count or 1 for request count)
     */
    void add(long timestamp, int value);

    /**
     * Remove entries older than the cutoff timestamp.
     *
     * @param cutoffTimestamp epoch millis; entries with timestamp < cutoff are removed
     */
    void cleanup(long cutoffTimestamp);

    /**
     * Sum all values currently in the window.
     */
    int getSum();

    /**
     * Count of entries currently in the window.
     */
    int getCount();

    /**
     * Whether the window is empty.
     */
    boolean isEmpty();

    /**
     * Timestamp of the oldest entry, or 0 if empty.
     */
    long getOldestTimestamp();

    /**
     * Last time this window was accessed (add or cleanup), epoch millis.
     */
    long getLastAccessTime();

}
