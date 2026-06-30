package com.apimarketplace.common.event;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Redis-backed EventBus for EE microservice mode.
 * Uses StringRedisTemplate for publish and RedisMessageListenerContainer for subscribe.
 */
public class RedisEventBus implements EventBus {

    private static final Logger log = LoggerFactory.getLogger(RedisEventBus.class);

    private final StringRedisTemplate redisTemplate;
    private final RedisMessageListenerContainer listenerContainer;
    private final MeterRegistry meterRegistry;

    public RedisEventBus(StringRedisTemplate redisTemplate,
                         RedisMessageListenerContainer listenerContainer,
                         MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.listenerContainer = listenerContainer;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void publish(String channel, String message) {
        Timer.builder("scaling.redis.eventbus.publish")
                .description("Time to publish a message to Redis EventBus")
                .tag("channel", sanitizeChannelTag(channel))
                .register(meterRegistry)
                .record(() -> redisTemplate.convertAndSend(channel, message));
    }

    @Override
    public Subscription subscribe(String channel, Consumer<String> listener) {
        MessageListener messageListener = (msg, pattern) -> {
            try {
                String body = new String(msg.getBody(), StandardCharsets.UTF_8);
                listener.accept(body);
            } catch (Exception e) {
                log.error("Error processing Redis message on channel '{}': {}", channel, e.getMessage(), e);
            }
        };

        ChannelTopic topic = new ChannelTopic(channel);
        listenerContainer.addMessageListener(messageListener, topic);
        log.debug("Redis subscription added for channel '{}'", channel);

        return () -> {
            listenerContainer.removeMessageListener(messageListener, topic);
            log.debug("Redis subscription removed for channel '{}'", channel);
        };
    }

    @Override
    public PatternSubscription subscribePattern(String pattern, PatternListener listener) {
        MessageListener messageListener = (Message msg, byte[] patternBytes) -> {
            try {
                String channel = new String(msg.getChannel(), StandardCharsets.UTF_8);
                String body = new String(msg.getBody(), StandardCharsets.UTF_8);
                listener.onMessage(channel, body);
            } catch (Exception e) {
                log.error("Error processing Redis pattern message: {}", e.getMessage(), e);
            }
        };

        PatternTopic topic = new PatternTopic(pattern);
        listenerContainer.addMessageListener(messageListener, topic);
        log.debug("Redis pattern subscription added for '{}'", pattern);

        return () -> {
            listenerContainer.removeMessageListener(messageListener, topic);
            log.debug("Redis pattern subscription removed for '{}'", pattern);
        };
    }

    /**
     * Sanitize channel name for use as a metric tag.
     * Extracts the channel prefix (e.g. "ws:workflow:run" from "ws:workflow:run:abc-123")
     * to avoid high-cardinality tags from dynamic IDs.
     */
    private String sanitizeChannelTag(String channel) {
        if (channel == null) return "unknown";
        // Keep up to 3 colon-separated segments to capture the channel type
        String[] parts = channel.split(":");
        int limit = Math.min(parts.length, 3);
        return String.join(":", java.util.Arrays.copyOf(parts, limit));
    }
}
