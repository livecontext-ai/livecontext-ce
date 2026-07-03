package com.apimarketplace.conversation.service.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BridgeStreamHeartbeat} - the reaper shield for DIRECT bridge dispatches.
 * Key contract must stay in exact parity with agent-service's ActiveStreamRegistry
 * ({@code stream:hb:} prefix, 120s TTL, synchronous first write): StreamTTLService's
 * absolute-timeout pass checks that precise key.
 */
@DisplayName("BridgeStreamHeartbeat")
@ExtendWith(MockitoExtension.class)
class BridgeStreamHeartbeatTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private BridgeStreamHeartbeat heartbeat;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        heartbeat = new BridgeStreamHeartbeat(redisTemplate);
    }

    @Test
    @DisplayName("register writes stream:hb:{id} with the 120s TTL SYNCHRONOUSLY (never observable active without a heartbeat)")
    void registerWritesKeyImmediately() {
        heartbeat.register("stream-1");

        verify(valueOperations).set("stream:hb:stream-1", "1", Duration.ofSeconds(120));
        assertThat(heartbeat.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("the scheduled tick refreshes every registered stream (a >120s run must never lose its shield)")
    void refreshRenewsAllActiveStreams() {
        heartbeat.register("stream-1");
        heartbeat.register("stream-2");

        heartbeat.refreshHeartbeats();

        verify(valueOperations, times(2)).set("stream:hb:stream-1", "1", Duration.ofSeconds(120));
        verify(valueOperations, times(2)).set("stream:hb:stream-2", "1", Duration.ofSeconds(120));
    }

    @Test
    @DisplayName("unregister deletes the key and stops refreshing it")
    void unregisterDeletesAndStopsRefreshing() {
        heartbeat.register("stream-1");

        heartbeat.unregister("stream-1");
        heartbeat.refreshHeartbeats();

        verify(redisTemplate).delete("stream:hb:stream-1");
        // one write from register, none from the post-unregister refresh
        verify(valueOperations, times(1)).set(anyString(), anyString(), any(Duration.class));
        assertThat(heartbeat.size()).isZero();
    }

    @Test
    @DisplayName("null streamIds are no-ops (defensive - callers pass raw request fields)")
    void nullIdsAreNoOps() {
        heartbeat.register(null);
        heartbeat.unregister(null);

        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
        assertThat(heartbeat.size()).isZero();
    }

    @Test
    @DisplayName("Redis failures never break the run (register, refresh and unregister all swallow)")
    void redisFailuresAreSwallowed() {
        doThrow(new RuntimeException("redis down"))
            .when(valueOperations).set(anyString(), anyString(), any(Duration.class));
        when(redisTemplate.delete(anyString())).thenThrow(new RuntimeException("redis down"));

        assertThatCode(() -> {
            heartbeat.register("stream-1");
            heartbeat.refreshHeartbeats();
            heartbeat.unregister("stream-1");
        }).doesNotThrowAnyException();
    }
}
