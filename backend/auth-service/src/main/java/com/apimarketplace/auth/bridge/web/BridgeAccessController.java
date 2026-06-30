package com.apimarketplace.auth.bridge.web;

import com.apimarketplace.auth.bridge.domain.BridgeAccessModels.BridgeAccessAllowlistEntry;
import com.apimarketplace.auth.bridge.domain.BridgeAccessModels.BridgeAccessPolicy;
import com.apimarketplace.auth.bridge.domain.BridgeAccessModels.BridgeAccessView;
import com.apimarketplace.auth.bridge.domain.BridgeAccessModels.UpdatePolicyRequest;
import com.apimarketplace.auth.bridge.service.BridgeAccessService;
import com.apimarketplace.common.web.AdminRoleGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Admin REST for CLI bridge access policies.
 *
 * <p>All endpoints require the {@code ADMIN} role. Non-admin requests are
 * rejected with 403 before any service call - there is no user-facing view
 * of the ACL surface (users discover access via the model picker, which
 * filters silently based on their policy membership).
 */
@RestController
@RequestMapping("/api/bridge-access")
public class BridgeAccessController {

    private static final Logger log = LoggerFactory.getLogger(BridgeAccessController.class);

    private final BridgeAccessService service;

    public BridgeAccessController(BridgeAccessService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<?> listPolicies(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        List<BridgeAccessPolicy> policies = service.listPolicies();
        return ResponseEntity.ok(Map.of("policies", policies));
    }

    @GetMapping("/{bridgeProvider}")
    public ResponseEntity<?> getPolicyView(
            @PathVariable String bridgeProvider,
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        try {
            BridgeAccessView view = service.getPolicyView(bridgeProvider);
            return ResponseEntity.ok(view);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{bridgeProvider}")
    public ResponseEntity<?> updatePolicy(
            @PathVariable String bridgeProvider,
            @RequestBody UpdatePolicyRequest request,
            @RequestHeader(value = "X-User-ID", defaultValue = "system") String userId,
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        try {
            BridgeAccessPolicy updated = service.updatePolicy(bridgeProvider, request, userId);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{bridgeProvider}/allowlist/{targetUserId}")
    public ResponseEntity<?> grant(
            @PathVariable String bridgeProvider,
            @PathVariable String targetUserId,
            @RequestHeader(value = "X-User-ID", defaultValue = "system") String grantedBy,
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        try {
            BridgeAccessAllowlistEntry entry = service.grantAccess(bridgeProvider, targetUserId, grantedBy);
            return ResponseEntity.ok(entry);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{bridgeProvider}/allowlist/{targetUserId}")
    public ResponseEntity<?> revoke(
            @PathVariable String bridgeProvider,
            @PathVariable String targetUserId,
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        try {
            boolean removed = service.revokeAccess(bridgeProvider, targetUserId);
            return ResponseEntity.ok(Map.of("removed", removed));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
