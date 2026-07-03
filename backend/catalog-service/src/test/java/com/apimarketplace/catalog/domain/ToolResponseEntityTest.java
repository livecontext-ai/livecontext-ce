package com.apimarketplace.catalog.domain;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ToolResponseEntity.
 *
 * ToolResponseEntity represents multi-format tool responses.
 */
@DisplayName("ToolResponseEntity")
class ToolResponseEntityTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    // ========================================================================
    // Bean Validation tests
    // ========================================================================

    @Nested
    @DisplayName("Bean Validation")
    class BeanValidationTests {

        private ToolResponseEntity newJsonResponse() {
            ToolResponseEntity entity = new ToolResponseEntity();
            entity.setToolId(UUID.randomUUID());
            entity.setResponseFormat(ResponseFormat.JSON);
            entity.setIsDefault(true);
            return entity;
        }

        @Test
        @DisplayName("a JSON response stores content in example_jsonb with example NULL and still validates")
        void jsonResponseWithNullExampleValidates() {
            // Regression: JSON examples are persisted in example_jsonb while the
            // 'example' text column stays NULL (the state of every catalog row). A
            // @NotBlank on 'example' made every managed-entity flush (e.g. when
            // setOtherResponsesAsNonDefault re-saves the previous default response)
            // throw ConstraintViolationException -> 500 on POST /api/tool-responses.
            ToolResponseEntity entity = newJsonResponse();
            entity.setExample(null);
            entity.setExampleJsonb("{\"state\":\"success\"}");

            Set<ConstraintViolation<ToolResponseEntity>> violations = validator.validate(entity);

            assertTrue(violations.stream().noneMatch(v -> "example".equals(v.getPropertyPath().toString())),
                    "a NULL example must not be a validation error - JSON content lives in example_jsonb");
        }

        @Test
        @DisplayName("re-saving the previous default response with a NULL example passes validation (the prod 500 path)")
        void demotingPreviousDefaultWithNullExampleValidates() {
            // Reproduces setOtherResponsesAsNonDefault: the previously-stored default
            // response (a JSON response, so example is NULL, content in example_jsonb)
            // is loaded as a MANAGED entity, its is_default flipped, and flushed. That
            // flush ran Bean Validation on the NULL example and threw
            // ConstraintViolationException -> HTTP 500 on POST /api/tool-responses.
            ToolResponseEntity previousDefault = newJsonResponse();
            previousDefault.setId(UUID.randomUUID());
            previousDefault.setName("default_response");
            previousDefault.setExample(null);
            previousDefault.setExampleJsonb("{\"state\":\"success\"}");
            previousDefault.setIsActive(true);

            // The exact mutation applied before the flush that used to fail.
            previousDefault.setIsDefault(false);
            previousDefault.setUpdatedAt(LocalDateTime.now());

            Set<ConstraintViolation<ToolResponseEntity>> violations = validator.validate(previousDefault);

            assertTrue(violations.isEmpty(),
                    "demoting a JSON default response (example NULL) must not raise any violation: " + violations);
        }

        @Test
        @DisplayName("still enforces the mandatory @NotNull fields (toolId, format, isDefault, isActive)")
        void mandatoryFieldsStillEnforced() {
            ToolResponseEntity entity = new ToolResponseEntity();
            entity.setToolId(null);
            entity.setResponseFormat(null);
            entity.setIsDefault(null);
            entity.setIsActive(null);

            Set<ConstraintViolation<ToolResponseEntity>> violations = validator.validate(entity);

            assertTrue(violations.stream().anyMatch(v -> "toolId".equals(v.getPropertyPath().toString())),
                    "toolId is @NotNull and must still be enforced");
            assertTrue(violations.stream().anyMatch(v -> "responseFormat".equals(v.getPropertyPath().toString())),
                    "responseFormat is @NotNull and must still be enforced");
            assertTrue(violations.stream().anyMatch(v -> "isDefault".equals(v.getPropertyPath().toString())),
                    "isDefault is @NotNull and must still be enforced");
            assertTrue(violations.stream().anyMatch(v -> "isActive".equals(v.getPropertyPath().toString())),
                    "isActive is @NotNull and must still be enforced");
        }
    }

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
