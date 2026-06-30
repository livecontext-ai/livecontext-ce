package com.apimarketplace.conversation.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Stream Entity")
class StreamEntityTest {

    @Nested
    @DisplayName("StreamStatus")
    class StreamStatusTests {

        @Test
        @DisplayName("should have all expected statuses")
        void shouldHaveAllStatuses() {
            assertThat(Stream.StreamStatus.values()).containsExactlyInAnyOrder(
                    Stream.StreamStatus.ACTIVE,
                    Stream.StreamStatus.STOPPED,
                    Stream.StreamStatus.COMPLETED,
                    Stream.StreamStatus.ERROR,
                    Stream.StreamStatus.INTERRUPTED
            );
        }
    }

    @Nested
    @DisplayName("isActive")
    class IsActive {

        @Test
        @DisplayName("should return true when status is ACTIVE")
        void shouldReturnTrueWhenActive() {
            Stream stream = new Stream();
            stream.setStatus(Stream.StreamStatus.ACTIVE);
            assertThat(stream.isActive()).isTrue();
        }

        @Test
        @DisplayName("should return false when status is not ACTIVE")
        void shouldReturnFalseWhenNotActive() {
            Stream stream = new Stream();
            stream.setStatus(Stream.StreamStatus.COMPLETED);
            assertThat(stream.isActive()).isFalse();
        }
    }

    @Nested
    @DisplayName("markAsCompleted")
    class MarkAsCompleted {

        @Test
        @DisplayName("should set status to COMPLETED and set completedAt")
        void shouldSetCompleted() {
            Stream stream = new Stream();
            stream.setStatus(Stream.StreamStatus.ACTIVE);

            stream.markAsCompleted();

            assertThat(stream.getStatus()).isEqualTo(Stream.StreamStatus.COMPLETED);
            assertThat(stream.getCompletedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("markAsStopped")
    class MarkAsStopped {

        @Test
        @DisplayName("should set status to STOPPED and set completedAt")
        void shouldSetStopped() {
            Stream stream = new Stream();
            stream.setStatus(Stream.StreamStatus.ACTIVE);

            stream.markAsStopped();

            assertThat(stream.getStatus()).isEqualTo(Stream.StreamStatus.STOPPED);
            assertThat(stream.getCompletedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("markAsError")
    class MarkAsError {

        @Test
        @DisplayName("should set status to ERROR with error message and completedAt")
        void shouldSetError() {
            Stream stream = new Stream();
            stream.setStatus(Stream.StreamStatus.ACTIVE);

            stream.markAsError("Connection timeout");

            assertThat(stream.getStatus()).isEqualTo(Stream.StreamStatus.ERROR);
            assertThat(stream.getErrorMessage()).isEqualTo("Connection timeout");
            assertThat(stream.getCompletedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("markAsInterrupted")
    class MarkAsInterrupted {

        @Test
        @DisplayName("should set status to INTERRUPTED with reason and completedAt")
        void shouldSetInterrupted() {
            Stream stream = new Stream();
            stream.setStatus(Stream.StreamStatus.ACTIVE);

            stream.markAsInterrupted("Agent heartbeat lost");

            assertThat(stream.getStatus()).isEqualTo(Stream.StreamStatus.INTERRUPTED);
            assertThat(stream.getErrorMessage()).isEqualTo("Agent heartbeat lost");
            assertThat(stream.getCompletedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Equals and HashCode")
    class EqualsHashCode {

        @Test
        @DisplayName("should be equal when IDs match")
        void shouldBeEqualWithSameId() {
            Stream stream1 = new Stream("id-1", "conv-1", "s-1", "u-1", Stream.StreamStatus.ACTIVE);
            Stream stream2 = new Stream("id-1", "conv-2", "s-2", "u-2", Stream.StreamStatus.COMPLETED);

            assertThat(stream1).isEqualTo(stream2);
            assertThat(stream1.hashCode()).isEqualTo(stream2.hashCode());
        }

        @Test
        @DisplayName("should not be equal when IDs differ")
        void shouldNotBeEqualWithDifferentId() {
            Stream stream1 = new Stream("id-1", "conv-1", "s-1", "u-1", Stream.StreamStatus.ACTIVE);
            Stream stream2 = new Stream("id-2", "conv-1", "s-1", "u-1", Stream.StreamStatus.ACTIVE);

            assertThat(stream1).isNotEqualTo(stream2);
        }
    }

    @Nested
    @DisplayName("Constructor and getters/setters")
    class ConstructorAndAccessors {

        @Test
        @DisplayName("should create stream with all args constructor")
        void shouldCreateWithAllArgs() {
            Stream stream = new Stream("id-1", "conv-1", "stream-1", "user-1", Stream.StreamStatus.ACTIVE);

            assertThat(stream.getId()).isEqualTo("id-1");
            assertThat(stream.getConversationId()).isEqualTo("conv-1");
            assertThat(stream.getStreamId()).isEqualTo("stream-1");
            assertThat(stream.getUserId()).isEqualTo("user-1");
            assertThat(stream.getStatus()).isEqualTo(Stream.StreamStatus.ACTIVE);
        }

        @Test
        @DisplayName("should create stream with default constructor")
        void shouldCreateWithDefaultConstructor() {
            Stream stream = new Stream();
            stream.setId("id-1");
            stream.setConversationId("conv-1");
            stream.setStreamId("stream-1");
            stream.setUserId("user-1");

            assertThat(stream.getId()).isEqualTo("id-1");
            assertThat(stream.getStatus()).isEqualTo(Stream.StreamStatus.ACTIVE); // default
        }

        @Test
        @DisplayName("toString should contain key fields")
        void toStringShouldContainKeyFields() {
            Stream stream = new Stream("id-1", "conv-1", "stream-1", "user-1", Stream.StreamStatus.ACTIVE);
            String str = stream.toString();

            assertThat(str).contains("id-1");
            assertThat(str).contains("conv-1");
            assertThat(str).contains("stream-1");
            assertThat(str).contains("ACTIVE");
        }
    }
}
