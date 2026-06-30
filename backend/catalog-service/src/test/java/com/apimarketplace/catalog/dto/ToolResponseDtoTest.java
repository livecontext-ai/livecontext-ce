package com.apimarketplace.catalog.dto;

import com.apimarketplace.catalog.domain.ResponseFormat;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ToolResponseDto class.
 *
 * ToolResponseDto represents tool response configuration.
 */
@DisplayName("ToolResponseDto")
class ToolResponseDtoTest {

    private static Validator validator;
    private ToolResponseDto dto;

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @BeforeEach
    void setUp() {
        dto = new ToolResponseDto();
    }

    // ========================================================================
    // Constructor tests
    // ========================================================================

    @Nested
    @DisplayName("Constructors")
    class ConstructorTests {

        @Test
        @DisplayName("should create empty DTO with default constructor")
        void shouldCreateEmptyDtoWithDefaultConstructor() {
            ToolResponseDto newDto = new ToolResponseDto();

            assertNotNull(newDto);
            assertNull(newDto.getId());
            assertNull(newDto.getName());
            assertEquals(200, newDto.getStatusCode());
            assertFalse(newDto.getIsDefault());
            assertFalse(newDto.getIsActive());
        }

        @Test
        @DisplayName("should create DTO with all parameters")
        void shouldCreateDtoWithAllParameters() {
            UUID id = UUID.randomUUID();
            UUID toolId = UUID.randomUUID();
            LocalDateTime now = LocalDateTime.now();

            ToolResponseDto newDto = new ToolResponseDto(
                    id, toolId, "Success Response", "Default success response",
                    "{\"type\": \"object\"}", "{\"id\": 1}", "{\"id\": 1}",
                    "{}", ResponseFormat.JSON, 200,
                    true, true, now, now, "user1"
            );

            assertEquals(id, newDto.getId());
            assertEquals(toolId, newDto.getToolId());
            assertEquals("Success Response", newDto.getName());
            assertEquals("Default success response", newDto.getDescription());
            assertEquals("{\"type\": \"object\"}", newDto.getSchema());
            assertEquals("{\"id\": 1}", newDto.getExample());
            assertEquals("{\"id\": 1}", newDto.getExampleJsonb());
            assertEquals("{}", newDto.getStructureSkeleton());
            assertEquals(ResponseFormat.JSON, newDto.getResponseFormat());
            assertEquals(200, newDto.getStatusCode());
            assertTrue(newDto.getIsDefault());
            assertTrue(newDto.getIsActive());
            assertEquals(now, newDto.getCreatedAt());
            assertEquals(now, newDto.getUpdatedAt());
            assertEquals("user1", newDto.getCreatedBy());
        }
    }

    // ========================================================================
    // Getter and setter tests
    // ========================================================================

    @Nested
    @DisplayName("Getters and setters")
    class GetterSetterTests {

        @Test
        @DisplayName("should set and get id")
        void shouldSetAndGetId() {
            UUID id = UUID.randomUUID();
            dto.setId(id);

            assertEquals(id, dto.getId());
        }

        @Test
        @DisplayName("should set and get toolId")
        void shouldSetAndGetToolId() {
            UUID toolId = UUID.randomUUID();
            dto.setToolId(toolId);

            assertEquals(toolId, dto.getToolId());
        }

        @Test
        @DisplayName("should set and get name")
        void shouldSetAndGetName() {
            dto.setName("Test Response");

            assertEquals("Test Response", dto.getName());
        }

        @Test
        @DisplayName("should set and get description")
        void shouldSetAndGetDescription() {
            dto.setDescription("A test response description");

            assertEquals("A test response description", dto.getDescription());
        }

        @Test
        @DisplayName("should set and get schema")
        void shouldSetAndGetSchema() {
            String schema = "{\"type\": \"object\", \"properties\": {}}";
            dto.setSchema(schema);

            assertEquals(schema, dto.getSchema());
        }

        @Test
        @DisplayName("should set and get example")
        void shouldSetAndGetExample() {
            String example = "{\"id\": 123, \"name\": \"test\"}";
            dto.setExample(example);

            assertEquals(example, dto.getExample());
        }

        @Test
        @DisplayName("should set and get exampleJsonb")
        void shouldSetAndGetExampleJsonb() {
            String exampleJsonb = "{\"id\": 123}";
            dto.setExampleJsonb(exampleJsonb);

            assertEquals(exampleJsonb, dto.getExampleJsonb());
        }

        @Test
        @DisplayName("should set and get structureSkeleton")
        void shouldSetAndGetStructureSkeleton() {
            String skeleton = "{\"fields\": []}";
            dto.setStructureSkeleton(skeleton);

            assertEquals(skeleton, dto.getStructureSkeleton());
        }

        @Test
        @DisplayName("should set and get responseFormat")
        void shouldSetAndGetResponseFormat() {
            dto.setResponseFormat(ResponseFormat.JSON);

            assertEquals(ResponseFormat.JSON, dto.getResponseFormat());
        }

        @Test
        @DisplayName("should set and get statusCode")
        void shouldSetAndGetStatusCode() {
            dto.setStatusCode(201);

            assertEquals(201, dto.getStatusCode());
        }

        @Test
        @DisplayName("should set and get isDefault")
        void shouldSetAndGetIsDefault() {
            dto.setIsDefault(true);

            assertTrue(dto.getIsDefault());
        }

        @Test
        @DisplayName("should set and get isActive")
        void shouldSetAndGetIsActive() {
            dto.setIsActive(true);

            assertTrue(dto.getIsActive());
        }

        @Test
        @DisplayName("should set and get createdAt")
        void shouldSetAndGetCreatedAt() {
            LocalDateTime now = LocalDateTime.now();
            dto.setCreatedAt(now);

            assertEquals(now, dto.getCreatedAt());
        }

        @Test
        @DisplayName("should set and get updatedAt")
        void shouldSetAndGetUpdatedAt() {
            LocalDateTime now = LocalDateTime.now();
            dto.setUpdatedAt(now);

            assertEquals(now, dto.getUpdatedAt());
        }

        @Test
        @DisplayName("should set and get createdBy")
        void shouldSetAndGetCreatedBy() {
            dto.setCreatedBy("testUser");

            assertEquals("testUser", dto.getCreatedBy());
        }
    }

    // ========================================================================
    // Validation tests
    // ========================================================================

    @Nested
    @DisplayName("Validation")
    class ValidationTests {

        @Test
        @DisplayName("should fail validation when example is blank")
        void shouldFailValidationWhenExampleIsBlank() {
            dto.setExample("");
            dto.setStatusCode(200);

            Set<ConstraintViolation<ToolResponseDto>> violations = validator.validate(dto);

            assertTrue(violations.stream()
                    .anyMatch(v -> v.getPropertyPath().toString().equals("example")));
        }

        @Test
        @DisplayName("should fail validation when status code is below 200")
        void shouldFailValidationWhenStatusCodeIsBelow200() {
            dto.setExample("{}");
            dto.setStatusCode(100);

            Set<ConstraintViolation<ToolResponseDto>> violations = validator.validate(dto);

            assertTrue(violations.stream()
                    .anyMatch(v -> v.getPropertyPath().toString().equals("statusCode")));
        }

        @Test
        @DisplayName("should fail validation when status code is above 299")
        void shouldFailValidationWhenStatusCodeIsAbove299() {
            dto.setExample("{}");
            dto.setStatusCode(300);

            Set<ConstraintViolation<ToolResponseDto>> violations = validator.validate(dto);

            assertTrue(violations.stream()
                    .anyMatch(v -> v.getPropertyPath().toString().equals("statusCode")));
        }

        @Test
        @DisplayName("should pass validation with valid data")
        void shouldPassValidationWithValidData() {
            dto.setExample("{\"id\": 1}");
            dto.setStatusCode(200);

            Set<ConstraintViolation<ToolResponseDto>> violations = validator.validate(dto);

            assertTrue(violations.isEmpty());
        }
    }

    // ========================================================================
    // toString tests
    // ========================================================================

    @Nested
    @DisplayName("toString()")
    class ToStringTests {

        @Test
        @DisplayName("should include key properties in string representation")
        void shouldIncludeKeyPropertiesInString() {
            UUID id = UUID.randomUUID();
            dto.setId(id);
            dto.setName("Test Response");
            dto.setStatusCode(200);
            dto.setIsDefault(true);
            dto.setIsActive(false);

            String str = dto.toString();

            assertTrue(str.contains("id=" + id));
            assertTrue(str.contains("name='Test Response'"));
            assertTrue(str.contains("statusCode=200"));
            assertTrue(str.contains("isDefault=true"));
            assertTrue(str.contains("isActive=false"));
        }

        @Test
        @DisplayName("should indicate structure skeleton presence")
        void shouldIndicateStructureSkeletonPresence() {
            dto.setStructureSkeleton("{}");

            String str = dto.toString();

            assertTrue(str.contains("hasStructureSkeleton=true"));
        }

        @Test
        @DisplayName("should indicate structure skeleton absence")
        void shouldIndicateStructureSkeletonAbsence() {
            String str = dto.toString();

            assertTrue(str.contains("hasStructureSkeleton=false"));
        }
    }
}
