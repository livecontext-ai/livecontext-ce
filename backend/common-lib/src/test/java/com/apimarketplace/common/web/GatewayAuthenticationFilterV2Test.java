package com.apimarketplace.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * CASA LC-035: the v2 signature binds roles, method, path and query, the replay window is 60s in
 * both directions, and {@code gateway.signature.accept-v1} decides whether a v1-only request is
 * still accepted during the rollout.
 */
@DisplayName("GatewayAuthenticationFilter v2 signature (LC-035)")
class GatewayAuthenticationFilterV2Test {

    private static final String SECRET = "test-gateway-hmac-key-for-unit-tests";
    private GatewayFilterProperties properties;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        properties = new GatewayFilterProperties();
        properties.setSecretKey(SECRET);
        properties.setVerificationEnabled(true);
        properties.setPublicPaths(List.of("/health"));
        chain = mock(FilterChain.class);
    }

    /** Sign exactly like a real internal caller: stamp (v1) then stampV2 at send time. */
    private static HttpHeaders signedHeaders(String method, String uri, String user, String roles) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-User-ID", user);
        h.set("X-Organization-ID", "org_7");
        if (roles != null) {
            h.set("X-User-Roles", roles);
        }
        InternalGatewaySigner.stamp(h, "internal-test", SECRET);
        InternalGatewaySigner.stampV2(h, method, URI.create(uri), SECRET);
        return h;
    }

    private static MockHttpServletRequest toServlet(String method, String path, String query, HttpHeaders h) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, path);
        r.setRequestURI(path);
        r.setQueryString(query);
        h.forEach((name, values) -> values.forEach(v -> r.addHeader(name, v)));
        return r;
    }

    private int run(boolean acceptV1, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new GatewayAuthenticationFilter(properties, acceptV1).doFilter(request, response, chain);
        return response.getStatus();
    }

    @Test
    @DisplayName("a request signed by InternalGatewaySigner (v1 + v2) passes with v1 disabled")
    void signerAndVerifierAgree() throws Exception {
        HttpHeaders h = signedHeaders("GET", "http://svc:8080/api/admin/users?page=1", "42", "USER,ADMIN");
        int status = run(false, toServlet("GET", "/api/admin/users", "page=1", h));
        assertThat(status).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @Test
    @DisplayName("forging X-User-Roles after signing is rejected (the header v1 never covered)")
    void tamperedRolesRejected() throws Exception {
        HttpHeaders h = signedHeaders("GET", "http://svc/api/admin/users", "42", "USER");
        h.set("X-User-Roles", "USER,ADMIN");
        assertThat(run(false, toServlet("GET", "/api/admin/users", null, h))).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("a second X-User-Roles value appended after signing is rejected")
    void appendedRoleValueRejected() throws Exception {
        HttpHeaders h = signedHeaders("GET", "http://svc/api/admin/users", "42", "USER");
        h.add("X-User-Roles", "ADMIN");
        assertThat(run(false, toServlet("GET", "/api/admin/users", null, h))).isEqualTo(401);
    }

    @Test
    @DisplayName("replaying a signature on another path, method or query is rejected")
    void requestLineIsBound() throws Exception {
        HttpHeaders h = signedHeaders("GET", "http://svc/api/workflows/1?x=1", "42", null);
        assertThat(run(false, toServlet("GET", "/api/workflows/2", "x=1", h))).isEqualTo(401);
        assertThat(run(false, toServlet("DELETE", "/api/workflows/1", "x=1", h))).isEqualTo(401);
        assertThat(run(false, toServlet("GET", "/api/workflows/1", "x=2", h))).isEqualTo(401);
        assertThat(run(false, toServlet("GET", "/api/workflows/1", "x=1", h))).isEqualTo(200);
    }

    @Test
    @DisplayName("percent-encoding differences between signer and receiver do not break v2")
    void encodingIsCanonicalised() throws Exception {
        HttpHeaders h = signedHeaders("GET", "http://svc/api/files/by-id/a%20b/raw?n=%C3%A9", "42", null);
        // The servlet sees the same characters, encoded differently (lower-case hex).
        assertThat(run(false, toServlet("GET", "/api/files/by-id/a%20b/raw", "n=%c3%a9", h))).isEqualTo(200);
    }

    @Test
    @DisplayName("v2 reads the provider id from X-Provider-ID only, never from ?providerId=")
    void providerIdQueryFallbackIgnoredByV2() throws Exception {
        HttpHeaders h = signedHeaders("GET", "http://svc/api/users/resolve?providerId=kc-1", "42", null);
        MockHttpServletRequest r = toServlet("GET", "/api/users/resolve", "providerId=kc-1", h);
        r.removeHeader("X-Provider-ID");
        r.setParameter("providerId", "kc-1");
        assertThat(run(false, r)).isEqualTo(401);
    }

    @Test
    @DisplayName("accept-v1=false rejects a v1-only request")
    void v1OnlyRejectedWhenDisabled() throws Exception {
        HttpHeaders h = signedHeaders("GET", "http://svc/api/x", "42", null);
        h.remove(GatewaySignatureV2.HEADER);
        assertThat(run(false, toServlet("GET", "/api/x", null, h))).isEqualTo(401);
    }

    @Test
    @DisplayName("rollout: accept-v1=true still accepts a v1-only request from an old signer")
    void v1OnlyAcceptedDuringRollout() throws Exception {
        HttpHeaders h = signedHeaders("GET", "http://svc/api/x", "42", null);
        h.remove(GatewaySignatureV2.HEADER);
        assertThat(run(true, toServlet("GET", "/api/x", null, h))).isEqualTo(200);
    }

    @Test
    @DisplayName("rollout: accept-v1=true falls back to a valid v1 when v2 does not match")
    void v2MismatchFallsBackToV1DuringRollout() throws Exception {
        HttpHeaders h = signedHeaders("GET", "http://svc/api/x", "42", null);
        h.set(GatewaySignatureV2.HEADER, "gw2_not-the-right-one");
        assertThat(run(true, toServlet("GET", "/api/x", null, h))).isEqualTo(200);
        assertThat(run(false, toServlet("GET", "/api/x", null, h))).isEqualTo(401);
    }

    @Test
    @DisplayName("v1 keeps its 5-minute window during the rollout: 90s old passes, 6 minutes old does not")
    void v1WindowStaysFiveMinutes() throws Exception {
        String recent = String.valueOf(System.currentTimeMillis() - 90_000);
        HttpHeaders ok = new HttpHeaders();
        ok.set("X-User-ID", "42");
        ok.set("X-Provider-ID", "p");
        ok.set("X-Gateway-Timestamp", recent);
        ok.set("X-Gateway-Secret", InternalGatewaySigner.sign("p", "42", null, recent, SECRET));
        assertThat(run(true, toServlet("GET", "/api/x", null, ok))).isEqualTo(200);

        String ts = String.valueOf(System.currentTimeMillis() - 360_000);
        HttpHeaders h = new HttpHeaders();
        h.set("X-User-ID", "42");
        h.set("X-Provider-ID", "p");
        h.set("X-Gateway-Timestamp", ts);
        h.set("X-Gateway-Secret", InternalGatewaySigner.sign("p", "42", null, ts, SECRET));
        assertThat(run(true, toServlet("GET", "/api/x", null, h))).isEqualTo(401);
    }

    @Test
    @DisplayName("a signature dated in the future is rejected (it used to replay forever)")
    void futureTimestampRejected() throws Exception {
        String ts = String.valueOf(System.currentTimeMillis() + 400_000);
        HttpHeaders h = new HttpHeaders();
        h.set("X-User-ID", "42");
        h.set("X-Provider-ID", "p");
        h.set("X-Gateway-Timestamp", ts);
        h.set("X-Gateway-Secret", InternalGatewaySigner.sign("p", "42", null, ts, SECRET));
        InternalGatewaySigner.stampV2(h, "GET", URI.create("http://svc/api/x"), SECRET);
        assertThat(run(true, toServlet("GET", "/api/x", null, h))).isEqualTo(401);
        assertThat(run(false, toServlet("GET", "/api/x", null, h))).isEqualTo(401);
    }

    @Test
    @DisplayName("stampV2 leaves a request the caller never signed untouched")
    void stampV2IgnoresUnsignedRequests() {
        HttpHeaders h = new HttpHeaders();
        h.set("X-User-ID", "42");
        InternalGatewaySigner.stampV2(h, "GET", URI.create("https://third-party.example/x"), SECRET);
        assertThat(h.getFirst(GatewaySignatureV2.HEADER)).isNull();
        HttpHeaders signed = signedHeaders("GET", "http://svc/x", "42", null);
        HttpHeaders blank = new HttpHeaders();
        signed.forEach(blank::addAll);
        blank.remove(GatewaySignatureV2.HEADER);
        InternalGatewaySigner.stampV2(blank, "GET", URI.create("http://svc/x"), " ");
        assertThat(blank.getFirst(GatewaySignatureV2.HEADER)).isNull();
    }

    @Test
    @DisplayName("a blank hmac-required entry (unset env var) gates nothing; a set one gates its prefix")
    void blankHmacRequiredEntryMatchesNothing() throws Exception {
        properties.setPublicPaths(List.of("/health", "/api/internal/"));
        properties.setHmacRequiredPaths(List.of(""));
        assertThat(run(true, toServlet("GET", "/health", null, new HttpHeaders()))).isEqualTo(200);
        assertThat(run(true, toServlet("GET", "/api/internal/storage/download", null, new HttpHeaders()))).isEqualTo(200);

        properties.setHmacRequiredPaths(List.of("/api/internal/storage/"));
        assertThat(run(true, toServlet("GET", "/api/internal/storage/download", null, new HttpHeaders()))).isEqualTo(401);
        HttpHeaders signed = signedHeaders("GET", "http://svc/api/internal/storage/download?key=42/a", "42", null);
        assertThat(run(false, toServlet("GET", "/api/internal/storage/download", "key=42/a", signed))).isEqualTo(200);
    }

    @Test
    @DisplayName("v2 window is gateway.signature.max-skew-seconds (default 60s)")
    void v2WindowIsConfigurable() throws Exception {
        String ts = String.valueOf(System.currentTimeMillis() - 90_000);
        HttpHeaders h = new HttpHeaders();
        h.set("X-User-ID", "42");
        h.set("X-Provider-ID", "p");
        h.set("X-Gateway-Timestamp", ts);
        InternalGatewaySigner.stampV2(h, "GET", URI.create("http://svc/api/x"), SECRET);
        MockHttpServletResponse r1 = new MockHttpServletResponse();
        new GatewayAuthenticationFilter(properties, false).doFilter(toServlet("GET", "/api/x", null, h), r1, chain);
        assertThat(r1.getStatus()).as("90s old, default 60s window").isEqualTo(401);
        MockHttpServletResponse r2 = new MockHttpServletResponse();
        new GatewayAuthenticationFilter(properties, false, 120).doFilter(toServlet("GET", "/api/x", null, h), r2, chain);
        assertThat(r2.getStatus()).as("90s old, 120s window").isEqualTo(200);
    }
}
