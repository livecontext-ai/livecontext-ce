package com.apimarketplace.catalog.integration;

import com.apimarketplace.catalog.domain.ApiCategoryEntity;
import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiSubcategoryEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.ApiToolParameterEntity;
import com.apimarketplace.catalog.repository.ApiCategoryRepository;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.repository.ApiSubcategoryRepository;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for CatalogToolsController.
 * Tests tool info and tool listing endpoints with real database.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfig.class)
@DisplayName("CatalogToolsController Integration Tests")
class CatalogToolsControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

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

    private ApiEntity savedApi;
    private ApiToolEntity savedTool;

    @BeforeEach
    void setUp() {
        parameterRepository.deleteAll();
        apiToolRepository.deleteAll();
        apiRepository.deleteAll();
        subcategoryRepository.deleteAll();
        categoryRepository.deleteAll();

        // Set up test data hierarchy
        ApiCategoryEntity category = new ApiCategoryEntity();
        category.setName("Test Category");
        category.setSlug("test-category");
        category.setSortOrder(0);
        category.setCreatedAt(System.currentTimeMillis());
        category.setUpdatedAt(System.currentTimeMillis());
        category = categoryRepository.save(category);

        ApiSubcategoryEntity subcategory = new ApiSubcategoryEntity();
        subcategory.setCategoryId(category.getId());
        subcategory.setName("Test Subcategory");
        subcategory.setSlug("test-subcategory");
        subcategory.setSortOrder(0);
        subcategory.setCreatedAt(System.currentTimeMillis());
        subcategory.setUpdatedAt(System.currentTimeMillis());
        subcategory = subcategoryRepository.save(subcategory);

        savedApi = new ApiEntity();
        savedApi.setApiName("Test API");
        savedApi.setApiSlug("test-api");
        savedApi.setDescription("Test API description");
        savedApi.setBaseUrl("https://api.example.com");
        savedApi.setCategoryId(category.getId());
        savedApi.setSubcategoryId(subcategory.getId());
        savedApi.setCreatedBy("user-1");
        savedApi.setStatus("ACTIVE");
        savedApi.setAuthType("apikey");
        savedApi.setAuthHeaderName("X-API-Key");
        savedApi.setAuthHeaderValue("test-key");
        savedApi.setIsActive(true);
        savedApi.setIsLocal(false);
        savedApi.setCreatedAt(System.currentTimeMillis());
        savedApi.setUpdatedAt(System.currentTimeMillis());
        savedApi = apiRepository.save(savedApi);

        savedTool = new ApiToolEntity();
        savedTool.setApiId(savedApi.getId());
        savedTool.setDescription("Get user profile");
        savedTool.setToolSlug("get-user-profile");
        savedTool.setMethod("GET");
        savedTool.setEndpoint("/users/{userId}");
        savedTool.setStatus("ACTIVE");
        savedTool.setIsActive(true);
        savedTool.setCreatedAt(System.currentTimeMillis());
        savedTool.setUpdatedAt(System.currentTimeMillis());
        savedTool = apiToolRepository.save(savedTool);

        // Add parameters
        ApiToolParameterEntity param = new ApiToolParameterEntity();
        param.setId(UUID.randomUUID());
        param.setApiToolId(savedTool.getId());
        param.setName("userId");
        param.setParameterType("path");
        param.setDataType("string");
        param.setIsRequired(true);
        param.setDescription("User ID");
        param.setExampleValue("12345");
        param.setCreatedAt(System.currentTimeMillis());
        param.setNew(true);
        parameterRepository.save(param);
    }

    @Nested
    @DisplayName("GET /api/catalog/tools/{toolId}/info")
    class GetToolInfoTests {

        @Test
        @DisplayName("should return tool info with all details")
        void shouldReturnToolInfoWithAllDetails() throws Exception {
            mockMvc.perform(get("/api/catalog/tools/{toolId}/info", savedTool.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id", is(savedTool.getId().toString())))
                    .andExpect(jsonPath("$.description", is("Get user profile")))
                    .andExpect(jsonPath("$.method", is("GET")))
                    .andExpect(jsonPath("$.endpoint", is("/users/{userId}")))
                    .andExpect(jsonPath("$.api.name", is("Test API")))
                    .andExpect(jsonPath("$.api.baseUrl", is("https://api.example.com")))
                    .andExpect(jsonPath("$.api.authType", is("apikey")))
                    .andExpect(jsonPath("$.parameters", hasSize(1)))
                    .andExpect(jsonPath("$.parameters[0].name", is("userId")))
                    .andExpect(jsonPath("$.parameters[0].required", is(true)))
                    .andExpect(jsonPath("$.fullEndpoint", notNullValue()));
        }

        @Test
        @DisplayName("should return 404 for non-existent tool")
        void shouldReturn404ForNonExistentTool() throws Exception {
            UUID randomId = UUID.randomUUID();
            mockMvc.perform(get("/api/catalog/tools/{toolId}/info", randomId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 400 for invalid tool ID format")
        void shouldReturn400ForInvalidToolId() throws Exception {
            mockMvc.perform(get("/api/catalog/tools/{toolId}/info", "invalid-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error", is("Invalid tool ID format")));
        }
    }

    @Nested
    @DisplayName("GET /api/catalog/tools")
    class GetAllToolsTests {

        @Test
        @DisplayName("should return all tools")
        void shouldReturnAllTools() throws Exception {
            mockMvc.perform(get("/api/catalog/tools"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].toolSlug", is("get-user-profile")))
                    .andExpect(jsonPath("$[0].method", is("GET")));
        }

        @Test
        @DisplayName("should return only active tools when activeOnly is true")
        void shouldReturnOnlyActiveTools() throws Exception {
            // Add an inactive tool
            ApiToolEntity inactiveTool = new ApiToolEntity();
            inactiveTool.setApiId(savedApi.getId());
            inactiveTool.setDescription("Inactive tool");
            inactiveTool.setMethod("POST");
            inactiveTool.setEndpoint("/inactive");
            inactiveTool.setStatus("DRAFT");
            inactiveTool.setIsActive(false);
            inactiveTool.setCreatedAt(System.currentTimeMillis());
            inactiveTool.setUpdatedAt(System.currentTimeMillis());
            apiToolRepository.save(inactiveTool);

            mockMvc.perform(get("/api/catalog/tools")
                            .param("activeOnly", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].description", is("Get user profile")));
        }
    }

    @Nested
    @DisplayName("GET /api/catalog/health")
    class HealthCheckTests {

        @Test
        @DisplayName("should return UP status")
        void shouldReturnUpStatus() throws Exception {
            mockMvc.perform(get("/api/catalog/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("UP")))
                    .andExpect(jsonPath("$.service", is("catalog-service")))
                    .andExpect(jsonPath("$.timestamp", notNullValue()));
        }
    }
}
