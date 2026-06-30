package com.apimarketplace.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.Topic;

import java.nio.charset.StandardCharsets;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("CeLinkActiveRowCacheInvalidationListener")
class CeLinkActiveRowCacheInvalidationListenerTest {

    @Mock private RedisMessageListenerContainer container;
    @Mock private CeLinkActiveRowCache cache;

    private CeLinkActiveRowCacheInvalidationListener listener;

    @BeforeEach
    void setUp() {
        listener = new CeLinkActiveRowCacheInvalidationListener(container, cache);
    }

    @Test
    @DisplayName("subscribe registers the listener on the documented channel topic")
    void subscribe_registers_channel_topic() {
        listener.subscribe();

        verify(container).addMessageListener(org.mockito.ArgumentMatchers.eq(listener),
                org.mockito.ArgumentMatchers.<Topic>argThat(t ->
                        t.getTopic().equals(CeLinkActiveRowCachePublisher.CHANNEL)));
    }

    @Test
    @DisplayName("onMessage parses the userId and invalidates the local cache")
    void on_message_invalidates_cache() {
        Message msg = new DefaultMessage(
                CeLinkActiveRowCachePublisher.CHANNEL.getBytes(StandardCharsets.UTF_8),
                "42".getBytes(StandardCharsets.UTF_8));

        listener.onMessage(msg, null);

        verify(cache).invalidate(42L);
    }

    @Test
    @DisplayName("onMessageIgnoresMalformedPayload - bad input does not crash the listener thread")
    void on_message_ignores_malformed() {
        Message msg = new DefaultMessage(
                CeLinkActiveRowCachePublisher.CHANNEL.getBytes(StandardCharsets.UTF_8),
                "not-a-number".getBytes(StandardCharsets.UTF_8));

        listener.onMessage(msg, null);

        verify(cache, never()).invalidate(org.mockito.ArgumentMatchers.anyLong());
    }
}
