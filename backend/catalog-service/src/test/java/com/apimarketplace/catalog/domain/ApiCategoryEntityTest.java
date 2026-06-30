package com.apimarketplace.catalog.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ApiCategoryEntity class.
 *
 * Tests constructors, getters, setters, and basic entity behavior.
 */
@DisplayName("ApiCategoryEntity Tests")
class ApiCategoryEntityTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // CONSTRUCTOR TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Constructors")
    class ConstructorTests {

        @Test
        @DisplayName("Should create entity with default constructor")
        void shouldCreateWithDefaultConstructor() {
            ApiCategoryEntity entity = new ApiCategoryEntity();

            assertNotNull(entity);
            assertNull(entity.getId());
            assertNull(entity.getName());
            assertNull(entity.getDescription());
            assertNull(entity.getIcon());
            assertNull(entity.getColor());
            assertNull(entity.getSortOrder());
            assertNull(entity.getSlug());
            assertNull(entity.getCreatedAt());
            assertNull(entity.getUpdatedAt());
        }

        @Test
        @DisplayName("Should create entity with all-args constructor")
        void shouldCreateWithAllArgsConstructor() {
            UUID id = UUID.randomUUID();
            String name = "Finance APIs";
            String description = "Financial data and payment APIs";
            String icon = "money-icon";
            String color = "#4CAF50";
            Integer sortOrder = 1;
            String slug = "finance-apis";
            Long createdAt = System.currentTimeMillis();
            Long updatedAt = System.currentTimeMillis();

            ApiCategoryEntity entity = new ApiCategoryEntity(
                id, name, description, icon, color, sortOrder, slug, createdAt, updatedAt
            );

            assertEquals(id, entity.getId());
            assertEquals(name, entity.getName());
            assertEquals(description, entity.getDescription());
            assertEquals(icon, entity.getIcon());
            assertEquals(color, entity.getColor());
            assertEquals(sortOrder, entity.getSortOrder());
            assertEquals(slug, entity.getSlug());
            assertEquals(createdAt, entity.getCreatedAt());
            assertEquals(updatedAt, entity.getUpdatedAt());
        }

        @Test
        @DisplayName("Should allow null values in all-args constructor")
        void shouldAllowNullValuesInConstructor() {
            ApiCategoryEntity entity = new ApiCategoryEntity(
                null, null, null, null, null, null, null, null, null
            );

            assertNotNull(entity);
            assertNull(entity.getId());
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
            ApiCategoryEntity entity = new ApiCategoryEntity();
            UUID id = UUID.randomUUID();

            entity.setId(id);

            assertEquals(id, entity.getId());
        }

        @Test
        @DisplayName("Should set and get name")
        void shouldSetAndGetName() {
            ApiCategoryEntity entity = new ApiCategoryEntity();
            String name = "Data APIs";

            entity.setName(name);

            assertEquals(name, entity.getName());
        }

        @Test
        @DisplayName("Should set and get description")
        void shouldSetAndGetDescription() {
            ApiCategoryEntity entity = new ApiCategoryEntity();
            String description = "APIs for data processing";

            entity.setDescription(description);

            assertEquals(description, entity.getDescription());
        }

        @Test
        @DisplayName("Should set and get icon")
        void shouldSetAndGetIcon() {
            ApiCategoryEntity entity = new ApiCategoryEntity();
            String icon = "data-icon";

            entity.setIcon(icon);

            assertEquals(icon, entity.getIcon());
        }

        @Test
        @DisplayName("Should set and get color")
        void shouldSetAndGetColor() {
            ApiCategoryEntity entity = new ApiCategoryEntity();
            String color = "#FF5722";

            entity.setColor(color);

            assertEquals(color, entity.getColor());
        }

        @Test
        @DisplayName("Should set and get sortOrder")
        void shouldSetAndGetSortOrder() {
            ApiCategoryEntity entity = new ApiCategoryEntity();
            Integer sortOrder = 5;

            entity.setSortOrder(sortOrder);

            assertEquals(sortOrder, entity.getSortOrder());
        }

        @Test
        @DisplayName("Should set and get slug")
        void shouldSetAndGetSlug() {
            ApiCategoryEntity entity = new ApiCategoryEntity();
            String slug = "my-category-slug";

            entity.setSlug(slug);

            assertEquals(slug, entity.getSlug());
        }

        @Test
        @DisplayName("Should set and get createdAt")
        void shouldSetAndGetCreatedAt() {
            ApiCategoryEntity entity = new ApiCategoryEntity();
            Long createdAt = 1700000000000L;

            entity.setCreatedAt(createdAt);

            assertEquals(createdAt, entity.getCreatedAt());
        }

        @Test
        @DisplayName("Should set and get updatedAt")
        void shouldSetAndGetUpdatedAt() {
            ApiCategoryEntity entity = new ApiCategoryEntity();
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
            ApiCategoryEntity entity = new ApiCategoryEntity();
            entity.setId(UUID.randomUUID());
            entity.setName("Test");

            entity.setId(null);
            entity.setName(null);

            assertNull(entity.getId());
            assertNull(entity.getName());
        }

        @Test
        @DisplayName("Should handle null sortOrder")
        void shouldHandleNullSortOrder() {
            ApiCategoryEntity entity = new ApiCategoryEntity();
            entity.setSortOrder(10);

            entity.setSortOrder(null);

            assertNull(entity.getSortOrder());
        }

        @Test
        @DisplayName("Should handle null timestamps")
        void shouldHandleNullTimestamps() {
            ApiCategoryEntity entity = new ApiCategoryEntity();
            entity.setCreatedAt(123L);
            entity.setUpdatedAt(456L);

            entity.setCreatedAt(null);
            entity.setUpdatedAt(null);

            assertNull(entity.getCreatedAt());
            assertNull(entity.getUpdatedAt());
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
            ApiCategoryEntity entity = new ApiCategoryEntity();

            entity.setName("");
            entity.setDescription("");
            entity.setSlug("");

            assertEquals("", entity.getName());
            assertEquals("", entity.getDescription());
            assertEquals("", entity.getSlug());
        }

        @Test
        @DisplayName("Should handle very long strings")
        void shouldHandleLongStrings() {
            ApiCategoryEntity entity = new ApiCategoryEntity();
            String longString = "a".repeat(10000);

            entity.setName(longString);
            entity.setDescription(longString);

            assertEquals(longString, entity.getName());
            assertEquals(longString, entity.getDescription());
        }

        @Test
        @DisplayName("Should handle special characters in strings")
        void shouldHandleSpecialCharacters() {
            ApiCategoryEntity entity = new ApiCategoryEntity();
            String specialChars = "Test with émojis 🚀 and ünïcödé";

            entity.setName(specialChars);
            entity.setDescription(specialChars);

            assertEquals(specialChars, entity.getName());
            assertEquals(specialChars, entity.getDescription());
        }

        @Test
        @DisplayName("Should handle zero sort order")
        void shouldHandleZeroSortOrder() {
            ApiCategoryEntity entity = new ApiCategoryEntity();

            entity.setSortOrder(0);

            assertEquals(0, entity.getSortOrder());
        }

        @Test
        @DisplayName("Should handle negative sort order")
        void shouldHandleNegativeSortOrder() {
            ApiCategoryEntity entity = new ApiCategoryEntity();

            entity.setSortOrder(-1);

            assertEquals(-1, entity.getSortOrder());
        }

        @Test
        @DisplayName("Should handle timestamp edge values")
        void shouldHandleTimestampEdgeValues() {
            ApiCategoryEntity entity = new ApiCategoryEntity();

            entity.setCreatedAt(0L);
            entity.setUpdatedAt(Long.MAX_VALUE);

            assertEquals(0L, entity.getCreatedAt());
            assertEquals(Long.MAX_VALUE, entity.getUpdatedAt());
        }
    }
}
