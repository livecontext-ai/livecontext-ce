package com.apimarketplace.catalog.integration;

import com.apimarketplace.catalog.domain.ApiCategoryEntity;
import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiSubcategoryEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.repository.ApiCategoryRepository;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.repository.ApiSubcategoryRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for ApiController.
 * Tests API management endpoints with real database.
 * Focuses on read-only and health check endpoints.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfig.class)
@DisplayName("ApiController Integration Tests")
class ApiControllerIntegrationTest {

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

    private ApiCategoryEntity category;
    private ApiSubcategoryEntity subcategory;
    private ApiEntity savedApi;

    @BeforeEach
    void setUp() {
        apiToolRepository.deleteAll();
        apiRepository.deleteAll();
        subcategoryRepository.deleteAll();
        categoryRepository.deleteAll();

        category = new ApiCategoryEntity();
        category.setName("Test Category");
        category.setSlug("test-category");
        category.setSortOrder(0);
        category.setCreatedAt(System.currentTimeMillis());
        category.setUpdatedAt(System.currentTimeMillis());
        category = categoryRepository.save(category);

        subcategory = new ApiSubcategoryEntity();
        subcategory.setCategoryId(category.getId());
        subcategory.setName("Test Subcategory");
        subcategory.setSlug("test-subcategory");
        subcategory.setSortOrder(0);
        subcategory.setCreatedAt(System.currentTimeMillis());
        subcategory.setUpdatedAt(System.currentTimeMillis());
        subcategory = subcategoryRepository.save(subcategory);

        savedApi = createApi("Test API", "test-api", "user-1");
    }

    @Nested
    @DisplayName("GET /api/apis/health")
    class HealthCheckTests {

        @Test
        @DisplayName("should return UP status")
        void shouldReturnUpStatus() throws Exception {
            mockMvc.perform(get("/api/apis/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("UP")))
                    .andExpect(jsonPath("$.service", is("api-management")));
        }
    }

    @Nested
    @DisplayName("GET /api/apis/me")
    class GetMyApisTests {

        @Test
        @DisplayName("should return APIs for authenticated user")
        void shouldReturnApisForAuthenticatedUser() throws Exception {
            mockMvc.perform(get("/api/apis/me")
                            .header("X-User-ID", "user-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].apiName", is("Test API")));
        }

        @Test
        @DisplayName("should return empty list for user with no APIs")
        void shouldReturnEmptyForUserWithNoApis() throws Exception {
            mockMvc.perform(get("/api/apis/me")
                            .header("X-User-ID", "user-no-apis"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        @DisplayName("should return 401 when no user header present")
        void shouldReturn401WhenNoUserHeader() throws Exception {
            mockMvc.perform(get("/api/apis/me"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("GET /api/apis")
    class GetAllApisTests {

        @Test
        @DisplayName("should return all APIs")
        void shouldReturnAllApis() throws Exception {
            mockMvc.perform(get("/api/apis"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].apiName", is("Test API")));
        }

        @Test
        @DisplayName("should filter APIs by creator")
        void shouldFilterApisByCreator() throws Exception {
            createApi("Another API", "another-api", "user-2");

            mockMvc.perform(get("/api/apis")
                            .param("createdBy", "user-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].apiName", is("Test API")));
        }

        @Test
        @DisplayName("should return all APIs when no creator filter specified")
        void shouldReturnAllWhenNoFilter() throws Exception {
            createApi("Second API", "second-api", "user-2");

            mockMvc.perform(get("/api/apis"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)));
        }
    }

    @Nested
    @DisplayName("GET /api/apis/check-name")
    class CheckApiNameUniquenessTests {

        @Test
        @DisplayName("should return unique for non-existing name")
        void shouldReturnUniqueForNewName() throws Exception {
            mockMvc.perform(get("/api/apis/check-name")
                            .param("name", "Brand New API")
                            .header("X-User-ID", "user-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.isUnique", is(true)))
                    .andExpect(jsonPath("$.nameIsUnique", is(true)))
                    .andExpect(jsonPath("$.generatedSlug", notNullValue()));
        }

        @Test
        @DisplayName("should return not unique for existing API name by same user")
        void shouldReturnNotUniqueForExistingName() throws Exception {
            mockMvc.perform(get("/api/apis/check-name")
                            .param("name", "Test API")
                            .header("X-User-ID", "user-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nameIsUnique", is(false)));
        }

        @Test
        @DisplayName("should check globally when no user header")
        void shouldCheckGloballyWhenNoUserHeader() throws Exception {
            mockMvc.perform(get("/api/apis/check-name")
                            .param("name", "Test API"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nameIsUnique", is(false)));
        }
    }

    @Nested
    @DisplayName("DELETE /api/apis/{id}")
    class DeleteApiTests {

        @Test
        @DisplayName("should delete API when user is owner")
        void shouldDeleteApiWhenOwner() throws Exception {
            mockMvc.perform(delete("/api/apis/{id}", savedApi.getId())
                            .header("X-User-ID", "user-1"))
                    .andExpect(status().isNoContent());

            // Verify API was deleted
            mockMvc.perform(get("/api/apis")
                            .param("createdBy", "user-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        @DisplayName("should deny deletion when user is not owner")
        void shouldDenyDeletionForNonOwner() throws Exception {
            mockMvc.perform(delete("/api/apis/{id}", savedApi.getId())
                            .header("X-User-ID", "other-user"))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("GET /api/apis/system/all")
    class GetAllApisWithCompleteInfoTests {

        @Test
        @DisplayName("should return paginated APIs with complete info")
        void shouldReturnPaginatedApis() throws Exception {
            mockMvc.perform(get("/api/apis/system/all")
                            .param("page", "0")
                            .param("size", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(1)))
                    .andExpect(jsonPath("$.totalElements", is(1)))
                    .andExpect(jsonPath("$.page", is(0)))
                    .andExpect(jsonPath("$.size", is(10)));
        }

        @Test
        @DisplayName("should filter by API name")
        void shouldFilterByApiName() throws Exception {
            createApi("Filtered API", "filtered-api", "user-2");

            mockMvc.perform(get("/api/apis/system/all")
                            .param("name", "Filtered"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(1)))
                    .andExpect(jsonPath("$.content[0].apiName", is("Filtered API")));
        }

        @Test
        @DisplayName("should handle pagination correctly")
        void shouldHandlePagination() throws Exception {
            createApi("API Two", "api-two", "user-1");
            createApi("API Three", "api-three", "user-1");

            mockMvc.perform(get("/api/apis/system/all")
                            .param("page", "0")
                            .param("size", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(2)))
                    .andExpect(jsonPath("$.totalElements", is(3)))
                    .andExpect(jsonPath("$.totalPages", is(2)))
                    .andExpect(jsonPath("$.first", is(true)))
                    .andExpect(jsonPath("$.last", is(false)));
        }
    }

    // ===== Helper methods =====

    private ApiEntity createApi(String name, String slug, String createdBy) {
        ApiEntity api = new ApiEntity();
        api.setApiName(name);
        api.setApiSlug(slug);
        api.setDescription(name + " description");
        api.setBaseUrl("https://api.example.com/" + slug);
        api.setCategoryId(category.getId());
        api.setSubcategoryId(subcategory.getId());
        api.setCreatedBy(createdBy);
        api.setStatus("ACTIVE");
        api.setAuthType("apikey");
        api.setAuthHeaderName("X-API-Key");
        api.setAuthHeaderValue("test-key");
        api.setIsActive(true);
        api.setIsPublic(false);
        api.setIsLocal(false);
        api.setCreatedAt(System.currentTimeMillis());
        api.setUpdatedAt(System.currentTimeMillis());
        return apiRepository.save(api);
    }
}
