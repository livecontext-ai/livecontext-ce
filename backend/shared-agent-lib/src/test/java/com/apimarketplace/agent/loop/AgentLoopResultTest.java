package com.apimarketplace.agent.loop;

import com.apimarketplace.agent.domain.AgentStopReason;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.UsageInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for AgentLoopResult record.
 */
@DisplayName("AgentLoopResult")
class AgentLoopResultTest {

    @Nested
    @DisplayName("success() factory methods")
    class SuccessFactoryTests {

        @Test
        @DisplayName("should create success result with legacy factory (7 args)")
        void shouldCreateSuccessLegacy() {
            CompletionResponse response = CompletionResponse.text("Done!");
            UsageInfo usage = UsageInfo.empty();

            AgentLoopResult result = AgentLoopResult.success(
                    response, List.of(), 3, usage, 1000L, "openai", "gpt-4"
            );

            assertThat(result.success()).isTrue();
            assertThat(result.content()).isEqualTo("Done!");
            assertThat(result.iterations()).isEqualTo(3);
            assertThat(result.durationMs()).isEqualTo(1000L);
            assertThat(result.provider()).isEqualTo("openai");
            assertThat(result.model()).isEqualTo("gpt-4");
            assertThat(result.stopReason()).isEqualTo(AgentStopReason.COMPLETED);
        }

        @Test
        @DisplayName("should create success result with full factory (10 args)")
        void shouldCreateSuccessFull() {
            CompletionResponse response = CompletionResponse.text("Complete");
            UsageInfo usage = UsageInfo.builder().promptTokens(100).completionTokens(50).totalTokens(150).build();

            AgentLoopResult result = AgentLoopResult.success(
                    response, List.of(), 5, usage, 2000L,
                    "anthropic", "claude-3",
                    List.of(), AgentStopReason.MAX_ITERATIONS,
                    Map.of("key", "value")
            );

            assertThat(result.success()).isTrue();
            assertThat(result.content()).isEqualTo("Complete");
            assertThat(result.stopReason()).isEqualTo(AgentStopReason.MAX_ITERATIONS);
            assertThat(result.metrics()).containsEntry("key", "value");
        }

        @Test
        @DisplayName("should handle null response gracefully")
        void shouldHandleNullResponse() {
            AgentLoopResult result = AgentLoopResult.success(
                    null, List.of(), 1, UsageInfo.empty(), 100L,
                    "openai", "gpt-4",
                    List.of(), AgentStopReason.COMPLETED, Map.of()
            );

            assertThat(result.success()).isTrue();
            assertThat(result.content()).isEqualTo("");
        }
    }

    @Nested
    @DisplayName("failure() factory methods")
    class FailureFactoryTests {

        @Test
        @DisplayName("should create failure result with default ERROR stop reason")
        void shouldCreateFailureDefault() {
            AgentLoopResult result = AgentLoopResult.failure(
                    "Connection failed", 500L, "openai"
            );

            assertThat(result.success()).isFalse();
            assertThat(result.error()).isEqualTo("Connection failed");
            assertThat(result.durationMs()).isEqualTo(500L);
            assertThat(result.provider()).isEqualTo("openai");
            assertThat(result.stopReason()).isEqualTo(AgentStopReason.ERROR);
        }

        @Test
        @DisplayName("should create failure result with specific stop reason")
        void shouldCreateFailureWithStopReason() {
            AgentLoopResult result = AgentLoopResult.failure(
                    "Timed out", 30000L, "anthropic", AgentStopReason.TIMEOUT
            );

            assertThat(result.success()).isFalse();
            assertThat(result.stopReason()).isEqualTo(AgentStopReason.TIMEOUT);
        }
    }
}
