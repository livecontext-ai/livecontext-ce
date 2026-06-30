package com.apimarketplace.common.web;

import org.springframework.http.HttpHeaders;

import java.lang.reflect.Method;

/**
 * Forwards the active request-scope HTTP headers ({@code X-User-ID},
 * {@code X-Organization-ID}, {@code X-Organization-Role}, {@code X-User-Roles})
 * from the currently bound HTTP request to an outbound {@link HttpHeaders}.
 *
 * <p><b>Why this lives in common-lib (not in each *-client):</b> before this
 * helper, every {@code *-client} module duplicated the same ~50-line
 * reflective implementation (auth-client, agent-client, conversation-client,
 * credential-client, datasource-client, interface-client, notification-client,
 * publication-client, storage-client, trigger-client, plus
 * {@code agent-service/SubAgentBridgeClient}). Maintenance hazard + bug
 * surface (header name typos, missing org-role forwarding, etc.). Consolidated
 * here so a single fix propagates to every inter-service hop.
 *
 * <p><b>Why reflection:</b> *-client modules ship as lightweight JARs with
 * {@code jakarta.servlet-api} declared {@code provided}. Direct dispatch on
 * {@code ServletRequestAttributes.getRequest()} would force the compiler to
 * resolve {@code HttpServletRequest} (the return type), pulling in the
 * servlet API at compile time. Reflection erases that type binding.
 *
 * <p><b>Why best-effort silent on errors:</b> failing to forward MUST NEVER
 * break the outbound call. Callers stay on personal scope (the safe default -
 * downstream services gate explicitly where required). Async / scheduled
 * paths have no request context → no-op.
 *
 * <p><b>Idempotency:</b> if the outbound headers already contain a value
 * (caller set it explicitly), this helper does NOT overwrite. Explicit beats
 * inherited.
 */
public final class OrgContextHeaderForwarder {

    private OrgContextHeaderForwarder() {
        // utility
    }

    /**
     * Sets {@code X-Organization-ID} on {@code outbound} iff the supplied
     * {@code orgId} is non-null and non-blank. No-op otherwise.
     *
     * <p>Centralizes the optional-header pattern duplicated across every
     * {@code *-client} module that builds outbound RestTemplate headers from
     * an explicit caller-supplied org (the {@link #forward} helper above is
     * the implicit thread-bound variant; this one is for callers that pass
     * the org as a method argument).
     */
    public static void setIfPresent(HttpHeaders outbound, String orgId) {
        if (orgId != null && !orgId.isBlank()) {
            outbound.set("X-Organization-ID", orgId);
        }
    }

    /**
     * Copies {@code X-User-ID}, {@code X-Organization-ID},
     * {@code X-Organization-Role}, and {@code X-User-Roles} from the currently
     * bound HTTP request onto the supplied outbound headers. No-op outside a
     * request context (async / scheduled callers); no-op on any reflection failure.
     *
     * <p>{@code X-User-ID} is request-bound only: it is copied from the
     * validated servlet request but intentionally has NO async ThreadLocal
     * fallback, so user identity cannot bleed across enqueue/dequeue boundaries.
     *
     * <p>{@code X-User-Roles} (the platform role set - typically
     * {@code "USER"} or {@code "ADMIN,USER"} as set by the gateway from JWT
     * claims) intentionally has NO async ThreadLocal fallback below: granting
     * ADMIN to daemon/queue threads would be a privilege-elevation surface.
     * Sync request-bound hops get the real role; async hops default to USER
     * downstream.
     *
     * @param outbound the outbound headers to enrich; must be non-null
     */
    public static void forward(HttpHeaders outbound) {
        try {
            Object attrs = org.springframework.web.context.request.RequestContextHolder
                    .getRequestAttributes();
            if (attrs != null) {
                Method getRequest;
                try {
                    getRequest = attrs.getClass().getMethod("getRequest");
                } catch (NoSuchMethodException nsm) {
                    getRequest = null;
                }
                if (getRequest != null) {
                    Object req = getRequest.invoke(attrs);
                    if (req != null) {
                        Method getHeader = req.getClass().getMethod("getHeader", String.class);
                        copyHeaderIfPresent(outbound, getHeader, req, "X-User-ID");
                        copyHeaderIfPresent(outbound, getHeader, req, "X-Organization-ID");
                        copyHeaderIfPresent(outbound, getHeader, req, "X-Organization-Role");
                        copyHeaderIfPresent(outbound, getHeader, req, "X-User-Roles");
                    }
                }
            }
        } catch (Exception ignored) {
            // Best-effort - fall through to thread-local check.
        }

        // Post-V261 - fallback to the async ThreadLocal bound by
        // {@link TenantResolver#runWithOrgScope(String, Runnable)} so daemon
        // / queue worker paths that explicitly bound an org can still
        // propagate it to outbound HTTP hops (no servlet request available).
        // Without this, daemon → HTTP client → downstream service silently
        // drops X-Organization-ID, breaking post-V261 strict isolation on
        // inter-service hops.
        if (!outbound.containsKey("X-Organization-ID")) {
            String asyncOrg = TenantResolver.currentRequestOrganizationId();
            if (asyncOrg != null && !asyncOrg.isBlank()) {
                outbound.set("X-Organization-ID", asyncOrg);
            }
        }
        // 2026-05-21 - symmetric fallback for X-Organization-Role so async
        // wraps that bind the role via runWithOrgScope(orgId, orgRole, task)
        // also propagate it to downstream HTTP hops. Closes the parity gap
        // between currentRequestOrganizationId() and currentRequestOrganizationRole().
        if (!outbound.containsKey("X-Organization-Role")) {
            String asyncRole = TenantResolver.currentRequestOrganizationRole();
            if (asyncRole != null && !asyncRole.isBlank()) {
                outbound.set("X-Organization-Role", asyncRole);
            }
        }
    }

    private static void copyHeaderIfPresent(HttpHeaders outbound, Method getHeader, Object request, String headerName)
            throws Exception {
        Object value = getHeader.invoke(request, headerName);
        if (value instanceof String s && !s.isBlank() && !outbound.containsKey(headerName)) {
            outbound.set(headerName, s);
        }
    }
}
