package com.apimarketplace.common.scaling.cache;

import java.math.BigDecimal;

/**
 * Abstraction for a distributed budget cache.
 * <p>
 * InMemory (single-instance mode): backed by ConcurrentHashMap.
 * Redis (multi-instance mode): backed by Redis with atomic Lua scripts.
 * <p>
 * Fail-open strategy: on Redis failure, falls back to in-memory with per-instance
 * budget partitioning to bound over-spend.
 */
public interface DistributedBudgetCache {

    /**
     * Get the current budget value for a key.
     *
     * @param key the budget key
     * @return the current value, or {@code null} if the key does not exist
     */
    BigDecimal get(String key);

    /**
     * Set the budget value for a key.
     *
     * @param key   the budget key
     * @param value the new value
     */
    void set(String key, BigDecimal value);

    /**
     * Atomically decrement the budget by the given amount and return the new value.
     * If the current budget is less than the amount (insufficient funds), the budget
     * is NOT decremented and the current (unchanged) value is returned.
     *
     * @param key    the budget key
     * @param amount the amount to decrement
     * @return the value after decrement, or the unchanged value if insufficient,
     *         or {@code null} if the key does not exist
     */
    BigDecimal decrementAndGet(String key, BigDecimal amount);

    /**
     * Remove a budget entry.
     *
     * @param key the budget key to remove
     */
    void remove(String key);

    /**
     * Check if a budget entry exists.
     *
     * @param key the budget key
     * @return true if the key exists
     */
    boolean exists(String key);

    /**
     * Set the value only if the key does not already exist.
     *
     * @param key   the budget key
     * @param value the value to set
     * @return true if the value was set (key was absent), false if already present
     */
    boolean setIfAbsent(String key, BigDecimal value);

    /**
     * Atomically compare and set: update value only if current value matches expected.
     *
     * @param key      the budget key
     * @param expected the expected current value
     * @param update   the new value to set
     * @return true if the update succeeded
     */
    boolean compareAndSet(String key, BigDecimal expected, BigDecimal update);
}
