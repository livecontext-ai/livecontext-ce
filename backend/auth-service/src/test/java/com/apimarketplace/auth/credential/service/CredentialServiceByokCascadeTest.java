package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.CredentialModels.Credential;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialEnvironment;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialStatus;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialType;
import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.AuthType;
import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredential;
import com.apimarketplace.auth.credential.repository.CredentialRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Deleting a BYOK OAuth client revokes only the credentials it issued and that nothing else can
 * keep refreshing.
 *
 * <p>Prod, 2026-09-23: a TikTok sandbox BYOK key was deleted and re-saved, and the cascade erased
 * the tokens of four production TikTok accounts connected through the platform-shared app,
 * because it revoked every active credential of the integration. The rule is integration-agnostic:
 * the issuer is the {@code client_id} every OAuth callback stores and every refresh reads back.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CredentialService - BYOK delete cascade revokes only what the deleted client orphans")
class CredentialServiceByokCascadeTest {

    private static final String TENANT = "1";
    private static final String ORG_A = "org-a";
    private static final String ORG_B = "org-b";
    private static final String SANDBOX = "sandbox-client";
    private static final String PLATFORM = "platform-client";

    @Mock private CredentialRepository credentialRepository;
    @Mock private StringRedisTemplate redisTemplate;
    @InjectMocks private CredentialService service;

    private static PlatformCredential row(long id, String integration, String tenantId, String orgId, String clientId) {
        return new PlatformCredential(id, integration, integration, AuthType.OAUTH2,
                clientId, "secret", null, null, null, "https://a", "https://t", null,
                integration, null, null, true, true, Map.of(), BigDecimal.ZERO, 0,
                Instant.now(), Instant.now(), null, tenantId, "primary", orgId);
    }

    private static final PlatformCredential DELETED = row(181, "tiktok", TENANT, ORG_A, SANDBOX);

    private static Credential credential(long id, String integration, String orgId, Map<String, Object> data) {
        return new Credential(id, TENANT, orgId, "cred-" + id, integration,
                CredentialType.OAuth2, CredentialEnvironment.Production, CredentialStatus.active,
                null, new HashMap<>(data), List.of(), List.of(), null, null, false,
                null, Instant.now(), Instant.now());
    }

    /** What an OAuth callback stores: the issuing client, its secret copy and the tokens. */
    private static Map<String, Object> issuedWithSecret(String clientId) {
        return Map.of("client_id", clientId, "oauth_client_id", clientId,
                "oauth_client_secret", "ENC:s", "access_token", "at", "refresh_token", "rt");
    }

    private static Map<String, Object> issuedWithoutSecret(String clientId) {
        return Map.of("client_id", clientId, "access_token", "at", "refresh_token", "rt");
    }

    private void candidates(Credential... creds) {
        when(credentialRepository.findActiveByTenantIdAndIssuerOrLegacy(TENANT, SANDBOX))
                .thenReturn(List.of(creds));
    }

    private List<Long> dependents(List<PlatformCredential> remaining) {
        return service.findByokDependents(TENANT, DELETED, remaining).stream().map(Credential::id).toList();
    }

    @Test
    @DisplayName("regression: the issuer query is what the cascade reads, so the 4 platform-issued accounts are never even candidates; the sandbox one is revoked")
    void sandboxKeyDeleteRevokesOnlyTheSandboxCredential() {
        Credential sandbox = credential(626, "tiktok", ORG_A, issuedWithSecret(SANDBOX));
        candidates(sandbox);
        when(credentialRepository.findById(626L)).thenReturn(Optional.of(sandbox));
        when(credentialRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int revoked = service.revokeForByokDelete(TENANT, DELETED, List.of());

        assertThat(revoked).isEqualTo(1);
        ArgumentCaptor<Credential> saved = ArgumentCaptor.forClass(Credential.class);
        verify(credentialRepository, times(1)).save(saved.capture());
        assertThat(saved.getValue().id()).isEqualTo(626L);
        assertThat(saved.getValue().status()).isEqualTo(CredentialStatus.needs_reauth);
        assertThat(saved.getValue().credentialData()).doesNotContainKeys("access_token", "refresh_token");
    }

    @Test
    @DisplayName("a platform-issued credential returned as a candidate is still never a dependent")
    void platformIssuedCredentialIsNeverADependent() {
        candidates(credential(476, "tiktok", ORG_A, issuedWithSecret(PLATFORM)),
                credential(626, "tiktok", ORG_A, issuedWithSecret(SANDBOX)));

        assertThat(dependents(List.of())).containsExactly(626L);
    }

    @Test
    @DisplayName("the delete-impact count is the same selection")
    void impactCountMatchesTheCascadeSelection() {
        candidates(credential(1, "tiktok", ORG_A, issuedWithSecret(SANDBOX)),
                credential(2, "tiktok", ORG_A, issuedWithSecret(PLATFORM)));

        assertThat(service.countDependentForByokDelete(TENANT, DELETED, List.of())).isEqualTo(1);
    }

    @Test
    @DisplayName("a credential with its own secret copy is spared by any other row holding the client, in another workspace too")
    void ownSecretCredentialSparedByARowInAnotherWorkspace() {
        candidates(credential(1, "tiktok", ORG_A, issuedWithSecret(SANDBOX)));

        assertThat(dependents(List.of(row(182, "tiktok", TENANT, ORG_B, SANDBOX)))).isEmpty();
    }

    @Test
    @DisplayName("a platform row holding the same client spares its credentials (the shared-client twin of the incident)")
    void platformRowWithTheSameClientSparesTheCredentials() {
        candidates(credential(1, "tiktok", ORG_A, issuedWithoutSecret(SANDBOX)));

        assertThat(dependents(List.of(row(161, "tiktok", null, null, SANDBOX)))).isEmpty();
    }

    @Test
    @DisplayName("a credential WITHOUT a secret copy is not spared by a row its refresh cannot reach (another workspace)")
    void secretlessCredentialNotSparedByAnUnreachableRow() {
        candidates(credential(1, "tiktok", ORG_A, issuedWithoutSecret(SANDBOX)));

        assertThat(dependents(List.of(row(182, "tiktok", TENANT, ORG_B, SANDBOX)))).containsExactly(1L);
    }

    @Test
    @DisplayName("a credential WITHOUT a secret copy is spared by an enabled row in its EXACT scope")
    void secretlessCredentialSparedByARowInItsExactScope() {
        candidates(credential(1, "tiktok", ORG_A, issuedWithoutSecret(SANDBOX)),
                credential(2, "tiktok", null, issuedWithoutSecret(SANDBOX)));

        assertThat(dependents(List.of(row(184, "tiktok", TENANT, ORG_A, SANDBOX),
                row(183, "tiktok", TENANT, null, SANDBOX)))).isEmpty();
    }

    @Test
    @DisplayName("a personal row does not spare a workspace credential WITHOUT a secret copy: refresh never falls back to personal")
    void personalRowDoesNotSpareAWorkspaceCredential() {
        candidates(credential(1, "tiktok", ORG_A, issuedWithoutSecret(SANDBOX)));

        assertThat(dependents(List.of(row(183, "tiktok", TENANT, null, SANDBOX)))).containsExactly(1L);
    }

    @Test
    @DisplayName("a DISABLED row does not spare a credential WITHOUT a secret copy (refresh skips it); one with a copy is still spared")
    void disabledRowSparesOnlyCredentialsWithTheirOwnSecret() {
        PlatformCredential disabled = new PlatformCredential(186L, "tiktok", "tiktok", AuthType.OAUTH2,
                SANDBOX, "secret", null, null, null, "https://a", "https://t", null,
                "tiktok", null, null, true, false, Map.of(), BigDecimal.ZERO, 0,
                Instant.now(), Instant.now(), null, TENANT, "primary", ORG_A);
        candidates(credential(1, "tiktok", ORG_A, issuedWithoutSecret(SANDBOX)),
                credential(2, "tiktok", ORG_A, issuedWithSecret(SANDBOX)));

        assertThat(dependents(List.of(disabled))).containsExactly(1L);
    }

    @Test
    @DisplayName("integration names are compared the way refresh resolves them: 'foo-api' reaches a row named 'foo'")
    void integrationNamesUseTheRefreshNormalizer() {
        candidates(credential(1, "tiktok-api", ORG_A, issuedWithoutSecret(SANDBOX)));

        assertThat(dependents(List.of(row(187, "tiktok", TENANT, ORG_A, SANDBOX)))).isEmpty();
    }

    @Test
    @DisplayName("a credential WITHOUT a secret copy is not spared by a same-client row of ANOTHER integration (its refresh resolves by integration)")
    void secretlessCredentialNotSparedByAnotherIntegrationsRow() {
        candidates(credential(1, "tiktok", ORG_A, issuedWithoutSecret(SANDBOX)));

        assertThat(dependents(List.of(row(185, "tiktok_business", TENANT, ORG_A, SANDBOX)))).containsExactly(1L);
    }

    @Test
    @DisplayName("a credential of another integration issued by the deleted client (one client, two APIs) is a dependent")
    void credentialOfAnotherIntegrationIssuedByTheDeletedClientIsADependent() {
        candidates(credential(1, "google_drive", ORG_A, issuedWithSecret(SANDBOX)));

        assertThat(dependents(List.of())).containsExactly(1L);
    }

    @Test
    @DisplayName("oauth_client_id is read when client_id is absent")
    void issuerFallsBackToOauthClientId() {
        candidates(credential(1, "tiktok", ORG_A, Map.of("oauth_client_id", SANDBOX, "refresh_token", "rt")));

        assertThat(dependents(List.of())).containsExactly(1L);
    }

    @Test
    @DisplayName("a legacy token holder with no client id is a dependent only in the deleted row's exact scope and integration")
    void legacyCredentialIsADependentOnlyWhereTheDeletedRowIsReachable() {
        candidates(credential(1, "tiktok", ORG_A, Map.of("refresh_token", "rt")),
                credential(2, "tiktok", ORG_B, Map.of("refresh_token", "rt")),
                credential(3, "tiktok", null, Map.of("refresh_token", "rt")),
                credential(4, "slack", ORG_A, Map.of("refresh_token", "rt")));

        assertThat(dependents(List.of())).containsExactly(1L);
    }

    @Test
    @DisplayName("deleting a PERSONAL row does not revoke legacy credentials in a workspace")
    void personalRowDeleteSparesWorkspaceLegacyCredentials() {
        PlatformCredential personal = row(190, "tiktok", TENANT, null, SANDBOX);
        candidates(credential(1, "tiktok", ORG_A, Map.of("refresh_token", "rt")),
                credential(2, "tiktok", null, Map.of("refresh_token", "rt")));

        assertThat(service.findByokDependents(TENANT, personal, List.of()))
                .extracting(Credential::id).containsExactly(2L);
    }

    @Test
    @DisplayName("a credential with neither a client id nor OAuth tokens (an API key) is never a dependent")
    void apiKeyCredentialIsNeverADependent() {
        candidates(credential(1, "tiktok", ORG_A, Map.of("api_key", "ENC:k")));

        assertThat(dependents(List.of())).isEmpty();
    }

    @Test
    @DisplayName("deleting a row with no client id (not an OAuth client) revokes nothing and reads nothing")
    void rowWithoutClientIdOrphansNothing() {
        int revoked = service.revokeForByokDelete(TENANT, row(9, "tiktok", TENANT, ORG_A, null), List.of());

        assertThat(revoked).isZero();
        verify(credentialRepository, never())
                .findActiveByTenantIdAndIssuerOrLegacy(anyString(), anyString());
    }
}
