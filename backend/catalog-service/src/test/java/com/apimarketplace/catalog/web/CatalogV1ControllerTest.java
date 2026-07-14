package com.apimarketplace.catalog.web;

import com.apimarketplace.catalog.domain.dto.IntentResolutionResponse;
import com.apimarketplace.catalog.domain.dto.ToolExecutionRequest;
import com.apimarketplace.catalog.domain.dto.ToolExecutionResponse;
import com.apimarketplace.catalog.domain.dto.ToolListResponse;
import com.apimarketplace.catalog.service.CatalogV1Service;
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

import java.util.Collections;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogV1Controller")
class CatalogV1ControllerTest {

    @Mock
    private CatalogV1Service catalogV1Service;

    @Mock
    private com.apimarketplace.catalog.service.execution.MockToolExecutionService mockToolExecutionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        CatalogV1Controller controller = new CatalogV1Controller(catalogV1Service, mockToolExecutionService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Nested
    @DisplayName("GET /catalog/v1/tools")
    class GetToolsTests {

        @Test
        @DisplayName("should return list response with default limit")
        void getToolsWithDefaultLimit() throws Exception {
            ToolListResponse response = ToolListResponse.builder()
                    .tools(Collections.emptyList())
                    .total(0)
                    .limit(20)
                    .offset(0)
                    .build();
            when(catalogV1Service.getTools(20, null, null, null, null)).thenReturn(response);

            mockMvc.perform(get("/catalog/v1/tools"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0))
                    .andExpect(jsonPath("$.limit").value(20));
        }

        @Test
        @DisplayName("should return list response with custom limit")
        void getToolsWithCustomLimit() throws Exception {
            ToolListResponse response = ToolListResponse.builder()
                    .tools(Collections.emptyList())
                    .total(0)
                    .limit(50)
                    .build();
            when(catalogV1Service.getTools(50, null, null, null, null)).thenReturn(response);

            mockMvc.perform(get("/catalog/v1/tools")
                            .param("limit", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.limit").value(50));
        }

        @Test
        @DisplayName("should pass category and search parameters")
        void getToolsWithCategoryAndSearch() throws Exception {
            ToolListResponse response = ToolListResponse.builder()
                    .tools(Collections.emptyList())
                    .total(0)
                    .build();
            when(catalogV1Service.getTools(20, "analytics", "instagram", null, null)).thenReturn(response);

            mockMvc.perform(get("/catalog/v1/tools")
                            .param("category", "analytics")
                            .param("search", "instagram"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("should pass user and org headers")
        void getToolsWithHeaders() throws Exception {
            ToolListResponse response = ToolListResponse.builder()
                    .tools(Collections.emptyList())
                    .total(0)
                    .build();
            when(catalogV1Service.getTools(20, null, null, "user123", "org456")).thenReturn(response);

            mockMvc.perform(get("/catalog/v1/tools")
                            .header("X-User-ID", "user123")
                            .header("X-Organization-ID", "org456"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("should return 500 on service exception")
        void getToolsReturnsErrorOnException() throws Exception {
            when(catalogV1Service.getTools(anyInt(), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("Database error"));

            mockMvc.perform(get("/catalog/v1/tools"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error").value("Database error"));
        }
    }

    @Nested
    @DisplayName("POST /catalog/v1/tools/{toolId}/execute")
    class ExecuteToolTests {

        @Test
        @DisplayName("should execute tool successfully")
        void executeTool() throws Exception {
            UUID toolId = UUID.randomUUID();
            ToolExecutionResponse response = ToolExecutionResponse.builder()
                    .success(true)
                    .toolId(toolId.toString())
                    .build();
            when(catalogV1Service.executeTool(eq(toolId.toString()), any(ToolExecutionRequest.class), any(), any(), any()))
                    .thenReturn(response);

            mockMvc.perform(post("/catalog/v1/tools/{toolId}/execute", toolId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.toolId").value(toolId.toString()));
        }

        @Test
        @DisplayName("should execute tool with slug identifier")
        void executeToolWithSlug() throws Exception {
            String toolSlug = "my-api-get-users";
            ToolExecutionResponse response = ToolExecutionResponse.builder()
                    .success(true)
                    .toolId(toolSlug)
                    .build();
            when(catalogV1Service.executeTool(eq(toolSlug), any(), any(), any(), any()))
                    .thenReturn(response);

            mockMvc.perform(post("/catalog/v1/tools/{toolId}/execute", toolSlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("should execute tool without request body")
        void executeToolWithoutBody() throws Exception {
            String toolId = UUID.randomUUID().toString();
            ToolExecutionResponse response = ToolExecutionResponse.builder()
                    .success(true)
                    .build();
            when(catalogV1Service.executeTool(eq(toolId), any(), any(), any(), any()))
                    .thenReturn(response);

            mockMvc.perform(post("/catalog/v1/tools/{toolId}/execute", toolId)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("should pass headers to service")
        void executeToolWithHeaders() throws Exception {
            String toolId = UUID.randomUUID().toString();
            ToolExecutionResponse response = ToolExecutionResponse.builder()
                    .success(true)
                    .requestId("req-123")
                    .build();
            when(catalogV1Service.executeTool(eq(toolId), any(), eq("user123"), eq("org456"), eq("req-123")))
                    .thenReturn(response);

            mockMvc.perform(post("/catalog/v1/tools/{toolId}/execute", toolId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}")
                            .header("X-User-ID", "user123")
                            .header("X-Organization-ID", "org456")
                            .header("X-Request-Id", "req-123"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requestId").value("req-123"));
        }

        @Test
        @DisplayName("should return 500 on service exception")
        void executeToolReturnsErrorOnException() throws Exception {
            String toolId = UUID.randomUUID().toString();
            when(catalogV1Service.executeTool(anyString(), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("Execution failed"));

            mockMvc.perform(post("/catalog/v1/tools/{toolId}/execute", toolId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.toolId").value(toolId));
        }

        @Test
        @DisplayName("should propagate credentialSource and selectedCredentialId during request, then clear() in finally")
        void executeToolSetsAndClearsCredentialModeContext() throws Exception {
            String toolId = UUID.randomUUID().toString();
            ToolExecutionResponse response = ToolExecutionResponse.builder()
                    .success(true).toolId(toolId).requestId("req-1").build();

            // Capture explicitSource seen during the service call (proves
            // setExplicitSource was applied BEFORE the catalog service ran),
            // then assert the ThreadLocal is cleared after the response
            // returns (proves the finally block fired). Workflow direct calls
            // POST credentialSource on the request body; legacy
            // credentialModeOverride is now @JsonIgnore-sealed and cannot
            // resurrect the legacy code path.
            String[] seenInsideRequest = new String[]{"NOT_CAPTURED"};
            Long[] seenSelectedCredentialId = new Long[]{Long.MIN_VALUE};
            org.mockito.Mockito.doAnswer(invocation -> {
                seenInsideRequest[0] = com.apimarketplace.catalog.service.http.CredentialModeContext.getExplicitSource();
                seenSelectedCredentialId[0] = com.apimarketplace.catalog.service.http.CredentialModeContext.getSelectedCredentialId();
                return response;
            }).when(catalogV1Service).executeTool(anyString(), any(ToolExecutionRequest.class), any(), any(), any());

            mockMvc.perform(post("/catalog/v1/tools/{toolId}/execute", toolId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"credentialSource\":\"user\",\"selectedCredentialId\":123}"))
                    .andExpect(status().isOk());

            org.junit.jupiter.api.Assertions.assertEquals("user", seenInsideRequest[0],
                    "explicitSource must be visible to the service via CredentialModeContext.getExplicitSource() during the request");
            org.junit.jupiter.api.Assertions.assertEquals(123L, seenSelectedCredentialId[0],
                    "selectedCredentialId must be visible to the service during the request");
            org.junit.jupiter.api.Assertions.assertNull(
                    com.apimarketplace.catalog.service.http.CredentialModeContext.getExplicitSource(),
                    "CredentialModeContext must be cleared after the request (finally block); else 'platform' leaks to next thread-pool task");
            org.junit.jupiter.api.Assertions.assertNull(
                    com.apimarketplace.catalog.service.http.CredentialModeContext.getSelectedCredentialId(),
                    "selectedCredentialId must be cleared after the request");
        }

        @Test
        @DisplayName("should clear CredentialModeContext even when the service throws (regression: leaked source on next request)")
        void executeToolClearsCredentialModeContextOnException() throws Exception {
            String toolId = UUID.randomUUID().toString();
            when(catalogV1Service.executeTool(anyString(), any(ToolExecutionRequest.class), any(), any(), any()))
                    .thenThrow(new RuntimeException("boom"));

            // Pre-flight: nothing in the ThreadLocal.
            com.apimarketplace.catalog.service.http.CredentialModeContext.clear();

            mockMvc.perform(post("/catalog/v1/tools/{toolId}/execute", toolId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"credentialSource\":\"platform\"}"))
                    .andExpect(status().isInternalServerError());

            org.junit.jupiter.api.Assertions.assertNull(
                    com.apimarketplace.catalog.service.http.CredentialModeContext.getExplicitSource(),
                    "finally must clear() even on exception path; otherwise the next request on this thread sees a stale 'platform'");
        }
    }

    @Nested
    @DisplayName("POST /catalog/v1/tools/.../execute-mock")
    class ExecuteMockToolTests {

        @Test
        @DisplayName("should serve the mock execution response for a single-segment tool id")
        void executeMockHappyPath() throws Exception {
            String toolId = UUID.randomUUID().toString();
            ToolExecutionResponse response = ToolExecutionResponse.builder()
                    .success(true)
                    .toolId(toolId)
                    .build();
            when(mockToolExecutionService.executeMockTool(eq(toolId), anyString())).thenReturn(response);

            mockMvc.perform(post("/catalog/v1/tools/{toolId}/execute-mock", toolId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.toolId").value(toolId));
        }

        @Test
        @DisplayName("two-segment form resolves the tool id to 'apiSlug/toolSlug' (orchestrator step id format)")
        void executeMockTwoSegmentSlugForm() throws Exception {
            ToolExecutionResponse response = ToolExecutionResponse.builder()
                    .success(true)
                    .toolId("gmail/gmail-list-messages")
                    .build();
            when(mockToolExecutionService.executeMockTool(eq("gmail/gmail-list-messages"), anyString()))
                    .thenReturn(response);

            mockMvc.perform(post("/catalog/v1/tools/{apiSlug}/{toolSlug}/execute-mock",
                            "gmail", "gmail-list-messages"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            org.mockito.Mockito.verify(mockToolExecutionService)
                    .executeMockTool(eq("gmail/gmail-list-messages"), anyString());
        }

        @Test
        @DisplayName("should forward the X-Request-Id header to the service when provided")
        void executeMockForwardsRequestId() throws Exception {
            String toolId = UUID.randomUUID().toString();
            ToolExecutionResponse response = ToolExecutionResponse.builder()
                    .success(true)
                    .requestId("req-mock-9")
                    .build();
            when(mockToolExecutionService.executeMockTool(toolId, "req-mock-9")).thenReturn(response);

            mockMvc.perform(post("/catalog/v1/tools/{toolId}/execute-mock", toolId)
                            .header("X-Request-Id", "req-mock-9"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requestId").value("req-mock-9"));
        }

        @Test
        @DisplayName("ToolNotFoundException translates to 404 with {success:false, message:'Tool not found', toolId} (orchestrator's CatalogMockClient reads this shape)")
        void executeMockToolNotFoundIs404() throws Exception {
            when(mockToolExecutionService.executeMockTool(eq("gmail/nope"), anyString()))
                    .thenThrow(new com.apimarketplace.catalog.service.exception.ToolNotFoundException("gmail/nope"));

            mockMvc.perform(post("/catalog/v1/tools/{apiSlug}/{toolSlug}/execute-mock", "gmail", "nope"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value("Tool not found"))
                    .andExpect(jsonPath("$.toolId").value("gmail/nope"));
        }

        @Test
        @DisplayName("MockExampleNotFoundException translates to 404 with the exception message as 'message' + toolId")
        void executeMockExampleNotFoundIs404() throws Exception {
            String toolId = UUID.randomUUID().toString();
            when(mockToolExecutionService.executeMockTool(eq(toolId), anyString()))
                    .thenThrow(new com.apimarketplace.catalog.service.execution.MockToolExecutionService
                            .MockExampleNotFoundException("No default example response configured for tool " + toolId));

            mockMvc.perform(post("/catalog/v1/tools/{toolId}/execute-mock", toolId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message")
                            .value("No default example response configured for tool " + toolId))
                    .andExpect(jsonPath("$.toolId").value(toolId));
        }

        @Test
        @DisplayName("an unexpected service exception translates to 500 with the error detail")
        void executeMockUnexpectedErrorIs500() throws Exception {
            String toolId = UUID.randomUUID().toString();
            when(mockToolExecutionService.executeMockTool(eq(toolId), anyString()))
                    .thenThrow(new RuntimeException("projection blew up"));

            mockMvc.perform(post("/catalog/v1/tools/{toolId}/execute-mock", toolId))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error").value("projection blew up"))
                    .andExpect(jsonPath("$.toolId").value(toolId));
        }
    }

    @Nested
    @DisplayName("GET /catalog/v1/intents/resolve")
    class ResolveIntentTests {

        @Test
        @DisplayName("should require query parameter")
        void resolveIntentRequiresQuery() throws Exception {
            mockMvc.perform(get("/catalog/v1/intents/resolve"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return response for valid query")
        void resolveIntent() throws Exception {
            IntentResolutionResponse response = IntentResolutionResponse.builder()
                    .query("analytics")
                    .candidates(Collections.emptyList())
                    .totalCandidates(0)
                    .build();
            when(catalogV1Service.resolveIntent("analytics", 5, null, null)).thenReturn(response);

            mockMvc.perform(get("/catalog/v1/intents/resolve")
                            .param("q", "analytics"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.query").value("analytics"))
                    .andExpect(jsonPath("$.totalCandidates").value(0));
        }

        @Test
        @DisplayName("should use custom limit")
        void resolveIntentWithCustomLimit() throws Exception {
            IntentResolutionResponse response = IntentResolutionResponse.builder()
                    .query("search")
                    .candidates(Collections.emptyList())
                    .totalCandidates(0)
                    .build();
            when(catalogV1Service.resolveIntent("search", 10, null, null)).thenReturn(response);

            mockMvc.perform(get("/catalog/v1/intents/resolve")
                            .param("q", "search")
                            .param("limit", "10"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("should pass headers to service")
        void resolveIntentWithHeaders() throws Exception {
            IntentResolutionResponse response = IntentResolutionResponse.builder()
                    .query("test")
                    .candidates(Collections.emptyList())
                    .totalCandidates(0)
                    .build();
            when(catalogV1Service.resolveIntent("test", 5, "user123", "org456")).thenReturn(response);

            mockMvc.perform(get("/catalog/v1/intents/resolve")
                            .param("q", "test")
                            .header("X-User-ID", "user123")
                            .header("X-Organization-ID", "org456"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("should return 500 on service exception")
        void resolveIntentReturnsErrorOnException() throws Exception {
            when(catalogV1Service.resolveIntent(anyString(), anyInt(), any(), any()))
                    .thenThrow(new RuntimeException("Resolution error"));

            mockMvc.perform(get("/catalog/v1/intents/resolve")
                            .param("q", "test"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }
}
