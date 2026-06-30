package com.apimarketplace.catalog.integration;

import com.apimarketplace.catalog.domain.ApiCategoryEntity;
import com.apimarketplace.catalog.domain.ApiSubcategoryEntity;
import com.apimarketplace.catalog.domain.ToolCategoryEntity;
import com.apimarketplace.catalog.domain.ToolNameEntity;
import com.apimarketplace.catalog.repository.ApiCategoryRepository;
import com.apimarketplace.catalog.repository.ApiSubcategoryRepository;
import com.apimarketplace.catalog.repository.ToolCategoryRepository;
import com.apimarketplace.catalog.repository.ToolNameRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for ToolCategoryController.
 * Tests tool category and tool name CRUD endpoints with real database.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfig.class)
@org.springframework.test.context.TestPropertySource(properties = "catalog.admin-token=test-admin-token")
@DisplayName("ToolCategoryController Integration Tests")
class ToolCategoryControllerIntegrationTest {

    private static final String ADMIN_TOKEN = "test-admin-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ToolCategoryRepository toolCategoryRepository;

    @Autowired
    private ToolNameRepository toolNameRepository;

    @Autowired
    private ApiCategoryRepository apiCategoryRepository;

    @Autowired
    private ApiSubcategoryRepository apiSubcategoryRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private ToolCategoryEntity activeCategory;
    private ToolCategoryEntity inactiveCategory;
    private ApiSubcategoryEntity apiSubcategory;

    @BeforeEach
    void setUp() {
        toolNameRepository.deleteAll();
        toolCategoryRepository.deleteAll();
        apiSubcategoryRepository.deleteAll();
        apiCategoryRepository.deleteAll();

        activeCategory = saveToolCategory("HTTP Tools", "HTTP request tools", "http-tools", 1, true);
        inactiveCategory = saveToolCategory("Deprecated Tools", "Old tools", "deprecated-tools", 99, false);

        // Set up API category and subcategory for subcategory-based lookups
        ApiCategoryEntity apiCategory = new ApiCategoryEntity();
        apiCategory.setName("Integration Test Category");
        apiCategory.setSlug("integration-test-category");
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
    @DisplayName("GET /api/tool-categories")
    class GetAllToolCategoriesTests {

        @Test
        @DisplayName("should return only active tool categories")
        void shouldReturnOnlyActiveToolCategories() throws Exception {
            mockMvc.perform(get("/api/tool-categories")
                            .header("X-User-ID", "test-user"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].name", is("HTTP Tools")))
                    .andExpect(jsonPath("$[0].isActive", is(true)));
        }

        @Test
        @DisplayName("should return empty list when no active categories exist")
        void shouldReturnEmptyWhenNoActiveCategories() throws Exception {
            toolNameRepository.deleteAll();
            toolCategoryRepository.deleteAll();

            mockMvc.perform(get("/api/tool-categories"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        @DisplayName("should return categories ordered by sort order")
        void shouldReturnCategoriesOrderedBySortOrder() throws Exception {
            saveToolCategory("Database Tools", "DB tools", "database-tools", 0, true);

            mockMvc.perform(get("/api/tool-categories"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].name", is("Database Tools")))
                    .andExpect(jsonPath("$[1].name", is("HTTP Tools")));
        }
    }

    @Nested
    @DisplayName("GET /api/tool-categories/by-name/{name}")
    class GetToolCategoryByNameTests {

        @Test
        @DisplayName("should return tool category by name")
        void shouldReturnToolCategoryByName() throws Exception {
            mockMvc.perform(get("/api/tool-categories/by-name/{name}", "HTTP Tools"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name", is("HTTP Tools")))
                    .andExpect(jsonPath("$.slug", is("http-tools")));
        }

        @Test
        @DisplayName("should return 404 for non-existent category name")
        void shouldReturn404ForNonExistentCategoryName() throws Exception {
            mockMvc.perform(get("/api/tool-categories/by-name/{name}", "Non Existent"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET /api/tool-categories/{categoryId}/tool-names")
    class GetToolNamesByCategoryTests {

        @Test
        @DisplayName("should return tool names for category")
        void shouldReturnToolNamesForCategory() throws Exception {
            saveToolName("GET Request", "HTTP GET", activeCategory.getId(), null, "external", true);
            saveToolName("POST Request", "HTTP POST", activeCategory.getId(), null, "external", true);

            mockMvc.perform(get("/api/tool-categories/{categoryId}/tool-names", activeCategory.getId())
                            .header("X-User-ID", "test-user"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].name", is("GET Request")))
                    .andExpect(jsonPath("$[1].name", is("POST Request")));
        }

        @Test
        @DisplayName("should filter tool names by run scopes")
        void shouldFilterToolNamesByRunScopes() throws Exception {
            saveToolName("External Tool", "External", activeCategory.getId(), null, "external", true);
            saveToolName("Internal Tool", "Internal", activeCategory.getId(), null, "internal", true);

            mockMvc.perform(get("/api/tool-categories/{categoryId}/tool-names", activeCategory.getId())
                            .param("runScopes", "external"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].name", is("External Tool")));
        }

        @Test
        @DisplayName("should return empty list when no tool names exist for category")
        void shouldReturnEmptyWhenNoToolNames() throws Exception {
            mockMvc.perform(get("/api/tool-categories/{categoryId}/tool-names", activeCategory.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        @DisplayName("should exclude inactive tool names")
        void shouldExcludeInactiveToolNames() throws Exception {
            saveToolName("Active Tool", "Active", activeCategory.getId(), null, "external", true);
            saveToolName("Inactive Tool", "Inactive", activeCategory.getId(), null, "external", false);

            mockMvc.perform(get("/api/tool-categories/{categoryId}/tool-names", activeCategory.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].name", is("Active Tool")));
        }
    }

    @Nested
    @DisplayName("GET /api/tool-categories/tool-names")
    class GetAllToolNamesTests {

        @Test
        @DisplayName("should return all active tool names")
        void shouldReturnAllActiveToolNames() throws Exception {
            saveToolName("Tool A", "A", activeCategory.getId(), null, "external", true);
            saveToolName("Tool B", "B", activeCategory.getId(), null, "internal", true);

            mockMvc.perform(get("/api/tool-categories/tool-names"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)));
        }

        @Test
        @DisplayName("should filter tool names by run scopes")
        void shouldFilterByRunScopes() throws Exception {
            saveToolName("External Tool", "Ext", activeCategory.getId(), null, "external", true);
            saveToolName("Internal Tool", "Int", activeCategory.getId(), null, "internal", true);

            mockMvc.perform(get("/api/tool-categories/tool-names")
                            .param("runScopes", "internal"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].name", is("Internal Tool")));
        }
    }

    @Nested
    @DisplayName("GET /api/tool-categories/by-subcategory/{subcategoryId}")
    class GetToolCategoriesBySubcategoryTests {

        @Test
        @DisplayName("should return categories for subcategory")
        void shouldReturnCategoriesForSubcategory() throws Exception {
            saveToolName("REST GET", "REST GET tool", activeCategory.getId(), apiSubcategory.getId(), "external", true);

            mockMvc.perform(get("/api/tool-categories/by-subcategory/{subcategoryId}", apiSubcategory.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].name", is("HTTP Tools")));
        }

        @Test
        @DisplayName("should return empty list when no tools in subcategory")
        void shouldReturnEmptyForSubcategoryWithNoTools() throws Exception {
            mockMvc.perform(get("/api/tool-categories/by-subcategory/{subcategoryId}", apiSubcategory.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }
    }

    @Nested
    @DisplayName("GET /api/tool-categories/by-subcategory-name/{subcategoryName}")
    class GetToolCategoriesBySubcategoryNameTests {

        @Test
        @DisplayName("should return categories by subcategory name")
        void shouldReturnCategoriesBySubcategoryName() throws Exception {
            saveToolName("REST Tool", "REST tool", activeCategory.getId(), apiSubcategory.getId(), "external", true);

            mockMvc.perform(get("/api/tool-categories/by-subcategory-name/{subcategoryName}", "REST APIs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].name", is("HTTP Tools")));
        }

        @Test
        @DisplayName("should support pagination")
        void shouldSupportPagination() throws Exception {
            saveToolName("REST Tool", "REST tool", activeCategory.getId(), apiSubcategory.getId(), "external", true);

            mockMvc.perform(get("/api/tool-categories/by-subcategory-name/{subcategoryName}", "REST APIs")
                            .param("page", "0")
                            .param("size", "10"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("should return empty for non-existent subcategory name")
        void shouldReturnEmptyForNonExistentSubcategoryName() throws Exception {
            mockMvc.perform(get("/api/tool-categories/by-subcategory-name/{subcategoryName}", "Non Existent"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }
    }

    @Nested
    @DisplayName("GET /api/tool-categories/subcategory/{subcategoryId}/tool-names")
    class GetToolNamesBySubcategoryTests {

        @Test
        @DisplayName("should return tool names for subcategory")
        void shouldReturnToolNamesForSubcategory() throws Exception {
            saveToolName("REST GET", "GET tool", activeCategory.getId(), apiSubcategory.getId(), "external", true);
            saveToolName("REST POST", "POST tool", activeCategory.getId(), apiSubcategory.getId(), "external", true);

            mockMvc.perform(get("/api/tool-categories/subcategory/{subcategoryId}/tool-names", apiSubcategory.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)));
        }

        @Test
        @DisplayName("should return empty when no tool names for subcategory")
        void shouldReturnEmptyWhenNoToolNames() throws Exception {
            mockMvc.perform(get("/api/tool-categories/subcategory/{subcategoryId}/tool-names", apiSubcategory.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }
    }

    @Nested
    @DisplayName("GET /api/tool-categories/{toolCategoryId}/subcategory/{subcategoryId}/tool-names")
    class GetToolNamesByToolCategoryAndSubcategoryTests {

        @Test
        @DisplayName("should return tool names filtered by category and subcategory")
        void shouldReturnFilteredToolNames() throws Exception {
            saveToolName("Matching Tool", "Matches both", activeCategory.getId(), apiSubcategory.getId(), "external", true);
            saveToolName("Different Subcategory Tool", "Different sub", activeCategory.getId(), null, "external", true);

            mockMvc.perform(get("/api/tool-categories/{toolCategoryId}/subcategory/{subcategoryId}/tool-names",
                            activeCategory.getId(), apiSubcategory.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].name", is("Matching Tool")));
        }
    }

    @Nested
    @DisplayName("GET /api/tool-categories/tool-names/search")
    class SearchToolNamesTests {

        @Test
        @DisplayName("should search tool names by query")
        void shouldSearchToolNamesByQuery() throws Exception {
            saveToolName("HTTP GET Request", "HTTP GET", activeCategory.getId(), null, "external", true);
            saveToolName("HTTP POST Request", "HTTP POST", activeCategory.getId(), null, "external", true);
            saveToolName("SQL Query", "SQL query", activeCategory.getId(), null, "external", true);

            mockMvc.perform(get("/api/tool-categories/tool-names/search")
                            .param("q", "HTTP"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)));
        }

        @Test
        @DisplayName("should return empty for no matching search results")
        void shouldReturnEmptyForNoMatch() throws Exception {
            saveToolName("Tool A", "A", activeCategory.getId(), null, "external", true);

            mockMvc.perform(get("/api/tool-categories/tool-names/search")
                            .param("q", "nonexistent"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        @DisplayName("should search case-insensitively")
        void shouldSearchCaseInsensitively() throws Exception {
            saveToolName("HTTP Tool", "HTTP", activeCategory.getId(), null, "external", true);

            mockMvc.perform(get("/api/tool-categories/tool-names/search")
                            .param("q", "http"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));
        }
    }

    @Nested
    @DisplayName("GET /api/tool-categories/tool-names/by-name/{name}")
    class GetToolNamesByNameTests {

        @Test
        @DisplayName("should return tool names by exact name")
        void shouldReturnToolNamesByName() throws Exception {
            saveToolName("HTTP GET", "GET", activeCategory.getId(), null, "external", true);

            mockMvc.perform(get("/api/tool-categories/tool-names/by-name/{name}", "HTTP GET"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].name", is("HTTP GET")));
        }

        @Test
        @DisplayName("should return 404 when no tool names match")
        void shouldReturn404WhenNoMatch() throws Exception {
            mockMvc.perform(get("/api/tool-categories/tool-names/by-name/{name}", "Non Existent"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET /api/tool-categories/tool-names/by-name/{name}/category/{categoryId}")
    class GetToolNameByNameAndCategoryTests {

        @Test
        @DisplayName("should return tool name by name and category")
        void shouldReturnToolNameByNameAndCategory() throws Exception {
            saveToolName("HTTP GET", "GET", activeCategory.getId(), null, "external", true);

            mockMvc.perform(get("/api/tool-categories/tool-names/by-name/{name}/category/{categoryId}",
                            "HTTP GET", activeCategory.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name", is("HTTP GET")));
        }

        @Test
        @DisplayName("should return 404 when tool name not found in category")
        void shouldReturn404WhenNotFoundInCategory() throws Exception {
            mockMvc.perform(get("/api/tool-categories/tool-names/by-name/{name}/category/{categoryId}",
                            "Non Existent", activeCategory.getId()))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("POST /api/tool-categories")
    class CreateToolCategoryTests {

        @Test
        @DisplayName("should create a new tool category")
        void shouldCreateToolCategory() throws Exception {
            ToolCategoryEntity newCategory = new ToolCategoryEntity();
            newCategory.setName("New Category");
            newCategory.setDescription("New category description");
            newCategory.setIsActive(true);

            mockMvc.perform(post("/api/tool-categories")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(newCategory))
                            .header("X-Internal-Admin-Token", ADMIN_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name", is("New Category")))
                    .andExpect(jsonPath("$.description", is("New category description")))
                    .andExpect(jsonPath("$.slug", notNullValue()))
                    .andExpect(jsonPath("$.id", notNullValue()));
        }

        @Test
        @DisplayName("should set default values for new category")
        void shouldSetDefaultValues() throws Exception {
            ToolCategoryEntity newCategory = new ToolCategoryEntity();
            newCategory.setName("Defaults Category");
            newCategory.setIsActive(true);

            mockMvc.perform(post("/api/tool-categories")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(newCategory))
                            .header("X-Internal-Admin-Token", ADMIN_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sortOrder", is(999)))
                    .andExpect(jsonPath("$.icon", is("default")))
                    .andExpect(jsonPath("$.color", is("#6B7280")));
        }
    }

    @Nested
    @DisplayName("POST /api/tool-categories/tool-names")
    class CreateToolNameTests {

        @Test
        @DisplayName("should create a new tool name")
        void shouldCreateToolName() throws Exception {
            ToolNameEntity newToolName = new ToolNameEntity();
            newToolName.setName("New Tool");
            newToolName.setDescription("New tool description");
            newToolName.setToolCategoryId(activeCategory.getId());
            newToolName.setRunScope("external");
            newToolName.setIsActive(true);

            mockMvc.perform(post("/api/tool-categories/tool-names")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(newToolName))
                            .header("X-Internal-Admin-Token", ADMIN_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name", is("New Tool")))
                    .andExpect(jsonPath("$.description", is("New tool description")))
                    .andExpect(jsonPath("$.slug", notNullValue()))
                    .andExpect(jsonPath("$.id", notNullValue()));
        }
    }

    @Nested
    @DisplayName("PUT /api/tool-categories/{categoryId}")
    class UpdateToolCategoryTests {

        @Test
        @DisplayName("should update tool category")
        void shouldUpdateToolCategory() throws Exception {
            activeCategory.setDescription("Updated description");
            activeCategory.setUpdatedAt(System.currentTimeMillis());

            mockMvc.perform(put("/api/tool-categories/{categoryId}", activeCategory.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(activeCategory))
                            .header("X-Internal-Admin-Token", ADMIN_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.description", is("Updated description")));
        }
    }

    @Nested
    @DisplayName("PUT /api/tool-categories/tool-names/{toolNameId}")
    class UpdateToolNameTests {

        @Test
        @DisplayName("should update tool name")
        void shouldUpdateToolName() throws Exception {
            ToolNameEntity toolName = saveToolName("Original Name", "Original", activeCategory.getId(), null, "external", true);
            toolName.setDescription("Updated description");
            toolName.setUpdatedAt(System.currentTimeMillis());

            mockMvc.perform(put("/api/tool-categories/tool-names/{toolNameId}", toolName.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(toolName))
                            .header("X-Internal-Admin-Token", ADMIN_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.description", is("Updated description")));
        }
    }

    @Nested
    @DisplayName("DELETE /api/tool-categories/{categoryId}")
    class DeleteToolCategoryTests {

        @Test
        @DisplayName("should soft delete tool category")
        void shouldSoftDeleteToolCategory() throws Exception {
            mockMvc.perform(delete("/api/tool-categories/{categoryId}", activeCategory.getId())
                            .header("X-Internal-Admin-Token", ADMIN_TOKEN))
                    .andExpect(status().isOk());

            // After soft delete, category should no longer appear in active list
            mockMvc.perform(get("/api/tool-categories"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }
    }

    @Nested
    @DisplayName("DELETE /api/tool-categories/tool-names/{toolNameId}")
    class DeleteToolNameTests {

        @Test
        @DisplayName("should soft delete tool name")
        void shouldSoftDeleteToolName() throws Exception {
            ToolNameEntity toolName = saveToolName("To Delete", "Delete me", activeCategory.getId(), null, "external", true);

            mockMvc.perform(delete("/api/tool-categories/tool-names/{toolNameId}", toolName.getId())
                            .header("X-Internal-Admin-Token", ADMIN_TOKEN))
                    .andExpect(status().isOk());

            // After soft delete, tool name should no longer appear in active list
            mockMvc.perform(get("/api/tool-categories/{categoryId}/tool-names", activeCategory.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
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

    private ToolNameEntity saveToolName(String name, String description, UUID categoryId,
                                         UUID subcategoryId, String runScope, boolean isActive) {
        ToolNameEntity toolName = new ToolNameEntity();
        toolName.setName(name);
        toolName.setDescription(description);
        toolName.setToolCategoryId(categoryId);
        toolName.setSubcategoryId(subcategoryId);
        toolName.setRunScope(runScope);
        toolName.setRequiresUserCredentials(false);
        toolName.setSlug(name.toLowerCase().replace(" ", "-"));
        toolName.setIsActive(isActive);
        toolName.setCreatedAt(System.currentTimeMillis());
        toolName.setUpdatedAt(System.currentTimeMillis());
        return toolNameRepository.save(toolName);
    }
}
