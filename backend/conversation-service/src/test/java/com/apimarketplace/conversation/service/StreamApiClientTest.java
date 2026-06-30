package com.apimarketplace.conversation.service;

import com.apimarketplace.conversation.entity.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("StreamApiClient")
@ExtendWith(MockitoExtension.class)
class StreamApiClientTest {

    @Mock
    private StreamService streamService;

    @InjectMocks
    private StreamApiClient streamApiClient;

    @Nested
    @DisplayName("createStream")
    class CreateStream {

        @Test
        @DisplayName("should return true on successful creation")
        void shouldReturnTrueOnSuccess() {
            Stream stream = new Stream("id", "conv-1", "stream-1", "user-1", Stream.StreamStatus.ACTIVE);
            when(streamService.createStream("conv-1", "stream-1", "user-1")).thenReturn(stream);

            assertThat(streamApiClient.createStream("stream-1", "conv-1", "user-1")).isTrue();
        }

        @Test
        @DisplayName("should return false when service returns null")
        void shouldReturnFalseOnNull() {
            when(streamService.createStream("conv-1", "stream-1", "user-1")).thenReturn(null);

            assertThat(streamApiClient.createStream("stream-1", "conv-1", "user-1")).isFalse();
        }

        @Test
        @DisplayName("should return false on exception")
        void shouldReturnFalseOnException() {
            when(streamService.createStream("conv-1", "stream-1", "user-1"))
                    .thenThrow(new RuntimeException("DB error"));

            assertThat(streamApiClient.createStream("stream-1", "conv-1", "user-1")).isFalse();
        }
    }

    @Nested
    @DisplayName("markStreamAsCompleted")
    class MarkCompleted {

        @Test
        @DisplayName("should return true on success")
        void shouldReturnTrueOnSuccess() {
            doNothing().when(streamService).markStreamAsCompleted("stream-1");
            assertThat(streamApiClient.markStreamAsCompleted("stream-1")).isTrue();
        }

        @Test
        @DisplayName("should return false on exception")
        void shouldReturnFalseOnError() {
            doThrow(new RuntimeException("error")).when(streamService).markStreamAsCompleted("stream-1");
            assertThat(streamApiClient.markStreamAsCompleted("stream-1")).isFalse();
        }
    }

    @Nested
    @DisplayName("markStreamAsStopped")
    class MarkStopped {

        @Test
        @DisplayName("should return true on success")
        void shouldReturnTrueOnSuccess() {
            doNothing().when(streamService).markStreamAsStopped("stream-1");
            assertThat(streamApiClient.markStreamAsStopped("stream-1")).isTrue();
        }
    }

    @Nested
    @DisplayName("markStreamAsError")
    class MarkError {

        @Test
        @DisplayName("should return true on success")
        void shouldReturnTrueOnSuccess() {
            doNothing().when(streamService).markStreamAsError("stream-1", "timeout");
            assertThat(streamApiClient.markStreamAsError("stream-1", "timeout")).isTrue();
        }
    }

}
