package com.apimarketplace.auth.credential.web;

import com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2InitiateRequest;
import com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2SimpleInitiateRequest;
import com.apimarketplace.auth.credential.service.CredentialService;
import com.apimarketplace.auth.credential.service.InternalCredentialService;
import com.apimarketplace.auth.credential.service.OAuth2Service;
import com.apimarketplace.auth.credential.util.RequestParameterExtractor;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Regression for the credential write gap: every credential mutation (create, rename,
 * LLM key routing, delete, set / clear the default) and both OAuth2 initiations were
 * scoped to the active workspace but never checked the ROLE, so a read-only VIEWER could
 * add, delete or swap the credentials every workflow of the workspace runs with.
 */
@DisplayName("Credential and OAuth2 writes refuse the workspace VIEWER role")
class CredentialWriteViewerGateTest {

    private static final String TENANT = "42";
    private static final String ORG = "org-c";

    private CredentialService credentialService;
    private OAuth2Service oAuth2Service;
    private TenantResolver tenantResolver;
    private HttpServletRequest request;
    private CredentialController credentialController;
    private OAuth2Controller oAuth2Controller;

    @BeforeEach
    void setUp() {
        credentialService = mock(CredentialService.class);
        oAuth2Service = mock(OAuth2Service.class);
        tenantResolver = mock(TenantResolver.class);
        request = mock(HttpServletRequest.class);
        credentialController = new CredentialController(credentialService, tenantResolver,
                mock(RequestParameterExtractor.class));
        oAuth2Controller = new OAuth2Controller(oAuth2Service, tenantResolver, mock(InternalCredentialService.class));
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT);
        when(tenantResolver.resolve(request)).thenReturn(TENANT);
        when(tenantResolver.resolveOrgId(request)).thenReturn(ORG);
    }

    private void role(String role) {
        when(tenantResolver.resolveOrgRole(request)).thenReturn(role);
    }

    private static void assertForbidden(ResponseEntity<?> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    @DisplayName("create, rename and llm-mode by a VIEWER are 403 with a machine-readable code")
    void viewerBodyWritesRefused() {
        role("VIEWER");

        ResponseEntity<?> create = credentialController.createCredential(request, Map.of("name", "k"));
        assertForbidden(create);
        assertThat(create.getBody()).isEqualTo(Map.of(
                "error", "Viewers cannot modify workspace credentials", "code", "org_role_read_only"));
        assertForbidden(credentialController.renameCredential(7L, request, Map.of("name", "k2")));
        assertForbidden(credentialController.setLlmKeyMode(7L, request, Map.of("mode", "proxy")));
        verifyNoInteractions(credentialService);
    }

    @Test
    @DisplayName("delete, set-default and clear-default by a VIEWER (any case) are 403")
    void viewerIdWritesRefused() {
        role(" viewer ");

        assertForbidden(credentialController.deleteCredential(7L, request, null));
        assertForbidden(credentialController.setAsDefault(7L, request, null));
        assertForbidden(credentialController.clearDefault(7L, request, null));
        verifyNoInteractions(credentialService);
    }

    @Test
    @DisplayName("both OAuth2 initiations by a VIEWER are 403 before any provider round-trip")
    void viewerOAuthRefused() {
        role("VIEWER");

        assertForbidden(oAuth2Controller.initiate(request, null, mock(OAuth2InitiateRequest.class)));
        assertForbidden(oAuth2Controller.initiateSimple(request, null, mock(OAuth2SimpleInitiateRequest.class)));
        verifyNoInteractions(oAuth2Service);
    }

    @Test
    @DisplayName("a MEMBER still deletes and swaps defaults: the gate is a no-op for writers")
    void memberWritesProceed() {
        role("MEMBER");
        when(credentialService.deleteCredentialForScope(7L, TENANT, ORG)).thenReturn(true);

        assertThat(credentialController.deleteCredential(7L, request, null).getStatusCode().value()).isEqualTo(204);
        assertThat(credentialController.setAsDefault(7L, request, null).getStatusCode().value()).isEqualTo(200);
        verify(credentialService).deleteCredentialForScope(7L, TENANT, ORG);
        verify(credentialService).setAsDefault(TENANT, ORG, 7L);
    }

    @Test
    @DisplayName("a VIEWER role without a workspace is not a workspace role: delete proceeds")
    void viewerWithoutOrgProceeds() {
        when(tenantResolver.resolveOrgId(request)).thenReturn(null);
        role("VIEWER");
        when(credentialService.deleteCredentialForScope(7L, TENANT, null)).thenReturn(true);

        assertThat(credentialController.deleteCredential(7L, request, null).getStatusCode().value()).isEqualTo(204);
    }

    @Test
    @DisplayName("reads stay open to a VIEWER")
    void viewerReadsProceed() {
        role("VIEWER");
        when(credentialService.getAllCredentialsForScope(TENANT, ORG)).thenReturn(java.util.List.of());

        assertThat(credentialController.getAllCredentials(request, null).getStatusCode().value()).isEqualTo(200);
    }
}
