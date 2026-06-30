package com.apimarketplace.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins {@link OrgContextHeaderForwarder#forward(HttpHeaders)} as the single
 * propagation point for the cross-service trust headers
 * ({@code X-User-ID}, {@code X-Organization-ID}, {@code X-Organization-Role},
 * {@code X-User-Roles}).
 *
 * <p>The bridge-access regression chain (2026-05-21) surfaced that adding
 * {@code X-User-Roles} reading on the receiving controller is necessary but
 * not sufficient - every inter-service hop (orchestrator → agent-service,
 * conversation-service → agent-service, …) routes through *-client builds
 * that all delegate header propagation to this helper. A typo or a missed
 * header here silently breaks the entire admin_only bridge policy enforcement
 * across the platform.
 */
@DisplayName("OrgContextHeaderForwarder.forward - request-bound trust-header propagation")
class OrgContextHeaderForwarderTest {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
        TenantResolver.runWithOrgScope(null, () -> { });
    }

    @Nested
    @DisplayName("X-User-ID forwarding (monolith self-calls depend on this)")
    class UserIdPropagation {

        @Test
        @DisplayName("Forwards X-User-ID from the validated inbound request")
        void forwardsUserIdFromInboundRequest() {
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.addHeader("X-User-ID", "tenant-42");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

            HttpHeaders outbound = new HttpHeaders();
            OrgContextHeaderForwarder.forward(outbound);

            assertThat(outbound.getFirst("X-User-ID")).isEqualTo("tenant-42");
        }

        @Test
        @DisplayName("Does not overwrite an explicitly-set outbound X-User-ID")
        void explicitOutboundUserIdWins() {
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.addHeader("X-User-ID", "tenant-42");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

            HttpHeaders outbound = new HttpHeaders();
            outbound.set("X-User-ID", "tenant-explicit");
            OrgContextHeaderForwarder.forward(outbound);

            assertThat(outbound.getFirst("X-User-ID")).isEqualTo("tenant-explicit");
        }

        @Test
        @DisplayName("Skips a blank inbound X-User-ID instead of forwarding an empty string")
        void blankInboundUserIdIsSkipped() {
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.addHeader("X-User-ID", "   ");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

            HttpHeaders outbound = new HttpHeaders();
            OrgContextHeaderForwarder.forward(outbound);

            assertThat(outbound.containsKey("X-User-ID")).isFalse();
        }

        @Test
        @DisplayName("Outside a request context does not invent X-User-ID")
        void asyncContextHasNoUserIdFallback() {
            HttpHeaders outbound = new HttpHeaders();
            OrgContextHeaderForwarder.forward(outbound);

            assertThat(outbound.containsKey("X-User-ID")).isFalse();
        }
    }

    @Nested
    @DisplayName("X-User-Roles forwarding (admin_only bridge policy depends on this)")
    class UserRolesPropagation {

        @Test
        @DisplayName("Forwards X-User-Roles from inbound request - regression for 2026-05-21 'admin workflow agent denied by admin_only bridge policy' prod symptom")
        void forwardsUserRolesFromInboundRequest() {
            // Pre-fix: forward() copied X-Organization-ID/Role only. An
            // orchestrator handling an inbound admin request and calling
            // agent-service would emit outbound headers WITHOUT X-User-Roles,
            // so the receiving guard saw the default "USER" role and admin_only
            // denied. This test pins the third header into the forwarded set.
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.addHeader("X-User-Roles", "ADMIN,USER");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

            HttpHeaders outbound = new HttpHeaders();
            OrgContextHeaderForwarder.forward(outbound);

            assertThat(outbound.getFirst("X-User-Roles")).isEqualTo("ADMIN,USER");
        }

        @Test
        @DisplayName("Does not overwrite an explicitly-set outbound X-User-Roles")
        void explicitOutboundWins() {
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.addHeader("X-User-Roles", "USER");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

            HttpHeaders outbound = new HttpHeaders();
            outbound.set("X-User-Roles", "ADMIN");
            OrgContextHeaderForwarder.forward(outbound);

            assertThat(outbound.getFirst("X-User-Roles")).isEqualTo("ADMIN");
        }

        @Test
        @DisplayName("Skips a blank inbound X-User-Roles instead of forwarding an empty string")
        void blankInboundIsSkipped() {
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.addHeader("X-User-Roles", "   ");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

            HttpHeaders outbound = new HttpHeaders();
            OrgContextHeaderForwarder.forward(outbound);

            assertThat(outbound.containsKey("X-User-Roles")).isFalse();
        }

        @Test
        @DisplayName("Outside a request context (async / daemon) does NOT default X-User-Roles - privilege-elevation safeguard")
        void asyncContextHasNoUserRolesFallback() {
            // Intentional asymmetry vs the X-Organization-ID/Role async
            // fallback below: granting ADMIN from a daemon ThreadLocal would
            // be a privilege-elevation surface. Async hops default to USER
            // downstream via HttpBridgeAccessClient's blank→USER fallback.
            HttpHeaders outbound = new HttpHeaders();
            OrgContextHeaderForwarder.forward(outbound);

            assertThat(outbound.containsKey("X-User-Roles")).isFalse();
        }
    }

    @Nested
    @DisplayName("Existing org-header behaviour (regression guard for pre-existing contract)")
    class OrgHeadersPropagation {

        @Test
        @DisplayName("Forwards user, org and role headers in one pass")
        void forwardsAllTrustHeaders() {
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.addHeader("X-User-ID", "tenant-42");
            req.addHeader("X-Organization-ID", "org-abc");
            req.addHeader("X-Organization-Role", "ADMIN");
            req.addHeader("X-User-Roles", "ADMIN,USER");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

            HttpHeaders outbound = new HttpHeaders();
            OrgContextHeaderForwarder.forward(outbound);

            assertThat(outbound.getFirst("X-User-ID")).isEqualTo("tenant-42");
            assertThat(outbound.getFirst("X-Organization-ID")).isEqualTo("org-abc");
            assertThat(outbound.getFirst("X-Organization-Role")).isEqualTo("ADMIN");
            assertThat(outbound.getFirst("X-User-Roles")).isEqualTo("ADMIN,USER");
        }
    }

    /** Sanity check that the reflective `getHeader` path doesn't trip on a non-Spring servlet impl. */
    @Test
    @DisplayName("Tolerates a plain HttpServletRequest mock - reflection contract")
    void reflectionWorksOnMockitoMock() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-User-ID")).thenReturn("tenant-42");
        when(req.getHeader("X-User-Roles")).thenReturn("ADMIN");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        HttpHeaders outbound = new HttpHeaders();
        OrgContextHeaderForwarder.forward(outbound);

        assertThat(outbound.getFirst("X-User-ID")).isEqualTo("tenant-42");
        assertThat(outbound.getFirst("X-User-Roles")).isEqualTo("ADMIN");
    }
}
