package com.apimarketplace.orchestrator.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LC-042, audit round 2: the per-token and per-owner windows were per-instance, so N orchestrator
 * replicas multiplied every ceiling by N. With a cluster counter (Redis in scaling.backend=redis)
 * two limiters share one budget; without Redis (CE) or when it fails, the in-memory window applies.
 */
@DisplayName("Webhook rate limit - cluster-wide windows (LC-042 round 2)")
class WebhookClusterRateLimitTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-23T10:00:30Z"), ZoneOffset.UTC);

    /** A shared in-memory stand-in for Redis INCR, keyed like the real counter. */
    private static final class SharedCounter implements WebhookDispatchService.WebhookRateLimiter.ClusterFireCounter {
        final Map<String, AtomicLong> counts = new ConcurrentHashMap<>();

        @Override
        public Long hit(String key, Duration window, long nowMillis) {
            return counts.computeIfAbsent(key + ":" + nowMillis / window.toMillis(), k -> new AtomicLong())
                    .incrementAndGet();
        }
    }

    private static WebhookDispatchService.WebhookRateLimiter limiter(int perToken, int perOwner) {
        return new WebhookDispatchService.WebhookRateLimiter(CLOCK, perToken, perOwner, 20, 200, Duration.ofSeconds(90));
    }

    @Test
    @DisplayName("two replicas share ONE per-token budget through the cluster counter")
    void replicasShareTheTokenBudget() {
        SharedCounter redis = new SharedCounter();
        var replicaA = limiter(4, 600).withClusterCounter(redis);
        var replicaB = limiter(4, 600).withClusterCounter(redis);

        int allowed = 0;
        for (int i = 0; i < 5; i++) {
            if (replicaA.allowToken("tok")) allowed++;
            if (replicaB.allowToken("tok")) allowed++;
        }

        // Per-instance windows would have allowed 4 + 4 = 8.
        assertThat(allowed).isEqualTo(4);
        assertThat(redis.counts.keySet()).allSatisfy(k -> assertThat(k).doesNotContain("tok:"));
    }

    @Test
    @DisplayName("two replicas share ONE per-owner budget")
    void replicasShareTheOwnerBudget() {
        SharedCounter redis = new SharedCounter();
        var replicaA = limiter(1000, 3).withClusterCounter(redis);
        var replicaB = limiter(1000, 3).withClusterCounter(redis);

        assertThat(replicaA.allowOwner("owner-1")).isTrue();
        assertThat(replicaB.allowOwner("owner-1")).isTrue();
        assertThat(replicaA.allowOwner("owner-1")).isTrue();
        assertThat(replicaB.allowOwner("owner-1")).isFalse();
    }

    @Test
    @DisplayName("a failing cluster counter falls back to the in-memory window instead of failing open")
    void failingCounterFallsBackToLocalWindow() {
        var limiter = limiter(2, 600).withClusterCounter((key, window, now) -> null);

        assertThat(limiter.allowToken("tok")).isTrue();
        assertThat(limiter.allowToken("tok")).isTrue();
        assertThat(limiter.allowToken("tok")).isFalse();
    }

    @Test
    @DisplayName("the Redis counter INCRs the bucket and sets its TTL in ONE atomic script call")
    void redisCounterIncrementsAndExpiresAtomically() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(eq(WebhookDispatchService.RedisFireCounter.INCR_WITH_TTL),
                eq(java.util.List.of("rl:webhook:token:abc:2")), eq("120000")))
                .thenReturn(1L, 2L);

        var counter = new WebhookDispatchService.RedisFireCounter(redis);
        assertThat(counter.hit("token:abc", Duration.ofMinutes(1), 120_000L)).isEqualTo(1L);
        assertThat(counter.hit("token:abc", Duration.ofMinutes(1), 120_000L)).isEqualTo(2L);

        // Pre-fix: a separate INCR then EXPIRE; a failure between them left a bucket without TTL.
        verify(redis, never()).opsForValue();
        verify(redis, never()).expire(anyString(), any(Duration.class));
        assertThat(WebhookDispatchService.RedisFireCounter.INCR_WITH_TTL.getScriptAsString())
                .contains("INCR").contains("PEXPIRE");
    }

    @Test
    @DisplayName("a Redis error answers null so the caller uses its local window")
    void redisErrorAnswersNull() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                org.mockito.ArgumentMatchers.<java.util.List<String>>any(), any(Object[].class)))
                .thenThrow(new IllegalStateException("down"));

        assertThat(new WebhookDispatchService.RedisFireCounter(redis)
                .hit("owner:o", Duration.ofMinutes(1), 0L)).isNull();
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("the cluster counter is only armed when scaling.backend=redis and Redis is present")
    void clusterCounterOnlyInRedisMode() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        assertThat(WebhookDispatchService.RedisFireCounter.forBackend("redis", redis)).isNotNull();
        assertThat(WebhookDispatchService.RedisFireCounter.forBackend("memory", redis)).isNull();
        assertThat(WebhookDispatchService.RedisFireCounter.forBackend("redis", null)).isNull();
    }
}
