package com.apimarketplace.catalog.web;

import com.apimarketplace.catalog.config.GlobalExceptionHandler;
import com.apimarketplace.catalog.domain.ApiCategoryEntity;
import com.apimarketplace.catalog.domain.ApiSubcategoryEntity;
import com.apimarketplace.catalog.service.CategoryService;
import com.apimarketplace.catalog.service.CatalogV1Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CategoryController Integration Tests")
class CategoryControllerIntegrationTest {

    @Mock
    private CategoryService categoryService;

    @Mock
    private CatalogV1Service catalogV1Service;

    private MockMvc mockMvc;

    private static final UUID CATEGORY_ID = UUID.randomUUID();
    private static final UUID SUBCATEGORY_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        CategoryController controller = new CategoryController(categoryService, catalogV1Service);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private ApiCategoryEntity createSampleCategory(String name) {
        ApiCategoryEntity category = new ApiCategoryEntity();
        category.setId(UUID.randomUUID());
        category.setName(name);
        category.setDescription("Description for " + name);
        category.setIcon("icon-" + name.toLowerCase());
        category.setColor("#FF0000");
        category.setSortOrder(1);
        category.setSlug(name.toLowerCase().replace(" ", "-"));
        category.setCreatedAt(System.currentTimeMillis());
        category.setUpdatedAt(System.currentTimeMillis());
        return category;
    }

    private ApiSubcategoryEntity createSampleSubcategory(UUID categoryId, String name) {
        ApiSubcategoryEntity subcategory = new ApiSubcategoryEntity();
        subcategory.setId(UUID.randomUUID());
        subcategory.setCategoryId(categoryId);
        subcategory.setName(name);
        subcategory.setDescription("Description for " + name);
        subcategory.setIcon("icon-" + name.toLowerCase());
        subcategory.setColor("#00FF00");
        subcategory.setSortOrder(1);
        subcategory.setSlug(name.toLowerCase().replace(" ", "-"));
        subcategory.setCreatedAt(System.currentTimeMillis());
        subcategory.setUpdatedAt(System.currentTimeMillis());
        return subcategory;
    }

    @Nested
    @DisplayName("GET /api/catalog/categories")
    class GetAllCategories {

        @Test
        @DisplayName("should return empty list when no categories exist")
        void shouldReturnEmptyList() throws Exception {
            when(categoryService.getAllCategories()).thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/catalog/categories"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        @DisplayName("should return list of categories")
        void shouldReturnListOfCategories() throws Exception {
            ApiCategoryEntity cat1 = createSampleCategory("Technology");
            ApiCategoryEntity cat2 = createSampleCategory("Finance");
            when(categoryService.getAllCategories()).thenReturn(List.of(cat1, cat2));

            mockMvc.perform(get("/api/catalog/categories"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].name").value("Technology"))
                    .andExpect(jsonPath("$[1].name").value("Finance"));
        }

        @Test
        @DisplayName("should accept optional X-User-Id and X-Org-Id headers")
        void shouldAcceptOptionalHeaders() throws Exception {
            when(categoryService.getAllCategories()).thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/catalog/categories")
                            .header("X-User-ID", "user-123")
                            .header("X-Organization-ID", "org-456"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("should return 500 on service exception")
        void shouldReturn500OnException() throws Exception {
            when(categoryService.getAllCategories()).thenThrow(new RuntimeException("DB error"));

            mockMvc.perform(get("/api/catalog/categories"))
                    .andExpect(status().isInternalServerError());
        }
    }

    @Nested
    @DisplayName("GET /api/catalog/categories/{categoryId}/subcategories")
    class GetSubcategoriesByCategoryId {

        @Test
        @DisplayName("should return subcategories for a category")
        void shouldReturnSubcategories() throws Exception {
            ApiSubcategoryEntity sub1 = createSampleSubcategory(CATEGORY_ID, "Cloud");
            ApiSubcategoryEntity sub2 = createSampleSubcategory(CATEGORY_ID, "AI");
            when(categoryService.getSubcategoriesByCategoryId(CATEGORY_ID)).thenReturn(List.of(sub1, sub2));

            mockMvc.perform(get("/api/catalog/categories/{categoryId}/subcategories", CATEGORY_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].name").value("Cloud"))
                    .andExpect(jsonPath("$[1].name").value("AI"));
        }

        @Test
        @DisplayName("should return empty list when no subcategories exist")
        void shouldReturnEmptyList() throws Exception {
            when(categoryService.getSubcategoriesByCategoryId(CATEGORY_ID)).thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/catalog/categories/{categoryId}/subcategories", CATEGORY_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        @DisplayName("should return 500 on service exception")
        void shouldReturn500OnException() throws Exception {
            when(categoryService.getSubcategoriesByCategoryId(CATEGORY_ID))
                    .thenThrow(new RuntimeException("DB error"));

            mockMvc.perform(get("/api/catalog/categories/{categoryId}/subcategories", CATEGORY_ID))
                    .andExpect(status().isInternalServerError());
        }

        @Test
        @DisplayName("should accept optional headers")
        void shouldAcceptOptionalHeaders() throws Exception {
            when(categoryService.getSubcategoriesByCategoryId(CATEGORY_ID)).thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/catalog/categories/{categoryId}/subcategories", CATEGORY_ID)
                            .header("X-User-ID", "user-123")
                            .header("X-Organization-ID", "org-456"))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("GET /api/catalog/categories/subcategories")
    class GetAllSubcategories {

        @Test
        @DisplayName("should return all subcategories")
        void shouldReturnAllSubcategories() throws Exception {
            ApiSubcategoryEntity sub1 = createSampleSubcategory(CATEGORY_ID, "Cloud");
            when(categoryService.getAllSubcategories()).thenReturn(List.of(sub1));

            mockMvc.perform(get("/api/catalog/categories/subcategories"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].name").value("Cloud"));
        }

        @Test
        @DisplayName("should return empty list when no subcategories exist")
        void shouldReturnEmptyList() throws Exception {
            when(categoryService.getAllSubcategories()).thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/catalog/categories/subcategories"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        @DisplayName("should return 500 on service exception")
        void shouldReturn500OnException() throws Exception {
            when(categoryService.getAllSubcategories()).thenThrow(new RuntimeException("DB error"));

            mockMvc.perform(get("/api/catalog/categories/subcategories"))
                    .andExpect(status().isInternalServerError());
        }
    }

    @Nested
    @DisplayName("POST /api/catalog/categories/initialize")
    class InitializeDefaultCategories {

        @Test
        @DisplayName("should return 200 on successful initialization")
        void shouldReturn200OnSuccess() throws Exception {
            doNothing().when(categoryService).initializeDefaultCategories();

            mockMvc.perform(post("/api/catalog/categories/initialize"))
                    .andExpect(status().isOk());

            verify(categoryService).initializeDefaultCategories();
        }

        @Test
        @DisplayName("should return 500 on initialization failure")
        void shouldReturn500OnFailure() throws Exception {
            doThrow(new RuntimeException("Initialization failed")).when(categoryService).initializeDefaultCategories();

            mockMvc.perform(post("/api/catalog/categories/initialize"))
                    .andExpect(status().isInternalServerError());
        }

        @Test
        @DisplayName("should accept optional headers during initialization")
        void shouldAcceptOptionalHeaders() throws Exception {
            doNothing().when(categoryService).initializeDefaultCategories();

            mockMvc.perform(post("/api/catalog/categories/initialize")
                            .header("X-User-ID", "admin")
                            .header("X-Organization-ID", "org-123"))
                    .andExpect(status().isOk());
        }
    }
}
