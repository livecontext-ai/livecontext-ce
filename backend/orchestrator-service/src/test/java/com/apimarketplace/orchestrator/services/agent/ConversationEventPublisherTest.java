package com.apimarketplace.orchestrator.services.agent;

import com.apimarketplace.conversation.client.ConversationClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.apimarketplace.common.event.EventBus;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("ConversationEventPublisher")
@ExtendWith(MockitoExtension.class)
class ConversationEventPublisherTest {

    @Mock
    private EventBus eventBus;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ListOperations<String, String> listOps;

    @Mock
    private ConversationClient conversationClient;

    private ConversationEventPublisher publisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForList()).thenReturn(listOps);
        lenient().when(redisTemplate.expire(anyString(), any())).thenReturn(true);
        publisher = new ConversationEventPublisher(eventBus, objectMapper, redisTemplate, conversationClient);
    }

    private Map<String, Object> capturePublishedJson() throws JsonProcessingException {
        ArgumentCaptor<String> channelCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventBus).publish(channelCaptor.capture(), messageCaptor.capture());

        assertThat(channelCaptor.getValue()).startsWith("ws:conversation:");
        @SuppressWarnings("unchecked")
        Map<String, Object> event = objectMapper.readValue(messageCaptor.getValue(), Map.class);
        return event;
    }

    @Nested
    @DisplayName("publishStreamStarted")
    class PublishStreamStarted {

        @Test
        @DisplayName("should publish stream_started with correct fields")
        void shouldPublishStreamStarted() throws JsonProcessingException {
            publisher.publishStreamStarted("conv-1", "stream-1", "gpt-4");

            Map<String, Object> event = capturePublishedJson();
            assertThat(event).containsEntry("streamId", "stream-1");
            assertThat(event).containsEntry("conversationId", "conv-1");
            assertThat(event).containsEntry("model", "gpt-4");
            assertThat(event).containsKey("timestamp");
        }

        @Test
        @DisplayName("should default model to unknown when null")
        void shouldDefaultModelToUnknown() throws JsonProcessingException {
            publisher.publishStreamStarted("conv-1", "stream-1", null);

            Map<String, Object> event = capturePublishedJson();
            assertThat(event).containsEntry("model", "unknown");
        }

        @Test
        @DisplayName("should publish to correct Redis channel")
        void shouldPublishToCorrectChannel() {
            publisher.publishStreamStarted("conv-123", "stream-1", "model");

            verify(eventBus).publish(eq("ws:conversation:conv-123"), anyString());
        }

        @Test
        @DisplayName("should register stream in conversation-service")
        void shouldRegisterStream() {
            publisher.publishStreamStarted("conv-1", "stream-1", "gpt-4");

            verify(conversationClient).registerStream("stream-1", "conv-1", "gpt-4", "workflow");
        }
    }

    @Nested
    @DisplayName("publishContent")
    class PublishContent {

        @Test
        @DisplayName("should publish content chunk")
        void shouldPublishContentChunk() throws JsonProcessingException {
            publisher.publishContent("conv-1", "stream-1", "Hello world");

            Map<String, Object> event = capturePublishedJson();
            assertThat(event).containsEntry("streamId", "stream-1");
            assertThat(event).containsEntry("content", "Hello world");
        }
    }

    @Nested
    @DisplayName("publishToolCall")
    class PublishToolCall {

        @Test
        @DisplayName("should publish tool_call with arguments")
        void shouldPublishToolCall() throws JsonProcessingException {
            Map<String, Object> args = Map.of("query", "test");
            publisher.publishToolCall("conv-1", "stream-1", "catalog", "tc-001", args);

            Map<String, Object> event = capturePublishedJson();
            assertThat(event).containsEntry("streamId", "stream-1");
            assertThat(event).containsEntry("toolName", "catalog");
            assertThat(event).containsEntry("toolId", "tc-001");
            @SuppressWarnings("unchecked")
            Map<String, Object> publishedArgs = (Map<String, Object>) event.get("arguments");
            assertThat(publishedArgs).containsEntry("query", "test");
        }

        @Test
        @DisplayName("should handle null arguments")
        void shouldHandleNullArguments() throws JsonProcessingException {
            publisher.publishToolCall("conv-1", "stream-1", "tool", "tc-1", null);

            Map<String, Object> event = capturePublishedJson();
            assertThat(event.get("arguments")).isNotNull();
        }
    }

    @Nested
    @DisplayName("publishToolResult")
    class PublishToolResult {

        @Test
        @DisplayName("should publish successful tool_result")
        void shouldPublishSuccessfulResult() throws JsonProcessingException {
            publisher.publishToolResult("conv-1", "stream-1", "tc-001", "catalog", true, 150L, "result data");

            Map<String, Object> event = capturePublishedJson();
            assertThat(event).containsEntry("toolId", "tc-001");
            assertThat(event).containsEntry("toolName", "catalog");
            assertThat(event).containsEntry("success", true);
            assertThat(event).containsEntry("durationMs", 150);
            assertThat(event.get("error")).isNull();
        }

        @Test
        @DisplayName("should publish failed tool_result with error")
        void shouldPublishFailedResult() throws JsonProcessingException {
            publisher.publishToolResult("conv-1", "stream-1", "tc-001", "catalog", false, 50L, "Connection refused");

            Map<String, Object> event = capturePublishedJson();
            assertThat(event).containsEntry("success", false);
            assertThat(event).containsEntry("error", "Connection refused");
        }

        @Test
        @DisplayName("should omit durationMs when null")
        void shouldOmitDurationWhenNull() throws JsonProcessingException {
            publisher.publishToolResult("conv-1", "stream-1", "tc-001", "catalog", true, null, null);

            Map<String, Object> event = capturePublishedJson();
            assertThat(event).doesNotContainKey("durationMs");
        }
    }

    @Nested
    @DisplayName("publishCompleted")
    class PublishCompleted {

        @Test
        @DisplayName("should publish completed with content")
        void shouldPublishCompleted() throws JsonProcessingException {
            publisher.publishCompleted("conv-1", "stream-1", "Full response text");

            Map<String, Object> event = capturePublishedJson();
            assertThat(event).containsEntry("streamId", "stream-1");
            assertThat(event).containsEntry("fullContent", "Full response text");
            assertThat(event).containsKey("totalTokens");
        }

        @Test
        @DisplayName("should handle null content")
        void shouldHandleNullContent() throws JsonProcessingException {
            publisher.publishCompleted("conv-1", "stream-1", null);

            Map<String, Object> event = capturePublishedJson();
            assertThat(event).containsEntry("fullContent", "");
            assertThat(event).containsEntry("totalTokens", 0);
        }

        @Test
        @DisplayName("should finalize stream as COMPLETED")
        void shouldFinalizeStream() {
            publisher.publishCompleted("conv-1", "stream-1", "done");

            verify(conversationClient).finalizeStream("stream-1", "COMPLETED");
        }
    }

    @Nested
    @DisplayName("publishError")
    class PublishError {

        @Test
        @DisplayName("should publish error event")
        void shouldPublishError() throws JsonProcessingException {
            publisher.publishError("conv-1", "stream-1", "Agent timed out");

            Map<String, Object> event = capturePublishedJson();
            assertThat(event).containsEntry("streamId", "stream-1");
            assertThat(event).containsEntry("error", "Agent timed out");
            assertThat(event).containsEntry("errorCode", "AGENT_ERROR");
            assertThat(event).containsEntry("retryable", false);
        }

        @Test
        @DisplayName("should finalize stream as ERROR")
        void shouldFinalizeStreamOnError() {
            publisher.publishError("conv-1", "stream-1", "Agent timed out");

            verify(conversationClient).finalizeStream("stream-1", "ERROR");
        }
    }

    @Nested
    @DisplayName("null conversationId handling")
    class NullConversationId {

        @Test
        @DisplayName("should skip publishing when conversationId is null")
        void shouldSkipWhenNull() {
            publisher.publishStreamStarted(null, "stream-1", "model");
            publisher.publishContent(null, "stream-1", "chunk");
            publisher.publishCompleted(null, "stream-1", "done");

            verifyNoInteractions(eventBus);
        }
    }

    @Nested
    @DisplayName("Redis error handling")
    class RedisErrorHandling {

        @Test
        @DisplayName("should not throw when Redis fails")
        void shouldNotThrowOnRedisError() {
            doThrow(new RuntimeException("Redis down")).when(eventBus)
                .publish(anyString(), anyString());

            // Should not throw
            publisher.publishContent("conv-1", "stream-1", "chunk");
        }
    }
}
