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
    @DisplayName("puts allowedInterfaceIds into BOTH channels - credentials is the one the module reads")
    void shouldPutAllowedIdsIntoCredentialsAndVariables() {
        when(toolsProvider.execute(any(), any(), any()))
            .thenReturn(ToolExecutionResult.success(Map.of()));

        Map<String, Object> request = new HashMap<>();
        request.put("tool", "interface");
        request.put("parameters", Map.of("action", "list"));
        request.put("allowedInterfaceIds", List.of("uuid-1", "uuid-2"));

        controller.executeTool(createRequest("tenant-1"), request);

        ArgumentCaptor<ToolExecutionContext> ctxCaptor = ArgumentCaptor.forClass(ToolExecutionContext.class);
        verify(toolsProvider).execute(any(), any(), ctxCaptor.capture());
        // CREDENTIALS is the load-bearing assertion: InterfaceCrudModule resolves its
        // allow-list through ToolAccessControl.getAllowedIds(context.credentials(), ...), so
        // this relay loop IS the whole enforcement path in microservice (cloud) mode. Without
        // this line the loop could be deleted with every test still green, and interface
        // scoping would evaporate in cloud exactly as it had in the monolith.
        assertThat(ctxCaptor.getValue().credentials())
                .containsEntry("allowedInterfaceIds", List.of("uuid-1", "uuid-2"));
        // variables keeps the same value: it is part of this relay's published shape and
        // nothing reads the allow-list from it any more, but removing a key is a separate change.
        assertThat(ctxCaptor.getValue().variables())
                .containsEntry("allowedInterfaceIds", List.of("uuid-1", "uuid-2"));
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

    @Test
    @DisplayName("a body-supplied orgRole never reaches the execution context")
    void bodyOrgRoleIsIgnored() {
        // The body was a second channel for the privilege axis, on an endpoint the gateway routes.
        // The gateway strips the caller's own identity HEADERS but not the body, so a user whose
        // gateway resolved no active org could name a workspace and assert OWNER in it. Every
        // internal caller sends the role as a header from the same source it filled the body with.
        when(toolsProvider.execute(any(), any(), any()))
            .thenReturn(ToolExecutionResult.success(Map.of()));

        Map<String, Object> request = new java.util.HashMap<>();
        request.put("tool", "interface");
        request.put("parameters", Map.of("action", "list"));
        request.put("orgId", "victim-org");
        request.put("orgRole", "OWNER");

        controller.executeTool(createRequest("tenant-1"), request);

        ArgumentCaptor<ToolExecutionContext> ctx = ArgumentCaptor.forClass(ToolExecutionContext.class);
        verify(toolsProvider).execute(any(), any(), ctx.capture());
        assertThat(ctx.getValue().orgRole())
            .as("a body-supplied role must never reach the execution context")
            .isNull();
        assertThat(ctx.getValue().orgId()).isEqualTo("victim-org");
    }
}
