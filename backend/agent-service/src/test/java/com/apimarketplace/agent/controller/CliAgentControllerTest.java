package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.dto.cli.CliSessionResponse;
import com.apimarketplace.agent.dto.cli.CliSessionStartRequest;
import com.apimarketplace.agent.service.cli.CliAgentService;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CliAgentController#startSession} role resolution.
 *
 * <p>The agent-cli MCP stdio bridge bypasses the gateway, so platform roles must
 * be resolved server-side from the authoritative store (AuthClient.getUserRoles)
 * - NEVER from a spoofable X-User-Roles request header - and threaded into the
 * session so admin-gated tools (e.g. editing a GLOBAL skill) work on this path.
 */
@DisplayName("CliAgentController.startSession role resolution")
class CliAgentControllerTest {

    private CliAgentService cliAgentService;
    private TenantResolver tenantResolver;
    private AuthClient authClient;
    private CliAgentController controller;

    @BeforeEach
    void setUp() {
        cliAgentService = mock(CliAgentService.class);
        tenantResolver = mock(TenantResolver.class);
        authClient = mock(AuthClient.class);
        controller = new CliAgentController(cliAgentService, tenantResolver, authClient);

        when(cliAgentService.startSession(any(), any(), any(), any(), any()))
                .thenReturn(new CliSessionResponse("sess-1", "prompt", List.of(), List.of()));
    }

    @Test
    @DisplayName("resolves roles from AuthClient (authoritative store) and threads them to the service")
    void resolvesRolesFromAuthClientAndThreadsThem() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(tenantResolver.resolve(req)).thenReturn("user-42");
        when(tenantResolver.resolveOrgId(req)).thenReturn("org-1");
        when(tenantResolver.resolveOrgRole(req)).thenReturn("OWNER");
        when(authClient.getUserRoles("user-42")).thenReturn("USER,ADMIN");

        CliSessionStartRequest request = new CliSessionStartRequest(
                null, null, "test-model", null, null, null, null, null, null, null);

        controller.startSession(request, req);

        verify(authClient).getUserRoles("user-42");
        verify(cliAgentService).startSession(eq(request), eq("user-42"), eq("org-1"),
                eq("OWNER"), eq("USER,ADMIN"));
    }

    @Test
    @DisplayName("never reads the spoofable X-User-Roles header for role resolution")
    void neverReadsUserRolesHeader() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(tenantResolver.resolve(req)).thenReturn("user-42");
        when(tenantResolver.resolveOrgId(req)).thenReturn("org-1");
        when(tenantResolver.resolveOrgRole(req)).thenReturn("OWNER");
        when(authClient.getUserRoles("user-42")).thenReturn("USER");

        controller.startSession(null, req);

        verify(req, never()).getHeader("X-User-Roles");
        verify(cliAgentService).startSession(any(), eq("user-42"), eq("org-1"), eq("OWNER"), eq("USER"));
    }

    @Test
    @DisplayName("falls back to default-personal org when no X-Organization-ID is present (bridge path)")
    void fallsBackToDefaultOrgWhenHeaderAbsent() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(tenantResolver.resolve(req)).thenReturn("user-42");
        when(tenantResolver.resolveOrgId(req)).thenReturn(null);
        when(authClient.getDefaultOrganizationIdForUser("user-42")).thenReturn("org-default");
        when(tenantResolver.resolveOrgRole(req)).thenReturn(null);
        when(authClient.getUserRoles("user-42")).thenReturn("USER");

        controller.startSession(null, req);

        verify(cliAgentService).startSession(any(), eq("user-42"), eq("org-default"), eq(null), eq("USER"));
    }
}
