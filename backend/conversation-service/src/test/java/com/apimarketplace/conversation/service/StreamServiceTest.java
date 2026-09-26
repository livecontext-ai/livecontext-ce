package com.apimarketplace.conversation.service;

import com.apimarketplace.conversation.entity.Stream;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.repository.StreamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("StreamService")
@ExtendWith(MockitoExtension.class)
class StreamServiceTest {

    @Mock
    private StreamRepository streamRepository;

    @Mock
    private ConversationRepository conversationRepository;

    private StreamService streamService;

    @BeforeEach
    void setUp() {
        streamService = new StreamService(streamRepository, conversationRepository);
    }

    @Nested
    @DisplayName("createStream")
    class CreateStream {

        @Test
        @DisplayName("should create a new stream and stop existing active streams")
        void shouldCreateNewStream() {
            // Stated, not inferred: createStream returns early when the streamId already
            // exists, so "not yet registered" is a precondition of creating anything.
            when(streamRepository.findByStreamId("stream-1")).thenReturn(Optional.empty());
            when(conversationRepository.lockConversationRowIfExists("conv-1")).thenReturn(Optional.of(1));
            when(streamRepository.stopAllActiveStreamsForConversation(anyString(), any())).thenReturn(0);
            when(streamRepository.saveAndFlush(any(Stream.class))).thenAnswer(inv -> inv.getArgument(0));

            Stream result = streamService.createStream("conv-1", "stream-1", "user-1");

            assertThat(result).isNotNull();
            assertThat(result.getConversationId()).isEqualTo("conv-1");
            assertThat(result.getStreamId()).isEqualTo("stream-1");
            assertThat(result.getUserId()).isEqualTo("user-1");
            assertThat(result.getStatus()).isEqualTo(Stream.StreamStatus.ACTIVE);
        }

        @Test
        @DisplayName("should stop existing active streams before creating new one")
        void shouldStopExistingStreams() {
            // Stated, not inferred: createStream returns early when the streamId already
            // exists, so "not yet registered" is a precondition of creating anything.
            when(streamRepository.findByStreamId("stream-1")).thenReturn(Optional.empty());
            when(conversationRepository.lockConversationRowIfExists("conv-1")).thenReturn(Optional.of(1));
            when(streamRepository.stopAllActiveStreamsForConversation(anyString(), any())).thenReturn(1);
            when(streamRepository.saveAndFlush(any(Stream.class))).thenAnswer(inv -> inv.getArgument(0));

            streamService.createStream("conv-1", "stream-1", "user-1");

            verify(streamRepository).stopAllActiveStreamsForConversation(eq("conv-1"), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("an already-registered streamId returns the existing row and writes nothing")
        void alreadyRegisteredStreamIdIsReused() {
            Stream existing = new Stream("row-1", "conv-1", "stream-1", "user-1",
                    Stream.StreamStatus.ACTIVE);
            when(streamRepository.findByStreamId("stream-1")).thenReturn(Optional.of(existing));

            Stream result = streamService.createStream("conv-1", "stream-1", "user-1");

            assertThat(result).isSameAs(existing);
            verify(streamRepository, never()).saveAndFlush(any());
            verify(streamRepository, never()).stopAllActiveStreamsForConversation(anyString(), any());
            verify(conversationRepository, never()).lockConversationRowIfExists(anyString());
        }

        @Test
        @DisplayName("a streamId bound to another conversation is refused rather than re-homed")
        void streamIdBoundToAnotherConversationIsRefused() {
            Stream elsewhere = new Stream("row-1", "other-conv", "stream-1", "user-1",
                    Stream.StreamStatus.ACTIVE);
            when(streamRepository.findByStreamId("stream-1")).thenReturn(Optional.of(elsewhere));

            Stream result = streamService.createStream("conv-1", "stream-1", "user-1");

            assertThat(result)
                .as("returning a row from another conversation would silently mis-attribute the "
                    + "stream; null is the established graceful-skip contract")
                .isNull();
            verify(streamRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("should skip the row (return null, no save) when the conversation no longer exists")
        void shouldSkipWhenConversationGone() {
            // FK guard: a non-existent conversation (lock returns empty) must NOT reach the streams
            // INSERT - pre-fix this hit streams_conversation_id_fkey. The chat tolerates the null.
            when(streamRepository.findByStreamId("stream-1")).thenReturn(Optional.empty());
            when(conversationRepository.lockConversationRowIfExists("conv-gone")).thenReturn(Optional.empty());

            Stream result = streamService.createStream("conv-gone", "stream-1", "user-1");

            assertThat(result).isNull();
            verify(streamRepository, never()).saveAndFlush(any());
            verify(streamRepository, never()).stopAllActiveStreamsForConversation(anyString(), any());
        }
    }

    @Nested
    @DisplayName("getActiveStream")
    class GetActiveStream {

        @Test
        @DisplayName("should return active stream when exists")
        void shouldReturnActiveStream() {
            Stream stream = new Stream("id-1", "conv-1", "stream-1", "user-1", Stream.StreamStatus.ACTIVE);
            when(streamRepository.findActiveStreamByConversationId("conv-1")).thenReturn(Optional.of(stream));

            Optional<Stream> result = streamService.getActiveStream("conv-1");
            assertThat(result).isPresent();
            assertThat(result.get().getStreamId()).isEqualTo("stream-1");
        }

        @Test
        @DisplayName("should return empty when no active stream")
        void shouldReturnEmptyWhenNoActive() {
            when(streamRepository.findActiveStreamByConversationId("conv-1")).thenReturn(Optional.empty());

            Optional<Stream> result = streamService.getActiveStream("conv-1");
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("stopAllActiveStreamsForConversation")
    class StopAllActive {

        @Test
        @DisplayName("should return true when streams were stopped")
        void shouldReturnTrueWhenStreamsStopped() {
            when(streamRepository.stopAllActiveStreamsForConversation(anyString(), any())).thenReturn(2);

            assertThat(streamService.stopAllActiveStreamsForConversation("conv-1")).isTrue();
        }

        @Test
        @DisplayName("should return false when no streams to stop")
        void shouldReturnFalseWhenNoStreams() {
            when(streamRepository.stopAllActiveStreamsForConversation(anyString(), any())).thenReturn(0);

            assertThat(streamService.stopAllActiveStreamsForConversation("conv-1")).isFalse();
        }
    }

    @Nested
    @DisplayName("markStreamAsCompleted")
    class MarkCompleted {

        @Test
        @DisplayName("should mark stream as completed when found")
        void shouldMarkAsCompleted() {
            Stream stream = new Stream("id-1", "conv-1", "stream-1", "user-1", Stream.StreamStatus.ACTIVE);
            when(streamRepository.findByStreamId("stream-1")).thenReturn(Optional.of(stream));
            when(streamRepository.save(any(Stream.class))).thenAnswer(inv -> inv.getArgument(0));

            streamService.markStreamAsCompleted("stream-1");

            ArgumentCaptor<Stream> captor = ArgumentCaptor.forClass(Stream.class);
            verify(streamRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(Stream.StreamStatus.COMPLETED);
        }

        @Test
        @DisplayName("should do nothing when stream not found")
        void shouldDoNothingWhenNotFound() {
            when(streamRepository.findByStreamId("stream-x")).thenReturn(Optional.empty());

            streamService.markStreamAsCompleted("stream-x");

            verify(streamRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("markStreamAsStopped")
    class MarkStopped {

        @Test
        @DisplayName("should mark stream as stopped when found")
        void shouldMarkAsStopped() {
            Stream stream = new Stream("id-1", "conv-1", "stream-1", "user-1", Stream.StreamStatus.ACTIVE);
            when(streamRepository.findByStreamId("stream-1")).thenReturn(Optional.of(stream));
            when(streamRepository.save(any(Stream.class))).thenAnswer(inv -> inv.getArgument(0));

            streamService.markStreamAsStopped("stream-1");

            ArgumentCaptor<Stream> captor = ArgumentCaptor.forClass(Stream.class);
            verify(streamRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(Stream.StreamStatus.STOPPED);
        }
    }

    @Nested
    @DisplayName("markStreamAsError")
    class MarkError {

        @Test
        @DisplayName("should mark stream as error with message")
        void shouldMarkAsError() {
            Stream stream = new Stream("id-1", "conv-1", "stream-1", "user-1", Stream.StreamStatus.ACTIVE);
            when(streamRepository.findByStreamId("stream-1")).thenReturn(Optional.of(stream));
            when(streamRepository.save(any(Stream.class))).thenAnswer(inv -> inv.getArgument(0));

            streamService.markStreamAsError("stream-1", "timeout");

            ArgumentCaptor<Stream> captor = ArgumentCaptor.forClass(Stream.class);
            verify(streamRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(Stream.StreamStatus.ERROR);
            assertThat(captor.getValue().getErrorMessage()).isEqualTo("timeout");
        }

        @Test
        @DisplayName("marking a stream ERROR logs no ERROR line (every caller logs the cause itself)")
        void markingErrorDoesNotLogAtError() {
            Stream stream = new Stream("id-1", "conv-1", "stream-1", "user-1", Stream.StreamStatus.ACTIVE);
            when(streamRepository.findByStreamId("stream-1")).thenReturn(Optional.of(stream));
            when(streamRepository.save(any(Stream.class))).thenAnswer(inv -> inv.getArgument(0));
            ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                    org.slf4j.LoggerFactory.getLogger(StreamService.class);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                    new ch.qos.logback.core.read.ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                streamService.markStreamAsError("stream-1", "timeout");
            } finally {
                logger.detachAppender(appender);
            }

            assertThat(appender.list).noneMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR);
        }
    }

    @Nested
    @DisplayName("markStreamAsInterrupted")
    class MarkInterrupted {

        @Test
        @DisplayName("should mark stream as interrupted with reason")
        void shouldMarkAsInterrupted() {
            Stream stream = new Stream("id-1", "conv-1", "stream-1", "user-1", Stream.StreamStatus.ACTIVE);
            when(streamRepository.findByStreamId("stream-1")).thenReturn(Optional.of(stream));
            when(streamRepository.save(any(Stream.class))).thenAnswer(inv -> inv.getArgument(0));

            streamService.markStreamAsInterrupted("stream-1", "Agent heartbeat lost");

            ArgumentCaptor<Stream> captor = ArgumentCaptor.forClass(Stream.class);
            verify(streamRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(Stream.StreamStatus.INTERRUPTED);
            assertThat(captor.getValue().getErrorMessage()).isEqualTo("Agent heartbeat lost");
        }

        @Test
        @DisplayName("should be a no-op when stream row does not exist")
        void shouldNoOpWhenMissing() {
            when(streamRepository.findByStreamId("stream-x")).thenReturn(Optional.empty());

            streamService.markStreamAsInterrupted("stream-x", "reason");

            verify(streamRepository, never()).save(any());
        }
    }

}
