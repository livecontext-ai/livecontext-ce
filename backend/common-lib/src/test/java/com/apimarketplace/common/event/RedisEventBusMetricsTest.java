package com.apimarketplace.common.event;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("RedisEventBus Metrics Tests")
class RedisEventBusMetricsTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private RedisMessageListenerContainer listenerContainer;

    private SimpleMeterRegistry meterRegistry;
    private RedisEventBus eventBus;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        eventBus = new RedisEventBus(redisTemplate, listenerContainer, meterRegistry);
    }

    @Test
    @DisplayName("publish() should record timer metric")
    void publishShouldRecordMetric() {
        eventBus.publish("ws:workflow:run:abc-123", "{\"type\":\"update\"}");

        verify(redisTemplate).convertAndSend("ws:workflow:run:abc-123", "{\"type\":\"update\"}");

        Timer timer = meterRegistry.find("scaling.redis.eventbus.publish")
                .tag("channel", "ws:workflow:run")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("publish() should sanitize channel tag to avoid high cardinality")
    void publishShouldSanitizeChannelTag() {
        eventBus.publish("ws:workflow:run:uuid-1", "msg1");
        eventBus.publish("ws:workflow:run:uuid-2", "msg2");

        // Both should map to the same tag "ws:workflow:run"
        Timer timer = meterRegistry.find("scaling.redis.eventbus.publish")
                .tag("channel", "ws:workflow:run")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("publish() with short channel should preserve full name in tag")
    void publishWithShortChannelShouldPreserveName() {
        eventBus.publish("events", "msg");

        Timer timer = meterRegistry.find("scaling.redis.eventbus.publish")
                .tag("channel", "events")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }
}
