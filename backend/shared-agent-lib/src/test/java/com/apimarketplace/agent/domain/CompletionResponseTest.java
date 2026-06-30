package com.apimarketplace.agent.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for CompletionResponse record.
 */
@DisplayName("CompletionResponse")
class CompletionResponseTest {

    @Nested
    @DisplayName("hasToolCalls()")
    class HasToolCallsTests {

        @Test
        @DisplayName("should return true when tool calls exist")
        void shouldReturnTrueWithToolCalls() {
            ToolCall tc = ToolCall.builder().id("tc-1").toolName("search").build();
            CompletionResponse resp = CompletionResponse.builder()
                    .toolCalls(List.of(tc))
                    .build();

            assertThat(resp.hasToolCalls()).isTrue();
        }

        @Test
        @DisplayName("should return false when tool calls list is empty")
        void shouldReturnFalseWhenEmpty() {
            CompletionResponse resp = CompletionResponse.builder()
                    .toolCalls(List.of())
                    .build();

            assertThat(resp.hasToolCalls()).isFalse();
        }

        @Test
        @DisplayName("should return false when tool calls list is null")
        void shouldReturnFalseWhenNull() {
            CompletionResponse resp = CompletionResponse.builder().build();

            assertThat(resp.hasToolCalls()).isFalse();
        }
    }

    @Nested
    @DisplayName("isComplete()")
    class IsCompleteTests {

        @Test
        @DisplayName("should return true when no tool calls and finish reason is stop")
        void shouldReturnTrueWhenComplete() {
            CompletionResponse resp = CompletionResponse.builder()
                    .content("Hello!")
                    .finishReason("stop")
                    .build();

            assertThat(resp.isComplete()).isTrue();
        }

        @Test
        @DisplayName("should return false when has tool calls")
        void shouldReturnFalseWithToolCalls() {
            ToolCall tc = ToolCall.builder().id("tc-1").toolName("search").build();
            CompletionResponse resp = CompletionResponse.builder()
                    .toolCalls(List.of(tc))
                    .finishReason("tool_calls")
                    .build();

            assertThat(resp.isComplete()).isFalse();
        }

        @Test
        @DisplayName("should return false when finish reason is not stop")
        void shouldReturnFalseWhenNotStop() {
            CompletionResponse resp = CompletionResponse.builder()
                    .content("partial")
                    .finishReason("length")
                    .build();

            assertThat(resp.isComplete()).isFalse();
        }
    }

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethodTests {

        @Test
        @DisplayName("text() should create simple text response")
        void textShouldCreateSimpleResponse() {
            CompletionResponse resp = CompletionResponse.text("Hello World");

            assertThat(resp.content()).isEqualTo("Hello World");
            assertThat(resp.finishReason()).isEqualTo("stop");
            assertThat(resp.hasToolCalls()).isFalse();
            assertThat(resp.isComplete()).isTrue();
        }

        @Test
        @DisplayName("error() should create error response")
        void errorShouldCreateErrorResponse() {
            CompletionResponse resp = CompletionResponse.error("Something went wrong");

            assertThat(resp.content()).isEqualTo("Something went wrong");
            assertThat(resp.finishReason()).isEqualTo("error");
            assertThat(resp.metadata()).containsEntry("error", true);
            assertThat(resp.isComplete()).isFalse();
        }
    }
}
