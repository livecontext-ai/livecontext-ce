package com.apimarketplace.catalog.web;

import com.apimarketplace.catalog.domain.ToolCategoryEntity;
import com.apimarketplace.catalog.domain.ToolNameEntity;
import com.apimarketplace.catalog.service.ToolCategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ToolCategoryController")
class ToolCategoryControllerTest {

    private static final String ADMIN_TOKEN = "test-admin-token";

    @Mock
    private ToolCategoryService toolCategoryService;

    private ToolCategoryController controller;

    @BeforeEach
    void setUp() {
        controller = new ToolCategoryController(toolCategoryService);
        ReflectionTestUtils.setField(controller, "catalogAdminToken", ADMIN_TOKEN);
    }

    private ToolCategoryEntity buildCategory(String name) {
        ToolCategoryEntity entity = new ToolCategoryEntity();
        entity.setId(UUID.randomUUID());
        entity.setName(name);
        entity.setSlug(name.toLowerCase().replace(" ", "-"));
        entity.setIsActive(true);
        return entity;
    }

    private ToolNameEntity buildToolName(String name) {
        ToolNameEntity entity = new ToolNameEntity();
        entity.setId(UUID.randomUUID());
        entity.setName(name);
        entity.setIsActive(true);
        return entity;
    }

    @Nested
    @DisplayName("GET /api/tool-categories")
    class GetAllToolCategoriesTests {

        @Test
        @DisplayName("should return all categories")
        void returnsAllCategories() {
            List<ToolCategoryEntity> categories = List.of(
                    buildCategory("Social Media"),
                    buildCategory("Search Engines")
            );

            when(toolCategoryService.getAllToolCategories()).thenReturn(categories);

            ResponseEntity<List<ToolCategoryEntity>> response =
                    controller.getAllToolCategories("user-1", "org-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).hasSize(2);
        }

        @Test
        @DisplayName("should return empty list when no categories")
        void returnsEmptyList() {
            when(toolCategoryService.getAllToolCategories()).thenReturn(Collections.emptyList());

            ResponseEntity<List<ToolCategoryEntity>> response =
                    controller.getAllToolCategories(null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEmpty();
        }
    }

    @Nested
    @DisplayName("GET /api/tool-categories/by-name/{name}")
    class GetToolCategoryByNameTests {

        @Test
        @DisplayName("should return category when found")
        void returnsCategoryWhenFound() {
            ToolCategoryEntity category = buildCategory("Social Media");

            when(toolCategoryService.getToolCategoryByName("Social Media"))
                    .thenReturn(Optional.of(category));

            ResponseEntity<ToolCategoryEntity> response =
                    controller.getToolCategoryByName("Social Media");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getName()).isEqualTo("Social Media");
        }

        @Test
        @DisplayName("should return 404 when not found")
        void returns404WhenNotFound() {
            when(toolCategoryService.getToolCategoryByName("Unknown"))
                    .thenReturn(Optional.empty());

            ResponseEntity<ToolCategoryEntity> response =
                    controller.getToolCategoryByName("Unknown");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("GET /api/tool-categories/{categoryId}/tool-names")
    class GetToolNamesByCategoryTests {

        @Test
        @DisplayName("should return tool names by category")
        void returnsToolNamesByCategory() {
            UUID categoryId = UUID.randomUUID();
            List<ToolNameEntity> toolNames = List.of(
                    buildToolName("Search Tool"),
                    buildToolName("Post Tool")
            );

            when(toolCategoryService.getToolNamesByCategory(categoryId)).thenReturn(toolNames);

            ResponseEntity<List<ToolNameEntity>> response =
                    controller.getToolNamesByCategory(categoryId, null, "user-1", "org-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).hasSize(2);
        }

        @Test
        @DisplayName("should filter by runScopes when provided")
        void filtersByRunScopes() {
            UUID categoryId = UUID.randomUUID();
            String[] runScopes = {"workflow", "agent"};
            List<ToolNameEntity> toolNames = List.of(buildToolName("Workflow Tool"));

            when(toolCategoryService.getToolNamesByCategoryAndRunScopes(eq(categoryId), anyList()))
                    .thenReturn(toolNames);

            ResponseEntity<List<ToolNameEntity>> response =
                    controller.getToolNamesByCategory(categoryId, runScopes, "user-1", "org-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).hasSize(1);
            verify(toolCategoryService).getToolNamesByCategoryAndRunScopes(eq(categoryId), anyList());
        }

        @Test
        @DisplayName("should not filter by runScopes when empty array")
        void doesNotFilterByEmptyRunScopes() {
            UUID categoryId = UUID.randomUUID();
            String[] runScopes = {};

            when(toolCategoryService.getToolNamesByCategory(categoryId)).thenReturn(Collections.emptyList());

            controller.getToolNamesByCategory(categoryId, runScopes, null, null);

            verify(toolCategoryService).getToolNamesByCategory(categoryId);
            verify(toolCategoryService, never()).getToolNamesByCategoryAndRunScopes(any(), any());
        }
    }

    @Nested
    @DisplayName("GET /api/tool-categories/tool-names")
    class GetAllToolNamesTests {

        @Test
        @DisplayName("should return all tool names")
        void returnsAllToolNames() {
            List<ToolNameEntity> toolNames = List.of(
                    buildToolName("Tool A"),
                    buildToolName("Tool B")
            );

            when(toolCategoryService.getAllToolNames()).thenReturn(toolNames);

            ResponseEntity<List<ToolNameEntity>> response =
                    controller.getAllToolNames(null, "user-1", "org-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).hasSize(2);
        }

        @Test
        @DisplayName("should filter by runScopes when provided")
        void filtersByRunScopes() {
            String[] runScopes = {"workflow"};
            List<ToolNameEntity> toolNames = List.of(buildToolName("Filtered Tool"));

            when(toolCategoryService.getToolNamesByRunScopes(anyList())).thenReturn(toolNames);

            ResponseEntity<List<ToolNameEntity>> response =
                    controller.getAllToolNames(runScopes, null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).hasSize(1);
            verify(toolCategoryService).getToolNamesByRunScopes(anyList());
        }
    }

    @Nested
    @DisplayName("GET /api/tool-categories/by-subcategory/{subcategoryId}")
    class GetToolCategoriesBySubcategoryTests {

        @Test
        @DisplayName("should return categories by subcategory")
        void returnsCategoriesBySubcategory() {
            UUID subcategoryId = UUID.randomUUID();
            List<ToolCategoryEntity> categories = List.of(buildCategory("Category A"));

            when(toolCategoryService.getToolCategoriesBySubcategory(subcategoryId))
                    .thenReturn(categories);

            ResponseEntity<List<ToolCategoryEntity>> response =
                    controller.getToolCategoriesBySubcategory(subcategoryId, null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("GET /api/tool-categories/by-subcategory-name/{subcategoryName}")
    class GetToolCategoriesBySubcategoryNameTests {

        @Test
        @DisplayName("should return paginated categories by subcategory name")
        void returnsPaginatedCategories() {
            List<ToolCategoryEntity> categories = List.of(buildCategory("Category A"));

            when(toolCategoryService.getToolCategoriesBySubcategoryName("ai-tools", 0, 20))
                    .thenReturn(categories);

            ResponseEntity<List<ToolCategoryEntity>> response =
                    controller.getToolCategoriesBySubcategoryName("ai-tools", 0, 20, null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("GET /api/tool-categories/tool-names/search")
    class SearchToolNamesTests {

        @Test
        @DisplayName("should return matching tool names")
        void returnsMatchingToolNames() {
            List<ToolNameEntity> toolNames = List.of(buildToolName("Search API"));

            when(toolCategoryService.searchToolNames("search")).thenReturn(toolNames);

            ResponseEntity<List<ToolNameEntity>> response = controller.searchToolNames("search");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("GET /api/tool-categories/tool-names/by-name/{name}")
    class GetToolNamesByNameTests {

        @Test
        @DisplayName("should return tool names when found")
        void returnsToolNamesWhenFound() {
            List<ToolNameEntity> toolNames = List.of(buildToolName("My Tool"));

            when(toolCategoryService.getToolNamesByName("My Tool")).thenReturn(toolNames);

            ResponseEntity<List<ToolNameEntity>> response =
                    controller.getToolNamesByName("My Tool");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).hasSize(1);
        }

        @Test
        @DisplayName("should return 404 when not found")
        void returns404WhenNotFound() {
            when(toolCategoryService.getToolNamesByName("Unknown")).thenReturn(Collections.emptyList());

            ResponseEntity<List<ToolNameEntity>> response =
                    controller.getToolNamesByName("Unknown");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("GET /api/tool-categories/tool-names/by-name/{name}/category/{categoryId}")
    class GetToolNameByNameAndCategoryTests {

        @Test
        @DisplayName("should return tool name when found")
        void returnsToolNameWhenFound() {
            UUID categoryId = UUID.randomUUID();
            ToolNameEntity toolName = buildToolName("My Tool");

            when(toolCategoryService.getToolNameByNameAndCategory("My Tool", categoryId))
                    .thenReturn(Optional.of(toolName));

            ResponseEntity<ToolNameEntity> response =
                    controller.getToolNameByNameAndCategory("My Tool", categoryId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getName()).isEqualTo("My Tool");
        }

        @Test
        @DisplayName("should return 404 when not found")
        void returns404WhenNotFound() {
            UUID categoryId = UUID.randomUUID();

            when(toolCategoryService.getToolNameByNameAndCategory("Unknown", categoryId))
                    .thenReturn(Optional.empty());

            ResponseEntity<ToolNameEntity> response =
                    controller.getToolNameByNameAndCategory("Unknown", categoryId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("POST /api/tool-categories")
    class CreateToolCategoryTests {

        @Test
        @DisplayName("should create a tool category")
        void createsToolCategory() {
            ToolCategoryEntity category = buildCategory("New Category");

            when(toolCategoryService.createToolCategory(category)).thenReturn(category);

            ResponseEntity<ToolCategoryEntity> response = controller.createToolCategory(category, ADMIN_TOKEN);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getName()).isEqualTo("New Category");
        }
    }

    @Nested
    @DisplayName("POST /api/tool-categories/tool-names")
    class CreateToolNameTests {

        @Test
        @DisplayName("should create a tool name")
        void createsToolName() {
            ToolNameEntity toolName = buildToolName("New Tool");

            when(toolCategoryService.createToolName(toolName)).thenReturn(toolName);

            ResponseEntity<ToolNameEntity> response = controller.createToolName(toolName, ADMIN_TOKEN);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getName()).isEqualTo("New Tool");
        }
    }

    @Nested
    @DisplayName("PUT /api/tool-categories/{categoryId}")
    class UpdateToolCategoryTests {

        @Test
        @DisplayName("should update a tool category and set ID from path")
        void updatesToolCategory() {
            UUID categoryId = UUID.randomUUID();
            ToolCategoryEntity category = buildCategory("Updated");

            when(toolCategoryService.updateToolCategory(category)).thenReturn(category);

            ResponseEntity<ToolCategoryEntity> response =
                    controller.updateToolCategory(categoryId, category, ADMIN_TOKEN);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(category.getId()).isEqualTo(categoryId);
        }
    }

    @Nested
    @DisplayName("PUT /api/tool-categories/tool-names/{toolNameId}")
    class UpdateToolNameTests {

        @Test
        @DisplayName("should update a tool name and set ID from path")
        void updatesToolName() {
            UUID toolNameId = UUID.randomUUID();
            ToolNameEntity toolName = buildToolName("Updated Tool");

            when(toolCategoryService.updateToolName(toolName)).thenReturn(toolName);

            ResponseEntity<ToolNameEntity> response =
                    controller.updateToolName(toolNameId, toolName, ADMIN_TOKEN);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(toolName.getId()).isEqualTo(toolNameId);
        }
    }

    @Nested
    @DisplayName("DELETE /api/tool-categories/{categoryId}")
    class DeleteToolCategoryTests {

        @Test
        @DisplayName("should delete a tool category")
        void deletesToolCategory() {
            UUID categoryId = UUID.randomUUID();

            doNothing().when(toolCategoryService).deleteToolCategory(categoryId);

            ResponseEntity<Void> response = controller.deleteToolCategory(categoryId, ADMIN_TOKEN);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(toolCategoryService).deleteToolCategory(categoryId);
        }

        @Test
        @DisplayName("should reject category deletion without admin token")
        void rejectsDeleteWithoutAdminToken() {
            UUID categoryId = UUID.randomUUID();

            ResponseEntity<Void> response = controller.deleteToolCategory(categoryId, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            verify(toolCategoryService, never()).deleteToolCategory(any());
        }
    }

    @Nested
    @DisplayName("DELETE /api/tool-categories/tool-names/{toolNameId}")
    class DeleteToolNameTests {

        @Test
        @DisplayName("should delete a tool name")
        void deletesToolName() {
            UUID toolNameId = UUID.randomUUID();

            doNothing().when(toolCategoryService).deleteToolName(toolNameId);

            ResponseEntity<Void> response = controller.deleteToolName(toolNameId, ADMIN_TOKEN);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(toolCategoryService).deleteToolName(toolNameId);
        }

        @Test
        @DisplayName("should reject tool name deletion without admin token")
        void rejectsDeleteWithoutAdminToken() {
            UUID toolNameId = UUID.randomUUID();

            ResponseEntity<Void> response = controller.deleteToolName(toolNameId, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            verify(toolCategoryService, never()).deleteToolName(any());
        }
    }
}
