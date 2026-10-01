package com.apimarketplace.orchestrator.execution.v2.async;

import com.apimarketplace.orchestrator.domain.execution.AgentResultMessage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every {@link PendingAgent} component survives each place the entry is written to Redis. The
 * three serializers map the fields by hand, so a new component is silently dropped by the one
 * nobody remembered: the in-flight store dropped {@code loopIteration} that way, and a delivery
 * replayed after a crash then landed on iteration 0. The entry is built by reflection with a
 * distinct value per component, so adding a component without serializing it fails here.
 */
@DisplayName("PendingAgent - every component survives every Redis serializer")
class PendingAgentSerializationParityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** An entry whose every component holds a distinct non-default value (millisecond-precise instant). */
    static PendingAgent everyComponentSet() throws Exception {
        RecordComponent[] components = PendingAgent.class.getRecordComponents();
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            Class<?> type = components[i].getType();
            String name = components[i].getName();
            if (type == String.class) values[i] = name + "-value";
            else if (type == int.class) values[i] = 7 + i;
            else if (type == long.class) values[i] = 1_000L + i;
            else if (type == Integer.class) values[i] = 40 + i;
            else if (type == Instant.class) values[i] = Instant.ofEpochMilli(1_790_000_000_123L);
            else if (type == Map.class) values[i] = Map.of(name, "entry");
            else throw new IllegalStateException("No sample value for component " + name + " of " + type
                + ": teach this test the type, and every serializer the field");
        }
        Constructor<PendingAgent> canonical = PendingAgent.class.getDeclaredConstructor(
            Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new));
        return canonical.newInstance(values);
    }

    @Test
    @DisplayName("the in-flight store (crash replay) keeps every component, loopIteration included")
    void inFlightStoreKeepsEveryComponent() throws Exception {
        PendingAgent pending = everyComponentSet();
        AgentResultMessage result = new AgentResultMessage(pending.correlationId(), pending.runId(), pending.nodeId(),
            Map.of("text", "ok"), true, null, pending.agentType(), Instant.ofEpochMilli(1_790_000_001_000L));

        String json = objectMapper.writeValueAsString(RedisInFlightStore.toInFlightMap(pending, result));
        RedisInFlightStore.InFlightEntry round =
            RedisInFlightStore.fromInFlightMap(objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {}));

        assertThat(round.pending()).isEqualTo(pending);
    }

    @Test
    @DisplayName("the pending store (restart recovery, cross-replica claim) keeps every component")
    @SuppressWarnings("unchecked")
    void pendingStoreKeepsEveryComponent() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(mock(SetOperations.class));
        RedisPendingAgentStore store = new RedisPendingAgentStore(redis, objectMapper, Duration.ofHours(3));
        PendingAgent pending = everyComponentSet();

        store.store(pending);
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq(RedisPendingAgentStore.KEY_PREFIX + pending.correlationId()), json.capture(),
            org.mockito.ArgumentMatchers.anyLong(), any(java.util.concurrent.TimeUnit.class));
        when(values.getAndDelete(RedisPendingAgentStore.KEY_PREFIX + pending.correlationId())).thenReturn(json.getValue());

        assertThat(store.claim(pending.correlationId())).contains(pending);
    }

    @Test
    @DisplayName("the timed-out attempt kept for billing a late answer keeps every component")
    @SuppressWarnings("unchecked")
    void timedOutAttemptKeepsEveryComponent() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RedisPendingAgentStore store = new RedisPendingAgentStore(redis, objectMapper, Duration.ofHours(3));
        PendingAgent pending = everyComponentSet();

        store.storeTimedOut(pending);
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq(RedisPendingAgentStore.TIMED_OUT_PREFIX + pending.correlationId()), json.capture(), any(Duration.class));
        when(values.getAndDelete(anyString())).thenReturn(json.getValue());

        assertThat(store.claimTimedOut(pending.correlationId())).contains(pending);
    }

    @Test
    @DisplayName("an entry written before attempt/timeoutMs existed reads back as a first attempt with no timeout")
    void entryWithoutTheNewFieldsIsAFirstAttempt() {
        PendingAgent legacy = new PendingAgent("cid", "run", "agent:a", "a", "trigger:t", 1, 0, null, "agent",
            "tenant", null, null, null, null, null, "model", null, null, Instant.now(), "org", null);
        Map<String, Object> map = RedisInFlightStore.toInFlightMap(legacy,
            new AgentResultMessage("cid", "run", "agent:a", Map.of(), true, null, "agent", Instant.now()));
        map.remove("attempt");
        map.remove("timeoutMs");

        PendingAgent read = RedisInFlightStore.fromInFlightMap(map).pending();

        assertThat(read.attempt()).isEqualTo(1);
        assertThat(read.timeoutMs()).isZero();
    }

    @Test
    @DisplayName("nextAttempt keeps the node, item, loop turn and timeout, and moves to the next attempt")
    void nextAttemptKeepsTheCoordinates() throws Exception {
        PendingAgent first = everyComponentSet();
        Instant sendAt = Instant.ofEpochMilli(1_790_000_009_000L);

        PendingAgent next = first.nextAttempt("cid-2", "exec-2", "stream-2", sendAt);

        assertThat(next.correlationId()).isEqualTo("cid-2");
        assertThat(next.executionId()).isEqualTo("exec-2");
        assertThat(next.streamId()).isEqualTo("stream-2");
        assertThat(next.startedAt()).isEqualTo(sendAt);
        assertThat(next.attempt()).isEqualTo(first.attempt() + 1);
        assertThat(next).usingRecursiveComparison()
            .ignoringFields("correlationId", "executionId", "streamId", "startedAt", "attempt")
            .isEqualTo(first);
    }
}
