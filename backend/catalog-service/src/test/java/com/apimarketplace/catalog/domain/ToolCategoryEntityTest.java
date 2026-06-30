package com.apimarketplace.catalog.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ToolCategoryEntity.
 *
 * ToolCategoryEntity represents a tool category in the catalog.
 */
@DisplayName("ToolCategoryEntity")
class ToolCategoryEntityTest {

    // ========================================================================
    // Constructor tests
    // ========================================================================

    @Nested
    @DisplayName("Constructors")
    class ConstructorTests {

        @Test
        @DisplayName("should create entity with default constructor")
        void shouldCreateEntityWithDefaultConstructor() {
            // Act
            ToolCategoryEntity entity = new ToolCategoryEntity();

            // Assert
            assertNotNull(entity);
            assertNull(entity.getId());
            assertNull(entity.getName());
            assertNull(entity.getDescription());
            assertNull(entity.getIcon());
            assertNull(entity.getColor());
            assertNull(entity.getSortOrder());
            assertNull(entity.getSlug());
            assertNull(entity.getIconUrl());
            assertFalse(entity.getIsActive()); // Default is false
            assertNull(entity.getCreatedAt());
            assertNull(entity.getUpdatedAt());
        }

        @Test
        @DisplayName("should create entity with parameterized constructor")
        void shouldCreateEntityWithParameterizedConstructor() {
            // Arrange
            UUID id = UUID.randomUUID();
            String name = "Data APIs";
            String description = "APIs for data processing";
            String icon = "data-icon";
            String color = "#FF5733";
            Integer sortOrder = 1;
            String slug = "data-apis";
            Boolean isActive = true;
            Long createdAt = System.currentTimeMillis();
            Long updatedAt = System.currentTimeMillis();

            // Act
            ToolCategoryEntity entity = new ToolCategoryEntity(
                id, name, description, icon, color, sortOrder, slug, isActive, createdAt, updatedAt
            );

            // Assert
            assertEquals(id, entity.getId());
            assertEquals(name, entity.getName());
            assertEquals(description, entity.getDescription());
            assertEquals(icon, entity.getIcon());
            assertEquals(color, entity.getColor());
            assertEquals(sortOrder, entity.getSortOrder());
            assertEquals(slug, entity.getSlug());
            assertEquals(isActive, entity.getIsActive());
            assertEquals(createdAt, entity.getCreatedAt());
            assertEquals(updatedAt, entity.getUpdatedAt());
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
            ToolCategoryEntity entity = new ToolCategoryEntity();
            UUID id = UUID.randomUUID();
            String name = "AI Tools";
            String description = "AI-powered tools";
            String icon = "ai-icon";
            String color = "#00FF00";
            Integer sortOrder = 5;
            String slug = "ai-tools";
            String iconUrl = "https://example.com/icon.png";
            Boolean isActive = true;
            Long createdAt = 1700000000000L;
            Long updatedAt = 1700000001000L;

            // Act
            entity.setId(id);
            entity.setName(name);
            entity.setDescription(description);
            entity.setIcon(icon);
            entity.setColor(color);
            entity.setSortOrder(sortOrder);
            entity.setSlug(slug);
            entity.setIconUrl(iconUrl);
            entity.setIsActive(isActive);
            entity.setCreatedAt(createdAt);
            entity.setUpdatedAt(updatedAt);

            // Assert
            assertEquals(id, entity.getId());
            assertEquals(name, entity.getName());
            assertEquals(description, entity.getDescription());
            assertEquals(icon, entity.getIcon());
            assertEquals(color, entity.getColor());
            assertEquals(sortOrder, entity.getSortOrder());
            assertEquals(slug, entity.getSlug());
            assertEquals(iconUrl, entity.getIconUrl());
            assertEquals(isActive, entity.getIsActive());
            assertEquals(createdAt, entity.getCreatedAt());
            assertEquals(updatedAt, entity.getUpdatedAt());
        }

        @Test
        @DisplayName("should handle null values in setters")
        void shouldHandleNullValuesInSetters() {
            // Arrange
            ToolCategoryEntity entity = new ToolCategoryEntity();
            entity.setName("Initial Name");
            entity.setIsActive(true);

            // Act
            entity.setName(null);
            entity.setIsActive(null);

            // Assert
            assertNull(entity.getName());
            assertNull(entity.getIsActive());
        }
    }

    // ========================================================================
    // Default values tests
    // ========================================================================

    @Nested
    @DisplayName("Default Values")
    class DefaultValuesTests {

        @Test
        @DisplayName("should have isActive default to false")
        void shouldHaveIsActiveDefaultToFalse() {
            // Act
            ToolCategoryEntity entity = new ToolCategoryEntity();

            // Assert
            assertFalse(entity.getIsActive());
        }
    }
}
