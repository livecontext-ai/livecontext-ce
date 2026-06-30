package com.apimarketplace.auth.credential.web;

import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.AuthType;
import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.CreatePlatformCredentialRequest;
import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredential;
import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredentialResponse;
import com.apimarketplace.auth.credential.service.CredentialService;
import com.apimarketplace.auth.credential.service.PlatformCredentialPricingService;
import com.apimarketplace.auth.credential.service.PlatformCredentialService;
import com.apimarketplace.auth.credential.service.TooManyByokAppsException;
import com.apimarketplace.auth.credential.web.dto.MyOAuthAppDto;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers the {@code /api/platform-credentials/my} family - the user-facing
 * (non-admin) endpoints for managing tenant-scoped BYOK rows.
 *
 * <p>Three contracts under test:
 * <ol>
 *   <li>{@code GET /my} requires {@code X-Authenticated: true} and a non-blank
 *       {@code X-User-ID}; otherwise 401/400 respectively. The actual
 *       tenant-isolation guarantee (no {@code tenant_id IS NULL} row leaks)
 *       is enforced by the SQL {@code WHERE tenant_id = ?} in
 *       {@code PlatformCredentialRepository.findOwnedByTenant} - see the
 *       Postgres IT for that regression guard. The controller layers
 *       header-level defense-in-depth on top.</li>
 *   <li>{@code GET /my} maps each {@link PlatformCredential} through
 *       {@link MyOAuthAppDto#from} so the response shape is the explicit
 *       allowlist guarded by {@code MyOAuthAppDtoLeakTest}.</li>
 *   <li>{@code POST /my} surfaces {@link TooManyByokAppsException} as HTTP 409
 *       with a stable error code so the frontend can react deterministically.</li>
 * </ol>
 *
 * <p>V362: the /my endpoints thread the active workspace (resolved by
 * {@link TenantResolver#resolveOrgId} from the gateway-injected
 * {@code X-Organization-ID}) into the service so BYOK rows are workspace-scoped.
 * These tests pin that the controller passes the resolved org through verbatim
 * (workspace value or null for personal scope).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlatformCredentialsController - my endpoints")
class PlatformCredentialsControllerMyEndpointsTest {

    @Mock
    private PlatformCredentialService service;

    @Mock
    private PlatformCredentialPricingService pricingService;

    @Mock
    private CredentialService credentialService;

    @Mock
    private TenantResolver tenantResolver;

    @Mock
    private HttpServletRequest httpRequest;

    @InjectMocks
    private PlatformCredentialsController controller;

    private static final String TENANT = "tenant-abc";
    private static final String ORG = "org-xyz";

    @Test
    @DisplayName("GET /my returns 401 when X-Authenticated header is absent - defense in depth even though gateway should always inject it")
    void listMy_missingAuthenticatedHeaderReturns401() {
        ResponseEntity<?> response = controller.listMy(httpRequest, null, TENANT);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("GET /my returns 401 when X-Authenticated is anything other than 'true'")
    void listMy_falsyAuthenticatedHeaderReturns401() {
        ResponseEntity<?> response = controller.listMy(httpRequest, "false", TENANT);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("GET /my returns 400 when X-User-ID is blank - must not query with an empty tenant id")
    void listMy_blankTenantReturns400() {
        ResponseEntity<?> response = controller.listMy(httpRequest, "true", "  ");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("GET /my returns the tenant's BYOK rows for the active workspace, mapped through MyOAuthAppDto allowlist")
    @SuppressWarnings("unchecked")
    void listMy_returnsDtoList() {
        PlatformCredential row = buildCredential(42L, "gmail", TENANT, "client-id-1234567890", ORG);
        when(tenantResolver.resolveOrgId(httpRequest)).thenReturn(ORG);
        when(service.findOwnedByTenant(TENANT, ORG)).thenReturn(List.of(row));

        ResponseEntity<?> response = controller.listMy(httpRequest, "true", TENANT);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        List<MyOAuthAppDto> body = (List<MyOAuthAppDto>) response.getBody();
        assertThat(body).hasSize(1);
        MyOAuthAppDto dto = body.get(0);
        assertThat(dto.id()).isEqualTo(42L);
        assertThat(dto.integrationName()).isEqualTo("gmail");
        assertThat(dto.clientIdMasked()).matches("^.{4}\\*{4}.{4}$");
        assertThat(dto.hasClientSecret()).isTrue();
        assertThat(dto.organizationId()).isEqualTo(ORG);
    }

    @Test
    @DisplayName("GET /my scopes the listing to the resolved active workspace - never the tenant-wide overload")
    void listMy_scopesToActiveWorkspace() {
        when(tenantResolver.resolveOrgId(httpRequest)).thenReturn(ORG);
        when(service.findOwnedByTenant(TENANT, ORG)).thenReturn(List.of());

        controller.listMy(httpRequest, "true", TENANT);

        verify(service).findOwnedByTenant(TENANT, ORG);
        verify(service, never()).findOwnedByTenant(any());
    }

    @Test
    @DisplayName("GET /my in personal scope (no active org) lists the tenant's personal rows (org=null)")
    void listMy_personalScopeWhenNoActiveOrg() {
        when(tenantResolver.resolveOrgId(httpRequest)).thenReturn(null);
        when(service.findOwnedByTenant(TENANT, null)).thenReturn(List.of());

        controller.listMy(httpRequest, "true", TENANT);

        verify(service).findOwnedByTenant(TENANT, null);
    }

    @Test
    @DisplayName("GET /my returns empty list when service has no rows for this tenant")
    @SuppressWarnings("unchecked")
    void listMy_emptyWhenNoRows() {
        when(tenantResolver.resolveOrgId(httpRequest)).thenReturn(ORG);
        when(service.findOwnedByTenant(TENANT, ORG)).thenReturn(List.of());

        ResponseEntity<?> response = controller.listMy(httpRequest, "true", TENANT);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat((List<MyOAuthAppDto>) response.getBody()).isEmpty();
    }

    @Test
    @DisplayName("POST /my returns 409 with stable error code when service hits the per-tenant cap")
    @SuppressWarnings("unchecked")
    void saveMy_tooManyByokAppsReturns409() {
        CreatePlatformCredentialRequest request = new CreatePlatformCredentialRequest(
                "newapi", "New API", "oauth2",
                "cid", "csec", null, null, null,
                null, null, null, null, null, null, null, null, null);
        when(tenantResolver.resolveOrgId(httpRequest)).thenReturn(ORG);
        when(service.saveCredential(eq(request), eq(TENANT), eq(ORG)))
                .thenThrow(new TooManyByokAppsException(50));

        ResponseEntity<?> response = controller.saveMy(httpRequest, TENANT, request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("error")).isEqualTo("too_many_byok_apps");
        assertThat(body.get("maxAllowed")).isEqualTo(50);
        assertThat((String) body.get("message")).contains("50").contains("custom OAuth connections");
    }

    @Test
    @DisplayName("POST /my returns tenant-facing DTO and does not expose admin-only fields")
    void saveMy_returnsTenantFacingDto() {
        CreatePlatformCredentialRequest request = new CreatePlatformCredentialRequest(
                "newapi", "New API", "api_key",
                null, null, "api-key", null, null,
                null, null, null, "newapi", "category", null, null, null, null);
        PlatformCredentialResponse saved = PlatformCredentialResponse.from(
                new PlatformCredential(
                        42L, "newapi", "New API", AuthType.API_KEY,
                        null, null, "api-key", null, null,
                        null, null, null, "newapi", "category", null, true,
                        Map.of(), BigDecimal.ZERO, 500,
                        Instant.parse("2026-01-02T03:04:05Z"),
                        Instant.parse("2026-01-02T04:05:06Z"),
                        null, TENANT, "primary"
                ).withOrganizationId(ORG)
        );
        when(tenantResolver.resolveOrgId(httpRequest)).thenReturn(ORG);
        when(service.saveCredential(eq(request), eq(TENANT), eq(ORG))).thenReturn(saved);

        ResponseEntity<?> response = controller.saveMy(httpRequest, TENANT, request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        MyOAuthAppDto body = (MyOAuthAppDto) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.integrationName()).isEqualTo("newapi");
        assertThat(body.clientIdMasked()).isNull();
        assertThat(body.hasApiKey()).isTrue();
        assertThat(body.organizationId()).isEqualTo(ORG);
    }

    @Test
    @DisplayName("POST /my tags the new row with the active workspace resolved from the request")
    void saveMy_tagsActiveWorkspace() {
        CreatePlatformCredentialRequest request = new CreatePlatformCredentialRequest(
                "newapi", "New API", "api_key",
                null, null, "api-key", null, null,
                null, null, null, "newapi", "category", null, null, null, null);
        when(tenantResolver.resolveOrgId(httpRequest)).thenReturn(ORG);
        when(service.saveCredential(eq(request), eq(TENANT), eq(ORG)))
                .thenReturn(PlatformCredentialResponse.from(buildCredential(1L, "newapi", TENANT, null, ORG)));

        controller.saveMy(httpRequest, TENANT, request);

        verify(service).saveCredential(request, TENANT, ORG);
        verify(service, never()).saveCredential(any(), any());
    }

    @Test
    @DisplayName("POST /my in personal scope (no active org) saves with org=null")
    void saveMy_personalScopeWhenNoActiveOrg() {
        CreatePlatformCredentialRequest request = new CreatePlatformCredentialRequest(
                "newapi", "New API", "api_key",
                null, null, "api-key", null, null,
                null, null, null, "newapi", "category", null, null, null, null);
        when(tenantResolver.resolveOrgId(httpRequest)).thenReturn(null);
        when(service.saveCredential(eq(request), eq(TENANT), eq(null)))
                .thenReturn(PlatformCredentialResponse.from(buildCredential(1L, "newapi", TENANT, null, null)));

        controller.saveMy(httpRequest, TENANT, request);

        verify(service).saveCredential(request, TENANT, null);
    }

    @Test
    @DisplayName("POST /my returns 400 with no service call when integrationName is blank")
    void saveMy_blankIntegrationNameReturns400() {
        CreatePlatformCredentialRequest request = new CreatePlatformCredentialRequest(
                "", "", "oauth2",
                null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);

        ResponseEntity<?> response = controller.saveMy(httpRequest, TENANT, request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(service, never()).saveCredential(any(), any(), any());
    }

    @Test
    @DisplayName("POST /my returns 400 with no service call when X-User-ID is blank")
    void saveMy_blankTenantReturns400() {
        CreatePlatformCredentialRequest request = new CreatePlatformCredentialRequest(
                "newapi", "New API", "oauth2",
                "cid", "csec", null, null, null,
                null, null, null, null, null, null, null, null, null);

        ResponseEntity<?> response = controller.saveMy(httpRequest, "  ", request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(service, never()).saveCredential(any(), any(), any());
    }

    // ========== delete-impact + cascade DELETE (Phase 2) ==========

    @Test
    @DisplayName("GET /my/{name}/delete-impact returns 401 when X-Authenticated is missing")
    void deleteImpact_unauthenticatedReturns401() {
        ResponseEntity<?> response = controller.deleteImpact(null, TENANT, "gmail");
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(credentialService);
    }

    @Test
    @DisplayName("GET /my/{name}/delete-impact returns 400 when X-User-ID is blank")
    void deleteImpact_blankTenantReturns400() {
        ResponseEntity<?> response = controller.deleteImpact("true", "", "gmail");
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(credentialService);
    }

    @Test
    @DisplayName("GET /my/{name}/delete-impact returns affected count and untruncated flag for normal tenants")
    @SuppressWarnings("unchecked")
    void deleteImpact_returnsAffectedCount() {
        when(credentialService.countDependentForByokDelete(TENANT, "gmail")).thenReturn(3);

        ResponseEntity<?> response = controller.deleteImpact("true", TENANT, "gmail");

        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body.get("integrationName")).isEqualTo("gmail");
        assertThat(body.get("affectedCredentialCount")).isEqualTo(3);
        assertThat(body.get("truncated")).isEqualTo(false);
    }

    @Test
    @DisplayName("GET /my/{name}/delete-impact caps the displayed count at 999 with truncated=true so a precise tenant-size signal cannot be fingerprinted")
    @SuppressWarnings("unchecked")
    void deleteImpact_truncatesAt999() {
        when(credentialService.countDependentForByokDelete(TENANT, "gmail")).thenReturn(1500);

        ResponseEntity<?> response = controller.deleteImpact("true", TENANT, "gmail");

        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body.get("affectedCredentialCount")).isEqualTo(999);
        assertThat(body.get("truncated")).isEqualTo(true);
    }

    @Test
    @DisplayName("DELETE /my/{name} runs cascade-revoke BEFORE deleting the BYOK row - order matters so a partial failure leaves BYOK intact for retry")
    @SuppressWarnings("unchecked")
    void deleteMy_cascadeOrderIsRevokeThenDelete() {
        when(tenantResolver.resolveOrgId(httpRequest)).thenReturn(ORG);
        when(credentialService.revokeForByokDelete(TENANT, "gmail")).thenReturn(2);
        when(service.deleteCredential("gmail", TENANT, ORG)).thenReturn(true);

        ResponseEntity<?> response = controller.deleteMy(httpRequest, "true", TENANT, "gmail");

        // Order assertion: revokeForByokDelete must run STRICTLY BEFORE deleteCredential.
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(credentialService, service);
        inOrder.verify(credentialService).revokeForByokDelete(TENANT, "gmail");
        inOrder.verify(service).deleteCredential("gmail", TENANT, ORG);

        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body.get("deleted")).isEqualTo(true);
        assertThat(body.get("revokedCredentialCount")).isEqualTo(2);
        assertThat(body.get("integrationName")).isEqualTo("gmail");
    }

    @Test
    @DisplayName("DELETE /my/{name} scopes the row removal to the active workspace")
    void deleteMy_scopesDeleteToActiveWorkspace() {
        when(tenantResolver.resolveOrgId(httpRequest)).thenReturn(ORG);
        when(credentialService.revokeForByokDelete(TENANT, "gmail")).thenReturn(0);
        when(service.deleteCredential("gmail", TENANT, ORG)).thenReturn(true);

        controller.deleteMy(httpRequest, "true", TENANT, "gmail");

        verify(service).deleteCredential("gmail", TENANT, ORG);
        verify(service, never()).deleteCredential(any(), any());
    }

    @Test
    @DisplayName("DELETE /my/{name} returns 401 when X-Authenticated is missing")
    void deleteMy_unauthenticatedReturns401() {
        ResponseEntity<?> response = controller.deleteMy(httpRequest, null, TENANT, "gmail");
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(credentialService);
        verify(service, never()).deleteCredential(any(), any(), any());
    }

    private PlatformCredential buildCredential(Long id, String integrationName,
                                                String tenantId, String clientId, String organizationId) {
        return new PlatformCredential(
                id, integrationName, "Display " + integrationName, AuthType.OAUTH2,
                clientId, clientId == null ? null : "client-secret-encrypted", null, null, null,
                "https://auth.url", "https://token.url", "scope",
                integrationName, "category", "Description", true,
                Map.of(), BigDecimal.ZERO, 500,
                Instant.parse("2026-01-02T03:04:05Z"),
                Instant.parse("2026-01-02T04:05:06Z"),
                "alice", tenantId, "primary"
        ).withOrganizationId(organizationId);
    }
}
