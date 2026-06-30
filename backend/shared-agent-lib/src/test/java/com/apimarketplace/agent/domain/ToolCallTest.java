package com.apimarketplace.agent.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ToolCall record.
 */
@DisplayName("ToolCall")
class ToolCallTest {

    @Test
    @DisplayName("should build with all fields")
    void shouldBuildWithAllFields() {
        ToolCall tc = ToolCall.builder()
                .id("call-001")
                .toolName("send_email")
                .arguments(Map.of("to", "user@example.com", "subject", "Hi"))
                .index(0)
                .build();

        assertThat(tc.id()).isEqualTo("call-001");
        assertThat(tc.toolName()).isEqualTo("send_email");
        assertThat(tc.arguments()).containsEntry("to", "user@example.com");
        assertThat(tc.arguments()).containsEntry("subject", "Hi");
        assertThat(tc.index()).isEqualTo(0);
    }

    @Test
    @DisplayName("should build with minimal fields")
    void shouldBuildWithMinimalFields() {
        ToolCall tc = ToolCall.builder()
                .id("call-002")
                .toolName("get_weather")
                .build();

        assertThat(tc.id()).isEqualTo("call-002");
        assertThat(tc.toolName()).isEqualTo("get_weather");
        assertThat(tc.arguments()).isNull();
        assertThat(tc.index()).isNull();
    }

    @Test
    @DisplayName("should support equality based on all fields")
    void shouldSupportEquality() {
        ToolCall tc1 = ToolCall.builder()
                .id("call-001").toolName("search").arguments(Map.of("q", "test")).build();
        ToolCall tc2 = ToolCall.builder()
                .id("call-001").toolName("search").arguments(Map.of("q", "test")).build();

        assertThat(tc1).isEqualTo(tc2);
    }
}
