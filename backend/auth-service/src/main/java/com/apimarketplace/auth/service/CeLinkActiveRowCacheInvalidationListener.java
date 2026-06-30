package com.apimarketplace.auth.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Receives cache-invalidation broadcasts from {@link CeLinkActiveRowCachePublisher}
 * and drops the matching entry from THIS replica's
 * {@link CeLinkActiveRowCache}. Pairs with the publisher to close the
 * cross-replica staleness window down to "Redis hop latency" (sub-second)
 * instead of "TTL expiry" (30s).
 *
 * <p>Gated by {@code @ConditionalOnBean(RedisMessageListenerContainer.class)} -
 * the bean lives in common-lib's {@code RedisEventBusConfiguration}, so a
 * deployment running without Redis will simply not wire this listener (the
 * TTL fallback then governs staleness, which is the documented degraded mode).
 */
@Component
@ConditionalOnBean(RedisMessageListenerContainer.class)
@ConditionalOnProperty(name = "auth.mode", havingValue = "keycloak", matchIfMissing = false)
public class CeLinkActiveRowCacheInvalidationListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(CeLinkActiveRowCacheInvalidationListener.class);

    private final RedisMessageListenerContainer listenerContainer;
    private final CeLinkActiveRowCache cache;

    public CeLinkActiveRowCacheInvalidationListener(RedisMessageListenerContainer listenerContainer,
                                                    CeLinkActiveRowCache cache) {
        this.listenerContainer = listenerContainer;
        this.cache = cache;
    }

    @PostConstruct
    void subscribe() {
        listenerContainer.addMessageListener(this, new ChannelTopic(CeLinkActiveRowCachePublisher.CHANNEL));
        log.info("CeLinkActiveRowCacheInvalidationListener subscribed to {}", CeLinkActiveRowCachePublisher.CHANNEL);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            Long userId = Long.parseLong(payload.trim());
            cache.invalidate(userId);
            log.debug("CeLinkActiveRowCacheInvalidationListener invalidated cache for userId={}", userId);
        } catch (NumberFormatException malformed) {
            // Defensive: a bad message shouldn't crash the listener thread.
            log.warn("CeLinkActiveRowCacheInvalidationListener ignored malformed payload: '{}'", payload);
        }
    }
}
