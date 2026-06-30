package com.apimarketplace.datasource.controllers.datasource;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Resolves tenantId from HTTP request headers or query parameters.
 * X-User-ID header (injected by Gateway from JWT) takes priority.
 *
 * Extracted from DataSourceController for Single Responsibility Principle.
 *
 * @see DataSourceController
 */
@Component
public class TenantIdResolver {

    /**
     * Resolve tenantId from the gateway-injected X-User-ID header ONLY.
     * Audit 2026-05-17 round-4 - the {@code tenantIdParam} fallback was a
     * Bug-#4 surface (client-supplied identity via {@code ?tenantId=victim}).
     * The query param is now ignored entirely; if X-User-ID is absent,
     * returns null and the controller fails closed (401).
     *
     * @param request The HTTP request
     * @param tenantIdParam Retained for binary back-compat with existing
     *                      controller signatures; no longer consulted.
     * @return The X-User-ID header value, or null when absent.
     */
    @SuppressWarnings("unused")
    public String resolveTenantId(HttpServletRequest request, String tenantIdParam) {
        String userIdHeader = request.getHeader("X-User-ID");
        if (userIdHeader != null && !userIdHeader.isBlank()) {
            return userIdHeader;
        }
        return null;
    }

    /**
     * Resolve the caller's active organization from the gateway-injected
     * {@code X-Organization-ID} header. Returns null when absent (personal
     * workspace).
     */
    public String resolveOrgId(HttpServletRequest request) {
        String h = request.getHeader("X-Organization-ID");
        return (h != null && !h.isBlank()) ? h : null;
    }
}
