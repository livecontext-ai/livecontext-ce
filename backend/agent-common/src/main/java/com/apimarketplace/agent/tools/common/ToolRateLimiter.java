package com.apimarketplace.agent.tools.common;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import com.apimarketplace.agent.tools.ToolErrorCode;

/**
 * Shared rate limiter for tool providers that need to prevent LLM perfectionism loops.
 * Tracks consecutive updates and total creates per conversation.
 * Entries auto-expire after a configurable TTL to prevent memory leaks.
 *
 * Used by InterfaceCrudModule, AgentCrudModule, SkillCrudModule.
 */
@Slf4j
public class ToolRateLimiter {

    private static final long DEFAULT_TTL_MINUTES = 5;

    private final ConcurrentHashMap<String, CounterEntry> counters = new ConcurrentHashMap<>();
    private final long ttlMinutes;

    public ToolRateLimiter() {
        this(DEFAULT_TTL_MINUTES);
    }

    public ToolRateLimiter(long ttlMinutes) {
        this.ttlMinutes = ttlMinutes;
    }

    /**
     * Check if a rate limit has been exceeded. Increments the counter automatically.
     *
     * @param key      Unique key (e.g., "tenantId:conversationId")
     * @param maxCount Maximum allowed count (returns failure on maxCount+1)
     * @param errorMsg Error message to return when limit is exceeded
     * @return Empty if within limits, or a failure result if exceeded
     */
    public Optional<ToolExecutionResult> checkLimit(String key, int maxCount, String errorMsg) {
        evictExpired();
        boolean[] rejected = {false};
        counters.compute(key, (k, existing) -> {
            if (existing == null || existing.isExpired(ttlMinutes)) {
                if (maxCount <= 0) { rejected[0] = true; return null; }
                return new CounterEntry(1);
            }
            if (existing.getCount() >= maxCount) {
                rejected[0] = true;
                return existing;
            }
            existing.increment();
            return existing;
        });

        if (rejected[0]) {
            log.warn("Rate limit exceeded for key '{}': {} >= {}", key, getCount(key), maxCount);
            return Optional.of(ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, errorMsg));
        }
        return Optional.empty();
    }

    /**
     * Get the current count for a key without incrementing.
     */
    public int getCount(String key) {
        CounterEntry entry = counters.get(key);
        if (entry == null || entry.isExpired(ttlMinutes)) return 0;
        return entry.getCount();
    }

    /**
     * Reset the counter for a key.
     */
    public void reset(String key) {
        counters.remove(key);
    }

    /**
     * Refund one unit from the counter for a key (floor at 0).
     * Used when a resource created under this counter is deleted within the same
     * window, so the caller regains a slot. No-op if the counter doesn't exist
     * or is already expired. Never goes below zero.
     */
    public void decrement(String key) {
        counters.computeIfPresent(key, (k, entry) -> {
            if (entry.isExpired(ttlMinutes)) return null;
            entry.decrement();
            return entry.getCount() <= 0 ? null : entry;
        });
    }

    /**
     * Evict expired entries to prevent memory leaks.
     * Called lazily on each checkLimit to avoid background threads.
     */
    private void evictExpired() {
        counters.entrySet().removeIf(e -> e.getValue().isExpired(ttlMinutes));
    }

    private static class CounterEntry {
        private int count;
        private final Instant createdAt;

        CounterEntry(int initialCount) {
            this.count = initialCount;
            this.createdAt = Instant.now();
        }

        void increment() {
            count++;
        }

        void decrement() {
            if (count > 0) count--;
        }

        int getCount() {
            return count;
        }

        boolean isExpired(long ttlMinutes) {
            return createdAt.plus(ttlMinutes, ChronoUnit.MINUTES).isBefore(Instant.now());
        }
    }
}
