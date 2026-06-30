package com.apimarketplace.catalog.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for McpTool.
 *
 * McpTool represents an MCP tool discovered from an MCP server.
 */
@DisplayName("McpTool")
class McpToolTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    // ========================================================================
    // Constructor tests
    // ========================================================================

    @Nested
    @DisplayName("Constructors")
    class ConstructorTests {

        @Test
        @DisplayName("should create tool with default constructor")
        void shouldCreateToolWithDefaultConstructor() {
            McpTool tool = new McpTool();

            assertNull(tool.getName());
            assertNull(tool.getDescription());
            assertNull(tool.getInputSchema());
            assertNull(tool.getOutputSchema());
        }

        @Test
        @DisplayName("should create tool with name, description and input schema")
        void shouldCreateToolWithNameDescriptionAndInputSchema() throws Exception {
            JsonNode schema = mapper.readTree("{\"type\": \"object\"}");
            McpTool tool = new McpTool("weather", "Get weather data", schema);

            assertEquals("weather", tool.getName());
            assertEquals("Get weather data", tool.getDescription());
            assertEquals(schema, tool.getInputSchema());
            assertNull(tool.getOutputSchema());
        }
    }

    // ========================================================================
    // hasInputSchema tests
    // ========================================================================

    @Nested
    @DisplayName("hasInputSchema()")
    class HasInputSchemaTests {

        @Test
        @DisplayName("should return false when input schema is null")
        void shouldReturnFalseWhenInputSchemaIsNull() {
            McpTool tool = new McpTool("test", "Test tool", null);

            assertFalse(tool.hasInputSchema());
        }

        @Test
        @DisplayName("should return false when input schema is empty")
        void shouldReturnFalseWhenInputSchemaIsEmpty() throws Exception {
            JsonNode emptySchema = mapper.readTree("{}");
            McpTool tool = new McpTool("test", "Test tool", emptySchema);

            assertFalse(tool.hasInputSchema());
        }

        @Test
        @DisplayName("should return true when input schema has content")
        void shouldReturnTrueWhenInputSchemaHasContent() throws Exception {
            JsonNode schema = mapper.readTree("{\"type\": \"object\"}");
            McpTool tool = new McpTool("test", "Test tool", schema);

            assertTrue(tool.hasInputSchema());
        }
    }

    // ========================================================================
    // hasOutputSchema tests
    // ========================================================================

    @Nested
    @DisplayName("hasOutputSchema()")
    class HasOutputSchemaTests {

        @Test
        @DisplayName("should return false when output schema is null")
        void shouldReturnFalseWhenOutputSchemaIsNull() {
            McpTool tool = new McpTool();

            assertFalse(tool.hasOutputSchema());
        }

        @Test
        @DisplayName("should return true when output schema has content")
        void shouldReturnTrueWhenOutputSchemaHasContent() throws Exception {
            McpTool tool = new McpTool();
            JsonNode schema = mapper.readTree("{\"type\": \"string\"}");
            tool.setOutputSchema(schema);

            assertTrue(tool.hasOutputSchema());
        }
    }

    // ========================================================================
    // getInputType tests
    // ========================================================================

    @Nested
    @DisplayName("getInputType()")
    class GetInputTypeTests {

        @Test
        @DisplayName("should return 'unknown' when no input schema")
        void shouldReturnUnknownWhenNoInputSchema() {
            McpTool tool = new McpTool("test", "desc", null);

            assertEquals("unknown", tool.getInputType());
        }

        @Test
        @DisplayName("should return 'object' as default when type not specified")
        void shouldReturnObjectAsDefaultWhenTypeNotSpecified() throws Exception {
            JsonNode schema = mapper.readTree("{\"properties\": {}}");
            McpTool tool = new McpTool("test", "desc", schema);

            assertEquals("object", tool.getInputType());
        }

        @Test
        @DisplayName("should return specified type from schema")
        void shouldReturnSpecifiedTypeFromSchema() throws Exception {
            JsonNode schema = mapper.readTree("{\"type\": \"string\"}");
            McpTool tool = new McpTool("test", "desc", schema);

            assertEquals("string", tool.getInputType());
        }
    }

    // ========================================================================
    // hasRequiredParameters tests
    // ========================================================================

    @Nested
    @DisplayName("hasRequiredParameters()")
    class HasRequiredParametersTests {

        @Test
        @DisplayName("should return false when no input schema")
        void shouldReturnFalseWhenNoInputSchema() {
            McpTool tool = new McpTool("test", "desc", null);

            assertFalse(tool.hasRequiredParameters());
        }

        @Test
        @DisplayName("should return false when required is empty array")
        void shouldReturnFalseWhenRequiredIsEmptyArray() throws Exception {
            JsonNode schema = mapper.readTree("{\"required\": []}");
            McpTool tool = new McpTool("test", "desc", schema);

            assertFalse(tool.hasRequiredParameters());
        }

        @Test
        @DisplayName("should return true when required has elements")
        void shouldReturnTrueWhenRequiredHasElements() throws Exception {
            JsonNode schema = mapper.readTree("{\"required\": [\"city\"]}");
            McpTool tool = new McpTool("test", "desc", schema);

            assertTrue(tool.hasRequiredParameters());
        }
    }

    // ========================================================================
    // getRequiredParameters tests
    // ========================================================================

    @Nested
    @DisplayName("getRequiredParameters()")
    class GetRequiredParametersTests {

        @Test
        @DisplayName("should return empty array when no required parameters")
        void shouldReturnEmptyArrayWhenNoRequiredParameters() {
            McpTool tool = new McpTool("test", "desc", null);

            String[] params = tool.getRequiredParameters();

            assertEquals(0, params.length);
        }

        @Test
        @DisplayName("should return required parameters from schema")
        void shouldReturnRequiredParametersFromSchema() throws Exception {
            JsonNode schema = mapper.readTree("{\"required\": [\"city\", \"units\"]}");
            McpTool tool = new McpTool("test", "desc", schema);

            String[] params = tool.getRequiredParameters();

            assertEquals(2, params.length);
            assertEquals("city", params[0]);
            assertEquals("units", params[1]);
        }
    }

    // ========================================================================
    // getParameterDescription tests
    // ========================================================================

    @Nested
    @DisplayName("getParameterDescription()")
    class GetParameterDescriptionTests {

        @Test
        @DisplayName("should return null when no input schema")
        void shouldReturnNullWhenNoInputSchema() {
            McpTool tool = new McpTool("test", "desc", null);

            assertNull(tool.getParameterDescription("city"));
        }

        @Test
        @DisplayName("should return null when properties not defined")
        void shouldReturnNullWhenPropertiesNotDefined() throws Exception {
            JsonNode schema = mapper.readTree("{\"type\": \"object\"}");
            McpTool tool = new McpTool("test", "desc", schema);

            assertNull(tool.getParameterDescription("city"));
        }

        @Test
        @DisplayName("should return null when parameter not found")
        void shouldReturnNullWhenParameterNotFound() throws Exception {
            JsonNode schema = mapper.readTree("{\"properties\": {\"city\": {}}}");
            McpTool tool = new McpTool("test", "desc", schema);

            assertNull(tool.getParameterDescription("unknown"));
        }

        @Test
        @DisplayName("should return parameter description")
        void shouldReturnParameterDescription() throws Exception {
            JsonNode schema = mapper.readTree(
                "{\"properties\": {\"city\": {\"description\": \"The city name\"}}}"
            );
            McpTool tool = new McpTool("test", "desc", schema);

            assertEquals("The city name", tool.getParameterDescription("city"));
        }
    }

    // ========================================================================
    // getParameterType tests
    // ========================================================================

    @Nested
    @DisplayName("getParameterType()")
    class GetParameterTypeTests {

        @Test
        @DisplayName("should return 'unknown' when no input schema")
        void shouldReturnUnknownWhenNoInputSchema() {
            McpTool tool = new McpTool("test", "desc", null);

            assertEquals("unknown", tool.getParameterType("city"));
        }

        @Test
        @DisplayName("should return 'unknown' when properties not defined")
        void shouldReturnUnknownWhenPropertiesNotDefined() throws Exception {
            JsonNode schema = mapper.readTree("{\"type\": \"object\"}");
            McpTool tool = new McpTool("test", "desc", schema);

            assertEquals("unknown", tool.getParameterType("city"));
        }

        @Test
        @DisplayName("should return 'string' as default when type not specified")
        void shouldReturnStringAsDefaultWhenTypeNotSpecified() throws Exception {
            JsonNode schema = mapper.readTree("{\"properties\": {\"city\": {}}}");
            McpTool tool = new McpTool("test", "desc", schema);

            assertEquals("string", tool.getParameterType("city"));
        }

        @Test
        @DisplayName("should return parameter type from schema")
        void shouldReturnParameterTypeFromSchema() throws Exception {
            JsonNode schema = mapper.readTree(
                "{\"properties\": {\"count\": {\"type\": \"integer\"}}}"
            );
            McpTool tool = new McpTool("test", "desc", schema);

            assertEquals("integer", tool.getParameterType("count"));
        }
    }

    // ========================================================================
    // isParameterRequired tests
    // ========================================================================

    @Nested
    @DisplayName("isParameterRequired()")
    class IsParameterRequiredTests {

        @Test
        @DisplayName("should return false when no required parameters")
        void shouldReturnFalseWhenNoRequiredParameters() {
            McpTool tool = new McpTool("test", "desc", null);

            assertFalse(tool.isParameterRequired("city"));
        }

        @Test
        @DisplayName("should return false when parameter is not required")
        void shouldReturnFalseWhenParameterIsNotRequired() throws Exception {
            JsonNode schema = mapper.readTree("{\"required\": [\"city\"]}");
            McpTool tool = new McpTool("test", "desc", schema);

            assertFalse(tool.isParameterRequired("units"));
        }

        @Test
        @DisplayName("should return true when parameter is required")
        void shouldReturnTrueWhenParameterIsRequired() throws Exception {
            JsonNode schema = mapper.readTree("{\"required\": [\"city\", \"units\"]}");
            McpTool tool = new McpTool("test", "desc", schema);

            assertTrue(tool.isParameterRequired("city"));
            assertTrue(tool.isParameterRequired("units"));
        }
    }

    // ========================================================================
    // getShortDescription tests
    // ========================================================================

    @Nested
    @DisplayName("getShortDescription()")
    class GetShortDescriptionTests {

        @Test
        @DisplayName("should return default when description is null")
        void shouldReturnDefaultWhenDescriptionIsNull() {
            McpTool tool = new McpTool("test", null, null);

            assertEquals("Outil MCP", tool.getShortDescription());
        }

        @Test
        @DisplayName("should return full description when 100 chars or less")
        void shouldReturnFullDescriptionWhenShort() {
            String desc = "Short description";
            McpTool tool = new McpTool("test", desc, null);

            assertEquals(desc, tool.getShortDescription());
        }

        @Test
        @DisplayName("should truncate description longer than 100 chars")
        void shouldTruncateDescriptionLongerThan100Chars() {
            String longDesc = "A".repeat(150);
            McpTool tool = new McpTool("test", longDesc, null);

            String result = tool.getShortDescription();

            assertEquals(103, result.length()); // 100 + "..."
            assertTrue(result.endsWith("..."));
        }

        @Test
        @DisplayName("should return exactly 100 chars description as-is")
        void shouldReturnExactly100CharsDescriptionAsIs() {
            String desc = "A".repeat(100);
            McpTool tool = new McpTool("test", desc, null);

            assertEquals(desc, tool.getShortDescription());
        }
    }

    // ========================================================================
    // getParametersSummary tests
    // ========================================================================

    @Nested
    @DisplayName("getParametersSummary()")
    class GetParametersSummaryTests {

        @Test
        @DisplayName("should return 'Aucun parametre' when no input schema")
        void shouldReturnNoParamsWhenNoInputSchema() {
            McpTool tool = new McpTool("test", "desc", null);

            assertEquals("Aucun parametre", tool.getParametersSummary());
        }

        @Test
        @DisplayName("should return 'Aucun parametre' when no properties")
        void shouldReturnNoParamsWhenNoProperties() throws Exception {
            JsonNode schema = mapper.readTree("{\"type\": \"object\"}");
            McpTool tool = new McpTool("test", "desc", schema);

            assertEquals("Aucun parametre", tool.getParametersSummary());
        }

        @Test
        @DisplayName("should return 'Aucun parametre' when properties is empty")
        void shouldReturnNoParamsWhenPropertiesEmpty() throws Exception {
            JsonNode schema = mapper.readTree("{\"properties\": {}}");
            McpTool tool = new McpTool("test", "desc", schema);

            assertEquals("Aucun parametre", tool.getParametersSummary());
        }

        @Test
        @DisplayName("should return optional count when no required params")
        void shouldReturnOptionalCountWhenNoRequiredParams() throws Exception {
            JsonNode schema = mapper.readTree(
                "{\"properties\": {\"city\": {}, \"units\": {}}}"
            );
            McpTool tool = new McpTool("test", "desc", schema);

            assertEquals("2 parametre(s) optionnel(s)", tool.getParametersSummary());
        }

        @Test
        @DisplayName("should return required count when all are required")
        void shouldReturnRequiredCountWhenAllAreRequired() throws Exception {
            JsonNode schema = mapper.readTree(
                "{\"properties\": {\"city\": {}}, \"required\": [\"city\"]}"
            );
            McpTool tool = new McpTool("test", "desc", schema);

            assertEquals("1 parametre(s) obligatoire(s)", tool.getParametersSummary());
        }

        @Test
        @DisplayName("should return mixed count when some required and some optional")
        void shouldReturnMixedCountWhenMixed() throws Exception {
            JsonNode schema = mapper.readTree(
                "{\"properties\": {\"city\": {}, \"units\": {}, \"lang\": {}}, \"required\": [\"city\"]}"
            );
            McpTool tool = new McpTool("test", "desc", schema);

            assertEquals("1 obligatoire(s), 2 optionnel(s)", tool.getParametersSummary());
        }
    }

    // ========================================================================
    // toString tests
    // ========================================================================

    @Nested
    @DisplayName("toString()")
    class ToStringTests {

        @Test
        @DisplayName("should return formatted string representation")
        void shouldReturnFormattedStringRepresentation() throws Exception {
            JsonNode schema = mapper.readTree(
                "{\"properties\": {\"city\": {}}, \"required\": [\"city\"]}"
            );
            McpTool tool = new McpTool("weather", "Get weather data", schema);

            String result = tool.toString();

            assertTrue(result.contains("McpTool"));
            assertTrue(result.contains("name='weather'"));
            assertTrue(result.contains("description='Get weather data'"));
            assertTrue(result.contains("1 parametre(s) obligatoire(s)"));
        }
    }
}
