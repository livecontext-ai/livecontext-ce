package com.apimarketplace.orchestrator.controllers.mcp;

import com.apimarketplace.orchestrator.services.mcp.McpProtocolService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Connection metadata for the MCP Streamable HTTP endpoint, consumed by the
 * "MCP Server" settings page to render the copy-paste client configuration.
 * Kept separate from {@code /api/mcp/**} (routed to catalog-service in cloud)
 * and from {@code /mcp} itself (reserved for the MCP transport).
 */
@Slf4j
@RestController
@RequestMapping("/api/mcp-server")
public class McpServerConnectionController {

    /**
     * Same externally-reachable origin webhooks advertise ({@code WEBHOOK_BASE_URL}):
     * the cloud public host in cloud, the published monolith port in CE.
     */
    @Value("${orchestrator.webhook.base-url:http://localhost:8080}")
    private String publicBaseUrl;

    private final McpProtocolService protocolService;

    public McpServerConnectionController(McpProtocolService protocolService) {
        this.protocolService = protocolService;
    }

    /**
     * GET /api/mcp-server/connection
     * Authenticated: requires the upstream-injected X-User-ID.
     */
    @GetMapping("/connection")
    public ResponseEntity<Map<String, Object>> getConnection(HttpServletRequest request) {
        String userId = request.getHeader("X-User-ID");
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Unauthorized"));
        }

        String base = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;

        return ResponseEntity.ok(Map.of(
                "url", base + "/mcp",
                "serverName", McpStreamableHttpController.SERVER_NAME,
                "authHeader", "X-API-Key",
                "toolCount", protocolService.listTools().size()
        ));
    }
}
