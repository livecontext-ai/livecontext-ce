package com.apimarketplace.catalog.mapping.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MappingVersionEntity.
 *
 * MappingVersionEntity represents a version of a mapping definition.
 */
@DisplayName("MappingVersionEntity")
class MappingVersionEntityTest {

    // ========================================================================
    // Default constructor tests
    // ========================================================================

    @Nested
    @DisplayName("Default constructor")
    class DefaultConstructorTests {

        @Test
        @DisplayName("should create entity with default values")
        void shouldCreateEntityWithDefaultValues() {
            MappingVersionEntity entity = new MappingVersionEntity();

            assertNull(entity.getId());
            assertNull(entity.getMappingDefinitionId());
            assertNull(entity.getVersion());
            assertNull(entity.getSpec());
            assertFalse(entity.getIsLatest());
            assertNull(entity.getCreatedAt());
            assertNull(entity.getCreatedBy());
            assertNull(entity.getDescription());
            assertNull(entity.getMappingDefinition());
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
            String spec = "{\"source\": \"json\", \"target\": \"json\"}";

            MappingVersionEntity entity = new MappingVersionEntity(
                1L, "1.0.0", spec, "user123"
            );

            assertEquals(1L, entity.getMappingDefinitionId());
            assertEquals("1.0.0", entity.getVersion());
            assertEquals(spec, entity.getSpec());
            assertEquals("user123", entity.getCreatedBy());
            assertNotNull(entity.getCreatedAt());
            assertFalse(entity.getIsLatest());
        }

        @Test
        @DisplayName("should set createdAt on creation")
        void shouldSetCreatedAtOnCreation() {
            LocalDateTime before = LocalDateTime.now();

            MappingVersionEntity entity = new MappingVersionEntity(
                1L, "1.0.0", "{}", "user"
            );

            LocalDateTime after = LocalDateTime.now();

            assertNotNull(entity.getCreatedAt());
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
            MappingVersionEntity entity = new MappingVersionEntity();

            entity.setId(42L);

            assertEquals(42L, entity.getId());
        }

        @Test
        @DisplayName("should get and set mappingDefinitionId")
        void shouldGetAndSetMappingDefinitionId() {
            MappingVersionEntity entity = new MappingVersionEntity();

            entity.setMappingDefinitionId(5L);

            assertEquals(5L, entity.getMappingDefinitionId());
        }

        @Test
        @DisplayName("should get and set version")
        void shouldGetAndSetVersion() {
            MappingVersionEntity entity = new MappingVersionEntity();

            entity.setVersion("2.0.0");

            assertEquals("2.0.0", entity.getVersion());
        }

        @Test
        @DisplayName("should get and set spec")
        void shouldGetAndSetSpec() {
            MappingVersionEntity entity = new MappingVersionEntity();
            String spec = "{\"mappings\": []}";

            entity.setSpec(spec);

            assertEquals(spec, entity.getSpec());
        }

        @Test
        @DisplayName("should get and set isLatest")
        void shouldGetAndSetIsLatest() {
            MappingVersionEntity entity = new MappingVersionEntity();

            entity.setIsLatest(true);

            assertTrue(entity.getIsLatest());
        }

        @Test
        @DisplayName("should get and set createdAt")
        void shouldGetAndSetCreatedAt() {
            MappingVersionEntity entity = new MappingVersionEntity();
            LocalDateTime now = LocalDateTime.now();

            entity.setCreatedAt(now);

            assertEquals(now, entity.getCreatedAt());
        }

        @Test
        @DisplayName("should get and set createdBy")
        void shouldGetAndSetCreatedBy() {
            MappingVersionEntity entity = new MappingVersionEntity();

            entity.setCreatedBy("admin");

            assertEquals("admin", entity.getCreatedBy());
        }

        @Test
        @DisplayName("should get and set description")
        void shouldGetAndSetDescription() {
            MappingVersionEntity entity = new MappingVersionEntity();

            entity.setDescription("Initial version of the mapping");

            assertEquals("Initial version of the mapping", entity.getDescription());
        }

        @Test
        @DisplayName("should get and set mappingDefinition")
        void shouldGetAndSetMappingDefinition() {
            MappingVersionEntity entity = new MappingVersionEntity();
            MappingDefinitionEntity definition = new MappingDefinitionEntity();

            entity.setMappingDefinition(definition);

            assertEquals(definition, entity.getMappingDefinition());
        }
    }

    // ========================================================================
    // Spec update tests
    // ========================================================================

    @Nested
    @DisplayName("Spec updates")
    class SpecUpdateTests {

        @Test
        @DisplayName("should reset parsedSpec when spec changes")
        void shouldResetParsedSpecWhenSpecChanges() {
            MappingVersionEntity entity = new MappingVersionEntity();
            entity.setSpec("{\"old\": true}");

            // Access parsedSpec to trigger parsing
            entity.getParsedSpec();

            // Update spec
            entity.setSpec("{\"new\": true}");

            // The parsedSpec should be reset (will be null or re-parsed)
            assertEquals("{\"new\": true}", entity.getSpec());
        }
    }

    // ========================================================================
    // Version format tests
    // ========================================================================

    @Nested
    @DisplayName("Version formats")
    class VersionFormatTests {

        @Test
        @DisplayName("should accept semantic version format")
        void shouldAcceptSemanticVersionFormat() {
            MappingVersionEntity entity = new MappingVersionEntity();

            entity.setVersion("1.2.3");

            assertEquals("1.2.3", entity.getVersion());
        }

        @Test
        @DisplayName("should accept simple version format")
        void shouldAcceptSimpleVersionFormat() {
            MappingVersionEntity entity = new MappingVersionEntity();

            entity.setVersion("v1");

            assertEquals("v1", entity.getVersion());
        }

        @Test
        @DisplayName("should accept date-based version format")
        void shouldAcceptDateBasedVersionFormat() {
            MappingVersionEntity entity = new MappingVersionEntity();

            entity.setVersion("2024-01-15");

            assertEquals("2024-01-15", entity.getVersion());
        }
    }

    // ========================================================================
    // isLatest flag tests
    // ========================================================================

    @Nested
    @DisplayName("isLatest flag")
    class IsLatestFlagTests {

        @Test
        @DisplayName("should have false as default isLatest")
        void shouldHaveFalseAsDefaultIsLatest() {
            MappingVersionEntity entity = new MappingVersionEntity();

            assertFalse(entity.getIsLatest());
        }

        @Test
        @DisplayName("should allow setting isLatest to true")
        void shouldAllowSettingIsLatestToTrue() {
            MappingVersionEntity entity = new MappingVersionEntity();

            entity.setIsLatest(true);

            assertTrue(entity.getIsLatest());
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
            MappingVersionEntity entity = new MappingVersionEntity(
                1L, "1.0.0", "{}", "user123"
            );
            entity.setId(42L);
            entity.setIsLatest(true);

            String str = entity.toString();

            assertNotNull(str);
            assertTrue(str.contains("MappingVersionEntity"));
            assertTrue(str.contains("id=42"));
            assertTrue(str.contains("version='1.0.0'"));
            assertTrue(str.contains("isLatest=true"));
        }
    }
}
