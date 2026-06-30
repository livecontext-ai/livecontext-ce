package com.apimarketplace.catalog.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ApiToolParameterEntity class.
 *
 * ApiToolParameterEntity represents a parameter for an API tool (header, path, query, body).
 * Uses Lombok for boilerplate generation.
 * Implements Persistable<UUID> for Spring Data support.
 */
@DisplayName("ApiToolParameterEntity Tests")
class ApiToolParameterEntityTest {

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
            ApiToolParameterEntity entity = new ApiToolParameterEntity();

            // Assert
            assertNull(entity.getId());
            assertNull(entity.getApiToolId());
            assertNull(entity.getParameterType());
            assertTrue(entity.isNew()); // default isNew = true
        }

        @Test
        @DisplayName("Should create entity with all-args constructor")
        void shouldCreateEntityWithAllArgsConstructor() {
            // Arrange
            UUID id = UUID.randomUUID();
            UUID apiToolId = UUID.randomUUID();
            Long createdAt = System.currentTimeMillis();

            // Act
            ApiToolParameterEntity entity = new ApiToolParameterEntity(
                false,           // isNew
                id,
                apiToolId,
                "query",         // parameterType
                "limit",         // name
                "integer",       // dataType
                false,           // isRequired
                "Limit results", // description
                "10",            // exampleValue
                "20",            // defaultValue
                "1,10,20,50",    // allowedValues
                null,            // filePath
                null,            // extras
                false,           // isHidden
                createdAt
            );

            // Assert
            assertFalse(entity.isNew());
            assertEquals(id, entity.getId());
            assertEquals(apiToolId, entity.getApiToolId());
            assertEquals("query", entity.getParameterType());
            assertEquals("limit", entity.getName());
            assertEquals("integer", entity.getDataType());
            assertFalse(entity.getIsRequired());
            assertEquals("Limit results", entity.getDescription());
            assertEquals("10", entity.getExampleValue());
            assertEquals("20", entity.getDefaultValue());
            assertEquals("1,10,20,50", entity.getAllowedValues());
            assertFalse(entity.getIsHidden());
            assertEquals(createdAt, entity.getCreatedAt());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PERSISTABLE INTERFACE TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Persistable Interface Tests")
    class PersistableTests {

        @Test
        @DisplayName("isNew should return true by default")
        void isNewShouldReturnTrueByDefault() {
            // Arrange
            ApiToolParameterEntity entity = new ApiToolParameterEntity();

            // Assert
            assertTrue(entity.isNew());
        }

        @Test
        @DisplayName("setNew should update isNew flag")
        void setNewShouldUpdateFlag() {
            // Arrange
            ApiToolParameterEntity entity = new ApiToolParameterEntity();
            assertTrue(entity.isNew());

            // Act
            entity.setNew(false);

            // Assert
            assertFalse(entity.isNew());
        }

        @Test
        @DisplayName("getId should return the entity ID")
        void getIdShouldReturnEntityId() {
            // Arrange
            ApiToolParameterEntity entity = new ApiToolParameterEntity();
            UUID id = UUID.randomUUID();
            entity.setId(id);

            // Assert
            assertEquals(id, entity.getId());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PARAMETER TYPE TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Parameter Type Tests")
    class ParameterTypeTests {

        @Test
        @DisplayName("Should accept header parameter type")
        void shouldAcceptHeaderParameterType() {
            // Arrange
            ApiToolParameterEntity entity = new ApiToolParameterEntity();

            // Act
            entity.setParameterType("header");

            // Assert
            assertEquals("header", entity.getParameterType());
        }

        @Test
        @DisplayName("Should accept path parameter type")
        void shouldAcceptPathParameterType() {
            // Arrange
            ApiToolParameterEntity entity = new ApiToolParameterEntity();

            // Act
            entity.setParameterType("path");

            // Assert
            assertEquals("path", entity.getParameterType());
        }

        @Test
        @DisplayName("Should accept query parameter type")
        void shouldAcceptQueryParameterType() {
            // Arrange
            ApiToolParameterEntity entity = new ApiToolParameterEntity();

            // Act
            entity.setParameterType("query");

            // Assert
            assertEquals("query", entity.getParameterType());
        }

        @Test
        @DisplayName("Should accept body parameter type")
        void shouldAcceptBodyParameterType() {
            // Arrange
            ApiToolParameterEntity entity = new ApiToolParameterEntity();

            // Act
            entity.setParameterType("body");

            // Assert
            assertEquals("body", entity.getParameterType());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GETTERS AND SETTERS TESTS (Lombok)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Getters and Setters Tests")
    class GettersSettersTests {

        @Test
        @DisplayName("Should set and get name")
        void shouldSetAndGetName() {
            // Arrange
            ApiToolParameterEntity entity = new ApiToolParameterEntity();

            // Act
            entity.setName("userId");

            // Assert
            assertEquals("userId", entity.getName());
        }

        @Test
        @DisplayName("Should set and get dataType")
        void shouldSetAndGetDataType() {
            // Arrange
            ApiToolParameterEntity entity = new ApiToolParameterEntity();

            // Act
            entity.setDataType("string");

            // Assert
            assertEquals("string", entity.getDataType());
        }

        @Test
        @DisplayName("Should set and get isRequired")
        void shouldSetAndGetIsRequired() {
            // Arrange
            ApiToolParameterEntity entity = new ApiToolParameterEntity();

            // Act
            entity.setIsRequired(true);

            // Assert
            assertTrue(entity.getIsRequired());
        }

        @Test
        @DisplayName("Should set and get description")
        void shouldSetAndGetDescription() {
            // Arrange
            ApiToolParameterEntity entity = new ApiToolParameterEntity();

            // Act
            entity.setDescription("The unique user identifier");

            // Assert
            assertEquals("The unique user identifier", entity.getDescription());
        }

        @Test
        @DisplayName("Should set and get exampleValue")
        void shouldSetAndGetExampleValue() {
            // Arrange
            ApiToolParameterEntity entity = new ApiToolParameterEntity();

            // Act
            entity.setExampleValue("user-123");

            // Assert
            assertEquals("user-123", entity.getExampleValue());
        }

        @Test
        @DisplayName("Should set and get defaultValue")
        void shouldSetAndGetDefaultValue() {
            // Arrange
            ApiToolParameterEntity entity = new ApiToolParameterEntity();

            // Act
            entity.setDefaultValue("default-user");

            // Assert
            assertEquals("default-user", entity.getDefaultValue());
        }

        @Test
        @DisplayName("Should set and get allowedValues")
        void shouldSetAndGetAllowedValues() {
            // Arrange
            ApiToolParameterEntity entity = new ApiToolParameterEntity();

            // Act
            entity.setAllowedValues("active,inactive,pending");

            // Assert
            assertEquals("active,inactive,pending", entity.getAllowedValues());
        }

        @Test
        @DisplayName("Should set and get filePath")
        void shouldSetAndGetFilePath() {
            // Arrange
            ApiToolParameterEntity entity = new ApiToolParameterEntity();

            // Act
            entity.setFilePath("/path/to/schema.json");

            // Assert
            assertEquals("/path/to/schema.json", entity.getFilePath());
        }

        @Test
        @DisplayName("Should set and get extras")
        void shouldSetAndGetExtras() {
            // Arrange
            ApiToolParameterEntity entity = new ApiToolParameterEntity();
            String extras = "{\"min\": 0, \"max\": 100}";

            // Act
            entity.setExtras(extras);

            // Assert
            assertEquals(extras, entity.getExtras());
        }

        @Test
        @DisplayName("Should set and get isHidden")
        void shouldSetAndGetIsHidden() {
            // Arrange
            ApiToolParameterEntity entity = new ApiToolParameterEntity();

            // Act
            entity.setIsHidden(true);

            // Assert
            assertTrue(entity.getIsHidden());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EQUALS AND HASHCODE TESTS (Lombok @Data)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Equals and HashCode Tests")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("Should be equal when all fields match")
        void shouldBeEqualWhenAllFieldsMatch() {
            // Arrange
            UUID id = UUID.randomUUID();
            UUID apiToolId = UUID.randomUUID();

            ApiToolParameterEntity entity1 = new ApiToolParameterEntity();
            entity1.setId(id);
            entity1.setApiToolId(apiToolId);
            entity1.setName("param");
            entity1.setNew(false);

            ApiToolParameterEntity entity2 = new ApiToolParameterEntity();
            entity2.setId(id);
            entity2.setApiToolId(apiToolId);
            entity2.setName("param");
            entity2.setNew(false);

            // Assert
            assertEquals(entity1, entity2);
            assertEquals(entity1.hashCode(), entity2.hashCode());
        }

        @Test
        @DisplayName("Should not be equal when id differs")
        void shouldNotBeEqualWhenIdDiffers() {
            // Arrange
            ApiToolParameterEntity entity1 = new ApiToolParameterEntity();
            entity1.setId(UUID.randomUUID());

            ApiToolParameterEntity entity2 = new ApiToolParameterEntity();
            entity2.setId(UUID.randomUUID());

            // Assert
            assertNotEquals(entity1, entity2);
        }
    }
}
