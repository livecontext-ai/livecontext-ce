package com.apimarketplace.catalog.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ToolNameEntity.
 *
 * ToolNameEntity represents a tool name in the catalog.
 */
@DisplayName("ToolNameEntity")
class ToolNameEntityTest {

    @Nested
    @DisplayName("Constructors")
    class ConstructorTests {

        @Test
        @DisplayName("should create entity with default constructor")
        void shouldCreateEntityWithDefaultConstructor() {
            ToolNameEntity entity = new ToolNameEntity();

            assertNull(entity.getId());
            assertNull(entity.getName());
            assertNull(entity.getToolCategoryId());
            assertFalse(entity.getIsActive());
        }

        @Test
        @DisplayName("should create entity with all-args constructor")
        void shouldCreateEntityWithAllArgsConstructor() {
            UUID id = UUID.randomUUID();
            String name = "weatherForecast";
            String description = "Get weather forecast data";
            UUID toolCategoryId = UUID.randomUUID();
            UUID subcategoryId = UUID.randomUUID();
            String runScope = "user";
            Boolean requiresUserCredentials = true;
            String slug = "weather-forecast";
            Boolean isActive = true;
            Long createdAt = System.currentTimeMillis();
            Long updatedAt = System.currentTimeMillis();

            ToolNameEntity entity = new ToolNameEntity(
                id, name, description, toolCategoryId, subcategoryId,
                runScope, requiresUserCredentials, slug, isActive, createdAt, updatedAt
            );

            assertEquals(id, entity.getId());
            assertEquals(name, entity.getName());
            assertEquals(description, entity.getDescription());
            assertEquals(toolCategoryId, entity.getToolCategoryId());
            assertEquals(subcategoryId, entity.getSubcategoryId());
            assertEquals(runScope, entity.getRunScope());
            assertEquals(requiresUserCredentials, entity.getRequiresUserCredentials());
            assertEquals(slug, entity.getSlug());
            assertEquals(isActive, entity.getIsActive());
        }
    }

    @Nested
    @DisplayName("Getters and Setters")
    class GetterSetterTests {

        @Test
        @DisplayName("should get and set id")
        void shouldGetAndSetId() {
            ToolNameEntity entity = new ToolNameEntity();
            UUID id = UUID.randomUUID();
            entity.setId(id);
            assertEquals(id, entity.getId());
        }

        @Test
        @DisplayName("should get and set name")
        void shouldGetAndSetName() {
            ToolNameEntity entity = new ToolNameEntity();
            entity.setName("searchWeb");
            assertEquals("searchWeb", entity.getName());
        }

        @Test
        @DisplayName("should get and set toolCategoryId")
        void shouldGetAndSetToolCategoryId() {
            ToolNameEntity entity = new ToolNameEntity();
            UUID categoryId = UUID.randomUUID();
            entity.setToolCategoryId(categoryId);
            assertEquals(categoryId, entity.getToolCategoryId());
        }

        @Test
        @DisplayName("should get and set subcategoryId")
        void shouldGetAndSetSubcategoryId() {
            ToolNameEntity entity = new ToolNameEntity();
            UUID subcategoryId = UUID.randomUUID();
            entity.setSubcategoryId(subcategoryId);
            assertEquals(subcategoryId, entity.getSubcategoryId());
        }

        @Test
        @DisplayName("should get and set runScope")
        void shouldGetAndSetRunScope() {
            ToolNameEntity entity = new ToolNameEntity();
            entity.setRunScope("global");
            assertEquals("global", entity.getRunScope());
        }

        @Test
        @DisplayName("should get and set requiresUserCredentials")
        void shouldGetAndSetRequiresUserCredentials() {
            ToolNameEntity entity = new ToolNameEntity();
            entity.setRequiresUserCredentials(true);
            assertTrue(entity.getRequiresUserCredentials());
        }

        @Test
        @DisplayName("should get and set isActive")
        void shouldGetAndSetIsActive() {
            ToolNameEntity entity = new ToolNameEntity();
            entity.setIsActive(true);
            assertTrue(entity.getIsActive());
        }

        @Test
        @DisplayName("should get and set slug")
        void shouldGetAndSetSlug() {
            ToolNameEntity entity = new ToolNameEntity();
            entity.setSlug("search-web");
            assertEquals("search-web", entity.getSlug());
        }
    }

    @Nested
    @DisplayName("Default values")
    class DefaultValueTests {

        @Test
        @DisplayName("should have false as default isActive")
        void shouldHaveFalseAsDefaultIsActive() {
            ToolNameEntity entity = new ToolNameEntity();
            assertFalse(entity.getIsActive());
        }
    }
}
