package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.common.web.ContentDispositions;
import com.apimarketplace.orchestrator.services.InterfaceRenderService.InterfaceRenderResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.net.URLConnection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Public controller for shared application access (no auth required, token-based).
 * Gateway routes /app/public/** → /api/internal/app/public/**
 */
@RestController
@RequestMapping("/api/internal/app/public")
public class PublicApplicationController {

    private static final Logger logger = LoggerFactory.getLogger(PublicApplicationController.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Generic error messages - never leak internal identifiers to public users. */
    private static final String ERROR_NOT_FOUND = "Resource not found";
    private static final String ERROR_CONFLICT = "Operation conflict";

    /** Input validation: nodeId must be alphanumeric with colons/underscores, max 128 chars. */
    private static final Pattern NODE_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_:.-]{1,128}$");
    /** Input validation: actionKey max length (CSS selectors, data-action attrs, etc.). */
    private static final int MAX_ACTION_KEY_LENGTH = 200;
    /** Max payload size for action data: 64KB when serialized. */
    private static final int MAX_DATA_KEYS = 100;

    private final PublicApplicationService appService;

    public PublicApplicationController(PublicApplicationService appService) {
        this.appService = appService;
    }

    /**
     * Get application config: name, interfaces, run status.
     */
    @GetMapping("/{token}/config")
    public ResponseEntity<?> getConfig(@PathVariable String token) {
        try {
            return ResponseEntity.ok(appService.getConfig(token));
        } catch (IllegalArgumentException e) {
            logger.debug("[PublicApp] Config not found for token: {}", e.getMessage());
            return ResponseEntity.status(404).body(Map.of("error", ERROR_NOT_FOUND));
        } catch (Exception e) {
            logger.error("[PublicApp] Error getting config for token {}: {}", truncateToken(token), e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "Failed to load application"));
        }
    }

    /**
     * Render an interface from the application's run.
     */
    @GetMapping("/{token}/render")
    public ResponseEntity<?> renderInterface(
            @PathVariable String token,
            @RequestParam UUID interfaceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "1") int size,
            @RequestParam(required = false) Integer epoch,
            @RequestParam(value = "variablePages", required = false) String variablePagesJson) {
        try {
            Map<String, Integer> variablePages = parseVariablePages(variablePagesJson);
            InterfaceRenderResult result = appService.renderInterface(token, interfaceId, page, size, epoch, variablePages);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("htmlTemplate", result.htmlTemplate());
            response.put("cssTemplate", result.cssTemplate());
            response.put("jsTemplate", result.jsTemplate());
            // The format travels with the templates: a consumer of this public render cannot
            // learn the interface's shape from anywhere else.
            response.put("format", result.format());
            response.put("items", result.items().stream().map(item -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("epoch", item.epoch());
                m.put("itemIndex", item.itemIndex());
                m.put("spawn", item.spawn());
                m.put("data", item.data());
                m.put("triggerData", item.triggerData());
                return m;
            }).toList());
            response.put("pagination", Map.of(
                    "page", result.pagination().page(),
                    "size", result.pagination().size(),
                    "totalItems", result.pagination().totalItems(),
                    "totalPages", result.pagination().totalPages()
            ));
            response.put("actionMappings", result.actionMappings());

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            logger.debug("[PublicApp] Render not found: {}", e.getMessage());
            return ResponseEntity.status(404).body(Map.of("error", ERROR_NOT_FOUND));
        } catch (IllegalStateException e) {
            logger.debug("[PublicApp] Render conflict: {}", e.getMessage());
            return ResponseEntity.status(409).body(Map.of("error", ERROR_CONFLICT));
        } catch (Exception e) {
            logger.error("[PublicApp] Error rendering interface for token {}: {}", truncateToken(token), e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "Failed to render interface"));
        }
    }

    /**
     * Fire an interface action on the application's run.
     */
    @PostMapping("/{token}/action")
    public ResponseEntity<?> fireAction(
            @PathVariable String token,
            @RequestBody Map<String, Object> body) {
        try {
            String nodeId = (String) body.get("nodeId");
            String actionKey = (String) body.get("actionKey");
            @SuppressWarnings("unchecked")
            Map<String, Object> data = body.get("data") instanceof Map
                    ? (Map<String, Object>) body.get("data") : Map.of();

            if (nodeId == null || actionKey == null || actionKey.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "nodeId and actionKey are required"));
            }

            // Input validation: reject malformed nodeId, oversized actionKey
            if (!NODE_ID_PATTERN.matcher(nodeId).matches()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid nodeId format"));
            }
            if (actionKey.length() > MAX_ACTION_KEY_LENGTH) {
                return ResponseEntity.badRequest().body(Map.of("error", "actionKey too long"));
            }

            // Payload size limit: reject excessively large data
            if (data.size() > MAX_DATA_KEYS) {
                return ResponseEntity.badRequest().body(Map.of("error", "Data payload too large"));
            }

            return ResponseEntity.ok(appService.fireAction(token, nodeId, actionKey, data));
        } catch (IllegalArgumentException e) {
            logger.debug("[PublicApp] Action not found: {}", e.getMessage());
            return ResponseEntity.status(404).body(Map.of("error", ERROR_NOT_FOUND));
        } catch (IllegalStateException e) {
            logger.debug("[PublicApp] Action conflict: {}", e.getMessage());
            return ResponseEntity.status(409).body(Map.of("error", ERROR_CONFLICT));
        } catch (Exception e) {
            logger.error("[PublicApp] Error firing action for token {}: {}", truncateToken(token), e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "Failed to process action"));
        }
    }

    /**
     * Get run-info for an interface (epoch timestamps for epoch slider).
     */
    @GetMapping("/{token}/run-info")
    public ResponseEntity<?> getRunInfo(
            @PathVariable String token,
            @RequestParam UUID interfaceId) {
        try {
            return ResponseEntity.ok(appService.getRunInfo(token, interfaceId));
        } catch (IllegalArgumentException e) {
            logger.debug("[PublicApp] Run-info not found: {}", e.getMessage());
            return ResponseEntity.status(404).body(Map.of("error", ERROR_NOT_FOUND));
        } catch (Exception e) {
            logger.error("[PublicApp] Error getting run-info for token {}: {}", truncateToken(token), e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "Failed to get run info"));
        }
    }

    /**
     * Proxy file download from storage (public access via share token).
     */
    @GetMapping("/{token}/file")
    public ResponseEntity<?> proxyFile(
            @PathVariable String token,
            @RequestParam String key) {
        try {
            byte[] content = appService.downloadFile(token, key);

            // Guess MIME type from key
            String mimeType = URLConnection.guessContentTypeFromName(key);
            if (mimeType == null) mimeType = "application/octet-stream";

            // Extract filename from key (last segment after /)
            String fileName = key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, mimeType)
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDispositions.inline(fileName))
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                    .body(content);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", ERROR_NOT_FOUND));
        } catch (Exception e) {
            logger.error("[PublicApp] Error proxying file for token {}: {}", truncateToken(token), e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "Failed to load file"));
        }
    }

    /**
     * Execute a trigger on the application's run (same as authenticated trigger endpoint).
     * Used by the shared app frontend when a button fires a workflow trigger.
     */
    @PostMapping("/{token}/trigger")
    public ResponseEntity<?> executeTrigger(
            @PathVariable String token,
            @RequestBody Map<String, Object> body) {
        try {
            String triggerId = (String) body.get("triggerId");
            String triggerType = (String) body.get("triggerType");
            @SuppressWarnings("unchecked")
            Map<String, Object> data = body.get("data") instanceof Map
                    ? (Map<String, Object>) body.get("data") : Map.of();

            if (triggerId == null || triggerId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "triggerId is required"));
            }
            if (!NODE_ID_PATTERN.matcher(triggerId).matches()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid triggerId format"));
            }

            return ResponseEntity.ok(appService.executeTrigger(token, triggerId, triggerType, data));
        } catch (IllegalArgumentException e) {
            logger.debug("[PublicApp] Trigger not found: {}", e.getMessage());
            return ResponseEntity.status(404).body(Map.of("error", ERROR_NOT_FOUND));
        } catch (IllegalStateException e) {
            logger.debug("[PublicApp] Trigger conflict: {}", e.getMessage());
            return ResponseEntity.status(409).body(Map.of("error", ERROR_CONFLICT));
        } catch (Exception e) {
            logger.error("[PublicApp] Error executing trigger for token {}: {}", truncateToken(token), e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "Failed to execute trigger"));
        }
    }

    /**
     * Get run status and active interface signals.
     */
    @GetMapping("/{token}/status")
    public ResponseEntity<?> getStatus(@PathVariable String token) {
        try {
            return ResponseEntity.ok(appService.getStatus(token));
        } catch (IllegalArgumentException e) {
            logger.debug("[PublicApp] Status not found: {}", e.getMessage());
            return ResponseEntity.status(404).body(Map.of("error", ERROR_NOT_FOUND));
        } catch (Exception e) {
            logger.error("[PublicApp] Error getting status for token {}: {}", truncateToken(token), e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "Failed to get status"));
        }
    }

    /** Truncate token in log messages to avoid leaking full token values. */
    private static String truncateToken(String token) {
        if (token == null || token.length() <= 8) return "***";
        return token.substring(0, 8) + "...";
    }

    private static Map<String, Integer> parseVariablePages(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, Integer>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
