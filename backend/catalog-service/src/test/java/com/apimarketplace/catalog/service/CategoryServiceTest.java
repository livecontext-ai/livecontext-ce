package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.ApiCategoryEntity;
import com.apimarketplace.catalog.domain.ApiSubcategoryEntity;
import com.apimarketplace.catalog.repository.ApiCategoryRepository;
import com.apimarketplace.catalog.repository.ApiSubcategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CategoryService.
 *
 * CategoryService manages categories and subcategories for API tools.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CategoryService")
class CategoryServiceTest {

    @Mock
    private ApiCategoryRepository categoryRepository;

    @Mock
    private ApiSubcategoryRepository subcategoryRepository;

    private CategoryService categoryService;

    @BeforeEach
    void setUp() {
        categoryService = new CategoryService(categoryRepository, subcategoryRepository);
    }

    // ========================================================================
    // Category retrieval tests
    // ========================================================================

    @Nested
    @DisplayName("Category retrieval")
    class CategoryRetrievalTests {

        @Test
        @DisplayName("should return all categories ordered by name")
        void shouldReturnAllCategoriesOrderedByName() {
            // Arrange
            ApiCategoryEntity cat1 = createCategory("AI & ML");
            ApiCategoryEntity cat2 = createCategory("Data");
            when(categoryRepository.findAllByOrderByNameAsc()).thenReturn(List.of(cat1, cat2));

            // Act
            List<ApiCategoryEntity> result = categoryService.getAllCategories();

            // Assert
            assertEquals(2, result.size());
            verify(categoryRepository).findAllByOrderByNameAsc();
        }

        @Test
        @DisplayName("should find category by slug")
        void shouldFindCategoryBySlug() {
            // Arrange
            ApiCategoryEntity category = createCategory("Social Media");
            category.setSlug("social-media");
            when(categoryRepository.findBySlug("social-media")).thenReturn(Optional.of(category));

            // Act
            Optional<ApiCategoryEntity> result = categoryService.getCategoryBySlug("social-media");

            // Assert
            assertTrue(result.isPresent());
            assertEquals("Social Media", result.get().getName());
        }

        @Test
        @DisplayName("should find category by name")
        void shouldFindCategoryByName() {
            // Arrange
            ApiCategoryEntity category = createCategory("Payments");
            when(categoryRepository.findByName("Payments")).thenReturn(Optional.of(category));

            // Act
            Optional<ApiCategoryEntity> result = categoryService.getCategoryByName("Payments");

            // Assert
            assertTrue(result.isPresent());
            assertEquals("Payments", result.get().getName());
        }
    }

    // ========================================================================
    // Subcategory retrieval tests
    // ========================================================================

    @Nested
    @DisplayName("Subcategory retrieval")
    class SubcategoryRetrievalTests {

        @Test
        @DisplayName("should return subcategories by category ID")
        void shouldReturnSubcategoriesByCategoryId() {
            // Arrange
            UUID categoryId = UUID.randomUUID();
            ApiSubcategoryEntity sub1 = createSubcategory("Instagram", categoryId);
            ApiSubcategoryEntity sub2 = createSubcategory("Twitter", categoryId);
            when(subcategoryRepository.findByCategoryId(categoryId)).thenReturn(List.of(sub1, sub2));

            // Act
            List<ApiSubcategoryEntity> result = categoryService.getSubcategoriesByCategoryId(categoryId);

            // Assert
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("should find subcategory by slug and category")
        void shouldFindSubcategoryBySlugAndCategory() {
            // Arrange
            UUID categoryId = UUID.randomUUID();
            ApiSubcategoryEntity subcategory = createSubcategory("Slack", categoryId);
            subcategory.setSlug("slack");
            when(subcategoryRepository.findBySlugAndCategoryId("slack", categoryId))
                    .thenReturn(Optional.of(subcategory));

            // Act
            Optional<ApiSubcategoryEntity> result = categoryService.getSubcategoryBySlugAndCategory("slack", categoryId);

            // Assert
            assertTrue(result.isPresent());
            assertEquals("Slack", result.get().getName());
        }
    }

    // ========================================================================
    // Category creation tests
    // ========================================================================

    @Nested
    @DisplayName("Category creation")
    class CategoryCreationTests {

        @Test
        @DisplayName("should create category with generated slug")
        void shouldCreateCategoryWithGeneratedSlug() {
            // Arrange
            when(categoryRepository.findAll()).thenReturn(Collections.emptyList());
            when(categoryRepository.save(any(ApiCategoryEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            ApiCategoryEntity result = categoryService.createCategory("Weather APIs", "Weather data services");

            // Assert
            assertEquals("Weather APIs", result.getName());
            assertEquals("Weather data services", result.getDescription());
            assertNotNull(result.getSlug());
            assertNotNull(result.getCreatedAt());
            assertNotNull(result.getUpdatedAt());
        }
    }

    // ========================================================================
    // Initialization tests
    // ========================================================================

    @Nested
    @DisplayName("Initialization")
    class InitializationTests {

        @Test
        @DisplayName("should skip initialization when categories exist")
        void shouldSkipInitializationWhenCategoriesExist() {
            // Arrange
            when(categoryRepository.count()).thenReturn(5L);

            // Act
            categoryService.initializeDefaultCategories();

            // Assert
            verify(categoryRepository).count();
            verify(categoryRepository, never()).save(any());
        }
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private ApiCategoryEntity createCategory(String name) {
        ApiCategoryEntity category = new ApiCategoryEntity();
        category.setId(UUID.randomUUID());
        category.setName(name);
        category.setSlug(name.toLowerCase().replace(" ", "-").replace("&", "and"));
        return category;
    }

    private ApiSubcategoryEntity createSubcategory(String name, UUID categoryId) {
        ApiSubcategoryEntity subcategory = new ApiSubcategoryEntity();
        subcategory.setId(UUID.randomUUID());
        subcategory.setName(name);
        subcategory.setCategoryId(categoryId);
        subcategory.setSlug(name.toLowerCase());
        return subcategory;
    }
}
