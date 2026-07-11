package com.apimarketplace.common.web;

import jakarta.servlet.ServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * X-Api-Key-Scopes propagation in the CE monolith filter (multi API keys, V398),
 * mirroring the cloud gateway: the header is injected ONLY for a SCOPED key
 * (ApiKeyAuth.scopes non-null), never for full-access keys or JWT auth, and a
 * client-supplied inbound copy is stripped like the other identity headers.
 */
@DisplayName("MonolithSecurityFilter X-Api-Key-Scopes propagation")
class MonolithSecurityFilterApiKeyScopesTest {

    private static final String VALID_KEY = "lc_live_" + "a".repeat(64);
    private static final String SCOPES_HEADER = "X-Api-Key-Scopes";

    private static MonolithSecurityFilter.JwtClaims claimsForUser42() {
        return new MonolithSecurityFilter.JwtClaims(
                "42",
                "local:mcp-user@example.com",
                "mcp-user@example.com",
                "USER",
                "org-uuid-1",
                "OWNER",
                List.of(new MonolithSecurityFilter.OrgMembershipClaim("org-uuid-1", "OWNER", true, false)));
    }

    private static MonolithSecurityFilter filterWithResolver(
            Function<String, MonolithSecurityFilter.ApiKeyAuth> resolver) {
        return new MonolithSecurityFilter(() -> null, List.of(), token -> null, resolver);
    }

    private static MockHttpServletRequest externalRequest(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("203.0.113.10");
        return request;
    }

    private static MockFilterChain capturingChain(AtomicReference<ServletRequest> captured) {
        return new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
                captured.set(request);
            }
        };
    }

    @Test
    @DisplayName("scoped API key injects the comma-joined X-Api-Key-Scopes header")
    void scopedKeyInjectsScopesHeader() throws Exception {
        MonolithSecurityFilter filter = filterWithResolver(key ->
                new MonolithSecurityFilter.ApiKeyAuth(claimsForUser42(), List.of("workflow", "table")));
        MockHttpServletRequest request = externalRequest("POST", "/mcp");
        request.addHeader("X-API-Key", VALID_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(200);
        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-User-ID")).isEqualTo("42");
        assertThat(forwarded.getHeader(SCOPES_HEADER)).isEqualTo("workflow,table");
    }

    @Test
    @DisplayName("full-access API key (null scopes) does NOT inject the header")
    void fullAccessKeyDoesNotInjectScopesHeader() throws Exception {
        MonolithSecurityFilter filter = filterWithResolver(key ->
                new MonolithSecurityFilter.ApiKeyAuth(claimsForUser42(), null));
        MockHttpServletRequest request = externalRequest("POST", "/mcp");
        request.addHeader("X-API-Key", VALID_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-User-ID")).isEqualTo("42");
        assertThat(forwarded.getHeader(SCOPES_HEADER)).isNull();
    }

    @Test
    @DisplayName("a spoofed inbound X-Api-Key-Scopes alongside a full-access key is stripped")
    void spoofedScopesHeaderAlongsideApiKeyIsStripped() throws Exception {
        MonolithSecurityFilter filter = filterWithResolver(key ->
                new MonolithSecurityFilter.ApiKeyAuth(claimsForUser42(), null));
        MockHttpServletRequest request = externalRequest("POST", "/mcp");
        request.addHeader("X-API-Key", VALID_KEY);
        request.addHeader(SCOPES_HEADER, "admin,everything");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader(SCOPES_HEADER)).isNull();
        assertThat(java.util.Collections.list(forwarded.getHeaders(SCOPES_HEADER))).isEmpty();
    }

    @Test
    @DisplayName("a spoofed inbound header cannot widen a scoped key: only the resolved scopes survive")
    void spoofedHeaderReplacedByResolvedScopes() throws Exception {
        MonolithSecurityFilter filter = filterWithResolver(key ->
                new MonolithSecurityFilter.ApiKeyAuth(claimsForUser42(), List.of("workflow")));
        MockHttpServletRequest request = externalRequest("POST", "/mcp");
        request.addHeader("X-API-Key", VALID_KEY);
        request.addHeader(SCOPES_HEADER, "workflow,table,credential");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader(SCOPES_HEADER)).isEqualTo("workflow");
        assertThat(java.util.Collections.list(forwarded.getHeaders(SCOPES_HEADER)))
                .containsExactly("workflow");
    }

    @Test
    @DisplayName("a spoofed inbound X-Api-Key-Scopes on an anonymous external request is stripped")
    void spoofedScopesHeaderOnAnonymousRequestIsStripped() throws Exception {
        MonolithSecurityFilter filter = filterWithResolver(key -> null);
        MockHttpServletRequest request = externalRequest("GET", "/api/public/catalog");
        request.addHeader(SCOPES_HEADER, "admin");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(200);
        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader(SCOPES_HEADER)).isNull();
    }

    @Test
    @DisplayName("JWT authentication never injects X-Api-Key-Scopes and strips a spoofed copy")
    void jwtAuthStripsAndDoesNotInject() throws Exception {
        java.security.KeyPair keyPair = generateRsaKeyPair();
        MonolithSecurityFilter filter = new MonolithSecurityFilter(
                () -> keyPair.getPublic(), List.of(), token -> null,
                (Function<String, MonolithSecurityFilter.ApiKeyAuth>) key -> null);
        MockHttpServletRequest request = externalRequest("GET", "/api/workflows");
        request.addHeader(SCOPES_HEADER, "admin,everything");
        request.addHeader("Authorization", "Bearer " + signedJwt(keyPair, java.util.Map.of(
                "sub", "42",
                "userId", 42,
                "email", "jwt-user@example.com",
                "provider", "local",
                "roles", List.of("USER"),
                "token_type", "access",
                "exp", java.time.Instant.now().plusSeconds(300).getEpochSecond())));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(200);
        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-User-ID")).isEqualTo("42");
        assertThat(forwarded.getHeader(SCOPES_HEADER)).isNull();
    }

    // ========== JWT helpers (mirrors MonolithSecurityFilterApiKeyTest) ==========

    private static java.security.KeyPair generateRsaKeyPair() throws Exception {
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String signedJwt(java.security.KeyPair keyPair, java.util.Map<String, Object> claims) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String header = base64Url(mapper.writeValueAsBytes(java.util.Map.of("alg", "RS256", "typ", "JWT")));
        String payload = base64Url(mapper.writeValueAsBytes(claims));
        String signingInput = header + "." + payload;
        java.security.Signature signature = java.security.Signature.getInstance("SHA256withRSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(signingInput.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return signingInput + "." + base64Url(signature.sign());
    }

    private static String base64Url(byte[] bytes) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
