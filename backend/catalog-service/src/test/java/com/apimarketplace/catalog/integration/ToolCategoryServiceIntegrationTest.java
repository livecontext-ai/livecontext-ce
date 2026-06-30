package com.apimarketplace.catalog.integration;

import com.apimarketplace.catalog.domain.ApiCategoryEntity;
import com.apimarketplace.catalog.domain.ApiSubcategoryEntity;
import com.apimarketplace.catalog.domain.ToolCategoryEntity;
import com.apimarketplace.catalog.domain.ToolNameEntity;
import com.apimarketplace.catalog.repository.ApiCategoryRepository;
import com.apimarketplace.catalog.repository.ApiSubcategoryRepository;
import com.apimarketplace.catalog.repository.ToolCategoryRepository;
import com.apimarketplace.catalog.repository.ToolNameRepository;
import com.apimarketplace.catalog.service.ToolCategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for ToolCategoryService.
 * Tests service business logic with real database interactions.
 */
@IntegrationTest
@Import(IntegrationTestConfig.class)
@DisplayName("ToolCategoryService Integration Tests")
class ToolCategoryServiceIntegrationTest {

    @Autowired
    private ToolCategoryService toolCategoryService;

    @Autowired
    private ToolCategoryRepository toolCategoryRepository;

    @Autowired
    private ToolNameRepository toolNameRepository;

    @Autowired
    private ApiCategoryRepository apiCategoryRepository;

    @Autowired
    private ApiSubcategoryRepository apiSubcategoryRepository;

    private ToolCategoryEntity httpCategory;
    private ToolCategoryEntity dbCategory;
    private ApiSubcategoryEntity apiSubcategory;

    @BeforeEach
    void setUp() {
        toolNameRepository.deleteAll();
        toolCategoryRepository.deleteAll();
        apiSubcategoryRepository.deleteAll();
        apiCategoryRepository.deleteAll();

        httpCategory = saveToolCategory("HTTP Tools", "HTTP request tools", "http-tools", 1, true);
        dbCategory = saveToolCategory("Database Tools", "DB tools", "database-tools", 2, true);
        saveToolCategory("Inactive Category", "Inactive", "inactive-cat", 99, false);

        // Set up API category/subcategory hierarchy for subcategory tests
        ApiCategoryEntity apiCategory = new ApiCategoryEntity();
        apiCategory.setName("APIs");
        apiCategory.setSlug("apis");
        apiCategory.setSortOrder(0);
        apiCategory.setCreatedAt(System.currentTimeMillis());
        apiCategory.setUpdatedAt(System.currentTimeMillis());
        apiCategory = apiCategoryRepository.save(apiCategory);

        apiSubcategory = new ApiSubcategoryEntity();
        apiSubcategory.setCategoryId(apiCategory.getId());
        apiSubcategory.setName("REST APIs");
        apiSubcategory.setSlug("rest-apis");
        apiSubcategory.setSortOrder(0);
        apiSubcategory.setCreatedAt(System.currentTimeMillis());
        apiSubcategory.setUpdatedAt(System.currentTimeMillis());
        apiSubcategory = apiSubcategoryRepository.save(apiSubcategory);
    }

    @Nested
    @DisplayName("getAllToolCategories")
    class GetAllToolCategoriesTests {

        @Test
        @DisplayName("should return only active categories ordered by sort order")
        void shouldReturnOnlyActiveCategoriesOrdered() {
            List<ToolCategoryEntity> result = toolCategoryService.getAllToolCategories();

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getName()).isEqualTo("HTTP Tools");
            assertThat(result.get(1).getName()).isEqualTo("Database Tools");
            assertThat(result).allMatch(c -> Boolean.TRUE.equals(c.getIsActive()));
        }
    }

    @Nested
    @DisplayName("getToolCategoryByName")
    class GetToolCategoryByNameTests {

        @Test
        @DisplayName("should find category by name")
        void shouldFindByName() {
            Optional<ToolCategoryEntity> result = toolCategoryService.getToolCategoryByName("HTTP Tools");

            assertThat(result).isPresent();
            assertThat(result.get().getSlug()).isEqualTo("http-tools");
        }

        @Test
        @DisplayName("should return empty for non-existent name")
        void shouldReturnEmptyForNonExistentName() {
            Optional<ToolCategoryEntity> result = toolCategoryService.getToolCategoryByName("Non Existent");

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getToolCategoryBySlug")
    class GetToolCategoryBySlugTests {

        @Test
        @DisplayName("should find category by slug")
        void shouldFindBySlug() {
            Optional<ToolCategoryEntity> result = toolCategoryService.getToolCategoryBySlug("http-tools");

            assertThat(result).isPresent();
            assertThat(result.get().getName()).isEqualTo("HTTP Tools");
        }
    }

    @Nested
    @DisplayName("getToolNamesByCategory")
    class GetToolNamesByCategoryTests {

        @Test
        @DisplayName("should return active tool names for category")
        void shouldReturnActiveToolNamesForCategory() {
            saveToolName("GET Request", httpCategory.getId(), null, "external", true);
            saveToolName("POST Request", httpCategory.getId(), null, "external", true);
            saveToolName("Inactive Tool", httpCategory.getId(), null, "external", false);

            List<ToolNameEntity> result = toolCategoryService.getToolNamesByCategory(httpCategory.getId());

            assertThat(result).hasSize(2);
            assertThat(result).extracting(ToolNameEntity::getName)
                    .containsExactly("GET Request", "POST Request");
        }

        @Test
        @DisplayName("should return empty for category with no tools")
        void shouldReturnEmptyForCategoryWithNoTools() {
            List<ToolNameEntity> result = toolCategoryService.getToolNamesByCategory(dbCategory.getId());

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getToolNamesByCategoryAndRunScopes")
    class GetToolNamesByCategoryAndRunScopesTests {

        @Test
        @DisplayName("should filter by run scopes")
        void shouldFilterByRunScopes() {
            saveToolName("External Tool", httpCategory.getId(), null, "external", true);
            saveToolName("Internal Tool", httpCategory.getId(), null, "internal", true);
            saveToolName("Both Tool", httpCategory.getId(), null, "both", true);

            List<ToolNameEntity> result = toolCategoryService.getToolNamesByCategoryAndRunScopes(
                    httpCategory.getId(), List.of("external", "both"));

            assertThat(result).hasSize(2);
            assertThat(result).extracting(ToolNameEntity::getName)
                    .containsExactlyInAnyOrder("Both Tool", "External Tool");
        }
    }

    @Nested
    @DisplayName("getToolNamesByRunScopes")
    class GetToolNamesByRunScopesTests {

        @Test
        @DisplayName("should return tool names by run scopes across categories")
        void shouldReturnToolNamesByRunScopes() {
            saveToolName("HTTP External", httpCategory.getId(), null, "external", true);
            saveToolName("DB External", dbCategory.getId(), null, "external", true);
            saveToolName("HTTP Internal", httpCategory.getId(), null, "internal", true);

            List<ToolNameEntity> result = toolCategoryService.getToolNamesByRunScopes(List.of("external"));

            assertThat(result).hasSize(2);
        }
    }

    @Nested
    @DisplayName("createToolCategory")
    class CreateToolCategoryTests {

        @Test
        @DisplayName("should create category with default values")
        void shouldCreateWithDefaults() {
            ToolCategoryEntity newCategory = new ToolCategoryEntity();
            newCategory.setName("New Category");
            newCategory.setIsActive(true);

            ToolCategoryEntity result = toolCategoryService.createToolCategory(newCategory);

            assertThat(result.getId()).isNotNull();
            assertThat(result.getName()).isEqualTo("New Category");
            assertThat(result.getSortOrder()).isEqualTo(999);
            assertThat(result.getIcon()).isEqualTo("default");
            assertThat(result.getColor()).isEqualTo("#6B7280");
            assertThat(result.getSlug()).isNotNull().isNotBlank();
            assertThat(result.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("should generate unique slug")
        void shouldGenerateUniqueSlug() {
            ToolCategoryEntity cat1 = new ToolCategoryEntity();
            cat1.setName("Unique Test");
            cat1.setIsActive(true);
            ToolCategoryEntity saved1 = toolCategoryService.createToolCategory(cat1);

            ToolCategoryEntity cat2 = new ToolCategoryEntity();
            cat2.setName("Unique Test Again");
            cat2.setSlug(saved1.getSlug()); // intentionally use same slug
            cat2.setIsActive(true);
            ToolCategoryEntity saved2 = toolCategoryService.createToolCategory(cat2);

            assertThat(saved2.getSlug()).isNotEqualTo(saved1.getSlug());
        }

        @Test
        @DisplayName("should preserve provided values")
        void shouldPreserveProvidedValues() {
            ToolCategoryEntity newCategory = new ToolCategoryEntity();
            newCategory.setName("Custom Category");
            newCategory.setSortOrder(5);
            newCategory.setIcon("custom-icon");
            newCategory.setColor("#FF0000");
            newCategory.setIsActive(true);

            ToolCategoryEntity result = toolCategoryService.createToolCategory(newCategory);

            assertThat(result.getSortOrder()).isEqualTo(5);
            assertThat(result.getIcon()).isEqualTo("custom-icon");
            assertThat(result.getColor()).isEqualTo("#FF0000");
        }
    }

    @Nested
    @DisplayName("createToolName")
    class CreateToolNameTests {

        @Test
        @DisplayName("should create tool name with slug")
        void shouldCreateWithSlug() {
            ToolNameEntity newToolName = new ToolNameEntity();
            newToolName.setName("New Tool");
            newToolName.setDescription("A new tool");
            newToolName.setToolCategoryId(httpCategory.getId());
            newToolName.setRunScope("external");
            newToolName.setIsActive(true);

            ToolNameEntity result = toolCategoryService.createToolName(newToolName);

            assertThat(result.getId()).isNotNull();
            assertThat(result.getName()).isEqualTo("New Tool");
            assertThat(result.getSlug()).isNotNull().isNotBlank();
            assertThat(result.getCreatedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("deleteToolCategory (soft delete)")
    class DeleteToolCategoryTests {

        @Test
        @DisplayName("should soft delete by setting isActive to false")
        void shouldSoftDeleteCategory() {
            assertThat(httpCategory.getIsActive()).isTrue();

            toolCategoryService.deleteToolCategory(httpCategory.getId());

            Optional<ToolCategoryEntity> result = toolCategoryRepository.findById(httpCategory.getId());
            assertThat(result).isPresent();
            assertThat(result.get().getIsActive()).isFalse();
        }

        @Test
        @DisplayName("should handle non-existent category gracefully")
        void shouldHandleNonExistentCategory() {
            // Should not throw
            toolCategoryService.deleteToolCategory(UUID.randomUUID());
        }
    }

    @Nested
    @DisplayName("deleteToolName (soft delete)")
    class DeleteToolNameTests {

        @Test
        @DisplayName("should soft delete by setting isActive to false")
        void shouldSoftDeleteToolName() {
            ToolNameEntity toolName = saveToolName("To Delete", httpCategory.getId(), null, "external", true);

            toolCategoryService.deleteToolName(toolName.getId());

            Optional<ToolNameEntity> result = toolNameRepository.findById(toolName.getId());
            assertThat(result).isPresent();
            assertThat(result.get().getIsActive()).isFalse();
        }
    }

    @Nested
    @DisplayName("getToolCategoriesBySubcategory")
    class GetToolCategoriesBySubcategoryTests {

        @Test
        @DisplayName("should return categories with tools in given subcategory")
        void shouldReturnCategoriesForSubcategory() {
            saveToolName("REST GET", httpCategory.getId(), apiSubcategory.getId(), "external", true);
            saveToolName("REST POST", httpCategory.getId(), apiSubcategory.getId(), "external", true);

            List<ToolCategoryEntity> result = toolCategoryService.getToolCategoriesBySubcategory(apiSubcategory.getId());

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("HTTP Tools");
        }

        @Test
        @DisplayName("should return multiple categories for subcategory")
        void shouldReturnMultipleCategoriesForSubcategory() {
            saveToolName("HTTP Tool", httpCategory.getId(), apiSubcategory.getId(), "external", true);
            saveToolName("DB Tool", dbCategory.getId(), apiSubcategory.getId(), "external", true);

            List<ToolCategoryEntity> result = toolCategoryService.getToolCategoriesBySubcategory(apiSubcategory.getId());

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("should exclude inactive categories from result")
        void shouldExcludeInactiveCategories() {
            ToolCategoryEntity inactiveCat = toolCategoryRepository.findByName("Inactive Category").orElseThrow();
            saveToolName("Inactive Tool", inactiveCat.getId(), apiSubcategory.getId(), "external", true);

            List<ToolCategoryEntity> result = toolCategoryService.getToolCategoriesBySubcategory(apiSubcategory.getId());

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getToolCategoriesBySubcategoryName")
    class GetToolCategoriesBySubcategoryNameTests {

        @Test
        @DisplayName("should find by subcategory name")
        void shouldFindBySubcategoryName() {
            saveToolName("REST Tool", httpCategory.getId(), apiSubcategory.getId(), "external", true);

            List<ToolCategoryEntity> result = toolCategoryService.getToolCategoriesBySubcategoryName("REST APIs");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("HTTP Tools");
        }

        @Test
        @DisplayName("should return empty for null name")
        void shouldReturnEmptyForNullName() {
            List<ToolCategoryEntity> result = toolCategoryService.getToolCategoriesBySubcategoryName(null);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty for blank name")
        void shouldReturnEmptyForBlankName() {
            List<ToolCategoryEntity> result = toolCategoryService.getToolCategoriesBySubcategoryName("   ");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should support pagination")
        void shouldSupportPagination() {
            // Create multiple categories with tools in the subcategory
            for (int i = 0; i < 5; i++) {
                ToolCategoryEntity cat = saveToolCategory(
                        "Paginated Category " + i, "desc", "paginated-" + i, 10 + i, true);
                saveToolName("Tool " + i, cat.getId(), apiSubcategory.getId(), "external", true);
            }

            List<ToolCategoryEntity> page0 = toolCategoryService.getToolCategoriesBySubcategoryName("REST APIs", 0, 3);
            List<ToolCategoryEntity> page1 = toolCategoryService.getToolCategoriesBySubcategoryName("REST APIs", 1, 3);

            assertThat(page0).hasSize(3);
            assertThat(page1).hasSize(2);
        }
    }

    @Nested
    @DisplayName("getToolNamesBySubcategory")
    class GetToolNamesBySubcategoryTests {

        @Test
        @DisplayName("should return tool names for subcategory ordered by name")
        void shouldReturnToolNamesForSubcategory() {
            saveToolName("REST POST", httpCategory.getId(), apiSubcategory.getId(), "external", true);
            saveToolName("REST GET", httpCategory.getId(), apiSubcategory.getId(), "external", true);

            List<ToolNameEntity> result = toolCategoryService.getToolNamesBySubcategory(apiSubcategory.getId());

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getName()).isEqualTo("REST GET");
            assertThat(result.get(1).getName()).isEqualTo("REST POST");
        }
    }

    @Nested
    @DisplayName("getToolNamesByToolCategoryAndSubcategory")
    class GetToolNamesByToolCategoryAndSubcategoryTests {

        @Test
        @DisplayName("should return tool names filtered by both category and subcategory")
        void shouldFilterByBothCategoryAndSubcategory() {
            saveToolName("HTTP REST", httpCategory.getId(), apiSubcategory.getId(), "external", true);
            saveToolName("DB REST", dbCategory.getId(), apiSubcategory.getId(), "external", true);
            saveToolName("HTTP Other", httpCategory.getId(), null, "external", true);

            List<ToolNameEntity> result = toolCategoryService.getToolNamesByToolCategoryAndSubcategory(
                    httpCategory.getId(), apiSubcategory.getId());

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("HTTP REST");
        }
    }

    @Nested
    @DisplayName("searchToolNames")
    class SearchToolNamesTests {

        @Test
        @DisplayName("should search case-insensitively")
        void shouldSearchCaseInsensitively() {
            saveToolName("HTTP GET Request", httpCategory.getId(), null, "external", true);
            saveToolName("HTTP POST Request", httpCategory.getId(), null, "external", true);
            saveToolName("SQL Query", dbCategory.getId(), null, "external", true);

            List<ToolNameEntity> result = toolCategoryService.searchToolNames("http");

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("should exclude inactive results")
        void shouldExcludeInactiveResults() {
            saveToolName("Active HTTP Tool", httpCategory.getId(), null, "external", true);
            saveToolName("Inactive HTTP Tool", httpCategory.getId(), null, "external", false);

            List<ToolNameEntity> result = toolCategoryService.searchToolNames("HTTP");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("Active HTTP Tool");
        }
    }

    @Nested
    @DisplayName("getToolNameByNameAndCategory")
    class GetToolNameByNameAndCategoryTests {

        @Test
        @DisplayName("should find tool name by name and category")
        void shouldFindByNameAndCategory() {
            saveToolName("Unique Tool", httpCategory.getId(), null, "external", true);
            saveToolName("Unique Tool", dbCategory.getId(), null, "external", true);

            Optional<ToolNameEntity> result = toolCategoryService.getToolNameByNameAndCategory(
                    "Unique Tool", httpCategory.getId());

            assertThat(result).isPresent();
            assertThat(result.get().getToolCategoryId()).isEqualTo(httpCategory.getId());
        }
    }

    @Nested
    @DisplayName("getAllToolNames")
    class GetAllToolNamesTests {

        @Test
        @DisplayName("should return all active tool names ordered by name")
        void shouldReturnAllActiveToolNames() {
            saveToolName("Zebra Tool", httpCategory.getId(), null, "external", true);
            saveToolName("Alpha Tool", dbCategory.getId(), null, "external", true);
            saveToolName("Inactive Tool", httpCategory.getId(), null, "external", false);

            List<ToolNameEntity> result = toolCategoryService.getAllToolNames();

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getName()).isEqualTo("Alpha Tool");
            assertThat(result.get(1).getName()).isEqualTo("Zebra Tool");
        }
    }

    // ===== Helper methods =====

    private ToolCategoryEntity saveToolCategory(String name, String description, String slug, int sortOrder, boolean isActive) {
        ToolCategoryEntity category = new ToolCategoryEntity();
        category.setName(name);
        category.setDescription(description);
        category.setSlug(slug);
        category.setSortOrder(sortOrder);
        category.setIsActive(isActive);
        category.setIcon("default");
        category.setColor("#6B7280");
        category.setCreatedAt(System.currentTimeMillis());
        category.setUpdatedAt(System.currentTimeMillis());
        return toolCategoryRepository.save(category);
    }

    private ToolNameEntity saveToolName(String name, UUID categoryId, UUID subcategoryId,
                                         String runScope, boolean isActive) {
        ToolNameEntity toolName = new ToolNameEntity();
        toolName.setName(name);
        toolName.setDescription(name + " description");
        toolName.setToolCategoryId(categoryId);
        toolName.setSubcategoryId(subcategoryId);
        toolName.setRunScope(runScope);
        toolName.setRequiresUserCredentials(false);
        toolName.setSlug(name.toLowerCase().replace(" ", "-") + "-" + UUID.randomUUID().toString().substring(0, 4));
        toolName.setIsActive(isActive);
        toolName.setCreatedAt(System.currentTimeMillis());
        toolName.setUpdatedAt(System.currentTimeMillis());
        return toolNameRepository.save(toolName);
    }
}
