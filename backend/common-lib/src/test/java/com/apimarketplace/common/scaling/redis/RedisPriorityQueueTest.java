package com.apimarketplace.common.scaling.redis;

import com.apimarketplace.common.scaling.queue.PriorityTierWeights;
import com.apimarketplace.common.scaling.queue.QueueMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisPriorityQueueTest {

    @Test
    @DisplayName("acknowledge uses atomic Lua ACK+DELETE script via stream metadata")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void acknowledgeUsesAtomicLuaScript() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        StreamOperations streamOperations = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any()))
                .thenReturn(1L);

        RedisPriorityQueue<String> queue = new RedisPriorityQueue<>(
                redisTemplate,
                new SimpleMeterRegistry(),
                new ObjectMapper(),
                "test",
                new PriorityTierWeights(),
                String.class);

        QueueMessage<String> message = new QueueMessage<>(
                "app-request-id",
                "payload",
                0,
                Instant.now(),
                Map.of(
                        RedisPriorityQueue.REDIS_STREAM_KEY_METADATA, "test:queue:p70",
                        RedisPriorityQueue.REDIS_STREAM_ID_METADATA, "123-0"));

        queue.acknowledge(message);

        verify(redisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(Collections.singletonList("test:queue:p70")),
                eq("workers"),
                eq("123-0"));
        verify(streamOperations, never()).acknowledge(anyString(), anyString(), anyString());
        verify(streamOperations, never()).delete(anyString(), any(String[].class));
    }

    @Test
    @DisplayName("isAvailable fails closed when Redis consumer groups cannot be ensured")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void availabilityFailsWhenConsumerGroupsCannotBeEnsured() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        StreamOperations streamOperations = mock(StreamOperations.class);
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);

        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(redisTemplate.getConnectionFactory()).thenReturn(connectionFactory);
        when(connectionFactory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("PONG");
        doThrow(new RuntimeException("redis unavailable"))
                .when(streamOperations).createGroup(anyString(), any(ReadOffset.class), eq("workers"));
        when(streamOperations.add(any())).thenThrow(new RuntimeException("redis unavailable"));

        RedisPriorityQueue<String> queue = new RedisPriorityQueue<>(
                redisTemplate,
                new SimpleMeterRegistry(),
                new ObjectMapper(),
                "test",
                new PriorityTierWeights(),
                String.class);

        assertThat(queue.isAvailable()).isFalse();
        verify(streamOperations, atLeast(8))
                .createGroup(anyString(), any(ReadOffset.class), eq("workers"));
    }

    @Test
    @DisplayName("pending reclaim script persists XAUTOCLAIM cursor per stream")
    void reclaimPendingScriptPersistsCursorPerStream() throws Exception {
        String script = Files.readString(Path.of("src/main/resources/lua/reclaim_pending.lua"));

        assertThat(script).contains("local cursor_key = KEYS[1]");
        assertThat(script).contains("redis.call('HGET', cursor_key, stream)");
        assertThat(script).contains("redis.call('HSET', cursor_key, stream, next_cursor)");
        assertThat(script).contains("XAUTOCLAIM");
    }

    @Test
    @DisplayName("stream_ack_delete script performs atomic XACK then XDEL")
    void streamAckDeleteScriptIsAtomic() throws Exception {
        String script = Files.readString(Path.of("src/main/resources/lua/stream_ack_delete.lua"));

        assertThat(script).contains("XACK");
        assertThat(script).contains("XDEL");
        assertThat(script.indexOf("XACK")).isLessThan(script.indexOf("XDEL"));
    }

    @Test
    @DisplayName("size() is strictly read-only: XLEN per stream, no Lua script, no stream writes")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void sizeIsStrictlyReadOnly() {
        // Load-bearing invariant: the execution-queue worker loop uses
        // size() == 0 as its idle fast-path guard, precisely so that empty
        // polling never writes to Redis (prod 2026-06-10: idle bookkeeping
        // wrote ~700KB/s of AOF and forced a rewrite fork every ~90s).
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        StreamOperations streamOperations = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.size(anyString())).thenReturn(2L);

        RedisPriorityQueue<String> queue = new RedisPriorityQueue<>(
                redisTemplate,
                new SimpleMeterRegistry(),
                new ObjectMapper(),
                "test",
                new PriorityTierWeights(),
                String.class);

        long total = queue.size();

        assertThat(total).isEqualTo(16L); // 8 tiers x 2 entries
        verify(streamOperations, times(8)).size(anyString());
        verify(redisTemplate, never()).execute(any(DefaultRedisScript.class), anyList(), any());
        verify(streamOperations, never()).add(any());
        verify(streamOperations, never()).trim(anyString(), anyLong(), anyBoolean());
        verify(streamOperations, never()).acknowledge(anyString(), anyString(), anyString());
        verify(streamOperations, never()).delete(anyString(), any(String[].class));
    }

    @Test
    @DisplayName("size() treats a failing stream as empty and still counts the others")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void sizeSwallowsPerStreamFailures() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        StreamOperations streamOperations = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.size(anyString()))
                .thenThrow(new RuntimeException("redis unavailable"))
                .thenReturn(3L);

        RedisPriorityQueue<String> queue = new RedisPriorityQueue<>(
                redisTemplate,
                new SimpleMeterRegistry(),
                new ObjectMapper(),
                "test",
                new PriorityTierWeights(),
                String.class);

        assertThat(queue.size()).isEqualTo(21L); // first stream failed, 7 x 3 counted
    }

    @Test
    @DisplayName("weighted_dequeue.lua reads with XREADGROUP and never deletes from the stream (XLEN keeps counting pending entries)")
    void dequeueLuaLeavesEntriesInStream() throws Exception {
        // Companion invariant of the idle fast-path: a delivered-but-unacked
        // message must keep size() > 0 so crashed-consumer reclaims still run.
        // Deletion only happens at ACK time (atomic XACK+XDEL, covered above).
        Path lua = Path.of("src/main/resources/lua/weighted_dequeue.lua");
        String script = Files.readString(lua);
        assertThat(script).contains("XREADGROUP");
        assertThat(script).doesNotContain("XDEL");
        assertThat(script).doesNotContain("XACK");
        assertThat(script).doesNotContain("XTRIM");
    }
}
