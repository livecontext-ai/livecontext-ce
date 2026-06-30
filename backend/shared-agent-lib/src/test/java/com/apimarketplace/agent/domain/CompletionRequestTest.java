package com.apimarketplace.agent.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for CompletionRequest record.
 */
@DisplayName("CompletionRequest")
class CompletionRequestTest {

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should build with all fields")
        void shouldBuildWithAllFields() {
            CompletionRequest req = CompletionRequest.builder()
                    .tenantId("tenant-1")
                    .model("gpt-4")
                    .systemPrompt("You are helpful")
                    .userPrompt("Hello")
                    .temperature(0.7)
                    .maxTokens(1000)
                    .topP(0.95)
                    .frequencyPenalty(0.0)
                    .presencePenalty(0.0)
                    .stream(true)
                    .metadata(Map.of("key", "val"))
                    .build();

            assertThat(req.tenantId()).isEqualTo("tenant-1");
            assertThat(req.model()).isEqualTo("gpt-4");
            assertThat(req.systemPrompt()).isEqualTo("You are helpful");
            assertThat(req.userPrompt()).isEqualTo("Hello");
            assertThat(req.temperature()).isEqualTo(0.7);
            assertThat(req.maxTokens()).isEqualTo(1000);
            assertThat(req.topP()).isEqualTo(0.95);
            assertThat(req.stream()).isTrue();
        }
    }

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethodTests {

        @Test
        @DisplayName("simple() should create request with just user prompt")
        void simpleShouldCreateWithPrompt() {
            CompletionRequest req = CompletionRequest.simple("What is AI?");

            assertThat(req.userPrompt()).isEqualTo("What is AI?");
            assertThat(req.systemPrompt()).isNull();
            assertThat(req.model()).isNull();
        }

        @Test
        @DisplayName("withSystem() should create request with both prompts")
        void withSystemShouldCreateWithBothPrompts() {
            CompletionRequest req = CompletionRequest.withSystem("Be concise", "Explain REST");

            assertThat(req.systemPrompt()).isEqualTo("Be concise");
            assertThat(req.userPrompt()).isEqualTo("Explain REST");
        }
    }

    @Nested
    @DisplayName("isStreaming()")
    class IsStreamingTests {

        @Test
        @DisplayName("should return true when stream is true")
        void shouldReturnTrueWhenTrue() {
            CompletionRequest req = CompletionRequest.builder().stream(true).build();
            assertThat(req.isStreaming()).isTrue();
        }

        @Test
        @DisplayName("should return false when stream is false")
        void shouldReturnFalseWhenFalse() {
            CompletionRequest req = CompletionRequest.builder().stream(false).build();
            assertThat(req.isStreaming()).isFalse();
        }

        @Test
        @DisplayName("should return false when stream is null")
        void shouldReturnFalseWhenNull() {
            CompletionRequest req = CompletionRequest.builder().build();
            assertThat(req.isStreaming()).isFalse();
        }
    }
}
