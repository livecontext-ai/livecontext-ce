package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolResult;
import com.apimarketplace.agent.domain.UsageInfo;
import com.apimarketplace.agent.streaming.StreamingCallback;
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
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.mockingDetails;

/**
 * Tests for ConversationRedisStreamingCallback - the agent-service replacement for
 * conversation-service's AgentStreamingCallbackFactory + ThinkingMessageGenerator.
 *
 * Tests cover:
 * - forExecution() factory method
 * - onChunk, onThinking, onToolCall, onToolResult, onComplete, onError events
 * - Thinking section parsing (split by **title** pattern)
 * - Service approval detection (approval_needed + request_credential)
 * - shouldStop() via flag + Redis cancel key
 * - Ordered entries tracking
 */
@DisplayName("ConversationRedisStreamingCallback")
@ExtendWith(MockitoExtension.class)
class ConversationRedisStreamingCallbackTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private EventBus eventBus;

    @Mock
    private com.apimarketplace.conversation.client.ConversationClient conversationClient;

    @Mock
    private ActiveStreamRegistry activeStreamRegistry;

    private ObjectMapper objectMapper;
    private ConversationRedisStreamingCallback callbackFactory;

    private static final String STREAM_ID = "stream-123";
    private static final String CONVERSATION_ID = "conv-456";

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        // Mock opsForValue for the conv index set in forExecution()
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // Mock opsForList for content/tool buffering in onChunk/onToolCall/onToolResult
        ListOperations<String, String> listOps = mock(ListOperations.class);
        lenient().when(redisTemplate.opsForList()).thenReturn(listOps);
        lenient().when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);
        callbackFactory = new ConversationRedisStreamingCallback(
            redisTemplate, eventBus, objectMapper, conversationClient, activeStreamRegistry);
    }

    @Nested
    @DisplayName("forExecution()")
    class ForExecutionTests {

        @Test
        @DisplayName("should create a ConversationCallback instance")
        void shouldCreateCallback() {
            ConversationRedisStreamingCallback.ConversationCallback callback =
                callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);

            assertThat(callback).isNotNull();
            assertThat(callback).isInstanceOf(StreamingCallback.class);
        }

        @Test
        @DisplayName("should create independent callbacks for different conversations")
        void shouldCreateIndependentCallbacks() {
            var callback1 = callbackFactory.forExecution("stream-1", "conv-1");
            var callback2 = callbackFactory.forExecution("stream-2", "conv-2");

            assertThat(callback1).isNotSameAs(callback2);
        }

        @Test
        @DisplayName("should set conv index in Redis for stop signal propagation")
        void shouldSetConvIndex() {
            callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);

            verify(redisTemplate.opsForValue()).set(
                eq("stream:conv:" + CONVERSATION_ID),
                eq(STREAM_ID),
                eq(Duration.ofMinutes(30)));
        }

        @Test
        @DisplayName("should set conv index for sub-agent forExecution overload")
        void shouldSetConvIndexForSubAgent() {
            callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID,
                "parent-conv", "SubAgent", null, "agent-1", "run-1");

            verify(redisTemplate.opsForValue()).set(
                eq("stream:conv:" + CONVERSATION_ID),
                eq(STREAM_ID),
                eq(Duration.ofMinutes(30)));
        }

        @Test
        @DisplayName("should register the stream in conversation-service with the REAL model when the forExecution variant knows it (was hardcoded \"unknown\")")
        void shouldRegisterStreamWithRealModel() {
            callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID, "gpt-5.2");

            verify(conversationClient).registerStream(
                STREAM_ID, CONVERSATION_ID, "gpt-5.2", "remote-agent");
        }

        @Test
        @DisplayName("sub-agent forExecution variant with model should also register the real model")
        void shouldRegisterStreamWithRealModelForSubAgent() {
            callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID, "deepseek-chat",
                "parent-conv", "SubAgent", null, "agent-1", "run-1");

            verify(conversationClient).registerStream(
                STREAM_ID, CONVERSATION_ID, "deepseek-chat", "remote-agent");
        }

        @Test
        @DisplayName("should fall back to \"unknown\" when the variant has no model or the model is null")
        void shouldFallBackToUnknownModelWhenAbsent() {
            callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);
            verify(conversationClient).registerStream(
                STREAM_ID, CONVERSATION_ID, "unknown", "remote-agent");

            clearInvocations(conversationClient);
            callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID, (String) null);
            verify(conversationClient).registerStream(
                STREAM_ID, CONVERSATION_ID, "unknown", "remote-agent");
        }

        @Test
        @DisplayName("should not set conv index when conversationId is null")
        void shouldNotSetConvIndexWhenConvIdNull() {
            // Reset to track calls fresh
            reset(redisTemplate.opsForValue());

            var callback = callbackFactory.forExecution(STREAM_ID, null);
            assertThat(callback).isNotNull();
            // No set call when conversationId is null
            verify(redisTemplate.opsForValue(), never()).set(anyString(), anyString(), any(Duration.class));
        }
    }

    @Nested
    @DisplayName("onChunk()")
    class OnChunkTests {

        @Test
        @DisplayName("should publish content chunk to Redis channels")
        void shouldPublishContentChunk() {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);
            @SuppressWarnings("unchecked")
            ListOperations<String, String> listOps = mock(ListOperations.class);
            when(redisTemplate.opsForList()).thenReturn(listOps);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);
            callback.onChunk("Hello world");

            // Verify published to SSE channel via redisTemplate
            ArgumentCaptor<String> channelCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisTemplate, atLeast(1)).convertAndSend(channelCaptor.capture(), messageCaptor.capture());

            List<String> channels = channelCaptor.getAllValues();
            assertThat(channels).contains("stream:events:" + STREAM_ID);

            // Verify WS channel published via eventBus
            verify(eventBus, atLeast(1)).publish(eq("ws:conversation:" + CONVERSATION_ID), anyString());

            // Verify message contains content
            String json = messageCaptor.getAllValues().get(0);
            assertThat(json).contains("\"content\":\"Hello world\"");
            assertThat(json).contains("\"streamId\":\"" + STREAM_ID + "\"");
        }

        @Test
        @DisplayName("should accumulate content in Redis List for stop handler")
        void shouldAccumulateContentInRedisList() {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);
            @SuppressWarnings("unchecked")
            ListOperations<String, String> listOps = mock(ListOperations.class);
            when(redisTemplate.opsForList()).thenReturn(listOps);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);
            callback.onChunk("Hello");
            callback.onChunk(" world");

            // Verify content accumulated in Redis List
            verify(listOps).rightPush("stream:" + STREAM_ID + ":content", "Hello");
            verify(listOps).rightPush("stream:" + STREAM_ID + ":content", " world");
        }

        @Test
        @DisplayName("should still publish to Pub/Sub when Redis List write fails")
        void shouldPublishEvenWhenListWriteFails() {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);
            @SuppressWarnings("unchecked")
            ListOperations<String, String> listOps = mock(ListOperations.class);
            when(redisTemplate.opsForList()).thenReturn(listOps);
            when(listOps.rightPush(anyString(), anyString()))
                .thenThrow(new RuntimeException("Redis List error"));

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);
            callback.onChunk("Hello");

            // SSE Pub/Sub still works despite List failure
            verify(redisTemplate, atLeast(1)).convertAndSend(anyString(), anyString());
            // WS channel published via eventBus
            verify(eventBus, atLeast(1)).publish(eq("ws:conversation:" + CONVERSATION_ID), anyString());
        }
    }

    @Nested
    @DisplayName("onThinking()")
    class OnThinkingTests {

        @Test
        @DisplayName("should publish raw thinking chunk")
        void shouldPublishRawThinkingChunk() {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);
            callback.onThinking("Analyzing the problem...");

            // Verify SSE channel via redisTemplate
            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisTemplate, atLeast(1)).convertAndSend(anyString(), messageCaptor.capture());
            // Verify WS channel via eventBus
            verify(eventBus, atLeast(1)).publish(eq("ws:conversation:" + CONVERSATION_ID), anyString());

            String json = messageCaptor.getAllValues().get(0);
            assertThat(json).contains("\"thinking\":\"Analyzing the problem...\"");
        }

        @Test
        @DisplayName("should skip null or empty thinking")
        void shouldSkipNullOrEmptyThinking() {
            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);

            callback.onThinking(null);
            callback.onThinking("");

            verify(redisTemplate, never()).convertAndSend(anyString(), anyString());
        }

        @Test
        @DisplayName("should parse and emit complete thinking sections with **title** pattern")
        void shouldParseAndEmitThinkingSections() {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);

            // Send thinking with 2 sections (need 2 titles for first section to be "complete")
            callback.onThinking("**Analysis** Looking at the problem... **Solution** Here is what we do...");

            // Flush remaining
            callback.onComplete(CompletionResponse.text("Done"));

            // Should have published: raw thinking, thinking_section(s), done
            assertThat(callback.getThinkingSections()).isNotEmpty();
        }

        @Test
        @DisplayName("should flush thinking buffer on complete")
        void shouldFlushBufferOnComplete() {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);
            callback.onThinking("**Single Section** Some content");
            callback.onComplete(CompletionResponse.text("Done"));

            // Single section should be emitted on flush
            assertThat(callback.getThinkingSections()).hasSize(1);
            assertThat(callback.getThinkingSections().get(0).get("title")).isEqualTo("Single Section");
        }

        @Test
        @DisplayName("should handle thinking without titles as single section")
        void shouldHandleThinkingWithoutTitles() {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);
            callback.onThinking("Just some plain thinking text");
            callback.onComplete(CompletionResponse.text("Done"));

            assertThat(callback.getThinkingSections()).hasSize(1);
            assertThat(callback.getThinkingSections().get(0).get("title")).isEqualTo("");
            assertThat(callback.getThinkingSections().get(0).get("content")).isEqualTo("Just some plain thinking text");
        }
    }

    @Nested
    @DisplayName("onToolCall()")
    class OnToolCallTests {

        @Test
        @DisplayName("should publish tool call event")
        void shouldPublishToolCallEvent() {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);
            ToolCall toolCall = ToolCall.builder()
                .id("call_123")
                .toolName("web_search")
                .arguments(Map.of("query", "test"))
                .build();

            callback.onToolCall(toolCall);

            // Verify SSE channel via redisTemplate
            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisTemplate, atLeast(1)).convertAndSend(anyString(), messageCaptor.capture());
            // Verify WS channel via eventBus
            verify(eventBus, atLeast(1)).publish(eq("ws:conversation:" + CONVERSATION_ID), anyString());

            String json = messageCaptor.getAllValues().get(0);
            assertThat(json).contains("\"toolName\":\"web_search\"");
            assertThat(json).contains("\"toolId\":\"call_123\"");
        }

        @Test
        @DisplayName("should track tool call in ordered entries")
        void shouldTrackInOrderedEntries() {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);
            ToolCall toolCall = ToolCall.builder()
                .id("call_1")
                .toolName("catalog")
                .arguments(Map.of("action", "search"))
                .build();

            callback.onToolCall(toolCall);

            assertThat(callback.getOrderedEntries()).hasSize(1);
            assertThat(callback.getOrderedEntries().get(0).get("type")).isEqualTo("tool_call");
            assertThat(callback.getOrderedEntries().get(0).get("toolName")).isEqualTo("catalog");
        }

        @Test
        @DisplayName("should flush thinking buffer before tool call")
        void shouldFlushThinkingBeforeToolCall() {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);
            callback.onThinking("**Analysis** Some thinking...");

            ToolCall toolCall = ToolCall.builder()
                .id("call_1")
                .toolName("test")
                .arguments(Map.of())
                .build();
            callback.onToolCall(toolCall);

            // Ordered entries should have thinking first, then tool call
            assertThat(callback.getOrderedEntries()).hasSize(2);
            assertThat(callback.getOrderedEntries().get(0).get("type")).isEqualTo("thinking");
            assertThat(callback.getOrderedEntries().get(1).get("type")).isEqualTo("tool_call");
        }
    }

    @Nested
    @DisplayName("onToolResult()")
    class OnToolResultTests {

        @Test
        @DisplayName("should publish normal tool result event")
        void shouldPublishNormalToolResult() {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);
            ToolResult result = ToolResult.builder()
                .toolCall(ToolCall.builder().id("call_1").toolName("web_search").build())
                .success(true)
                .content("{\"results\": []}")
                .durationMs(150L)
                .build();

            callback.onToolResult(result);

            // Verify SSE channel via redisTemplate
            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisTemplate, atLeast(1)).convertAndSend(anyString(), messageCaptor.capture());
            // Verify WS channel via eventBus
            verify(eventBus, atLeast(1)).publish(eq("ws:conversation:" + CONVERSATION_ID), anyString());

            String json = messageCaptor.getAllValues().get(0);
            assertThat(json).contains("\"toolName\":\"web_search\"");
            assertThat(json).contains("\"success\":true");
        }

        @Test
        @DisplayName("should emit approval_needed card live WITHOUT stopping the agent loop")
        void shouldEmitApprovalNeededCardLiveWithoutStopping() {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);
            String approvalContent = "{\"status\":\"approval_needed\",\"serviceType\":\"github\"," +
                "\"serviceName\":\"GitHub\",\"iconSlug\":\"github\",\"toolName\":\"create_issue\"," +
                "\"toolId\":\"tool_1\",\"message\":\"Please approve GitHub access\"}";

            ToolResult result = ToolResult.builder()
                .toolCall(ToolCall.builder().id("call_1").toolName("catalog").build())
                .success(true)
                .content(approvalContent)
                .build();

            callback.onToolResult(result);

            // Async: the run must NOT be paused by an approval card.
            assertThat(callback.shouldStop()).isFalse();

            // The service_approval_required event was published live.
            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisTemplate, atLeast(1)).convertAndSend(anyString(), messageCaptor.capture());
            String published = String.join("\n", messageCaptor.getAllValues());
            assertThat(published).contains("\"services\":");
            assertThat(published).contains("github");
        }

        @Test
        @DisplayName("should emit request_credential service-approval card live WITHOUT stopping")
        void shouldEmitRequestCredentialServiceApprovalLiveWithoutStopping() {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);

            Map<String, Object> metadata = Map.of(
                "serviceApprovalRequested", true,
                "services", List.of(Map.of(
                    "serviceType", "slack",
                    "serviceName", "Slack",
                    "iconSlug", "slack",
                    "toolName", "send_message",
                    "toolId", "tool_1",
                    "description", "Send Slack messages"
                )),
                "reason", "Slack access needed",
                "needsAttention", false
            );

            ToolResult result = ToolResult.builder()
                .toolCall(ToolCall.builder().id("call_1").toolName("request_credential").build())
                .success(true)
                .content("Credentials requested")
                .metadata(metadata)
                .build();

            callback.onToolResult(result);

            assertThat(callback.shouldStop()).isFalse();
            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisTemplate, atLeast(1)).convertAndSend(anyString(), messageCaptor.capture());
            String published = String.join("\n", messageCaptor.getAllValues());
            assertThat(published).contains("Slack access needed");
            assertThat(published).contains("slack");
        }

        @Test
        @DisplayName("should publish toolAuthorizationRequired card live WITHOUT stopping the loop")
        void shouldEmitToolAuthorizationLiveWithoutStopping() {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);

            Map<String, Object> metadata = Map.of(
                "toolAuthorizationRequired", true,
                "rule", "application:acquire",
                "toolName", "application",
                "action", "acquire",
                "toolCallId", "call_1",
                "argsSummary", "{\"action\":\"acquire\"}",
                "applicationId", "pub-123"
            );

            ToolResult result = ToolResult.builder()
                .toolCall(ToolCall.builder().id("call_1").toolName("application").build())
                .success(true)
                .content("{\"status\":\"authorization_required\"}")
                .metadata(metadata)
                .build();

            callback.onToolResult(result);

            // Async: emitting an authorization card must not pause the run.
            assertThat(callback.shouldStop()).isFalse();

            // The distinct tool_authorization event was published (carries the rule)
            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisTemplate, atLeast(1)).convertAndSend(anyString(), messageCaptor.capture());
            String published = String.join("\n", messageCaptor.getAllValues());
            assertThat(published).contains("toolAuthorization");
            assertThat(published).contains("application:acquire");
            // The publication id rides along so the card can open the install modal
            assertThat(published).contains("applicationId");
            assertThat(published).contains("pub-123");
        }

        @Test
        @DisplayName("should emit several distinct cards in a single turn (parallel approvals)")
        void shouldEmitMultipleDistinctCardsInOneTurn() {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);

            // First: a service approval for Gmail.
            callback.onToolResult(ToolResult.builder()
                .toolCall(ToolCall.builder().id("c1").toolName("request_credential").build())
                .success(true)
                .metadata(Map.of(
                    "serviceApprovalRequested", true,
                    "services", List.of(Map.of("serviceType", "gmail", "serviceName", "Gmail",
                        "iconSlug", "gmail")),
                    "reason", "Gmail access"))
                .build());

            // Second: a tool authorization for application:acquire.
            callback.onToolResult(ToolResult.builder()
                .toolCall(ToolCall.builder().id("c2").toolName("application").build())
                .success(true)
                .metadata(Map.of(
                    "toolAuthorizationRequired", true,
                    "rule", "application:acquire",
                    "toolName", "application"))
                .build());

            assertThat(callback.shouldStop()).isFalse();
            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisTemplate, atLeast(1)).convertAndSend(anyString(), messageCaptor.capture());
            String published = String.join("\n", messageCaptor.getAllValues());
            // Both cards reached the wire.
            assertThat(published).contains("gmail");
            assertThat(published).contains("application:acquire");
        }

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("should keep normal connect and forced reconnect credential cards separate")
        void shouldKeepConnectAndForcedReconnectCardsSeparate() throws Exception {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);
            Map<String, Object> gmail = Map.of(
                "serviceType", "gmail",
                "serviceName", "Gmail",
                "iconSlug", "gmail"
            );

            callback.onToolResult(ToolResult.builder()
                .toolCall(ToolCall.builder().id("c1").toolName("request_credential").build())
                .success(true)
                .metadata(Map.of(
                    "serviceApprovalRequested", true,
                    "services", List.of(gmail),
                    "reason", "Connect Gmail",
                    "needsAttention", false
                ))
                .build());

            callback.onToolResult(ToolResult.builder()
                .toolCall(ToolCall.builder().id("c2").toolName("request_credential").build())
                .success(true)
                .metadata(Map.of(
                    "serviceApprovalRequested", true,
                    "services", List.of(gmail),
                    "reason", "Reconnect Gmail",
                    "needsAttention", true
                ))
                .build());

            assertThat(callback.shouldStop()).isFalse();
            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisTemplate, atLeast(1)).convertAndSend(anyString(), messageCaptor.capture());

            List<Map<String, Object>> approvalEvents = messageCaptor.getAllValues().stream()
                .map(json -> {
                    try {
                        return (Map<String, Object>) objectMapper.readValue(json, Map.class);
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                })
                .filter(event -> event.containsKey("services") && event.containsKey("reason"))
                .toList();

            assertThat(approvalEvents).hasSize(2);
            assertThat(approvalEvents)
                .extracting(event -> event.get("needsAttention"))
                .containsExactlyInAnyOrder(false, true);
        }

        @Test
        @DisplayName("should dedup an identical gated call (same rule) to a single card per turn")
        void shouldDedupIdenticalAuthorizationCard() {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);
            Map<String, Object> metadata = Map.of(
                "toolAuthorizationRequired", true,
                "rule", "application:acquire",
                "toolName", "application");

            // The agent retries the same gated action twice in one turn.
            callback.onToolResult(ToolResult.builder()
                .toolCall(ToolCall.builder().id("c1").toolName("application").build())
                .success(true).metadata(metadata).build());
            callback.onToolResult(ToolResult.builder()
                .toolCall(ToolCall.builder().id("c2").toolName("application").build())
                .success(true).metadata(metadata).build());

            // Only ONE tool_authorization event (with toolAuthorization payload) was published.
            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisTemplate, atLeast(1)).convertAndSend(anyString(), messageCaptor.capture());
            long authEvents = messageCaptor.getAllValues().stream()
                .filter(m -> m.contains("toolAuthorization")).count();
            assertThat(authEvents).isEqualTo(1);
        }

        @Test
        @DisplayName("should not emit a card for normal request_credential without approval flag")
        void shouldNotEmitCardForNormalRequestCredential() {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);

            ToolResult result = ToolResult.builder()
                .toolCall(ToolCall.builder().id("call_1").toolName("request_credential").build())
                .success(true)
                .content("Credential stored")
                .metadata(Map.of("serviceApprovalRequested", false))
                .build();

            callback.onToolResult(result);

            assertThat(callback.shouldStop()).isFalse();
            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisTemplate, atLeast(1)).convertAndSend(anyString(), messageCaptor.capture());
            String published = String.join("\n", messageCaptor.getAllValues());
            assertThat(published).doesNotContain("\"services\":");
            assertThat(published).doesNotContain("toolAuthorization");
        }

        @Test
        @DisplayName("should pass through metadata fields in tool result event")
        void shouldPassThroughMetadataFields() {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);
            ToolResult result = ToolResult.builder()
                .toolCall(ToolCall.builder().id("call_1").toolName("visualize").build())
                .success(true)
                .content("{}")
                .metadata(Map.of(
                    "iconSlug", "chart",
                    "label", "Sales Chart",
                    "visualization", Map.of("type", "chart")
                ))
                .build();

            callback.onToolResult(result);

            // Verify SSE channel via redisTemplate
            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisTemplate, atLeast(1)).convertAndSend(anyString(), messageCaptor.capture());
            // Verify WS channel via eventBus
            verify(eventBus, atLeast(1)).publish(eq("ws:conversation:" + CONVERSATION_ID), anyString());

            String json = messageCaptor.getAllValues().get(0);
            assertThat(json).contains("\"iconSlug\":\"chart\"");
            assertThat(json).contains("\"label\":\"Sales Chart\"");
        }
    }

    @Nested
    @DisplayName("onComplete()")
    class OnCompleteTests {

        @Test
        @DisplayName("should publish done event with content and tokens")
        void shouldPublishDoneEvent() {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);
            CompletionResponse response = CompletionResponse.builder()
                .content("Final response")
                .finishReason("stop")
                .usage(UsageInfo.builder().totalTokens(500).build())
                .build();

            callback.onComplete(response);

            // Verify SSE channel via redisTemplate
            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisTemplate, atLeast(1)).convertAndSend(anyString(), messageCaptor.capture());
            // Verify WS channel via eventBus
            verify(eventBus, atLeast(1)).publish(eq("ws:conversation:" + CONVERSATION_ID), anyString());

            String json = messageCaptor.getAllValues().get(0);
            assertThat(json).contains("\"fullContent\":\"Final response\"");
            assertThat(json).contains("\"totalTokens\":500");
        }

        @Test
        @DisplayName("should still publish a done event after an approval card (async - no early skip)")
        void shouldStillPublishDoneAfterApprovalCard() {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);

            // An approval card is raised mid-stream - this no longer ends the stream early.
            String approvalContent = "{\"status\":\"approval_needed\",\"serviceType\":\"github\"," +
                "\"serviceName\":\"GitHub\",\"message\":\"Approve\"}";
            callback.onToolResult(ToolResult.builder()
                .toolCall(ToolCall.builder().id("call_1").toolName("test").build())
                .success(true)
                .content(approvalContent)
                .build());

            // The agent finishes its turn normally.
            callback.onComplete(CompletionResponse.text("All set"));

            // A real done event (fullContent) must be published on completion.
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(redisTemplate, atLeast(1)).convertAndSend(anyString(), captor.capture());
            String published = String.join("\n", captor.getAllValues());
            assertThat(published).contains("\"fullContent\":\"All set\"");
        }

        @Test
        @DisplayName("should handle null response gracefully")
        void shouldHandleNullResponse() {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);
            callback.onComplete(null);

            // Verify SSE channel via redisTemplate
            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisTemplate, atLeast(1)).convertAndSend(anyString(), messageCaptor.capture());
            // Verify WS channel via eventBus
            verify(eventBus, atLeast(1)).publish(eq("ws:conversation:" + CONVERSATION_ID), anyString());

            String json = messageCaptor.getAllValues().get(0);
            assertThat(json).contains("\"fullContent\":\"\"");
            assertThat(json).contains("\"totalTokens\":0");
        }
    }

    @Nested
    @DisplayName("onError()")
    class OnErrorTests {

        @Test
        @DisplayName("should publish error event")
        void shouldPublishErrorEvent() {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);
            callback.onError("LLM provider timeout");

            // Verify SSE channel via redisTemplate
            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisTemplate, atLeast(1)).convertAndSend(anyString(), messageCaptor.capture());
            // Verify WS channel via eventBus
            verify(eventBus, atLeast(1)).publish(eq("ws:conversation:" + CONVERSATION_ID), anyString());

            String json = messageCaptor.getAllValues().get(0);
            assertThat(json).contains("\"error\":\"LLM provider timeout\"");
            assertThat(json).contains("\"errorCode\":\"STREAM_ERROR\"");
            assertThat(json).contains("\"retryable\":true");
        }
    }

    @Nested
    @DisplayName("graceful drain (ActiveStreamRegistry + interruptFromShutdown)")
    class GracefulDrainTests {

        @Test
        @DisplayName("forExecution should register the stream with an interruption handle in the registry")
        void shouldRegisterStreamInRegistry() {
            callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);

            verify(activeStreamRegistry).register(eq(STREAM_ID), any(Runnable.class));
        }

        @Test
        @DisplayName("sub-agent forExecution overload should also register the stream")
        void shouldRegisterStreamForSubAgentOverload() {
            callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID,
                "parent-conv", "SubBot", null, "agent-1", "run-1");

            verify(activeStreamRegistry).register(eq(STREAM_ID), any(Runnable.class));
        }

        @Test
        @DisplayName("onComplete should unregister the stream (terminal path)")
        void shouldUnregisterOnComplete() {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);
            callback.onComplete(CompletionResponse.text("Done"));

            verify(activeStreamRegistry).unregister(STREAM_ID);
        }

        @Test
        @DisplayName("onError should unregister the stream (terminal path)")
        void shouldUnregisterOnError() {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);
            callback.onError("LLM provider timeout");

            verify(activeStreamRegistry).unregister(STREAM_ID);
        }

        @Test
        @DisplayName("interruptFromShutdown should publish an INTERRUPTED error event, finalize as INTERRUPTED and unregister")
        void interruptFromShutdownPublishesAndFinalizes() {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);
            callback.interruptFromShutdown();

            // Error-shaped event with the dedicated INTERRUPTED code on the SSE channel
            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisTemplate, atLeast(1)).convertAndSend(
                eq("stream:events:" + STREAM_ID), messageCaptor.capture());
            String published = String.join("\n", messageCaptor.getAllValues());
            assertThat(published).contains("\"errorCode\":\"INTERRUPTED\"");
            assertThat(published).contains("\"retryable\":true");
            assertThat(published).contains("Service restarting - partial response saved");

            // INTERRUPTED finalize makes conversation-service persist the partial content
            verify(conversationClient).finalizeStream(STREAM_ID, "INTERRUPTED");
            verify(activeStreamRegistry).unregister(STREAM_ID);
        }

        @Test
        @DisplayName("interruptFromShutdown should be idempotent - second call publishes/finalizes nothing")
        void interruptFromShutdownIsIdempotent() {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);
            callback.interruptFromShutdown();
            callback.interruptFromShutdown();

            verify(conversationClient, times(1)).finalizeStream(STREAM_ID, "INTERRUPTED");
            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisTemplate, atLeast(1)).convertAndSend(anyString(), messageCaptor.capture());
            long interruptedEvents = messageCaptor.getAllValues().stream()
                .filter(m -> m.contains("\"errorCode\":\"INTERRUPTED\"")).count();
            assertThat(interruptedEvents).isEqualTo(1);
        }

        @Test
        @DisplayName("the handle registered in the registry should trigger interruptFromShutdown when run")
        void registeredHandleTriggersInterruptFromShutdown() {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

            callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);

            ArgumentCaptor<Runnable> handleCaptor = ArgumentCaptor.forClass(Runnable.class);
            verify(activeStreamRegistry).register(eq(STREAM_ID), handleCaptor.capture());

            // Simulate the drain-expiry path: the registry runs the handle.
            handleCaptor.getValue().run();

            verify(conversationClient).finalizeStream(STREAM_ID, "INTERRUPTED");
        }

        @Test
        @DisplayName("a late interruptFromShutdown racing a normal completion stays bounded to one INTERRUPTED finalize")
        void interruptAfterCompleteStillFinalizesOnlyInterruptedOnce() {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);
            callback.onComplete(CompletionResponse.text("Done"));
            // A racing interruptAll() after completion: at most one INTERRUPTED finalize.
            callback.interruptFromShutdown();
            callback.interruptFromShutdown();

            verify(conversationClient, times(1)).finalizeStream(STREAM_ID, "COMPLETED");
            verify(conversationClient, times(1)).finalizeStream(STREAM_ID, "INTERRUPTED");
        }
    }

    @Nested
    @DisplayName("shouldStop()")
    class ShouldStopTests {

        @Test
        @DisplayName("should return false initially")
        void shouldReturnFalseInitially() {
            @SuppressWarnings("unchecked")
            var valueOps = mock(org.springframework.data.redis.core.ValueOperations.class);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);

            // No cancel key exists - detection is now a single GET on the own cancel key
            when(valueOps.get("agent:cancel:" + STREAM_ID)).thenReturn(null);

            assertThat(callback.shouldStop()).isFalse();
        }

        @Test
        @DisplayName("should return true when Redis cancel key exists")
        void shouldReturnTrueWhenCancelKeyExists() {
            @SuppressWarnings("unchecked")
            var valueOps = mock(org.springframework.data.redis.core.ValueOperations.class);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);

            // Cancel key present → GET returns a non-null value → stop
            when(valueOps.get("agent:cancel:" + STREAM_ID)).thenReturn("1");

            assertThat(callback.shouldStop()).isTrue();
        }

        @Test
        @DisplayName("should NOT stop after a service-approval card (async)")
        void shouldNotStopAfterServiceApprovalCard() {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);
            @SuppressWarnings("unchecked")
            var valueOps = mock(org.springframework.data.redis.core.ValueOperations.class);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);

            String approvalContent = "{\"status\":\"approval_needed\",\"serviceType\":\"test\"," +
                "\"serviceName\":\"Test\",\"message\":\"Approve\"}";
            callback.onToolResult(ToolResult.builder()
                .toolCall(ToolCall.builder().id("call_1").toolName("test").build())
                .success(true)
                .content(approvalContent)
                .build());

            // No cancel key set → the run continues; the card alone never stops it.
            when(valueOps.get("agent:cancel:" + STREAM_ID)).thenReturn(null);
            assertThat(callback.shouldStop()).isFalse();
        }

        @Test
        @DisplayName("should handle Redis errors gracefully (return false)")
        void shouldHandleRedisErrorsGracefully() {
            @SuppressWarnings("unchecked")
            var valueOps = mock(org.springframework.data.redis.core.ValueOperations.class);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);

            when(valueOps.get(anyString())).thenThrow(new RuntimeException("Redis down"));

            // Should not throw, should return false (fail-open)
            assertThat(callback.shouldStop()).isFalse();
        }

        @Test
        @DisplayName("should propagate stop from parent cancel key for sub-agent")
        void shouldPropagateStopFromParentCancelKey() {
            @SuppressWarnings("unchecked")
            var valueOps = mock(org.springframework.data.redis.core.ValueOperations.class);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);

            var callback = callbackFactory.forExecution(
                STREAM_ID, CONVERSATION_ID, "parent-conv-1", "SubBot", null, "agent-1", null);

            // Own cancel key does NOT exist (single GET)
            when(valueOps.get("agent:cancel:" + STREAM_ID)).thenReturn(null);
            // Parent's stream lookup: stream:conv:parent-conv-1 → parent-stream-99
            when(valueOps.get("stream:conv:parent-conv-1")).thenReturn("parent-stream-99");
            // Parent's cancel key EXISTS
            when(valueOps.get("agent:cancel:parent-stream-99")).thenReturn("1");

            assertThat(callback.shouldStop()).isTrue();
        }

        @Test
        @DisplayName("should NOT propagate stop when parent has no cancel key")
        void shouldNotPropagateStopWhenParentNotCancelled() {
            @SuppressWarnings("unchecked")
            var valueOps = mock(org.springframework.data.redis.core.ValueOperations.class);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);

            var callback = callbackFactory.forExecution(
                STREAM_ID, CONVERSATION_ID, "parent-conv-1", "SubBot", null, "agent-1", null);

            // Own cancel key does NOT exist (single GET)
            when(valueOps.get("agent:cancel:" + STREAM_ID)).thenReturn(null);
            // Parent's stream lookup: stream:conv:parent-conv-1 → parent-stream-99
            when(valueOps.get("stream:conv:parent-conv-1")).thenReturn("parent-stream-99");
            // Parent's cancel key does NOT exist
            when(valueOps.get("agent:cancel:parent-stream-99")).thenReturn(null);

            assertThat(callback.shouldStop()).isFalse();
        }

        @Test
        @DisplayName("should NOT check parent when parentConversationId is null")
        void shouldNotCheckParentWhenNoParentConversationId() {
            @SuppressWarnings("unchecked")
            var valueOps = mock(org.springframework.data.redis.core.ValueOperations.class);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);

            // Reset invocation count after forExecution (which uses opsForValue for conv index)
            clearInvocations(redisTemplate);
            clearInvocations(valueOps);

            // Re-stub after clearInvocations
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("agent:cancel:" + STREAM_ID)).thenReturn(null);

            assertThat(callback.shouldStop()).isFalse();
            // opsForValue is called for the single GET on the own cancel key,
            // but should NOT be called for parent stream lookup (no parent)
            verify(valueOps, times(1)).get(anyString()); // only own cancel key
            verify(valueOps, never()).get(startsWith("stream:conv:"));
        }

        @Test
        @DisplayName("should handle missing parent stream index gracefully")
        void shouldHandleMissingParentStreamIndex() {
            @SuppressWarnings("unchecked")
            var valueOps = mock(org.springframework.data.redis.core.ValueOperations.class);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);

            var callback = callbackFactory.forExecution(
                STREAM_ID, CONVERSATION_ID, "parent-conv-1", "SubBot", null, "agent-1", null);

            // Own cancel key does NOT exist (single GET)
            when(valueOps.get("agent:cancel:" + STREAM_ID)).thenReturn(null);
            // Parent's stream index is gone (expired TTL)
            when(valueOps.get("stream:conv:parent-conv-1")).thenReturn(null);

            assertThat(callback.shouldStop()).isFalse();
        }

        @Test
        @DisplayName("should cache stopped=true after parent stop detected")
        void shouldCacheStoppedAfterParentStop() {
            @SuppressWarnings("unchecked")
            var valueOps = mock(org.springframework.data.redis.core.ValueOperations.class);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);

            var callback = callbackFactory.forExecution(
                STREAM_ID, CONVERSATION_ID, "parent-conv-1", "SubBot", null, "agent-1", null);

            // Own cancel key does NOT exist (single GET)
            when(valueOps.get("agent:cancel:" + STREAM_ID)).thenReturn(null);
            when(valueOps.get("stream:conv:parent-conv-1")).thenReturn("parent-stream-99");
            when(valueOps.get("agent:cancel:parent-stream-99")).thenReturn("1");

            // First call detects parent stop
            assertThat(callback.shouldStop()).isTrue();

            // Second call should return true immediately without Redis calls
            // (the local stopped flag short-circuits before any Redis access).
            // Reset mock to verify no more Redis calls
            reset(redisTemplate);
            assertThat(callback.shouldStop()).isTrue();
            verify(redisTemplate, never()).opsForValue();
        }

        @Test
        @DisplayName("should detect workflow cancel via workflowRunId")
        void shouldDetectWorkflowCancel() {
            @SuppressWarnings("unchecked")
            var valueOps = mock(org.springframework.data.redis.core.ValueOperations.class);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);

            var callback = callbackFactory.forExecution(
                STREAM_ID, CONVERSATION_ID, null, null, null, null, "run-abc-123");

            // Own cancel key does NOT exist (single GET)
            when(valueOps.get("agent:cancel:" + STREAM_ID)).thenReturn(null);
            // Workflow cancel key EXISTS
            when(valueOps.get("workflow:cancel:run-abc-123")).thenReturn("1");

            assertThat(callback.shouldStop()).isTrue();
        }

        @Test
        @DisplayName("should NOT stop when workflow cancel key is absent")
        void shouldNotStopWhenWorkflowNotCancelled() {
            @SuppressWarnings("unchecked")
            var valueOps = mock(org.springframework.data.redis.core.ValueOperations.class);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);

            var callback = callbackFactory.forExecution(
                STREAM_ID, CONVERSATION_ID, null, null, null, null, "run-abc-123");

            // Own cancel key does NOT exist (single GET)
            when(valueOps.get("agent:cancel:" + STREAM_ID)).thenReturn(null);
            when(valueOps.get("workflow:cancel:run-abc-123")).thenReturn(null);

            assertThat(callback.shouldStop()).isFalse();
        }

        @Test
        @DisplayName("should check both parent conversation and workflow cancel keys")
        void shouldCheckBothParentAndWorkflowCancelKeys() {
            @SuppressWarnings("unchecked")
            var valueOps = mock(org.springframework.data.redis.core.ValueOperations.class);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);

            // Sub-agent with BOTH parent conversation and workflow run context
            var callback = callbackFactory.forExecution(
                STREAM_ID, CONVERSATION_ID, "parent-conv-1", "SubBot", null, "agent-1", "run-xyz");

            // Own cancel key: no (single GET)
            when(valueOps.get("agent:cancel:" + STREAM_ID)).thenReturn(null);
            // Parent conversation cancel key: no
            when(valueOps.get("stream:conv:parent-conv-1")).thenReturn("parent-stream-99");
            when(valueOps.get("agent:cancel:parent-stream-99")).thenReturn(null);
            // Workflow cancel key: YES
            when(valueOps.get("workflow:cancel:run-xyz")).thenReturn("1");

            assertThat(callback.shouldStop()).isTrue();
        }

        @Test
        @DisplayName("should NOT check workflow cancel when workflowRunId is null")
        void shouldNotCheckWorkflowWhenNoRunId() {
            @SuppressWarnings("unchecked")
            var valueOps = mock(org.springframework.data.redis.core.ValueOperations.class);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);

            // Reset invocation count after forExecution (which uses opsForValue for conv index)
            clearInvocations(valueOps);
            when(valueOps.get("agent:cancel:" + STREAM_ID)).thenReturn(null);

            assertThat(callback.shouldStop()).isFalse();
            // Only 1 GET (own cancel key); no workflow key read when runId is null.
            verify(valueOps, times(1)).get(anyString());
            verify(valueOps, never()).get(startsWith("workflow:cancel:"));
        }

        @Test
        @DisplayName("throttles Redis polling to at most once per interval under rapid calls")
        void throttlesRedisPollingUnderRapidCalls() {
            @SuppressWarnings("unchecked")
            var valueOps = mock(org.springframework.data.redis.core.ValueOperations.class);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);

            // Inject a fake clock so the throttle window is deterministic.
            long[] t = {1000};
            callback.configureCancelPollForTest(250, () -> t[0]);

            // forExecution wrote the conv index via opsForValue - reset so we only count polls.
            clearInvocations(valueOps);
            when(valueOps.get("agent:cancel:" + STREAM_ID)).thenReturn(null);

            // First call (lastCancelPollAt starts at 0) always polls Redis once.
            assertThat(callback.shouldStop()).isFalse();
            verify(valueOps, times(1)).get("agent:cancel:" + STREAM_ID);

            // Second call at the SAME clock time is within the 250ms window → skipped,
            // no additional Redis read.
            assertThat(callback.shouldStop()).isFalse();
            verify(valueOps, times(1)).get("agent:cancel:" + STREAM_ID);

            // Advance the clock past the window → polls again.
            t[0] = 1300;
            assertThat(callback.shouldStop()).isFalse();
            verify(valueOps, times(2)).get("agent:cancel:" + STREAM_ID);
        }

        @Test
        @DisplayName("detects own cancel key with a single GET and never calls hasKey")
        void detectsOwnCancelKeyWithSingleGetNeverHasKey() {
            @SuppressWarnings("unchecked")
            var valueOps = mock(org.springframework.data.redis.core.ValueOperations.class);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);

            clearInvocations(valueOps);
            // No cancel key present.
            when(valueOps.get("agent:cancel:" + STREAM_ID)).thenReturn(null);

            assertThat(callback.shouldStop()).isFalse();
            // Own cancel key read exactly once via GET - and hasKey is never used.
            verify(valueOps, times(1)).get("agent:cancel:" + STREAM_ID);
            verify(redisTemplate, never()).hasKey(anyString());
        }

        @Test
        @DisplayName("throttle gates the WHOLE body - own + workflow key reads are skipped within the window")
        void throttleGatesWorkflowKeyReadToo() {
            @SuppressWarnings("unchecked")
            var valueOps = mock(org.springframework.data.redis.core.ValueOperations.class);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);

            // Workflow-run stream: shouldStop checks BOTH the own key and workflow:cancel.
            var callback = callbackFactory.forExecution(
                STREAM_ID, CONVERSATION_ID, null, null, null, null, "run-abc-123");

            long[] t = {1000};
            callback.configureCancelPollForTest(250, () -> t[0]);
            clearInvocations(valueOps);
            when(valueOps.get("agent:cancel:" + STREAM_ID)).thenReturn(null);
            when(valueOps.get("workflow:cancel:run-abc-123")).thenReturn(null);

            // First call polls both keys once.
            assertThat(callback.shouldStop()).isFalse();
            verify(valueOps, times(1)).get("agent:cancel:" + STREAM_ID);
            verify(valueOps, times(1)).get("workflow:cancel:run-abc-123");

            // Second call within the window: the throttle wraps the ENTIRE body, so
            // NEITHER the own key nor the workflow key is read again.
            assertThat(callback.shouldStop()).isFalse();
            verify(valueOps, times(1)).get("agent:cancel:" + STREAM_ID);
            verify(valueOps, times(1)).get("workflow:cancel:run-abc-123");
        }
    }

    @Nested
    @DisplayName("data extraction")
    class DataExtractionTests {

        @Test
        @DisplayName("should track ordered entries for thinking and tool calls")
        void shouldTrackOrderedEntries() {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);

            // Add thinking
            callback.onThinking("**Step 1** Analyzing...");

            // Add tool call (this flushes thinking)
            ToolCall toolCall = ToolCall.builder()
                .id("call_1").toolName("search").arguments(Map.of()).build();
            callback.onToolCall(toolCall);

            // Add more thinking
            callback.onThinking("**Step 2** Processing results...");
            callback.onComplete(CompletionResponse.text("Done"));

            List<Map<String, Object>> entries = callback.getOrderedEntries();
            assertThat(entries).hasSizeGreaterThanOrEqualTo(3);

            // First entry should be thinking
            assertThat(entries.get(0).get("type")).isEqualTo("thinking");
            // Then tool call
            assertThat(entries.get(1).get("type")).isEqualTo("tool_call");
            // Then more thinking
            assertThat(entries.get(2).get("type")).isEqualTo("thinking");
        }

        @Test
        @DisplayName("should return unmodifiable lists")
        void shouldReturnUnmodifiableLists() {
            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);

            assertThat(callback.getThinkingSections()).isUnmodifiable();
            assertThat(callback.getOrderedEntries()).isUnmodifiable();
        }

        @Test
        @DisplayName("should track stream start time")
        void shouldTrackStreamStartTime() {
            long beforeCreate = System.currentTimeMillis();
            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);
            long afterCreate = System.currentTimeMillis();

            assertThat(callback.getStreamStartTime())
                .isGreaterThanOrEqualTo(beforeCreate)
                .isLessThanOrEqualTo(afterCreate);
        }
    }

    @Nested
    @DisplayName("thinking section parsing")
    class ThinkingSectionParsingTests {

        @Test
        @DisplayName("should parse multiple titled sections")
        void shouldParseMultipleSections() {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);
            callback.onThinking("**Analysis** Looking at the code structure. " +
                "**Implementation** Adding the new feature. " +
                "**Testing** Writing unit tests.");
            callback.onComplete(CompletionResponse.text("Done"));

            List<Map<String, Object>> sections = callback.getThinkingSections();
            assertThat(sections).hasSizeGreaterThanOrEqualTo(2);

            // Verify titles are extracted
            boolean hasAnalysis = sections.stream()
                .anyMatch(s -> "Analysis".equals(s.get("title")));
            assertThat(hasAnalysis).isTrue();
        }

        @Test
        @DisplayName("should handle text before first title")
        void shouldHandleTextBeforeFirstTitle() {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);
            callback.onThinking("Some preamble text **Title** The content");
            callback.onComplete(CompletionResponse.text("Done"));

            // Should have at least the preamble and the titled section
            assertThat(callback.getThinkingSections()).isNotEmpty();
        }

        @Test
        @DisplayName("should strip ** from title")
        void shouldStripAsterisksFromTitle() {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);
            callback.onThinking("**My Title** content here");
            callback.onComplete(CompletionResponse.text("Done"));

            List<Map<String, Object>> sections = callback.getThinkingSections();
            boolean hasCleanTitle = sections.stream()
                .anyMatch(s -> "My Title".equals(s.get("title")));
            assertThat(hasCleanTitle).isTrue();
        }
    }

    @Nested
    @DisplayName("sub-agent parent forwarding")
    class SubAgentParentForwardingTests {

        @Test
        @DisplayName("should forward onChunk to parent WS channel with subAgent metadata")
        void shouldForwardChunkToParent() {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);
            @SuppressWarnings("unchecked")
            ListOperations<String, String> listOps = mock(ListOperations.class);
            when(redisTemplate.opsForList()).thenReturn(listOps);

            var callback = callbackFactory.forExecution(
                STREAM_ID, CONVERSATION_ID, "parent-conv-789", "SubBot", "https://avatar.url/sub.png", "agent-42", null);
            callback.onChunk("Hello from sub-agent");

            // SSE published via redisTemplate
            verify(redisTemplate, atLeast(1)).convertAndSend(eq("stream:events:" + STREAM_ID), anyString());
            // Own WS channel published via eventBus
            verify(eventBus, atLeast(1)).publish(eq("ws:conversation:" + CONVERSATION_ID), anyString());

            // Capture parent WS channel publish via eventBus
            ArgumentCaptor<String> parentChannelCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> parentMessageCaptor = ArgumentCaptor.forClass(String.class);
            verify(eventBus, atLeast(1)).publish(parentChannelCaptor.capture(), parentMessageCaptor.capture());

            // Find the parent WS channel message
            int parentIdx = -1;
            for (int i = 0; i < parentChannelCaptor.getAllValues().size(); i++) {
                if ("ws:conversation:parent-conv-789".equals(parentChannelCaptor.getAllValues().get(i))) {
                    parentIdx = i;
                    break;
                }
            }
            assertThat(parentIdx).as("Should publish to parent WS channel").isGreaterThanOrEqualTo(0);

            String parentJson = parentMessageCaptor.getAllValues().get(parentIdx);
            assertThat(parentJson).contains("\"type\":\"sub_agent_content\"");
            // Verify nested subAgent object format (matching ConversationEventPublisher)
            assertThat(parentJson).contains("\"subAgent\":");
            assertThat(parentJson).contains("\"name\":\"SubBot\"");
            assertThat(parentJson).contains("\"avatarUrl\":\"https://avatar.url/sub.png\"");
            assertThat(parentJson).contains("\"agentId\":\"agent-42\"");
            assertThat(parentJson).contains("\"content\":\"Hello from sub-agent\"");
        }

        @Test
        @DisplayName("should NOT forward to parent when parentConversationId is null")
        void shouldNotForwardWhenNoParent() {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);
            @SuppressWarnings("unchecked")
            ListOperations<String, String> listOps = mock(ListOperations.class);
            when(redisTemplate.opsForList()).thenReturn(listOps);

            var callback = callbackFactory.forExecution(STREAM_ID, CONVERSATION_ID);
            callback.onChunk("Hello");

            // SSE published via redisTemplate
            verify(redisTemplate, atLeast(1)).convertAndSend(eq("stream:events:" + STREAM_ID), anyString());
            // Own WS published via eventBus
            verify(eventBus, atLeast(1)).publish(eq("ws:conversation:" + CONVERSATION_ID), anyString());

            // Capture all eventBus.publish calls to verify no parent channel
            ArgumentCaptor<String> channelCaptor = ArgumentCaptor.forClass(String.class);
            verify(eventBus, atLeast(1)).publish(channelCaptor.capture(), anyString());

            // Should NOT have a parent channel (a different ws:conversation: channel)
            boolean hasParentChannel = channelCaptor.getAllValues().stream()
                .anyMatch(ch -> ch.startsWith("ws:conversation:") && !ch.equals("ws:conversation:" + CONVERSATION_ID));
            assertThat(hasParentChannel).isFalse();
        }

        @Test
        @DisplayName("should forward onThinking to parent with sub_agent_thinking type")
        void shouldForwardThinkingToParent() {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

            var callback = callbackFactory.forExecution(
                STREAM_ID, CONVERSATION_ID, "parent-conv", "SubBot", null, "agent-1", null);
            callback.onThinking("Analyzing...");

            // SSE published via redisTemplate
            verify(redisTemplate, atLeast(1)).convertAndSend(eq("stream:events:" + STREAM_ID), anyString());

            // Capture all eventBus.publish calls to find the parent WS message
            ArgumentCaptor<String> channelCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(eventBus, atLeast(1)).publish(channelCaptor.capture(), messageCaptor.capture());

            int parentIdx = -1;
            for (int i = 0; i < channelCaptor.getAllValues().size(); i++) {
                if ("ws:conversation:parent-conv".equals(channelCaptor.getAllValues().get(i))) {
                    parentIdx = i;
                    break;
                }
            }
            assertThat(parentIdx).isGreaterThanOrEqualTo(0);

            String parentJson = messageCaptor.getAllValues().get(parentIdx);
            assertThat(parentJson).contains("\"type\":\"sub_agent_thinking\"");
            assertThat(parentJson).contains("\"subAgent\":");
            assertThat(parentJson).contains("\"name\":\"SubBot\"");
        }

        @Test
        @DisplayName("should forward onToolCall to parent with sub_agent_tool_call type")
        void shouldForwardToolCallToParent() {
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

            var callback = callbackFactory.forExecution(
                STREAM_ID, CONVERSATION_ID, "parent-conv", "SubBot", null, "agent-1", null);

            ToolCall toolCall = ToolCall.builder()
                .id("call_sub_1").toolName("web_search").arguments(Map.of("query", "test")).build();
            callback.onToolCall(toolCall);

            // SSE published via redisTemplate
            verify(redisTemplate, atLeast(1)).convertAndSend(eq("stream:events:" + STREAM_ID), anyString());

            // Capture all eventBus.publish calls to find the parent WS message
            ArgumentCaptor<String> channelCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(eventBus, atLeast(1)).publish(channelCaptor.capture(), messageCaptor.capture());

            int parentIdx = -1;
            for (int i = 0; i < channelCaptor.getAllValues().size(); i++) {
                if ("ws:conversation:parent-conv".equals(channelCaptor.getAllValues().get(i))) {
                    parentIdx = i;
                    break;
                }
            }
            assertThat(parentIdx).isGreaterThanOrEqualTo(0);

            String parentJson = messageCaptor.getAllValues().get(parentIdx);
            assertThat(parentJson).contains("\"type\":\"sub_agent_tool_call\"");
            assertThat(parentJson).contains("\"subAgent\":");
            assertThat(parentJson).contains("\"toolName\":\"web_search\"");
        }
    }

    @Nested
    @DisplayName("null conversationId")
    class NullConversationIdTests {

        @Test
        @DisplayName("should work with null conversationId (SSE only, no WS)")
        void shouldWorkWithNullConversationId() {
            when(redisTemplate.convertAndSend(eq("stream:events:" + STREAM_ID), anyString())).thenReturn(1L);

            var callback = callbackFactory.forExecution(STREAM_ID, null);
            callback.onChunk("Hello");

            // Should only publish to SSE channel, not WS
            verify(redisTemplate).convertAndSend(eq("stream:events:" + STREAM_ID), anyString());
            verify(redisTemplate, never()).convertAndSend(startsWith("ws:conversation:"), anyString());
        }
    }
}
