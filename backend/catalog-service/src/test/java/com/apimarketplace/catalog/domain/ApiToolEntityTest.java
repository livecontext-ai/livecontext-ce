package com.apimarketplace.catalog.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ApiToolEntity class.
 *
 * ApiToolEntity represents an API tool (endpoint) stored in the database.
 * Tests cover:
 * - Constructor behavior and default values
 * - Getters and setters
 * - Enum definitions (HttpMethod, ToolStatus, TestStatus)
 * - Utility methods
 */
@DisplayName("ApiToolEntity Tests")
class ApiToolEntityTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // CONSTRUCTOR TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should create entity with no-arg constructor")
        void shouldCreateEntityWithNoArgConstructor() {
            // Act
            ApiToolEntity entity = new ApiToolEntity();

            // Assert - check default values
            assertNull(entity.getId());
            assertNull(entity.getApiId());
            assertEquals("HTTP", entity.getProtocol());
            assertFalse(entity.getIsActive());
            assertEquals("1.0.0", entity.getVersion());
        }

        @Test
        @DisplayName("Should create entity with parameterized constructor")
        void shouldCreateEntityWithParameterizedConstructor() {
            // Arrange
            UUID id = UUID.randomUUID();
            UUID apiId = UUID.randomUUID();
            Long createdAt = System.currentTimeMillis();
            Long updatedAt = System.currentTimeMillis();

            // Act
            ApiToolEntity entity = new ApiToolEntity(
                id,
                apiId,
                "Get weather data",
                "tool-name-123",
                "GET",
                "/weather",
                "ACTIVE",
                "SUCCESS",
                true,
                createdAt,
                updatedAt
            );

            // Assert
            assertEquals(id, entity.getId());
            assertEquals(apiId, entity.getApiId());
            assertEquals("Get weather data", entity.getDescription());
            assertEquals("tool-name-123", entity.getToolNameId());
            assertEquals("GET", entity.getMethod());
            assertEquals("/weather", entity.getEndpoint());
            assertEquals("ACTIVE", entity.getStatus());
            assertEquals("SUCCESS", entity.getTestStatus());
            assertTrue(entity.getIsActive());
            assertEquals(createdAt, entity.getCreatedAt());
            assertEquals(updatedAt, entity.getUpdatedAt());
        }

        @Test
        @DisplayName("Should allow null values in parameterized constructor")
        void shouldAllowNullValuesInParameterizedConstructor() {
            // Act
            ApiToolEntity entity = new ApiToolEntity(
                null, null, null, null, null, null, null, null, null, null, null
            );

            // Assert
            assertNull(entity.getId());
            assertNull(entity.getApiId());
            assertNull(entity.getIsActive());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DEFAULT VALUES TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Default Values Tests")
    class DefaultValuesTests {

        @Test
        @DisplayName("Should have protocol default to HTTP")
        void shouldHaveProtocolDefaultToHttp() {
            // Act
            ApiToolEntity entity = new ApiToolEntity();

            // Assert
            assertEquals("HTTP", entity.getProtocol());
        }

        @Test
        @DisplayName("Should have isActive default to false")
        void shouldHaveIsActiveDefaultToFalse() {
            // Act
            ApiToolEntity entity = new ApiToolEntity();

            // Assert
            assertFalse(entity.getIsActive());
        }

        @Test
        @DisplayName("Should have version default to 1.0.0")
        void shouldHaveVersionDefaultTo100() {
            // Act
            ApiToolEntity entity = new ApiToolEntity();

            // Assert
            assertEquals("1.0.0", entity.getVersion());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GETTERS AND SETTERS TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Getters and Setters Tests")
    class GettersSettersTests {

        @Test
        @DisplayName("Should set and get id")
        void shouldSetAndGetId() {
            // Arrange
            ApiToolEntity entity = new ApiToolEntity();
            UUID id = UUID.randomUUID();

            // Act
            entity.setId(id);

            // Assert
            assertEquals(id, entity.getId());
        }

        @Test
        @DisplayName("Should set and get apiId")
        void shouldSetAndGetApiId() {
            // Arrange
            ApiToolEntity entity = new ApiToolEntity();
            UUID apiId = UUID.randomUUID();

            // Act
            entity.setApiId(apiId);

            // Assert
            assertEquals(apiId, entity.getApiId());
        }

        @Test
        @DisplayName("Should set and get toolSlug")
        void shouldSetAndGetToolSlug() {
            // Arrange
            ApiToolEntity entity = new ApiToolEntity();

            // Act
            entity.setToolSlug("get-weather");

            // Assert
            assertEquals("get-weather", entity.getToolSlug());
        }

        @Test
        @DisplayName("Should set and get description")
        void shouldSetAndGetDescription() {
            // Arrange
            ApiToolEntity entity = new ApiToolEntity();

            // Act
            entity.setDescription("Retrieves weather information");

            // Assert
            assertEquals("Retrieves weather information", entity.getDescription());
        }

        @Test
        @DisplayName("Should set and get toolNameId")
        void shouldSetAndGetToolNameId() {
            // Arrange
            ApiToolEntity entity = new ApiToolEntity();

            // Act
            entity.setToolNameId("tool-123");

            // Assert
            assertEquals("tool-123", entity.getToolNameId());
        }

        @Test
        @DisplayName("Should set and get method")
        void shouldSetAndGetMethod() {
            // Arrange
            ApiToolEntity entity = new ApiToolEntity();

            // Act
            entity.setMethod("POST");

            // Assert
            assertEquals("POST", entity.getMethod());
        }

        @Test
        @DisplayName("Should set and get endpoint")
        void shouldSetAndGetEndpoint() {
            // Arrange
            ApiToolEntity entity = new ApiToolEntity();

            // Act
            entity.setEndpoint("/api/v1/weather");

            // Assert
            assertEquals("/api/v1/weather", entity.getEndpoint());
        }

        @Test
        @DisplayName("Should set and get protocol")
        void shouldSetAndGetProtocol() {
            // Arrange
            ApiToolEntity entity = new ApiToolEntity();

            // Act
            entity.setProtocol("HTTPS");

            // Assert
            assertEquals("HTTPS", entity.getProtocol());
        }

        @Test
        @DisplayName("Should set and get defaultHeaders")
        void shouldSetAndGetDefaultHeaders() {
            // Arrange
            ApiToolEntity entity = new ApiToolEntity();
            String headers = "{\"Content-Type\": \"application/json\"}";

            // Act
            entity.setDefaultHeaders(headers);

            // Assert
            assertEquals(headers, entity.getDefaultHeaders());
        }

        @Test
        @DisplayName("Should set and get runtimeMetadata")
        void shouldSetAndGetRuntimeMetadata() {
            // Arrange
            ApiToolEntity entity = new ApiToolEntity();
            String metadata = "{\"timeout\": 30}";

            // Act
            entity.setRuntimeMetadata(metadata);

            // Assert
            assertEquals(metadata, entity.getRuntimeMetadata());
        }

        @Test
        @DisplayName("Should set and get status fields")
        void shouldSetAndGetStatusFields() {
            // Arrange
            ApiToolEntity entity = new ApiToolEntity();

            // Act
            entity.setStatus("ACTIVE");
            entity.setTestStatus("SUCCESS");

            // Assert
            assertEquals("ACTIVE", entity.getStatus());
            assertEquals("SUCCESS", entity.getTestStatus());
        }

        @Test
        @DisplayName("Should set and get version")
        void shouldSetAndGetVersion() {
            // Arrange
            ApiToolEntity entity = new ApiToolEntity();

            // Act
            entity.setVersion("2.0.0");

            // Assert
            assertEquals("2.0.0", entity.getVersion());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ENUM TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Enum Tests")
    class EnumTests {

        @Test
        @DisplayName("HttpMethod enum should have all expected values")
        void httpMethodEnumShouldHaveAllExpectedValues() {
            // Assert
            assertEquals(5, ApiToolEntity.HttpMethod.values().length);
            assertNotNull(ApiToolEntity.HttpMethod.GET);
            assertNotNull(ApiToolEntity.HttpMethod.POST);
            assertNotNull(ApiToolEntity.HttpMethod.PUT);
            assertNotNull(ApiToolEntity.HttpMethod.DELETE);
            assertNotNull(ApiToolEntity.HttpMethod.PATCH);
        }

        @Test
        @DisplayName("ToolStatus enum should have all expected values")
        void toolStatusEnumShouldHaveAllExpectedValues() {
            // Assert
            assertEquals(4, ApiToolEntity.ToolStatus.values().length);
            assertNotNull(ApiToolEntity.ToolStatus.DRAFT);
            assertNotNull(ApiToolEntity.ToolStatus.ACTIVE);
            assertNotNull(ApiToolEntity.ToolStatus.INACTIVE);
            assertNotNull(ApiToolEntity.ToolStatus.DEPRECATED);
        }

        @Test
        @DisplayName("TestStatus enum should have all expected values")
        void testStatusEnumShouldHaveAllExpectedValues() {
            // Assert
            assertEquals(4, ApiToolEntity.TestStatus.values().length);
            assertNotNull(ApiToolEntity.TestStatus.PENDING);
            assertNotNull(ApiToolEntity.TestStatus.SUCCESS);
            assertNotNull(ApiToolEntity.TestStatus.ERROR);
            assertNotNull(ApiToolEntity.TestStatus.TIMEOUT);
        }

        @Test
        @DisplayName("Enum valueOf should work correctly")
        void enumValueOfShouldWork() {
            // Assert
            assertEquals(ApiToolEntity.HttpMethod.GET, ApiToolEntity.HttpMethod.valueOf("GET"));
            assertEquals(ApiToolEntity.ToolStatus.ACTIVE, ApiToolEntity.ToolStatus.valueOf("ACTIVE"));
            assertEquals(ApiToolEntity.TestStatus.SUCCESS, ApiToolEntity.TestStatus.valueOf("SUCCESS"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UTILITY METHODS TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Utility Methods Tests")
    class UtilityMethodsTests {

        @Test
        @DisplayName("getFullEndpoint should return endpoint")
        void getFullEndpointShouldReturnEndpoint() {
            // Arrange
            ApiToolEntity entity = new ApiToolEntity();
            entity.setEndpoint("/api/v1/users");

            // Act
            String fullEndpoint = entity.getFullEndpoint();

            // Assert
            assertEquals("/api/v1/users", fullEndpoint);
        }

        @Test
        @DisplayName("getFullEndpoint should return null when endpoint is null")
        void getFullEndpointShouldReturnNullWhenEndpointIsNull() {
            // Arrange
            ApiToolEntity entity = new ApiToolEntity();

            // Act
            String fullEndpoint = entity.getFullEndpoint();

            // Assert
            assertNull(fullEndpoint);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NULL HANDLING TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Null Handling Tests")
    class NullHandlingTests {

        @Test
        @DisplayName("Should handle null values in setters")
        void shouldHandleNullValuesInSetters() {
            // Arrange
            ApiToolEntity entity = new ApiToolEntity();

            // Act
            entity.setToolSlug(null);
            entity.setMethod(null);
            entity.setEndpoint(null);
            entity.setProtocol(null);
            entity.setIsActive(null);

            // Assert
            assertNull(entity.getToolSlug());
            assertNull(entity.getMethod());
            assertNull(entity.getEndpoint());
            assertNull(entity.getProtocol());
            assertNull(entity.getIsActive());
        }
    }
}
