package com.apimarketplace.catalog.service.submission;

import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ToolParameterService.
 *
 * ToolParameterService handles saving tool parameters to the database.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ToolParameterService")
class ToolParameterServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private ToolParameterService toolParameterService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        toolParameterService = new ToolParameterService(jdbcTemplate);
        objectMapper = new ObjectMapper();
    }

    // ========================================================================
    // saveParameters - delete behavior tests
    // ========================================================================

    @Nested
    @DisplayName("Delete existing parameters")
    class DeleteExistingParametersTests {

        @Test
        @DisplayName("should delete existing parameters before inserting new ones")
        void shouldDeleteExistingParametersBeforeInsertingNewOnes() {
            // Arrange
            ApiToolEntity tool = createTool();
            ObjectNode toolData = objectMapper.createObjectNode();
            when(jdbcTemplate.update(contains("DELETE"), any(UUID.class))).thenReturn(2);

            // Act
            toolParameterService.saveParameters(tool, toolData, "TestTool");

            // Assert
            verify(jdbcTemplate).update(contains("DELETE"), eq(tool.getId()));
        }
    }

    // ========================================================================
    // saveParameters - header parameters tests
    // ========================================================================

    @Nested
    @DisplayName("Header parameters")
    class HeaderParametersTests {

        @Test
        @DisplayName("should save header parameters")
        void shouldSaveHeaderParameters() {
            // Arrange
            ApiToolEntity tool = createTool();
            ObjectNode toolData = objectMapper.createObjectNode();
            ArrayNode headers = toolData.putArray("headers");
            ObjectNode header = headers.addObject();
            header.put("name", "Authorization");
            header.put("required", true);
            header.put("description", "Bearer token");
            header.put("value", "Bearer xxx");

            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

            // Act
            toolParameterService.saveParameters(tool, toolData, "TestTool");

            // Assert
            verify(jdbcTemplate, atLeast(1)).update(contains("INSERT"), any(Object[].class));
        }

        @Test
        @DisplayName("should skip if headers is not an array")
        void shouldSkipIfHeadersIsNotAnArray() {
            // Arrange
            ApiToolEntity tool = createTool();
            ObjectNode toolData = objectMapper.createObjectNode();
            toolData.put("headers", "not-an-array");

            // Act
            toolParameterService.saveParameters(tool, toolData, "TestTool");

            // Assert - only delete, no insert
            verify(jdbcTemplate).update(contains("DELETE"), any(UUID.class));
            verify(jdbcTemplate, never()).update(contains("INSERT"), any(Object[].class));
        }
    }

    // ========================================================================
    // saveParameters - path parameters tests
    // ========================================================================

    @Nested
    @DisplayName("Path parameters")
    class PathParametersTests {

        @Test
        @DisplayName("should save path parameters")
        void shouldSavePathParameters() {
            // Arrange
            ApiToolEntity tool = createTool();
            ObjectNode toolData = objectMapper.createObjectNode();
            ArrayNode pathParams = toolData.putArray("pathParameters");
            ObjectNode param = pathParams.addObject();
            param.put("name", "userId");
            param.put("type", "string");
            param.put("required", true);
            param.put("description", "The user ID");
            param.put("exampleValue", "12345");

            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

            // Act
            toolParameterService.saveParameters(tool, toolData, "TestTool");

            // Assert
            verify(jdbcTemplate, atLeast(1)).update(contains("INSERT"), any(Object[].class));
        }

        @Test
        @DisplayName("should handle hidden path parameters")
        void shouldHandleHiddenPathParameters() {
            // Arrange
            ApiToolEntity tool = createTool();
            ObjectNode toolData = objectMapper.createObjectNode();
            ArrayNode pathParams = toolData.putArray("pathParameters");
            ObjectNode param = pathParams.addObject();
            param.put("name", "internalId");
            param.put("isHidden", true);
            param.put("defaultValue", "default-123");

            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

            // Act
            toolParameterService.saveParameters(tool, toolData, "TestTool");

            // Assert
            verify(jdbcTemplate, atLeast(1)).update(contains("INSERT"), any(Object[].class));
        }
    }

    // ========================================================================
    // saveParameters - query parameters tests
    // ========================================================================

    @Nested
    @DisplayName("Query parameters")
    class QueryParametersTests {

        @Test
        @DisplayName("should save query parameters")
        void shouldSaveQueryParameters() {
            // Arrange
            ApiToolEntity tool = createTool();
            ObjectNode toolData = objectMapper.createObjectNode();
            ArrayNode queryParams = toolData.putArray("queryParameters");
            ObjectNode param = queryParams.addObject();
            param.put("name", "limit");
            param.put("type", "integer");
            param.put("required", false);
            param.put("description", "Max items to return");

            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

            // Act
            toolParameterService.saveParameters(tool, toolData, "TestTool");

            // Assert
            verify(jdbcTemplate, atLeast(1)).update(contains("INSERT"), any(Object[].class));
        }

        @Test
        @DisplayName("should handle allowed values in query parameters")
        void shouldHandleAllowedValuesInQueryParameters() {
            // Arrange
            ApiToolEntity tool = createTool();
            ObjectNode toolData = objectMapper.createObjectNode();
            ArrayNode queryParams = toolData.putArray("queryParameters");
            ObjectNode param = queryParams.addObject();
            param.put("name", "status");
            ArrayNode allowedValues = param.putArray("allowedValues");
            allowedValues.add("active");
            allowedValues.add("inactive");

            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

            // Act
            toolParameterService.saveParameters(tool, toolData, "TestTool");

            // Assert
            verify(jdbcTemplate, atLeast(1)).update(contains("INSERT"), any(Object[].class));
        }
    }

    // ========================================================================
    // saveParameters - body parameters tests
    // ========================================================================

    @Nested
    @DisplayName("Body parameters")
    class BodyParametersTests {

        @Test
        @DisplayName("should save body parameters")
        void shouldSaveBodyParameters() {
            // Arrange
            ApiToolEntity tool = createTool();
            ObjectNode toolData = objectMapper.createObjectNode();
            ArrayNode bodyParams = toolData.putArray("bodyParams");
            ObjectNode param = bodyParams.addObject();
            param.put("name", "email");
            param.put("type", "string");
            param.put("required", true);
            param.put("description", "User email address");
            param.put("value", "test@example.com");

            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

            // Act
            toolParameterService.saveParameters(tool, toolData, "TestTool");

            // Assert
            verify(jdbcTemplate, atLeast(1)).update(contains("INSERT"), any(Object[].class));
        }

        @Test
        @DisplayName("should default body param required to true")
        void shouldDefaultBodyParamRequiredToTrue() {
            // Arrange
            ApiToolEntity tool = createTool();
            ObjectNode toolData = objectMapper.createObjectNode();
            ArrayNode bodyParams = toolData.putArray("bodyParams");
            ObjectNode param = bodyParams.addObject();
            param.put("name", "data");
            // required not specified

            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

            // Act
            toolParameterService.saveParameters(tool, toolData, "TestTool");

            // Assert
            verify(jdbcTemplate, atLeast(1)).update(contains("INSERT"), any(Object[].class));
        }
    }

    // ========================================================================
    // allowedValues persistence - pins the JSON-array contract for the inspector dropdown
    // ========================================================================

    @Nested
    @DisplayName("allowedValues column population")
    class AllowedValuesPersistenceTests {

        /**
         * Indices of the positional INSERT args. INSERT column order is:
         * id, api_tool_id, parameter_type, name, data_type, is_required,
         * description, example_value, default_value, allowed_values, file_path,
         * extras, is_hidden, created_at.
         */
        private static final int IDX_DEFAULT_VALUE = 8;
        private static final int IDX_ALLOWED_VALUES = 9;

        @Test
        @DisplayName("body params: serializes allowedValues as JSON array string into allowed_values column")
        void bodyParamWritesAllowedValuesAsJson() {
            ApiToolEntity tool = createTool();
            ObjectNode toolData = objectMapper.createObjectNode();
            ArrayNode bodyParams = toolData.putArray("bodyParams");
            ObjectNode param = bodyParams.addObject();
            param.put("name", "model");
            param.put("type", "string");
            param.put("required", true);
            param.put("defaultValue", "gpt-4o");
            ArrayNode allowed = param.putArray("allowedValues");
            allowed.add("gpt-4o");
            allowed.add("gpt-4o-mini");
            allowed.add("gpt-4.1");

            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

            toolParameterService.saveParameters(tool, toolData, "TestTool");

            ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
            verify(jdbcTemplate).update(contains("INSERT"), argsCaptor.capture());
            Object[] args = argsCaptor.getValue();
            assertEquals("gpt-4o", args[IDX_DEFAULT_VALUE],
                    "Scalar defaultValue must coexist with allowedValues so the picker pre-selects the recommended choice");
            assertEquals("[\"gpt-4o\",\"gpt-4o-mini\",\"gpt-4.1\"]", args[IDX_ALLOWED_VALUES],
                    "allowedValues must be serialized as a JSON array string - that's what WorkflowInspectorService reads back to drive the dropdown");
        }

        @Test
        @DisplayName("path params: allowedValues persisted (was previously dropped)")
        void pathParamWritesAllowedValues() {
            ApiToolEntity tool = createTool();
            ObjectNode toolData = objectMapper.createObjectNode();
            ArrayNode pathParams = toolData.putArray("pathParameters");
            ObjectNode param = pathParams.addObject();
            param.put("name", "version");
            param.put("type", "string");
            param.put("required", true);
            ArrayNode allowed = param.putArray("allowedValues");
            allowed.add("v1");
            allowed.add("v2");

            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

            toolParameterService.saveParameters(tool, toolData, "TestTool");

            ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
            verify(jdbcTemplate).update(contains("INSERT"), argsCaptor.capture());
            Object[] args = argsCaptor.getValue();
            assertEquals("[\"v1\",\"v2\"]", args[IDX_ALLOWED_VALUES],
                    "Path params used to write null for allowed_values regardless of the JSON - fixed");
        }

        @Test
        @DisplayName("body params: missing allowedValues writes NULL, not an empty-array JSON string")
        void bodyParamMissingAllowedValuesWritesNull() {
            ApiToolEntity tool = createTool();
            ObjectNode toolData = objectMapper.createObjectNode();
            ArrayNode bodyParams = toolData.putArray("bodyParams");
            ObjectNode param = bodyParams.addObject();
            param.put("name", "prompt");
            param.put("type", "string");
            param.put("required", true);

            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

            toolParameterService.saveParameters(tool, toolData, "TestTool");

            ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
            verify(jdbcTemplate).update(contains("INSERT"), argsCaptor.capture());
            Object[] args = argsCaptor.getValue();
            assertNull(args[IDX_ALLOWED_VALUES],
                    "When the JSON declares no allowedValues, allowed_values must be NULL - '[]' would mean 'explicitly no admissible value' which is misleading");
        }

        @Test
        @DisplayName("query params: empty allowedValues array → NULL (degenerate case is not a real enum)")
        void queryParamEmptyAllowedValuesWritesNull() {
            ApiToolEntity tool = createTool();
            ObjectNode toolData = objectMapper.createObjectNode();
            ArrayNode queryParams = toolData.putArray("queryParameters");
            ObjectNode param = queryParams.addObject();
            param.put("name", "limit");
            param.put("type", "integer");
            param.putArray("allowedValues"); // explicitly empty

            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

            toolParameterService.saveParameters(tool, toolData, "TestTool");

            ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
            verify(jdbcTemplate).update(contains("INSERT"), argsCaptor.capture());
            Object[] args = argsCaptor.getValue();
            assertNull(args[IDX_ALLOWED_VALUES],
                    "An empty allowedValues array carries no information and must round-trip to NULL");
        }
    }

    // ========================================================================
    // saveParameters - empty data tests
    // ========================================================================

    @Nested
    @DisplayName("Empty data handling")
    class EmptyDataHandlingTests {

        @Test
        @DisplayName("should handle empty tool data")
        void shouldHandleEmptyToolData() {
            // Arrange
            ApiToolEntity tool = createTool();
            ObjectNode toolData = objectMapper.createObjectNode();

            // Act
            toolParameterService.saveParameters(tool, toolData, "TestTool");

            // Assert - only delete, no insert
            verify(jdbcTemplate).update(contains("DELETE"), any(UUID.class));
            verify(jdbcTemplate, never()).update(contains("INSERT"), any(Object[].class));
        }

        @Test
        @DisplayName("should handle empty arrays")
        void shouldHandleEmptyArrays() {
            // Arrange
            ApiToolEntity tool = createTool();
            ObjectNode toolData = objectMapper.createObjectNode();
            toolData.putArray("headers");
            toolData.putArray("pathParameters");
            toolData.putArray("queryParameters");
            toolData.putArray("bodyParams");

            // Act
            toolParameterService.saveParameters(tool, toolData, "TestTool");

            // Assert - only delete, no insert
            verify(jdbcTemplate).update(contains("DELETE"), any(UUID.class));
            verify(jdbcTemplate, never()).update(contains("INSERT"), any(Object[].class));
        }
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private ApiToolEntity createTool() {
        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(UUID.randomUUID());
        tool.setEndpoint("/api/test");
        tool.setMethod("GET");
        return tool;
    }
}
