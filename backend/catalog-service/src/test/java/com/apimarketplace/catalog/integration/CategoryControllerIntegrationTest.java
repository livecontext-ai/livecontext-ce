package com.apimarketplace.catalog.integration;

import com.apimarketplace.catalog.domain.ApiCategoryEntity;
import com.apimarketplace.catalog.domain.ApiSubcategoryEntity;
import com.apimarketplace.catalog.repository.ApiCategoryRepository;
import com.apimarketplace.catalog.repository.ApiSubcategoryRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for CategoryController endpoints.
 * Uses real Spring context with H2 in-memory database.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfig.class)
@DisplayName("CategoryController Integration Tests")
class CategoryControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApiCategoryRepository categoryRepository;

    @Autowired
    private ApiSubcategoryRepository subcategoryRepository;

    private ApiCategoryEntity socialCategory;
    private ApiCategoryEntity paymentCategory;

    @BeforeEach
    void setUp() {
        subcategoryRepository.deleteAll();
        categoryRepository.deleteAll();

        socialCategory = saveCategory("Social Media", "Social media APIs", "social-media", 1);
        paymentCategory = saveCategory("Payments", "Payment APIs", "payments", 2);
    }

    @Nested
    @DisplayName("GET /api/catalog/categories")
    class GetAllCategoriesTests {

        @Test
        @DisplayName("should return all categories ordered by name")
        void shouldReturnAllCategories() throws Exception {
            mockMvc.perform(get("/api/catalog/categories")
                            .header("X-User-ID", "test-user"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].name", is("Payments")))
                    .andExpect(jsonPath("$[1].name", is("Social Media")));
        }

        @Test
        @DisplayName("should return empty list when no categories exist")
        void shouldReturnEmptyListWhenNoCategories() throws Exception {
            subcategoryRepository.deleteAll();
            categoryRepository.deleteAll();

            mockMvc.perform(get("/api/catalog/categories"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }
    }

    @Nested
    @DisplayName("GET /api/catalog/categories/{categoryId}/subcategories")
    class GetSubcategoriesTests {

        @Test
        @DisplayName("should return subcategories for category")
        void shouldReturnSubcategoriesForCategory() throws Exception {
            saveSubcategory(socialCategory.getId(), "Instagram", "Instagram APIs", "instagram");
            saveSubcategory(socialCategory.getId(), "Twitter", "Twitter APIs", "twitter");

            mockMvc.perform(get("/api/catalog/categories/{categoryId}/subcategories", socialCategory.getId())
                            .header("X-User-ID", "test-user"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)));
        }

        @Test
        @DisplayName("should return empty list for category with no subcategories")
        void shouldReturnEmptyListWhenNoSubcategories() throws Exception {
            mockMvc.perform(get("/api/catalog/categories/{categoryId}/subcategories", paymentCategory.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }
    }

    @Nested
    @DisplayName("GET /api/catalog/categories/subcategories")
    class GetAllSubcategoriesTests {

        @Test
        @DisplayName("should return all subcategories")
        void shouldReturnAllSubcategories() throws Exception {
            saveSubcategory(socialCategory.getId(), "Instagram", "Instagram APIs", "instagram");
            saveSubcategory(paymentCategory.getId(), "Stripe", "Stripe APIs", "stripe");

            mockMvc.perform(get("/api/catalog/categories/subcategories")
                            .header("X-User-ID", "test-user"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)));
        }
    }

    // ===== Helper methods =====

    private ApiCategoryEntity saveCategory(String name, String description, String slug, int sortOrder) {
        ApiCategoryEntity category = new ApiCategoryEntity();
        category.setName(name);
        category.setDescription(description);
        category.setSlug(slug);
        category.setSortOrder(sortOrder);
        category.setCreatedAt(System.currentTimeMillis());
        category.setUpdatedAt(System.currentTimeMillis());
        return categoryRepository.save(category);
    }

    private ApiSubcategoryEntity saveSubcategory(UUID categoryId, String name, String description, String slug) {
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
}
