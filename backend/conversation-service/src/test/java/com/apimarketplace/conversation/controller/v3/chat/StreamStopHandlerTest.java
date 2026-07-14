package com.apimarketplace.conversation.controller.v3.chat;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.conversation.service.ConversationHistoryService;
import com.apimarketplace.conversation.streaming.StreamMetadata;
import com.apimarketplace.conversation.streaming.StreamPubSubService;
import com.apimarketplace.conversation.streaming.StreamState;
import com.apimarketplace.conversation.streaming.StreamStateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("StreamStopHandler")
@ExtendWith(MockitoExtension.class)
class StreamStopHandlerTest {

    @Mock
    private StreamStateService stateService;

    @Mock
    private StreamPubSubService pubSubService;

    @Mock
    private ConversationHistoryService conversationHistoryService;

    @Mock
    private AgentClient agentClient;

    @InjectMocks
    private StreamStopHandler stopHandler;

    @Nested
    @DisplayName("stopStream")
    class StopStream {

        @Test
        @DisplayName("should return failure when conversationId is null")
        void shouldFailForNullConversationId() {
            StreamStopHandler.StopResult result = stopHandler.stopStream("user-1", null);

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("required");
        }

        @Test
        @DisplayName("should return failure when conversationId is empty")
        void shouldFailForEmptyConversationId() {
            StreamStopHandler.StopResult result = stopHandler.stopStream("user-1", "");

            assertThat(result.success()).isFalse();
        }

        @Test
        @DisplayName("should return success with 0 stopped when no active stream found")
        void shouldReturnSuccessNoActiveStream() {
            when(stateService.getByConversationId("conv-1")).thenReturn(Mono.just(
                    new StreamMetadata("stream-1", "user-1", "conv-1", "gpt-4", "openai",
                            StreamState.COMPLETED, Instant.now(), Instant.now(), 0)
            ));

            StreamStopHandler.StopResult result = stopHandler.stopStream("user-1", "conv-1");

            assertThat(result.success()).isTrue();
            assertThat(result.stoppedStreams()).isZero();
        }

        @Test
        @DisplayName("should stop active stream and save partial content")
        void shouldStopActiveStream() {
            StreamMetadata activeMetadata = new StreamMetadata(
                    "stream-1", "user-1", "conv-1", "gpt-4", "openai",
                    StreamState.STREAMING, Instant.now(), Instant.now(), 100
            );
            when(stateService.getByConversationId("conv-1")).thenReturn(Mono.just(activeMetadata));
            when(stateService.getFullContent("stream-1")).thenReturn(Mono.just("partial content"));
            when(stateService.stop("stream-1")).thenReturn(Mono.empty());
            when(stateService.setCancelKey("stream-1")).thenReturn(Mono.just(true));
            when(pubSubService.publishStopped("stream-1", "partial content")).thenReturn(Mono.empty());

            StreamStopHandler.StopResult result = stopHandler.stopStream("user-1", "conv-1");

            assertThat(result.success()).isTrue();
            assertThat(result.stoppedStreams()).isEqualTo(1);
            assertThat(result.savedPartialContent()).isEqualTo(1);
            assertThat(result.streamId()).isEqualTo("stream-1");

            verify(stateService).setCancelKey("stream-1");
            verify(conversationHistoryService).addMessage(
                    eq("conv-1"), eq("assistant"), eq("partial content"),
                    anyString(), anyString(), isNull(), eq("user-1"));
        }

        @Test
        @DisplayName("passes organizationId explicitly when cascading conversation STOP")
        void passesOrganizationIdToAgentClientCascade() {
            StreamMetadata activeMetadata = new StreamMetadata(
                    "stream-1", "user-1", "conv-1", "gpt-4", "openai",
                    StreamState.STREAMING, Instant.now(), Instant.now(), 100
            );
            when(stateService.getByConversationId("conv-1")).thenReturn(Mono.just(activeMetadata));
            when(stateService.getFullContent("stream-1")).thenReturn(Mono.just("partial content"));
            when(stateService.stop("stream-1")).thenReturn(Mono.empty());
            when(stateService.setCancelKey("stream-1")).thenReturn(Mono.just(true));
            when(pubSubService.publishStopped("stream-1", "partial content")).thenReturn(Mono.empty());

            stopHandler.stopStream("user-1", "conv-1", "org-1");

            verify(agentClient).cancelWorkflowsForConversation("conv-1", "org-1");
            verify(agentClient).cancelTasksForConversation("conv-1", "user-1", "org-1");
        }

        @Test
        @DisplayName("should save partial content accumulated from remote execution")
        void shouldSavePartialContentFromRemoteExecution() {
            // Simulates remote agent-service accumulating chunks in Redis List
            // via rightPush("stream:{streamId}:content", chunk)
            StreamMetadata activeMetadata = new StreamMetadata(
                    "stream-remote", "user-1", "conv-remote", "nova-pro", "amazon",
                    StreamState.STREAMING, Instant.now(), Instant.now(), 50
            );
            when(stateService.getByConversationId("conv-remote")).thenReturn(Mono.just(activeMetadata));
            // getFullContent joins the Redis List chunks - simulating accumulated remote content
            when(stateService.getFullContent("stream-remote"))
                    .thenReturn(Mono.just("Hello world, this is partial"));
            when(stateService.stop("stream-remote")).thenReturn(Mono.empty());
            when(stateService.setCancelKey("stream-remote")).thenReturn(Mono.just(true));
            when(pubSubService.publishStopped("stream-remote", "Hello world, this is partial"))
                    .thenReturn(Mono.empty());

            StreamStopHandler.StopResult result = stopHandler.stopStream("user-1", "conv-remote");

            assertThat(result.success()).isTrue();
            assertThat(result.stoppedStreams()).isEqualTo(1);
            assertThat(result.savedPartialContent()).isEqualTo(1);

            verify(stateService).setCancelKey("stream-remote");
            verify(conversationHistoryService).addMessage(
                    eq("conv-remote"), eq("assistant"), eq("Hello world, this is partial"),
                    anyString(), anyString(), isNull(), eq("user-1"));
        }

        @Test
        @DisplayName("persists the stream's REAL model on the partial message, not hardcoded gpt-4")
        void savesRealModelNotHardcodedGpt4() {
            StreamMetadata activeMetadata = new StreamMetadata(
                    "stream-ds", "user-1", "conv-ds", "deepseek-chat", "deepseek",
                    StreamState.STREAMING, Instant.now(), Instant.now(), 100
            );
            when(stateService.getByConversationId("conv-ds")).thenReturn(Mono.just(activeMetadata));
            when(stateService.getFullContent("stream-ds")).thenReturn(Mono.just("partial"));
            when(stateService.stop("stream-ds")).thenReturn(Mono.empty());
            when(stateService.setCancelKey("stream-ds")).thenReturn(Mono.just(true));
            when(pubSubService.publishStopped("stream-ds", "partial")).thenReturn(Mono.empty());

            stopHandler.stopStream("user-1", "conv-ds");

            // Regression: pre-fix the 4th arg (model) was the literal "gpt-4".
            verify(conversationHistoryService).addMessage(
                    eq("conv-ds"), eq("assistant"), eq("partial"),
                    eq("deepseek-chat"), anyString(), isNull(), eq("user-1"));
            verify(conversationHistoryService, never()).addMessage(
                    any(), any(), any(), eq("gpt-4"), any(), any(), any());
        }

        @Test
        @DisplayName("should handle null metadata from stateService")
        void shouldHandleNullMetadata() {
            when(stateService.getByConversationId("conv-1")).thenReturn(Mono.empty());

            StreamStopHandler.StopResult result = stopHandler.stopStream("user-1", "conv-1");

            // Mono.empty() -> block() returns null -> treated as "no active stream"
            assertThat(result.success()).isTrue();
            assertThat(result.stoppedStreams()).isZero();
        }
    }

    @Nested
    @DisplayName("toResponseMap")
    class ToResponseMap {

        @Test
        @DisplayName("should include streamId when present")
        void shouldIncludeStreamId() {
            StreamStopHandler.StopResult result = new StreamStopHandler.StopResult(
                    true, "stopped", "conv-1", "stream-1", 1, 1);

            Map<String, Object> map = stopHandler.toResponseMap(result);

            assertThat(map).containsEntry("success", true);
            assertThat(map).containsEntry("streamId", "stream-1");
            assertThat(map).containsEntry("stoppedStreams", 1);
            assertThat(map).containsEntry("savedPartialContent", 1);
        }

        @Test
        @DisplayName("should exclude streamId when null")
        void shouldExcludeStreamIdWhenNull() {
            StreamStopHandler.StopResult result = new StreamStopHandler.StopResult(
                    true, "no streams", "conv-1", null, 0, 0);

            Map<String, Object> map = stopHandler.toResponseMap(result);

            assertThat(map).containsEntry("success", true);
            assertThat(map).doesNotContainKey("streamId");
        }
    }
}
