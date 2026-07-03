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
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API-key authentication in the CE monolith filter (X-API-Key header or
 * "Authorization: Bearer lc_live_..."), mirroring the cloud gateway's
 * ApiKeyResolver path. Added for the MCP Streamable HTTP endpoint, whose
 * external clients authenticate with the user's lc_live_ key.
 */
@DisplayName("MonolithSecurityFilter API-key authentication")
class MonolithSecurityFilterApiKeyTest {

    private static final String VALID_KEY = "lc_live_" + "a".repeat(64);

    private static MonolithSecurityFilter.JwtClaims claimsForUser42() {
        return new MonolithSecurityFilter.JwtClaims(
                "42",
                "local:mcp-user@example.com",
                "mcp-user@example.com",
                "USER",
                "org-uuid-1",
                "OWNER",
                List.of(new MonolithSecurityFilter.OrgMembershipClaim("org-uuid-1", "OWNER", true, false),
                        new MonolithSecurityFilter.OrgMembershipClaim("team-org-uuid", "ADMIN", false, false)));
    }

    private static MonolithSecurityFilter filterWithResolver(Function<String, MonolithSecurityFilter.JwtClaims> resolver) {
        return new MonolithSecurityFilter(() -> null, List.of(), token -> null, resolver);
    }

    private static MockHttpServletRequest externalRequest(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("203.0.113.10");
        return request;
    }

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

    private static MockFilterChain capturingChain(AtomicReference<ServletRequest> captured) {
        return new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
                captured.set(request);
            }
        };
    }

    @Test
    @DisplayName("valid X-API-Key header resolves the owner and injects trusted user headers")
    void validXApiKeyHeaderInjectsTrustedHeaders() throws Exception {
        MonolithSecurityFilter filter = filterWithResolver(key ->
                VALID_KEY.equals(key) ? claimsForUser42() : null);
        MockHttpServletRequest request = externalRequest("POST", "/mcp");
        request.addHeader("X-API-Key", VALID_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(200);
        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-User-ID")).isEqualTo("42");
        assertThat(forwarded.getHeader("X-Authenticated")).isEqualTo("true");
        assertThat(forwarded.getHeader("X-User-Email")).isEqualTo("mcp-user@example.com");
        assertThat(forwarded.getHeader("X-Organization-ID")).isEqualTo("org-uuid-1");
        assertThat(forwarded.getHeader("X-Organization-Role")).isEqualTo("OWNER");
    }

    @Test
    @DisplayName("Bearer lc_live_ key is routed to the API-key resolver, not JWT validation")
    void bearerLcLiveKeyIsResolvedAsApiKey() throws Exception {
        AtomicReference<String> resolvedKey = new AtomicReference<>();
        MonolithSecurityFilter filter = filterWithResolver(key -> {
            resolvedKey.set(key);
            return claimsForUser42();
        });
        MockHttpServletRequest request = externalRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer " + VALID_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(resolvedKey.get()).isEqualTo(VALID_KEY);
        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-User-ID")).isEqualTo("42");
    }

    @Test
    @DisplayName("unknown API key on a protected path returns 401 invalid API key")
    void unknownApiKeyReturns401() throws Exception {
        MonolithSecurityFilter filter = filterWithResolver(key -> null);
        MockHttpServletRequest request = externalRequest("POST", "/mcp");
        request.addHeader("X-API-Key", "lc_live_deadbeef");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Invalid or unknown API key");
        assertThat(captured.get()).isNull();
    }

    @Test
    @DisplayName("API key without a configured resolver fails closed with 401")
    void apiKeyWithoutResolverFailsClosed() throws Exception {
        // 3-arg constructor: no apiKeyResolver wired (non-monolith hosts).
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of(), token -> null);
        MockHttpServletRequest request = externalRequest("POST", "/mcp");
        request.addHeader("X-API-Key", VALID_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(captured.get()).isNull();
    }

    @Test
    @DisplayName("invalid Bearer lc_live_ key on a public path falls back to anonymous instead of 401")
    void invalidBearerApiKeyOnPublicPathStaysAnonymous() throws Exception {
        MonolithSecurityFilter filter = filterWithResolver(key -> null);
        MockHttpServletRequest request = externalRequest("GET", "/api/public/catalog");
        request.addHeader("Authorization", "Bearer lc_live_unknown");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(200);
        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-User-ID")).isNull();
    }

    @Test
    @DisplayName("forged identity headers sent alongside an API key are replaced by the resolved identity")
    void forgedHeadersAlongsideApiKeyAreReplaced() throws Exception {
        MonolithSecurityFilter filter = filterWithResolver(key -> claimsForUser42());
        MockHttpServletRequest request = externalRequest("POST", "/mcp");
        request.addHeader("X-API-Key", VALID_KEY);
        request.addHeader("X-User-ID", "999");
        request.addHeader("X-User-Roles", "ADMIN");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-User-ID")).isEqualTo("42");
        assertThat(forwarded.getHeader("X-User-Roles")).isEqualTo("USER");
    }

    @Test
    @DisplayName("valid membership active-org claim switches the injected org scope for API-key auth")
    void activeOrgClaimResolvesAgainstMembershipsForApiKeyAuth() throws Exception {
        MonolithSecurityFilter filter = filterWithResolver(key -> claimsForUser42());
        MockHttpServletRequest request = externalRequest("POST", "/mcp");
        request.addHeader("X-API-Key", VALID_KEY);
        request.addHeader("X-Active-Organization-ID", "team-org-uuid");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-Organization-ID")).isEqualTo("team-org-uuid");
        assertThat(forwarded.getHeader("X-Organization-Role")).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("when both X-API-Key and Bearer lc_live_ are sent, the X-API-Key header wins (gateway parity)")
    void xApiKeyHeaderWinsOverBearerApiKey() throws Exception {
        AtomicReference<String> resolvedKey = new AtomicReference<>();
        MonolithSecurityFilter filter = filterWithResolver(key -> {
            resolvedKey.set(key);
            return claimsForUser42();
        });
        String otherKey = "lc_live_" + "b".repeat(64);
        MockHttpServletRequest request = externalRequest("POST", "/mcp");
        request.addHeader("X-API-Key", VALID_KEY);
        request.addHeader("Authorization", "Bearer " + otherKey);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(resolvedKey.get()).isEqualTo(VALID_KEY);
    }

    @Test
    @DisplayName("a valid JWT sent alongside an X-API-Key header wins, matching the gateway's JWT-first order")
    void jwtAlongsideApiKeyHeaderTakesPrecedence() throws Exception {
        java.security.KeyPair keyPair = generateRsaKeyPair();
        AtomicBoolean resolverCalled = new AtomicBoolean(false);
        MonolithSecurityFilter filter = new MonolithSecurityFilter(
                () -> keyPair.getPublic(), List.of(), token -> null,
                key -> {
                    resolverCalled.set(true);
                    return claimsForUser42();
                });
        MockHttpServletRequest request = externalRequest("POST", "/mcp");
        request.addHeader("X-API-Key", VALID_KEY);
        request.addHeader("Authorization", "Bearer " + signedJwt(keyPair, java.util.Map.of(
                "sub", "77",
                "userId", 77,
                "email", "jwt-user@example.com",
                "provider", "local",
                "roles", List.of("USER"),
                "token_type", "access",
                "exp", java.time.Instant.now().plusSeconds(300).getEpochSecond())));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(resolverCalled).isFalse();
        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-User-ID")).isEqualTo("77");
    }

    @Test
    @DisplayName("a regular Bearer JWT never reaches the API-key resolver")
    void regularBearerJwtDoesNotHitApiKeyResolver() throws Exception {
        AtomicBoolean resolverCalled = new AtomicBoolean(false);
        MonolithSecurityFilter filter = filterWithResolver(key -> {
            resolverCalled.set(true);
            return null;
        });
        MockHttpServletRequest request = externalRequest("GET", "/api/storage/quota");
        request.addHeader("Authorization", "Bearer not-an-api-key.jwt.payload");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(resolverCalled).isFalse();
        // Invalid JWT on a protected path still 401s through the JWT branch.
        assertThat(response.getStatus()).isEqualTo(401);
    }
}
