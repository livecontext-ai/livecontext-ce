package com.apimarketplace.catalog.integration;

import com.apimarketplace.catalog.domain.ApiCategoryEntity;
import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiSubcategoryEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.ApiToolParameterEntity;
import com.apimarketplace.catalog.domain.ToolCategoryEntity;
import com.apimarketplace.catalog.domain.ToolNameEntity;
import com.apimarketplace.catalog.repository.ApiCategoryRepository;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.repository.ApiSubcategoryRepository;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.catalog.repository.ToolCategoryRepository;
import com.apimarketplace.catalog.repository.ToolNameRepository;
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
 * Integration tests for Spring Data JDBC repositories.
 * Tests CRUD operations and custom query methods against H2 in-memory database.
 */
@IntegrationTest
@Import(IntegrationTestConfig.class)
@DisplayName("Repository Integration Tests")
class RepositoryIntegrationTest {

    @Autowired
    private ApiCategoryRepository categoryRepository;

    @Autowired
    private ApiSubcategoryRepository subcategoryRepository;

    @Autowired
    private ApiRepository apiRepository;

    @Autowired
    private ApiToolRepository apiToolRepository;

    @Autowired
    private ApiToolParameterRepository parameterRepository;

    @Autowired
    private ToolCategoryRepository toolCategoryRepository;

    @Autowired
    private ToolNameRepository toolNameRepository;

    private ApiCategoryEntity savedCategory;
    private ApiSubcategoryEntity savedSubcategory;

    @BeforeEach
    void setUp() {
        // Clean up in reverse dependency order
        parameterRepository.deleteAll();
        apiToolRepository.deleteAll();
        apiRepository.deleteAll();
        toolNameRepository.deleteAll();
        toolCategoryRepository.deleteAll();
        subcategoryRepository.deleteAll();
        categoryRepository.deleteAll();

        // Create base category and subcategory for tests that need them
        savedCategory = createCategory("Test Category", "Test category description", "test-category");
        savedSubcategory = createSubcategory(savedCategory.getId(), "Test Subcategory", "Test subcategory description", "test-subcategory");
    }

    @Nested
    @DisplayName("ApiCategoryRepository Tests")
    class ApiCategoryRepositoryTests {

        @Test
        @DisplayName("should save and find category by ID")
        void shouldSaveAndFindById() {
            Optional<ApiCategoryEntity> found = categoryRepository.findById(savedCategory.getId());
            assertThat(found).isPresent();
            assertThat(found.get().getName()).isEqualTo("Test Category");
        }

        @Test
        @DisplayName("should find category by name")
        void shouldFindByName() {
            Optional<ApiCategoryEntity> found = categoryRepository.findByName("Test Category");
            assertThat(found).isPresent();
            assertThat(found.get().getId()).isEqualTo(savedCategory.getId());
        }

        @Test
        @DisplayName("should find category by slug")
        void shouldFindBySlug() {
            Optional<ApiCategoryEntity> found = categoryRepository.findBySlug("test-category");
            assertThat(found).isPresent();
            assertThat(found.get().getName()).isEqualTo("Test Category");
        }

        @Test
        @DisplayName("should find all categories ordered by name")
        void shouldFindAllOrderedByName() {
            createCategory("Alpha Category", "First", "alpha-category");
            createCategory("Zeta Category", "Last", "zeta-category");

            List<ApiCategoryEntity> categories = categoryRepository.findAllByOrderByNameAsc();
            assertThat(categories).hasSizeGreaterThanOrEqualTo(3);
            assertThat(categories.get(0).getName()).isEqualTo("Alpha Category");
        }

        @Test
        @DisplayName("should check name existence excluding current ID")
        void shouldCheckNameExistenceExcludingId() {
            UUID otherId = UUID.randomUUID();
            boolean exists = categoryRepository.existsByNameAndIdNot("Test Category", otherId);
            assertThat(exists).isTrue();

            boolean notExists = categoryRepository.existsByNameAndIdNot("Test Category", savedCategory.getId());
            assertThat(notExists).isFalse();
        }
    }

    @Nested
    @DisplayName("ApiSubcategoryRepository Tests")
    class ApiSubcategoryRepositoryTests {

        @Test
        @DisplayName("should find subcategories by category ID")
        void shouldFindByCategoryId() {
            List<ApiSubcategoryEntity> subcategories = subcategoryRepository.findByCategoryId(savedCategory.getId());
            assertThat(subcategories).hasSize(1);
            assertThat(subcategories.get(0).getName()).isEqualTo("Test Subcategory");
        }

        @Test
        @DisplayName("should find subcategory by name and category")
        void shouldFindByNameAndCategory() {
            Optional<ApiSubcategoryEntity> found = subcategoryRepository.findByNameAndCategoryId(
                    "Test Subcategory", savedCategory.getId());
            assertThat(found).isPresent();
        }

        @Test
        @DisplayName("should find subcategory by slug")
        void shouldFindBySlug() {
            Optional<ApiSubcategoryEntity> found = subcategoryRepository.findBySlug("test-subcategory");
            assertThat(found).isPresent();
            assertThat(found.get().getName()).isEqualTo("Test Subcategory");
        }

        @Test
        @DisplayName("should find subcategories by name ignoring case")
        void shouldFindByNameIgnoreCase() {
            List<ApiSubcategoryEntity> found = subcategoryRepository.findByNameIgnoreCase("test subcategory");
            assertThat(found).hasSize(1);
        }
    }

    @Nested
    @DisplayName("ApiRepository Tests")
    class ApiRepositoryTests {

        @Test
        @DisplayName("should save and retrieve API entity")
        void shouldSaveAndRetrieveApi() {
            ApiEntity api = createApi("Test API", "user-1", "test-api");

            Optional<ApiEntity> found = apiRepository.findById(api.getId());
            assertThat(found).isPresent();
            assertThat(found.get().getApiName()).isEqualTo("Test API");
            assertThat(found.get().getCreatedBy()).isEqualTo("user-1");
        }

        @Test
        @DisplayName("should persist custom API organization scope")
        void shouldPersistCustomApiOrganizationScope() {
            ApiEntity orgApi = createApi("Scoped API", "user-1", "scoped-api");
            orgApi.setSource("custom");
            orgApi.setIsActive(true);
            orgApi.setOrganizationId("org-acme");
            apiRepository.save(orgApi);

            ApiEntity personalApi = createApi("Personal API", "user-1", "personal-api");
            personalApi.setSource("custom");
            personalApi.setIsActive(true);
            apiRepository.save(personalApi);

            List<ApiEntity> orgApis = apiRepository.findCustomApisInScope("user-1", "org-acme");
            List<ApiEntity> personalApis = apiRepository.findCustomApisInScope("user-1", null);

            assertThat(orgApis)
                    .extracting(ApiEntity::getApiName)
                    .containsExactly("Scoped API");
            assertThat(personalApis)
                    .extracting(ApiEntity::getApiName)
                    .containsExactly("Personal API");
        }

        @Test
        @DisplayName("should find API by name")
        void shouldFindByName() {
            createApi("My Special API", "user-1", "my-special-api");

            Optional<ApiEntity> found = apiRepository.findByApiName("My Special API");
            assertThat(found).isPresent();
            assertThat(found.get().getApiName()).isEqualTo("My Special API");
        }

        @Test
        @DisplayName("should find APIs by creator")
        void shouldFindByCreator() {
            createApi("User1 API A", "user-1", "user1-api-a");
            createApi("User1 API B", "user-1", "user1-api-b");
            createApi("User2 API", "user-2", "user2-api");

            List<ApiEntity> user1Apis = apiRepository.findByCreatedBy("user-1");
            assertThat(user1Apis).hasSize(2);

            List<ApiEntity> user2Apis = apiRepository.findByCreatedBy("user-2");
            assertThat(user2Apis).hasSize(1);
        }

        @Test
        @DisplayName("should find APIs by category")
        void shouldFindByCategoryId() {
            createApi("Category API", "user-1", "category-api");

            List<ApiEntity> found = apiRepository.findByCategoryId(savedCategory.getId());
            assertThat(found).hasSize(1);
        }

        @Test
        @DisplayName("should find API by slug")
        void shouldFindBySlug() {
            createApi("Slug API", "user-1", "slug-api");

            Optional<ApiEntity> found = apiRepository.findByApiSlug("slug-api");
            assertThat(found).isPresent();
            assertThat(found.get().getApiName()).isEqualTo("Slug API");
        }

        @Test
        @DisplayName("should find active APIs")
        void shouldFindActiveApis() {
            ApiEntity activeApi = createApi("Active API", "user-1", "active-api");
            activeApi.setIsActive(true);
            apiRepository.save(activeApi);

            createApi("Inactive API", "user-1", "inactive-api");

            List<ApiEntity> activeApis = apiRepository.findByIsActiveTrue();
            assertThat(activeApis).hasSize(1);
            assertThat(activeApis.get(0).getApiName()).isEqualTo("Active API");
        }

        @Test
        @DisplayName("should check name uniqueness excluding ID")
        void shouldCheckNameUniquenessExcludingId() {
            createApi("Unique Test API", "user-1", "unique-test-api");

            boolean exists = apiRepository.existsByApiNameAndIdNot("Unique Test API", UUID.randomUUID());
            assertThat(exists).isTrue();
        }
    }

    @Nested
    @DisplayName("ApiToolRepository Tests")
    class ApiToolRepositoryTests {

        private ApiEntity savedApi;

        @BeforeEach
        void setUpApi() {
            savedApi = createApi("Tool Test API", "user-tools", "tool-test-api");
        }

        @Test
        @DisplayName("should save and retrieve tool entity")
        void shouldSaveAndRetrieveTool() {
            ApiToolEntity tool = createTool(savedApi.getId(), "Test Tool", "GET", "/test");

            Optional<ApiToolEntity> found = apiToolRepository.findById(tool.getId());
            assertThat(found).isPresent();
            assertThat(found.get().getDescription()).isEqualTo("Test Tool");
            assertThat(found.get().getMethod()).isEqualTo("GET");
        }

        @Test
        @DisplayName("should find tools by API ID")
        void shouldFindByApiId() {
            createTool(savedApi.getId(), "Tool A", "GET", "/a");
            createTool(savedApi.getId(), "Tool B", "POST", "/b");

            List<ApiToolEntity> tools = apiToolRepository.findByApiId(savedApi.getId());
            assertThat(tools).hasSize(2);
        }

        @Test
        @DisplayName("should find tool by slug")
        void shouldFindBySlug() {
            ApiToolEntity tool = new ApiToolEntity();
            tool.setApiId(savedApi.getId());
            tool.setDescription("Slug Tool");
            tool.setMethod("GET");
            tool.setEndpoint("/slug");
            tool.setToolSlug("slug-tool");
            tool.setStatus("ACTIVE");
            tool.setCreatedAt(System.currentTimeMillis());
            tool.setUpdatedAt(System.currentTimeMillis());
            apiToolRepository.save(tool);

            Optional<ApiToolEntity> found = apiToolRepository.findByToolSlug("slug-tool");
            assertThat(found).isPresent();
            assertThat(found.get().getDescription()).isEqualTo("Slug Tool");
        }

        @Test
        @DisplayName("should find active tools by API ID")
        void shouldFindActiveToolsByApiId() {
            ApiToolEntity activeTool = createTool(savedApi.getId(), "Active Tool", "GET", "/active");
            activeTool.setIsActive(true);
            apiToolRepository.save(activeTool);

            createTool(savedApi.getId(), "Inactive Tool", "GET", "/inactive");

            List<ApiToolEntity> activeTools = apiToolRepository.findByApiIdAndIsActiveTrue(savedApi.getId());
            assertThat(activeTools).hasSize(1);
            assertThat(activeTools.get(0).getDescription()).isEqualTo("Active Tool");
        }
    }

    @Nested
    @DisplayName("ApiToolParameterRepository Tests")
    class ApiToolParameterRepositoryTests {

        private ApiToolEntity savedTool;

        @BeforeEach
        void setUpTool() {
            ApiEntity api = createApi("Param Test API", "user-params", "param-test-api");
            savedTool = createTool(api.getId(), "Param Tool", "POST", "/params");
        }

        @Test
        @DisplayName("should save and find parameters by tool ID")
        void shouldSaveAndFindByToolId() {
            createParameter(savedTool.getId(), "userId", "query", "string", true);
            createParameter(savedTool.getId(), "limit", "query", "integer", false);

            List<ApiToolParameterEntity> params = parameterRepository.findByApiToolId(savedTool.getId());
            assertThat(params).hasSize(2);
        }

        @Test
        @DisplayName("should find parameters by tool ID and parameter type")
        void shouldFindByToolIdAndType() {
            createParameter(savedTool.getId(), "userId", "query", "string", true);
            createParameter(savedTool.getId(), "Authorization", "header", "string", true);

            List<ApiToolParameterEntity> queryParams = parameterRepository.findByApiToolIdAndParameterType(
                    savedTool.getId(), "query");
            assertThat(queryParams).hasSize(1);
            assertThat(queryParams.get(0).getName()).isEqualTo("userId");

            List<ApiToolParameterEntity> headerParams = parameterRepository.findByApiToolIdAndParameterType(
                    savedTool.getId(), "header");
            assertThat(headerParams).hasSize(1);
            assertThat(headerParams.get(0).getName()).isEqualTo("Authorization");
        }
    }

    @Nested
    @DisplayName("ToolCategoryRepository Tests")
    class ToolCategoryRepositoryTests {

        @Test
        @DisplayName("should save and find tool category by name")
        void shouldSaveAndFindByName() {
            ToolCategoryEntity category = createToolCategory("Social Media", "social-media", true);

            Optional<ToolCategoryEntity> found = toolCategoryRepository.findByName("Social Media");
            assertThat(found).isPresent();
            assertThat(found.get().getSlug()).isEqualTo("social-media");
        }

        @Test
        @DisplayName("should find active categories ordered by sort order")
        void shouldFindActiveOrderedBySortOrder() {
            createToolCategory("Zeta Category", "zeta", true, 2);
            createToolCategory("Alpha Category", "alpha", true, 1);
            createToolCategory("Inactive Category", "inactive", false, 0);

            List<ToolCategoryEntity> active = toolCategoryRepository.findByIsActiveTrueOrderBySortOrderAsc();
            assertThat(active).hasSize(2);
            assertThat(active.get(0).getName()).isEqualTo("Alpha Category");
            assertThat(active.get(1).getName()).isEqualTo("Zeta Category");
        }

        @Test
        @DisplayName("should check existence by name and slug")
        void shouldCheckExistence() {
            createToolCategory("Existing Category", "existing", true);

            assertThat(toolCategoryRepository.existsByName("Existing Category")).isTrue();
            assertThat(toolCategoryRepository.existsByName("Nonexistent")).isFalse();
            assertThat(toolCategoryRepository.existsBySlug("existing")).isTrue();
            assertThat(toolCategoryRepository.existsBySlug("nonexistent")).isFalse();
        }

        @Test
        @DisplayName("should find categories by name containing ignoring case")
        void shouldFindByNameContainingIgnoreCase() {
            createToolCategory("Social Media Tools", "social-media-tools", true);
            createToolCategory("Social Analytics", "social-analytics", true);
            createToolCategory("Payment Tools", "payment-tools", true);

            List<ToolCategoryEntity> found = toolCategoryRepository.findByNameContainingIgnoreCaseAndIsActiveTrue("social");
            assertThat(found).hasSize(2);
        }
    }

    @Nested
    @DisplayName("ToolNameRepository Tests")
    class ToolNameRepositoryTests {

        private ToolCategoryEntity savedToolCategory;

        @BeforeEach
        void setUpToolCategory() {
            savedToolCategory = createToolCategory("Test Tool Category", "test-tool-category", true);
        }

        @Test
        @DisplayName("should save and find tool name by name and category")
        void shouldSaveAndFindByNameAndCategory() {
            createToolName("get_user_profile", savedToolCategory.getId(), null, "get-user-profile");

            Optional<ToolNameEntity> found = toolNameRepository.findByNameAndToolCategoryId(
                    "get_user_profile", savedToolCategory.getId());
            assertThat(found).isPresent();
        }

        @Test
        @DisplayName("should find active tool names ordered by name")
        void shouldFindActiveOrderedByName() {
            createToolName("zeta_tool", savedToolCategory.getId(), null, "zeta-tool");
            createToolName("alpha_tool", savedToolCategory.getId(), null, "alpha-tool");

            List<ToolNameEntity> names = toolNameRepository.findByIsActiveTrueOrderByNameAsc();
            assertThat(names).hasSize(2);
            assertThat(names.get(0).getName()).isEqualTo("alpha_tool");
        }

        @Test
        @DisplayName("should find tool names by subcategory")
        void shouldFindBySubcategory() {
            createToolName("subcategory_tool", savedToolCategory.getId(), savedSubcategory.getId(), "subcategory-tool");

            List<ToolNameEntity> found = toolNameRepository.findBySubcategoryIdAndIsActiveTrue(savedSubcategory.getId());
            assertThat(found).hasSize(1);
            assertThat(found.get(0).getName()).isEqualTo("subcategory_tool");
        }

        @Test
        @DisplayName("should find tool names by run scope")
        void shouldFindByRunScope() {
            ToolNameEntity externalTool = createToolName("external_tool", savedToolCategory.getId(), null, "external-tool");
            externalTool.setRunScope("external");
            toolNameRepository.save(externalTool);

            ToolNameEntity localTool = createToolName("local_tool", savedToolCategory.getId(), null, "local-tool");
            localTool.setRunScope("local");
            toolNameRepository.save(localTool);

            List<ToolNameEntity> externalTools = toolNameRepository.findByRunScopeInAndIsActiveTrueOrderByNameAsc(
                    List.of("external", "both"));
            assertThat(externalTools).hasSize(1);
            assertThat(externalTools.get(0).getName()).isEqualTo("external_tool");
        }

        @Test
        @DisplayName("should search tool names by name containing")
        void shouldSearchByNameContaining() {
            createToolName("get_user_profile", savedToolCategory.getId(), null, "get-user-profile-1");
            createToolName("get_user_tweets", savedToolCategory.getId(), null, "get-user-tweets");
            createToolName("post_message", savedToolCategory.getId(), null, "post-message");

            List<ToolNameEntity> found = toolNameRepository.findByNameContainingIgnoreCaseAndIsActiveTrue("user");
            assertThat(found).hasSize(2);
        }
    }

    // ===== Helper methods =====

    private ApiCategoryEntity createCategory(String name, String description, String slug) {
        ApiCategoryEntity category = new ApiCategoryEntity();
        category.setName(name);
        category.setDescription(description);
        category.setSlug(slug);
        category.setSortOrder(0);
        category.setCreatedAt(System.currentTimeMillis());
        category.setUpdatedAt(System.currentTimeMillis());
        return categoryRepository.save(category);
    }

    private ApiSubcategoryEntity createSubcategory(UUID categoryId, String name, String description, String slug) {
        ApiSubcategoryEntity subcategory = new ApiSubcategoryEntity();
        subcategory.setCategoryId(categoryId);
        subcategory.setName(name);
        subcategory.setDescription(description);
        subcategory.setSlug(slug);
        subcategory.setSortOrder(0);
        subcategory.setCreatedAt(System.currentTimeMillis());
        subcategory.setUpdatedAt(System.currentTimeMillis());
        return subcategoryRepository.save(subcategory);
    }

    private ApiEntity createApi(String name, String createdBy, String slug) {
        ApiEntity api = new ApiEntity();
        api.setApiName(name);
        api.setApiSlug(slug);
        api.setDescription("Description for " + name);
        api.setBaseUrl("https://api.example.com");
        api.setCategoryId(savedCategory.getId());
        api.setSubcategoryId(savedSubcategory.getId());
        api.setCreatedBy(createdBy);
        api.setStatus("DRAFT");
        api.setIsActive(false);
        api.setIsLocal(false);
        api.setCreatedAt(System.currentTimeMillis());
        api.setUpdatedAt(System.currentTimeMillis());
        return apiRepository.save(api);
    }

    private ApiToolEntity createTool(UUID apiId, String description, String method, String endpoint) {
        ApiToolEntity tool = new ApiToolEntity();
        tool.setApiId(apiId);
        tool.setDescription(description);
        tool.setMethod(method);
        tool.setEndpoint(endpoint);
        tool.setStatus("DRAFT");
        tool.setIsActive(false);
        tool.setCreatedAt(System.currentTimeMillis());
        tool.setUpdatedAt(System.currentTimeMillis());
        return apiToolRepository.save(tool);
    }

    private ApiToolParameterEntity createParameter(UUID toolId, String name, String type, String dataType, boolean required) {
        ApiToolParameterEntity param = new ApiToolParameterEntity();
        param.setId(UUID.randomUUID());
        param.setApiToolId(toolId);
        param.setName(name);
        param.setParameterType(type);
        param.setDataType(dataType);
        param.setIsRequired(required);
        param.setCreatedAt(System.currentTimeMillis());
        param.setNew(true);
        return parameterRepository.save(param);
    }

    private ToolCategoryEntity createToolCategory(String name, String slug, boolean active) {
        return createToolCategory(name, slug, active, 0);
    }

    private ToolCategoryEntity createToolCategory(String name, String slug, boolean active, int sortOrder) {
        ToolCategoryEntity category = new ToolCategoryEntity();
        category.setName(name);
        category.setSlug(slug);
        category.setIsActive(active);
        category.setSortOrder(sortOrder);
        category.setIcon("default");
        category.setColor("#000000");
        category.setCreatedAt(System.currentTimeMillis());
        category.setUpdatedAt(System.currentTimeMillis());
        return toolCategoryRepository.save(category);
    }

    private ToolNameEntity createToolName(String name, UUID toolCategoryId, UUID subcategoryId, String slug) {
        ToolNameEntity toolName = new ToolNameEntity();
        toolName.setName(name);
        toolName.setDescription("Description for " + name);
        toolName.setToolCategoryId(toolCategoryId);
        toolName.setSubcategoryId(subcategoryId);
        toolName.setSlug(slug);
        toolName.setRunScope("external");
        toolName.setRequiresUserCredentials(false);
        toolName.setIsActive(true);
        toolName.setCreatedAt(System.currentTimeMillis());
        toolName.setUpdatedAt(System.currentTimeMillis());
        return toolNameRepository.save(toolName);
    }
}
