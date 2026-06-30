package com.apimarketplace.agent.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ToolParameter record.
 */
@DisplayName("ToolParameter")
class ToolParameterTest {

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should build with all fields")
        void shouldBuildWithAllFields() {
            ToolParameter param = ToolParameter.builder()
                    .name("query")
                    .type("string")
                    .description("Search query")
                    .required(true)
                    .defaultValue("default search")
                    .enumValues(List.of("option1", "option2"))
                    .minLength(1)
                    .maxLength(500)
                    .minimum(0.0)
                    .maximum(100.0)
                    .pattern("^[a-zA-Z]+$")
                    .build();

            assertThat(param.name()).isEqualTo("query");
            assertThat(param.type()).isEqualTo("string");
            assertThat(param.description()).isEqualTo("Search query");
            assertThat(param.required()).isTrue();
            assertThat(param.defaultValue()).isEqualTo("default search");
            assertThat(param.enumValues()).containsExactly("option1", "option2");
            assertThat(param.minLength()).isEqualTo(1);
            assertThat(param.maxLength()).isEqualTo(500);
            assertThat(param.minimum()).isEqualTo(0.0);
            assertThat(param.maximum()).isEqualTo(100.0);
            assertThat(param.pattern()).isEqualTo("^[a-zA-Z]+$");
        }

        @Test
        @DisplayName("should build with nested object properties")
        void shouldBuildWithNestedProperties() {
            ToolParameter innerParam = ToolParameter.builder()
                    .name("city")
                    .type("string")
                    .required(true)
                    .build();

            ToolParameter param = ToolParameter.builder()
                    .name("location")
                    .type("object")
                    .properties(Map.of("city", innerParam))
                    .build();

            assertThat(param.properties()).containsKey("city");
            assertThat(param.properties().get("city").name()).isEqualTo("city");
        }

        @Test
        @DisplayName("should build with minimal fields")
        void shouldBuildWithMinimalFields() {
            ToolParameter param = ToolParameter.builder()
                    .name("input")
                    .type("string")
                    .build();

            assertThat(param.name()).isEqualTo("input");
            assertThat(param.type()).isEqualTo("string");
            assertThat(param.required()).isFalse();
            assertThat(param.defaultValue()).isNull();
            assertThat(param.enumValues()).isNull();
        }
    }
}
