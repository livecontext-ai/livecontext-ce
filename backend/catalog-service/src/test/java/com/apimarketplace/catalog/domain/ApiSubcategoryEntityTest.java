package com.apimarketplace.catalog.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ApiSubcategoryEntity class.
 *
 * Tests constructors, getters, setters, and basic entity behavior.
 */
@DisplayName("ApiSubcategoryEntity Tests")
class ApiSubcategoryEntityTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // CONSTRUCTOR TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Constructors")
    class ConstructorTests {

        @Test
        @DisplayName("Should create entity with default constructor")
        void shouldCreateWithDefaultConstructor() {
            ApiSubcategoryEntity entity = new ApiSubcategoryEntity();

            assertNotNull(entity);
            assertNull(entity.getId());
            assertNull(entity.getCategoryId());
            assertNull(entity.getName());
            assertNull(entity.getDescription());
            assertNull(entity.getIcon());
            assertNull(entity.getColor());
            assertNull(entity.getSortOrder());
            assertNull(entity.getSlug());
            assertNull(entity.getIconUrl());
            assertNull(entity.getCreatedAt());
            assertNull(entity.getUpdatedAt());
        }

        @Test
        @DisplayName("Should create entity with all-args constructor")
        void shouldCreateWithAllArgsConstructor() {
            UUID id = UUID.randomUUID();
            UUID categoryId = UUID.randomUUID();
            String name = "Payment Processing";
            String description = "APIs for payment processing";
            String icon = "payment-icon";
            String color = "#2196F3";
            Integer sortOrder = 1;
            String slug = "payment-processing";
            Long createdAt = System.currentTimeMillis();
            Long updatedAt = System.currentTimeMillis();

            ApiSubcategoryEntity entity = new ApiSubcategoryEntity(
                id, categoryId, name, description, icon, color, sortOrder, slug, createdAt, updatedAt
            );

            assertEquals(id, entity.getId());
            assertEquals(categoryId, entity.getCategoryId());
            assertEquals(name, entity.getName());
            assertEquals(description, entity.getDescription());
            assertEquals(icon, entity.getIcon());
            assertEquals(color, entity.getColor());
            assertEquals(sortOrder, entity.getSortOrder());
            assertEquals(slug, entity.getSlug());
            assertEquals(createdAt, entity.getCreatedAt());
            assertEquals(updatedAt, entity.getUpdatedAt());
            // iconUrl is not set by constructor
            assertNull(entity.getIconUrl());
        }

        @Test
        @DisplayName("Should allow null values in all-args constructor")
        void shouldAllowNullValuesInConstructor() {
            ApiSubcategoryEntity entity = new ApiSubcategoryEntity(
                null, null, null, null, null, null, null, null, null, null
            );

            assertNotNull(entity);
            assertNull(entity.getId());
            assertNull(entity.getCategoryId());
            assertNull(entity.getName());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GETTER/SETTER TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Getters and Setters")
    class GetterSetterTests {

        @Test
        @DisplayName("Should set and get id")
        void shouldSetAndGetId() {
            ApiSubcategoryEntity entity = new ApiSubcategoryEntity();
            UUID id = UUID.randomUUID();

            entity.setId(id);

            assertEquals(id, entity.getId());
        }

        @Test
        @DisplayName("Should set and get categoryId")
        void shouldSetAndGetCategoryId() {
            ApiSubcategoryEntity entity = new ApiSubcategoryEntity();
            UUID categoryId = UUID.randomUUID();

            entity.setCategoryId(categoryId);

            assertEquals(categoryId, entity.getCategoryId());
        }

        @Test
        @DisplayName("Should set and get name")
        void shouldSetAndGetName() {
            ApiSubcategoryEntity entity = new ApiSubcategoryEntity();
            String name = "Stock Data";

            entity.setName(name);

            assertEquals(name, entity.getName());
        }

        @Test
        @DisplayName("Should set and get description")
        void shouldSetAndGetDescription() {
            ApiSubcategoryEntity entity = new ApiSubcategoryEntity();
            String description = "APIs for stock market data";

            entity.setDescription(description);

            assertEquals(description, entity.getDescription());
        }

        @Test
        @DisplayName("Should set and get icon")
        void shouldSetAndGetIcon() {
            ApiSubcategoryEntity entity = new ApiSubcategoryEntity();
            String icon = "stock-icon";

            entity.setIcon(icon);

            assertEquals(icon, entity.getIcon());
        }

        @Test
        @DisplayName("Should set and get color")
        void shouldSetAndGetColor() {
            ApiSubcategoryEntity entity = new ApiSubcategoryEntity();
            String color = "#9C27B0";

            entity.setColor(color);

            assertEquals(color, entity.getColor());
        }

        @Test
        @DisplayName("Should set and get sortOrder")
        void shouldSetAndGetSortOrder() {
            ApiSubcategoryEntity entity = new ApiSubcategoryEntity();
            Integer sortOrder = 3;

            entity.setSortOrder(sortOrder);

            assertEquals(sortOrder, entity.getSortOrder());
        }

        @Test
        @DisplayName("Should set and get slug")
        void shouldSetAndGetSlug() {
            ApiSubcategoryEntity entity = new ApiSubcategoryEntity();
            String slug = "stock-data";

            entity.setSlug(slug);

            assertEquals(slug, entity.getSlug());
        }

        @Test
        @DisplayName("Should set and get iconUrl")
        void shouldSetAndGetIconUrl() {
            ApiSubcategoryEntity entity = new ApiSubcategoryEntity();
            String iconUrl = "https://example.com/icon.png";

            entity.setIconUrl(iconUrl);

            assertEquals(iconUrl, entity.getIconUrl());
        }

        @Test
        @DisplayName("Should set and get createdAt")
        void shouldSetAndGetCreatedAt() {
            ApiSubcategoryEntity entity = new ApiSubcategoryEntity();
            Long createdAt = 1700000000000L;

            entity.setCreatedAt(createdAt);

            assertEquals(createdAt, entity.getCreatedAt());
        }

        @Test
        @DisplayName("Should set and get updatedAt")
        void shouldSetAndGetUpdatedAt() {
            ApiSubcategoryEntity entity = new ApiSubcategoryEntity();
            Long updatedAt = 1700000000000L;

            entity.setUpdatedAt(updatedAt);

            assertEquals(updatedAt, entity.getUpdatedAt());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NULL HANDLING TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Null Handling")
    class NullHandlingTests {

        @Test
        @DisplayName("Should allow setting null values")
        void shouldAllowSettingNullValues() {
            ApiSubcategoryEntity entity = new ApiSubcategoryEntity();
            entity.setId(UUID.randomUUID());
            entity.setCategoryId(UUID.randomUUID());
            entity.setName("Test");

            entity.setId(null);
            entity.setCategoryId(null);
            entity.setName(null);

            assertNull(entity.getId());
            assertNull(entity.getCategoryId());
            assertNull(entity.getName());
        }

        @Test
        @DisplayName("Should handle null iconUrl")
        void shouldHandleNullIconUrl() {
            ApiSubcategoryEntity entity = new ApiSubcategoryEntity();
            entity.setIconUrl("https://example.com/icon.png");

            entity.setIconUrl(null);

            assertNull(entity.getIconUrl());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EDGE CASES
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle empty strings")
        void shouldHandleEmptyStrings() {
            ApiSubcategoryEntity entity = new ApiSubcategoryEntity();

            entity.setName("");
            entity.setDescription("");
            entity.setSlug("");
            entity.setIconUrl("");

            assertEquals("", entity.getName());
            assertEquals("", entity.getDescription());
            assertEquals("", entity.getSlug());
            assertEquals("", entity.getIconUrl());
        }

        @Test
        @DisplayName("Should handle very long strings")
        void shouldHandleLongStrings() {
            ApiSubcategoryEntity entity = new ApiSubcategoryEntity();
            String longString = "a".repeat(10000);

            entity.setName(longString);
            entity.setDescription(longString);
            entity.setIconUrl(longString);

            assertEquals(longString, entity.getName());
            assertEquals(longString, entity.getDescription());
            assertEquals(longString, entity.getIconUrl());
        }

        @Test
        @DisplayName("Should handle special characters in strings")
        void shouldHandleSpecialCharacters() {
            ApiSubcategoryEntity entity = new ApiSubcategoryEntity();
            String specialChars = "Test with émojis 🚀 and ünïcödé";

            entity.setName(specialChars);
            entity.setDescription(specialChars);

            assertEquals(specialChars, entity.getName());
            assertEquals(specialChars, entity.getDescription());
        }

        @Test
        @DisplayName("Should handle URL with query parameters")
        void shouldHandleUrlWithQueryParams() {
            ApiSubcategoryEntity entity = new ApiSubcategoryEntity();
            String url = "https://example.com/icon.png?size=large&format=webp";

            entity.setIconUrl(url);

            assertEquals(url, entity.getIconUrl());
        }

        @Test
        @DisplayName("Should handle same category and subcategory id")
        void shouldHandleSameCategoryAndSubcategoryId() {
            UUID sameId = UUID.randomUUID();
            ApiSubcategoryEntity entity = new ApiSubcategoryEntity();

            entity.setId(sameId);
            entity.setCategoryId(sameId);

            assertEquals(sameId, entity.getId());
            assertEquals(sameId, entity.getCategoryId());
        }
    }
}
