package com.apimarketplace.auth.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Pulls the originating client IP from an incoming HTTP request, honoring the
 * {@code X-Forwarded-For} / {@code X-Real-IP} chain set by Caddy / Cloudflare
 * (only the LEFT-most XFF entry is the actual originator).
 *
 * <p>Logic-equivalent to the private helper in {@code AuditLogger}; extracted
 * here so new code (e.g. {@code CeLinkController} heartbeat) reuses it instead
 * of cloning the if-else. Existing callsites in {@code AuditLogger} /
 * {@code EmbeddedAuthController} can migrate in a focused dedup pass.
 */
public final class ClientIpExtractor {

    private ClientIpExtractor() {}

    public static String extract(HttpServletRequest request) {
        if (request == null) return null;
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        return request.getRemoteAddr();
    }
}
