package com.apimarketplace.agent.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed sliding window using a Sorted Set (ZSET).
 *
 * <p>Each entry is stored as a ZSET member with:
 * <ul>
 *   <li><b>score</b> = timestamp (epoch millis)</li>
 *   <li><b>member</b> = "{value}:{uuid}" (unique per entry, value encoded in member)</li>
 * </ul>
 *
 * <p>Operations:
 * <ul>
 *   <li>{@code add} → ZADD + PEXPIRE</li>
 *   <li>{@code cleanup} → ZREMRANGEBYSCORE(-inf, cutoff)</li>
 *   <li>{@code getSum} → Lua script iterating members and summing the value prefix</li>
 *   <li>{@code getCount} → ZCARD</li>
 * </ul>
 *
 * <p>Thread safety: Redis operations are atomic; no external synchronization needed
 * (unlike InMemoryRateLimitWindow).</p>
 */
@Slf4j
public class RedisRateLimitWindow implements RateLimitWindow {

    private static final String KEY_PREFIX = "ratelimit:window:";

    /**
     * Lua script to sum all values in the ZSET.
     * Member format is "value:uuid", so we split on ':' and parse the first segment.
     */
    private static final String SUM_SCRIPT =
            "local members = redis.call('ZRANGE', KEYS[1], 0, -1) " +
            "local total = 0 " +
            "for _, m in ipairs(members) do " +
            "  local sep = string.find(m, ':') " +
            "  if sep then " +
            "    total = total + tonumber(string.sub(m, 1, sep - 1)) " +
            "  end " +
            "end " +
            "return total";

    private static final DefaultRedisScript<Long> SUM_REDIS_SCRIPT = new DefaultRedisScript<>(SUM_SCRIPT, Long.class);

    /**
     * Lua script to get the oldest score (lowest) from the ZSET.
     * Returns 0 if the set is empty.
     */
    private static final String OLDEST_SCRIPT =
            "local result = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES') " +
            "if #result >= 2 then return tonumber(result[2]) " +
            "else return 0 end";

    private static final DefaultRedisScript<Long> OLDEST_REDIS_SCRIPT = new DefaultRedisScript<>(OLDEST_SCRIPT, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final String redisKey;
    private final int windowSizeSeconds;
    private final long ttlMillis;

    /**
     * @param redisTemplate shared Redis connection
     * @param windowId      unique identifier for this window (e.g., "global:openai:tokens")
     * @param windowSizeSeconds sliding window duration
     */
    public RedisRateLimitWindow(StringRedisTemplate redisTemplate, String windowId, int windowSizeSeconds) {
        this.redisTemplate = redisTemplate;
        this.redisKey = KEY_PREFIX + windowId;
        this.windowSizeSeconds = windowSizeSeconds;
        // TTL = window size + 30s buffer to allow for clock skew and late cleanup
        this.ttlMillis = TimeUnit.SECONDS.toMillis(windowSizeSeconds) + 30_000L;
    }

    @Override
    public void add(long timestamp, int value) {
        // Member = "value:uuid" to ensure uniqueness
        String member = value + ":" + UUID.randomUUID();
        redisTemplate.opsForZSet().add(redisKey, member, timestamp);
        redisTemplate.expire(redisKey, Duration.ofMillis(ttlMillis));
    }

    @Override
    public void cleanup(long cutoffTimestamp) {
        redisTemplate.opsForZSet().removeRangeByScore(redisKey, Double.NEGATIVE_INFINITY, cutoffTimestamp - 1);
    }

    @Override
    public int getSum() {
        Long result = redisTemplate.execute(SUM_REDIS_SCRIPT, Collections.singletonList(redisKey));
        return result != null ? result.intValue() : 0;
    }

    @Override
    public int getCount() {
        Long count = redisTemplate.opsForZSet().zCard(redisKey);
        return count != null ? count.intValue() : 0;
    }

    @Override
    public boolean isEmpty() {
        return getCount() == 0;
    }

    @Override
    public long getOldestTimestamp() {
        Long result = redisTemplate.execute(OLDEST_REDIS_SCRIPT, Collections.singletonList(redisKey));
        return result != null ? result : 0L;
    }

    @Override
    public long getLastAccessTime() {
        // Redis keys are self-expiring; return current time as a reasonable approximation
        return System.currentTimeMillis();
    }

    /**
     * Returns the Redis key used by this window (for testing/debugging).
     */
    public String getRedisKey() {
        return redisKey;
    }
}
