package com.apimarketplace.conversation.streaming;

import com.apimarketplace.conversation.dto.DmMessageDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies the DM WebSocket pub/sub contract: the exact Redis channel and the JSON payload
 * shape (event {@code type} + ids) that the gateway bridge forwards to clients. A renamed
 * field or wrong type here silently breaks the live frontend without any other test failing.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DmEventPublisher - WS payload contract")
class DmEventPublisherTest {

    @Mock private ReactiveRedisTemplate<String, String> redisTemplate;
    // Mirror the Spring-provided mapper: JavaTimeModule registered so the DmMessageDto's
    // Instant fields serialize (a plain ObjectMapper throws, which the publisher would
    // swallow - masking the contract under test).
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private DmEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new DmEventPublisher(redisTemplate, objectMapper);
    }

    private DmMessageDto msg() {
        return new DmMessageDto("m1", "t1", "7", "hello", List.of(), null,
                Instant.parse("2026-01-02T00:00:00Z"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> captureSend(String expectedChannel) throws Exception {
        ArgumentCaptor<String> channel = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).convertAndSend(channel.capture(), json.capture());
        assertThat(channel.getValue()).isEqualTo(expectedChannel);
        return objectMapper.readValue(json.getValue(), Map.class);
    }

    @Test
    @DisplayName("publishMessage → ws:dm:{threadId} with type=message:new and the embedded message")
    void publishMessageContract() throws Exception {
        when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(Mono.just(1L));

        publisher.publishMessage("t1", msg());

        Map<String, Object> payload = captureSend("ws:dm:t1");
        assertThat(payload.get("type")).isEqualTo("message:new");
        assertThat(payload.get("threadId")).isEqualTo("t1");
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) payload.get("message");
        assertThat(message.get("id")).isEqualTo("m1");
        assertThat(message.get("senderUserId")).isEqualTo("7");
        assertThat(message.get("content")).isEqualTo("hello");
    }

    @Test
    @DisplayName("publishInbox → ws:dm-inbox:{userId} with type=dm:incoming")
    void publishInboxContract() throws Exception {
        when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(Mono.just(1L));

        publisher.publishInbox("99", "t1", msg());

        Map<String, Object> payload = captureSend("ws:dm-inbox:99");
        assertThat(payload.get("type")).isEqualTo("dm:incoming");
        assertThat(payload.get("threadId")).isEqualTo("t1");
    }

    @Test
    @DisplayName("publishInbox is a no-op when the recipient is null (no other participant)")
    void publishInboxNullRecipientNoOp() {
        publisher.publishInbox(null, "t1", msg());
        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("publishRead → ws:dm:{threadId} with type=message:read and readerUserId")
    void publishReadContract() throws Exception {
        when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(Mono.just(1L));

        publisher.publishRead("t1", "7");

        Map<String, Object> payload = captureSend("ws:dm:t1");
        assertThat(payload.get("type")).isEqualTo("message:read");
        assertThat(payload.get("threadId")).isEqualTo("t1");
        assertThat(payload.get("readerUserId")).isEqualTo("7");
    }
}
