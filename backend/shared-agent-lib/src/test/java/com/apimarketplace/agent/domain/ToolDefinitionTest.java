package com.apimarketplace.agent.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ToolDefinition record.
 */
@DisplayName("ToolDefinition")
class ToolDefinitionTest {

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should build with all fields")
        void shouldBuildWithAllFields() {
            ToolDefinition def = ToolDefinition.builder()
                    .id("tool-123")
                    .name("search_api")
                    .description("Search the API catalog")
                    .apiSlug("catalog")
                    .toolSlug("search")
                    .parameters(List.of())
                    .requiredParameters(List.of("query"))
                    .relevanceScore(0.95)
                    .metadata(Map.of("version", "1.0"))
                    .timeoutMs(5000L)
                    .build();

            assertThat(def.id()).isEqualTo("tool-123");
            assertThat(def.name()).isEqualTo("search_api");
            assertThat(def.description()).isEqualTo("Search the API catalog");
            assertThat(def.apiSlug()).isEqualTo("catalog");
            assertThat(def.toolSlug()).isEqualTo("search");
            assertThat(def.relevanceScore()).isEqualTo(0.95);
            assertThat(def.timeoutMs()).isEqualTo(5000L);
        }
    }

    @Nested
    @DisplayName("getEffectiveTimeoutMs()")
    class GetEffectiveTimeoutTests {

        @Test
        @DisplayName("should return configured timeout when set and positive")
        void shouldReturnConfiguredTimeout() {
            ToolDefinition def = ToolDefinition.builder().timeoutMs(3000L).build();
            assertThat(def.getEffectiveTimeoutMs(10000L)).isEqualTo(3000L);
        }

        @Test
        @DisplayName("should return default timeout when timeoutMs is null")
        void shouldReturnDefaultWhenNull() {
            ToolDefinition def = ToolDefinition.builder().build();
            assertThat(def.getEffectiveTimeoutMs(10000L)).isEqualTo(10000L);
        }

        @Test
        @DisplayName("should return default timeout when timeoutMs is zero")
        void shouldReturnDefaultWhenZero() {
            ToolDefinition def = ToolDefinition.builder().timeoutMs(0L).build();
            assertThat(def.getEffectiveTimeoutMs(10000L)).isEqualTo(10000L);
        }

        @Test
        @DisplayName("should return default timeout when timeoutMs is negative")
        void shouldReturnDefaultWhenNegative() {
            ToolDefinition def = ToolDefinition.builder().timeoutMs(-1L).build();
            assertThat(def.getEffectiveTimeoutMs(10000L)).isEqualTo(10000L);
        }
    }
}
