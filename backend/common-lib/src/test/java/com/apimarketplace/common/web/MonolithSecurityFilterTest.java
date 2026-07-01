package com.apimarketplace.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    @DisplayName("share token resolving to a null context is rejected with 401 invalid-share-token")
    void shareTokenWithNullResolvedContextIsRejected() throws Exception {
        // shareTokenResolver yields no context for a structurally valid share token.
        MonolithSecurityFilter filter = new MonolithSecurityFilter(
                () -> null,
                List.of(),
                token -> null);
        MockHttpServletRequest request = externalRequest("/api/publications/00000000-0000-0000-0000-000000000123");
        request.addHeader("Authorization", "ShareToken sl_unknown");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Invalid or expired share token");
        assertThat(captured.get()).as("an unresolvable share token must not reach the chain").isNull();
    }

    @Test
    @DisplayName("share token whose resolved context has a null userId is rejected with 401")
    void shareTokenWithNullUserIdIsRejected() throws Exception {
        MonolithSecurityFilter filter = new MonolithSecurityFilter(
                () -> null,
                List.of(),
                token -> new MonolithSecurityFilter.ShareTokenContext(
                        null, "org-789", "APPLICATION", "pub-123", "workflow-456"));
        MockHttpServletRequest request = externalRequest("/api/publications/00000000-0000-0000-0000-000000000123");
        request.addHeader("Authorization", "ShareToken sl_scope");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Invalid or expired share token");
        assertThat(captured.get()).isNull();
    }

    @Test
    @DisplayName("share token whose resolved context has a blank userId is rejected with 401")
    void shareTokenWithBlankUserIdIsRejected() throws Exception {
        MonolithSecurityFilter filter = new MonolithSecurityFilter(
                () -> null,
                List.of(),
                token -> new MonolithSecurityFilter.ShareTokenContext(
                        "   ", "org-789", "APPLICATION", "pub-123", "workflow-456"));
        MockHttpServletRequest request = externalRequest("/api/publications/00000000-0000-0000-0000-000000000123");
        request.addHeader("Authorization", "ShareToken sl_scope");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Invalid or expired share token");
        assertThat(captured.get()).isNull();
    }

    @Test
    @DisplayName("share token with a null organizationId does not inject an X-Organization-ID header")
    void shareTokenWithNullOrganizationIdSkipsOrganizationHeader() throws Exception {
        MonolithSecurityFilter filter = new MonolithSecurityFilter(
                () -> null,
                List.of(),
                token -> new MonolithSecurityFilter.ShareTokenContext(
                        "42", null, "APPLICATION", "pub-123", "workflow-456"));
        MockHttpServletRequest request = externalRequest("/api/publications/00000000-0000-0000-0000-000000000123");
        request.addHeader("Authorization", "ShareToken sl_scope");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(captured.get()).isNotNull();
        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-User-ID")).isEqualTo("42");
        assertThat(forwarded.getHeader("X-Authenticated")).isEqualTo("true");
        assertThat(forwarded.getHeader("X-Organization-ID"))
                .as("a null share organizationId must not be injected as a header")
                .isNull();

        // POSITIVE CONTROL: drive the SAME ShareAuthHeadersRequestWrapper path with a PRESENT,
        // non-blank organizationId and assert X-Organization-ID IS then injected with that value.
        // The null case above is a tautology on its own (Map.get of an absent key is null whether or
        // not addIfPresent skips nulls); the present->injected vs null->absent contrast proves the
        // wrapper injects the header CONDITIONALLY rather than never (or always) setting it.
        MonolithSecurityFilter presentOrgFilter = new MonolithSecurityFilter(
                () -> null,
                List.of(),
                token -> new MonolithSecurityFilter.ShareTokenContext(
                        "42", "org-present", "APPLICATION", "pub-123", "workflow-456"));
        MockHttpServletRequest presentOrgRequest =
                externalRequest("/api/publications/00000000-0000-0000-0000-000000000123");
        presentOrgRequest.addHeader("Authorization", "ShareToken sl_scope");
        MockHttpServletResponse presentOrgResponse = new MockHttpServletResponse();
        AtomicReference<ServletRequest> presentOrgCaptured = new AtomicReference<>();

        presentOrgFilter.doFilter(presentOrgRequest, presentOrgResponse, capturingChain(presentOrgCaptured));

        assertThat(presentOrgCaptured.get()).isNotNull();
        var presentOrgForwarded = (jakarta.servlet.http.HttpServletRequest) presentOrgCaptured.get();
        assertThat(presentOrgForwarded.getHeader("X-Organization-ID"))
                .as("a present share organizationId must be injected as a header")
                .isEqualTo("org-present");
    }

    @Test
    @DisplayName("share token PUT request is rejected with the read-only 403 error")
    void shareTokenPutIsRejectedWithReadOnlyError() throws Exception {
        MonolithSecurityFilter filter = new MonolithSecurityFilter(
                () -> null,
                List.of(),
                token -> new MonolithSecurityFilter.ShareTokenContext(
                        "42", "org-789", "APPLICATION", "pub-123", "workflow-456"));
        MockHttpServletRequest request = externalRequest("/api/v2/workflows/dag/00000000-0000-0000-0000-000000000456");
        request.setMethod("PUT");
        request.addHeader("Authorization", "Bearer ShareToken sl_scope");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("Write operations are not allowed in a shared context");
        assertThat(captured.get()).isNull();
    }

    @Test
    @DisplayName("share token PATCH request is rejected with the read-only 403 error")
    void shareTokenPatchIsRejectedWithReadOnlyError() throws Exception {
        MonolithSecurityFilter filter = new MonolithSecurityFilter(
                () -> null,
                List.of(),
                token -> new MonolithSecurityFilter.ShareTokenContext(
                        "42", "org-789", "APPLICATION", "pub-123", "workflow-456"));
        MockHttpServletRequest request = externalRequest("/api/v2/workflows/dag/00000000-0000-0000-0000-000000000456");
        request.setMethod("PATCH");
        request.addHeader("Authorization", "Bearer ShareToken sl_scope");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("Write operations are not allowed in a shared context");
        assertThat(captured.get()).isNull();
    }

    @Test
    @DisplayName("share token HEAD and OPTIONS requests are forwarded as reads (not 403)")
    void shareTokenHeadAndOptionsAreForwardedAsReads() throws Exception {
        for (String method : List.of("HEAD", "OPTIONS")) {
            MonolithSecurityFilter filter = new MonolithSecurityFilter(
                    () -> null,
                    List.of(),
                    token -> new MonolithSecurityFilter.ShareTokenContext(
                            "42", "org-789", "APPLICATION", "pub-123", "workflow-456"));
            MockHttpServletRequest request =
                    externalRequest("/api/publications/00000000-0000-0000-0000-000000000123");
            request.setMethod(method);
            request.addHeader("Authorization", "Bearer ShareToken sl_scope");
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicReference<ServletRequest> captured = new AtomicReference<>();

            filter.doFilter(request, response, capturingChain(captured));

            assertThat(response.getStatus()).as(method + " share request must not be 403").isNotEqualTo(403);
            assertThat(captured.get()).as(method + " share request must be forwarded as a read").isNotNull();
            var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
            assertThat(forwarded.getHeader("X-Share-Context")).isEqualTo("true");
        }
    }

    @Test
    @DisplayName("rejects an access token whose subject claim is missing")
    void jwtWithMissingSubjectClaimIsRejected() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> keyPair.getPublic(), List.of());
        MockHttpServletRequest request = externalRequest("/api/storage/quota");
        request.addHeader("Authorization", "Bearer " + signedJwt(keyPair, Map.of(
                "userId", 42,
                "roles", List.of("USER"),
                "token_type", "access",
                "exp", Instant.now().plusSeconds(300).getEpochSecond()
        ))); // no "sub" claim
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(captured.get()).as("a subjectless token must not reach the chain").isNull();
    }

    @Test
    @DisplayName("accepts an access token whose exp equals the current second (strict greater-than boundary)")
    void jwtExpiringAtCurrentSecondIsAccepted() throws Exception {
        // The expiry guard is `now/1000 > exp`, so exp == now is still valid. This pins the strict >
        // comparison (a >= guard would wrongly reject this token). A fixed clock is injected so the
        // filter reads exactly the second the token is stamped with, hitting the equality case
        // deterministically. (A real wall clock can roll a second between token-sign and filter-read,
        // which would make exp one second in the past and turn this into a rare second-boundary flake;
        // the injected LongSupplier seam removes that.)
        KeyPair keyPair = generateRsaKeyPair();
        long fixedMillis = 1_700_000_000_000L; // arbitrary fixed instant
        long nowSeconds = fixedMillis / 1000;
        MonolithSecurityFilter filter = new MonolithSecurityFilter(
                () -> keyPair.getPublic(), List.of(), null, () -> fixedMillis);
        MockHttpServletRequest request = externalRequest("/api/storage/quota");
        request.addHeader("Authorization", "Bearer " + signedJwt(keyPair, Map.of(
                "sub", "42",
                "userId", 42,
                "roles", List.of("USER"),
                "token_type", "access",
                "exp", nowSeconds
        )));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(captured.get()).isNotNull();
        assertThat(((jakarta.servlet.http.HttpServletRequest) captured.get()).getHeader("X-User-ID"))
                .isEqualTo("42");
    }

    @Test
    @DisplayName("rejects an access token that expired one second ago (strict greater-than boundary)")
    void jwtExpiredOneSecondAgoIsRejected() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> keyPair.getPublic(), List.of());
        MockHttpServletRequest request = externalRequest("/api/storage/quota");
        long expiredSecond = System.currentTimeMillis() / 1000 - 1;
        request.addHeader("Authorization", "Bearer " + signedJwt(keyPair, Map.of(
                "sub", "42",
                "userId", 42,
                "roles", List.of("USER"),
                "token_type", "access",
                "exp", expiredSecond
        )));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(captured.get()).isNull();
    }

    @Test
    @DisplayName("rejects a structurally valid RS256 JWT whose payload is malformed JSON")
    void malformedJsonPayloadCausesTokenRejection() throws Exception {
        // The 3-part structure, RS256 header and signature are all valid; only the payload JSON is
        // garbage, so parseJson catches the parse exception, returns null, and validation rejects.
        KeyPair keyPair = generateRsaKeyPair();
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> keyPair.getPublic(), List.of());
        String headerSegment = base64Url(MAPPER.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT")));
        String payloadSegment = base64Url("{ not-valid-json ".getBytes(StandardCharsets.UTF_8));
        String signingInput = headerSegment + "." + payloadSegment;
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        String token = signingInput + "." + base64Url(signature.sign());

        MockHttpServletRequest request = externalRequest("/api/storage/quota");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(captured.get()).isNull();
    }

    @Test
    @DisplayName("rejects a JWT when the verification key supplier returns a non-public key")
    void nonPublicVerificationKeyRejectsJwt() throws Exception {
        // The supplier returns the PRIVATE key, which is not a PublicKey instance, so verifySignature
        // returns false and the token is rejected before any claim is trusted.
        KeyPair keyPair = generateRsaKeyPair();
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> keyPair.getPrivate(), List.of());
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
    @DisplayName("alg-confusion token (HS256 header, valid RSA signature) is rejected by the RS256-only header gate")
    void nonRs256AlgorithmJwtIsRejected() throws Exception {
        // alg-confusion token: the header advertises alg=HS256, but the signature is a VALID RSA
        // signature over base64url(header)+"."+base64url(payload), produced with the same test
        // private key whose public key the supplier returns, and every claim is valid (access token,
        // unexpired exp, non-blank sub). The token is therefore rejected SOLELY by the RS256-only
        // header gate that runs BEFORE verifySignature: if that gate were removed, the token would
        // fall through to verifySignature, the RSA signature would genuinely verify, all claims would
        // pass, and the request would be authenticated (200). So this test fails (200, not 401) if the
        // gate is deleted, which makes it mutation-bearing. A fake/literal signature would NOT prove
        // this: removing the gate would still 401 it at the signature check, so the test would pass
        // either way and pin nothing.
        KeyPair keyPair = generateRsaKeyPair();
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> keyPair.getPublic(), List.of());
        String token = signedJwt(keyPair, "HS256", Map.of(
                "sub", "42",
                "userId", 42,
                "roles", List.of("USER"),
                "token_type", "access",
                "exp", Instant.now().plusSeconds(300).getEpochSecond()));

        MockHttpServletRequest request = externalRequest("/api/storage/quota");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus())
                .as("a non-RS256 token must be rejected by the header gate before signature verification")
                .isEqualTo(401);
        assertThat(captured.get())
                .as("the alg-confusion token (despite a valid RSA signature) must not reach the chain")
                .isNull();
    }

    @Test
    @DisplayName("a Bearer header with a whitespace-only token passes through unauthenticated and strips forged identity")
    void bearerWithWhitespaceOnlyTokenPassesThroughUnauthenticated() throws Exception {
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of());
        MockHttpServletRequest request = externalRequest("/api/storage/quota");
        request.addHeader("Authorization", "Bearer    ");
        request.addHeader("X-User-ID", "999"); // forged identity must still be stripped
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus())
                .as("an empty bearer token must be treated as no auth, not 401")
                .isNotEqualTo(401);
        assertThat(captured.get()).isNotNull();
        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-User-ID")).isNull();
    }

    @Test
    @DisplayName("skips malformed membership entries (non-map, missing/blank orgId) without failing JWT validation")
    void malformedMembershipEntriesAreSkippedWithoutCrashing() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> keyPair.getPublic(), List.of());
        MockHttpServletRequest request = externalRequest("/api/schedules");
        request.addHeader("X-Active-Organization-ID", "team-org-uuid");
        request.addHeader("Authorization", "Bearer " + signedJwt(keyPair, Map.of(
                "sub", "42",
                "userId", 42,
                "roles", List.of("USER"),
                "token_type", "access",
                "exp", Instant.now().plusSeconds(300).getEpochSecond(),
                "memberships", List.<Object>of(
                        "not-a-map-entry",
                        Map.of("role", "ADMIN", "personal", false, "paused", false),
                        Map.of("orgId", "", "role", "MEMBER", "personal", false, "paused", false),
                        Map.of("orgId", "team-org-uuid", "role", "ADMIN", "personal", false, "paused", false))
        )));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(captured.get()).isNotNull();
        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-Organization-ID"))
                .as("the only well-formed membership must still resolve despite malformed siblings")
                .isEqualTo("team-org-uuid");
        assertThat(forwarded.getHeader("X-Organization-Role")).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("paused default organization falls back to the personal OWNER organization")
    void pausedDefaultOrganizationFallsBackToPersonalOwnerOrg() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> keyPair.getPublic(), List.of());
        MockHttpServletRequest request = externalRequest("/api/schedules");
        // No X-Active-Organization-ID: resolution falls to the default org, which is paused.
        request.addHeader("Authorization", "Bearer " + signedJwt(keyPair, Map.of(
                "sub", "42",
                "userId", 42,
                "roles", List.of("USER"),
                "token_type", "access",
                "exp", Instant.now().plusSeconds(300).getEpochSecond(),
                "defaultOrganizationId", "default-org-uuid",
                "defaultOrganizationRole", "MEMBER",
                "memberships", List.of(
                        Map.of("orgId", "default-org-uuid", "role", "MEMBER", "personal", false, "paused", true),
                        Map.of("orgId", "personal-org-uuid", "role", "OWNER", "personal", true, "paused", false))
        )));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(captured.get()).isNotNull();
        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-Organization-ID"))
                .as("a paused default org must fall back to the personal OWNER org")
                .isEqualTo("personal-org-uuid");
        assertThat(forwarded.getHeader("X-Organization-Role")).isEqualTo("OWNER");
    }

    @Test
    @DisplayName("no default organization and no memberships injects no organization headers")
    void noDefaultOrgAndNoMembershipInjectsNoOrganizationHeaders() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> keyPair.getPublic(), List.of());
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

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(captured.get()).isNotNull();
        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-User-ID")).isEqualTo("42");
        assertThat(forwarded.getHeader("X-Organization-ID"))
                .as("OrgContext(null, null) must inject no org id header")
                .isNull();
        assertThat(forwarded.getHeader("X-Organization-Role"))
                .as("OrgContext(null, null) must inject no org role header")
                .isNull();

        // POSITIVE CONTROL: drive the SAME AuthHeadersRequestWrapper path with a JWT carrying a
        // (non-paused) default organization, so OrgContext resolves to a non-null org and
        // X-Organization-ID / X-Organization-Role ARE injected. The absent case above is a tautology
        // on its own; the present->injected vs absent->not contrast proves addIfPresent injects the
        // org headers CONDITIONALLY rather than never setting them.
        MockHttpServletRequest withOrgRequest = externalRequest("/api/storage/quota");
        withOrgRequest.addHeader("Authorization", "Bearer " + signedJwt(keyPair, Map.of(
                "sub", "42",
                "userId", 42,
                "roles", List.of("USER"),
                "token_type", "access",
                "exp", Instant.now().plusSeconds(300).getEpochSecond(),
                "defaultOrganizationId", "default-org-uuid",
                "defaultOrganizationRole", "MEMBER")));
        MockHttpServletResponse withOrgResponse = new MockHttpServletResponse();
        AtomicReference<ServletRequest> withOrgCaptured = new AtomicReference<>();

        filter.doFilter(withOrgRequest, withOrgResponse, capturingChain(withOrgCaptured));

        assertThat(withOrgCaptured.get()).isNotNull();
        var withOrgForwarded = (jakarta.servlet.http.HttpServletRequest) withOrgCaptured.get();
        assertThat(withOrgForwarded.getHeader("X-Organization-ID"))
                .as("a present default organization must be injected as a header")
                .isEqualTo("default-org-uuid");
        assertThat(withOrgForwarded.getHeader("X-Organization-Role")).isEqualTo("MEMBER");
    }

    @Test
    @DisplayName("downstream ServletException propagates after RequestContextHolder is restored")
    void downstreamServletExceptionPropagatesAfterRequestContextCleanup() {
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of());
        MockHttpServletRequest request = externalRequest("/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        ServletRequestAttributes previous = new ServletRequestAttributes(new MockHttpServletRequest());
        RequestContextHolder.setRequestAttributes(previous);
        FilterChain throwingChain = (req, res) -> {
            throw new ServletException("downstream boom");
        };
        try {
            assertThatThrownBy(() -> filter.doFilter(request, response, throwingChain))
                    .isInstanceOf(ServletException.class)
                    .hasMessage("downstream boom");
            assertThat(RequestContextHolder.getRequestAttributes())
                    .as("the previous request binding must be restored in the finally block")
                    .isSameAs(previous);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
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
        return signedJwt(keyPair, "RS256", claims);
    }

    /**
     * Builds a JWT whose header advertises {@code alg} but whose signature is ALWAYS a genuine RSA
     * signature (SHA256withRSA) over base64url(header)+"."+base64url(payload), made with the test
     * private key whose public key the supplier returns. Passing an alg other than "RS256" produces
     * an alg-confusion token: a structurally real, RSA-verifiable token whose only defect is the
     * header alg, so it exercises the RS256-only header gate rather than the signature check.
     */
    private static String signedJwt(KeyPair keyPair, String alg, Map<String, Object> claims) throws Exception {
        String header = base64Url(MAPPER.writeValueAsBytes(Map.of("alg", alg, "typ", "JWT")));
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
