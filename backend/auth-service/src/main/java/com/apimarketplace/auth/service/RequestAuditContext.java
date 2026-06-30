package com.apimarketplace.auth.service;

import com.apimarketplace.auth.util.ClientIpExtractor;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Request-derived signals that get attached to every ce-link audit row
 * (doc §7 forensic trail): HMAC-hashed client IP, the HMAC key generation
 * that produced it, and the raw User-Agent header.
 *
 * <p>Built once at the controller boundary (where {@link HttpServletRequest}
 * is in scope) and threaded down into the service, so the service stays
 * Servlet-API-free and unit-testable with synthetic values.
 *
 * <p>{@code ipHash} is null when no request is available (cron jobs, MCP-side
 * background ticks) - the audit row's {@code ip_hash} column is nullable
 * specifically for that case.
 */
public record RequestAuditContext(String ipHash, Integer keyVersion, String userAgent) {

    /**
     * Sentinel used by background / non-HTTP callers (cron jobs, MCP-side
     * background ticks). {@code keyVersion=1} so the audit row's
     * {@code NOT NULL key_version} column never trips, but {@code ipHash=null}
     * documents that no IP was hashed - forensic queries can filter on
     * {@code ip_hash IS NULL} to identify SYSTEM-origin events.
     */
    public static RequestAuditContext none() {
        return new RequestAuditContext(null, 1, null);
    }

    /**
     * Build from a live request + the {@link IpHashService} to use for the hash.
     * The User-Agent header is trimmed to 256 chars to fit the audit column.
     */
    public static RequestAuditContext from(HttpServletRequest request,
                                           IpHashService ipHashService,
                                           java.util.UUID installId) {
        String ip = ClientIpExtractor.extract(request);
        String userAgent = request != null ? request.getHeader("User-Agent") : null;
        if (userAgent != null && userAgent.length() > 256) {
            userAgent = userAgent.substring(0, 256);
        }
        if (ip == null || installId == null || ipHashService == null) {
            // No request data to hash - still produce a valid context. keyVersion=1
            // matches the V260 DEFAULT, so a downstream NOT NULL constraint never trips.
            return new RequestAuditContext(null, 1, userAgent);
        }
        IpHashService.HashResult hash = ipHashService.hashWithCurrent(installId, ip);
        return new RequestAuditContext(hash.hash(), hash.keyVersion(), userAgent);
    }
}
