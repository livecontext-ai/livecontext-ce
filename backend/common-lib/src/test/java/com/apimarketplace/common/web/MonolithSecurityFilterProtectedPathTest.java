package com.apimarketplace.common.web;

import jakarta.servlet.ServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Closes the e2e coverage ceiling for the CE monolith "protected path" identity-header strip.
 *
 * <p>End-to-end tests run over a real HTTP socket and therefore cannot exercise the
 * {@code isLoopbackRequest} branch in isolation (they always arrive from a non-loopback or a
 * loopback origin, never both controllably). The security guarantee that matters here - that
 * {@link MonolithSecurityFilter#isProtectedMonolithPath(String)} paths
 * (notably {@code /api/credentials/by-integration/}) are wrapped with the
 * stripped-identity request wrapper <em>regardless of network origin</em>, so externally supplied
 * trusted identity headers can never be honored - must be proven at the filter unit layer.</p>
 *
 * <p>Mirrors the construction / capture / assertion style of {@code MonolithSecurityFilterTest}.</p>
 */
@DisplayName("MonolithSecurityFilter protected-path identity-header strip")
class MonolithSecurityFilterProtectedPathTest {

    private static final String PROTECTED_CREDENTIAL_PATH =
            "/api/credentials/by-integration/accuweather";
    // bridge-access/check is internal-only but reachable like browser-agent/: loopback-trusted for the
    // in-process service callers (X-User-ID, no JWT), JWT-enforced for external callers. It is NOT in
    // isProtectedMonolithPath - protecting it stripped loopback trust and 401'd the in-process check.
    private static final String BRIDGE_ACCESS_PATH =
            "/api/internal/bridge-access/check";

    @Test
    @DisplayName("strips forged trusted headers on a protected credentials path for an external unauthenticated request")
    void protectedCredentialPathStripsForgedHeadersForExternalRequestWithoutJwt() throws Exception {
        // Arrange: external (non-loopback) request to a protected path carrying forged trust headers, no JWT.
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", PROTECTED_CREDENTIAL_PATH);
        request.setRemoteAddr("203.0.113.10");
        forgeTrustedIdentityHeaders(request);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        // Act
        filter.doFilter(request, response, capturingChain(captured));

        // Assert: downstream never sees any forged trusted identity header.
        assertThat(captured.get()).isNotNull();
        assertStrippedTrustedIdentityHeaders((jakarta.servlet.http.HttpServletRequest) captured.get());
    }

    @Test
    @DisplayName("strips forged trusted headers on the bridge-access guard for an EXTERNAL request without JWT")
    void bridgeAccessGuardStripsForgedHeadersForExternalRequestWithoutJwt() throws Exception {
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", BRIDGE_ACCESS_PATH);
        request.setRemoteAddr("203.0.113.10");
        forgeTrustedIdentityHeaders(request);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(captured.get()).isNotNull();
        assertStrippedTrustedIdentityHeaders((jakarta.servlet.http.HttpServletRequest) captured.get());
    }

    @Test
    @DisplayName("strips forged trusted headers on a protected credentials path even for an IPv4 loopback request")
    void protectedCredentialPathStripsForgedHeadersForIpv4LoopbackRequestWithoutJwt() throws Exception {
        // Arrange: loopback-looking request (127.0.0.1) to a protected path, forged trust headers, no JWT.
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", PROTECTED_CREDENTIAL_PATH);
        request.setRemoteAddr("127.0.0.1");
        forgeTrustedIdentityHeaders(request);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        // Act
        filter.doFilter(request, response, capturingChain(captured));

        // Assert: protected paths are never exempted by origin - loopback still gets stripped.
        assertThat(captured.get()).isNotNull();
        assertStrippedTrustedIdentityHeaders((jakarta.servlet.http.HttpServletRequest) captured.get());
    }

    @Test
    @DisplayName("strips forged trusted headers on a protected credentials path even for an IPv6 loopback request")
    void protectedCredentialPathStripsForgedHeadersForIpv6LoopbackRequestWithoutJwt() throws Exception {
        // Arrange: IPv6 loopback (::1) request to a protected path, forged trust headers, no JWT.
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", PROTECTED_CREDENTIAL_PATH);
        request.setRemoteAddr("::1");
        forgeTrustedIdentityHeaders(request);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        // Act
        filter.doFilter(request, response, capturingChain(captured));

        // Assert: IPv6 loopback is still a protected-path strip, no origin exemption.
        assertThat(captured.get()).isNotNull();
        assertStrippedTrustedIdentityHeaders((jakarta.servlet.http.HttpServletRequest) captured.get());
    }

    @Test
    @DisplayName("strips forged trusted headers on a protected credentials path even when that prefix is configured public")
    void protectedCredentialPathStripsForgedHeadersEvenWhenConfiguredPublicLoopbackWithoutJwt() throws Exception {
        // Arrange: the protected prefix is *also* listed as a public path, loopback origin, forged headers, no JWT.
        // isPublicPath() defers to isProtectedMonolithPath(), so the protected branch must still win.
        MonolithSecurityFilter filter = new MonolithSecurityFilter(
                () -> null,
                List.of("/api/credentials/by-integration/"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", PROTECTED_CREDENTIAL_PATH);
        request.setRemoteAddr("127.0.0.1");
        forgeTrustedIdentityHeaders(request);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        // Act
        filter.doFilter(request, response, capturingChain(captured));

        // Assert: a configured "public" entry cannot un-protect a protected path.
        assertThat(captured.get()).isNotNull();
        assertStrippedTrustedIdentityHeaders((jakarta.servlet.http.HttpServletRequest) captured.get());
    }

    @Test
    @DisplayName("contrast control: a non-protected loopback path preserves pre-injected trusted headers")
    void nonProtectedLoopbackPathPreservesPreInjectedTrustedHeaders() throws Exception {
        // Arrange: SAME conditions (loopback, no JWT, trusted headers present) but a NON-protected path.
        // This proves the strip in the tests above is caused by the protected-path branch, not by the
        // request being unauthenticated.
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/storage/quota");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-User-ID", "42");
        request.addHeader("X-User-Roles", "USER");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        // Act
        filter.doFilter(request, response, capturingChain(captured));

        // Assert: pre-injected loopback headers survive on the non-protected path (no strip).
        assertThat(captured.get()).isNotNull();
        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-User-ID")).isEqualTo("42");
        assertThat(forwarded.getHeader("X-User-Roles")).isEqualTo("USER");
    }

    @Test
    @DisplayName("regression: loopback in-process bridge-access check preserves the trusted X-User-ID (must not strip)")
    void loopbackBridgeAccessCheckPreservesTrustedIdentity() throws Exception {
        // The in-process callers (BridgeAccessEnforcer / HttpBridgeAccessClient) send only X-User-ID,
        // no JWT. If bridge-access/check were in isProtectedMonolithPath the loopback identity would be
        // stripped and a JWT required -> 401 -> fail-closed deny of EVERY CE bridge agent. It must stay
        // loopback-trusted (this test fails on the protected-path variant, passes once it is exempted).
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", BRIDGE_ACCESS_PATH);
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-User-ID", "42");
        request.addHeader("X-User-Roles", "USER");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(captured.get()).isNotNull();
        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-User-ID")).isEqualTo("42");
        assertThat(forwarded.getHeader("X-User-Roles")).isEqualTo("USER");
    }

    private static void forgeTrustedIdentityHeaders(MockHttpServletRequest request) {
        request.addHeader("X-Authenticated", "true");
        request.addHeader("X-User-ID", "999");
        request.addHeader("X-User-Roles", "ADMIN");
        request.addHeader("X-User-Email", "attacker@example.com");
        request.addHeader("X-Organization-ID", "11111111-1111-1111-1111-111111111111");
        request.addHeader("X-Organization-Role", "OWNER");
        request.addHeader("X-Active-Organization-ID", "22222222-2222-2222-2222-222222222222");
        request.addHeader(MonolithSecurityFilter.MONOLITH_ACTIVE_ORG_CLAIM_HEADER,
                "33333333-3333-3333-3333-333333333333");
    }

    private static void assertStrippedTrustedIdentityHeaders(jakarta.servlet.http.HttpServletRequest forwarded) {
        assertThat(forwarded.getHeader("X-Authenticated")).isNull();
        assertThat(forwarded.getHeader("X-User-ID")).isNull();
        assertThat(forwarded.getHeader("X-User-Roles")).isNull();
        assertThat(forwarded.getHeader("X-User-Email")).isNull();
        assertThat(forwarded.getHeader("X-Organization-ID")).isNull();
        assertThat(forwarded.getHeader("X-Organization-Role")).isNull();
        assertThat(forwarded.getHeader("X-Active-Organization-ID")).isNull();
        assertThat(forwarded.getHeader(MonolithSecurityFilter.MONOLITH_ACTIVE_ORG_CLAIM_HEADER)).isNull();
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
