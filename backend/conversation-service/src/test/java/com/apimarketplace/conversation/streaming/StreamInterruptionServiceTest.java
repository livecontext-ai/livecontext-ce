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

    @Nested
    @DisplayName("claimUnsavedReply")
    class ClaimUnsavedReply {

        private static final String CLAIM_KEY = "stream:interrupt:claim:stream-1";

        @Test
        @DisplayName("regression: a stream its producer already COMPLETED is claimable - interrupt() skips terminal streams, so a lost agent-service answer lost the reply for good")
        void claimsTheBufferedReplyOfACompletedStream() {
            when(stateService.getMetadata("stream-1")).thenReturn(Mono.just(metadata(StreamState.COMPLETED)));
            when(stateService.getFullContent("stream-1")).thenReturn(Mono.just("the whole answer"));

            Optional<StreamInterruptionService.BufferedReply> reply = interruptionService.claimUnsavedReply("stream-1");

            assertThat(reply).isPresent();
            assertThat(reply.get().content()).isEqualTo("the whole answer");
            // The state is handed back so the caller never presents a partial as complete.
            assertThat(reply.get().state()).isEqualTo(StreamState.COMPLETED);
            assertThat(reply.get().producerCompleted()).isTrue();
            verify(valueOperations).setIfAbsent(eq(CLAIM_KEY), eq("1"), any(Duration.class));
            // The caller writes the message and owns the terminal event: nothing is written,
            // published, flipped or cleaned up here.
            verifyNoInteractions(conversationHistoryService, pubSubService, streamService);
            verify(stateService, never()).updateState(any(), any());
            verify(stateService, never()).delete(any());
        }

        @Test
        @DisplayName("a stream still STREAMING is claimable and reported as NOT completed (its producer may still be running)")
        void streamingStreamIsReportedNotCompleted() {
            when(stateService.getMetadata("stream-1")).thenReturn(Mono.just(metadata(StreamState.STREAMING)));
            when(stateService.getFullContent("stream-1")).thenReturn(Mono.just("half an answer"));

            StreamInterruptionService.BufferedReply reply = interruptionService.claimUnsavedReply("stream-1").orElseThrow();

            assertThat(reply.state()).isEqualTo(StreamState.STREAMING);
            assertThat(reply.producerCompleted()).isFalse();
            assertThat(reply.producerFailed()).isFalse();
        }

        @Test
        @DisplayName("a stream its producer ended in ERROR is claimable and reported as failed")
        void errorStreamIsReportedFailed() {
            when(stateService.getMetadata("stream-1")).thenReturn(Mono.just(metadata(StreamState.ERROR)));
            when(stateService.getFullContent("stream-1")).thenReturn(Mono.just("partial of a failed run"));

            StreamInterruptionService.BufferedReply reply = interruptionService.claimUnsavedReply("stream-1").orElseThrow();

            assertThat(reply.producerFailed()).isTrue();
            assertThat(reply.producerCompleted()).isFalse();
        }

        @Test
        @DisplayName("nothing buffered: nothing claimed (a claim would hold off the TTL rescue for nothing)")
        void blankBufferClaimsNothing() {
            when(stateService.getMetadata("stream-1")).thenReturn(Mono.just(metadata(StreamState.STREAMING)));
            when(stateService.getFullContent("stream-1")).thenReturn(Mono.just("  \n"));

            assertThat(interruptionService.claimUnsavedReply("stream-1")).isEmpty();

            verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("a stream the user STOPPED is left alone: the stop handler already saved its partial")
        void stoppedStreamIsNotClaimed() {
            when(stateService.getMetadata("stream-1")).thenReturn(Mono.just(metadata(StreamState.STOPPED_BY_USER)));

            assertThat(interruptionService.claimUnsavedReply("stream-1")).isEmpty();

            verify(stateService, never()).getFullContent(any());
            verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("an INTERRUPTED stream is left alone: interrupt() already saved its partial")
        void interruptedStreamIsNotClaimed() {
            when(stateService.getMetadata("stream-1")).thenReturn(Mono.just(metadata(StreamState.INTERRUPTED)));

            assertThat(interruptionService.claimUnsavedReply("stream-1")).isEmpty();

            verify(stateService, never()).getFullContent(any());
        }

        @Test
        @DisplayName("another rescuer holds the shared claim (a drain or TTL rescue): nothing handed out, never saved twice")
        void claimHeldElsewhereHandsOutNothing() {
            when(stateService.getMetadata("stream-1")).thenReturn(Mono.just(metadata(StreamState.STREAMING)));
            when(stateService.getFullContent("stream-1")).thenReturn(Mono.just("partial"));
            when(valueOperations.setIfAbsent(eq(CLAIM_KEY), eq("1"), any(Duration.class))).thenReturn(false);

            assertThat(interruptionService.claimUnsavedReply("stream-1")).isEmpty();
        }

        @Test
        @DisplayName("the claim takes the same key as interrupt(), so interrupt() backs off a stream this rescue already claimed")
        void interruptBacksOffAfterAClaim() {
            java.util.Set<String> claims = new java.util.HashSet<>();
            when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                    .thenAnswer(inv -> claims.add(inv.getArgument(0)));
            when(stateService.getMetadata("stream-1")).thenReturn(Mono.just(metadata(StreamState.STREAMING)));
            when(stateService.getFullContent("stream-1")).thenReturn(Mono.just("partial"));

            assertThat(interruptionService.claimUnsavedReply("stream-1")).isPresent();
            assertThat(interruptionService.interrupt("stream-1", "heartbeat lost")).isFalse();

            verifyNoInteractions(conversationHistoryService);
        }

        @Test
        @DisplayName("an unreadable state does not block the claim (the claim still keeps it single), and is reported as unknown, never as completed")
        void unreadableStateIsUnknownNotCompleted() {
            when(stateService.getMetadata("stream-1")).thenReturn(Mono.error(new RuntimeException("redis blip")));
            when(stateService.getFullContent("stream-1")).thenReturn(Mono.just("answer"));

            StreamInterruptionService.BufferedReply reply = interruptionService.claimUnsavedReply("stream-1").orElseThrow();

            assertThat(reply.state()).isNull();
            assertThat(reply.producerCompleted()).isFalse();
        }
    }

    @Nested
    @DisplayName("endInterrupted")
    class EndInterrupted {

        @Test
        @DisplayName("ends the stream as a cut-off turn: stopped event carrying the partial, INTERRUPTED, keys freed, DB row marked")
        void endsTheStreamAsInterrupted() {
            when(pubSubService.publishStopped("stream-1", "half an answer")).thenReturn(Mono.just(1L));
            when(stateService.updateState("stream-1", StreamState.INTERRUPTED)).thenReturn(Mono.just(true));
            when(stateService.delete("stream-1")).thenReturn(Mono.just(1L));

            interruptionService.endInterrupted("stream-1", "half an answer", "no response from bridge");

            verify(pubSubService).publishStopped("stream-1", "half an answer");
            verify(stateService).updateState("stream-1", StreamState.INTERRUPTED);
            verify(stateService).delete("stream-1");
            verify(streamService).markStreamAsInterrupted("stream-1", "no response from bridge");
            // Never the complete-turn event.
            verify(pubSubService, never()).publishComplete(anyString(), anyString(), anyInt());
        }
    }

    @Nested
    @DisplayName("claimRescue")
    class ClaimRescue {

        @Test
        @DisplayName("the first saver takes the claim; the next one is refused")
        void firstSaverWinsTheNextIsRefused() {
            java.util.Set<String> held = new java.util.HashSet<>();
            when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                    .thenAnswer(inv -> held.add(inv.getArgument(0)));

            assertThat(interruptionService.claimRescue("stream-1")).isTrue();
            assertThat(interruptionService.claimRescue("stream-1")).isFalse();
            assertThat(held).containsExactly("stream:interrupt:claim:stream-1");
        }

        @Test
        @DisplayName("Redis cannot arbitrate: the caller may save (a duplicated save beats a lost reply)")
        void redisFailureLetsTheCallerSave() {
            when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                    .thenThrow(new RuntimeException("Redis down"));

            assertThat(interruptionService.claimRescue("stream-1")).isTrue();
        }
    }

    /**
     * The order of the cancel-key write against the key cleanup, on a state store that deletes
     * and expires keys like the real one ({@link InMemoryStreamStateService}): a mock records
     * the calls but cannot show that the cleanup removed the key written before it.
     */
    @Nested
    @DisplayName("endInterrupted - cancel key order")
    class CancelKeyOrder {

        private InMemoryStreamStateService store;
        private StreamInterruptionService service;

        @BeforeEach
        void realOrderStore() {
            store = new InMemoryStreamStateService();
            store.seed("stream-1", "conv-1", StreamState.STREAMING, "half an answer");
            lenient().when(pubSubService.publishStopped(anyString(), anyString())).thenReturn(Mono.just(1L));
            service = new StreamInterruptionService(store, pubSubService, conversationHistoryService,
                    streamService, conversationRepository, stringRedisTemplate);
        }

        @Test
        @DisplayName("regression: stopping the producer leaves the cancel key IN PLACE after the cleanup, with the Stop path's TTL - written before it, the key was deleted before any poller saw it")
        void cancelKeySurvivesTheCleanup() {
            service.endInterrupted("stream-1", "half an answer", "no response", true);

            assertThat(store.hasStream("stream-1")).as("the stream keys are freed as before").isFalse();
            assertThat(store.hasCancelKey("stream-1")).isTrue();
            assertThat(store.cancelKeyTtl("stream-1")).isEqualTo(InMemoryStreamStateService.STOP_PATH_CANCEL_TTL);
        }

        @Test
        @DisplayName("the mechanism: without stopping the producer (the drain / TTL rescue), the cleanup frees every key, a cancel key written BEFORE it included - the order that left a still-running producer billing")
        void theCleanupDeletesACancelKeyWrittenBeforeIt() {
            store.setCancelKey("stream-1").block();

            service.endInterrupted("stream-1", "half an answer", "pod died");

            assertThat(store.hasStream("stream-1")).isFalse();
            assertThat(store.hasCancelKey("stream-1")).isFalse();
        }
    }
}
