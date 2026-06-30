package com.apimarketplace.agent.tool;

import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolParameter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ToolFormatter - formats tools for different LLM providers.
 */
@DisplayName("ToolFormatter")
class ToolFormatterTest {

    private ToolDefinition createTool(String name, String description) {
        ToolParameter queryParam = ToolParameter.builder()
                .name("query")
                .type("string")
                .description("Search query")
                .required(true)
                .build();

        ToolParameter limitParam = ToolParameter.builder()
                .name("limit")
                .type("integer")
                .description("Max results")
                .required(false)
                .enumValues(List.of("5", "10", "20"))
                .defaultValue(10)
                .build();

        return ToolDefinition.builder()
                .name(name)
                .description(description)
                .parameters(List.of(queryParam, limitParam))
                .requiredParameters(List.of("query"))
                .build();
    }

    @Nested
    @DisplayName("formatToolsForProvider()")
    class FormatForProviderTests {

        @Test
        @DisplayName("should return empty list for null tools")
        void shouldReturnEmptyForNull() {
            assertThat(ToolFormatter.formatToolsForProvider(null, "openai")).isEmpty();
        }

        @Test
        @DisplayName("should return empty list for empty tools")
        void shouldReturnEmptyForEmpty() {
            assertThat(ToolFormatter.formatToolsForProvider(List.of(), "openai")).isEmpty();
        }

        @Test
        @DisplayName("should use OpenAI format for openai provider")
        void shouldUseOpenaiFormat() {
            List<Map<String, Object>> result = ToolFormatter.formatToolsForProvider(
                    List.of(createTool("search", "Search API")), "openai"
            );

            assertThat(result).hasSize(1);
            assertThat(result.get(0)).containsKey("type");
            assertThat(result.get(0).get("type")).isEqualTo("function");
        }

        @Test
        @DisplayName("should use Claude format for anthropic provider")
        void shouldUseClaudeFormat() {
            List<Map<String, Object>> result = ToolFormatter.formatToolsForProvider(
                    List.of(createTool("search", "Search API")), "anthropic"
            );

            assertThat(result).hasSize(1);
            assertThat(result.get(0)).containsKey("input_schema");
            assertThat(result.get(0)).doesNotContainKey("type");
        }

        @Test
        @DisplayName("should use Gemini format for google provider")
        void shouldUseGeminiFormat() {
            List<Map<String, Object>> result = ToolFormatter.formatToolsForProvider(
                    List.of(createTool("search", "Search API")), "google"
            );

            assertThat(result).hasSize(1);
            assertThat(result.get(0)).containsKey("parameters");
        }

        @ParameterizedTest
        @ValueSource(strings = {"mistral", "deepseek", "unknown"})
        @DisplayName("should default to OpenAI format for other providers")
        void shouldDefaultToOpenaiFormat(String provider) {
            List<Map<String, Object>> result = ToolFormatter.formatToolsForProvider(
                    List.of(createTool("search", "Search")), provider
            );

            assertThat(result.get(0)).containsKey("type");
            assertThat(result.get(0).get("type")).isEqualTo("function");
        }
    }

    @Nested
    @DisplayName("formatToolsForOpenAI()")
    class OpenAIFormatTests {

        @Test
        @DisplayName("should include function type, name, description, and parameters")
        void shouldIncludeAllFields() {
            List<Map<String, Object>> result = ToolFormatter.formatToolsForOpenAI(
                    List.of(createTool("search_api", "Search the catalog"))
            );

            Map<String, Object> tool = result.get(0);
            assertThat(tool.get("type")).isEqualTo("function");

            @SuppressWarnings("unchecked")
            Map<String, Object> function = (Map<String, Object>) tool.get("function");
            assertThat(function.get("name")).isEqualTo("search_api");
            assertThat(function.get("description")).isEqualTo("Search the catalog");
            assertThat(function).containsKey("parameters");
        }
    }

    @Nested
    @DisplayName("buildParametersSchema()")
    class ParametersSchemaTests {

        @Test
        @DisplayName("should build schema with type, properties, and required")
        void shouldBuildSchema() {
            ToolDefinition tool = createTool("test", "test tool");
            Map<String, Object> schema = ToolFormatter.buildParametersSchema(tool);

            assertThat(schema.get("type")).isEqualTo("object");
            assertThat(schema).containsKey("properties");
            assertThat(schema).containsKey("required");

            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) schema.get("properties");
            assertThat(props).containsKeys("query", "limit");

            @SuppressWarnings("unchecked")
            Map<String, Object> queryProp = (Map<String, Object>) props.get("query");
            assertThat(queryProp.get("type")).isEqualTo("string");
            assertThat(queryProp.get("description")).isEqualTo("Search query");

            @SuppressWarnings("unchecked")
            Map<String, Object> limitProp = (Map<String, Object>) props.get("limit");
            assertThat(limitProp.get("type")).isEqualTo("integer");
            assertThat(limitProp).containsKey("enum");
            assertThat(limitProp.get("default")).isEqualTo(10);
        }

        @Test
        @DisplayName("should build empty schema when no parameters")
        void shouldBuildEmptySchema() {
            ToolDefinition tool = ToolDefinition.builder().name("t").description("d").build();
            Map<String, Object> schema = ToolFormatter.buildParametersSchema(tool);

            assertThat(schema.get("type")).isEqualTo("object");
            assertThat(schema.get("properties")).isEqualTo(Map.of());
        }
    }

    @Nested
    @DisplayName("sanitizeToolName()")
    class SanitizeToolNameTests {

        @Test
        @DisplayName("should lowercase and replace special characters")
        void shouldSanitize() {
            assertThat(ToolFormatter.sanitizeToolName("My Tool-v2")).isEqualTo("my_tool_v2");
            assertThat(ToolFormatter.sanitizeToolName("hello.world")).isEqualTo("hello_world");
        }

        @Test
        @DisplayName("should keep underscores and alphanumeric")
        void shouldKeepValidChars() {
            assertThat(ToolFormatter.sanitizeToolName("search_api_v2")).isEqualTo("search_api_v2");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("should return unknown_tool for null, empty, or blank input")
        void shouldReturnDefaultForInvalid(String input) {
            assertThat(ToolFormatter.sanitizeToolName(input)).isEqualTo("unknown_tool");
        }

        @Test
        @DisplayName("should return unknown_tool when sanitized result is all underscores")
        void shouldReturnDefaultWhenAllUnderscores() {
            assertThat(ToolFormatter.sanitizeToolName("---")).isEqualTo("unknown_tool");
        }
    }

    @Nested
    @DisplayName("isValidToolName()")
    class IsValidToolNameTests {

        @Test
        @DisplayName("should return true for valid names")
        void shouldReturnTrueForValid() {
            assertThat(ToolFormatter.isValidToolName("search_api")).isTrue();
            assertThat(ToolFormatter.isValidToolName("_private")).isTrue();
            assertThat(ToolFormatter.isValidToolName("tool123")).isTrue();
            assertThat(ToolFormatter.isValidToolName("A")).isTrue();
        }

        @Test
        @DisplayName("should return false for invalid names")
        void shouldReturnFalseForInvalid() {
            assertThat(ToolFormatter.isValidToolName("123start")).isFalse();
            assertThat(ToolFormatter.isValidToolName("has-dash")).isFalse();
            assertThat(ToolFormatter.isValidToolName("has space")).isFalse();
            assertThat(ToolFormatter.isValidToolName("")).isFalse();
            assertThat(ToolFormatter.isValidToolName(null)).isFalse();
        }
    }
}
