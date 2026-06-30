package com.apimarketplace.conversation.dto;

import com.apimarketplace.conversation.streaming.StreamMetadata;
import com.apimarketplace.conversation.streaming.StreamState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StreamStatusResponse")
class StreamStatusResponseTest {

    @Nested
    @DisplayName("from factory method")
    class FromTests {

        @Test
        @DisplayName("should create response from metadata")
        void shouldCreateFromMetadata() {
            Instant now = Instant.now();
            StreamMetadata metadata = new StreamMetadata(
                    "stream-1", "user-1", "conv-1",
                    "gpt-4", "openai",
                    StreamState.STREAMING, now, now, 150
            );

            StreamStatusResponse response = StreamStatusResponse.from(metadata);

            assertThat(response.streamId()).isEqualTo("stream-1");
            assertThat(response.conversationId()).isEqualTo("conv-1");
            assertThat(response.model()).isEqualTo("gpt-4");
            assertThat(response.provider()).isEqualTo("openai");
            assertThat(response.state()).isEqualTo(StreamState.STREAMING);
            assertThat(response.createdAt()).isEqualTo(now);
            assertThat(response.lastActivity()).isEqualTo(now);
            assertThat(response.contentLength()).isEqualTo(150);
            assertThat(response.hasActiveStream()).isTrue();
            assertThat(response.timestamp()).isNotNull();
        }

        @Test
        @DisplayName("should reflect terminal state as not reconnectable")
        void shouldReflectTerminalState() {
            Instant now = Instant.now();
            StreamMetadata metadata = new StreamMetadata(
                    "stream-2", "user-1", "conv-2",
                    "claude-3", "anthropic",
                    StreamState.COMPLETED, now, now, 500
            );

            StreamStatusResponse response = StreamStatusResponse.from(metadata);

            assertThat(response.state()).isEqualTo(StreamState.COMPLETED);
            assertThat(response.hasActiveStream()).isFalse();
        }
    }

    @Nested
    @DisplayName("noActiveStream factory method")
    class NoActiveStreamTests {

        @Test
        @DisplayName("should create response with no active stream")
        void shouldCreateNoActiveStreamResponse() {
            StreamStatusResponse response = StreamStatusResponse.noActiveStream("conv-123");

            assertThat(response.streamId()).isNull();
            assertThat(response.conversationId()).isEqualTo("conv-123");
            assertThat(response.model()).isNull();
            assertThat(response.provider()).isNull();
            assertThat(response.state()).isNull();
            assertThat(response.createdAt()).isNull();
            assertThat(response.lastActivity()).isNull();
            assertThat(response.contentLength()).isZero();
            assertThat(response.hasActiveStream()).isFalse();
            assertThat(response.timestamp()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Record accessors")
    class RecordAccessorTests {

        @Test
        @DisplayName("should access all record components")
        void shouldAccessAllComponents() {
            Instant now = Instant.now();
            String timestampStr = now.toString();

            StreamStatusResponse response = new StreamStatusResponse(
                    "s-1", "c-1", "m-1", "p-1",
                    StreamState.CREATED, now, now, 100, true, timestampStr
            );

            assertThat(response.streamId()).isEqualTo("s-1");
            assertThat(response.conversationId()).isEqualTo("c-1");
            assertThat(response.model()).isEqualTo("m-1");
            assertThat(response.provider()).isEqualTo("p-1");
            assertThat(response.state()).isEqualTo(StreamState.CREATED);
            assertThat(response.createdAt()).isEqualTo(now);
            assertThat(response.lastActivity()).isEqualTo(now);
            assertThat(response.contentLength()).isEqualTo(100);
            assertThat(response.hasActiveStream()).isTrue();
            assertThat(response.timestamp()).isEqualTo(timestampStr);
        }
    }
}
