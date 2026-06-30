package com.apimarketplace.conversation.streaming;

import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.entity.Stream;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.service.ConversationHistoryService;
import com.apimarketplace.conversation.service.StreamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("StreamInterruptionService")
@ExtendWith(MockitoExtension.class)
class StreamInterruptionServiceTest {

    @Mock
    private StreamStateService stateService;

    @Mock
    private StreamPubSubService pubSubService;

    @Mock
    private ConversationHistoryService conversationHistoryService;

    @Mock
    private StreamService streamService;

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private StreamInterruptionService interruptionService;

    @BeforeEach
    void setUpClaim() {
        // Default: the atomic interrupt claim succeeds - individual tests override to simulate
        // a concurrent rescuer (claim refused) or a Redis outage (claim throws).
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
    }

    private StreamMetadata metadata(StreamState state) {
        return new StreamMetadata("stream-1", "internal", "conv-1", "gpt-4", "workflow",
                state, Instant.now(), Instant.now(), 0);
    }

    @Nested
    @DisplayName("interrupt")
    class Interrupt {

        @Test
        @DisplayName("should save non-empty partial content as assistant message, publish stopped and mark INTERRUPTED")
        void shouldRescuePartialContent() {
            when(stateService.getMetadata("stream-1")).thenReturn(Mono.just(metadata(StreamState.STREAMING)));
            when(stateService.getFullContent("stream-1")).thenReturn(Mono.just("partial answer"));
            when(pubSubService.publishStopped(eq("stream-1"), anyString())).thenReturn(Mono.just(1L));
            when(stateService.updateState("stream-1", StreamState.INTERRUPTED)).thenReturn(Mono.just(true));
            when(stateService.delete("stream-1")).thenReturn(Mono.just(1L));
            when(streamService.getStreamByStreamId("stream-1")).thenReturn(Optional.empty());
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.empty());

            boolean result = interruptionService.interrupt("stream-1", "pod died");

            assertThat(result).isTrue();
            verify(conversationHistoryService).addMessage(
                    eq("conv-1"), eq("assistant"), eq("partial answer"), eq("gpt-4"),
                    anyString(), isNull(), eq("system"));
            verify(pubSubService).publishStopped("stream-1", "partial answer");
            verify(stateService).updateState("stream-1", StreamState.INTERRUPTED);
            verify(streamService).markStreamAsInterrupted("stream-1", "pod died");
        }

        @Test
        @DisplayName("should not save a message when partial content is blank, but still mark INTERRUPTED")
        void shouldSkipMessageForBlankContent() {
            when(stateService.getMetadata("stream-1")).thenReturn(Mono.just(metadata(StreamState.STREAMING)));
            when(stateService.getFullContent("stream-1")).thenReturn(Mono.just("   "));
            when(pubSubService.publishStopped(eq("stream-1"), anyString())).thenReturn(Mono.just(1L));
            when(stateService.updateState("stream-1", StreamState.INTERRUPTED)).thenReturn(Mono.just(true));
            when(stateService.delete("stream-1")).thenReturn(Mono.just(1L));
            when(streamService.getStreamByStreamId("stream-1")).thenReturn(Optional.empty());
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.empty());

            boolean result = interruptionService.interrupt("stream-1", "timeout");

            assertThat(result).isTrue();
            verify(conversationHistoryService, never()).addMessage(any(), any(), any(), any(), any(), any(), any());
            verify(stateService).updateState("stream-1", StreamState.INTERRUPTED);
        }

        @Test
        @DisplayName("should return false without side effects when no Redis metadata exists")
        void shouldReturnFalseWhenNoMetadata() {
            when(stateService.getMetadata("stream-1")).thenReturn(Mono.empty());

            boolean result = interruptionService.interrupt("stream-1", "timeout");

            assertThat(result).isFalse();
            verifyNoInteractions(conversationHistoryService, pubSubService);
            verify(stateService, never()).updateState(any(), any());
            verify(streamService, never()).markStreamAsInterrupted(any(), any());
        }

        @Test
        @DisplayName("should return false when stream is already in a terminal state")
        void shouldReturnFalseWhenAlreadyTerminal() {
            when(stateService.getMetadata("stream-1")).thenReturn(Mono.just(metadata(StreamState.COMPLETED)));

            boolean result = interruptionService.interrupt("stream-1", "timeout");

            assertThat(result).isFalse();
            verifyNoInteractions(conversationHistoryService, pubSubService);
            verify(stateService, never()).updateState(any(), any());
        }

        @Test
        @DisplayName("should attribute the rescued message to the DB stream row's userId when present")
        void shouldUseDbRowUserId() {
            when(stateService.getMetadata("stream-1")).thenReturn(Mono.just(metadata(StreamState.STREAMING)));
            when(stateService.getFullContent("stream-1")).thenReturn(Mono.just("partial"));
            when(pubSubService.publishStopped(eq("stream-1"), anyString())).thenReturn(Mono.just(1L));
            when(stateService.updateState("stream-1", StreamState.INTERRUPTED)).thenReturn(Mono.just(true));
            when(stateService.delete("stream-1")).thenReturn(Mono.just(1L));
            Stream dbRow = new Stream();
            dbRow.setUserId("user-42");
            when(streamService.getStreamByStreamId("stream-1")).thenReturn(Optional.of(dbRow));

            interruptionService.interrupt("stream-1", "heartbeat lost");

            verify(conversationHistoryService).addMessage(
                    eq("conv-1"), eq("assistant"), eq("partial"), eq("gpt-4"),
                    anyString(), isNull(), eq("user-42"));
            verifyNoInteractions(conversationRepository);
        }

        @Test
        @DisplayName("should fall back to the conversation owner's userId when no DB stream row exists")
        void shouldFallBackToConversationOwner() {
            when(stateService.getMetadata("stream-1")).thenReturn(Mono.just(metadata(StreamState.STREAMING)));
            when(stateService.getFullContent("stream-1")).thenReturn(Mono.just("partial"));
            when(pubSubService.publishStopped(eq("stream-1"), anyString())).thenReturn(Mono.just(1L));
            when(stateService.updateState("stream-1", StreamState.INTERRUPTED)).thenReturn(Mono.just(true));
            when(stateService.delete("stream-1")).thenReturn(Mono.just(1L));
            when(streamService.getStreamByStreamId("stream-1")).thenReturn(Optional.empty());
            Conversation conv = mock(Conversation.class);
            when(conv.getUserId()).thenReturn("owner-7");
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            interruptionService.interrupt("stream-1", "heartbeat lost");

            verify(conversationHistoryService).addMessage(
                    eq("conv-1"), eq("assistant"), eq("partial"), eq("gpt-4"),
                    anyString(), isNull(), eq("owner-7"));
        }

        @Test
        @DisplayName("M2: should return false without any rescue effect when the interrupt claim is already held by another rescuer")
        void shouldReturnFalseWhenClaimRefused() {
            when(valueOperations.setIfAbsent(eq("stream:interrupt:claim:stream-1"), eq("1"), any(Duration.class)))
                    .thenReturn(false);

            boolean result = interruptionService.interrupt("stream-1", "pod died");

            assertThat(result).isFalse();
            // The losing rescuer must not touch ANYTHING - no read, no save, no publish, no state flip
            verifyNoInteractions(stateService, conversationHistoryService, pubSubService,
                    streamService, conversationRepository);
        }

        @Test
        @DisplayName("M2: should proceed with the full rescue when the claim call throws (best-effort, duplicate beats data loss)")
        void shouldProceedWhenClaimThrows() {
            when(valueOperations.setIfAbsent(eq("stream:interrupt:claim:stream-1"), eq("1"), any(Duration.class)))
                    .thenThrow(new RuntimeException("Redis down"));
            when(stateService.getMetadata("stream-1")).thenReturn(Mono.just(metadata(StreamState.STREAMING)));
            when(stateService.getFullContent("stream-1")).thenReturn(Mono.just("partial answer"));
            when(pubSubService.publishStopped(eq("stream-1"), anyString())).thenReturn(Mono.just(1L));
            when(stateService.updateState("stream-1", StreamState.INTERRUPTED)).thenReturn(Mono.just(true));
            when(stateService.delete("stream-1")).thenReturn(Mono.just(1L));
            when(streamService.getStreamByStreamId("stream-1")).thenReturn(Optional.empty());
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.empty());

            boolean result = interruptionService.interrupt("stream-1", "pod died");

            assertThat(result).isTrue();
            verify(conversationHistoryService).addMessage(
                    eq("conv-1"), eq("assistant"), eq("partial answer"), eq("gpt-4"),
                    anyString(), isNull(), eq("system"));
            verify(stateService).updateState("stream-1", StreamState.INTERRUPTED);
            verify(streamService).markStreamAsInterrupted("stream-1", "pod died");
        }

        @Test
        @DisplayName("should still mark INTERRUPTED when saving the partial content throws (best-effort steps)")
        void shouldContinueWhenSaveFails() {
            when(stateService.getMetadata("stream-1")).thenReturn(Mono.just(metadata(StreamState.STREAMING)));
            when(stateService.getFullContent("stream-1")).thenReturn(Mono.just("partial"));
            when(conversationHistoryService.addMessage(any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("DB down"));
            when(pubSubService.publishStopped(eq("stream-1"), anyString())).thenReturn(Mono.just(1L));
            when(stateService.updateState("stream-1", StreamState.INTERRUPTED)).thenReturn(Mono.just(true));
            when(stateService.delete("stream-1")).thenReturn(Mono.just(1L));
            when(streamService.getStreamByStreamId("stream-1")).thenReturn(Optional.empty());
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.empty());

            boolean result = interruptionService.interrupt("stream-1", "timeout");

            assertThat(result).isTrue();
            verify(stateService).updateState("stream-1", StreamState.INTERRUPTED);
            verify(streamService).markStreamAsInterrupted("stream-1", "timeout");
        }
    }
}
