package com.apimarketplace.agent.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ToolResult record.
 */
@DisplayName("ToolResult")
class ToolResultTest {

    private final ToolCall sampleCall = ToolCall.builder()
            .id("tc-1")
            .toolName("search")
            .arguments(Map.of("query", "test"))
            .build();

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethodTests {

        @Test
        @DisplayName("success() should create successful result")
        void shouldCreateSuccess() {
            ToolResult result = ToolResult.success(sampleCall, "{\"data\":\"found\"}");

            assertThat(result.success()).isTrue();
            assertThat(result.content()).isEqualTo("{\"data\":\"found\"}");
            assertThat(result.toolCall()).isEqualTo(sampleCall);
            assertThat(result.error()).isNull();
        }

        @Test
        @DisplayName("failure() should create failed result")
        void shouldCreateFailure() {
            ToolResult result = ToolResult.failure(sampleCall, "Connection timeout");

            assertThat(result.success()).isFalse();
            assertThat(result.error()).isEqualTo("Connection timeout");
            assertThat(result.toolCall()).isEqualTo(sampleCall);
            assertThat(result.content()).isNull();
        }
    }

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should build with all fields including metadata and duration")
        void shouldBuildWithAllFields() {
            ToolResult result = ToolResult.builder()
                    .toolCall(sampleCall)
                    .success(true)
                    .content("result data")
                    .durationMs(250L)
                    .metadata(Map.of("cached", true))
                    .build();

            assertThat(result.durationMs()).isEqualTo(250L);
            assertThat(result.metadata()).containsEntry("cached", true);
        }
    }
}
