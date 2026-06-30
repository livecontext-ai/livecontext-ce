package com.apimarketplace.agent.registry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ToolCategory enum.
 */
@DisplayName("ToolCategory")
class ToolCategoryTest {

    @Test
    @DisplayName("should have 12 categories (added IMAGE_GENERATION)")
    void shouldHaveTwelveCategories() {
        assertThat(ToolCategory.values()).hasSize(12);
    }

    @ParameterizedTest
    @EnumSource(ToolCategory.class)
    @DisplayName("all categories should have non-null slug, displayName, and description")
    void allCategoriesShouldHaveFields(ToolCategory category) {
        assertThat(category.getSlug()).isNotNull().isNotBlank();
        assertThat(category.getDisplayName()).isNotNull().isNotBlank();
        assertThat(category.getDescription()).isNotNull().isNotBlank();
    }

    @Nested
    @DisplayName("fromSlug()")
    class FromSlugTests {

        @Test
        @DisplayName("should find category by exact slug")
        void shouldFindByExactSlug() {
            assertThat(ToolCategory.fromSlug("search")).isEqualTo(ToolCategory.SEARCH);
            assertThat(ToolCategory.fromSlug("workflow")).isEqualTo(ToolCategory.WORKFLOW);
            assertThat(ToolCategory.fromSlug("help")).isEqualTo(ToolCategory.HELP);
            assertThat(ToolCategory.fromSlug("utility")).isEqualTo(ToolCategory.UTILITY);
        }

        @Test
        @DisplayName("should find category case-insensitively")
        void shouldFindCaseInsensitive() {
            assertThat(ToolCategory.fromSlug("SEARCH")).isEqualTo(ToolCategory.SEARCH);
            assertThat(ToolCategory.fromSlug("Workflow")).isEqualTo(ToolCategory.WORKFLOW);
        }

        @Test
        @DisplayName("should return null for unknown slug")
        void shouldReturnNullForUnknown() {
            assertThat(ToolCategory.fromSlug("nonexistent")).isNull();
        }

        @Test
        @DisplayName("should return null for null slug")
        void shouldReturnNullForNull() {
            assertThat(ToolCategory.fromSlug(null)).isNull();
        }
    }
}
