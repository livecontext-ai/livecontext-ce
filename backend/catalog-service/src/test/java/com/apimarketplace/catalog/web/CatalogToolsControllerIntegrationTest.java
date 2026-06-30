package com.apimarketplace.catalog.web;

import com.apimarketplace.catalog.config.GlobalExceptionHandler;
import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.ApiToolParameterEntity;
import com.apimarketplace.catalog.domain.ToolNameEntity;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.catalog.repository.ToolNameRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.*;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogToolsController Integration Tests")
class CatalogToolsControllerIntegrationTest {

    @Mock
    private ApiToolRepository apiToolRepository;

    @Mock
    private ApiRepository apiRepository;

    @Mock
    private ApiToolParameterRepository apiToolParameterRepository;

    @Mock
    private ToolNameRepository toolNameRepository;

    private MockMvc mockMvc;

    private static final UUID TOOL_ID = UUID.randomUUID();
    private static final UUID API_ID = UUID.randomUUID();
    private static final UUID TOOL_NAME_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        CatalogToolsController controller = new CatalogToolsController(
                apiToolRepository, apiRepository, apiToolParameterRepository, toolNameRepository);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private ApiToolEntity createSampleTool() {
        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(TOOL_ID);
        tool.setApiId(API_ID);
        tool.setToolSlug("get-users");
        tool.setDescription("Get all users");
        tool.setVersion("1.0");
        tool.setIsActive(true);
        tool.setStatus("active");
        tool.setMethod("GET");
        tool.setEndpoint("/users");
        tool.setToolNameId(TOOL_NAME_ID.toString());
        return tool;
    }

    private ApiEntity createSampleApi() {
        ApiEntity api = new ApiEntity();
        api.setId(API_ID);
        api.setApiName("Test API");
        api.setBaseUrl("https://api.example.com");
        api.setAuthType("bearer");
        api.setAuthHeaderName("Authorization");
        api.setAuthHeaderValue("Bearer token123");
        api.setIconSlug("test-icon");
        return api;
    }

    private ToolNameEntity createSampleToolName() {
        ToolNameEntity toolName = new ToolNameEntity();
        toolName.setId(TOOL_NAME_ID);
        toolName.setName("Get Users");
        return toolName;
    }

    @Nested
    @DisplayName("GET /api/catalog/tools/{toolId}/info")
    class GetToolInfo {

        @Test
        @DisplayName("should return tool info for valid UUID")
        void shouldReturnToolInfo() throws Exception {
            ApiToolEntity tool = createSampleTool();
            ApiEntity api = createSampleApi();
            ToolNameEntity toolName = createSampleToolName();

            when(apiToolRepository.findById(TOOL_ID)).thenReturn(Optional.of(tool));
            when(apiRepository.findById(API_ID)).thenReturn(Optional.of(api));
            when(apiToolParameterRepository.findByApiToolId(TOOL_ID)).thenReturn(Collections.emptyList());
            when(toolNameRepository.findById(TOOL_NAME_ID)).thenReturn(Optional.of(toolName));

            mockMvc.perform(get("/api/catalog/tools/{toolId}/info", TOOL_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(TOOL_ID.toString()))
                    .andExpect(jsonPath("$.name").value("Get Users"))
                    .andExpect(jsonPath("$.description").value("Get all users"))
                    .andExpect(jsonPath("$.method").value("GET"))
                    .andExpect(jsonPath("$.endpoint").value("/users"))
                    .andExpect(jsonPath("$.isActive").value(true))
                    .andExpect(jsonPath("$.api.id").value(API_ID.toString()))
                    .andExpect(jsonPath("$.api.name").value("Test API"))
                    .andExpect(jsonPath("$.api.baseUrl").value("https://api.example.com"))
                    .andExpect(jsonPath("$.iconSlug").value("test-icon"));
        }

        @Test
        @DisplayName("should return tool info with parameters")
        void shouldReturnToolInfoWithParameters() throws Exception {
            ApiToolEntity tool = createSampleTool();
            ApiEntity api = createSampleApi();

            ApiToolParameterEntity param = new ApiToolParameterEntity();
            param.setName("userId");
            param.setParameterType("query");
            param.setDataType("string");
            param.setIsRequired(true);
            param.setDescription("The user ID");
            param.setExampleValue("123");

            when(apiToolRepository.findById(TOOL_ID)).thenReturn(Optional.of(tool));
            when(apiRepository.findById(API_ID)).thenReturn(Optional.of(api));
            when(apiToolParameterRepository.findByApiToolId(TOOL_ID)).thenReturn(List.of(param));
            when(toolNameRepository.findById(TOOL_NAME_ID)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/catalog/tools/{toolId}/info", TOOL_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.parameters", hasSize(1)))
                    .andExpect(jsonPath("$.parameters[0].name").value("userId"))
                    .andExpect(jsonPath("$.parameters[0].type").value("query"))
                    .andExpect(jsonPath("$.parameters[0].required").value(true));
        }

        @Test
        @DisplayName("should expose defaultValue + allowedValues per param so the inspector dropdown lights up")
        void shouldExposeDefaultValueAndAllowedValues() throws Exception {
            ApiToolEntity tool = createSampleTool();
            ApiEntity api = createSampleApi();

            ApiToolParameterEntity modelParam = new ApiToolParameterEntity();
            modelParam.setName("model");
            modelParam.setParameterType("body");
            modelParam.setDataType("string");
            modelParam.setIsRequired(true);
            modelParam.setDescription("Model ID");
            modelParam.setDefaultValue("gpt-4o");
            // Stored as JSON-array literal (TEXT column); the controller parses it via AllowedValuesParser.
            modelParam.setAllowedValues("[\"gpt-4o\",\"gpt-4o-mini\",\"gpt-4.1\"]");

            ApiToolParameterEntity tempParam = new ApiToolParameterEntity();
            tempParam.setName("temperature");
            tempParam.setParameterType("body");
            tempParam.setDataType("number");
            tempParam.setIsRequired(false);
            tempParam.setDescription("Sampling temperature");
            tempParam.setDefaultValue("1");
            // No allowedValues - parser must return null so JSON serializes as null (not "[]").
            tempParam.setAllowedValues(null);

            when(apiToolRepository.findById(TOOL_ID)).thenReturn(Optional.of(tool));
            when(apiRepository.findById(API_ID)).thenReturn(Optional.of(api));
            when(apiToolParameterRepository.findByApiToolId(TOOL_ID))
                    .thenReturn(List.of(modelParam, tempParam));
            when(toolNameRepository.findById(TOOL_NAME_ID)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/catalog/tools/{toolId}/info", TOOL_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.parameters", hasSize(2)))
                    .andExpect(jsonPath("$.parameters[0].name").value("model"))
                    .andExpect(jsonPath("$.parameters[0].defaultValue").value("gpt-4o"))
                    .andExpect(jsonPath("$.parameters[0].allowedValues", hasSize(3)))
                    .andExpect(jsonPath("$.parameters[0].allowedValues[1]").value("gpt-4o-mini"))
                    .andExpect(jsonPath("$.parameters[1].name").value("temperature"))
                    .andExpect(jsonPath("$.parameters[1].defaultValue").value("1"))
                    .andExpect(jsonPath("$.parameters[1].allowedValues").isEmpty());
        }

        @Test
        @DisplayName("should return tool slug as name when tool name not found")
        void shouldReturnSlugWhenNameNotFound() throws Exception {
            ApiToolEntity tool = createSampleTool();
            ApiEntity api = createSampleApi();

            when(apiToolRepository.findById(TOOL_ID)).thenReturn(Optional.of(tool));
            when(apiRepository.findById(API_ID)).thenReturn(Optional.of(api));
            when(apiToolParameterRepository.findByApiToolId(TOOL_ID)).thenReturn(Collections.emptyList());
            when(toolNameRepository.findById(TOOL_NAME_ID)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/catalog/tools/{toolId}/info", TOOL_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("get-users"));
        }

        @Test
        @DisplayName("should return 400 for invalid UUID format")
        void shouldReturn400ForInvalidUuid() throws Exception {
            mockMvc.perform(get("/api/catalog/tools/{toolId}/info", "not-a-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Invalid tool ID format"));
        }

        @Test
        @DisplayName("should return 404 when tool not found")
        void shouldReturn404WhenToolNotFound() throws Exception {
            when(apiToolRepository.findById(TOOL_ID)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/catalog/tools/{toolId}/info", TOOL_ID))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 404 when API not found for tool")
        void shouldReturn404WhenApiNotFound() throws Exception {
            ApiToolEntity tool = createSampleTool();
            when(apiToolRepository.findById(TOOL_ID)).thenReturn(Optional.of(tool));
            when(apiRepository.findById(API_ID)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/catalog/tools/{toolId}/info", TOOL_ID))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should build full endpoint URL correctly")
        void shouldBuildFullEndpointUrl() throws Exception {
            ApiToolEntity tool = createSampleTool();
            ApiEntity api = createSampleApi();

            when(apiToolRepository.findById(TOOL_ID)).thenReturn(Optional.of(tool));
            when(apiRepository.findById(API_ID)).thenReturn(Optional.of(api));
            when(apiToolParameterRepository.findByApiToolId(TOOL_ID)).thenReturn(Collections.emptyList());
            when(toolNameRepository.findById(TOOL_NAME_ID)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/catalog/tools/{toolId}/info", TOOL_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.fullEndpoint").value("https://api.example.com/users"))
                    .andExpect(jsonPath("$.baseUrl").value("https://api.example.com"));
        }

        @Test
        @DisplayName("should handle tool with null toolNameId")
        void shouldHandleNullToolNameId() throws Exception {
            ApiToolEntity tool = createSampleTool();
            tool.setToolNameId(null);
            ApiEntity api = createSampleApi();

            when(apiToolRepository.findById(TOOL_ID)).thenReturn(Optional.of(tool));
            when(apiRepository.findById(API_ID)).thenReturn(Optional.of(api));
            when(apiToolParameterRepository.findByApiToolId(TOOL_ID)).thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/catalog/tools/{toolId}/info", TOOL_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("get-users"));

            verify(toolNameRepository, never()).findById(any());
        }
    }

    @Nested
    @DisplayName("GET /api/catalog/tools")
    class GetAllTools {

        @Test
        @DisplayName("should return all tools")
        void shouldReturnAllTools() throws Exception {
            ApiToolEntity tool = createSampleTool();
            when(apiToolRepository.findAll()).thenReturn(List.of(tool));

            mockMvc.perform(get("/api/catalog/tools"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].id").value(TOOL_ID.toString()))
                    .andExpect(jsonPath("$[0].toolSlug").value("get-users"))
                    .andExpect(jsonPath("$[0].method").value("GET"));
        }

        @Test
        @DisplayName("should return empty list when no tools exist")
        void shouldReturnEmptyList() throws Exception {
            when(apiToolRepository.findAll()).thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/catalog/tools"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        @DisplayName("should filter active tools when activeOnly is true")
        void shouldFilterActiveTools() throws Exception {
            ApiToolEntity activeTool = createSampleTool();
            activeTool.setIsActive(true);

            // activeOnly=true is a user-facing list → bundle-deprecated rows excluded (V331).
            when(apiToolRepository.findByIsActiveTrueAndDeprecatedAtIsNull()).thenReturn(List.of(activeTool));

            mockMvc.perform(get("/api/catalog/tools")
                            .param("activeOnly", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));
        }

        @Test
        @DisplayName("should return all tools when activeOnly is false")
        void shouldReturnAllToolsWhenActiveOnlyFalse() throws Exception {
            ApiToolEntity tool1 = createSampleTool();
            ApiToolEntity tool2 = createSampleTool();
            tool2.setId(UUID.randomUUID());
            tool2.setIsActive(false);

            when(apiToolRepository.findAll()).thenReturn(List.of(tool1, tool2));

            mockMvc.perform(get("/api/catalog/tools")
                            .param("activeOnly", "false"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)));
        }

        @Test
        @DisplayName("should return 500 on repository exception")
        void shouldReturn500OnException() throws Exception {
            when(apiToolRepository.findAll()).thenThrow(new RuntimeException("DB error"));

            mockMvc.perform(get("/api/catalog/tools"))
                    .andExpect(status().isInternalServerError());
        }
    }

    @Nested
    @DisplayName("GET /api/catalog/health")
    class HealthCheck {

        @Test
        @DisplayName("should return UP status with service name and timestamp")
        void shouldReturnUpStatus() throws Exception {
            mockMvc.perform(get("/api/catalog/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.service").value("catalog-service"))
                    .andExpect(jsonPath("$.timestamp").isNumber());
        }
    }
}
