package com.apimarketplace.orchestrator.controllers;

import com.apimarketplace.common.web.AdminRoleGuard;
import com.apimarketplace.orchestrator.domain.NodeTypeDocumentationEntity;
import com.apimarketplace.orchestrator.services.NodeTypeSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST Controller for global node type settings (admin-only).
 * Allows enabling/disabling workflow node types visible to the AI agent.
 */
@RestController
@RequestMapping("/api/node-type-settings")
public class NodeTypeSettingsController {

    private static final Logger log = LoggerFactory.getLogger(NodeTypeSettingsController.class);

    private final NodeTypeSearchService nodeTypeSearchService;

    public NodeTypeSettingsController(NodeTypeSearchService nodeTypeSearchService) {
        this.nodeTypeSearchService = nodeTypeSearchService;
    }

    /**
     * GET /api/node-type-settings - List all node types with their enabled status.
     */
    @GetMapping
    public ResponseEntity<?> getAll(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;

        List<NodeTypeDocumentationEntity> all = nodeTypeSearchService.getAllTypesIncludingDisabled();
        List<Map<String, Object>> result = all.stream()
                .map(this::toSettingsMap)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /**
     * PUT /api/node-type-settings/{nodeType}/toggle - Toggle enabled status.
     */
    @PutMapping("/{nodeType}/toggle")
    public ResponseEntity<?> toggle(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @PathVariable String nodeType,
            @RequestBody Map<String, Object> body
    ) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;

        log.info("[NodeTypeSettings] Toggle request received: nodeType='{}', body={}", nodeType, body);

        Boolean enabled = (Boolean) body.get("enabled");
        if (enabled == null) {
            log.warn("[NodeTypeSettings] Missing 'enabled' field in body: {}", body);
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'enabled' field"));
        }

        try {
            NodeTypeDocumentationEntity entity = nodeTypeSearchService.toggleEnabled(nodeType, enabled);
            log.info("[NodeTypeSettings] Toggled '{}' to enabled={}", nodeType, enabled);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "nodeType", nodeType,
                    "enabled", entity.isEnabled()
            ));
        } catch (IllegalArgumentException e) {
            log.warn("[NodeTypeSettings] Node type not found: '{}'", nodeType, e);
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("[NodeTypeSettings] Unexpected error toggling '{}': {}", nodeType, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private Map<String, Object> toSettingsMap(NodeTypeDocumentationEntity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", entity.getType());
        map.put("label", entity.getLabel());
        map.put("category", entity.getCategory());
        map.put("description", entity.getDescription());
        map.put("variablePrefix", entity.getVariablePrefix());
        map.put("parameters", entity.getParameters());
        map.put("enabled", entity.isEnabled());
        return map;
    }
}
