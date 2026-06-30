package com.apimarketplace.catalog.integration;

import com.apimarketplace.catalog.domain.ApiCategoryEntity;
import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiSubcategoryEntity;
import com.apimarketplace.catalog.repository.ApiCategoryRepository;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.repository.ApiSubcategoryRepository;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.catalog.service.AuthorizationService;
import com.apimarketplace.catalog.service.exception.AccessDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for AuthorizationService.
 * Tests ownership verification and access control with real database.
 */
@IntegrationTest
@Import(IntegrationTestConfig.class)
@DisplayName("AuthorizationService Integration Tests")
class AuthorizationServiceIntegrationTest {

    @Autowired
    private AuthorizationService authorizationService;

    @Autowired
    private ApiCategoryRepository categoryRepository;

    @Autowired
    private ApiSubcategoryRepository subcategoryRepository;

    @Autowired
    private ApiRepository apiRepository;

    @Autowired
    private ApiToolRepository apiToolRepository;

    private ApiEntity ownedApi;

    @BeforeEach
    void setUp() {
        apiToolRepository.deleteAll();
        apiRepository.deleteAll();
        subcategoryRepository.deleteAll();
        categoryRepository.deleteAll();

        ApiCategoryEntity category = new ApiCategoryEntity();
        category.setName("Test Category");
        category.setSlug("test-cat");
        category.setSortOrder(0);
        category.setCreatedAt(System.currentTimeMillis());
        category.setUpdatedAt(System.currentTimeMillis());
        category = categoryRepository.save(category);

        ApiSubcategoryEntity subcategory = new ApiSubcategoryEntity();
        subcategory.setCategoryId(category.getId());
        subcategory.setName("Test Subcategory");
        subcategory.setSlug("test-subcat");
        subcategory.setSortOrder(0);
        subcategory.setCreatedAt(System.currentTimeMillis());
        subcategory.setUpdatedAt(System.currentTimeMillis());
        subcategory = subcategoryRepository.save(subcategory);

        ownedApi = createApi("Owned API", "owned-api", "owner-user", false, category.getId(), subcategory.getId());
    }

    @Nested
    @DisplayName("verifyApiOwnership")
    class VerifyApiOwnershipTests {

        @Test
        @DisplayName("should allow access for API owner")
        void shouldAllowAccessForOwner() {
            // Should not throw
            authorizationService.verifyApiOwnership("owner-user", ownedApi.getId());
        }

        @Test
        @DisplayName("should allow case-insensitive owner match")
        void shouldAllowCaseInsensitiveMatch() {
            // Should not throw - case-insensitive comparison
            authorizationService.verifyApiOwnership("OWNER-USER", ownedApi.getId());
        }

        @Test
        @DisplayName("should deny access for non-owner")
        void shouldDenyAccessForNonOwner() {
            assertThatThrownBy(() ->
                    authorizationService.verifyApiOwnership("other-user", ownedApi.getId())
            ).isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("should deny access for null userId")
        void shouldDenyAccessForNullUserId() {
            assertThatThrownBy(() ->
                    authorizationService.verifyApiOwnership(null, ownedApi.getId())
            ).isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("should deny access for blank userId")
        void shouldDenyAccessForBlankUserId() {
            assertThatThrownBy(() ->
                    authorizationService.verifyApiOwnership("  ", ownedApi.getId())
            ).isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("should not throw for non-existent API")
        void shouldNotThrowForNonExistentApi() {
            // Should not throw - let controller handle not found
            authorizationService.verifyApiOwnership("owner-user", UUID.randomUUID());
        }
    }

    // ===== Helper methods =====

    private ApiEntity createApi(String name, String slug, String createdBy, boolean isPublic,
                                 UUID categoryId, UUID subcategoryId) {
        ApiEntity api = new ApiEntity();
        api.setApiName(name);
        api.setApiSlug(slug);
        api.setDescription(name + " description");
        api.setBaseUrl("https://api.example.com/" + slug);
        api.setCategoryId(categoryId);
        api.setSubcategoryId(subcategoryId);
        api.setCreatedBy(createdBy);
        api.setStatus("ACTIVE");
        api.setAuthType("apikey");
        api.setIsActive(true);
        api.setIsPublic(isPublic);
        api.setVisibility(isPublic ? "public" : "private");
        api.setIsLocal(false);
        api.setCreatedAt(System.currentTimeMillis());
        api.setUpdatedAt(System.currentTimeMillis());
        return apiRepository.save(api);
    }
}
