package com.apimarketplace.conversation.streaming;

import com.apimarketplace.conversation.domain.stream.StreamEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("StreamPubSubService")
@ExtendWith(MockitoExtension.class)
class StreamPubSubServiceTest {

    @Mock
    private ReactiveRedisTemplate<String, String> redisTemplate;

    @Mock
    private ReactiveRedisMessageListenerContainer messageListenerContainer;

    @Mock
    private StreamStateService stateService;

    private ObjectMapper objectMapper;
    private StreamPubSubService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        service = new StreamPubSubService(redisTemplate, messageListenerContainer, objectMapper, stateService);
    }

    // ==================== publish ====================

    @Nested
    @DisplayName("publish")
    class Publish {

        @Test
        @DisplayName("should publish serialized event to correct Redis channel")
        void shouldPublishToCorrectChannel() {
            StreamEvent event = StreamEvent.content("stream-1", "Hello");
            when(redisTemplate.convertAndSend(eq("stream:events:stream-1"), anyString()))
                    .thenReturn(Mono.just(1L));

            StepVerifier.create(service.publish("stream-1", event))
                    .expectNext(1L)
                    .verifyComplete();

            verify(redisTemplate).convertAndSend(eq("stream:events:stream-1"), anyString());
        }

        @Test
        @DisplayName("should serialize event as JSON")
        void shouldSerializeEventAsJson() {
            StreamEvent event = StreamEvent.content("stream-1", "test content");
            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            when(redisTemplate.convertAndSend(anyString(), jsonCaptor.capture()))
                    .thenReturn(Mono.just(1L));

            StepVerifier.create(service.publish("stream-1", event))
                    .expectNext(1L)
                    .verifyComplete();

            String json = jsonCaptor.getValue();
            assertThat(json).contains("\"content\":\"test content\"");
            assertThat(json).contains("\"streamId\":\"stream-1\"");
        }

        @Test
        @DisplayName("should return number of receivers")
        void shouldReturnReceiverCount() {
            StreamEvent event = StreamEvent.heartbeat("stream-1");
            when(redisTemplate.convertAndSend(anyString(), anyString()))
                    .thenReturn(Mono.just(3L));

            StepVerifier.create(service.publish("stream-1", event))
                    .expectNext(3L)
                    .verifyComplete();
        }

        @Test
        @DisplayName("should propagate Redis publish errors")
        void shouldPropagatePublishErrors() {
            StreamEvent event = StreamEvent.content("stream-1", "data");
            when(redisTemplate.convertAndSend(anyString(), anyString()))
                    .thenReturn(Mono.error(new RuntimeException("Redis down")));

            StepVerifier.create(service.publish("stream-1", event))
                    .expectError(RuntimeException.class)
                    .verify();
        }
    }

    // ==================== publishAndStoreToolEvent ====================

    @Nested
    @DisplayName("publishAndStoreToolEvent")
    class PublishAndStoreToolEvent {

        @Test
        @DisplayName("should store tool event before publishing")
        void shouldStoreBeforePublish() {
            StreamEvent event = StreamEvent.toolCall("stream-1", "search", "tool-1",
                    Map.of("query", "test"));
            when(stateService.appendToolEvent(eq("stream-1"), anyString()))
                    .thenReturn(Mono.just(1L));
            when(redisTemplate.convertAndSend(anyString(), anyString()))
                    .thenReturn(Mono.just(1L));

            StepVerifier.create(service.publishAndStoreToolEvent("stream-1", event))
                    .expectNext(1L)
                    .verifyComplete();

            verify(stateService).appendToolEvent(eq("stream-1"), anyString());
            verify(redisTemplate).convertAndSend(eq("stream:events:stream-1"), anyString());
        }

        @Test
        @DisplayName("should store tool result events for reconnection replay")
        void shouldStoreToolResultEvents() {
            StreamEvent event = StreamEvent.toolResult("stream-1", "tool-1", "search",
                    true, 100L, "result-1", null, null, null, null, null, null);
            when(stateService.appendToolEvent(eq("stream-1"), anyString()))
                    .thenReturn(Mono.just(2L));
            when(redisTemplate.convertAndSend(anyString(), anyString()))
                    .thenReturn(Mono.just(1L));

            StepVerifier.create(service.publishAndStoreToolEvent("stream-1", event))
                    .expectNext(1L)
                    .verifyComplete();

            verify(stateService).appendToolEvent(eq("stream-1"), anyString());
        }

        @Test
        @DisplayName("should propagate store failure")
        void shouldPropagateStoreFailure() {
            StreamEvent event = StreamEvent.toolCall("stream-1", "search", "tool-1", Map.of());
            when(stateService.appendToolEvent(anyString(), anyString()))
                    .thenReturn(Mono.error(new RuntimeException("Redis write failed")));
            // publish is called in the chain so convertAndSend needs to be stubbed
            lenient().when(redisTemplate.convertAndSend(anyString(), anyString()))
                    .thenReturn(Mono.just(0L));

            StepVerifier.create(service.publishAndStoreToolEvent("stream-1", event))
                    .expectError(RuntimeException.class)
                    .verify();
        }
    }

    // ==================== Convenience Publish Methods ====================

    @Nested
    @DisplayName("publishContent")
    class PublishContent {

        @Test
        @DisplayName("should publish content chunk event")
        void shouldPublishContentChunk() {
            when(redisTemplate.convertAndSend(anyString(), anyString()))
                    .thenReturn(Mono.just(1L));

            StepVerifier.create(service.publishContent("stream-1", "Hello World"))
                    .expectNext(1L)
                    .verifyComplete();

            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisTemplate).convertAndSend(eq("stream:events:stream-1"), jsonCaptor.capture());
            assertThat(jsonCaptor.getValue()).contains("\"content\":\"Hello World\"");
        }
    }

    @Nested
    @DisplayName("publishThinking")
    class PublishThinking {

        @Test
        @DisplayName("should publish thinking chunk event")
        void shouldPublishThinkingChunk() {
            when(redisTemplate.convertAndSend(anyString(), anyString()))
                    .thenReturn(Mono.just(1L));

            StepVerifier.create(service.publishThinking("stream-1", "Let me analyze..."))
                    .expectNext(1L)
                    .verifyComplete();

            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisTemplate).convertAndSend(eq("stream:events:stream-1"), jsonCaptor.capture());
            assertThat(jsonCaptor.getValue()).contains("\"thinking\":\"Let me analyze...\"");
        }
    }

    @Nested
    @DisplayName("publishThinkingSection")
    class PublishThinkingSection {

        @Test
        @DisplayName("should publish thinking section with title and content")
        void shouldPublishThinkingSection() {
            when(redisTemplate.convertAndSend(anyString(), anyString()))
                    .thenReturn(Mono.just(1L));

            StepVerifier.create(service.publishThinkingSection("stream-1", "Analysis", "Detailed content"))
                    .expectNext(1L)
                    .verifyComplete();

            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisTemplate).convertAndSend(eq("stream:events:stream-1"), jsonCaptor.capture());
            String json = jsonCaptor.getValue();
            assertThat(json).contains("\"title\":\"Analysis\"");
            assertThat(json).contains("\"content\":\"Detailed content\"");
        }
    }

    @Nested
    @DisplayName("publishComplete")
    class PublishComplete {

        @Test
        @DisplayName("should publish completed event with full content and token count")
        void shouldPublishCompleted() {
            when(redisTemplate.convertAndSend(anyString(), anyString()))
                    .thenReturn(Mono.just(1L));

            StepVerifier.create(service.publishComplete("stream-1", "Full response text", 150))
                    .expectNext(1L)
                    .verifyComplete();

            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisTemplate).convertAndSend(eq("stream:events:stream-1"), jsonCaptor.capture());
            String json = jsonCaptor.getValue();
            assertThat(json).contains("\"fullContent\":\"Full response text\"");
            assertThat(json).contains("\"totalTokens\":150");
        }
    }

    @Nested
    @DisplayName("publishError")
    class PublishError {

        @Test
        @DisplayName("should publish error event with code and retryable flag")
        void shouldPublishError() {
            when(redisTemplate.convertAndSend(anyString(), anyString()))
                    .thenReturn(Mono.just(1L));

            StepVerifier.create(service.publishError("stream-1", "Rate limited", "RATE_LIMIT", true))
                    .expectNext(1L)
                    .verifyComplete();

            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisTemplate).convertAndSend(eq("stream:events:stream-1"), jsonCaptor.capture());
            String json = jsonCaptor.getValue();
            assertThat(json).contains("\"error\":\"Rate limited\"");
            assertThat(json).contains("\"errorCode\":\"RATE_LIMIT\"");
            assertThat(json).contains("\"retryable\":true");
        }
    }

    @Nested
    @DisplayName("publishStopped")
    class PublishStopped {

        @Test
        @DisplayName("should publish stopped event with partial content")
        void shouldPublishStopped() {
            when(redisTemplate.convertAndSend(anyString(), anyString()))
                    .thenReturn(Mono.just(1L));

            StepVerifier.create(service.publishStopped("stream-1", "Partial resp"))
                    .expectNext(1L)
                    .verifyComplete();

            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisTemplate).convertAndSend(eq("stream:events:stream-1"), jsonCaptor.capture());
            assertThat(jsonCaptor.getValue()).contains("\"partialContent\":\"Partial resp\"");
        }
    }

    @Nested
    @DisplayName("publishHeartbeat")
    class PublishHeartbeat {

        @Test
        @DisplayName("should publish heartbeat event")
        void shouldPublishHeartbeat() {
            when(redisTemplate.convertAndSend(anyString(), anyString()))
                    .thenReturn(Mono.just(1L));

            StepVerifier.create(service.publishHeartbeat("stream-1"))
                    .expectNext(1L)
                    .verifyComplete();

            verify(redisTemplate).convertAndSend(eq("stream:events:stream-1"), anyString());
        }
    }

    @Nested
    @DisplayName("publishTitleUpdated")
    class PublishTitleUpdated {

        @Test
        @DisplayName("should publish title update event with conversationId and title")
        void shouldPublishTitleUpdate() {
            when(redisTemplate.convertAndSend(anyString(), anyString()))
                    .thenReturn(Mono.just(1L));

            StepVerifier.create(service.publishTitleUpdated("stream-1", "conv-1", "New Title"))
                    .expectNext(1L)
                    .verifyComplete();

            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisTemplate).convertAndSend(eq("stream:events:stream-1"), jsonCaptor.capture());
            String json = jsonCaptor.getValue();
            assertThat(json).contains("\"title\":\"New Title\"");
            assertThat(json).contains("\"conversationId\":\"conv-1\"");
        }
    }

    @Nested
    @DisplayName("publishCompactionDone")
    class PublishCompactionDone {

        @Test
        @DisplayName("should publish compaction_done event with envelope projection fields")
        void shouldPublishCompactionDone() {
            // User-facing marker dispatched from ChatCompactionOrchestrator. The
            // payload is the lightweight projection that matches the DTO's
            // CompactionMarker - turnsCovered (count), summarizerModel, and
            // generatedAt - so the frontend can reconcile real-time and fetch.
            when(redisTemplate.convertAndSend(anyString(), anyString()))
                    .thenReturn(Mono.just(1L));
            java.time.Instant generatedAt = java.time.Instant.parse("2026-04-21T10:00:00Z");

            StepVerifier.create(service.publishCompactionDone(
                            "stream-1", "conv-1", 4, "claude-haiku-4-5", generatedAt))
                    .expectNext(1L)
                    .verifyComplete();

            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisTemplate).convertAndSend(eq("stream:events:stream-1"), jsonCaptor.capture());
            String json = jsonCaptor.getValue();
            assertThat(json).contains("\"conversationId\":\"conv-1\"");
            assertThat(json).contains("\"turnsCoveredCount\":4");
            assertThat(json).contains("\"summarizerModel\":\"claude-haiku-4-5\"");
            // Instant is serialized as epoch seconds by the JavaTimeModule default;
            // 2026-04-21T10:00:00Z → 1776765600. Pin the numeric encoding so a future
            // switch to WRITE_DATES_AS_TIMESTAMPS=false shows up in test output.
            assertThat(json).contains("\"generatedAt\":1776765600");
        }
    }

    // ==================== subscribe ====================

    @Nested
    @DisplayName("subscribe")
    class Subscribe {

        @Test
        @DisplayName("should subscribe to correct Redis channel")
        @SuppressWarnings("unchecked")
        void shouldSubscribeToCorrectChannel() {
            when(messageListenerContainer.receive(any(ChannelTopic.class)))
                    .thenReturn(Flux.empty());

            StepVerifier.create(service.subscribe("stream-1"))
                    .verifyComplete();

            ArgumentCaptor<ChannelTopic> topicCaptor = ArgumentCaptor.forClass(ChannelTopic.class);
            verify(messageListenerContainer).receive(topicCaptor.capture());
            assertThat(topicCaptor.getValue().getTopic()).isEqualTo("stream:events:stream-1");
        }

        @Test
        @DisplayName("should deserialize received messages into StreamEvents")
        @SuppressWarnings("unchecked")
        void shouldDeserializeMessages() throws Exception {
            StreamEvent.ContentChunk contentEvent = StreamEvent.content("stream-1", "Hello");
            String json = objectMapper.writeValueAsString(contentEvent);

            ReactiveSubscription.Message<String, String> msg = mock(ReactiveSubscription.Message.class);
            when(msg.getMessage()).thenReturn(json);

            when(messageListenerContainer.receive(any(ChannelTopic.class)))
                    .thenReturn(Flux.just(msg));

            StepVerifier.create(service.subscribe("stream-1"))
                    .assertNext(event -> {
                        assertThat(event).isInstanceOf(StreamEvent.ContentChunk.class);
                        StreamEvent.ContentChunk chunk = (StreamEvent.ContentChunk) event;
                        assertThat(chunk.content()).isEqualTo("Hello");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should complete on terminal event (StreamCompleted)")
        @SuppressWarnings("unchecked")
        void shouldCompleteOnStreamCompleted() throws Exception {
            StreamEvent.ContentChunk content = StreamEvent.content("stream-1", "data");
            StreamEvent.StreamCompleted completed = StreamEvent.completed("stream-1", "full content", 100);

            String contentJson = objectMapper.writeValueAsString(content);
            String completedJson = objectMapper.writeValueAsString(completed);

            ReactiveSubscription.Message<String, String> msg1 = mock(ReactiveSubscription.Message.class);
            when(msg1.getMessage()).thenReturn(contentJson);
            ReactiveSubscription.Message<String, String> msg2 = mock(ReactiveSubscription.Message.class);
            when(msg2.getMessage()).thenReturn(completedJson);

            when(messageListenerContainer.receive(any(ChannelTopic.class)))
                    .thenReturn(Flux.just(msg1, msg2));

            StepVerifier.create(service.subscribe("stream-1"))
                    .expectNextCount(2)  // content + completed (takeUntil includes the terminal)
                    .verifyComplete();
        }

        @Test
        @DisplayName("should complete on terminal event (StreamError)")
        @SuppressWarnings("unchecked")
        void shouldCompleteOnStreamError() throws Exception {
            StreamEvent.StreamError error = StreamEvent.error("stream-1", "fail", "ERR", false);
            String json = objectMapper.writeValueAsString(error);

            ReactiveSubscription.Message<String, String> msg = mock(ReactiveSubscription.Message.class);
            when(msg.getMessage()).thenReturn(json);

            when(messageListenerContainer.receive(any(ChannelTopic.class)))
                    .thenReturn(Flux.just(msg));

            StepVerifier.create(service.subscribe("stream-1"))
                    .assertNext(event -> assertThat(event).isInstanceOf(StreamEvent.StreamError.class))
                    .verifyComplete();
        }

        @Test
        @DisplayName("CompactionDone is NOT terminal - subscriber keeps receiving until a real terminal arrives")
        @SuppressWarnings("unchecked")
        void compactionDoneDoesNotTerminateSubscriber() throws Exception {
            // Regression pin: compaction fires async and may arrive before or
            // after StreamCompleted. If someone adds CompactionDone to
            // isTerminalEvent() by accident, the subscriber would cut off
            // early and miss actual completion payloads - this test locks
            // that misbehavior out.
            java.time.Instant generatedAt = java.time.Instant.parse("2026-04-21T10:00:00Z");
            StreamEvent.CompactionDone compaction = StreamEvent.compactionDone(
                    "stream-1", "conv-1", 3, "claude-haiku-4-5", generatedAt);
            StreamEvent.StreamCompleted completed = StreamEvent.completed("stream-1", "full content", 100);

            String compactionJson = objectMapper.writeValueAsString(compaction);
            String completedJson = objectMapper.writeValueAsString(completed);

            ReactiveSubscription.Message<String, String> msg1 = mock(ReactiveSubscription.Message.class);
            when(msg1.getMessage()).thenReturn(compactionJson);
            ReactiveSubscription.Message<String, String> msg2 = mock(ReactiveSubscription.Message.class);
            when(msg2.getMessage()).thenReturn(completedJson);

            when(messageListenerContainer.receive(any(ChannelTopic.class)))
                    .thenReturn(Flux.just(msg1, msg2));

            StepVerifier.create(service.subscribe("stream-1"))
                    .expectNextCount(2)   // both CompactionDone and StreamCompleted must pass through
                    .verifyComplete();
        }

        @Test
        @DisplayName("should complete on terminal event (StreamStopped)")
        @SuppressWarnings("unchecked")
        void shouldCompleteOnStreamStopped() throws Exception {
            StreamEvent.StreamStopped stopped = StreamEvent.stopped("stream-1", "partial");
            String json = objectMapper.writeValueAsString(stopped);

            ReactiveSubscription.Message<String, String> msg = mock(ReactiveSubscription.Message.class);
            when(msg.getMessage()).thenReturn(json);

            when(messageListenerContainer.receive(any(ChannelTopic.class)))
                    .thenReturn(Flux.just(msg));

            StepVerifier.create(service.subscribe("stream-1"))
                    .assertNext(event -> assertThat(event).isInstanceOf(StreamEvent.StreamStopped.class))
                    .verifyComplete();
        }

        @Test
        @DisplayName("should skip invalid JSON messages gracefully")
        @SuppressWarnings("unchecked")
        void shouldSkipInvalidJsonMessages() {
            ReactiveSubscription.Message<String, String> invalidMsg = mock(ReactiveSubscription.Message.class);
            when(invalidMsg.getMessage()).thenReturn("not valid json {{{");

            when(messageListenerContainer.receive(any(ChannelTopic.class)))
                    .thenReturn(Flux.just(invalidMsg));

            // Invalid messages are skipped (Mono.empty), so the Flux completes normally
            StepVerifier.create(service.subscribe("stream-1"))
                    .verifyComplete();
        }
    }

    // ==================== deserializeEvent ====================

    @Nested
    @DisplayName("deserializeEvent")
    class DeserializeEvent {

        @Test
        @DisplayName("should deserialize ContentChunk")
        void shouldDeserializeContentChunk() throws Exception {
            String json = objectMapper.writeValueAsString(StreamEvent.content("s1", "hello"));

            StreamEvent result = service.deserializeEvent(json);

            assertThat(result).isInstanceOf(StreamEvent.ContentChunk.class);
            assertThat(((StreamEvent.ContentChunk) result).content()).isEqualTo("hello");
        }

        @Test
        @DisplayName("should deserialize StreamCompleted")
        void shouldDeserializeStreamCompleted() throws Exception {
            String json = objectMapper.writeValueAsString(StreamEvent.completed("s1", "full", 200));

            StreamEvent result = service.deserializeEvent(json);

            assertThat(result).isInstanceOf(StreamEvent.StreamCompleted.class);
            StreamEvent.StreamCompleted completed = (StreamEvent.StreamCompleted) result;
            assertThat(completed.fullContent()).isEqualTo("full");
            assertThat(completed.totalTokens()).isEqualTo(200);
        }

        @Test
        @DisplayName("should deserialize StreamError")
        void shouldDeserializeStreamError() throws Exception {
            String json = objectMapper.writeValueAsString(
                    StreamEvent.error("s1", "fail", "ERR_CODE", true));

            StreamEvent result = service.deserializeEvent(json);

            assertThat(result).isInstanceOf(StreamEvent.StreamError.class);
            StreamEvent.StreamError error = (StreamEvent.StreamError) result;
            assertThat(error.error()).isEqualTo("fail");
            assertThat(error.errorCode()).isEqualTo("ERR_CODE");
            assertThat(error.retryable()).isTrue();
        }

        @Test
        @DisplayName("should deserialize StreamStopped")
        void shouldDeserializeStreamStopped() throws Exception {
            String json = objectMapper.writeValueAsString(StreamEvent.stopped("s1", "partial"));

            StreamEvent result = service.deserializeEvent(json);

            assertThat(result).isInstanceOf(StreamEvent.StreamStopped.class);
            assertThat(((StreamEvent.StreamStopped) result).partialContent()).isEqualTo("partial");
        }

        @Test
        @DisplayName("should deserialize ToolCall")
        void shouldDeserializeToolCall() throws Exception {
            String json = objectMapper.writeValueAsString(
                    StreamEvent.toolCall("s1", "search", "tool-1", Map.of("query", "test")));

            StreamEvent result = service.deserializeEvent(json);

            assertThat(result).isInstanceOf(StreamEvent.ToolCall.class);
            StreamEvent.ToolCall toolCall = (StreamEvent.ToolCall) result;
            assertThat(toolCall.toolName()).isEqualTo("search");
            assertThat(toolCall.toolId()).isEqualTo("tool-1");
        }

        @Test
        @DisplayName("should deserialize ToolResult")
        void shouldDeserializeToolResult() throws Exception {
            String json = objectMapper.writeValueAsString(
                    StreamEvent.toolResult("s1", "tool-1", "search", true, 50L,
                            "result-1", null, null, null, null, null, null));

            StreamEvent result = service.deserializeEvent(json);

            assertThat(result).isInstanceOf(StreamEvent.ToolResult.class);
            StreamEvent.ToolResult toolResult = (StreamEvent.ToolResult) result;
            assertThat(toolResult.success()).isTrue();
            assertThat(toolResult.resultId()).isEqualTo("result-1");
        }

        @Test
        @DisplayName("should deserialize ThinkingChunk")
        void shouldDeserializeThinkingChunk() throws Exception {
            String json = objectMapper.writeValueAsString(
                    StreamEvent.thinking("s1", "reasoning..."));

            StreamEvent result = service.deserializeEvent(json);

            assertThat(result).isInstanceOf(StreamEvent.ThinkingChunk.class);
            assertThat(((StreamEvent.ThinkingChunk) result).thinking()).isEqualTo("reasoning...");
        }

        @Test
        @DisplayName("should deserialize TitleUpdated")
        void shouldDeserializeTitleUpdated() throws Exception {
            String json = objectMapper.writeValueAsString(
                    StreamEvent.titleUpdated("s1", "conv-1", "My Title"));

            StreamEvent result = service.deserializeEvent(json);

            assertThat(result).isInstanceOf(StreamEvent.TitleUpdated.class);
            StreamEvent.TitleUpdated titleEvent = (StreamEvent.TitleUpdated) result;
            assertThat(titleEvent.title()).isEqualTo("My Title");
            assertThat(titleEvent.conversationId()).isEqualTo("conv-1");
        }

        @Test
        @DisplayName("should deserialize StreamStarted")
        void shouldDeserializeStreamStarted() throws Exception {
            String json = objectMapper.writeValueAsString(
                    StreamEvent.started("s1", "conv-1", "gpt-4"));

            StreamEvent result = service.deserializeEvent(json);

            assertThat(result).isInstanceOf(StreamEvent.StreamStarted.class);
            StreamEvent.StreamStarted started = (StreamEvent.StreamStarted) result;
            assertThat(started.model()).isEqualTo("gpt-4");
            assertThat(started.conversationId()).isEqualTo("conv-1");
        }

        @Test
        @DisplayName("should deserialize Heartbeat from minimal JSON")
        void shouldDeserializeHeartbeat() throws Exception {
            // Heartbeat has only streamId and timestamp
            String json = objectMapper.writeValueAsString(StreamEvent.heartbeat("s1"));

            StreamEvent result = service.deserializeEvent(json);

            assertThat(result).isInstanceOf(StreamEvent.Heartbeat.class);
            assertThat(result.streamId()).isEqualTo("s1");
        }

        @Test
        @DisplayName("should deserialize AgentBrowseStep via cdpToken+cdpWsUrl discriminator")
        void shouldDeserializeAgentBrowseStep() throws Exception {
            // Regression-pin: locks the discriminator order in
            // deserializeEvent. The cdpToken+cdpWsUrl branch MUST be
            // reachable via JSON that doesn't also match
            // screenshotIndex+screenshotKey (FetchScreenshot) - otherwise
            // M7 live-view bootstrap events would silently misroute to
            // FetchScreenshot and the chat panel never opens mid-execution.
            StreamEvent.AgentBrowseStep event = new StreamEvent.AgentBrowseStep(
                    "stream-1", "tool-1", "ses_xyz",
                    "eyJfresh.token", "wss://bridge-host.test/cdp/ses_xyz",
                    "https://example.com", 0, "run-1", "node-1",
                    java.time.Instant.now());
            String json = objectMapper.writeValueAsString(event);

            StreamEvent result = service.deserializeEvent(json);

            assertThat(result).isInstanceOf(StreamEvent.AgentBrowseStep.class);
            StreamEvent.AgentBrowseStep abs = (StreamEvent.AgentBrowseStep) result;
            assertThat(abs.streamId()).isEqualTo("stream-1");
            assertThat(abs.toolId()).isEqualTo("tool-1");
            assertThat(abs.sessionId()).isEqualTo("ses_xyz");
            assertThat(abs.cdpToken()).isEqualTo("eyJfresh.token");
            assertThat(abs.cdpWsUrl()).isEqualTo("wss://bridge-host.test/cdp/ses_xyz");
        }

        @Test
        @DisplayName("should deserialize ThinkingSection")
        void shouldDeserializeThinkingSection() throws Exception {
            String json = objectMapper.writeValueAsString(
                    StreamEvent.thinkingSection("s1", "Step 1", "Analyzing the data"));

            StreamEvent result = service.deserializeEvent(json);

            assertThat(result).isInstanceOf(StreamEvent.ThinkingSection.class);
            StreamEvent.ThinkingSection section = (StreamEvent.ThinkingSection) result;
            assertThat(section.title()).isEqualTo("Step 1");
            assertThat(section.content()).isEqualTo("Analyzing the data");
        }

        @Test
        @DisplayName("should deserialize StreamAwaitingApproval")
        void shouldDeserializeStreamAwaitingApproval() throws Exception {
            var services = List.of(new StreamEvent.ServiceApprovalInfo("gmail", "Gmail", "gmail"));
            String json = objectMapper.writeValueAsString(
                    StreamEvent.awaitingApproval("s1", "partial content", services));

            StreamEvent result = service.deserializeEvent(json);

            assertThat(result).isInstanceOf(StreamEvent.StreamAwaitingApproval.class);
            StreamEvent.StreamAwaitingApproval awaiting = (StreamEvent.StreamAwaitingApproval) result;
            assertThat(awaiting.partialContent()).isEqualTo("partial content");
            assertThat(awaiting.services()).hasSize(1);
        }

        @Test
        @DisplayName("should deserialize PendingActionCancelled")
        void shouldDeserializePendingActionCancelled() throws Exception {
            String json = objectMapper.writeValueAsString(
                    StreamEvent.pendingActionCancelled("s1", "user_new_message", "New message sent"));

            StreamEvent result = service.deserializeEvent(json);

            assertThat(result).isInstanceOf(StreamEvent.PendingActionCancelled.class);
            StreamEvent.PendingActionCancelled cancelled = (StreamEvent.PendingActionCancelled) result;
            assertThat(cancelled.reason()).isEqualTo("user_new_message");
            assertThat(cancelled.message()).isEqualTo("New message sent");
        }

        @Test
        @DisplayName("should deserialize CredentialRequired")
        void shouldDeserializeCredentialRequired() throws Exception {
            String json = objectMapper.writeValueAsString(
                    StreamEvent.credentialRequired("s1", "oauth2", "gmail_send", "tool-1"));

            StreamEvent result = service.deserializeEvent(json);

            assertThat(result).isInstanceOf(StreamEvent.CredentialRequired.class);
            StreamEvent.CredentialRequired cred = (StreamEvent.CredentialRequired) result;
            assertThat(cred.credentialType()).isEqualTo("oauth2");
            assertThat(cred.toolName()).isEqualTo("gmail_send");
        }

        @Test
        @DisplayName("should deserialize ServiceApprovalRequired")
        void shouldDeserializeServiceApprovalRequired() throws Exception {
            var services = List.of(
                    new StreamEvent.ServiceApprovalInfo("slack", "Slack", "slack", "Send Message", "tool-1", "Need to post"));
            String json = objectMapper.writeValueAsString(
                    StreamEvent.serviceApprovalRequired("s1", services, "Agent needs Slack access", false));

            StreamEvent result = service.deserializeEvent(json);

            assertThat(result).isInstanceOf(StreamEvent.ServiceApprovalRequired.class);
            StreamEvent.ServiceApprovalRequired approval = (StreamEvent.ServiceApprovalRequired) result;
            assertThat(approval.reason()).isEqualTo("Agent needs Slack access");
            assertThat(approval.services()).hasSize(1);
        }

        @Test
        @DisplayName("should deserialize CompactionDone")
        void shouldDeserializeCompactionDone() throws Exception {
            // Round-trip pin for the discriminator-free deserialiser: the event
            // must be distinguishable from neighbours (TitleUpdated also carries
            // conversationId) via the turnsCovered+summarizerModel pair.
            java.time.Instant generatedAt = java.time.Instant.parse("2026-04-21T10:00:00Z");
            String json = objectMapper.writeValueAsString(
                    StreamEvent.compactionDone("s1", "conv-1", 4, "claude-haiku-4-5", generatedAt));

            StreamEvent result = service.deserializeEvent(json);

            assertThat(result).isInstanceOf(StreamEvent.CompactionDone.class);
            StreamEvent.CompactionDone done = (StreamEvent.CompactionDone) result;
            assertThat(done.conversationId()).isEqualTo("conv-1");
            assertThat(done.turnsCoveredCount()).isEqualTo(4);
            assertThat(done.summarizerModel()).isEqualTo("claude-haiku-4-5");
            assertThat(done.generatedAt()).isEqualTo(generatedAt);
        }

        @Test
        @DisplayName("should throw JsonProcessingException for completely invalid JSON")
        void shouldThrowForInvalidJson() {
            assertThatThrownBy(() -> service.deserializeEvent("not json at all {{{"))
                    .isInstanceOf(JsonProcessingException.class);
        }

        @Test
        @DisplayName("should default to Heartbeat for unknown JSON structure")
        void shouldDefaultToHeartbeatForUnknownStructure() throws Exception {
            String json = "{\"streamId\":\"s1\",\"unknownField\":\"value\"}";

            StreamEvent result = service.deserializeEvent(json);

            assertThat(result).isInstanceOf(StreamEvent.Heartbeat.class);
            assertThat(result.streamId()).isEqualTo("s1");
        }
    }

    // ==================== Channel Key Verification ====================

    @Nested
    @DisplayName("channelKey routing")
    class ChannelKeyRouting {

        @Test
        @DisplayName("should use stream:events: prefix for all publish channels")
        void shouldUsePrefixForChannels() {
            when(redisTemplate.convertAndSend(anyString(), anyString()))
                    .thenReturn(Mono.just(1L));

            service.publishContent("my-stream-id", "data").block();

            verify(redisTemplate).convertAndSend(eq("stream:events:my-stream-id"), anyString());
        }

        @Test
        @DisplayName("different stream IDs should use different channels")
        void shouldUseDifferentChannelsForDifferentStreams() {
            when(redisTemplate.convertAndSend(anyString(), anyString()))
                    .thenReturn(Mono.just(1L));

            service.publishContent("stream-a", "data").block();
            service.publishContent("stream-b", "data").block();

            verify(redisTemplate).convertAndSend(eq("stream:events:stream-a"), anyString());
            verify(redisTemplate).convertAndSend(eq("stream:events:stream-b"), anyString());
        }
    }

    // ==================== WebSocket Dual Publish ====================

    @Nested
    @DisplayName("WebSocket dual publish")
    class WebSocketDualPublish {

        @Test
        @DisplayName("StreamStarted should publish to both stream and WS channels")
        void streamStartedShouldPublishToBothChannels() {
            when(redisTemplate.convertAndSend(anyString(), anyString()))
                    .thenReturn(Mono.just(1L));

            StreamEvent event = StreamEvent.started("stream-1", "conv-123", "gpt-4");
            service.publish("stream-1", event).block();

            // Verify stream channel
            verify(redisTemplate).convertAndSend(eq("stream:events:stream-1"), anyString());
            // Verify WS channel (keyed by conversationId)
            verify(redisTemplate).convertAndSend(eq("ws:conversation:conv-123"), anyString());
        }

        @Test
        @DisplayName("Subsequent events should publish to WS channel after StreamStarted caches conversationId")
        void subsequentEventsShouldPublishToWsChannelFromCache() {
            when(redisTemplate.convertAndSend(anyString(), anyString()))
                    .thenReturn(Mono.just(1L));

            // First: StreamStarted populates the cache
            service.publish("stream-1", StreamEvent.started("stream-1", "conv-456", "gpt-4")).block();

            // Reset mock to clear invocation history
            reset(redisTemplate);
            when(redisTemplate.convertAndSend(anyString(), anyString()))
                    .thenReturn(Mono.just(1L));

            // Second: ContentChunk should use cached conversationId for WS channel
            service.publishContent("stream-1", "Hello from cache").block();

            // Verify both channels
            verify(redisTemplate).convertAndSend(eq("stream:events:stream-1"), anyString());
            verify(redisTemplate).convertAndSend(eq("ws:conversation:conv-456"), anyString());
        }

        @Test
        @DisplayName("TitleUpdated should publish to WS channel")
        void titleUpdatedShouldPublishToWsChannel() {
            when(redisTemplate.convertAndSend(anyString(), anyString()))
                    .thenReturn(Mono.just(1L));

            service.publishTitleUpdated("stream-1", "conv-789", "New Title").block();

            verify(redisTemplate).convertAndSend(eq("stream:events:stream-1"), anyString());
            verify(redisTemplate).convertAndSend(eq("ws:conversation:conv-789"), anyString());
        }

        @Test
        @DisplayName("Events without conversationId should only publish to stream channel")
        void eventsWithoutConversationIdShouldOnlyPublishToSse() {
            when(redisTemplate.convertAndSend(anyString(), anyString()))
                    .thenReturn(Mono.just(1L));

            // No StreamStarted → no cache → no WS publish
            service.publishContent("stream-no-conv", "data").block();

            verify(redisTemplate).convertAndSend(eq("stream:events:stream-no-conv"), anyString());
            verify(redisTemplate, times(1)).convertAndSend(anyString(), anyString());
        }

        @Test
        @DisplayName("Terminal event should evict stream from cache")
        void terminalEventShouldEvictCache() {
            when(redisTemplate.convertAndSend(anyString(), anyString()))
                    .thenReturn(Mono.just(1L));

            // Populate cache
            service.publish("stream-1", StreamEvent.started("stream-1", "conv-cleanup", "gpt-4")).block();

            // Terminal event (completed) should evict
            service.publishComplete("stream-1", "Done", 100).block();

            // Reset and try another event - should NOT publish to WS (cache evicted)
            reset(redisTemplate);
            when(redisTemplate.convertAndSend(anyString(), anyString()))
                    .thenReturn(Mono.just(1L));

            service.publishContent("stream-1", "after terminal").block();

            // Only stream channel, no WS channel (cache was evicted)
            verify(redisTemplate).convertAndSend(eq("stream:events:stream-1"), anyString());
            verify(redisTemplate, times(1)).convertAndSend(anyString(), anyString());
        }
    }
}
