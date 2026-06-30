package com.apimarketplace.catalog.web;

import com.apimarketplace.catalog.config.GlobalExceptionHandler;
import com.apimarketplace.catalog.dto.*;
import com.apimarketplace.catalog.service.WorkflowInspectorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.*;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowInspectorController Integration Tests")
class WorkflowInspectorControllerIntegrationTest {

    @Mock
    private WorkflowInspectorService workflowInspectorService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        WorkflowInspectorController controller = new WorkflowInspectorController(workflowInspectorService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
    }

    private WorkflowApiDTO createSampleApiDTO(String slug, String name) {
        return new WorkflowApiDTO(slug, name, "Description of " + name, 5, "mcp", null);
    }

    private WorkflowToolDTO createSampleToolDTO(String slug, String name) {
        return new WorkflowToolDTO(slug, name, "Description of " + name, "GET", "my-api", "mcp", null, null, null, null);
    }

    private WorkflowToolDetailDTO createSampleToolDetailDTO(String slug, String name) {
        return new WorkflowToolDetailDTO(
                slug, name, "Description of " + name, "GET", "my-api", "mcp", null,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                null,
                null,
                null
        );
    }

    @Nested
    @DisplayName("GET /api/workflow-inspector/apis")
    class GetApisForWorkflow {

        @Test
        @DisplayName("should return paginated APIs with default parameters")
        void shouldReturnPaginatedApis() throws Exception {
            WorkflowApiDTO api1 = createSampleApiDTO("slack-api", "Slack API");
            WorkflowApiDTO api2 = createSampleApiDTO("github-api", "GitHub API");
            when(workflowInspectorService.getAllApisForWorkflow(null))
                    .thenReturn(List.of(api1, api2));

            mockMvc.perform(get("/api/workflow-inspector/apis"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(2)))
                    .andExpect(jsonPath("$.content[0].slug").value("slack-api"))
                    .andExpect(jsonPath("$.content[0].apiName").value("Slack API"))
                    .andExpect(jsonPath("$.content[0].toolsCount").value(5))
                    .andExpect(jsonPath("$.content[0].iconSlug").value("mcp"))
                    .andExpect(jsonPath("$.totalElements").value(2))
                    .andExpect(jsonPath("$.page").value(0))
                    .andExpect(jsonPath("$.size").value(100))
                    .andExpect(jsonPath("$.first").value(true))
                    .andExpect(jsonPath("$.last").value(true));
        }

        @Test
        @DisplayName("should support pagination parameters")
        void shouldSupportPagination() throws Exception {
            List<WorkflowApiDTO> apis = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                apis.add(createSampleApiDTO("api-" + i, "API " + i));
            }
            when(workflowInspectorService.getAllApisForWorkflow(null)).thenReturn(apis);

            mockMvc.perform(get("/api/workflow-inspector/apis")
                            .param("page", "1")
                            .param("size", "3"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(3)))
                    .andExpect(jsonPath("$.page").value(1))
                    .andExpect(jsonPath("$.size").value(3))
                    .andExpect(jsonPath("$.totalElements").value(10))
                    .andExpect(jsonPath("$.first").value(false));
        }

        @Test
        @DisplayName("should support name filter")
        void shouldSupportNameFilter() throws Exception {
            WorkflowApiDTO api = createSampleApiDTO("slack-api", "Slack API");
            when(workflowInspectorService.getAllApisForWorkflow("slack")).thenReturn(List.of(api));

            mockMvc.perform(get("/api/workflow-inspector/apis")
                            .param("name", "slack"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(1)))
                    .andExpect(jsonPath("$.content[0].apiName").value("Slack API"));

            verify(workflowInspectorService).getAllApisForWorkflow("slack");
        }

        @Test
        @DisplayName("should return empty content when no APIs match")
        void shouldReturnEmptyContent() throws Exception {
            when(workflowInspectorService.getAllApisForWorkflow(null)).thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/workflow-inspector/apis"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(0)))
                    .andExpect(jsonPath("$.totalElements").value(0))
                    .andExpect(jsonPath("$.totalPages").value(0));
        }

        @Test
        @DisplayName("should return empty content when page exceeds total")
        void shouldReturnEmptyOnExceededPage() throws Exception {
            when(workflowInspectorService.getAllApisForWorkflow(null))
                    .thenReturn(List.of(createSampleApiDTO("api", "API")));

            mockMvc.perform(get("/api/workflow-inspector/apis")
                            .param("page", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(0)));
        }

        @Test
        @DisplayName("should return 500 on service exception")
        void shouldReturn500OnException() throws Exception {
            when(workflowInspectorService.getAllApisForWorkflow(any()))
                    .thenThrow(new RuntimeException("DB error"));

            mockMvc.perform(get("/api/workflow-inspector/apis"))
                    .andExpect(status().isInternalServerError());
        }
    }

    @Nested
    @DisplayName("GET /api/workflow-inspector/apis/{apiSlug}")
    class GetApiBySlug {

        @Test
        @DisplayName("should return API by slug")
        void shouldReturnApiBySlug() throws Exception {
            WorkflowApiDTO api = createSampleApiDTO("slack-api", "Slack API");
            when(workflowInspectorService.getApiBySlug("slack-api")).thenReturn(Optional.of(api));

            mockMvc.perform(get("/api/workflow-inspector/apis/{apiSlug}", "slack-api"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.slug").value("slack-api"))
                    .andExpect(jsonPath("$.apiName").value("Slack API"));
        }

        @Test
        @DisplayName("should return 404 when API not found")
        void shouldReturn404WhenNotFound() throws Exception {
            when(workflowInspectorService.getApiBySlug("unknown-api")).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/workflow-inspector/apis/{apiSlug}", "unknown-api"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 500 on service exception")
        void shouldReturn500OnException() throws Exception {
            when(workflowInspectorService.getApiBySlug("error-api"))
                    .thenThrow(new RuntimeException("DB error"));

            mockMvc.perform(get("/api/workflow-inspector/apis/{apiSlug}", "error-api"))
                    .andExpect(status().isInternalServerError());
        }
    }

    @Nested
    @DisplayName("GET /api/workflow-inspector/apis/{apiSlug}/tools")
    class GetToolsForApi {

        @Test
        @DisplayName("should return tools for an API")
        void shouldReturnTools() throws Exception {
            WorkflowToolDTO tool1 = createSampleToolDTO("get-users", "Get Users");
            WorkflowToolDTO tool2 = createSampleToolDTO("create-user", "Create User");
            when(workflowInspectorService.getToolsForApi("slack-api")).thenReturn(List.of(tool1, tool2));

            mockMvc.perform(get("/api/workflow-inspector/apis/{apiSlug}/tools", "slack-api"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].slug").value("get-users"))
                    .andExpect(jsonPath("$[0].name").value("Get Users"))
                    .andExpect(jsonPath("$[0].method").value("GET"))
                    .andExpect(jsonPath("$[0].apiSlug").value("my-api"));
        }

        @Test
        @DisplayName("should return empty list when API has no tools")
        void shouldReturnEmptyList() throws Exception {
            when(workflowInspectorService.getToolsForApi("empty-api")).thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/workflow-inspector/apis/{apiSlug}/tools", "empty-api"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        @DisplayName("should return 500 on service exception")
        void shouldReturn500OnException() throws Exception {
            when(workflowInspectorService.getToolsForApi("error-api"))
                    .thenThrow(new RuntimeException("DB error"));

            mockMvc.perform(get("/api/workflow-inspector/apis/{apiSlug}/tools", "error-api"))
                    .andExpect(status().isInternalServerError());
        }
    }

    @Nested
    @DisplayName("GET /api/workflow-inspector/tools/{toolSlug}")
    class GetToolBySlug {

        @Test
        @DisplayName("should return tool by slug")
        void shouldReturnToolBySlug() throws Exception {
            WorkflowToolDTO tool = createSampleToolDTO("get-users", "Get Users");
            when(workflowInspectorService.getToolBySlug("get-users")).thenReturn(Optional.of(tool));

            mockMvc.perform(get("/api/workflow-inspector/tools/{toolSlug}", "get-users"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.slug").value("get-users"))
                    .andExpect(jsonPath("$.name").value("Get Users"));
        }

        @Test
        @DisplayName("should return 404 when tool not found")
        void shouldReturn404WhenNotFound() throws Exception {
            when(workflowInspectorService.getToolBySlug("unknown-tool")).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/workflow-inspector/tools/{toolSlug}", "unknown-tool"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 500 on service exception")
        void shouldReturn500OnException() throws Exception {
            when(workflowInspectorService.getToolBySlug("error-tool"))
                    .thenThrow(new RuntimeException("DB error"));

            mockMvc.perform(get("/api/workflow-inspector/tools/{toolSlug}", "error-tool"))
                    .andExpect(status().isInternalServerError());
        }
    }

    @Nested
    @DisplayName("GET /api/workflow-inspector/tools/{toolSlug}/details")
    class GetToolDetailBySlug {

        @Test
        @DisplayName("should return tool details by slug")
        void shouldReturnToolDetails() throws Exception {
            WorkflowToolDetailDTO detail = createSampleToolDetailDTO("get-users", "Get Users");
            when(workflowInspectorService.getToolDetailBySlug("get-users")).thenReturn(Optional.of(detail));

            mockMvc.perform(get("/api/workflow-inspector/tools/{toolSlug}/details", "get-users"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.slug").value("get-users"))
                    .andExpect(jsonPath("$.name").value("Get Users"))
                    .andExpect(jsonPath("$.parameters").isArray())
                    .andExpect(jsonPath("$.responses").isArray())
                    .andExpect(jsonPath("$.credentials").isArray());
        }

        @Test
        @DisplayName("should return 404 when tool details not found")
        void shouldReturn404WhenNotFound() throws Exception {
            when(workflowInspectorService.getToolDetailBySlug("unknown")).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/workflow-inspector/tools/{toolSlug}/details", "unknown"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 500 on service exception")
        void shouldReturn500OnException() throws Exception {
            when(workflowInspectorService.getToolDetailBySlug("error"))
                    .thenThrow(new RuntimeException("DB error"));

            mockMvc.perform(get("/api/workflow-inspector/tools/{toolSlug}/details", "error"))
                    .andExpect(status().isInternalServerError());
        }
    }

    @Nested
    @DisplayName("GET /api/workflow-inspector/apis/{apiSlug}/icon")
    class GetApiIcon {

        @Test
        @DisplayName("should return icon slug for API")
        void shouldReturnIconSlug() throws Exception {
            when(workflowInspectorService.getApiIconSlug("slack-api")).thenReturn(Optional.of("slack"));

            mockMvc.perform(get("/api/workflow-inspector/apis/{apiSlug}/icon", "slack-api"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.iconSlug").value("slack"));
        }

        @Test
        @DisplayName("should return 404 when API not found")
        void shouldReturn404WhenNotFound() throws Exception {
            when(workflowInspectorService.getApiIconSlug("unknown")).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/workflow-inspector/apis/{apiSlug}/icon", "unknown"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 500 on service exception")
        void shouldReturn500OnException() throws Exception {
            when(workflowInspectorService.getApiIconSlug("error"))
                    .thenThrow(new RuntimeException("DB error"));

            mockMvc.perform(get("/api/workflow-inspector/apis/{apiSlug}/icon", "error"))
                    .andExpect(status().isInternalServerError());
        }
    }

    @Nested
    @DisplayName("GET /api/workflow-inspector/tools/{toolSlug}/icon")
    class GetToolIcon {

        @Test
        @DisplayName("should return icon slug for tool")
        void shouldReturnToolIconSlug() throws Exception {
            when(workflowInspectorService.getToolIconSlug("get-users")).thenReturn(Optional.of("users-icon"));

            mockMvc.perform(get("/api/workflow-inspector/tools/{toolSlug}/icon", "get-users"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.iconSlug").value("users-icon"));
        }

        @Test
        @DisplayName("should return 404 when tool not found")
        void shouldReturn404WhenNotFound() throws Exception {
            when(workflowInspectorService.getToolIconSlug("unknown")).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/workflow-inspector/tools/{toolSlug}/icon", "unknown"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 500 on service exception")
        void shouldReturn500OnException() throws Exception {
            when(workflowInspectorService.getToolIconSlug("error"))
                    .thenThrow(new RuntimeException("DB error"));

            mockMvc.perform(get("/api/workflow-inspector/tools/{toolSlug}/icon", "error"))
                    .andExpect(status().isInternalServerError());
        }
    }

    @Nested
    @DisplayName("POST /api/workflow-inspector/tools/batch")
    class GetToolsBatch {

        @Test
        @DisplayName("should return tools for batch request")
        void shouldReturnToolsBatch() throws Exception {
            WorkflowToolDetailDTO tool1 = createSampleToolDetailDTO("get-users", "Get Users");
            WorkflowToolDetailDTO tool2 = createSampleToolDetailDTO("create-user", "Create User");

            Map<String, WorkflowToolDetailDTO> result = new HashMap<>();
            result.put("get-users", tool1);
            result.put("create-user", tool2);

            when(workflowInspectorService.getToolsBatch(List.of("get-users", "create-user")))
                    .thenReturn(result);

            String requestBody = objectMapper.writeValueAsString(
                    new ToolBatchRequest(List.of("get-users", "create-user")));

            mockMvc.perform(post("/api/workflow-inspector/tools/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.get-users.slug").value("get-users"))
                    .andExpect(jsonPath("$.get-users.name").value("Get Users"))
                    .andExpect(jsonPath("$.create-user.slug").value("create-user"));
        }

        @Test
        @DisplayName("should return empty map for null tool slugs")
        void shouldReturnEmptyMapForNullSlugs() throws Exception {
            String requestBody = "{\"toolSlugs\": null}";

            mockMvc.perform(post("/api/workflow-inspector/tools/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isEmpty());
        }

        @Test
        @DisplayName("should return empty map for empty tool slugs list")
        void shouldReturnEmptyMapForEmptySlugs() throws Exception {
            String requestBody = objectMapper.writeValueAsString(
                    new ToolBatchRequest(Collections.emptyList()));

            mockMvc.perform(post("/api/workflow-inspector/tools/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isEmpty());
        }

        @Test
        @DisplayName("should return partial results when some tools not found")
        void shouldReturnPartialResults() throws Exception {
            WorkflowToolDetailDTO tool = createSampleToolDetailDTO("get-users", "Get Users");
            Map<String, WorkflowToolDetailDTO> result = Map.of("get-users", tool);
            when(workflowInspectorService.getToolsBatch(List.of("get-users", "unknown")))
                    .thenReturn(result);

            String requestBody = objectMapper.writeValueAsString(
                    new ToolBatchRequest(List.of("get-users", "unknown")));

            mockMvc.perform(post("/api/workflow-inspector/tools/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.get-users").exists())
                    .andExpect(jsonPath("$.unknown").doesNotExist());
        }

        @Test
        @DisplayName("should return 500 on service exception")
        void shouldReturn500OnException() throws Exception {
            when(workflowInspectorService.getToolsBatch(any()))
                    .thenThrow(new RuntimeException("DB error"));

            String requestBody = objectMapper.writeValueAsString(
                    new ToolBatchRequest(List.of("get-users")));

            mockMvc.perform(post("/api/workflow-inspector/tools/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isInternalServerError());
        }
    }
}
