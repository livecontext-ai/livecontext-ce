package com.apimarketplace.auth.credential.web;

import com.apimarketplace.auth.credential.domain.CredentialModels.Credential;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialEnvironment;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialStatus;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialType;
import com.apimarketplace.auth.credential.repository.CredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("InternalCredentialLookupController - org-aware credential lookup")
class InternalCredentialLookupControllerTest {

    @Mock
    private CredentialRepository credentialRepository;

    private InternalCredentialLookupController controller;

    private static final String OWNER = "5";
    private static final String OTHER_MEMBER = "1";
    private static final String ORG = "org-1";

    @BeforeEach
    void setUp() {
        controller = new InternalCredentialLookupController(credentialRepository);
    }

    // ===== GET /{id} =====

    @Test
    @DisplayName("getById returns the credential when the caller owns it")
    void getById_ownerMatch() {
        Credential cred = cred(242L, OWNER, ORG, "twitter", CredentialStatus.active, true);
        when(credentialRepository.findById(242L)).thenReturn(Optional.of(cred));

        ResponseEntity<Credential> resp = controller.getCredentialById(242L, OWNER, ORG);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEqualTo(cred);
    }

    @Test
    @DisplayName("getById returns a workspace credential owned by another member of the same org")
    void getById_orgMatchDifferentOwner() {
        // The credential is owned by member 5 but the caller is member 1 in the same workspace.
        Credential cred = cred(242L, OWNER, ORG, "twitter", CredentialStatus.active, true);
        when(credentialRepository.findById(242L)).thenReturn(Optional.of(cred));

        ResponseEntity<Credential> resp = controller.getCredentialById(242L, OTHER_MEMBER, ORG);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEqualTo(cred);
    }

    @Test
    @DisplayName("getById returns 404 when the caller neither owns the row nor shares its workspace")
    void getById_noMatch() {
        Credential cred = cred(242L, OWNER, "other-org", "twitter", CredentialStatus.active, true);
        when(credentialRepository.findById(242L)).thenReturn(Optional.of(cred));

        ResponseEntity<Credential> resp = controller.getCredentialById(242L, OTHER_MEMBER, ORG);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ===== GET /default =====

    @Test
    @DisplayName("getDefault returns the caller's own default without consulting the org fallback")
    void getDefault_ownDefaultWins() {
        Credential own = cred(10L, OTHER_MEMBER, ORG, "twitter", CredentialStatus.active, true);
        when(credentialRepository.findDefaultByTenantIdAndIntegration(OTHER_MEMBER, "twitter"))
                .thenReturn(Optional.of(own));

        ResponseEntity<Credential> resp = controller.getDefaultCredential(OTHER_MEMBER, "twitter", ORG);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEqualTo(own);
        verify(credentialRepository, never()).findByScopeAndIntegration(OTHER_MEMBER, ORG, "twitter");
    }

    @Test
    @DisplayName("getDefault falls back to the workspace-shared credential when the caller has no own default")
    void getDefault_orgFallback() {
        when(credentialRepository.findDefaultByTenantIdAndIntegration(OTHER_MEMBER, "twitter"))
                .thenReturn(Optional.empty());
        Credential shared = cred(242L, OWNER, ORG, "twitter", CredentialStatus.active, true);
        when(credentialRepository.findByScopeAndIntegration(OTHER_MEMBER, ORG, "twitter"))
                .thenReturn(List.of(shared));

        ResponseEntity<Credential> resp = controller.getDefaultCredential(OTHER_MEMBER, "twitter", ORG);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEqualTo(shared);
    }

    @Test
    @DisplayName("getDefault org fallback skips inactive workspace credentials")
    void getDefault_orgFallbackSkipsInactive() {
        when(credentialRepository.findDefaultByTenantIdAndIntegration(OTHER_MEMBER, "twitter"))
                .thenReturn(Optional.empty());
        Credential inactive = cred(242L, OWNER, ORG, "twitter", CredentialStatus.needs_reauth, true);
        when(credentialRepository.findByScopeAndIntegration(OTHER_MEMBER, ORG, "twitter"))
                .thenReturn(List.of(inactive));

        ResponseEntity<Credential> resp = controller.getDefaultCredential(OTHER_MEMBER, "twitter", ORG);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("getDefault returns 404 with no own default and no org context")
    void getDefault_noneNoOrg() {
        when(credentialRepository.findDefaultByTenantIdAndIntegration(OTHER_MEMBER, "twitter"))
                .thenReturn(Optional.empty());

        ResponseEntity<Credential> resp = controller.getDefaultCredential(OTHER_MEMBER, "twitter", null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(credentialRepository, never()).findByScopeAndIntegration(OTHER_MEMBER, null, "twitter");
    }

    // ===== GET /all =====

    @Test
    @DisplayName("getAll returns every workspace credential when an organization is supplied")
    void getAll_orgScope() {
        Credential a = cred(1L, OWNER, ORG, "twitter", CredentialStatus.active, true);
        Credential b = cred(2L, OTHER_MEMBER, ORG, "gmail", CredentialStatus.active, false);
        when(credentialRepository.findByOrganizationIdStrict(ORG, 1, 10_000))
                .thenReturn(List.of(a, b));

        ResponseEntity<List<Credential>> resp = controller.getAllCredentials(OTHER_MEMBER, ORG);

        assertThat(resp.getBody()).containsExactly(a, b);
        verify(credentialRepository, never()).findAllByTenantId(OTHER_MEMBER);
    }

    @Test
    @DisplayName("getAll falls back to tenant scope when no organization is supplied")
    void getAll_tenantFallback() {
        Credential a = cred(1L, OTHER_MEMBER, ORG, "twitter", CredentialStatus.active, true);
        when(credentialRepository.findAllByTenantId(OTHER_MEMBER)).thenReturn(List.of(a));

        ResponseEntity<List<Credential>> resp = controller.getAllCredentials(OTHER_MEMBER, null);

        assertThat(resp.getBody()).containsExactly(a);
        verify(credentialRepository, never()).findByOrganizationIdStrict(ORG, 1, 10_000);
    }

    private static Credential cred(Long id, String tenantId, String orgId, String integration,
                                   CredentialStatus status, boolean isDefault) {
        Instant now = Instant.now();
        return new Credential(
                id, tenantId, orgId, "Cred " + id, integration,
                CredentialType.OAuth2, CredentialEnvironment.Production,
                status, "desc",
                Map.of("access_token", "tok"),
                List.of(), List.of(),
                tenantId, "icon", isDefault,
                null, now, now
        );
    }
}
