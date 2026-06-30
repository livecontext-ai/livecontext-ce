package com.apimarketplace.datasource.controllers.tools;

import com.apimarketplace.agent.tools.ToolsProvider;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ServiceToolsController (datasource)")
class ServiceToolsControllerTest {

    @Mock private ToolsProvider toolsProvider;
    private ServiceToolsController controller;

    @BeforeEach
    void setUp() {
        controller = new ServiceToolsController(toolsProvider);
    }

    private MockHttpServletRequest createRequest(String tenantId) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (tenantId != null) req.addHeader("X-User-ID", tenantId);
        return req;
    }

    // ==================== execute ====================

    @Test
    @DisplayName("Should reject missing tool name")
    void shouldRejectMissingToolName() {
        ResponseEntity<Map<String, Object>> response = controller.executeTool(
            createRequest("tenant-1"), Map.of());
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("success", false);
    }

    @Test
    @DisplayName("Should execute tool and return success response")
    void shouldExecuteToolSuccessfully() {
        when(toolsProvider.execute(eq("table"), any(), any()))
            .thenReturn(ToolExecutionResult.success(Map.of("id", 1L, "name", "Test")));

        Map<String, Object> request = new HashMap<>();
        request.put("tool", "table");
        request.put("parameters", Map.of("action", "list"));

        ResponseEntity<Map<String, Object>> response = controller.executeTool(
            createRequest("tenant-1"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("success", true);
        assertThat(response.getBody()).containsEntry("tool", "table");
        assertThat(response.getBody()).containsKey("data");
    }

    @Test
    @DisplayName("Should return error response for failed execution")
    void shouldReturnErrorForFailedExecution() {
        when(toolsProvider.execute(eq("table"), any(), any()))
            .thenReturn(ToolExecutionResult.failure("Not found"));

        Map<String, Object> request = new HashMap<>();
        request.put("tool", "table");
        request.put("parameters", Map.of("action", "get", "table_id", 999));

        ResponseEntity<Map<String, Object>> response = controller.executeTool(
            createRequest("tenant-1"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("success", false);
        assertThat(response.getBody().get("error")).isEqualTo("Not found");
    }

    @Test
    @DisplayName("Should pass tenantId from X-User-ID header")
    void shouldPassTenantIdFromHeader() {
        when(toolsProvider.execute(any(), any(), any()))
            .thenReturn(ToolExecutionResult.success(Map.of()));

        Map<String, Object> request = new HashMap<>();
        request.put("tool", "table");
        request.put("parameters", Map.of("action", "list"));

        controller.executeTool(createRequest("my-tenant"), request);

        ArgumentCaptor<ToolExecutionContext> ctxCaptor = ArgumentCaptor.forClass(ToolExecutionContext.class);
        verify(toolsProvider).execute(eq("table"), any(), ctxCaptor.capture());
        assertThat(ctxCaptor.getValue().tenantId()).isEqualTo("my-tenant");
    }

    @Test
    @DisplayName("Should put allowedTableIds into CREDENTIALS (canonical channel: ToolAccessControl.getAllowedIds + grantCreatedResource both use credentials, so the allow-list and the create-grant round-trip)")
    void shouldPutAllowedIdsIntoCredentials() {
        when(toolsProvider.execute(any(), any(), any()))
            .thenReturn(ToolExecutionResult.success(Map.of()));

        Map<String, Object> request = new HashMap<>();
        request.put("tool", "table");
        request.put("parameters", Map.of("action", "list"));
        request.put("allowedTableIds", List.of("1", "2", "3"));

        controller.executeTool(createRequest("tenant-1"), request);

        ArgumentCaptor<ToolExecutionContext> ctxCaptor = ArgumentCaptor.forClass(ToolExecutionContext.class);
        verify(toolsProvider).execute(any(), any(), ctxCaptor.capture());
        // Canonical credentials channel (was variables - a split that made the create auto-grant a
        // no-op and left the row/schema/publish modules with no allow-list to read).
        assertThat(ctxCaptor.getValue().credentials()).containsEntry("allowedTableIds", List.of("1", "2", "3"));
        assertThat(ctxCaptor.getValue().variables()).doesNotContainKey("allowedTableIds");
    }

    @Test
    @DisplayName("Should put conversationId and turnId into variables")
    void shouldPutContextDataIntoVariables() {
        when(toolsProvider.execute(any(), any(), any()))
            .thenReturn(ToolExecutionResult.success(Map.of()));

        Map<String, Object> request = new HashMap<>();
        request.put("tool", "table");
        request.put("parameters", Map.of("action", "list"));
        request.put("conversationId", "conv-123");
        request.put("turnId", "turn-456");

        controller.executeTool(createRequest("tenant-1"), request);

        ArgumentCaptor<ToolExecutionContext> ctxCaptor = ArgumentCaptor.forClass(ToolExecutionContext.class);
        verify(toolsProvider).execute(any(), any(), ctxCaptor.capture());
        assertThat(ctxCaptor.getValue().variables().get("conversationId")).isEqualTo("conv-123");
        assertThat(ctxCaptor.getValue().variables().get("turnId")).isEqualTo("turn-456");
    }

    @Test
    @DisplayName("Should pass org context from request body")
    void shouldPassOrgContext() {
        when(toolsProvider.execute(any(), any(), any()))
            .thenReturn(ToolExecutionResult.success(Map.of()));

        Map<String, Object> request = new HashMap<>();
        request.put("tool", "table");
        request.put("parameters", Map.of("action", "list"));
        request.put("orgId", "org-1");
        request.put("orgRole", "admin");

        controller.executeTool(createRequest("tenant-1"), request);

        ArgumentCaptor<ToolExecutionContext> ctxCaptor = ArgumentCaptor.forClass(ToolExecutionContext.class);
        verify(toolsProvider).execute(any(), any(), ctxCaptor.capture());
        assertThat(ctxCaptor.getValue().orgId()).isEqualTo("org-1");
        assertThat(ctxCaptor.getValue().orgRole()).isEqualTo("admin");
    }

    @Test
    @DisplayName("Should pass org context from headers over body")
    void shouldPreferOrgContextFromHeaders() {
        when(toolsProvider.execute(any(), any(), any()))
            .thenReturn(ToolExecutionResult.success(Map.of()));

        MockHttpServletRequest httpRequest = createRequest("tenant-1");
        httpRequest.addHeader("X-Organization-ID", "header-org");
        httpRequest.addHeader("X-Organization-Role", "viewer");

        Map<String, Object> request = new HashMap<>();
        request.put("tool", "table");
        request.put("parameters", Map.of("action", "list"));
        request.put("orgId", "body-org");  // Should be overridden by header

        controller.executeTool(httpRequest, request);

        ArgumentCaptor<ToolExecutionContext> ctxCaptor = ArgumentCaptor.forClass(ToolExecutionContext.class);
        verify(toolsProvider).execute(any(), any(), ctxCaptor.capture());
        assertThat(ctxCaptor.getValue().orgId()).isEqualTo("header-org");
        assertThat(ctxCaptor.getValue().orgRole()).isEqualTo("viewer");
    }

    @Test
    @DisplayName("Should include metadata in response")
    void shouldIncludeMetadataInResponse() {
        Map<String, Object> metadata = Map.of("visualization", Map.of("type", "interface", "id", "abc"));
        when(toolsProvider.execute(any(), any(), any()))
            .thenReturn(ToolExecutionResult.success(Map.of("id", "abc"), metadata));

        Map<String, Object> request = new HashMap<>();
        request.put("tool", "table");
        request.put("parameters", Map.of("action", "create"));

        ResponseEntity<Map<String, Object>> response = controller.executeTool(
            createRequest("tenant-1"), request);

        assertThat(response.getBody()).containsKey("metadata");
        @SuppressWarnings("unchecked")
        Map<String, Object> respMetadata = (Map<String, Object>) response.getBody().get("metadata");
        assertThat(respMetadata).containsKey("visualization");
    }

    // ==================== listTools ====================

    @Test
    @DisplayName("Should list available tools")
    void shouldListTools() {
        when(toolsProvider.getTools()).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.listTools();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("count", 0);
    }
}
