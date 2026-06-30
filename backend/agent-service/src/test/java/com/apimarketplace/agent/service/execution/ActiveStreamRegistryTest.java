package com.apimarketplace.agent.service.execution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ActiveStreamRegistry}.
 *
 * Tests cover:
 * 1. Heartbeat key written synchronously at register time (reconciler liveness contract)
 * 2. Scheduled heartbeat tick refreshing every active stream
 * 3. unregister removing the stream (no further heartbeats)
 * 4. interruptAll invoking each handle, isolated from a failing one
 * 5. Best-effort Redis writes - a Redis failure never breaks register/heartbeat
 */
@DisplayName("ActiveStreamRegistry")
@ExtendWith(MockitoExtension.class)
class ActiveStreamRegistryTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private ActiveStreamRegistry registry;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        registry = new ActiveStreamRegistry(redisTemplate);
    }

    @Nested
    @DisplayName("register()")
    class RegisterTests {

        @Test
        @DisplayName("should write the heartbeat key IMMEDIATELY (not only at the next scheduled tick) - the reconciler treats an active stream without it as orphaned")
        void shouldWriteHeartbeatKeyImmediately() {
            registry.register("stream-1", () -> { });

            verify(valueOperations).set(
                eq("stream:hb:stream-1"), eq("1"), eq(Duration.ofSeconds(120)));
        }

        @Test
        @DisplayName("should track the stream as active")
        void shouldTrackStreamAsActive() {
            registry.register("stream-1", () -> { });
            registry.register("stream-2", () -> { });

            assertThat(registry.size()).isEqualTo(2);
            assertThat(registry.activeStreamIds()).containsExactlyInAnyOrder("stream-1", "stream-2");
        }

        @Test
        @DisplayName("should ignore null streamId and null handle")
        void shouldIgnoreNullArguments() {
            registry.register(null, () -> { });
            registry.register("stream-1", null);

            assertThat(registry.size()).isZero();
            verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("should not throw when the heartbeat write fails (best-effort - Redis must never break an execution)")
        void shouldSurviveRedisFailure() {
            doThrow(new RuntimeException("Redis down"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

            assertThatCode(() -> registry.register("stream-1", () -> { }))
                .doesNotThrowAnyException();
            // The stream is still registered for drain interruption despite the failed heartbeat
            assertThat(registry.size()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("refreshHeartbeats()")
    class HeartbeatTests {

        @Test
        @DisplayName("scheduled tick should refresh the heartbeat key for every active stream")
        void shouldRefreshAllActiveStreams() {
            registry.register("stream-1", () -> { });
            registry.register("stream-2", () -> { });
            clearInvocations(valueOperations);

            registry.refreshHeartbeats();

            verify(valueOperations).set(eq("stream:hb:stream-1"), eq("1"), eq(Duration.ofSeconds(120)));
            verify(valueOperations).set(eq("stream:hb:stream-2"), eq("1"), eq(Duration.ofSeconds(120)));
        }

        @Test
        @DisplayName("tick should be a no-op when no stream is active")
        void shouldBeNoOpWhenEmpty() {
            registry.refreshHeartbeats();

            verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("tick should not throw when Redis is down")
        void shouldSurviveRedisFailure() {
            registry.register("stream-1", () -> { });
            doThrow(new RuntimeException("Redis down"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

            assertThatCode(() -> registry.refreshHeartbeats()).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("unregister()")
    class UnregisterTests {

        @Test
        @DisplayName("should remove the stream so subsequent ticks stop refreshing it")
        void shouldRemoveStream() {
            registry.register("stream-1", () -> { });
            registry.unregister("stream-1");
            clearInvocations(valueOperations);

            registry.refreshHeartbeats();

            assertThat(registry.size()).isZero();
            assertThat(registry.activeStreamIds()).isEmpty();
            verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("should tolerate unknown or null streamId")
        void shouldTolerateUnknownStreamId() {
            assertThatCode(() -> {
                registry.unregister("never-registered");
                registry.unregister(null);
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should DELETE the stream:hb:{id} liveness key so Redis does not carry up to 120s of stale liveness for a terminated stream")
        void shouldDeleteHeartbeatKey() {
            registry.register("stream-1", () -> { });

            registry.unregister("stream-1");

            verify(redisTemplate).delete("stream:hb:stream-1");
        }

        @Test
        @DisplayName("heartbeat-key deletion is best-effort - a Redis failure never breaks the terminal path (TTL expires the key instead)")
        void heartbeatKeyDeletionIsBestEffort() {
            registry.register("stream-1", () -> { });
            doThrow(new RuntimeException("Redis down"))
                .when(redisTemplate).delete(anyString());

            assertThatCode(() -> registry.unregister("stream-1")).doesNotThrowAnyException();
            // The stream is still removed from the in-memory registry despite the failed delete
            assertThat(registry.size()).isZero();
        }
    }

    @Nested
    @DisplayName("interruptAll()")
    class InterruptAllTests {

        @Test
        @DisplayName("should invoke every registered interruption handle")
        void shouldInvokeEveryHandle() {
            AtomicInteger invocations = new AtomicInteger();
            registry.register("stream-1", invocations::incrementAndGet);
            registry.register("stream-2", invocations::incrementAndGet);

            registry.interruptAll();

            assertThat(invocations.get()).isEqualTo(2);
        }

        @Test
        @DisplayName("should survive a handle throwing - the remaining streams are still interrupted")
        void shouldSurviveFailingHandle() {
            AtomicInteger survivorInvocations = new AtomicInteger();
            registry.register("stream-failing", () -> {
                throw new RuntimeException("finalize failed");
            });
            registry.register("stream-ok", survivorInvocations::incrementAndGet);

            assertThatCode(() -> registry.interruptAll()).doesNotThrowAnyException();
            assertThat(survivorInvocations.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("should tolerate a handle that unregisters its own stream (real interruptFromShutdown flow)")
        void shouldTolerateSelfUnregisteringHandle() {
            registry.register("stream-1", () -> registry.unregister("stream-1"));

            assertThatCode(() -> registry.interruptAll()).doesNotThrowAnyException();
            assertThat(registry.size()).isZero();
        }

        @Test
        @DisplayName("should be a no-op when no stream is active")
        void shouldBeNoOpWhenEmpty() {
            assertThatCode(() -> registry.interruptAll()).doesNotThrowAnyException();
        }
    }
}
