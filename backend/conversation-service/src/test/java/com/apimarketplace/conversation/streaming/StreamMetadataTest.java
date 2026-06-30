package com.apimarketplace.conversation.streaming;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StreamMetadata")
class StreamMetadataTest {

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("should create metadata with CREATED state")
        void shouldCreateWithCreatedState() {
            StreamMetadata metadata = StreamMetadata.create("stream-1", "user-1", "conv-1", "gpt-4", "openai");

            assertThat(metadata.streamId()).isEqualTo("stream-1");
            assertThat(metadata.userId()).isEqualTo("user-1");
            assertThat(metadata.conversationId()).isEqualTo("conv-1");
            assertThat(metadata.model()).isEqualTo("gpt-4");
            assertThat(metadata.provider()).isEqualTo("openai");
            assertThat(metadata.state()).isEqualTo(StreamState.CREATED);
            assertThat(metadata.contentLength()).isZero();
            assertThat(metadata.createdAt()).isNotNull();
            assertThat(metadata.lastActivity()).isNotNull();
        }
    }

    @Nested
    @DisplayName("withState")
    class WithState {

        @Test
        @DisplayName("should create copy with new state")
        void shouldCreateCopyWithNewState() {
            StreamMetadata original = StreamMetadata.create("stream-1", "user-1", "conv-1", "gpt-4", "openai");
            StreamMetadata streaming = original.withState(StreamState.STREAMING);

            assertThat(streaming.state()).isEqualTo(StreamState.STREAMING);
            assertThat(streaming.streamId()).isEqualTo("stream-1");
            assertThat(streaming.userId()).isEqualTo("user-1");
            assertThat(streaming.conversationId()).isEqualTo("conv-1");
        }

        @Test
        @DisplayName("should update lastActivity timestamp")
        void shouldUpdateLastActivity() throws InterruptedException {
            StreamMetadata original = StreamMetadata.create("stream-1", "user-1", "conv-1", "gpt-4", "openai");
            Thread.sleep(10); // Ensure different timestamp
            StreamMetadata updated = original.withState(StreamState.STREAMING);

            assertThat(updated.lastActivity()).isAfterOrEqualTo(original.lastActivity());
        }
    }

    @Nested
    @DisplayName("withContentLength")
    class WithContentLength {

        @Test
        @DisplayName("should create copy with new content length")
        void shouldCreateCopyWithNewLength() {
            StreamMetadata original = StreamMetadata.create("stream-1", "user-1", "conv-1", "gpt-4", "openai");
            StreamMetadata updated = original.withContentLength(500);

            assertThat(updated.contentLength()).isEqualTo(500);
            assertThat(updated.streamId()).isEqualTo("stream-1");
        }
    }
}
