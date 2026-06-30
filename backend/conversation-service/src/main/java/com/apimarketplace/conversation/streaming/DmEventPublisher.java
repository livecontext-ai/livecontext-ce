package com.apimarketplace.conversation.streaming;

import com.apimarketplace.conversation.dto.DmMessageDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fans DM events out over Redis pub/sub to the gateway's RedisChannelBridge, which
 * forwards them to subscribed WebSocket clients in real time.
 *
 * <p>Channel convention (the bridge subscribes the {@code ws:}-prefixed Redis channel
 * when a client subscribes to the un-prefixed frontend channel):
 * <ul>
 *   <li>{@code ws:dm:{threadId}} - both participants get message:new / message:read.</li>
 *   <li>{@code ws:dm-inbox:{userId}} - the recipient's inbox gets dm:incoming for unread
 *       badges / new-thread surfacing even when the thread isn't open.</li>
 * </ul>
 * Mirrors {@link StreamPubSubService}'s {@code ws:conversation:{id}} mechanism; reuses the
 * same {@link ReactiveRedisTemplate}. Publishing is best-effort fire-and-forget.
 */
@Component
public class DmEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(DmEventPublisher.class);
    private static final String THREAD_CHANNEL = "ws:dm:";
    private static final String INBOX_CHANNEL = "ws:dm-inbox:";

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public DmEventPublisher(ReactiveRedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /** New message → both participants subscribed to the thread channel. */
    public void publishMessage(String threadId, DmMessageDto message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "message:new");
        payload.put("threadId", threadId);
        payload.put("message", message);
        send(THREAD_CHANNEL + threadId, payload);
    }

    /** New message → recipient's personal inbox (badge / surface), even if thread not open. */
    public void publishInbox(String recipientUserId, String threadId, DmMessageDto message) {
        if (recipientUserId == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "dm:incoming");
        payload.put("threadId", threadId);
        payload.put("message", message);
        send(INBOX_CHANNEL + recipientUserId, payload);
    }

    /** The reader opened the thread → tell the sender their messages were read. */
    public void publishRead(String threadId, String readerUserId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "message:read");
        payload.put("threadId", threadId);
        payload.put("readerUserId", readerUserId);
        send(THREAD_CHANNEL + threadId, payload);
    }

    private void send(String channel, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            redisTemplate.convertAndSend(channel, json)
                    .doOnError(e -> log.warn("[DM PUBSUB] publish to {} failed: {}", channel, e.getMessage()))
                    .subscribe();
        } catch (Exception e) {
            log.warn("[DM PUBSUB] serialize/publish to {} failed: {}", channel, e.getMessage());
        }
    }
}
