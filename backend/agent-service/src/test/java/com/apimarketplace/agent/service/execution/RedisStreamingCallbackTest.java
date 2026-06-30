package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolResult;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.apimarketplace.agent.tools.common.ToolMediaMetadata;
import com.apimarketplace.common.event.EventBus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for RedisStreamingCallback - the agent-service workflow streaming callback.
 * Focuses on shouldStop() cancel signal detection via Redis key.
 */
@DisplayName("RedisStreamingCallback")
@ExtendWith(MockitoExtension.class)
class RedisStreamingCallbackTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private EventBus eventBus;

    private ObjectMapper objectMapper;
    private RedisStreamingCallback callbackFactory;

    private static final String RUN_ID = "run-123";
    private static final String NODE_ID = "agent:my_agent";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        callbackFactory = new RedisStreamingCallback(redisTemplate, eventBus, objectMapper);
    }

    @Nested
    @DisplayName("forExecution()")
    class ForExecutionTests {

        @Test
        @DisplayName("should create a StreamingCallback instance")
        void shouldCreateCallback() {
            StreamingCallback callback = callbackFactory.forExecution(RUN_ID, NODE_ID, 0, null);
            assertThat(callback).isNotNull();
        }

        @Test
        @DisplayName("should create independent callbacks for different executions")
        void shouldCreateIndependentCallbacks() {
            StreamingCallback cb1 = callbackFactory.forExecution("run-1", NODE_ID, 0, null);
            StreamingCallback cb2 = callbackFactory.forExecution("run-2", NODE_ID, 0, null);
            assertThat(cb1).isNotSameAs(cb2);
        }
    }

    @Nested
    @DisplayName("shouldStop()")
    class ShouldStopTests {

        @Test
        @DisplayName("should return false when no cancel key exists")
        void shouldReturnFalseWhenNoCancelKey() {
            when(redisTemplate.hasKey("workflow:cancel:" + RUN_ID)).thenReturn(false);

            StreamingCallback callback = callbackFactory.forExecution(RUN_ID, NODE_ID, 0, null);
            assertThat(callback.shouldStop()).isFalse();
        }

        @Test
        @DisplayName("should return true when cancel key exists in Redis")
        void shouldReturnTrueWhenCancelKeyExists() {
            when(redisTemplate.hasKey("workflow:cancel:" + RUN_ID)).thenReturn(true);

            StreamingCallback callback = callbackFactory.forExecution(RUN_ID, NODE_ID, 0, null);
            assertThat(callback.shouldStop()).isTrue();
        }

        @Test
        @DisplayName("should cache stop signal locally after first detection")
        void shouldCacheStopSignalLocally() {
            when(redisTemplate.hasKey("workflow:cancel:" + RUN_ID)).thenReturn(true);

            StreamingCallback callback = callbackFactory.forExecution(RUN_ID, NODE_ID, 0, null);

            // First call detects cancel
            assertThat(callback.shouldStop()).isTrue();

            // Second call should use cached value without hitting Redis again
            assertThat(callback.shouldStop()).isTrue();

            // Redis should only have been queried once (cached after first detection)
            verify(redisTemplate, times(1)).hasKey("workflow:cancel:" + RUN_ID);
        }

        @Test
        @DisplayName("should handle Redis errors gracefully (fail-open)")
        void shouldHandleRedisErrorsGracefully() {
            when(redisTemplate.hasKey("workflow:cancel:" + RUN_ID))
                .thenThrow(new RuntimeException("Redis connection error"));

            StreamingCallback callback = callbackFactory.forExecution(RUN_ID, NODE_ID, 0, null);
            // Should not throw, should return false (fail-open)
            assertThat(callback.shouldStop()).isFalse();
        }

        @Test
        @DisplayName("sub-agents sharing same runId should see the same cancel signal")
        void subAgentsShouldShareCancelSignal() {
            when(redisTemplate.hasKey("workflow:cancel:" + RUN_ID)).thenReturn(true);

            // Parent agent and sub-agent share the same runId but different nodeIds
            StreamingCallback parentCb = callbackFactory.forExecution(RUN_ID, "agent:parent", 0, null);
            StreamingCallback subAgentCb = callbackFactory.forExecution(RUN_ID, "agent:child", 0, null);

            assertThat(parentCb.shouldStop()).isTrue();
            assertThat(subAgentCb.shouldStop()).isTrue();
        }
    }

    @Nested
    @DisplayName("onToolCall()")
    class OnToolCallTests {

        @Test
        @DisplayName("should publish tool call event to Redis channel")
        void shouldPublishToolCallEvent() {
            StreamingCallback callback = callbackFactory.forExecution(RUN_ID, NODE_ID, 0, null);
            callback.onToolCall(new ToolCall("call-1", "web_search", Map.of("query", "test"), null));

            ArgumentCaptor<String> channelCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(eventBus, atLeast(1)).publish(channelCaptor.capture(), messageCaptor.capture());

            assertThat(channelCaptor.getValue()).isEqualTo("ws:workflow:run:" + RUN_ID);
            assertThat(messageCaptor.getValue()).contains("AgentToolCallEvent");
            assertThat(messageCaptor.getValue()).contains("web_search");
        }
    }

    @Nested
    @DisplayName("onToolResult() - vision-media stripping")
    class OnToolResultTests {

        @Test
        @DisplayName("strips the heavy vision-media base64 from the Redis message but keeps the light metadata")
        void stripsHeavyMediaFromStreamedMetadata() {
            Map<String, Object> metadata = new java.util.HashMap<>();
            metadata.put("iconSlug", "image");
            metadata.put(ToolMediaMetadata.MEDIA_KEY, java.util.List.of(
                    ToolMediaMetadata.imageDescriptor("image/png", "HEAVYVISIONBASE64")));
            ToolResult result = ToolResult.builder()
                    .toolCall(new ToolCall("call-1", "files", Map.of("action", "view"), null))
                    .success(true)
                    .content("{\"vision\":\"inlined\"}")
                    .metadata(metadata)
                    .build();

            StreamingCallback callback = callbackFactory.forExecution(RUN_ID, NODE_ID, 0, null);
            callback.onToolResult(result);

            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(eventBus, atLeast(1)).publish(anyString(), messageCaptor.capture());
            String message = messageCaptor.getValue();

            // The multi-MB base64 must never hit the Redis stream...
            assertThat(message).doesNotContain("HEAVYVISIONBASE64");
            // ...but the light metadata + a count summary survive for the frontend.
            assertThat(message).contains("iconSlug");
            assertThat(message).contains(ToolMediaMetadata.MEDIA_SUMMARY_KEY);
        }
    }
}
