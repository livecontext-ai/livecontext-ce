package com.apimarketplace.catalog.mapping.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MappingDefinitionEntity.
 *
 * MappingDefinitionEntity represents a mapping definition in the catalog.
 */
@DisplayName("MappingDefinitionEntity")
class MappingDefinitionEntityTest {

    // ========================================================================
    // Default constructor tests
    // ========================================================================

    @Nested
    @DisplayName("Default constructor")
    class DefaultConstructorTests {

        @Test
        @DisplayName("should create entity with default values")
        void shouldCreateEntityWithDefaultValues() {
            MappingDefinitionEntity entity = new MappingDefinitionEntity();

            assertNull(entity.getId());
            assertNull(entity.getToolId());
            assertNull(entity.getDisplayName());
            assertEquals("ACTIVE", entity.getStatus());
            assertNull(entity.getCreatedAt());
            assertNull(entity.getUpdatedAt());
            assertNull(entity.getCreatedBy());
            assertNull(entity.getDescription());
            assertNull(entity.getVersions());
        }
    }

    // ========================================================================
    // Parameterized constructor tests
    // ========================================================================

    @Nested
    @DisplayName("Parameterized constructor")
    class ParameterizedConstructorTests {

        @Test
        @DisplayName("should create entity with required fields")
        void shouldCreateEntityWithRequiredFields() {
            UUID toolId = UUID.randomUUID();

            MappingDefinitionEntity entity = new MappingDefinitionEntity(
                toolId, "Weather Mapping", "user123"
            );

            assertEquals(toolId, entity.getToolId());
            assertEquals("Weather Mapping", entity.getDisplayName());
            assertEquals("user123", entity.getCreatedBy());
            assertNotNull(entity.getCreatedAt());
            assertNotNull(entity.getUpdatedAt());
            assertEquals("ACTIVE", entity.getStatus());
        }

        @Test
        @DisplayName("should set timestamps on creation")
        void shouldSetTimestampsOnCreation() {
            LocalDateTime before = LocalDateTime.now();

            MappingDefinitionEntity entity = new MappingDefinitionEntity(
                UUID.randomUUID(), "Test Mapping", "user"
            );

            LocalDateTime after = LocalDateTime.now();

            assertNotNull(entity.getCreatedAt());
            assertNotNull(entity.getUpdatedAt());
            assertFalse(entity.getCreatedAt().isBefore(before));
            assertFalse(entity.getCreatedAt().isAfter(after));
        }
    }

    // ========================================================================
    // Getter and Setter tests
    // ========================================================================

    @Nested
    @DisplayName("Getters and Setters")
    class GetterSetterTests {

        @Test
        @DisplayName("should get and set id")
        void shouldGetAndSetId() {
            MappingDefinitionEntity entity = new MappingDefinitionEntity();

            entity.setId(123L);

            assertEquals(123L, entity.getId());
        }

        @Test
        @DisplayName("should get and set toolId")
        void shouldGetAndSetToolId() {
            MappingDefinitionEntity entity = new MappingDefinitionEntity();
            UUID toolId = UUID.randomUUID();

            entity.setToolId(toolId);

            assertEquals(toolId, entity.getToolId());
        }

        @Test
        @DisplayName("should get and set displayName")
        void shouldGetAndSetDisplayName() {
            MappingDefinitionEntity entity = new MappingDefinitionEntity();

            entity.setDisplayName("My Mapping");

            assertEquals("My Mapping", entity.getDisplayName());
        }

        @Test
        @DisplayName("should get and set status")
        void shouldGetAndSetStatus() {
            MappingDefinitionEntity entity = new MappingDefinitionEntity();

            entity.setStatus("INACTIVE");

            assertEquals("INACTIVE", entity.getStatus());
        }

        @Test
        @DisplayName("should get and set createdAt")
        void shouldGetAndSetCreatedAt() {
            MappingDefinitionEntity entity = new MappingDefinitionEntity();
            LocalDateTime now = LocalDateTime.now();

            entity.setCreatedAt(now);

            assertEquals(now, entity.getCreatedAt());
        }

        @Test
        @DisplayName("should get and set updatedAt")
        void shouldGetAndSetUpdatedAt() {
            MappingDefinitionEntity entity = new MappingDefinitionEntity();
            LocalDateTime now = LocalDateTime.now();

            entity.setUpdatedAt(now);

            assertEquals(now, entity.getUpdatedAt());
        }

        @Test
        @DisplayName("should get and set createdBy")
        void shouldGetAndSetCreatedBy() {
            MappingDefinitionEntity entity = new MappingDefinitionEntity();

            entity.setCreatedBy("admin");

            assertEquals("admin", entity.getCreatedBy());
        }

        @Test
        @DisplayName("should get and set description")
        void shouldGetAndSetDescription() {
            MappingDefinitionEntity entity = new MappingDefinitionEntity();

            entity.setDescription("This mapping transforms weather data");

            assertEquals("This mapping transforms weather data", entity.getDescription());
        }

        @Test
        @DisplayName("should get and set versions")
        void shouldGetAndSetVersions() {
            MappingDefinitionEntity entity = new MappingDefinitionEntity();
            List<MappingVersionEntity> versions = List.of(new MappingVersionEntity());

            entity.setVersions(versions);

            assertEquals(versions, entity.getVersions());
        }
    }

    // ========================================================================
    // Status values tests
    // ========================================================================

    @Nested
    @DisplayName("Status values")
    class StatusValuesTests {

        @Test
        @DisplayName("should have ACTIVE as default status")
        void shouldHaveActiveAsDefaultStatus() {
            MappingDefinitionEntity entity = new MappingDefinitionEntity();

            assertEquals("ACTIVE", entity.getStatus());
        }

        @Test
        @DisplayName("should accept INACTIVE status")
        void shouldAcceptInactiveStatus() {
            MappingDefinitionEntity entity = new MappingDefinitionEntity();

            entity.setStatus("INACTIVE");

            assertEquals("INACTIVE", entity.getStatus());
        }

        @Test
        @DisplayName("should accept DEPRECATED status")
        void shouldAcceptDeprecatedStatus() {
            MappingDefinitionEntity entity = new MappingDefinitionEntity();

            entity.setStatus("DEPRECATED");

            assertEquals("DEPRECATED", entity.getStatus());
        }
    }

    // ========================================================================
    // toString tests
    // ========================================================================

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("should return string representation")
        void shouldReturnStringRepresentation() {
            UUID toolId = UUID.randomUUID();
            MappingDefinitionEntity entity = new MappingDefinitionEntity(
                toolId, "Test Mapping", "user123"
            );
            entity.setId(1L);

            String str = entity.toString();

            assertNotNull(str);
            assertTrue(str.contains("MappingDefinitionEntity"));
            assertTrue(str.contains("id=1"));
            assertTrue(str.contains("Test Mapping"));
            assertTrue(str.contains("user123"));
        }
    }
}
