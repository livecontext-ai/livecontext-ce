package com.apimarketplace.orchestrator.controllers.mcp;

import com.apimarketplace.orchestrator.services.mcp.McpProtocolService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Standard MCP <b>Streamable HTTP</b> transport: a single {@code /mcp} endpoint
 * speaking JSON-RPC 2.0 over POST, connectable from any MCP client (Claude Code,
 * Cursor, ...) with {@code "type": "http"} + an API key header.
 *
 * <p>Stateless by design: no {@code Mcp-Session-Id} is issued (the spec makes the
 * session optional), so any replica can serve any request and no gateway affinity
 * is needed. Server-initiated streams are not supported: GET returns 405, which
 * the spec allows for servers that never push.</p>
 *
 * <p>Authentication happens upstream (cloud gateway / CE {@code MonolithSecurityFilter})
 * from a JWT, an {@code X-API-Key} header, or {@code Authorization: Bearer lc_live_...};
 * this controller only requires the resulting {@code X-User-ID}. Requests without an
 * authenticated identity get HTTP 401 so clients surface a credentials problem instead
 * of operating on a null tenant.</p>
 */
@Slf4j
@RestController
@RequestMapping("/mcp")
@RequiredArgsConstructor
public class McpStreamableHttpController {

    /** Newest first; initialize echoes the client's version when supported. */
    static final List<String> SUPPORTED_PROTOCOL_VERSIONS =
            List.of("2025-06-18", "2025-03-26", "2024-11-05");
    static final String SERVER_NAME = "LiveContext Agent Tools";
    static final String SERVER_VERSION = "1.0.0";

    // JSON-RPC 2.0 error codes (+ MCP's resource-not-found extension)
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int INTERNAL_ERROR = -32603;
    private static final int RESOURCE_NOT_FOUND = -32002;

    /**
     * Injected by the auth layer (cloud gateway / CE {@code MonolithSecurityFilter})
     * ONLY when the request was authenticated with a SCOPED API key: a comma-separated,
     * lowercase list of top-level tool names the key may use. Absent header = full
     * access (JWT session or legacy/full-access key).
     */
    static final String API_KEY_SCOPES_HEADER = "X-Api-Key-Scopes";

    private final McpProtocolService protocolService;
    private final ObjectMapper objectMapper;

    @PostMapping
    public ResponseEntity<Object> handlePost(HttpServletRequest httpRequest, @RequestBody JsonNode body) {
        String tenantId = headerOrNull(httpRequest, "X-User-ID");
        if (tenantId == null) {
            return unauthorized();
        }
        String orgId = headerOrNull(httpRequest, "X-Organization-ID");
        String orgRole = headerOrNull(httpRequest, "X-Organization-Role");
        Set<String> scopes = parseScopes(httpRequest.getHeader(API_KEY_SCOPES_HEADER));

        // JSON-RPC batch (allowed up to protocol 2025-03-26): process each message,
        // answer only the ones that carry an id.
        if (body.isArray()) {
            if (body.isEmpty()) {
                // JSON-RPC 2.0: an empty batch is invalid and gets ONE error response.
                return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                        .body(errorResponse(null, INVALID_REQUEST, "Invalid Request"));
            }
            List<Map<String, Object>> responses = new ArrayList<>();
            for (JsonNode message : body) {
                Map<String, Object> response = processMessage(message, tenantId, orgId, orgRole, scopes);
                if (response != null) {
                    responses.add(response);
                }
            }
            if (responses.isEmpty()) {
                return ResponseEntity.accepted().build();
            }
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(responses);
        }

        Map<String, Object> response = processMessage(body, tenantId, orgId, orgRole, scopes);
        if (response == null) {
            // Notification (or client response): acknowledged with no body, per the transport spec.
            return ResponseEntity.accepted().build();
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response);
    }

    /**
     * The spec lets a server without server-initiated streams reject the SSE GET.
     */
    @GetMapping
    public ResponseEntity<Void> handleGet() {
        return methodNotAllowed();
    }

    /**
     * Stateless server: there is no session to terminate.
     */
    @DeleteMapping
    public ResponseEntity<Void> handleDelete() {
        return methodNotAllowed();
    }

    // ==================== JSON-RPC dispatch ====================

    /**
     * Parses the {@value #API_KEY_SCOPES_HEADER} header into the tool-scope set.
     *
     * <p>Semantics (security-relevant, do not soften): an ABSENT header means the
     * request was not authenticated with a scoped key, so {@code null} = full
     * access. A PRESENT header, however, always means "scoped key": tokens are
     * split on commas, trimmed, lowercased, and blanks dropped, and if nothing
     * parseable remains (empty or whitespace-only value) the result is the EMPTY
     * set = access to NO tools. Treating a present-but-empty header as full access
     * would silently grant a zero-scope key everything.</p>
     *
     * @return an immutable lowercase set of allowed tool names, possibly empty,
     *         or {@code null} for full access.
     */
    static Set<String> parseScopes(String header) {
        if (header == null) {
            return null; // no scoped key involved: full access
        }
        return Arrays.stream(header.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * @return the JSON-RPC response for a request, or {@code null} for
     *         notifications / client responses (nothing to send back).
     */
    private Map<String, Object> processMessage(JsonNode message, String tenantId, String orgId, String orgRole,
                                               Set<String> scopes) {
        if (message == null || !message.isObject()) {
            return errorResponse(null, INVALID_REQUEST, "Invalid Request");
        }

        JsonNode idNode = message.hasNonNull("id") ? message.get("id") : null;
        String method = message.hasNonNull("method") ? message.get("method").asText() : null;

        if (method == null) {
            // A message without a method is either a client->server response (ignore)
            // or malformed (report when it expects an answer).
            return idNode != null ? errorResponse(idNode, INVALID_REQUEST, "Invalid Request") : null;
        }

        if (idNode == null) {
            // Notification: nothing is expected back. notifications/initialized and
            // notifications/cancelled are no-ops for a stateless server.
            log.debug("MCP notification received: {}", method);
            return null;
        }

        JsonNode params = message.get("params");
        try {
            return switch (method) {
                case "initialize" -> successResponse(idNode, initializeResult(params));
                case "ping" -> successResponse(idNode, Map.of());
                case "tools/list" -> successResponse(idNode, Map.of("tools", protocolService.listTools(scopes)));
                case "tools/call" -> toolsCall(idNode, params, tenantId, orgId, orgRole, scopes);
                // Resources (schemas + tools docs) are deliberately NOT scope-filtered:
                // any valid key can read them; scopes only restrict tool visibility/execution.
                case "resources/list" -> successResponse(idNode, Map.of("resources", protocolService.listResources()));
                case "resources/templates/list" -> successResponse(idNode, Map.of("resourceTemplates", List.of()));
                case "resources/read" -> resourcesRead(idNode, params);
                default -> errorResponse(idNode, METHOD_NOT_FOUND, "Method not found: " + method);
            };
        } catch (Exception e) {
            log.error("Error handling MCP method {}: {}", method, e.getMessage(), e);
            return errorResponse(idNode, INTERNAL_ERROR, "Error: " + e.getMessage());
        }
    }

    private Map<String, Object> initializeResult(JsonNode params) {
        String requested = params != null && params.hasNonNull("protocolVersion")
                ? params.get("protocolVersion").asText()
                : null;
        String negotiated = SUPPORTED_PROTOCOL_VERSIONS.contains(requested)
                ? requested
                : SUPPORTED_PROTOCOL_VERSIONS.get(0);

        String clientName = params != null && params.has("clientInfo")
                ? params.path("clientInfo").path("name").asText("unknown")
                : "unknown";
        log.info("MCP initialize from client '{}' (requested protocol {}, negotiated {})",
                clientName, requested, negotiated);

        return Map.of(
                "protocolVersion", negotiated,
                "serverInfo", Map.of(
                        "name", SERVER_NAME,
                        "version", SERVER_VERSION
                ),
                "capabilities", Map.of(
                        "tools", Map.of(),
                        "resources", Map.of()
                )
        );
    }

    private Map<String, Object> toolsCall(JsonNode idNode, JsonNode params,
                                          String tenantId, String orgId, String orgRole,
                                          Set<String> scopes) throws Exception {
        String toolName = params != null && params.hasNonNull("name") ? params.get("name").asText() : null;
        if (toolName == null || toolName.isBlank()) {
            return errorResponse(idNode, INVALID_PARAMS, "tool name is required");
        }
        // An out-of-scope tool is indistinguishable from a nonexistent one (same
        // "Unknown tool" error): scoped keys must not be able to enumerate tools.
        if (!protocolService.hasTool(toolName, scopes)) {
            return errorResponse(idNode, INVALID_PARAMS, "Unknown tool: " + toolName);
        }

        Map<String, Object> arguments = params.hasNonNull("arguments")
                ? objectMapper.convertValue(params.get("arguments"),
                        objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class))
                : Map.of();

        return successResponse(idNode, protocolService.callTool(toolName, arguments, tenantId, orgId, orgRole, scopes));
    }

    private Map<String, Object> resourcesRead(JsonNode idNode, JsonNode params) {
        String uri = params != null && params.hasNonNull("uri") ? params.get("uri").asText() : null;
        if (uri == null || uri.isBlank()) {
            return errorResponse(idNode, INVALID_PARAMS, "uri is required");
        }
        String content = protocolService.getResourceContent(uri);
        if (content == null) {
            return errorResponse(idNode, RESOURCE_NOT_FOUND, "Resource not found: " + uri);
        }
        return successResponse(idNode, Map.of(
                "contents", List.of(Map.of(
                        "uri", uri,
                        "mimeType", protocolService.resourceMimeType(uri),
                        "text", content
                ))
        ));
    }

    // ==================== Response plumbing ====================

    private Map<String, Object> successResponse(JsonNode idNode, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", idNode);
        response.put("result", result);
        return response;
    }

    private Map<String, Object> errorResponse(JsonNode idNode, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        // JSON-RPC mandates an id member on errors; null when the request id is unknown.
        response.put("id", idNode);
        response.put("error", Map.of("code", code, "message", message));
        return response;
    }

    private ResponseEntity<Object> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"mcp\"")
                .body(Map.of(
                        "error", "Unauthorized",
                        "message", "Authentication required. Provide an X-API-Key header "
                                + "(or Authorization: Bearer <api key>) with a LiveContext API key."
                ));
    }

    private ResponseEntity<Void> methodNotAllowed() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .header(HttpHeaders.ALLOW, "POST")
                .build();
    }

    private static String headerOrNull(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value != null && !value.isBlank() ? value : null;
    }
}
