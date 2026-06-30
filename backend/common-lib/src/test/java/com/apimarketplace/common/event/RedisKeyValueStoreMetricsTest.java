package com.apimarketplace.common.event;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RedisKeyValueStore Metrics Tests")
class RedisKeyValueStoreMetricsTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private HashOperations<String, Object, Object> hashOps;

    @Mock
    private ListOperations<String, String> listOps;

    private SimpleMeterRegistry meterRegistry;
    private RedisKeyValueStore store;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        store = new RedisKeyValueStore(redisTemplate, meterRegistry);
    }

    @Test
    @DisplayName("set() should record timer metric")
    void setShouldRecordMetric() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        store.set("key", "value", null);

        Timer timer = meterRegistry.find("scaling.redis.kvstore")
                .tag("operation", "set")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("get() should record timer metric")
    void getShouldRecordMetric() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("key")).thenReturn("value");

        Optional<String> result = store.get("key");

        assertThat(result).contains("value");

        Timer timer = meterRegistry.find("scaling.redis.kvstore")
                .tag("operation", "get")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("delete() should record timer metric")
    void deleteShouldRecordMetric() {
        when(redisTemplate.delete("key")).thenReturn(true);

        store.delete("key");

        Timer timer = meterRegistry.find("scaling.redis.kvstore")
                .tag("operation", "delete")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("hashPutAll() should record timer metric")
    void hashPutAllShouldRecordMetric() {
        when(redisTemplate.opsForHash()).thenReturn(hashOps);

        store.hashPutAll("key", Map.of("f1", "v1"), null);

        Timer timer = meterRegistry.find("scaling.redis.kvstore")
                .tag("operation", "hashPutAll")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("hashGetAll() should record timer metric")
    void hashGetAllShouldRecordMetric() {
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.entries("key")).thenReturn(Map.of());

        store.hashGetAll("key");

        Timer timer = meterRegistry.find("scaling.redis.kvstore")
                .tag("operation", "hashGetAll")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("listRightPush() should record timer metric")
    void listRightPushShouldRecordMetric() {
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.rightPush("key", "value")).thenReturn(1L);

        store.listRightPush("key", "value");

        Timer timer = meterRegistry.find("scaling.redis.kvstore")
                .tag("operation", "listRightPush")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("multiple operations should accumulate in the same timer")
    void multipleOpsShouldAccumulate() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn("v");

        store.get("k1");
        store.get("k2");
        store.get("k3");

        Timer timer = meterRegistry.find("scaling.redis.kvstore")
                .tag("operation", "get")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(3);
    }
}
