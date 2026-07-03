package com.apimarketplace.orchestrator.controllers.mcp;

import com.apimarketplace.orchestrator.services.mcp.McpProtocolService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MCP Streamable HTTP transport at /mcp: JSON-RPC dispatch, protocol-version
 * negotiation, notification handling, auth guard, and batch support.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("McpStreamableHttpController")
class McpStreamableHttpControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private McpProtocolService protocolService;

    private McpStreamableHttpController controller;

    @BeforeEach
    void setUp() {
        controller = new McpStreamableHttpController(protocolService, MAPPER);
    }

    private static MockHttpServletRequest authenticatedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("X-User-ID", "42");
        request.addHeader("X-Organization-ID", "org-1");
        request.addHeader("X-Organization-Role", "OWNER");
        return request;
    }

    private static JsonNode json(String content) throws Exception {
        return MAPPER.readTree(content);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ResponseEntity<Object> response) {
        return (Map<String, Object>) response.getBody();
    }

    // ==================== initialize ====================

    @Test
    @DisplayName("initialize echoes a supported requested protocol version")
    void initializeEchoesSupportedProtocolVersion() throws Exception {
        ResponseEntity<Object> response = controller.handlePost(authenticatedRequest(), json(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"," +
                "\"params\":{\"protocolVersion\":\"2025-03-26\",\"clientInfo\":{\"name\":\"claude-code\"}}}"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> result = (Map<String, Object>) body(response).get("result");
        assertThat(result.get("protocolVersion")).isEqualTo("2025-03-26");
        Map<String, Object> serverInfo = (Map<String, Object>) result.get("serverInfo");
        assertThat(serverInfo.get("name")).isEqualTo("LiveContext Agent Tools");
        Map<String, Object> capabilities = (Map<String, Object>) result.get("capabilities");
        assertThat(capabilities).containsKeys("tools", "resources");
    }

    @Test
    @DisplayName("initialize falls back to the latest supported version for an unknown one")
    void initializeFallsBackToLatestVersion() throws Exception {
        ResponseEntity<Object> response = controller.handlePost(authenticatedRequest(), json(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"," +
                "\"params\":{\"protocolVersion\":\"1999-01-01\"}}"));

        Map<String, Object> result = (Map<String, Object>) body(response).get("result");
        assertThat(result.get("protocolVersion")).isEqualTo("2025-06-18");
    }

    // ==================== notifications ====================

    @Test
    @DisplayName("notifications/initialized is acknowledged with 202 and no body")
    void initializedNotificationReturns202() throws Exception {
        ResponseEntity<Object> response = controller.handlePost(authenticatedRequest(), json(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).isNull();
    }

    // ==================== ping / tools ====================

    @Test
    @DisplayName("ping returns an empty result")
    void pingReturnsEmptyResult() throws Exception {
        ResponseEntity<Object> response = controller.handlePost(authenticatedRequest(), json(
                "{\"jsonrpc\":\"2.0\",\"id\":\"p1\",\"method\":\"ping\"}"));

        assertThat(body(response).get("result")).isEqualTo(Map.of());
        assertThat(body(response)).doesNotContainKey("error");
    }

    @Test
    @DisplayName("tools/list returns the registry tools")
    void toolsListReturnsRegistryTools() throws Exception {
        when(protocolService.listTools()).thenReturn(List.of(Map.of("name", "workflow")));

        ResponseEntity<Object> response = controller.handlePost(authenticatedRequest(), json(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"));

        Map<String, Object> result = (Map<String, Object>) body(response).get("result");
        assertThat((List<Map<String, Object>>) result.get("tools"))
                .extracting(t -> t.get("name")).containsExactly("workflow");
    }

    @Test
    @DisplayName("tools/call executes the tool with the header-resolved tenant and org context")
    void toolsCallPassesTenantAndOrgContext() throws Exception {
        when(protocolService.hasTool("workflow")).thenReturn(true);
        when(protocolService.callTool(eq("workflow"), anyMap(), eq("42"), eq("org-1"), eq("OWNER")))
                .thenReturn(Map.of("content", List.of(), "isError", false));

        ResponseEntity<Object> response = controller.handlePost(authenticatedRequest(), json(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\"," +
                "\"params\":{\"name\":\"workflow\",\"arguments\":{\"action\":\"list\"}}}"));

        Map<String, Object> result = (Map<String, Object>) body(response).get("result");
        assertThat(result.get("isError")).isEqualTo(false);
        verify(protocolService).callTool(eq("workflow"), eq(Map.of("action", "list")),
                eq("42"), eq("org-1"), eq("OWNER"));
    }

    @Test
    @DisplayName("tools/call on an unknown tool returns invalid-params error")
    void toolsCallUnknownToolReturnsInvalidParams() throws Exception {
        when(protocolService.hasTool("nope")).thenReturn(false);

        ResponseEntity<Object> response = controller.handlePost(authenticatedRequest(), json(
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"nope\"}}"));

        Map<String, Object> error = (Map<String, Object>) body(response).get("error");
        assertThat(error.get("code")).isEqualTo(-32602);
        assertThat((String) error.get("message")).contains("nope");
    }

    @Test
    @DisplayName("tools/call without a tool name returns invalid-params error")
    void toolsCallWithoutNameReturnsInvalidParams() throws Exception {
        ResponseEntity<Object> response = controller.handlePost(authenticatedRequest(), json(
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{}}"));

        Map<String, Object> error = (Map<String, Object>) body(response).get("error");
        assertThat(error.get("code")).isEqualTo(-32602);
    }

    @Test
    @DisplayName("a service exception during tools/call surfaces as internal JSON-RPC error")
    void toolsCallServiceExceptionReturnsInternalError() throws Exception {
        when(protocolService.hasTool("workflow")).thenReturn(true);
        when(protocolService.callTool(anyString(), anyMap(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("boom"));

        ResponseEntity<Object> response = controller.handlePost(authenticatedRequest(), json(
                "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"workflow\"}}"));

        Map<String, Object> error = (Map<String, Object>) body(response).get("error");
        assertThat(error.get("code")).isEqualTo(-32603);
        assertThat((String) error.get("message")).contains("boom");
    }

    // ==================== resources ====================

    @Test
    @DisplayName("resources/read of an unknown uri returns the MCP resource-not-found code")
    void resourcesReadUnknownUriReturnsResourceNotFound() throws Exception {
        when(protocolService.getResourceContent("schema://nope")).thenReturn(null);

        ResponseEntity<Object> response = controller.handlePost(authenticatedRequest(), json(
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"resources/read\",\"params\":{\"uri\":\"schema://nope\"}}"));

        Map<String, Object> error = (Map<String, Object>) body(response).get("error");
        assertThat(error.get("code")).isEqualTo(-32002);
    }

    @Test
    @DisplayName("resources/read of a known uri returns its contents")
    void resourcesReadKnownUriReturnsContents() throws Exception {
        when(protocolService.getResourceContent("docs://tools")).thenReturn("# Tools");
        when(protocolService.resourceMimeType("docs://tools")).thenReturn("text/markdown");

        ResponseEntity<Object> response = controller.handlePost(authenticatedRequest(), json(
                "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"resources/read\",\"params\":{\"uri\":\"docs://tools\"}}"));

        Map<String, Object> result = (Map<String, Object>) body(response).get("result");
        List<Map<String, Object>> contents = (List<Map<String, Object>>) result.get("contents");
        assertThat(contents.get(0).get("text")).isEqualTo("# Tools");
        assertThat(contents.get(0).get("mimeType")).isEqualTo("text/markdown");
    }

    // ==================== protocol errors ====================

    @Test
    @DisplayName("an unknown method returns method-not-found")
    void unknownMethodReturnsMethodNotFound() throws Exception {
        ResponseEntity<Object> response = controller.handlePost(authenticatedRequest(), json(
                "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"prompts/list\"}"));

        Map<String, Object> error = (Map<String, Object>) body(response).get("error");
        assertThat(error.get("code")).isEqualTo(-32601);
    }

    @Test
    @DisplayName("a message with an id but no method returns invalid-request")
    void messageWithoutMethodReturnsInvalidRequest() throws Exception {
        ResponseEntity<Object> response = controller.handlePost(authenticatedRequest(), json(
                "{\"jsonrpc\":\"2.0\",\"id\":10}"));

        Map<String, Object> error = (Map<String, Object>) body(response).get("error");
        assertThat(error.get("code")).isEqualTo(-32600);
    }

    // ==================== auth guard ====================

    @Test
    @DisplayName("a request without upstream-injected X-User-ID gets HTTP 401")
    void missingUserIdReturns401() throws Exception {
        MockHttpServletRequest anonymous = new MockHttpServletRequest("POST", "/mcp");

        ResponseEntity<Object> response = controller.handlePost(anonymous, json(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}"));

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isNotBlank();
        verify(protocolService, never()).listTools();
    }

    // ==================== batch ====================

    @Test
    @DisplayName("a JSON-RPC batch returns one response per request in order")
    void batchReturnsOneResponsePerRequest() throws Exception {
        ResponseEntity<Object> response = controller.handlePost(authenticatedRequest(), json(
                "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}," +
                " {\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}," +
                " {\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"unknown/method\"}]"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        List<Map<String, Object>> responses = (List<Map<String, Object>>) response.getBody();
        assertThat(responses).hasSize(2);
        assertThat(responses.get(0)).containsKey("result");
        assertThat(((Map<String, Object>) responses.get(1).get("error")).get("code")).isEqualTo(-32601);
    }

    @Test
    @DisplayName("an empty batch is invalid per JSON-RPC 2.0 and gets a single -32600 error")
    void emptyBatchReturnsInvalidRequest() throws Exception {
        ResponseEntity<Object> response = controller.handlePost(authenticatedRequest(), json("[]"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> error = (Map<String, Object>) body(response).get("error");
        assertThat(error.get("code")).isEqualTo(-32600);
    }

    @Test
    @DisplayName("a batch of only notifications is acknowledged with 202")
    void allNotificationBatchReturns202() throws Exception {
        ResponseEntity<Object> response = controller.handlePost(authenticatedRequest(), json(
                "[{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}]"));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
    }

    // ==================== transport methods ====================

    @Test
    @DisplayName("GET /mcp returns 405 with Allow: POST (no server-initiated streams)")
    void getReturns405() {
        ResponseEntity<Void> response = controller.handleGet();

        assertThat(response.getStatusCode().value()).isEqualTo(405);
        assertThat(response.getHeaders().getFirst(HttpHeaders.ALLOW)).isEqualTo("POST");
    }

    @Test
    @DisplayName("DELETE /mcp returns 405 (stateless server, no session to terminate)")
    void deleteReturns405() {
        ResponseEntity<Void> response = controller.handleDelete();

        assertThat(response.getStatusCode().value()).isEqualTo(405);
    }
}
