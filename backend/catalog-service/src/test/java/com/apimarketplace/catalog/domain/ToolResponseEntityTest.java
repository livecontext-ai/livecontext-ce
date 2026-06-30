package com.apimarketplace.catalog.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ToolResponseEntity.
 *
 * ToolResponseEntity represents multi-format tool responses.
 */
@DisplayName("ToolResponseEntity")
class ToolResponseEntityTest {

    // ========================================================================
    // Constructor tests
    // ========================================================================

    @Nested
    @DisplayName("Constructors")
    class ConstructorTests {

        @Test
        @DisplayName("should create entity with default constructor and default values")
        void shouldCreateEntityWithDefaultConstructorAndDefaultValues() {
            // Act
            ToolResponseEntity entity = new ToolResponseEntity();

            // Assert
            assertNotNull(entity);
            assertNull(entity.getId());
            assertNull(entity.getToolId());
            assertNull(entity.getName());
            assertNull(entity.getDescription());
            assertNull(entity.getSchema());
            assertNull(entity.getExample());
            assertNull(entity.getExampleJsonb());
            assertNull(entity.getStructureSkeleton());
            assertNull(entity.getStatusCode());
            assertFalse(entity.getIsDefault()); // Default is false
            assertNull(entity.getResponseFormat());
            assertFalse(entity.getIsActive()); // Default is false
            assertNull(entity.getCreatedAt());
            assertNull(entity.getUpdatedAt());
            assertNull(entity.getCreatedBy());
        }

        @Test
        @DisplayName("should create entity with parameterized constructor")
        void shouldCreateEntityWithParameterizedConstructor() {
            // Arrange
            UUID toolId = UUID.randomUUID();
            String name = "Success Response";
            String description = "Returns successful data";
            String schema = "{\"type\": \"object\"}";
            String example = "{\"data\": \"test\"}";
            String exampleJsonb = "{\"data\": \"test\"}";
            ResponseFormat format = ResponseFormat.JSON;
            Integer statusCode = 200;
            Boolean isDefault = true;
            Boolean isActive = true;

            // Act
            ToolResponseEntity entity = new ToolResponseEntity(
                toolId, name, description, schema, example, exampleJsonb, format, statusCode, isDefault, isActive
            );

            // Assert
            assertEquals(toolId, entity.getToolId());
            assertEquals(name, entity.getName());
            assertEquals(description, entity.getDescription());
            assertEquals(schema, entity.getSchema());
            assertEquals(example, entity.getExample());
            assertEquals(exampleJsonb, entity.getExampleJsonb());
            assertEquals(format, entity.getResponseFormat());
            assertEquals(statusCode, entity.getStatusCode());
            assertEquals(isDefault, entity.getIsDefault());
            assertEquals(isActive, entity.getIsActive());
        }
    }

    // ========================================================================
    // Getter/Setter tests
    // ========================================================================

    @Nested
    @DisplayName("Getters and Setters")
    class GetterSetterTests {

        @Test
        @DisplayName("should set and get all fields correctly")
        void shouldSetAndGetAllFieldsCorrectly() {
            // Arrange
            ToolResponseEntity entity = new ToolResponseEntity();
            UUID id = UUID.randomUUID();
            UUID toolId = UUID.randomUUID();
            String name = "Error Response";
            String description = "Error description";
            String schema = "{\"type\": \"string\"}";
            String example = "Error message";
            String exampleJsonb = "{\"error\": \"message\"}";
            String structureSkeleton = "{\"_t\": \"obj\"}";
            ResponseFormat format = ResponseFormat.TEXT;
            Integer statusCode = 500;
            Boolean isDefault = false;
            Boolean isActive = true;
            LocalDateTime createdAt = LocalDateTime.now();
            LocalDateTime updatedAt = LocalDateTime.now();
            String createdBy = "user123";

            // Act
            entity.setId(id);
            entity.setToolId(toolId);
            entity.setName(name);
            entity.setDescription(description);
            entity.setSchema(schema);
            entity.setExample(example);
            entity.setExampleJsonb(exampleJsonb);
            entity.setStructureSkeleton(structureSkeleton);
            entity.setResponseFormat(format);
            entity.setStatusCode(statusCode);
            entity.setIsDefault(isDefault);
            entity.setIsActive(isActive);
            entity.setCreatedAt(createdAt);
            entity.setUpdatedAt(updatedAt);
            entity.setCreatedBy(createdBy);

            // Assert
            assertEquals(id, entity.getId());
            assertEquals(toolId, entity.getToolId());
            assertEquals(name, entity.getName());
            assertEquals(description, entity.getDescription());
            assertEquals(schema, entity.getSchema());
            assertEquals(example, entity.getExample());
            assertEquals(exampleJsonb, entity.getExampleJsonb());
            assertEquals(structureSkeleton, entity.getStructureSkeleton());
            assertEquals(format, entity.getResponseFormat());
            assertEquals(statusCode, entity.getStatusCode());
            assertEquals(isDefault, entity.getIsDefault());
            assertEquals(isActive, entity.getIsActive());
            assertEquals(createdAt, entity.getCreatedAt());
            assertEquals(updatedAt, entity.getUpdatedAt());
            assertEquals(createdBy, entity.getCreatedBy());
        }
    }

    // ========================================================================
    // toString tests
    // ========================================================================

    @Nested
    @DisplayName("toString()")
    class ToStringTests {

        @Test
        @DisplayName("should return string with all key fields")
        void shouldReturnStringWithAllKeyFields() {
            // Arrange
            ToolResponseEntity entity = new ToolResponseEntity();
            UUID toolId = UUID.randomUUID();
            entity.setToolId(toolId);
            entity.setName("Test Response");
            entity.setDescription("Test description");
            entity.setResponseFormat(ResponseFormat.JSON);
            entity.setStatusCode(200);
            entity.setIsDefault(true);
            entity.setIsActive(true);

            // Act
            String result = entity.toString();

            // Assert
            assertNotNull(result);
            assertTrue(result.contains("ToolResponseEntity"));
            assertTrue(result.contains("toolId=" + toolId));
            assertTrue(result.contains("name='Test Response'"));
            assertTrue(result.contains("responseFormat=JSON"));
            assertTrue(result.contains("statusCode=200"));
            assertTrue(result.contains("isDefault=true"));
            assertTrue(result.contains("isActive=true"));
        }
    }

    // ========================================================================
    // ResponseFormat tests
    // ========================================================================

    @Nested
    @DisplayName("ResponseFormat handling")
    class ResponseFormatTests {

        @Test
        @DisplayName("should handle all ResponseFormat values")
        void shouldHandleAllResponseFormatValues() {
            // Arrange
            ToolResponseEntity entity = new ToolResponseEntity();

            // Test all formats
            for (ResponseFormat format : ResponseFormat.values()) {
                entity.setResponseFormat(format);
                assertEquals(format, entity.getResponseFormat());
            }
        }
    }
}
