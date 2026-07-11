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

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
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

    /** Scope-picker descriptions are capped so the settings UI stays compact. */
    private static final int SCOPE_DESCRIPTION_MAX_LENGTH = 200;

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

        List<Map<String, Object>> tools = protocolService.listTools();

        return ResponseEntity.ok(Map.of(
                "url", base + "/mcp",
                "serverName", McpStreamableHttpController.SERVER_NAME,
                "authHeader", "X-API-Key",
                "toolCount", tools.size(),
                "availableScopes", availableScopes(tools)
        ));
    }

    /**
     * The scope vocabulary for the API-key settings UI: one entry per tool the
     * MCP server exposes (same merged list {@code tools/list} serves), so a
     * scoped key can be restricted to any subset of these names.
     */
    private static List<Map<String, Object>> availableScopes(List<Map<String, Object>> tools) {
        return tools.stream()
                .map(tool -> {
                    Map<String, Object> scope = new LinkedHashMap<>();
                    scope.put("name", String.valueOf(tool.get("name")));
                    scope.put("description", truncatedDescription(tool.get("description")));
                    return scope;
                })
                .sorted(Comparator.comparing(scope -> String.valueOf(scope.get("name"))))
                .toList();
    }

    private static String truncatedDescription(Object description) {
        if (description == null) {
            return "";
        }
        String text = description.toString();
        return text.length() > SCOPE_DESCRIPTION_MAX_LENGTH
                ? text.substring(0, SCOPE_DESCRIPTION_MAX_LENGTH)
                : text;
    }
}
