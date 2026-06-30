package com.apimarketplace.agent.streaming;

import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for StreamingEvent sealed interface and its implementations.
 */
@DisplayName("StreamingEvent")
class StreamingEventTest {

    @Nested
    @DisplayName("ContentChunk")
    class ContentChunkTests {

        @Test
        @DisplayName("should create content chunk via factory method")
        void shouldCreateViaFactory() {
            StreamingEvent.ContentChunk chunk = StreamingEvent.ContentChunk.of("Hello");
            assertThat(chunk.content()).isEqualTo("Hello");
        }

        @Test
        @DisplayName("should create via static convenience method")
        void shouldCreateViaConvenience() {
            StreamingEvent event = StreamingEvent.content("World");
            assertThat(event).isInstanceOf(StreamingEvent.ContentChunk.class);
            assertThat(((StreamingEvent.ContentChunk) event).content()).isEqualTo("World");
        }
    }

    @Nested
    @DisplayName("ToolCallEvent")
    class ToolCallEventTests {

        @Test
        @DisplayName("should create tool call event")
        void shouldCreateToolCallEvent() {
            ToolCall tc = ToolCall.builder().id("tc-1").toolName("search").build();
            StreamingEvent.ToolCallEvent event = StreamingEvent.ToolCallEvent.of(tc);

            assertThat(event.toolCall()).isEqualTo(tc);
            assertThat(event.toolCall().toolName()).isEqualTo("search");
        }

        @Test
        @DisplayName("should create via static convenience method")
        void shouldCreateViaConvenience() {
            ToolCall tc = ToolCall.builder().id("tc-1").toolName("execute").build();
            StreamingEvent event = StreamingEvent.toolCall(tc);

            assertThat(event).isInstanceOf(StreamingEvent.ToolCallEvent.class);
        }
    }

    @Nested
    @DisplayName("CompletedEvent")
    class CompletedEventTests {

        @Test
        @DisplayName("should create completed event with response")
        void shouldCreateCompletedEvent() {
            CompletionResponse resp = CompletionResponse.text("Done");
            StreamingEvent.CompletedEvent event = StreamingEvent.CompletedEvent.of(resp);

            assertThat(event.response()).isEqualTo(resp);
            assertThat(event.response().content()).isEqualTo("Done");
        }

        @Test
        @DisplayName("should create via static convenience method")
        void shouldCreateViaConvenience() {
            CompletionResponse resp = CompletionResponse.text("Finished");
            StreamingEvent event = StreamingEvent.completed(resp);

            assertThat(event).isInstanceOf(StreamingEvent.CompletedEvent.class);
        }
    }

    @Nested
    @DisplayName("ErrorEvent")
    class ErrorEventTests {

        @Test
        @DisplayName("should create error event with message only")
        void shouldCreateWithMessageOnly() {
            StreamingEvent.ErrorEvent event = StreamingEvent.ErrorEvent.of("Something failed");

            assertThat(event.message()).isEqualTo("Something failed");
            assertThat(event.cause()).isNull();
        }

        @Test
        @DisplayName("should create error event with message and cause")
        void shouldCreateWithMessageAndCause() {
            Exception cause = new RuntimeException("root cause");
            StreamingEvent.ErrorEvent event = StreamingEvent.ErrorEvent.of("Error occurred", cause);

            assertThat(event.message()).isEqualTo("Error occurred");
            assertThat(event.cause()).isEqualTo(cause);
        }

        @Test
        @DisplayName("should create via static convenience method (message only)")
        void shouldCreateViaConvenienceMessageOnly() {
            StreamingEvent event = StreamingEvent.error("fail");
            assertThat(event).isInstanceOf(StreamingEvent.ErrorEvent.class);
        }

        @Test
        @DisplayName("should create via static convenience method (with cause)")
        void shouldCreateViaConvenienceWithCause() {
            Throwable cause = new RuntimeException("oops");
            StreamingEvent event = StreamingEvent.error("fail", cause);

            assertThat(event).isInstanceOf(StreamingEvent.ErrorEvent.class);
            assertThat(((StreamingEvent.ErrorEvent) event).cause()).isEqualTo(cause);
        }
    }

    @Nested
    @DisplayName("Pattern matching")
    class PatternMatchingTests {

        @Test
        @DisplayName("should support sealed interface pattern matching")
        void shouldSupportPatternMatching() {
            StreamingEvent event = StreamingEvent.content("test");

            String result = switch (event) {
                case StreamingEvent.ContentChunk c -> "content: " + c.content();
                case StreamingEvent.ToolCallEvent t -> "tool: " + t.toolCall().toolName();
                case StreamingEvent.CompletedEvent c -> "done";
                case StreamingEvent.ErrorEvent e -> "error: " + e.message();
            };

            assertThat(result).isEqualTo("content: test");
        }
    }
}
