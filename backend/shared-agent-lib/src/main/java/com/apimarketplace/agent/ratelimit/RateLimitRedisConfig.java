package com.apimarketplace.agent.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Redis-backed rate limit configuration, activated when {@code scaling.backend=redis}.
 *
 * <p>Provides a {@link RateLimitWindowFactory} that creates {@link RedisRateLimitWindow}
 * instances backed by Redis ZSETs, enabling rate limiting to work across multiple
 * application instances.</p>
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "scaling.backend", havingValue = "redis")
public class RateLimitRedisConfig {

    private static final String LOCK_KEY_PREFIX = "ratelimit:lock:";
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);
    private static final Duration LOCK_WAIT_TIMEOUT = Duration.ofSeconds(5);
    private static final long LOCK_RETRY_SLEEP_MS = 10L;

    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
        "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "return redis.call('del', KEYS[1]) " +
            "else return 0 end",
        Long.class);

    @Bean
    public RateLimitWindowFactory rateLimitWindowFactory(StringRedisTemplate redisTemplate) {
        log.info("Redis-backed rate limiting enabled (scaling.backend=redis)");
        return new RateLimitWindowFactory() {
            @Override
            public RateLimitWindow create(String windowId, int windowSizeSeconds) {
                return new RedisRateLimitWindow(redisTemplate, windowId, windowSizeSeconds);
            }

            @Override
            public <T> T withAtomicReservationLock(String lockKey, Supplier<T> operation) {
                String redisLockKey = LOCK_KEY_PREFIX + lockKey;
                String token = UUID.randomUUID().toString();
                long deadline = System.nanoTime() + LOCK_WAIT_TIMEOUT.toNanos();
                while (System.nanoTime() < deadline) {
                    Boolean acquired = redisTemplate.opsForValue()
                        .setIfAbsent(redisLockKey, token, LOCK_TTL);
                    if (Boolean.TRUE.equals(acquired)) {
                        try {
                            return operation.get();
                        } finally {
                            redisTemplate.execute(
                                RELEASE_LOCK_SCRIPT,
                                Collections.singletonList(redisLockKey),
                                token);
                        }
                    }
                    try {
                        Thread.sleep(LOCK_RETRY_SLEEP_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted while waiting for Redis rate-limit lock", e);
                    }
                }
                throw new IllegalStateException("Timed out waiting for Redis rate-limit lock: " + lockKey);
            }
        };
    }
}
