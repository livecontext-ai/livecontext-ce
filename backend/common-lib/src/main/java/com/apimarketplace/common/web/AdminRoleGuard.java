package com.apimarketplace.common.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.Map;

/**
 * Utility for admin role verification on backend endpoints.
 * Uses the X-User-Roles header injected by the gateway (from JWT/Keycloak).
 */
public final class AdminRoleGuard {

    private static final String ADMIN_ROLE = "ADMIN";

    private AdminRoleGuard() {
    }

    /**
     * Check if the given roles string (comma-separated) contains the ADMIN role.
     */
    public static boolean isAdmin(String roles) {
        if (roles == null || roles.isBlank()) return false;
        return Arrays.asList(roles.split(",")).contains(ADMIN_ROLE);
    }

    /**
     * Returns a 403 Forbidden response if not admin, or null if admin.
     * Usage: {@code var denied = AdminRoleGuard.denyIfNotAdmin(roles); if (denied != null) return denied;}
     */
    public static ResponseEntity<Map<String, Object>> denyIfNotAdmin(String roles) {
        if (isAdmin(roles)) return null;
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Admin access required"));
    }
}
