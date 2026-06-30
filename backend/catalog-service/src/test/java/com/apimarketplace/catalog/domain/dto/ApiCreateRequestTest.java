package com.apimarketplace.catalog.domain.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ApiCreateRequest record.
 *
 * ApiCreateRequest is a DTO for creating an API with tools and parameters.
 */
@DisplayName("ApiCreateRequest")
class ApiCreateRequestTest {

    // ========================================================================
    // ApiCreateRequest tests
    // ========================================================================

    @Nested
    @DisplayName("ApiCreateRequest record")
    class ApiCreateRequestRecordTests {

        @Test
        @DisplayName("should create request with all fields")
        void shouldCreateRequestWithAllFields() {
            // Arrange
            String apiName = "Weather API";
            String description = "Get weather data";
            String baseUrl = "https://api.weather.com";
            UUID categoryId = UUID.randomUUID();
            UUID subcategoryId = UUID.randomUUID();
            List<ApiCreateRequest.ToolCreateRequest> tools = List.of();

            // Act
            ApiCreateRequest request = new ApiCreateRequest(
                apiName, description, baseUrl, categoryId, subcategoryId, tools
            );

            // Assert
            assertEquals(apiName, request.apiName());
            assertEquals(description, request.description());
            assertEquals(baseUrl, request.baseUrl());
            assertEquals(categoryId, request.categoryId());
            assertEquals(subcategoryId, request.subcategoryId());
            assertNotNull(request.tools());
            assertTrue(request.tools().isEmpty());
        }

        @Test
        @DisplayName("should create request with null description")
        void shouldCreateRequestWithNullDescription() {
            // Arrange
            UUID categoryId = UUID.randomUUID();
            UUID subcategoryId = UUID.randomUUID();

            // Act
            ApiCreateRequest request = new ApiCreateRequest(
                "Test API", null, "https://test.com", categoryId, subcategoryId, null
            );

            // Assert
            assertNull(request.description());
            assertNull(request.tools());
        }

        @Test
        @DisplayName("should implement equals correctly for identical records")
        void shouldImplementEqualsCorrectlyForIdenticalRecords() {
            // Arrange
            UUID categoryId = UUID.randomUUID();
            UUID subcategoryId = UUID.randomUUID();

            ApiCreateRequest request1 = new ApiCreateRequest(
                "API", "desc", "https://test.com", categoryId, subcategoryId, List.of()
            );
            ApiCreateRequest request2 = new ApiCreateRequest(
                "API", "desc", "https://test.com", categoryId, subcategoryId, List.of()
            );

            // Assert
            assertEquals(request1, request2);
            assertEquals(request1.hashCode(), request2.hashCode());
        }

        @Test
        @DisplayName("should implement equals correctly for different records")
        void shouldImplementEqualsCorrectlyForDifferentRecords() {
            // Arrange
            UUID categoryId = UUID.randomUUID();
            UUID subcategoryId = UUID.randomUUID();

            ApiCreateRequest request1 = new ApiCreateRequest(
                "API 1", "desc", "https://test.com", categoryId, subcategoryId, List.of()
            );
            ApiCreateRequest request2 = new ApiCreateRequest(
                "API 2", "desc", "https://test.com", categoryId, subcategoryId, List.of()
            );

            // Assert
            assertNotEquals(request1, request2);
        }

        @Test
        @DisplayName("should generate meaningful toString")
        void shouldGenerateMeaningfulToString() {
            // Arrange
            UUID categoryId = UUID.randomUUID();
            UUID subcategoryId = UUID.randomUUID();

            ApiCreateRequest request = new ApiCreateRequest(
                "Test API", "Description", "https://test.com", categoryId, subcategoryId, null
            );

            // Act
            String result = request.toString();

            // Assert
            assertNotNull(result);
            assertTrue(result.contains("Test API"));
            assertTrue(result.contains("https://test.com"));
        }
    }

    // ========================================================================
    // ToolCreateRequest tests
    // ========================================================================

    @Nested
    @DisplayName("ToolCreateRequest record")
    class ToolCreateRequestRecordTests {

        @Test
        @DisplayName("should create tool request with all fields")
        void shouldCreateToolRequestWithAllFields() {
            // Arrange
            String name = "getCurrentWeather";
            String description = "Get current weather data";
            String endpoint = "/weather/current";
            String method = "GET";
            List<ApiCreateRequest.ParameterCreateRequest> parameters = List.of();

            // Act
            ApiCreateRequest.ToolCreateRequest tool = new ApiCreateRequest.ToolCreateRequest(
                name, description, endpoint, method, parameters
            );

            // Assert
            assertEquals(name, tool.name());
            assertEquals(description, tool.description());
            assertEquals(endpoint, tool.endpoint());
            assertEquals(method, tool.method());
            assertNotNull(tool.parameters());
            assertTrue(tool.parameters().isEmpty());
        }

        @Test
        @DisplayName("should create tool request with null parameters")
        void shouldCreateToolRequestWithNullParameters() {
            // Act
            ApiCreateRequest.ToolCreateRequest tool = new ApiCreateRequest.ToolCreateRequest(
                "tool", "desc", "/endpoint", "POST", null
            );

            // Assert
            assertNull(tool.parameters());
        }

        @Test
        @DisplayName("should implement equals correctly")
        void shouldImplementEqualsCorrectly() {
            // Arrange
            ApiCreateRequest.ToolCreateRequest tool1 = new ApiCreateRequest.ToolCreateRequest(
                "tool", "desc", "/endpoint", "GET", List.of()
            );
            ApiCreateRequest.ToolCreateRequest tool2 = new ApiCreateRequest.ToolCreateRequest(
                "tool", "desc", "/endpoint", "GET", List.of()
            );

            // Assert
            assertEquals(tool1, tool2);
            assertEquals(tool1.hashCode(), tool2.hashCode());
        }
    }

    // ========================================================================
    // ParameterCreateRequest tests
    // ========================================================================

    @Nested
    @DisplayName("ParameterCreateRequest record")
    class ParameterCreateRequestRecordTests {

        @Test
        @DisplayName("should create parameter request with all fields")
        void shouldCreateParameterRequestWithAllFields() {
            // Arrange
            String name = "city";
            String type = "string";
            String description = "City name";
            Boolean required = true;
            String defaultValue = "Paris";

            // Act
            ApiCreateRequest.ParameterCreateRequest param = new ApiCreateRequest.ParameterCreateRequest(
                name, type, description, required, defaultValue
            );

            // Assert
            assertEquals(name, param.name());
            assertEquals(type, param.type());
            assertEquals(description, param.description());
            assertEquals(required, param.required());
            assertEquals(defaultValue, param.defaultValue());
        }

        @Test
        @DisplayName("should create parameter with optional fields null")
        void shouldCreateParameterWithOptionalFieldsNull() {
            // Act
            ApiCreateRequest.ParameterCreateRequest param = new ApiCreateRequest.ParameterCreateRequest(
                "param", "string", null, true, null
            );

            // Assert
            assertNull(param.description());
            assertNull(param.defaultValue());
        }

        @Test
        @DisplayName("should implement equals correctly")
        void shouldImplementEqualsCorrectly() {
            // Arrange
            ApiCreateRequest.ParameterCreateRequest param1 = new ApiCreateRequest.ParameterCreateRequest(
                "name", "string", "desc", true, "default"
            );
            ApiCreateRequest.ParameterCreateRequest param2 = new ApiCreateRequest.ParameterCreateRequest(
                "name", "string", "desc", true, "default"
            );

            // Assert
            assertEquals(param1, param2);
            assertEquals(param1.hashCode(), param2.hashCode());
        }
    }

    // ========================================================================
    // Nested structure tests
    // ========================================================================

    @Nested
    @DisplayName("Nested structure")
    class NestedStructureTests {

        @Test
        @DisplayName("should create complete API with tools and parameters")
        void shouldCreateCompleteApiWithToolsAndParameters() {
            // Arrange
            UUID categoryId = UUID.randomUUID();
            UUID subcategoryId = UUID.randomUUID();

            ApiCreateRequest.ParameterCreateRequest param = new ApiCreateRequest.ParameterCreateRequest(
                "location", "string", "Location name", true, null
            );

            ApiCreateRequest.ToolCreateRequest tool = new ApiCreateRequest.ToolCreateRequest(
                "getWeather", "Get weather data", "/weather", "GET", List.of(param)
            );

            // Act
            ApiCreateRequest request = new ApiCreateRequest(
                "Weather API", "Weather service API", "https://weather.api.com",
                categoryId, subcategoryId, List.of(tool)
            );

            // Assert
            assertEquals(1, request.tools().size());
            assertEquals("getWeather", request.tools().get(0).name());
            assertEquals(1, request.tools().get(0).parameters().size());
            assertEquals("location", request.tools().get(0).parameters().get(0).name());
        }
    }
}
