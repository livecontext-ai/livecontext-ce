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
 * Security regression tests: in the CE monolith a SCOPED lc_live_ API key
 * (ApiKeyAuth.scopes != null) is confined to the canonical MCP streamable
 * endpoint (/mcp and /mcp/**) and gets 403 scope_forbidden everywhere else,
 * mirroring the cloud gateway. Pre-fix, a scoped key authenticated the ENTIRE
 * API surface, so it could mint itself a full-access key via
 * POST /api/auth/api-keys and execute out-of-scope tools through the legacy
 * /api/mcp/* REST surface. Full-access keys (scopes == null) and JWT sessions
 * are unaffected.
 */
@DisplayName("MonolithSecurityFilter scoped-key MCP confinement")
class MonolithSecurityFilterScopedKeyMcpConfinementTest {

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

    private static MockHttpServletRequest externalApiKeyRequest(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("X-API-Key", VALID_KEY);
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

    private static void assertScopeForbidden(MockHttpServletResponse response,
                                             AtomicReference<ServletRequest> captured) throws Exception {
        assertThat(captured.get()).as("request must NOT reach the chain").isNull();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString()).contains("scope_forbidden");
        assertThat(response.getContentAsString()).contains("limited to the MCP endpoint (/mcp)");
    }

    // ========== Scoped key on MCP paths: allowed ==========

    @Test
    @DisplayName("scoped key on /mcp is allowed and injects identity + scopes headers")
    void scopedKeyOnMcpAllowed() throws Exception {
        MonolithSecurityFilter filter = filterWithResolver(key ->
                new MonolithSecurityFilter.ApiKeyAuth(claimsForUser42(), List.of("workflow", "table")));
        MockHttpServletRequest request = externalApiKeyRequest("POST", "/mcp");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(200);
        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded).isNotNull();
        assertThat(forwarded.getHeader("X-User-ID")).isEqualTo("42");
        assertThat(forwarded.getHeader(SCOPES_HEADER)).isEqualTo("workflow,table");
    }

    @Test
    @DisplayName("scoped key on an /mcp/ sub-path is allowed")
    void scopedKeyOnMcpSubPathAllowed() throws Exception {
        MonolithSecurityFilter filter = filterWithResolver(key ->
                new MonolithSecurityFilter.ApiKeyAuth(claimsForUser42(), List.of("workflow")));
        MockHttpServletRequest request = externalApiKeyRequest("POST", "/mcp/session-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(200);
        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded).isNotNull();
        assertThat(forwarded.getHeader("X-User-ID")).isEqualTo("42");
    }

    // ========== Scoped key outside /mcp: 403 scope_forbidden ==========

    @Test
    @DisplayName("scoped key on a non-MCP protected path (/api/workflows) is 403 scope_forbidden")
    void scopedKeyOnWorkflowsForbidden() throws Exception {
        MonolithSecurityFilter filter = filterWithResolver(key ->
                new MonolithSecurityFilter.ApiKeyAuth(claimsForUser42(), List.of("workflow")));
        MockHttpServletRequest request = externalApiKeyRequest("GET", "/api/workflows");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertScopeForbidden(response, captured);
    }

    @Test
    @DisplayName("scoped key on POST /api/auth/api-keys is 403 (no self-minting a full-access key)")
    void scopedKeyOnKeyManagementForbidden() throws Exception {
        MonolithSecurityFilter filter = filterWithResolver(key ->
                new MonolithSecurityFilter.ApiKeyAuth(claimsForUser42(), List.of("workflow")));
        MockHttpServletRequest request = externalApiKeyRequest("POST", "/api/auth/api-keys");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertScopeForbidden(response, captured);
    }

    @Test
    @DisplayName("scoped key on the legacy /api/mcp/tools/call REST surface is 403")
    void scopedKeyOnLegacyMcpRestForbidden() throws Exception {
        MonolithSecurityFilter filter = filterWithResolver(key ->
                new MonolithSecurityFilter.ApiKeyAuth(claimsForUser42(), List.of("workflow")));
        MockHttpServletRequest request = externalApiKeyRequest("POST", "/api/mcp/tools/call");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertScopeForbidden(response, captured);
    }

    // ========== Full-access key and JWT: unchanged ==========

    @Test
    @DisplayName("full-access key (null scopes) on /api/workflows stays allowed (unchanged)")
    void fullAccessKeyOnWorkflowsAllowed() throws Exception {
        MonolithSecurityFilter filter = filterWithResolver(key ->
                new MonolithSecurityFilter.ApiKeyAuth(claimsForUser42(), null));
        MockHttpServletRequest request = externalApiKeyRequest("GET", "/api/workflows");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(200);
        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded).isNotNull();
        assertThat(forwarded.getHeader("X-User-ID")).isEqualTo("42");
        assertThat(forwarded.getHeader(SCOPES_HEADER)).isNull();
    }

    @Test
    @DisplayName("JWT session on /api/workflows stays allowed (unchanged)")
    void jwtOnWorkflowsAllowed() throws Exception {
        java.security.KeyPair keyPair = generateRsaKeyPair();
        MonolithSecurityFilter filter = new MonolithSecurityFilter(
                () -> keyPair.getPublic(), List.of(), token -> null,
                (Function<String, MonolithSecurityFilter.ApiKeyAuth>) key -> null);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/workflows");
        request.setRemoteAddr("203.0.113.10");
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
        assertThat(forwarded).isNotNull();
        assertThat(forwarded.getHeader("X-User-ID")).isEqualTo("42");
    }

    // ========== JWT helpers (mirrors MonolithSecurityFilterApiKeyScopesTest) ==========

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
