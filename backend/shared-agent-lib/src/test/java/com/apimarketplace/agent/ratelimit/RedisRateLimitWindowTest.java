package com.apimarketplace.agent.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RedisRateLimitWindow}.
 * Mocks RedisTemplate to verify ZADD/ZREMRANGEBYSCORE/ZCARD patterns.
 */
@ExtendWith(MockitoExtension.class)
class RedisRateLimitWindowTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    private RedisRateLimitWindow window;

    private static final String WINDOW_ID = "global:openai:tokens";
    private static final String EXPECTED_KEY = "ratelimit:window:" + WINDOW_ID;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        window = new RedisRateLimitWindow(redisTemplate, WINDOW_ID, 60);
    }

    @Test
    void shouldHaveCorrectRedisKey() {
        assertEquals(EXPECTED_KEY, window.getRedisKey());
    }

    @Test
    void shouldAddEntryViaZADD() {
        long timestamp = 1000L;
        int value = 500;

        window.add(timestamp, value);

        // Verify ZADD was called with correct key, score=timestamp
        ArgumentCaptor<String> memberCaptor = ArgumentCaptor.forClass(String.class);
        verify(zSetOperations).add(eq(EXPECTED_KEY), memberCaptor.capture(), eq((double) timestamp));

        // Member should start with "500:" (value prefix)
        String member = memberCaptor.getValue();
        assertTrue(member.startsWith("500:"), "Member should start with value prefix, got: " + member);

        // Verify TTL was set (60s window + 30s buffer = 90s)
        verify(redisTemplate).expire(eq(EXPECTED_KEY), eq(Duration.ofMillis(90_000L)));
    }

    @Test
    void shouldCleanupViaZREMRANGEBYSCORE() {
        long cutoff = 5000L;

        window.cleanup(cutoff);

        // ZREMRANGEBYSCORE removes entries with score < cutoff
        verify(zSetOperations).removeRangeByScore(EXPECTED_KEY, Double.NEGATIVE_INFINITY, cutoff - 1);
    }

    @Test
    void shouldGetSumViaLuaScript() {
        when(redisTemplate.execute(any(RedisScript.class), eq(Collections.singletonList(EXPECTED_KEY))))
                .thenReturn(1500L);

        int sum = window.getSum();

        assertEquals(1500, sum);
        verify(redisTemplate).execute(any(RedisScript.class), eq(Collections.singletonList(EXPECTED_KEY)));
    }

    @Test
    void shouldReturnZeroSumWhenScriptReturnsNull() {
        when(redisTemplate.execute(any(RedisScript.class), eq(Collections.singletonList(EXPECTED_KEY))))
                .thenReturn(null);

        assertEquals(0, window.getSum());
    }

    @Test
    void shouldGetCountViaZCARD() {
        when(zSetOperations.zCard(EXPECTED_KEY)).thenReturn(42L);

        assertEquals(42, window.getCount());
    }

    @Test
    void shouldReturnZeroCountWhenZCardReturnsNull() {
        when(zSetOperations.zCard(EXPECTED_KEY)).thenReturn(null);

        assertEquals(0, window.getCount());
    }

    @Test
    void shouldCheckEmptyViaCount() {
        when(zSetOperations.zCard(EXPECTED_KEY)).thenReturn(0L);
        assertTrue(window.isEmpty());

        when(zSetOperations.zCard(EXPECTED_KEY)).thenReturn(5L);
        assertFalse(window.isEmpty());
    }

    @Test
    void shouldGetOldestTimestampViaLuaScript() {
        // The oldest script is the second script type used
        // We need to match the specific script
        when(redisTemplate.execute(any(RedisScript.class), eq(Collections.singletonList(EXPECTED_KEY))))
                .thenReturn(12345L);

        long oldest = window.getOldestTimestamp();

        assertEquals(12345L, oldest);
    }

    @Test
    void shouldReturnZeroOldestWhenScriptReturnsNull() {
        when(redisTemplate.execute(any(RedisScript.class), eq(Collections.singletonList(EXPECTED_KEY))))
                .thenReturn(null);

        assertEquals(0L, window.getOldestTimestamp());
    }

    @Test
    void shouldReturnCurrentTimeForLastAccessTime() {
        long before = System.currentTimeMillis();
        long accessTime = window.getLastAccessTime();
        long after = System.currentTimeMillis();

        assertTrue(accessTime >= before && accessTime <= after,
                "getLastAccessTime should return approximately current time");
    }

    @Test
    void shouldGenerateUniqueMembersForSameValue() {
        long timestamp = 1000L;

        window.add(timestamp, 100);
        window.add(timestamp, 100);

        ArgumentCaptor<String> memberCaptor = ArgumentCaptor.forClass(String.class);
        verify(zSetOperations, times(2)).add(eq(EXPECTED_KEY), memberCaptor.capture(), eq((double) timestamp));

        List<String> members = memberCaptor.getAllValues();
        assertNotEquals(members.get(0), members.get(1),
                "Each add should produce a unique member to prevent ZSET deduplication");
    }
}
