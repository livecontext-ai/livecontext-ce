package com.apimarketplace.orchestrator.controllers.mcp;

import com.apimarketplace.orchestrator.services.mcp.McpProtocolService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Connection metadata endpoint feeding the Settings > MCP Server page.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("McpServerConnectionController")
class McpServerConnectionControllerTest {

    @Mock
    private McpProtocolService protocolService;

    private McpServerConnectionController controller;

    @BeforeEach
    void setUp() {
        controller = new McpServerConnectionController(protocolService);
        ReflectionTestUtils.setField(controller, "publicBaseUrl", "https://livecontext.example.com");
    }

    private static MockHttpServletRequest requestWithUser(String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/mcp-server/connection");
        if (userId != null) {
            request.addHeader("X-User-ID", userId);
        }
        return request;
    }

    @Test
    @DisplayName("returns the public /mcp url, auth header name and tool count")
    void returnsConnectionMetadata() {
        when(protocolService.listTools()).thenReturn(List.of(Map.of(), Map.of()));

        ResponseEntity<Map<String, Object>> response = controller.getConnection(requestWithUser("42"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("url")).isEqualTo("https://livecontext.example.com/mcp");
        assertThat(response.getBody().get("authHeader")).isEqualTo("X-API-Key");
        assertThat(response.getBody().get("toolCount")).isEqualTo(2);
    }

    @Test
    @DisplayName("a trailing slash on the configured base url does not double the separator")
    void trailingSlashBaseUrlIsNormalized() {
        ReflectionTestUtils.setField(controller, "publicBaseUrl", "http://localhost:8080/");
        when(protocolService.listTools()).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.getConnection(requestWithUser("42"));

        assertThat(response.getBody().get("url")).isEqualTo("http://localhost:8080/mcp");
    }

    @Test
    @DisplayName("a request without upstream identity gets 401")
    void missingUserIdReturns401() {
        ResponseEntity<Map<String, Object>> response = controller.getConnection(requestWithUser(null));

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }
}
