package com.apimarketplace.common.scaling;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Collections;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RedisStreamCleanupTask Tests")
class RedisStreamCleanupTaskTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    private RedisStreamCleanupTask task;

    @BeforeEach
    void setUp() {
        task = new RedisStreamCleanupTask(redisTemplate);
    }

    @Nested
    @DisplayName("cleanupAcknowledgedMessages()")
    class CleanupTests {

        @Test
        @DisplayName("should skip when no scaling streams exist")
        void shouldSkipWhenNoStreams() {
            when(redisTemplate.keys(RedisStreamCleanupTask.STREAM_KEY_PREFIX + "*"))
                    .thenReturn(Collections.emptySet());

            assertThatNoException().isThrownBy(() -> task.cleanupAcknowledgedMessages());

            // Should not attempt any trim operations
            verify(redisTemplate, never()).execute(any(RedisCallback.class));
        }

        @Test
        @DisplayName("should skip when keys returns null")
        void shouldSkipWhenKeysReturnsNull() {
            when(redisTemplate.keys(RedisStreamCleanupTask.STREAM_KEY_PREFIX + "*"))
                    .thenReturn(null);

            assertThatNoException().isThrownBy(() -> task.cleanupAcknowledgedMessages());
        }

        @Test
        @DisplayName("should handle exception during keys lookup gracefully")
        void shouldHandleKeysException() {
            when(redisTemplate.keys(any()))
                    .thenThrow(new RuntimeException("Redis unavailable"));

            assertThatNoException().isThrownBy(() -> task.cleanupAcknowledgedMessages());
        }

        @Test
        @DisplayName("should attempt trim for each discovered stream key")
        void shouldTrimEachStreamKey() {
            Set<String> keys = Set.of(
                    "scaling:stream:execution-queue",
                    "scaling:stream:signal-events"
            );
            when(redisTemplate.keys(RedisStreamCleanupTask.STREAM_KEY_PREFIX + "*"))
                    .thenReturn(keys);

            // Mock the execute call to return 0 (no entries trimmed)
            when(redisTemplate.execute(any(RedisCallback.class)))
                    .thenReturn(0L);

            task.cleanupAcknowledgedMessages();

            // Verify execute was called for each stream key
            verify(redisTemplate, times(2)).execute(any(RedisCallback.class));
        }
    }

    @Nested
    @DisplayName("trimStream()")
    class TrimStreamTests {

        @Test
        @DisplayName("should return 0 on exception")
        void shouldReturnZeroOnException() {
            when(redisTemplate.execute(any(RedisCallback.class)))
                    .thenThrow(new RuntimeException("Connection lost"));

            long result = task.trimStream("scaling:stream:test", "1234567890-0");

            assertThat(result).isZero();
        }

        @Test
        @DisplayName("should return trimmed count from Redis")
        void shouldReturnTrimmedCount() {
            when(redisTemplate.execute(any(RedisCallback.class)))
                    .thenReturn(42L);

            long result = task.trimStream("scaling:stream:test", "1234567890-0");

            assertThat(result).isEqualTo(42);
        }

        @Test
        @DisplayName("should return 0 when execute returns null")
        void shouldReturnZeroWhenNull() {
            when(redisTemplate.execute(any(RedisCallback.class)))
                    .thenReturn(null);

            long result = task.trimStream("scaling:stream:test", "1234567890-0");

            assertThat(result).isZero();
        }
    }

    @Nested
    @DisplayName("Constants")
    class ConstantTests {

        @Test
        @DisplayName("stream key prefix should be 'scaling:stream:'")
        void shouldHaveCorrectPrefix() {
            assertThat(RedisStreamCleanupTask.STREAM_KEY_PREFIX).isEqualTo("scaling:stream:");
        }

        @Test
        @DisplayName("max message age should be 1 hour")
        void shouldHaveOneHourMaxAge() {
            assertThat(RedisStreamCleanupTask.MAX_MESSAGE_AGE.toHours()).isEqualTo(1);
        }
    }
}
