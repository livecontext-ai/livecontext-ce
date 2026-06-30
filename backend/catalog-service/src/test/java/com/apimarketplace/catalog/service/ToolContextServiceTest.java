package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.*;
import com.apimarketplace.catalog.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ToolContextService.
 *
 * ToolContextService loads tool context data for mapping generation.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ToolContextService")
class ToolContextServiceTest {

    @Mock
    private ApiRepository apiRepository;

    @Mock
    private ApiToolRepository apiToolRepository;

    @Mock
    private ToolNameRepository toolNameRepository;

    @Mock
    private ToolCategoryRepository toolCategoryRepository;

    @Mock
    private ApiSubcategoryRepository apiSubcategoryRepository;

    @Mock
    private ApiToolParameterRepository apiToolParameterRepository;

    @Mock
    private ToolCategoryService toolCategoryService;

    private ToolContextService service;

    @BeforeEach
    void setUp() {
        service = new ToolContextService(
            apiRepository, apiToolRepository, toolNameRepository,
            toolCategoryRepository, apiSubcategoryRepository,
            apiToolParameterRepository, toolCategoryService
        );
    }

    // ========================================================================
    // loadToolContext() - UUID tests
    // ========================================================================

    @Nested
    @DisplayName("loadToolContext() - UUID")
    class LoadToolContextByUuidTests {

        @Test
        @DisplayName("should return empty optional when tool not found by UUID")
        void shouldReturnEmptyOptionalWhenToolNotFoundByUuid() {
            UUID toolId = UUID.randomUUID();
            when(apiToolRepository.findById(toolId)).thenReturn(Optional.empty());

            Optional<ToolContextService.ToolContext> result = service.loadToolContext(toolId.toString());

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should load tool context by UUID")
        void shouldLoadToolContextByUuid() {
            UUID toolId = UUID.randomUUID();
            UUID apiId = UUID.randomUUID();

            ApiToolEntity tool = new ApiToolEntity();
            tool.setId(toolId);
            tool.setApiId(apiId);
            tool.setToolNameId(null);
            tool.setDescription("Send an email");
            tool.setMethod("POST");
            tool.setEndpoint("/send");

            ApiEntity api = new ApiEntity();
            api.setId(apiId);
            api.setIconSlug("gmail");

            when(apiToolRepository.findById(toolId)).thenReturn(Optional.of(tool));
            when(apiRepository.findById(apiId)).thenReturn(Optional.of(api));
            when(toolCategoryService.getToolNameByToolNameId(any())).thenReturn(Optional.empty());
            when(apiToolParameterRepository.findByApiToolId(toolId)).thenReturn(List.of());

            Optional<ToolContextService.ToolContext> result = service.loadToolContext(toolId.toString());

            assertTrue(result.isPresent());
            assertEquals(toolId.toString(), result.get().getToolId());
            assertEquals(apiId.toString(), result.get().getApiId());
            assertEquals("Send an email", result.get().getToolDescription());
            assertEquals("POST", result.get().getHttpMethod());
            assertEquals("/send", result.get().getEndpoint());
            assertEquals("gmail", result.get().getIconSlug());
        }

        @Test
        @DisplayName("should load tool context with tool name from tool_names table")
        void shouldLoadToolContextWithToolNameFromToolNamesTable() {
            UUID toolId = UUID.randomUUID();
            UUID apiId = UUID.randomUUID();
            UUID toolNameUuid = UUID.randomUUID();
            UUID categoryId = UUID.randomUUID();

            ApiToolEntity tool = new ApiToolEntity();
            tool.setId(toolId);
            tool.setApiId(apiId);
            tool.setToolNameId(toolNameUuid.toString());
            tool.setDescription("Tool description");
            tool.setMethod("GET");
            tool.setEndpoint("/users");

            ApiEntity api = new ApiEntity();
            api.setId(apiId);
            api.setIconSlug("mcp");

            ToolNameEntity toolName = new ToolNameEntity();
            toolName.setId(toolNameUuid);
            toolName.setName("List Users");
            toolName.setDescription("List all users");
            toolName.setToolCategoryId(categoryId);

            ToolCategoryEntity category = new ToolCategoryEntity();
            category.setId(categoryId);
            category.setName("User Management");

            when(apiToolRepository.findById(toolId)).thenReturn(Optional.of(tool));
            when(apiRepository.findById(apiId)).thenReturn(Optional.of(api));
            when(toolCategoryService.getToolNameByToolNameId(toolNameUuid.toString()))
                .thenReturn(Optional.of(toolName));
            when(toolNameRepository.findById(toolNameUuid)).thenReturn(Optional.of(toolName));
            when(toolCategoryRepository.findById(categoryId)).thenReturn(Optional.of(category));
            when(apiToolParameterRepository.findByApiToolId(toolId)).thenReturn(List.of());

            Optional<ToolContextService.ToolContext> result = service.loadToolContext(toolId.toString());

            assertTrue(result.isPresent());
            assertEquals("List Users", result.get().getToolName());
            assertEquals("List all users", result.get().getToolDescription());
            assertEquals("User Management", result.get().getToolCategoryName());
        }
    }

    // ========================================================================
    // loadToolContext() - Slug tests
    // ========================================================================

    @Nested
    @DisplayName("loadToolContext() - Slug")
    class LoadToolContextBySlugTests {

        @Test
        @DisplayName("should return empty optional when tool not found by slug")
        void shouldReturnEmptyOptionalWhenToolNotFoundBySlug() {
            when(apiToolRepository.findByToolSlug("unknown-tool")).thenReturn(Optional.empty());

            Optional<ToolContextService.ToolContext> result = service.loadToolContext("unknown-tool");

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should load tool context by simple slug")
        void shouldLoadToolContextBySimpleSlug() {
            UUID toolId = UUID.randomUUID();
            UUID apiId = UUID.randomUUID();

            ApiToolEntity tool = new ApiToolEntity();
            tool.setId(toolId);
            tool.setApiId(apiId);
            tool.setToolSlug("gmail-api-send-email");
            tool.setToolNameId(null);
            tool.setDescription("Send an email");
            tool.setMethod("POST");
            tool.setEndpoint("/send");

            ApiEntity api = new ApiEntity();
            api.setId(apiId);
            api.setIconSlug("gmail");

            when(apiToolRepository.findByToolSlug("gmail-api-send-email")).thenReturn(Optional.of(tool));
            when(apiRepository.findById(apiId)).thenReturn(Optional.of(api));
            when(toolCategoryService.getToolNameByToolNameId(any())).thenReturn(Optional.empty());
            when(apiToolParameterRepository.findByApiToolId(toolId)).thenReturn(List.of());

            Optional<ToolContextService.ToolContext> result = service.loadToolContext("gmail-api-send-email");

            assertTrue(result.isPresent());
            assertEquals(toolId.toString(), result.get().getToolId());
        }

        @Test
        @DisplayName("should extract tool slug from api-slug/tool-slug format")
        void shouldExtractToolSlugFromCompoundFormat() {
            UUID toolId = UUID.randomUUID();
            UUID apiId = UUID.randomUUID();

            ApiToolEntity tool = new ApiToolEntity();
            tool.setId(toolId);
            tool.setApiId(apiId);
            tool.setToolSlug("send-email");
            tool.setToolNameId(null);
            tool.setDescription("Send an email");
            tool.setMethod("POST");
            tool.setEndpoint("/send");

            ApiEntity api = new ApiEntity();
            api.setId(apiId);
            api.setIconSlug("gmail");

            when(apiToolRepository.findByToolSlug("send-email")).thenReturn(Optional.of(tool));
            when(apiRepository.findById(apiId)).thenReturn(Optional.of(api));
            when(toolCategoryService.getToolNameByToolNameId(any())).thenReturn(Optional.empty());
            when(apiToolParameterRepository.findByApiToolId(toolId)).thenReturn(List.of());

            Optional<ToolContextService.ToolContext> result = service.loadToolContext("gmail-api/send-email");

            assertTrue(result.isPresent());
            verify(apiToolRepository).findByToolSlug("send-email");
        }
    }

    // ========================================================================
    // loadToolContext() - Parameters tests
    // ========================================================================

    @Nested
    @DisplayName("loadToolContext() - Parameters")
    class LoadToolContextParametersTests {

        @Test
        @DisplayName("should load allowed parameter names")
        void shouldLoadAllowedParameterNames() {
            UUID toolId = UUID.randomUUID();
            UUID apiId = UUID.randomUUID();

            ApiToolEntity tool = new ApiToolEntity();
            tool.setId(toolId);
            tool.setApiId(apiId);
            tool.setToolNameId(null);
            tool.setDescription("Send email");
            tool.setMethod("POST");

            ApiEntity api = new ApiEntity();
            api.setId(apiId);
            api.setIconSlug("mcp");

            ApiToolParameterEntity param1 = new ApiToolParameterEntity();
            param1.setName("to");
            ApiToolParameterEntity param2 = new ApiToolParameterEntity();
            param2.setName("subject");
            ApiToolParameterEntity param3 = new ApiToolParameterEntity();
            param3.setName("body");

            when(apiToolRepository.findById(toolId)).thenReturn(Optional.of(tool));
            when(apiRepository.findById(apiId)).thenReturn(Optional.of(api));
            when(toolCategoryService.getToolNameByToolNameId(any())).thenReturn(Optional.empty());
            when(apiToolParameterRepository.findByApiToolId(toolId))
                .thenReturn(List.of(param1, param2, param3));

            Optional<ToolContextService.ToolContext> result = service.loadToolContext(toolId.toString());

            assertTrue(result.isPresent());
            Set<String> paramNames = result.get().getAllowedParameterNames();
            assertEquals(3, paramNames.size());
            assertTrue(paramNames.contains("to"));
            assertTrue(paramNames.contains("subject"));
            assertTrue(paramNames.contains("body"));
        }

        @Test
        @DisplayName("should return empty parameter set when no parameters")
        void shouldReturnEmptyParameterSetWhenNoParameters() {
            UUID toolId = UUID.randomUUID();
            UUID apiId = UUID.randomUUID();

            ApiToolEntity tool = new ApiToolEntity();
            tool.setId(toolId);
            tool.setApiId(apiId);
            tool.setToolNameId(null);
            tool.setMethod("GET");

            ApiEntity api = new ApiEntity();
            api.setId(apiId);
            api.setIconSlug("mcp");

            when(apiToolRepository.findById(toolId)).thenReturn(Optional.of(tool));
            when(apiRepository.findById(apiId)).thenReturn(Optional.of(api));
            when(toolCategoryService.getToolNameByToolNameId(any())).thenReturn(Optional.empty());
            when(apiToolParameterRepository.findByApiToolId(toolId)).thenReturn(List.of());

            Optional<ToolContextService.ToolContext> result = service.loadToolContext(toolId.toString());

            assertTrue(result.isPresent());
            assertTrue(result.get().getAllowedParameterNames().isEmpty());
        }
    }

    // ========================================================================
    // loadToolContext() - Subcategory tests
    // ========================================================================

    @Nested
    @DisplayName("loadToolContext() - Subcategory")
    class LoadToolContextSubcategoryTests {

        @Test
        @DisplayName("should load subcategory when available")
        void shouldLoadSubcategoryWhenAvailable() {
            UUID toolId = UUID.randomUUID();
            UUID apiId = UUID.randomUUID();
            UUID toolNameUuid = UUID.randomUUID();
            UUID categoryId = UUID.randomUUID();
            UUID subcategoryId = UUID.randomUUID();

            ApiToolEntity tool = new ApiToolEntity();
            tool.setId(toolId);
            tool.setApiId(apiId);
            tool.setToolNameId(toolNameUuid.toString());
            tool.setMethod("GET");

            ApiEntity api = new ApiEntity();
            api.setId(apiId);
            api.setIconSlug("mcp");

            ToolNameEntity toolName = new ToolNameEntity();
            toolName.setId(toolNameUuid);
            toolName.setName("Tool Name");
            toolName.setToolCategoryId(categoryId);
            toolName.setSubcategoryId(subcategoryId);

            ToolCategoryEntity category = new ToolCategoryEntity();
            category.setId(categoryId);
            category.setName("Category");

            ApiSubcategoryEntity subcategory = new ApiSubcategoryEntity();
            subcategory.setId(subcategoryId);
            subcategory.setName("Subcategory");

            when(apiToolRepository.findById(toolId)).thenReturn(Optional.of(tool));
            when(apiRepository.findById(apiId)).thenReturn(Optional.of(api));
            when(toolCategoryService.getToolNameByToolNameId(toolNameUuid.toString()))
                .thenReturn(Optional.of(toolName));
            when(toolNameRepository.findById(toolNameUuid)).thenReturn(Optional.of(toolName));
            when(toolCategoryRepository.findById(categoryId)).thenReturn(Optional.of(category));
            when(apiSubcategoryRepository.findById(subcategoryId)).thenReturn(Optional.of(subcategory));
            when(apiToolParameterRepository.findByApiToolId(toolId)).thenReturn(List.of());

            Optional<ToolContextService.ToolContext> result = service.loadToolContext(toolId.toString());

            assertTrue(result.isPresent());
            assertEquals("Category", result.get().getToolCategoryName());
            assertEquals("Subcategory", result.get().getToolSubCategoryName());
        }
    }

    // ========================================================================
    // loadToolContext() - Error handling tests
    // ========================================================================

    @Nested
    @DisplayName("loadToolContext() - Error handling")
    class LoadToolContextErrorHandlingTests {

        @Test
        @DisplayName("should handle invalid UUID format")
        void shouldHandleInvalidUuidFormat() {
            // This looks like a UUID but is invalid
            String invalidUuid = "not-a-valid-uuid-format";

            when(apiToolRepository.findByToolSlug(invalidUuid)).thenReturn(Optional.empty());

            Optional<ToolContextService.ToolContext> result = service.loadToolContext(invalidUuid);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should handle null apiId")
        void shouldHandleNullApiId() {
            UUID toolId = UUID.randomUUID();

            ApiToolEntity tool = new ApiToolEntity();
            tool.setId(toolId);
            tool.setApiId(null);
            tool.setToolNameId(null);
            tool.setMethod("GET");

            when(apiToolRepository.findById(toolId)).thenReturn(Optional.of(tool));
            when(toolCategoryService.getToolNameByToolNameId(any())).thenReturn(Optional.empty());
            when(apiToolParameterRepository.findByApiToolId(toolId)).thenReturn(List.of());

            Optional<ToolContextService.ToolContext> result = service.loadToolContext(toolId.toString());

            assertTrue(result.isPresent());
            assertNull(result.get().getApiId());
        }

        @Test
        @DisplayName("should use default icon slug when API icon is null")
        void shouldUseDefaultIconSlugWhenApiIconIsNull() {
            UUID toolId = UUID.randomUUID();
            UUID apiId = UUID.randomUUID();

            ApiToolEntity tool = new ApiToolEntity();
            tool.setId(toolId);
            tool.setApiId(apiId);
            tool.setToolNameId(null);
            tool.setMethod("GET");

            ApiEntity api = new ApiEntity();
            api.setId(apiId);
            api.setIconSlug(null);

            when(apiToolRepository.findById(toolId)).thenReturn(Optional.of(tool));
            when(apiRepository.findById(apiId)).thenReturn(Optional.of(api));
            when(toolCategoryService.getToolNameByToolNameId(any())).thenReturn(Optional.empty());
            when(apiToolParameterRepository.findByApiToolId(toolId)).thenReturn(List.of());

            Optional<ToolContextService.ToolContext> result = service.loadToolContext(toolId.toString());

            assertTrue(result.isPresent());
            assertEquals("mcp", result.get().getIconSlug());
        }

        @Test
        @DisplayName("should handle invalid toolNameId format")
        void shouldHandleInvalidToolNameIdFormat() {
            UUID toolId = UUID.randomUUID();
            UUID apiId = UUID.randomUUID();

            ApiToolEntity tool = new ApiToolEntity();
            tool.setId(toolId);
            tool.setApiId(apiId);
            tool.setToolNameId("invalid-uuid");
            tool.setMethod("GET");

            ApiEntity api = new ApiEntity();
            api.setId(apiId);
            api.setIconSlug("mcp");

            when(apiToolRepository.findById(toolId)).thenReturn(Optional.of(tool));
            when(apiRepository.findById(apiId)).thenReturn(Optional.of(api));
            when(toolCategoryService.getToolNameByToolNameId("invalid-uuid")).thenReturn(Optional.empty());
            when(apiToolParameterRepository.findByApiToolId(toolId)).thenReturn(List.of());

            Optional<ToolContextService.ToolContext> result = service.loadToolContext(toolId.toString());

            // Should still return context, just with "Unknown Tool" name
            assertTrue(result.isPresent());
        }
    }

    // ========================================================================
    // ToolContext class tests
    // ========================================================================

    @Nested
    @DisplayName("ToolContext class")
    class ToolContextClassTests {

        @Test
        @DisplayName("should get and set all fields")
        void shouldGetAndSetAllFields() {
            ToolContextService.ToolContext context = new ToolContextService.ToolContext();

            context.setToolId("tool-id");
            context.setApiId("api-id");
            context.setToolName("Tool Name");
            context.setToolNameId("tool-name-id");
            context.setToolDescription("Description");
            context.setToolCategoryName("Category");
            context.setToolSubCategoryName("Subcategory");
            context.setHttpMethod("POST");
            context.setEndpoint("/endpoint");
            context.setToolDescriptionFull("Full description");
            context.setIconSlug("icon");
            context.setAllowedParameterNames(Set.of("param1", "param2"));

            assertEquals("tool-id", context.getToolId());
            assertEquals("api-id", context.getApiId());
            assertEquals("Tool Name", context.getToolName());
            assertEquals("tool-name-id", context.getToolNameId());
            assertEquals("Description", context.getToolDescription());
            assertEquals("Category", context.getToolCategoryName());
            assertEquals("Subcategory", context.getToolSubCategoryName());
            assertEquals("POST", context.getHttpMethod());
            assertEquals("/endpoint", context.getEndpoint());
            assertEquals("Full description", context.getToolDescriptionFull());
            assertEquals("icon", context.getIconSlug());
            assertEquals(2, context.getAllowedParameterNames().size());
        }
    }
}
