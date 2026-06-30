package com.apimarketplace.conversation.streaming;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("RedisStreamingOutput")
@ExtendWith(MockitoExtension.class)
class RedisStreamingOutputTest {

    @Mock
    private StreamStateService stateService;

    @Mock
    private StreamPubSubService pubSubService;

    private RedisStreamingOutput output;

    @BeforeEach
    void setUp() {
        output = new RedisStreamingOutput(
                "stream-1", stateService, pubSubService,
                "conv-1", "gpt-4", "openai"
        );
    }

    @Nested
    @DisplayName("Constructor and getters")
    class ConstructorTests {

        @Test
        @DisplayName("should initialize with correct values")
        void shouldInitialize() {
            assertThat(output.getCurrentStreamId()).isEqualTo("stream-1");
            assertThat(output.getConversationId()).isEqualTo("conv-1");
            assertThat(output.getModel()).isEqualTo("gpt-4");
            assertThat(output.getProvider()).isEqualTo("openai");
        }

        @Test
        @DisplayName("should be active after creation")
        void shouldBeActiveAfterCreation() {
            assertThat(output.isActive()).isTrue();
        }

        @Test
        @DisplayName("should have empty content initially")
        void shouldHaveEmptyContent() {
            assertThat(output.getCurrentContent()).isEmpty();
        }
    }

    @Nested
    @DisplayName("isStreamProcessing")
    class IsStreamProcessingTests {

        @Test
        @DisplayName("should return true when processing and Redis state is active")
        void shouldReturnTrueWhenActive() {
            when(stateService.isActive("stream-1")).thenReturn(Mono.just(true));

            assertThat(output.isStreamProcessing()).isTrue();
        }

        @Test
        @DisplayName("should return false when Redis state is not active")
        void shouldReturnFalseWhenRedisInactive() {
            when(stateService.isActive("stream-1")).thenReturn(Mono.just(false));

            assertThat(output.isStreamProcessing()).isFalse();
        }

        @Test
        @DisplayName("should keep stream active when Redis activity check fails")
        void shouldKeepStreamActiveWhenRedisActivityCheckFails() {
            when(stateService.isActive("stream-1"))
                    .thenReturn(Mono.error(new RuntimeException("Redis command timed out")));

            assertThat(output.isStreamProcessing()).isTrue();
            assertThat(output.isActive()).isTrue();
        }
    }

    @Nested
    @DisplayName("sendContent")
    class SendContentTests {

        @Test
        @DisplayName("should buffer content and publish to Redis")
        void shouldBufferAndPublish() {
            when(stateService.isActive("stream-1")).thenReturn(Mono.just(true));
            when(stateService.appendContent(anyString(), anyString())).thenReturn(Mono.just(1L));
            when(pubSubService.publishContent(anyString(), anyString())).thenReturn(Mono.just(1L));

            output.sendContent("Hello ", "gpt-4", "openai", "user-1", "conv-1");
            output.sendContent("World", "gpt-4", "openai", "user-1", "conv-1");

            assertThat(output.getCurrentContent()).isEqualTo("Hello World");
            verify(stateService, times(2)).appendContent(eq("stream-1"), anyString());
            verify(pubSubService, times(2)).publishContent(eq("stream-1"), anyString());
        }

        @Test
        @DisplayName("should not publish when stream is not processing")
        void shouldNotPublishWhenNotProcessing() {
            when(stateService.isActive("stream-1")).thenReturn(Mono.just(false));

            output.sendContent("Hello", "gpt-4", "openai", "user-1", "conv-1");

            assertThat(output.getCurrentContent()).isEmpty();
            verify(stateService, never()).appendContent(any(), any());
        }
    }

    @Nested
    @DisplayName("sendDone")
    class SendDoneTests {

        @Test
        @DisplayName("should mark stream as completed")
        void shouldMarkCompleted() {
            when(stateService.complete("stream-1")).thenReturn(Mono.just(true));
            when(pubSubService.publishComplete(eq("stream-1"), anyString(), anyInt()))
                    .thenReturn(Mono.just(1L));

            output.sendDone("Full response", "gpt-4", "openai", "user-1", "conv-1");

            verify(stateService).complete("stream-1");
            verify(pubSubService).publishComplete(eq("stream-1"), eq("Full response"), anyInt());
        }

        @Test
        @DisplayName("should not send done twice")
        void shouldNotSendDoneTwice() {
            when(stateService.complete("stream-1")).thenReturn(Mono.just(true));
            when(pubSubService.publishComplete(eq("stream-1"), anyString(), anyInt()))
                    .thenReturn(Mono.just(1L));

            output.sendDone("Response", "gpt-4", "openai", "user-1", "conv-1");
            output.sendDone("Response 2", "gpt-4", "openai", "user-1", "conv-1");

            verify(stateService, times(1)).complete("stream-1");
        }

        @Test
        @DisplayName("should set isActive to false after done")
        void shouldSetInactiveAfterDone() {
            when(stateService.complete("stream-1")).thenReturn(Mono.just(true));
            when(pubSubService.publishComplete(eq("stream-1"), anyString(), anyInt()))
                    .thenReturn(Mono.just(1L));

            output.sendDone("Response", "gpt-4", "openai", "user-1", "conv-1");

            assertThat(output.isActive()).isFalse();
        }
    }

    @Nested
    @DisplayName("sendError")
    class SendErrorTests {

        @Test
        @DisplayName("should mark stream as error")
        void shouldMarkError() {
            when(stateService.error("stream-1", "Something went wrong")).thenReturn(Mono.just(true));
            when(pubSubService.publishError(eq("stream-1"), eq("Something went wrong"),
                    eq("STREAM_ERROR"), eq(true))).thenReturn(Mono.just(1L));

            output.sendError("Something went wrong");

            verify(stateService).error("stream-1", "Something went wrong");
            assertThat(output.isActive()).isFalse();
        }

        @Test
        @DisplayName("should not send error twice")
        void shouldNotSendErrorTwice() {
            when(stateService.error(anyString(), anyString())).thenReturn(Mono.just(true));
            when(pubSubService.publishError(anyString(), anyString(), anyString(), anyBoolean()))
                    .thenReturn(Mono.just(1L));

            output.sendError("Error 1");
            output.sendError("Error 2");

            verify(stateService, times(1)).error(anyString(), anyString());
        }

        @Test
        @DisplayName("should delegate overloaded sendError")
        void shouldDelegateOverloadedSendError() {
            when(stateService.error(anyString(), anyString())).thenReturn(Mono.just(true));
            when(pubSubService.publishError(anyString(), anyString(), anyString(), anyBoolean()))
                    .thenReturn(Mono.just(1L));

            output.sendError("Error msg", "gpt-4", "openai", "user-1", "conv-1");

            verify(stateService).error("stream-1", "Error msg");
        }
    }

    @Nested
    @DisplayName("stop")
    class StopTests {

        @Test
        @DisplayName("should stop stream and publish stopped event")
        void shouldStop() {
            when(stateService.stop("stream-1")).thenReturn(Mono.just(true));
            when(pubSubService.publishStopped(eq("stream-1"), anyString())).thenReturn(Mono.just(1L));

            output.stop();

            verify(stateService).stop("stream-1");
            verify(pubSubService).publishStopped(eq("stream-1"), eq(""));
            assertThat(output.isActive()).isFalse();
        }

        @Test
        @DisplayName("should not stop twice")
        void shouldNotStopTwice() {
            when(stateService.stop("stream-1")).thenReturn(Mono.just(true));
            when(pubSubService.publishStopped(eq("stream-1"), anyString())).thenReturn(Mono.just(1L));

            output.stop();
            output.stop();

            verify(stateService, times(1)).stop("stream-1");
        }
    }

    @Nested
    @DisplayName("sendStreamId")
    class SendStreamIdTests {

        @Test
        @DisplayName("should update state to STREAMING and publish event")
        void shouldUpdateStateAndPublish() {
            when(stateService.isActive("stream-1")).thenReturn(Mono.just(true));
            when(stateService.updateState("stream-1", StreamState.STREAMING)).thenReturn(Mono.just(true));
            when(pubSubService.publish(eq("stream-1"), any())).thenReturn(Mono.just(1L));

            output.sendStreamId("stream-1");

            verify(stateService).updateState("stream-1", StreamState.STREAMING);
        }

        @Test
        @DisplayName("should still start when Redis activity check times out")
        void shouldStillStartWhenRedisActivityCheckTimesOut() {
            when(stateService.isActive("stream-1"))
                    .thenReturn(Mono.error(new RuntimeException("Redis command timed out")));
            when(stateService.updateState("stream-1", StreamState.STREAMING)).thenReturn(Mono.just(true));
            when(pubSubService.publish(eq("stream-1"), any())).thenReturn(Mono.just(1L));

            output.sendStreamId("stream-1");

            verify(stateService).updateState("stream-1", StreamState.STREAMING);
            verify(pubSubService).publish(eq("stream-1"), any());
        }
    }

    @Nested
    @DisplayName("updateConversationId")
    class UpdateConversationIdTests {

        @Test
        @DisplayName("should update conversation ID")
        void shouldUpdateConversationId() {
            when(stateService.updateConversationId("stream-1", "conv-new"))
                    .thenReturn(Mono.just(true));

            output.updateConversationId("conv-new");

            assertThat(output.getConversationId()).isEqualTo("conv-new");
            verify(stateService).updateConversationId("stream-1", "conv-new");
        }
    }

    @Nested
    @DisplayName("sendUserMessage")
    class SendUserMessageTests {

        @Test
        @DisplayName("should not publish user messages to stream")
        void shouldNotPublishUserMessages() {
            when(stateService.isActive("stream-1")).thenReturn(Mono.just(true));

            output.sendUserMessage("conv-1", java.util.Map.of("role", "user"));

            // User messages are stored in DB, not streamed
            verify(pubSubService, never()).publish(anyString(), any());
        }
    }

    @Nested
    @DisplayName("handleNaturalEnd")
    class HandleNaturalEndTests {

        @Test
        @DisplayName("should not throw")
        void shouldNotThrow() {
            // handleNaturalEnd just logs, state transition is in sendDone
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                    output.handleNaturalEnd("content", "conv-1")
            );
        }
    }

    @Nested
    @DisplayName("handleStreamEnd")
    class HandleStreamEndTests {

        @Test
        @DisplayName("should not throw")
        void shouldNotThrow() {
            // handleStreamEnd just logs, state transition is in sendError/stop
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                    output.handleStreamEnd("error", "conv-1")
            );
        }
    }

    @Nested
    @DisplayName("sendThinkingSection")
    class SendThinkingSectionTests {

        @Test
        @DisplayName("should publish thinking section even when streamId is present")
        void shouldPublishThinkingSection() {
            when(pubSubService.publishThinkingSection("stream-1", "Analysis", "Some content"))
                    .thenReturn(Mono.just(1L));

            output.sendThinkingSection("Analysis", "Some content");

            verify(pubSubService).publishThinkingSection("stream-1", "Analysis", "Some content");
        }
    }

    @Nested
    @DisplayName("sendDoneSimple")
    class SendDoneSimpleTests {

        @Test
        @DisplayName("should delegate to sendDone")
        void shouldDelegateToSendDone() {
            when(stateService.complete("stream-1")).thenReturn(Mono.just(true));
            when(pubSubService.publishComplete(eq("stream-1"), anyString(), anyInt()))
                    .thenReturn(Mono.just(1L));

            output.sendDoneSimple("gpt-4", "openai", "user-1", "conv-1", "stream-1");

            verify(stateService).complete("stream-1");
        }
    }
}
