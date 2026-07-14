package com.apimarketplace.orchestrator.trigger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ShareInvocationLimiter")
class ShareInvocationLimiterTest {

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;

    private ShareInvocationLimiter limiter;
    private Map<String, Long> counters;

    @BeforeEach
    void setUp() {
        limiter = new ShareInvocationLimiter(redis);
        ReflectionTestUtils.setField(limiter, "enabled", true);
        ReflectionTestUtils.setField(limiter, "perTokenDailyLimit", 2L);
        ReflectionTestUtils.setField(limiter, "perOwnerDailyLimit", 3L);
        counters = new HashMap<>();
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        lenient().when(valueOps.increment(any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            long v = counters.getOrDefault(key, 0L) + 1;
            counters.put(key, v);
            return v;
        });
    }

    @Test
    @DisplayName("allows invocations up to the per-token limit, then denies")
    void deniesOverPerTokenLimit() {
        assertThat(limiter.tryAcquire("tok", "owner")).isTrue();  // token=1, owner=1
        assertThat(limiter.tryAcquire("tok", "owner")).isTrue();  // token=2, owner=2
        assertThat(limiter.tryAcquire("tok", "owner")).isFalse(); // token=3 > 2 -> deny
    }

    @Test
    @DisplayName("denies when the per-owner limit is exceeded even if the per-link limit is not")
    void deniesOverPerOwnerLimit() {
        // Three different links, one owner, per-owner limit = 3.
        assertThat(limiter.tryAcquire("a", "owner")).isTrue();  // owner=1
        assertThat(limiter.tryAcquire("b", "owner")).isTrue();  // owner=2
        assertThat(limiter.tryAcquire("c", "owner")).isTrue();  // owner=3
        assertThat(limiter.tryAcquire("d", "owner")).isFalse(); // owner=4 > 3 -> deny
    }

    @Test
    @DisplayName("an exhausted link does NOT increment the owner counter (per-link checked first)")
    void exhaustedLinkDoesNotChargeOwner() {
        limiter.tryAcquire("tok", "owner"); // token=1, owner=1
        limiter.tryAcquire("tok", "owner"); // token=2, owner=2
        limiter.tryAcquire("tok", "owner"); // token=3 -> deny BEFORE owner increment
        assertThat(counters.get("share:invoke:owner:owner")).isEqualTo(2L);
    }

    @Test
    @DisplayName("sets a 24h TTL on the first hit of each counter")
    void setsTtlOnFirstHit() {
        limiter.tryAcquire("tok", "owner");
        org.mockito.Mockito.verify(redis).expire(eq("share:invoke:token:tok"), any());
        org.mockito.Mockito.verify(redis).expire(eq("share:invoke:owner:owner"), any());
    }

    @Test
    @DisplayName("disabled limiter always allows")
    void disabledAllows() {
        ReflectionTestUtils.setField(limiter, "enabled", false);
        for (int i = 0; i < 100; i++) {
            assertThat(limiter.tryAcquire("tok", "owner")).isTrue();
        }
    }

    @Test
    @DisplayName("fails OPEN on a Redis error (availability over a bounded abuse window)")
    void failsOpenOnRedisError() {
        when(valueOps.increment(any())).thenThrow(new RuntimeException("redis down"));
        assertThat(limiter.tryAcquire("tok", "owner")).isTrue();
    }
}
