package com.apimarketplace.catalog.web;

import com.apimarketplace.catalog.service.ApiVisibilityService;
import com.apimarketplace.common.web.AdminRoleGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/catalog/api-visibility")
@RequiredArgsConstructor
@Slf4j
public class ApiVisibilityController {

    private final ApiVisibilityService apiVisibilityService;

    @GetMapping("/integrations")
    public ResponseEntity<?> listIntegrations() {
        try {
            var integrations = apiVisibilityService.listIntegrations();
            return ResponseEntity.ok(Map.of("integrations", integrations));
        } catch (Exception e) {
            log.error("Failed to list integrations", e);
            return ResponseEntity.status(500).body(Map.of("error", "Failed to list integrations"));
        }
    }

    @PutMapping("/apis/{apiId}/toggle")
    public ResponseEntity<?> toggleApi(
            @PathVariable UUID apiId,
            @RequestParam boolean isActive,
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles) {
        // is_active is GLOBAL (cross-tenant) catalog state, so toggling it is admin-only.
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        try {
            apiVisibilityService.toggleApi(apiId, isActive);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to toggle API {}", apiId, e);
            return ResponseEntity.status(500).body(Map.of("error", "Failed to toggle API"));
        }
    }

    @GetMapping("/apis/{apiId}/tools")
    public ResponseEntity<?> listApiTools(@PathVariable UUID apiId) {
        try {
            var tools = apiVisibilityService.listApiTools(apiId);
            return ResponseEntity.ok(tools);
        } catch (Exception e) {
            log.error("Failed to list tools for API {}", apiId, e);
            return ResponseEntity.status(500).body(Map.of("error", "Failed to list tools"));
        }
    }

    @PutMapping("/tools/{toolId}/toggle")
    public ResponseEntity<?> toggleTool(
            @PathVariable UUID toolId,
            @RequestParam boolean isActive,
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles) {
        // is_active is GLOBAL (cross-tenant) catalog state, so toggling it is admin-only.
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        try {
            apiVisibilityService.toggleTool(toolId, isActive);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to toggle tool {}", toolId, e);
            return ResponseEntity.status(500).body(Map.of("error", "Failed to toggle tool"));
        }
    }
}
