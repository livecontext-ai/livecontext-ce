package com.apimarketplace.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MonolithSecurityFilter")
class MonolithSecurityFilterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("strips forged trusted headers from external unauthenticated requests")
    void spoofedHeadersWithoutJwtAreStrippedBeforeController() throws Exception {
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of());
        MockHttpServletRequest request = externalRequest("/api/ce/registration");
        request.addHeader("X-User-ID", "999");
        request.addHeader("X-User-Roles", "ADMIN");
        request.addHeader("X-Organization-ID", "11111111-1111-1111-1111-111111111111");
        request.addHeader("X-Active-Organization-ID", "22222222-2222-2222-2222-222222222222");
        request.addHeader(MonolithSecurityFilter.MONOLITH_ACTIVE_ORG_CLAIM_HEADER,
                "33333333-3333-3333-3333-333333333333");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(captured.get()).isNotNull();
        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-User-ID")).isNull();
        assertThat(forwarded.getHeader("X-User-Roles")).isNull();
        assertThat(forwarded.getHeader("X-Organization-ID")).isNull();
        assertThat(forwarded.getHeader("X-Active-Organization-ID")).isNull();
        assertThat(forwarded.getHeader(MonolithSecurityFilter.MONOLITH_ACTIVE_ORG_CLAIM_HEADER)).isNull();
    }

    @Test
    @DisplayName("preserves pre-injected trusted headers on non-protected loopback requests")
    void loopbackPreInjectedHeadersArePreservedForNonProtectedInternalCalls() throws Exception {
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/storage/quota");
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

    @Test
    @DisplayName("strips loopback spoofed headers on protected conversation callback without JWT")
    void protectedConversationCallbackDoesNotTrustLoopbackHeadersWithoutJwt() throws Exception {
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of("/api/internal/"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/internal/conversation/tools/execute");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-User-ID", "42");
        request.addHeader("X-User-Roles", "ADMIN");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(captured.get()).isNotNull();
        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-User-ID")).isNull();
        assertThat(forwarded.getHeader("X-User-Roles")).isNull();
    }

    @Test
    @DisplayName("valid RSA JWT injects CE trusted user headers")
    void validRsaJwtInjectsTrustedHeaders() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> keyPair.getPublic(), List.of());
        MockHttpServletRequest request = externalRequest("/api/storage/quota");
        request.addHeader("Authorization", "Bearer " + signedJwt(keyPair, Map.of(
                "sub", "42",
                "userId", 42,
                "email", "ce-user@example.com",
                "provider", "local",
                "roles", List.of("USER", "ADMIN"),
                "token_type", "access",
                "exp", Instant.now().plusSeconds(300).getEpochSecond()
        )));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(captured.get()).isNotNull();
        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-User-ID")).isEqualTo("42");
        assertThat(forwarded.getHeader("X-Authenticated")).isEqualTo("true");
        assertThat(forwarded.getHeader("X-User-Roles")).isEqualTo("USER,ADMIN");
        assertThat(forwarded.getHeader("X-User-Plan")).isEqualTo("CE");
        assertThat(forwarded.getHeader("X-Remaining-Credits")).isEqualTo("999999999");
    }

    @Test
    @DisplayName("valid RSA JWT resolves active organization from membership claims")
    void validRsaJwtResolvesActiveOrganizationFromMembershipClaims() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> keyPair.getPublic(), List.of());
        MockHttpServletRequest request = externalRequest("/api/schedules");
        request.addHeader("X-Organization-ID", "forged-org");
        request.addHeader("X-Organization-Role", "OWNER");
        request.addHeader("X-Active-Organization-ID", "team-org-uuid");
        request.addHeader("Authorization", "Bearer " + signedJwt(keyPair, orgScopedAccessClaims()));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(captured.get()).isNotNull();
        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-Organization-ID")).isEqualTo("team-org-uuid");
        assertThat(forwarded.getHeader("X-Organization-Role")).isEqualTo("ADMIN");
        assertThat(forwarded.getHeader("X-Active-Organization-ID")).isNull();
        assertThat(forwarded.getHeader(MonolithSecurityFilter.MONOLITH_ACTIVE_ORG_CLAIM_HEADER))
                .isEqualTo("team-org-uuid");
    }

    @Test
    @DisplayName("valid RSA JWT hands off stale active organization claims for DB validation")
    void validRsaJwtHandsOffStaleActiveOrganizationClaimForDbValidation() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> keyPair.getPublic(), List.of());
        MockHttpServletRequest request = externalRequest("/api/schedules");
        request.addHeader("X-Active-Organization-ID", "fresh-team-org-uuid");
        request.addHeader("Authorization", "Bearer " + signedJwt(keyPair, orgScopedAccessClaims()));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(captured.get()).isNotNull();
        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-Organization-ID")).isEqualTo("default-org-uuid");
        assertThat(forwarded.getHeader("X-Active-Organization-ID")).isNull();
        assertThat(forwarded.getHeader(MonolithSecurityFilter.MONOLITH_ACTIVE_ORG_CLAIM_HEADER))
                .isEqualTo("fresh-team-org-uuid");
    }

    @Test
    @DisplayName("valid RSA JWT binds injected organization headers for internal client forwarding")
    void validRsaJwtBindsInjectedOrganizationHeadersForInternalClientForwarding() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> keyPair.getPublic(), List.of());
        MockHttpServletRequest request = externalRequest("/api/schedules/config");
        request.addHeader("X-Active-Organization-ID", "team-org-uuid");
        request.addHeader("Authorization", "Bearer " + signedJwt(keyPair, orgScopedAccessClaims()));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<HttpHeaders> forwardedHeaders = new AtomicReference<>();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            HttpHeaders outbound = new HttpHeaders();
            OrgContextHeaderForwarder.forward(outbound);
            forwardedHeaders.set(outbound);
        });

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(forwardedHeaders.get()).isNotNull();
        assertThat(forwardedHeaders.get().getFirst("X-User-ID")).isEqualTo("42");
        assertThat(forwardedHeaders.get().getFirst("X-Organization-ID")).isEqualTo("team-org-uuid");
        assertThat(forwardedHeaders.get().getFirst("X-Organization-Role")).isEqualTo("ADMIN");
        assertThat(forwardedHeaders.get().getFirst("X-User-Roles")).isEqualTo("USER");
    }

    @Test
    @DisplayName("valid RSA JWT keeps organization scope when downstream request binding is replaced")
    void validRsaJwtKeepsOrganizationScopeWhenDownstreamRequestBindingIsReplaced() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> keyPair.getPublic(), List.of());
        MockHttpServletRequest request = externalRequest("/api/schedules/config");
        request.addHeader("X-Active-Organization-ID", "team-org-uuid");
        request.addHeader("Authorization", "Bearer " + signedJwt(keyPair, orgScopedAccessClaims()));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<HttpHeaders> forwardedHeaders = new AtomicReference<>();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

            HttpHeaders outbound = new HttpHeaders();
            OrgContextHeaderForwarder.forward(outbound);
            forwardedHeaders.set(outbound);
        });

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(forwardedHeaders.get()).isNotNull();
        assertThat(forwardedHeaders.get().getFirst("X-Organization-ID")).isEqualTo("team-org-uuid");
        assertThat(forwardedHeaders.get().getFirst("X-Organization-Role")).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("invalid active organization claim falls back to the default organization")
    void invalidActiveOrganizationClaimFallsBackToDefaultOrganization() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> keyPair.getPublic(), List.of());
        MockHttpServletRequest request = externalRequest("/api/schedules");
        request.addHeader("X-Active-Organization-ID", "stranger-org-uuid");
        request.addHeader("Authorization", "Bearer " + signedJwt(keyPair, orgScopedAccessClaims()));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(captured.get()).isNotNull();
        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-Organization-ID")).isEqualTo("default-org-uuid");
        assertThat(forwarded.getHeader("X-Organization-Role")).isEqualTo("MEMBER");
        assertThat(forwarded.getHeader("X-Active-Organization-ID")).isNull();
    }

    @Test
    @DisplayName("absent active organization claim uses the default organization")
    void absentActiveOrganizationClaimUsesDefaultOrganization() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> keyPair.getPublic(), List.of());
        MockHttpServletRequest request = externalRequest("/api/schedules");
        request.addHeader("Authorization", "Bearer " + signedJwt(keyPair, orgScopedAccessClaims()));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(captured.get()).isNotNull();
        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-Organization-ID")).isEqualTo("default-org-uuid");
        assertThat(forwarded.getHeader("X-Organization-Role")).isEqualTo("MEMBER");
    }

    @Test
    @DisplayName("configured public workflow paths still inject trusted headers when bearer token is present")
    void publicWorkflowPathWithBearerTokenInjectsTrustedHeaders() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> keyPair.getPublic(), List.of("/api/workflows/"));
        MockHttpServletRequest request = externalRequest("/api/workflows/11111111-1111-1111-1111-111111111111");
        request.addHeader("Authorization", "Bearer " + signedJwt(keyPair, Map.of(
                "sub", "42",
                "userId", 42,
                "email", "ce-user@example.com",
                "provider", "local",
                "roles", List.of("USER"),
                "token_type", "access",
                "exp", Instant.now().plusSeconds(300).getEpochSecond()
        )));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(captured.get()).isNotNull();
        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-User-ID")).isEqualTo("42");
        assertThat(forwarded.getHeader("X-Authenticated")).isEqualTo("true");
        assertThat(forwarded.getHeader("X-User-Plan")).isEqualTo("CE");
    }

    @Test
    @DisplayName("configured public paths without bearer token remain public and strip forged identity headers")
    void publicPathWithoutBearerTokenRemainsPublicAndStripsSpoofedHeaders() throws Exception {
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of("/api/workflows/"));
        MockHttpServletRequest request = externalRequest("/api/workflows/11111111-1111-1111-1111-111111111111");
        request.addHeader("X-User-ID", "999");
        request.addHeader("X-User-Roles", "ADMIN");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(captured.get()).isNotNull();
        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-User-ID")).isNull();
        assertThat(forwarded.getHeader("X-User-Roles")).isNull();
    }

    @Test
    @DisplayName("public paths ignore expired bearer tokens and continue anonymously")
    void publicPathWithExpiredBearerTokenContinuesAnonymously() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> keyPair.getPublic(), List.of());
        MockHttpServletRequest request = externalRequest("/webhook/public-token");
        request.addHeader("X-User-ID", "999");
        request.addHeader("Authorization", "Bearer " + signedJwt(keyPair, Map.of(
                "sub", "42",
                "userId", 42,
                "roles", List.of("USER"),
                "token_type", "access",
                "exp", Instant.now().minusSeconds(5).getEpochSecond()
        )));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(captured.get()).isNotNull();
        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-User-ID")).isNull();
    }

    @Test
    @DisplayName("conversation tool callback is protected even when /api/internal is public")
    void conversationToolCallbackIsProtectedEvenWhenInternalPathsArePublic() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> keyPair.getPublic(), List.of("/api/internal/"));
        MockHttpServletRequest request = externalRequest("/api/internal/conversation/tools/execute");
        request.setMethod("POST");
        request.addHeader("Authorization", "Bearer " + signedJwt(keyPair, Map.of(
                "sub", "42",
                "userId", 42,
                "email", "ce-user@example.com",
                "provider", "local",
                "roles", List.of("USER"),
                "token_type", "access",
                "exp", Instant.now().plusSeconds(300).getEpochSecond()
        )));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(captured.get()).isNotNull();
        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-User-ID")).isEqualTo("42");
        assertThat(forwarded.getHeader("X-Authenticated")).isEqualTo("true");
    }

    @Test
    @DisplayName("credential integration lookup is protected even when inherited service config marks it public")
    void credentialByIntegrationIsProtectedEvenWhenConfiguredPublic() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        MonolithSecurityFilter filter = new MonolithSecurityFilter(
                () -> keyPair.getPublic(),
                List.of("/api/credentials/by-integration/"));
        MockHttpServletRequest request = externalRequest("/api/credentials/by-integration/accuweather");
        request.addHeader("Authorization", "Bearer " + signedJwt(keyPair, Map.of(
                "sub", "42",
                "userId", 42,
                "email", "ce-user@example.com",
                "provider", "local",
                "roles", List.of("USER"),
                "token_type", "access",
                "exp", Instant.now().plusSeconds(300).getEpochSecond()
        )));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(captured.get()).isNotNull();
        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-User-ID")).isEqualTo("42");
        assertThat(forwarded.getHeader("X-Authenticated")).isEqualTo("true");
    }

    @Test
    @DisplayName("share token read injects scoped owner headers and strips forged share headers")
    void shareTokenReadInjectsScopedOwnerHeadersAndStripsForgedShareHeaders() throws Exception {
        MonolithSecurityFilter filter = new MonolithSecurityFilter(
                () -> null,
                List.of(),
                token -> new MonolithSecurityFilter.ShareTokenContext(
                        "42",
                        "org-789",
                        "APPLICATION",
                        "pub-123",
                        "workflow-456"));
        MockHttpServletRequest request = externalRequest("/api/publications/00000000-0000-0000-0000-000000000123");
        request.addHeader("Authorization", "ShareToken sl_scope");
        request.addHeader("X-Share-Resource-Token", "forged-pub");
        request.addHeader("X-User-ID", "999");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(captured.get()).isNotNull();
        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-User-ID")).isEqualTo("42");
        assertThat(forwarded.getHeader("X-Organization-ID")).isEqualTo("org-789");
        assertThat(forwarded.getHeader("X-Authenticated")).isEqualTo("true");
        assertThat(forwarded.getHeader("X-Provider-ID")).isEqualTo("share:sl_scope");
        assertThat(forwarded.getHeader("X-Share-Context")).isEqualTo("true");
        assertThat(forwarded.getHeader("X-Share-Resource-Type")).isEqualTo("APPLICATION");
        assertThat(forwarded.getHeader("X-Share-Resource-Token")).isEqualTo("pub-123");
        assertThat(forwarded.getHeader("X-Share-Resource-Id")).isEqualTo("workflow-456");
    }

    @Test
    @DisplayName("share token delete is rejected with gateway-compatible read-only error")
    void shareTokenDeleteIsRejectedWithGatewayCompatibleReadOnlyError() throws Exception {
        MonolithSecurityFilter filter = new MonolithSecurityFilter(
                () -> null,
                List.of(),
                token -> new MonolithSecurityFilter.ShareTokenContext(
                        "42",
                        "org-789",
                        "APPLICATION",
                        "pub-123",
                        "workflow-456"));
        MockHttpServletRequest request = externalRequest("/api/v2/workflows/dag/00000000-0000-0000-0000-000000000456");
        request.setMethod("DELETE");
        request.addHeader("Authorization", "Bearer ShareToken sl_scope");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("Write operations are not allowed in a shared context");
        assertThat(captured.get()).isNull();
    }

    @Test
    @DisplayName("share token post is rejected with gateway-compatible read-only error")
    void shareTokenPostIsRejectedWithGatewayCompatibleReadOnlyError() throws Exception {
        MonolithSecurityFilter filter = new MonolithSecurityFilter(
                () -> null,
                List.of(),
                token -> new MonolithSecurityFilter.ShareTokenContext(
                        "42",
                        "org-789",
                        "APPLICATION",
                        "pub-123",
                        "workflow-456"));
        MockHttpServletRequest request = externalRequest("/api/publications/pub-123/reviews");
        request.setMethod("POST");
        request.addHeader("Authorization", "Bearer ShareToken sl_scope");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("Write operations are not allowed in a shared context");
        assertThat(captured.get()).isNull();
    }

    @Test
    @DisplayName("share token POST to /api/workflow-inspector/tools/batch is forwarded - read, not write (cloud parity)")
    void shareTokenWorkflowInspectorBatchPostIsForwardedAsRead() throws Exception {
        // Cloud parity with AuthenticationFilter.isAllowedShareTokenReadOnlyPost: this batch endpoint
        // resolves tool metadata for the whole plan in one read. A prior guard that blocked all
        // non-GET 403'd it, forcing the builder into a slow per-tool N+1 fallback in a shared
        // context. CE must allow it so a CE share link behaves like a cloud one. Resource type is
        // intentionally WORKFLOW (not APPLICATION) - the carve-out is path+method only, independent
        // of the application-bootstrap exception. Fails on the pre-fix filter (which 403'd it).
        MonolithSecurityFilter filter = new MonolithSecurityFilter(
                () -> null,
                List.of(),
                token -> new MonolithSecurityFilter.ShareTokenContext(
                        "42",
                        "org-789",
                        "WORKFLOW",
                        "pub-123",
                        "workflow-456"));
        MockHttpServletRequest request = externalRequest("/api/workflow-inspector/tools/batch");
        request.setMethod("POST");
        request.addHeader("Authorization", "Bearer ShareToken sl_scope");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isNotEqualTo(403);
        assertThat(captured.get())
                .as("read-only batch tool-fetch must be forwarded in a shared context, not 403'd")
                .isNotNull();
    }

    @Test
    @DisplayName("application share token post may bootstrap the application run")
    void applicationShareTokenPostMayBootstrapApplicationRun() throws Exception {
        MonolithSecurityFilter filter = new MonolithSecurityFilter(
                () -> null,
                List.of(),
                token -> new MonolithSecurityFilter.ShareTokenContext(
                        "42",
                        "org-789",
                        "APPLICATION",
                        "pub-123",
                        "workflow-456"));
        MockHttpServletRequest request = externalRequest("/api/v2/workflows/dag/execute");
        request.setMethod("POST");
        request.addHeader("Authorization", "Bearer ShareToken sl_scope");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(captured.get()).isNotNull();
        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-Share-Context")).isEqualTo("true");
        assertThat(forwarded.getHeader("X-Share-Resource-Type")).isEqualTo("APPLICATION");
        assertThat(forwarded.getHeader("X-Share-Resource-Token")).isEqualTo("pub-123");
        assertThat(forwarded.getHeader("X-Share-Resource-Id")).isEqualTo("workflow-456");
    }

    @Test
    @DisplayName("rejects a JWT signed by a different key")
    void forgedJwtSignedWithAnotherKeyIsRejected() throws Exception {
        KeyPair trusted = generateRsaKeyPair();
        KeyPair attacker = generateRsaKeyPair();
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> trusted.getPublic(), List.of());
        MockHttpServletRequest request = externalRequest("/api/storage/quota");
        request.addHeader("Authorization", "Bearer " + signedJwt(attacker, Map.of(
                "sub", "999",
                "userId", 999,
                "roles", List.of("ADMIN"),
                "token_type", "access",
                "exp", Instant.now().plusSeconds(300).getEpochSecond()
        )));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(captured.get()).isNull();
    }

    @Test
    @DisplayName("rejects bearer tokens when no verification key is configured")
    void bearerTokenIsRejectedWhenVerificationKeyMissing() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of());
        MockHttpServletRequest request = externalRequest("/api/storage/quota");
        request.addHeader("Authorization", "Bearer " + signedJwt(keyPair, Map.of(
                "sub", "42",
                "userId", 42,
                "roles", List.of("USER"),
                "token_type", "access",
                "exp", Instant.now().plusSeconds(300).getEpochSecond()
        )));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(captured.get()).isNull();
    }

    @Test
    @DisplayName("rejects valid signed refresh tokens on protected API routes")
    void signedRefreshTokenIsRejectedForProtectedApiRoutes() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> keyPair.getPublic(), List.of());
        MockHttpServletRequest request = externalRequest("/api/storage/quota");
        request.addHeader("Authorization", "Bearer " + signedJwt(keyPair, Map.of(
                "sub", "42",
                "userId", 42,
                "email", "ce-user@example.com",
                "roles", List.of("USER"),
                "token_type", "refresh",
                "exp", Instant.now().plusSeconds(30 * 24 * 3600).getEpochSecond()
        )));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(captured.get()).isNull();
    }

    @Test
    @DisplayName("rejects signed tokens without an expiration claim")
    void signedTokenWithoutExpirationIsRejected() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> keyPair.getPublic(), List.of());
        MockHttpServletRequest request = externalRequest("/api/storage/quota");
        request.addHeader("Authorization", "Bearer " + signedJwt(keyPair, Map.of(
                "sub", "42",
                "userId", 42,
                "roles", List.of("USER"),
                "token_type", "access"
        )));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(captured.get()).isNull();
    }

    @Test
    @DisplayName("invitation-info lookup is public: an invalid bearer is ignored and the request is forwarded anonymously")
    void invitationInfoIsPublicSoInvalidBearerFallsThroughAnonymously() throws Exception {
        // The accept page looks up an invitation while logged out. A stale/expired
        // token can still ride along in the client; on a PUBLIC path the filter must
        // ignore a failed-validation bearer and forward anonymously (not 401). This is
        // the BLOCKER fix: pre-fix the path was NOT public, so an invalid bearer 401'd
        // and the accept page rendered "invalid" instead of the register form.
        KeyPair keyPair = generateRsaKeyPair();
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> keyPair.getPublic(), List.of());
        MockHttpServletRequest request = externalRequest("/api/organizations/invitations/info");
        // Garbage bearer (not 3 parts) → validateAndExtractClaims returns null.
        request.addHeader("Authorization", "Bearer not-a-real-jwt");
        request.addHeader("X-User-ID", "999"); // forged identity must still be stripped
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(captured.get())
                .as("public invitation-info lookup must be forwarded, not 401'd")
                .isNotNull();
        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-User-ID")).isNull();
    }

    @Test
    @DisplayName("invitation-info lookup with no bearer is forwarded anonymously and strips forged identity headers")
    void invitationInfoWithoutBearerIsForwardedAndStripsForgedHeaders() throws Exception {
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of());
        MockHttpServletRequest request = externalRequest("/api/organizations/invitations/info");
        request.addHeader("X-User-ID", "999");
        request.addHeader("X-User-Roles", "ADMIN");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(captured.get()).isNotNull();
        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-User-ID")).isNull();
        assertThat(forwarded.getHeader("X-User-Roles")).isNull();
    }

    @Test
    @DisplayName("invitation sibling (/invitations/mine) is NOT made public: an invalid bearer is rejected 401")
    void invitationMineSiblingIsNotPublicAndRejectsInvalidBearer() throws Exception {
        // The exact /invitations/info entry must not widen to the authenticated inbox.
        // /invitations/mine is NOT public, so a failed-validation bearer 401s here
        // (proving the scoping). This fails if the public match were a prefix that
        // also caught /mine.
        KeyPair keyPair = generateRsaKeyPair();
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> keyPair.getPublic(), List.of());
        MockHttpServletRequest request = externalRequest("/api/organizations/invitations/mine");
        request.addHeader("Authorization", "Bearer not-a-real-jwt");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(captured.get())
                .as("non-public invitation sibling must reject an invalid bearer, not forward it")
                .isNull();
    }

    private static MockHttpServletRequest externalRequest(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr("203.0.113.10");
        return request;
    }

    private static MockFilterChain capturingChain(AtomicReference<ServletRequest> captured) {
        return new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest request, jakarta.servlet.ServletResponse response) {
                captured.set(request);
            }
        };
    }

    private static KeyPair generateRsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String signedJwt(KeyPair keyPair, Map<String, Object> claims) throws Exception {
        String header = base64Url(MAPPER.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT")));
        String payload = base64Url(MAPPER.writeValueAsBytes(claims));
        String signingInput = header + "." + payload;
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + base64Url(signature.sign());
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Map<String, Object> orgScopedAccessClaims() {
        return Map.of(
                "sub", "42",
                "userId", 42,
                "email", "ce-user@example.com",
                "provider", "local",
                "roles", List.of("USER"),
                "token_type", "access",
                "exp", Instant.now().plusSeconds(300).getEpochSecond(),
                "defaultOrganizationId", "default-org-uuid",
                "defaultOrganizationRole", "MEMBER",
                "memberships", List.of(
                        Map.of("orgId", "default-org-uuid", "role", "MEMBER", "personal", false, "paused", false),
                        Map.of("orgId", "team-org-uuid", "role", "ADMIN", "personal", false, "paused", false)));
    }
}
