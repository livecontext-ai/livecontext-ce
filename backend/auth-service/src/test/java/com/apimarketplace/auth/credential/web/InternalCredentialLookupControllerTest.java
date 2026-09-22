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

    // ===== GET /identities =====

    @Test
    @DisplayName("identities answer with id, name and integration, and never with credential material")
    void identitiesCarryNoSecrets() {
        // This endpoint exists so a caller can work out WHICH credential was meant
        // without receiving the material of the ones it is about to reject. A field
        // added here that can carry secrets defeats its entire reason for existing,
        // so the assertion is on the ABSENCE, not on the presence.
        when(credentialRepository.findAllByTenantId(OWNER))
                .thenReturn(List.of(cred(10L, OWNER, null, "instagram", CredentialStatus.active, true)));

        ResponseEntity<List<InternalCredentialLookupController.CredentialIdentity>> response =
                controller.getCredentialIdentities(OWNER, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        InternalCredentialLookupController.CredentialIdentity identity = response.getBody().get(0);
        assertThat(identity.id()).isEqualTo(10L);
        assertThat(identity.name()).isEqualTo("Cred 10");
        assertThat(identity.integration()).isEqualTo("instagram");
        assertThat(identity.status()).isEqualTo("active");
        // Deciding WHICH account was meant now includes deciding which one can run a
        // given endpoint, so the type, the granted scopes and the default flag are part
        // of the identity. None of them can carry material: a scope is a permission label
        // the user consented to on the provider screen and already sees in their
        // credentials list, and it unlocks nothing by itself.
        assertThat(identity.type()).isEqualTo("OAuth2");
        assertThat(identity.isDefault()).isTrue();
        // The record has exactly seven components; an eighth one carrying material
        // would make this fail rather than ship silently.
        assertThat(InternalCredentialLookupController.CredentialIdentity.class.getRecordComponents())
                .hasSize(7);
        assertThat(InternalCredentialLookupController.CredentialIdentity.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .as("a component that can carry a secret defeats the whole reason this endpoint exists")
                .doesNotContain("credentialData", "credential_data");
    }

    @Test
    @DisplayName("identities carry the granted scopes, which is what decides whether an account can run a given endpoint")
    void identitiesCarryGrantedScopes() {
        // Before this, the only internal listing that held scopes was /all, which answers
        // with decrypted secrets for every row - including the ones the caller is about to
        // reject. Working out which account can run an endpoint had to go through it or
        // not happen at all, and it did not happen at all.
        when(credentialRepository.findAllByTenantId(OWNER)).thenReturn(List.of(
                credWithScopes(20L, OWNER, "gmail",
                        List.of("https://www.googleapis.com/auth/gmail.send"))));

        InternalCredentialLookupController.CredentialIdentity identity =
                controller.getCredentialIdentities(OWNER, null).getBody().get(0);

        assertThat(identity.scopes())
                .containsExactly("https://www.googleapis.com/auth/gmail.send");
    }

    @Test
    @DisplayName("the default flag crosses the wire as is_default, which is the spelling the client reads")
    void defaultFlagKeepsItsWireName() throws Exception {
        // A record component named isDefault serialises as "isDefault" unless it is
        // annotated, and the client field is annotated "is_default". Get that pair wrong
        // and every account deserialises as NOT default - which silently turns every
        // capability answer into the "your default cannot run this" branch.
        when(credentialRepository.findAllByTenantId(OWNER))
                .thenReturn(List.of(cred(30L, OWNER, null, "gmail", CredentialStatus.active, true)));

        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(controller.getCredentialIdentities(OWNER, null).getBody().get(0));

        assertThat(json).contains("\"is_default\":true").doesNotContain("\"isDefault\"");
    }

    @Test
    @DisplayName("a credential with no scopes answers with an empty list, never null")
    void nullScopesBecomeAnEmptyList() {
        // A null would cross the wire as an absent field and read, on the other side, as
        // "this account was granted nothing" - which is what a revoked one looks like.
        when(credentialRepository.findAllByTenantId(OWNER))
                .thenReturn(List.of(credWithScopes(21L, OWNER, "stripe", null)));

        assertThat(controller.getCredentialIdentities(OWNER, null).getBody().get(0).scopes())
                .isNotNull()
                .isEmpty();
    }

    @Test
    @DisplayName("identities follow the same scope rule as the full listing")
    void identitiesAreOrgScopedLikeTheFullListing() {
        // The two must never disagree about which rows exist: a narrower identity
        // listing would refuse a name the picker offered.
        when(credentialRepository.findByOrganizationIdStrict(ORG, 1, 10_000))
                .thenReturn(List.of(cred(11L, OTHER_MEMBER, ORG, "instagram", CredentialStatus.active, false)));

        ResponseEntity<List<InternalCredentialLookupController.CredentialIdentity>> response =
                controller.getCredentialIdentities(OWNER, ORG);

        assertThat(response.getBody()).extracting(InternalCredentialLookupController.CredentialIdentity::id)
                .containsExactly(11L);
        verify(credentialRepository, never()).findAllByTenantId(OWNER);
    }

    private static Credential credWithScopes(Long id, String tenantId, String integration,
                                            List<String> scopes) {
        Instant now = Instant.now();
        return new Credential(
                id, tenantId, null, "Cred " + id, integration,
                CredentialType.OAuth2, CredentialEnvironment.Production,
                CredentialStatus.active, "desc",
                Map.of("access_token", "tok"),
                scopes, List.of(),
                tenantId, "icon", true,
                null, now, now
        );
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
