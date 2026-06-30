package com.apimarketplace.interfaces.controller.tools;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ServiceToolsController (interface)")
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
        when(toolsProvider.execute(eq("interface"), any(), any()))
            .thenReturn(ToolExecutionResult.success(Map.of("id", "uuid-1", "name", "UI")));

        Map<String, Object> request = new HashMap<>();
        request.put("tool", "interface");
        request.put("parameters", Map.of("action", "list"));

        ResponseEntity<Map<String, Object>> response = controller.executeTool(
            createRequest("tenant-1"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("success", true);
        assertThat(response.getBody()).containsEntry("tool", "interface");
    }

    @Test
    @DisplayName("Should return error response for failed execution")
    void shouldReturnErrorForFailedExecution() {
        when(toolsProvider.execute(eq("interface"), any(), any()))
            .thenReturn(ToolExecutionResult.failure("Interface not found"));

        Map<String, Object> request = new HashMap<>();
        request.put("tool", "interface");
        request.put("parameters", Map.of("action", "get", "interface_id", "bad-uuid"));

        ResponseEntity<Map<String, Object>> response = controller.executeTool(
            createRequest("tenant-1"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("success", false);
        assertThat(response.getBody().get("error")).isEqualTo("Interface not found");
    }

    @Test
    @DisplayName("Should pass tenantId from X-User-ID header")
    void shouldPassTenantIdFromHeader() {
        when(toolsProvider.execute(any(), any(), any()))
            .thenReturn(ToolExecutionResult.success(Map.of()));

        Map<String, Object> request = new HashMap<>();
        request.put("tool", "interface");
        request.put("parameters", Map.of("action", "list"));

        controller.executeTool(createRequest("my-tenant"), request);

        ArgumentCaptor<ToolExecutionContext> ctxCaptor = ArgumentCaptor.forClass(ToolExecutionContext.class);
        verify(toolsProvider).execute(eq("interface"), any(), ctxCaptor.capture());
        assertThat(ctxCaptor.getValue().tenantId()).isEqualTo("my-tenant");
    }

    @Test
    @DisplayName("Should put allowedInterfaceIds into variables (not credentials)")
    void shouldPutAllowedIdsIntoVariables() {
        when(toolsProvider.execute(any(), any(), any()))
            .thenReturn(ToolExecutionResult.success(Map.of()));

        Map<String, Object> request = new HashMap<>();
        request.put("tool", "interface");
        request.put("parameters", Map.of("action", "list"));
        request.put("allowedInterfaceIds", List.of("uuid-1", "uuid-2"));

        controller.executeTool(createRequest("tenant-1"), request);

        ArgumentCaptor<ToolExecutionContext> ctxCaptor = ArgumentCaptor.forClass(ToolExecutionContext.class);
        verify(toolsProvider).execute(any(), any(), ctxCaptor.capture());
        assertThat(ctxCaptor.getValue().variables()).containsKey("allowedInterfaceIds");
        assertThat(ctxCaptor.getValue().variables().get("allowedInterfaceIds")).isEqualTo(List.of("uuid-1", "uuid-2"));
    }

    @Test
    @DisplayName("Should put turnId into variables for rate limiting")
    void shouldPutTurnIdIntoVariables() {
        when(toolsProvider.execute(any(), any(), any()))
            .thenReturn(ToolExecutionResult.success(Map.of()));

        Map<String, Object> request = new HashMap<>();
        request.put("tool", "interface");
        request.put("parameters", Map.of("action", "create", "name", "UI", "html_template", "<div>Hi</div>"));
        request.put("turnId", "turn-789");

        controller.executeTool(createRequest("tenant-1"), request);

        ArgumentCaptor<ToolExecutionContext> ctxCaptor = ArgumentCaptor.forClass(ToolExecutionContext.class);
        verify(toolsProvider).execute(any(), any(), ctxCaptor.capture());
        assertThat(ctxCaptor.getValue().variables().get("turnId")).isEqualTo("turn-789");
    }

    @Test
    @DisplayName("Should include visualization metadata in response")
    void shouldIncludeMetadataInResponse() {
        Map<String, Object> metadata = Map.of("visualization", Map.of("type", "interface", "id", "abc"));
        when(toolsProvider.execute(any(), any(), any()))
            .thenReturn(ToolExecutionResult.success(Map.of("id", "abc"), metadata));

        Map<String, Object> request = new HashMap<>();
        request.put("tool", "interface");
        request.put("parameters", Map.of("action", "create"));

        ResponseEntity<Map<String, Object>> response = controller.executeTool(
            createRequest("tenant-1"), request);

        assertThat(response.getBody()).containsKey("metadata");
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
