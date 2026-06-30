package com.apimarketplace.monolith.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("MonolithStreamingOutput")
class MonolithStreamingOutputTest {

    @Test
    @DisplayName("sendUserMessage tolerates null metadata from the shared chat streaming service")
    void sendUserMessageToleratesNullMetadata() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        MonolithStreamingOutput output = new MonolithStreamingOutput(
                "stream-1", "conv-1", "deepseek-chat", "deepseek", redisTemplate, new ObjectMapper());

        assertThatCode(() -> output.sendUserMessage("conv-1", null)).doesNotThrowAnyException();

        verify(redisTemplate).convertAndSend(
                eq("ws:conversation:conv-1"),
                contains("\"type\":\"user_message\""));
    }
}
