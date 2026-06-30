package com.apimarketplace.agent.registry;

import com.apimarketplace.agent.domain.ToolParameter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ToolSchemaGenerator - generates JSON Schemas from tool definitions.
 */
@DisplayName("ToolSchemaGenerator")
class ToolSchemaGeneratorTest {

    @Nested
    @DisplayName("generateInputSchema()")
    class GenerateInputSchemaTests {

        @Test
        @DisplayName("should return object schema with empty properties for null parameters")
        void shouldReturnEmptySchemaForNull() {
            Map<String, Object> schema = ToolSchemaGenerator.generateInputSchema(null, null);

            assertThat(schema.get("type")).isEqualTo("object");
            assertThat(schema.get("properties")).isEqualTo(Map.of());
            assertThat(schema).doesNotContainKey("required");
        }

        @Test
        @DisplayName("should return object schema with empty properties for empty parameters")
        void shouldReturnEmptySchemaForEmpty() {
            Map<String, Object> schema = ToolSchemaGenerator.generateInputSchema(List.of(), List.of());

            assertThat(schema.get("type")).isEqualTo("object");
            assertThat(schema.get("properties")).isEqualTo(Map.of());
        }

        @Test
        @DisplayName("should build schema with properties from parameters")
        void shouldBuildSchemaWithProperties() {
            ToolParameter param = ToolParameter.builder()
                    .name("query")
                    .type("string")
                    .description("Search query")
                    .required(true)
                    .build();

            Map<String, Object> schema = ToolSchemaGenerator.generateInputSchema(
                    List.of(param), List.of("query"));

            assertThat(schema.get("type")).isEqualTo("object");

            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            assertThat(properties).containsKey("query");

            assertThat(schema.get("required")).isEqualTo(List.of("query"));
        }

        @Test
        @DisplayName("should not include required field when requiredParams is null")
        void shouldNotIncludeRequiredWhenNull() {
            ToolParameter param = ToolParameter.builder()
                    .name("opt")
                    .type("string")
                    .description("Optional")
                    .build();

            Map<String, Object> schema = ToolSchemaGenerator.generateInputSchema(
                    List.of(param), null);

            assertThat(schema).doesNotContainKey("required");
        }

        @Test
        @DisplayName("should not include required field when requiredParams is empty")
        void shouldNotIncludeRequiredWhenEmpty() {
            ToolParameter param = ToolParameter.builder()
                    .name("opt")
                    .type("string")
                    .description("Optional")
                    .build();

            Map<String, Object> schema = ToolSchemaGenerator.generateInputSchema(
                    List.of(param), List.of());

            assertThat(schema).doesNotContainKey("required");
        }

        @Test
        @DisplayName("should handle multiple parameters")
        void shouldHandleMultipleParameters() {
            ToolParameter p1 = ToolParameter.builder().name("a").type("string").description("A").build();
            ToolParameter p2 = ToolParameter.builder().name("b").type("integer").description("B").build();
            ToolParameter p3 = ToolParameter.builder().name("c").type("boolean").description("C").build();

            Map<String, Object> schema = ToolSchemaGenerator.generateInputSchema(
                    List.of(p1, p2, p3), List.of("a", "b"));

            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            assertThat(properties).hasSize(3);
            assertThat(properties).containsKeys("a", "b", "c");

            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) schema.get("required");
            assertThat(required).containsExactly("a", "b");
        }
    }

    @Nested
    @DisplayName("generateParameterSchema()")
    class GenerateParameterSchemaTests {

        @Test
        @DisplayName("should include type and description")
        void shouldIncludeTypeAndDescription() {
            ToolParameter param = ToolParameter.builder()
                    .name("query")
                    .type("string")
                    .description("Search query")
                    .build();

            Map<String, Object> schema = ToolSchemaGenerator.generateParameterSchema(param);

            assertThat(schema.get("type")).isEqualTo("string");
            assertThat(schema.get("description")).isEqualTo("Search query");
        }

        @Test
        @DisplayName("should not include description when blank")
        void shouldNotIncludeBlankDescription() {
            ToolParameter param = ToolParameter.builder()
                    .name("x")
                    .type("string")
                    .description("   ")
                    .build();

            Map<String, Object> schema = ToolSchemaGenerator.generateParameterSchema(param);

            assertThat(schema).doesNotContainKey("description");
        }

        @Test
        @DisplayName("should not include description when null")
        void shouldNotIncludeNullDescription() {
            ToolParameter param = ToolParameter.builder()
                    .name("x")
                    .type("string")
                    .build();

            Map<String, Object> schema = ToolSchemaGenerator.generateParameterSchema(param);

            assertThat(schema).doesNotContainKey("description");
        }

        @Test
        @DisplayName("should include enum values when present")
        void shouldIncludeEnumValues() {
            ToolParameter param = ToolParameter.builder()
                    .name("size")
                    .type("string")
                    .enumValues(List.of("small", "medium", "large"))
                    .build();

            Map<String, Object> schema = ToolSchemaGenerator.generateParameterSchema(param);

            assertThat(schema.get("enum")).isEqualTo(List.of("small", "medium", "large"));
        }

        @Test
        @DisplayName("should not include enum when empty")
        void shouldNotIncludeEmptyEnum() {
            ToolParameter param = ToolParameter.builder()
                    .name("size")
                    .type("string")
                    .enumValues(List.of())
                    .build();

            Map<String, Object> schema = ToolSchemaGenerator.generateParameterSchema(param);

            assertThat(schema).doesNotContainKey("enum");
        }

        @Test
        @DisplayName("should include default value when present")
        void shouldIncludeDefaultValue() {
            ToolParameter param = ToolParameter.builder()
                    .name("limit")
                    .type("integer")
                    .defaultValue(10)
                    .build();

            Map<String, Object> schema = ToolSchemaGenerator.generateParameterSchema(param);

            assertThat(schema.get("default")).isEqualTo(10);
        }

        @Test
        @DisplayName("should include nested properties for object type")
        void shouldIncludeNestedProperties() {
            Map<String, ToolParameter> nestedProps = Map.of(
                    "city", ToolParameter.builder().name("city").type("string").build()
            );

            ToolParameter param = ToolParameter.builder()
                    .name("address")
                    .type("object")
                    .properties(nestedProps)
                    .build();

            Map<String, Object> schema = ToolSchemaGenerator.generateParameterSchema(param);

            assertThat(schema.get("type")).isEqualTo("object");
            assertThat(schema.get("properties")).isEqualTo(nestedProps);
        }

        @Test
        @DisplayName("should add items schema for array type")
        void shouldAddItemsForArrayType() {
            ToolParameter param = ToolParameter.builder()
                    .name("tags")
                    .type("array")
                    .build();

            Map<String, Object> schema = ToolSchemaGenerator.generateParameterSchema(param);

            assertThat(schema.get("type")).isEqualTo("array");
            assertThat(schema.get("items")).isEqualTo(Map.of("type", "string"));
        }
    }

    @Nested
    @DisplayName("mapType() via generateParameterSchema")
    class MapTypeTests {

        @ParameterizedTest
        @CsvSource({
                "string, string",
                "text, string",
                "STRING, string",
                "TEXT, string"
        })
        @DisplayName("should map string-like types to string")
        void shouldMapStringTypes(String input, String expected) {
            ToolParameter param = ToolParameter.builder().name("x").type(input).build();
            Map<String, Object> schema = ToolSchemaGenerator.generateParameterSchema(param);
            assertThat(schema.get("type")).isEqualTo(expected);
        }

        @ParameterizedTest
        @CsvSource({
                "int, integer",
                "integer, integer",
                "long, integer",
                "INT, integer"
        })
        @DisplayName("should map integer-like types to integer")
        void shouldMapIntegerTypes(String input, String expected) {
            ToolParameter param = ToolParameter.builder().name("x").type(input).build();
            Map<String, Object> schema = ToolSchemaGenerator.generateParameterSchema(param);
            assertThat(schema.get("type")).isEqualTo(expected);
        }

        @ParameterizedTest
        @CsvSource({
                "float, number",
                "double, number",
                "number, number",
                "decimal, number"
        })
        @DisplayName("should map number-like types to number")
        void shouldMapNumberTypes(String input, String expected) {
            ToolParameter param = ToolParameter.builder().name("x").type(input).build();
            Map<String, Object> schema = ToolSchemaGenerator.generateParameterSchema(param);
            assertThat(schema.get("type")).isEqualTo(expected);
        }

        @ParameterizedTest
        @CsvSource({
                "bool, boolean",
                "boolean, boolean",
                "BOOL, boolean"
        })
        @DisplayName("should map boolean-like types to boolean")
        void shouldMapBooleanTypes(String input, String expected) {
            ToolParameter param = ToolParameter.builder().name("x").type(input).build();
            Map<String, Object> schema = ToolSchemaGenerator.generateParameterSchema(param);
            assertThat(schema.get("type")).isEqualTo(expected);
        }

        @ParameterizedTest
        @CsvSource({
                "array, array",
                "list, array"
        })
        @DisplayName("should map array-like types to array")
        void shouldMapArrayTypes(String input, String expected) {
            ToolParameter param = ToolParameter.builder().name("x").type(input).build();
            Map<String, Object> schema = ToolSchemaGenerator.generateParameterSchema(param);
            assertThat(schema.get("type")).isEqualTo(expected);
        }

        @ParameterizedTest
        @CsvSource({
                "object, object",
                "map, object",
                "json, object"
        })
        @DisplayName("should map object-like types to object")
        void shouldMapObjectTypes(String input, String expected) {
            ToolParameter param = ToolParameter.builder().name("x").type(input).build();
            Map<String, Object> schema = ToolSchemaGenerator.generateParameterSchema(param);
            assertThat(schema.get("type")).isEqualTo(expected);
        }

        @Test
        @DisplayName("should default to string for null type")
        void shouldDefaultToStringForNull() {
            ToolParameter param = ToolParameter.builder().name("x").type(null).build();
            Map<String, Object> schema = ToolSchemaGenerator.generateParameterSchema(param);
            assertThat(schema.get("type")).isEqualTo("string");
        }

        @ParameterizedTest
        @ValueSource(strings = {"custom", "blob", "unknown"})
        @DisplayName("should default to string for unknown types")
        void shouldDefaultToStringForUnknown(String type) {
            ToolParameter param = ToolParameter.builder().name("x").type(type).build();
            Map<String, Object> schema = ToolSchemaGenerator.generateParameterSchema(param);
            assertThat(schema.get("type")).isEqualTo("string");
        }
    }

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethodTests {

        @Test
        @DisplayName("stringParam should create string parameter")
        void shouldCreateStringParam() {
            ToolParameter param = ToolSchemaGenerator.stringParam("query", "Search query", true);

            assertThat(param.name()).isEqualTo("query");
            assertThat(param.type()).isEqualTo("string");
            assertThat(param.description()).isEqualTo("Search query");
            assertThat(param.required()).isTrue();
        }

        @Test
        @DisplayName("intParam should create integer parameter with default")
        void shouldCreateIntParam() {
            ToolParameter param = ToolSchemaGenerator.intParam("limit", "Max results", false, 10);

            assertThat(param.name()).isEqualTo("limit");
            assertThat(param.type()).isEqualTo("integer");
            assertThat(param.description()).isEqualTo("Max results");
            assertThat(param.required()).isFalse();
            assertThat(param.defaultValue()).isEqualTo(10);
        }

        @Test
        @DisplayName("boolParam should create boolean parameter with default")
        void shouldCreateBoolParam() {
            ToolParameter param = ToolSchemaGenerator.boolParam("verbose", "Enable verbose", false, true);

            assertThat(param.name()).isEqualTo("verbose");
            assertThat(param.type()).isEqualTo("boolean");
            assertThat(param.required()).isFalse();
            assertThat(param.defaultValue()).isEqualTo(true);
        }

        @Test
        @DisplayName("enumParam should create enum parameter with values")
        void shouldCreateEnumParam() {
            ToolParameter param = ToolSchemaGenerator.enumParam("size", "Size", true, List.of("S", "M", "L"));

            assertThat(param.name()).isEqualTo("size");
            assertThat(param.type()).isEqualTo("string");
            assertThat(param.required()).isTrue();
            assertThat(param.enumValues()).containsExactly("S", "M", "L");
        }

        @Test
        @DisplayName("objectParam should create object parameter")
        void shouldCreateObjectParam() {
            ToolParameter param = ToolSchemaGenerator.objectParam("config", "Config object", true);

            assertThat(param.name()).isEqualTo("config");
            assertThat(param.type()).isEqualTo("object");
            assertThat(param.required()).isTrue();
        }

        @Test
        @DisplayName("arrayParam should create array parameter")
        void shouldCreateArrayParam() {
            ToolParameter param = ToolSchemaGenerator.arrayParam("tags", "Tag list", false);

            assertThat(param.name()).isEqualTo("tags");
            assertThat(param.type()).isEqualTo("array");
            assertThat(param.required()).isFalse();
        }

        @Test
        @DisplayName("objectParamWithProperties should create object with nested schema")
        void shouldCreateObjectParamWithProperties() {
            Map<String, ToolParameter> props = Map.of(
                    "city", ToolParameter.builder().name("city").type("string").build()
            );

            ToolParameter param = ToolSchemaGenerator.objectParamWithProperties(
                    "address", "Address", true, props);

            assertThat(param.name()).isEqualTo("address");
            assertThat(param.type()).isEqualTo("object");
            assertThat(param.properties()).isEqualTo(props);
        }
    }

    @Nested
    @DisplayName("Schema generation methods")
    class SchemaGenerationTests {

        @Test
        @DisplayName("getWorkflowPlanSchema should return valid schema structure")
        void shouldReturnWorkflowPlanSchema() {
            Map<String, Object> schema = ToolSchemaGenerator.getWorkflowPlanSchema();

            assertThat(schema.get("type")).isEqualTo("object");
            assertThat(schema).containsKey("properties");
            assertThat(schema).containsKey("required");

            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            assertThat(properties).containsKeys(
                    "triggers", "mcps", "tables", "agents", "cores", "notes", "interfaces", "edges");

            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) schema.get("required");
            assertThat(required).containsExactly("triggers", "edges");
        }

        @Test
        @DisplayName("getAgentConfigSchema should return valid schema")
        void shouldReturnAgentConfigSchema() {
            Map<String, Object> schema = ToolSchemaGenerator.getAgentConfigSchema();

            assertThat(schema.get("type")).isEqualTo("object");

            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            assertThat(properties).containsKeys("name", "systemPrompt", "provider", "model",
                    "temperature", "maxTokens", "maxIterations", "tools");

            assertThat(schema.get("required")).isEqualTo(List.of("name"));
        }

        @Test
        @DisplayName("getInterfaceSchema should return valid schema")
        void shouldReturnInterfaceSchema() {
            Map<String, Object> schema = ToolSchemaGenerator.getInterfaceSchema();

            assertThat(schema.get("type")).isEqualTo("object");

            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            assertThat(properties).containsKeys("name", "description", "htmlTemplate",
                    "variable_mapping", "action_mapping");

            assertThat(schema.get("required")).isEqualTo(List.of("name"));
        }

        @Test
        @DisplayName("getDataSourceSchema should return valid schema")
        void shouldReturnDataSourceSchema() {
            Map<String, Object> schema = ToolSchemaGenerator.getDataSourceSchema();

            assertThat(schema.get("type")).isEqualTo("object");

            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            assertThat(properties).containsKeys("name", "description", "data");

            assertThat(schema.get("required")).isEqualTo(List.of("name", "data"));
        }
    }
}
