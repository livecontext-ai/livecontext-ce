package com.apimarketplace.common.web;

import jakarta.servlet.ServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the CE monolith {@code /api/internal/**} external-access block.
 *
 * <p>Bug: removing the cloud gateway left {@link MonolithSecurityFilter} as the sole authz layer,
 * which only stripped identity <em>headers</em>. The whole internal service-to-service surface
 * ({@code /api/internal/**}) stayed reachable by any external caller via the published backend
 * port. Those endpoints trust a caller-supplied identity (a {@code userId} query/path param, or
 * none at all) because in the cloud topology only the gateway reaches them - server-side, after
 * JWT validation - and the gateway NEVER routes {@code /api/internal/**} from outside. Concrete
 * impacts: unauthenticated cross-tenant read of DECRYPTED credentials
 * ({@code /api/internal/credentials/access-token?userId=…}), force-purge of a workspace
 * ({@code /api/internal/auth/workspace/{orgId}/purge}), cross-tenant run/agent access probes, etc.</p>
 *
 * <p>Fix: external (non-loopback) requests to internal paths get a 404 (mirroring the gateway's
 * "no route"). A small allowlist stays reachable: the public gateway rewrite targets
 * (webhook/chat/form/widget/app-public), the JWT-protected paths, the bridge pricing snapshot, and
 * the browser-agent live-CDP endpoints the frontend calls. In-process loopback calls are untouched.</p>
 *
 * <p>Every "blocked" test fails on the pre-fix filter (which passed the request straight to the
 * chain); the "allowed" tests guard against the block regressing legitimate flows.</p>
 */
@DisplayName("MonolithSecurityFilter /api/internal external-access block")
class MonolithSecurityFilterInternalPathTest {

    private static final String EXTERNAL_IP = "203.0.113.10";

    private static final String FILTER_404_BODY = "{\"error\":\"Not Found\"}";

    // ── BLOCKED: external callers must never reach the internal service-to-service surface ──

    @Test
    @DisplayName("external requests to internal credential endpoints are 404 and never reach the chain")
    void externalCredentialEndpointsBlocked() throws Exception {
        for (String p : List.of(
                "/api/internal/credentials/access-token?userId=victim&name=stripe",
                "/api/internal/credentials/data-map?userId=victim&name=stripe",
                "/api/internal/credentials/all?userId=victim")) {
            assertBlocked("GET", p);
        }
    }

    @Test
    @DisplayName("external request to the workspace force-purge endpoint is blocked (was unauthenticated + destructive)")
    void externalWorkspacePurgeBlocked() throws Exception {
        assertBlocked("POST", "/api/internal/auth/workspace/11111111-1111-1111-1111-111111111111/purge");
    }

    @Test
    @DisplayName("external requests to other internal service-to-service endpoints are blocked")
    void externalOtherInternalEndpointsBlocked() throws Exception {
        for (String[] req : List.of(
                new String[] {"POST", "/api/internal/auth/workspace/reconcile/42"},
                new String[] {"GET", "/api/internal/cloud-link/source/42"},
                new String[] {"GET", "/api/internal/agents/abc/access?userId=victim"},
                new String[] {"POST", "/api/internal/datasource/crud/execute"},
                new String[] {"POST", "/api/internal/sbs/run-1/execute/core:prepare"},
                new String[] {"POST", "/api/internal/signals/sig-1/resolve"},
                new String[] {"POST", "/api/internal/agent-observability/record"},
                new String[] {"GET", "/api/internal/trigger/tokens"},
                new String[] {"GET", "/api/internal/runs/run-1/snapshot"})) {
            assertBlocked(req[0], req[1]);
        }
    }

    // ── ALLOWED: the allowlist must stay reachable externally (no over-block / no regression) ──

    @Test
    @DisplayName("external requests to the public gateway rewrite targets pass through (self-validating)")
    void externalPublicRewriteTargetsAllowed() throws Exception {
        for (String[] req : List.of(
                new String[] {"POST", "/api/internal/webhook/abc123"},
                new String[] {"GET", "/api/internal/chat/c1"},
                new String[] {"GET", "/api/internal/form/f1"},
                new String[] {"GET", "/api/internal/widget/loader.js"},
                new String[] {"GET", "/api/internal/app/public/a1"})) {
            assertReachesChain(req[0], req[1]);
        }
    }

    @Test
    @DisplayName("external requests to the bridge pricing snapshot and browser-agent (JWT) endpoints pass through")
    void externalAllowlistedClientsAllowed() throws Exception {
        assertReachesChain("GET", "/api/internal/auth/pricing/snapshot");
        assertReachesChain("POST", "/api/internal/browser-agent/runs/r1/nodes/n1/cdp-token-refresh");
    }

    @Test
    @DisplayName("external request to the JWT-protected tools/execute path is not 404-blocked (auth enforced separately)")
    void externalProtectedToolsExecuteNotBlocked() throws Exception {
        assertReachesChain("POST", "/api/internal/conversation/tools/execute");
    }

    @Test
    @DisplayName("external request to the bridge-access guard reaches the chain (exempt like browser-agent; JWT enforced below, not 404)")
    void externalBridgeAccessGuardReachesChain() throws Exception {
        assertReachesChain("POST", "/api/internal/bridge-access/check?bridge=codex&incrementUsage=false");
    }

    @Test
    @DisplayName("a non-internal external path is unaffected by the block")
    void externalNonInternalPathUnaffected() throws Exception {
        assertReachesChain("GET", "/api/storage/quota");
    }

    // ── CONTRAST: the in-process loopback caller (the only legitimate internal caller) still works ──

    @Test
    @DisplayName("loopback (127.0.0.1) internal credential call still reaches the chain")
    void loopbackInternalCredentialCallStillWorks() throws Exception {
        assertLoopbackReachesChain("127.0.0.1", "/api/internal/credentials/access-token");
    }

    @Test
    @DisplayName("loopback (::1) internal workspace-purge call still reaches the chain")
    void ipv6LoopbackInternalPurgeStillWorks() throws Exception {
        assertLoopbackReachesChain("::1", "/api/internal/auth/workspace/11111111-1111-1111-1111-111111111111/purge");
    }

    @Test
    @DisplayName("regression: loopback in-process bridge-access check still reaches the chain (not stripped/401'd)")
    void loopbackBridgeAccessCheckStillWorks() throws Exception {
        // The CE in-process guard (BridgeAccessEnforcer / HttpBridgeAccessClient) calls this over
        // loopback with X-User-ID and no JWT. Putting it in isProtectedMonolithPath broke that path
        // (stripped identity + JWT required -> 401 -> fail-closed deny of every bridge agent).
        assertLoopbackReachesChain("127.0.0.1", "/api/internal/bridge-access/check?bridge=codex&incrementUsage=true");
    }

    // ── CE-1: loopback-trust must not be inherited by a proxied request ──
    // A same-host reverse proxy (nginx/Caddy `proxy_pass http://127.0.0.1`) - a
    // deployment the project's own docs recommend for the source build - makes
    // getRemoteAddr() == 127.0.0.1 for EVERY external request. Pre-fix that
    // inherited loopback trust, re-opening the /api/internal/** surface AND
    // forwarding a forged X-User-ID unstripped. A genuine in-process call never
    // carries a forwarding header, so its presence marks the request as proxied.

    @Test
    @DisplayName("loopback remoteAddr WITH X-Forwarded-For is treated as proxied/external and BLOCKED (404), forged identity not forwarded")
    void loopbackWithForwardedForIsBlocked() throws Exception {
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/internal/credentials/access-token");
        request.setRemoteAddr("127.0.0.1");
        request.setQueryString("userId=victim&name=stripe");
        request.addHeader("X-Forwarded-For", EXTERNAL_IP);
        request.addHeader("X-User-ID", "999"); // forged by the external client
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).as("proxied loopback must be 404-blocked").isEqualTo(404);
        assertThat(response.getContentAsString()).isEqualTo(FILTER_404_BODY);
        assertThat(captured.get()).as("proxied loopback must not reach the internal credential endpoint").isNull();
    }

    @Test
    @DisplayName("loopback remoteAddr with X-Real-IP is likewise treated as proxied and BLOCKED (404)")
    void loopbackWithXRealIpIsBlocked() throws Exception {
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of());
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/internal/auth/workspace/11111111-1111-1111-1111-111111111111/purge");
        request.setRemoteAddr("::1");
        request.addHeader("X-Real-IP", EXTERNAL_IP);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(captured.get()).isNull();
    }

    @Test
    @DisplayName("loopback remoteAddr with an RFC 7239 Forwarded header is treated as proxied and BLOCKED (404)")
    void loopbackWithRfc7239ForwardedHeaderIsBlocked() throws Exception {
        // The RFC 7239 `Forwarded` header is the third proxy marker isLoopbackRequest inspects (after
        // X-Forwarded-For and X-Real-IP). A genuine in-process call never sets it, so its presence on
        // a 127.0.0.1 origin marks the request as relayed by a same-host reverse proxy and must drop
        // loopback trust, leaving the internal surface 404-blocked.
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/internal/credentials/access-token");
        request.setRemoteAddr("127.0.0.1");
        request.setQueryString("userId=victim&name=stripe");
        request.addHeader("Forwarded", "for=203.0.113.10;proto=https");
        request.addHeader("X-User-ID", "999"); // forged by the external client
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).as("Forwarded-header loopback must be 404-blocked").isEqualTo(404);
        assertThat(response.getContentAsString()).isEqualTo(FILTER_404_BODY);
        assertThat(captured.get()).as("proxied loopback must not reach the internal credential endpoint").isNull();
    }

    @Test
    @DisplayName("null remoteAddr is treated as non-loopback (external) and BLOCKED on internal paths (404)")
    void nullRemoteAddrIsTreatedAsExternalAndBlocked() throws Exception {
        // isLoopbackRequest returns false when getRemoteAddr() is null/blank, so the request is held to
        // the external rules and the internal credential surface is 404-blocked rather than trusted.
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/internal/credentials/access-token");
        request.setRemoteAddr(null);
        request.setQueryString("userId=victim&name=stripe");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).as("null remoteAddr must not inherit loopback trust").isEqualTo(404);
        assertThat(response.getContentAsString()).isEqualTo(FILTER_404_BODY);
        assertThat(captured.get()).isNull();
    }

    // ── Allowlist boundary: trailing-slash precision + header-strip defence on a wide prefix ──

    @Test
    @DisplayName("allowlisted /api/internal/chat/sync reaches the chain but a forged X-User-ID is stripped")
    void allowlistedChatSyncStripsForgedIdentity() throws Exception {
        // /api/internal/chat/ is allowlisted for the public token-based PublicChatController, but it
        // also covers InternalChatController's /chat/sync (header identity). That is safe because a
        // non-loopback request gets its trusted identity headers stripped - so a forged X-User-ID
        // never reaches the controller (it would then 400 on the required header, or only ever act
        // as the JWT-validated caller).
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/internal/chat/sync");
        request.setRemoteAddr(EXTERNAL_IP);
        request.addHeader("X-User-ID", "999"); // forged
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isNotEqualTo(404);
        assertThat(captured.get()).isNotNull();
        assertThat(((jakarta.servlet.http.HttpServletRequest) captured.get()).getHeader("X-User-ID"))
                .as("forged X-User-ID must be stripped on a non-loopback allowlisted internal path")
                .isNull();
    }

    @Test
    @DisplayName("bare /api/internal/chat (no trailing slash) is NOT allowlisted and is blocked")
    void bareInternalChatBlocked() throws Exception {
        assertBlocked("POST", "/api/internal/chat");
    }

    // ── Defence-in-depth: the service-prefix rewrite vector is also blocked ──

    @Test
    @DisplayName("service-prefix-rewritten internal path is also blocked (rewrite filter runs first)")
    void servicePrefixRewrittenInternalPathBlocked() throws Exception {
        // ServicePrefixRewriteFilter (@Order -10) rewrites
        // /api/auth-service/api/internal/credentials/... -> /api/internal/credentials/... BEFORE
        // MonolithSecurityFilter (@Order 0) reads getRequestURI(); prove the block still fires.
        ServicePrefixRewriteFilter rewrite = new ServicePrefixRewriteFilter();
        MonolithSecurityFilter security = new MonolithSecurityFilter(() -> null, List.of());
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/auth-service/api/internal/credentials/access-token");
        request.setRemoteAddr(EXTERNAL_IP);
        request.setQueryString("userId=victim&name=stripe");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean servletReached = new AtomicBoolean(false);
        jakarta.servlet.Servlet servlet = new jakarta.servlet.http.HttpServlet() {
            @Override
            public void service(ServletRequest req, jakarta.servlet.ServletResponse res) {
                servletReached.set(true);
            }
        };
        MockFilterChain chain = new MockFilterChain(servlet, rewrite, security);

        chain.doFilter(request, response);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(servletReached.get()).isFalse();
    }

    // ── helpers ──

    private static void assertBlocked(String method, String pathWithQuery) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = invokeExternal(method, pathWithQuery, response);
        assertThat(response.getStatus()).as(method + " " + pathWithQuery + " status").isEqualTo(404);
        assertThat(response.getContentAsString()).as(method + " " + pathWithQuery + " body").isEqualTo(FILTER_404_BODY);
        assertThat(captured.get()).as(method + " " + pathWithQuery + " must not reach chain").isNull();
    }

    private static void assertReachesChain(String method, String pathWithQuery) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = invokeExternal(method, pathWithQuery, response);
        assertThat(response.getStatus()).as(method + " " + pathWithQuery + " status").isNotEqualTo(404);
        assertThat(captured.get()).as(method + " " + pathWithQuery + " must reach chain").isNotNull();
    }

    private static void assertLoopbackReachesChain(String remoteAddr, String path) throws Exception {
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr(remoteAddr);
        request.addHeader("X-User-ID", "u1"); // trusted header set by the calling in-process service
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();
        filter.doFilter(request, response, capturingChain(captured));
        assertThat(response.getStatus()).isNotEqualTo(404);
        assertThat(captured.get()).as("loopback internal call must pass through").isNotNull();
    }

    private static AtomicReference<ServletRequest> invokeExternal(
            String method, String pathWithQuery, MockHttpServletResponse response) throws Exception {
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of());
        String path = pathWithQuery;
        String query = null;
        int q = pathWithQuery.indexOf('?');
        if (q >= 0) {
            path = pathWithQuery.substring(0, q);
            query = pathWithQuery.substring(q + 1);
        }
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(EXTERNAL_IP);
        if (query != null) {
            request.setQueryString(query);
        }
        AtomicReference<ServletRequest> captured = new AtomicReference<>();
        filter.doFilter(request, response, capturingChain(captured));
        return captured;
    }

    private static MockFilterChain capturingChain(AtomicReference<ServletRequest> captured) {
        return new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest request, jakarta.servlet.ServletResponse response) {
                captured.set(request);
            }
        };
    }
}
