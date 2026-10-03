package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.CredentialModels.Credential;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialEnvironment;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialStatus;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialType;
import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.AuthType;
import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredential;
import com.apimarketplace.auth.credential.repository.CredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LC-065: every path that removes a credential (or its tokens) tells the provider first, and a
 * provider failure never blocks the removal. LC-058: each removal is an audit event carrying the
 * revocation outcome.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CredentialService revokes at the provider before every delete")
class CredentialServiceRevokeOnDeleteTest {

    @Mock private CredentialRepository repository;
    @Mock private StringRedisTemplate redis;
    @Mock private OAuth2RevocationService revocation;
    @Mock private CredentialAuditRecorder audit;

    private CredentialService service;

    private static final String USER = "user-1";
    private static final String ORG = "11111111-1111-1111-1111-111111111111";

    @BeforeEach
    void setUp() {
        service = new CredentialService(repository, redis);
        service.setLifecycleCollaborators(revocation, audit);
        lenient().when(revocation.revoke(any(), any())).thenReturn(OAuth2RevocationService.Outcome.REVOKED);
    }

    private static Credential gmail(long id) {
        return new Credential(id, USER, ORG, "Gmail " + id, "gmail", CredentialType.OAuth2,
                CredentialEnvironment.Production, CredentialStatus.active, null,
                Map.of("refresh_token", "ENC:rt"), List.of(), List.of(), USER, null, false, null,
                Instant.now(), Instant.now());
    }

    /** A gmail credential carrying an {@code oauth_client_id}, so the LC-065 sibling lookup fires. */
    private static Credential gmailWithClient(long id, String tenant, String clientId) {
        return new Credential(id, tenant, ORG, "Gmail " + id, "gmail", CredentialType.OAuth2,
                CredentialEnvironment.Production, CredentialStatus.active, null,
                Map.of("refresh_token", "ENC:rt", "oauth_client_id", clientId),
                List.of(), List.of(), tenant, null, false, null, Instant.now(), Instant.now());
    }

    /** The BYOK platform_credential row being deleted, for the {@code findByokDependents} selection. */
    private static PlatformCredential platformRow(long id, String integration, String tenant, String clientId) {
        return new PlatformCredential(id, integration, integration, AuthType.OAUTH2,
                clientId, "secret", null, null, null, "https://a", "https://t", null,
                integration, null, null, true, true, Map.of(), BigDecimal.ZERO, 0,
                Instant.now(), Instant.now(), null, tenant, "primary", ORG);
    }

    @Test
    @DisplayName("user disconnect (scoped delete): revoke, THEN delete, then an audit event with the outcome")
    void scopedDeleteRevokesFirst() {
        when(repository.findById(5L)).thenReturn(Optional.of(gmail(5)));

        assertThat(service.deleteCredentialForScope(5L, USER, ORG)).isTrue();

        InOrder order = inOrder(revocation, repository);
        order.verify(revocation).revoke(any(), any());
        order.verify(repository).deleteById(5L);
        verify(audit).recordDeleted(USER, 5L, "gmail", "user_delete", "revoked");
    }

    @Test
    @DisplayName("legacy delete path: revoke, THEN delete")
    void legacyDeleteRevokesFirst() {
        when(repository.findById(6L)).thenReturn(Optional.of(gmail(6)));

        assertThat(service.deleteCredential(6L, USER)).isTrue();

        InOrder order = inOrder(revocation, repository);
        order.verify(revocation).revoke(any(), any());
        order.verify(repository).deleteById(6L);
    }

    @Test
    @DisplayName("a provider failure is recorded and the delete still happens")
    void failureDoesNotBlockDelete() {
        when(repository.findById(5L)).thenReturn(Optional.of(gmail(5)));
        when(revocation.revoke(any(), any())).thenReturn(OAuth2RevocationService.Outcome.FAILED);

        assertThat(service.deleteCredentialForScope(5L, USER, ORG)).isTrue();

        verify(repository).deleteById(5L);
        verify(audit).recordDeleted(USER, 5L, "gmail", "user_delete", "failed");
    }

    @Test
    @DisplayName("a credential outside the caller's scope is neither revoked nor deleted")
    void foreignCredentialUntouched() {
        when(repository.findById(5L)).thenReturn(Optional.of(gmail(5)));

        assertThat(service.deleteCredential(5L, "someone-else")).isFalse();

        verify(revocation, never()).revoke(any(), any());
        verify(repository, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("BYOK client removal: dependents are scrubbed immediately, revoked at the provider only "
            + "AFTER the surrounding transaction commits (LC-065 item 2)")
    void byokCascadeScrubsImmediately_revokesAfterCommit() {
        Credential five = gmailWithClient(5, USER, "cid-shared");
        Credential six = gmailWithClient(6, USER, "cid-shared");
        PlatformCredential deletedRow = platformRow(181, "gmail", USER, "cid-shared");
        when(repository.findActiveByTenantIdAndIssuerOrLegacy(USER, "cid-shared"))
                .thenReturn(List.of(five, six));
        when(repository.findById(5L)).thenReturn(Optional.of(five));
        when(repository.findById(6L)).thenReturn(Optional.of(six));

        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.revokeForByokDelete(USER, deletedRow, List.of());

            // The scrub (which removes the inline token/secret copy) already happened, but the
            // provider round trip has NOT: it must not run inside the caller's transaction.
            verify(repository).save(org.mockito.ArgumentMatchers.argThat(c -> c.id() == 5L));
            verify(repository).save(org.mockito.ArgumentMatchers.argThat(c -> c.id() == 6L));
            verify(revocation, never()).revoke(any(), any());

            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);

            verify(revocation).revoke(org.mockito.ArgumentMatchers.argThat(c -> c.id() == 5L), any());
            verify(revocation).revoke(org.mockito.ArgumentMatchers.argThat(c -> c.id() == 6L), any());
            verify(audit).recordDeleted(USER, 5L, "gmail", "byok_client_deleted", "revoked");
            verify(audit).recordDeleted(USER, 6L, "gmail", "byok_client_deleted", "revoked");
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("BYOK client removal rolled back: nothing is ever sent to the provider (LC-065 item 2)")
    void byokCascadeRolledBack_revokesNothing() {
        Credential five = gmailWithClient(5, USER, "cid-shared");
        PlatformCredential deletedRow = platformRow(181, "gmail", USER, "cid-shared");
        when(repository.findActiveByTenantIdAndIssuerOrLegacy(USER, "cid-shared"))
                .thenReturn(List.of(five));
        when(repository.findById(5L)).thenReturn(Optional.of(five));

        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.revokeForByokDelete(USER, deletedRow, List.of());
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCompletion(
                            org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }

        verify(revocation, never()).revoke(any(), any());
    }

    @Test
    @DisplayName("BYOK client removal: only dependents connected through THIS client are revoked/scrubbed (LC-065 item 3)")
    void byokCascadeFiltersByClientId() {
        Credential viaDeletedClient = gmailWithClient(5, USER, "cid-deleted");
        Credential viaOtherClient = gmailWithClient(6, USER, "cid-other");
        PlatformCredential deletedRow = platformRow(181, "gmail", USER, "cid-deleted");
        // The repository query already filters by issuer server-side; both are returned here to
        // pin that CredentialService.isOrphanedBy ALSO checks the issuer itself as defense in
        // depth, so a candidate connected through a different client is never affected even if
        // it slipped through.
        when(repository.findActiveByTenantIdAndIssuerOrLegacy(USER, "cid-deleted"))
                .thenReturn(List.of(viaDeletedClient, viaOtherClient));
        when(repository.findById(5L)).thenReturn(Optional.of(viaDeletedClient));

        int revoked = service.revokeForByokDelete(USER, deletedRow, List.of());

        assertThat(revoked).isEqualTo(1);
        verify(repository).save(org.mockito.ArgumentMatchers.argThat(c -> c.id() == 5L));
        verify(repository, never()).save(org.mockito.ArgumentMatchers.argThat(c -> c.id() == 6L));
        verify(revocation).revoke(org.mockito.ArgumentMatchers.argThat(c -> c.id() == 5L), any());
        verify(revocation, never()).revoke(org.mockito.ArgumentMatchers.argThat(c -> c.id() == 6L), any());
        assertThat(service.countDependentForByokDelete(USER, deletedRow, List.of())).isEqualTo(1);
    }

    @Test
    @DisplayName("account purge: every credential the user owns is revoked")
    void accountPurgeRevokesAll() {
        when(repository.findAllByTenantId(USER)).thenReturn(List.of(gmail(5), gmail(6)));

        assertThat(service.revokeAllForAccountPurge(USER)).isEqualTo(2);

        verify(audit).recordDeleted(USER, 5L, "gmail", "account_purge", "revoked");
        verify(audit).recordDeleted(USER, 6L, "gmail", "account_purge", "revoked");
    }

    @Test
    @DisplayName("workspace purge: every page of workspace credentials is revoked")
    void workspacePurgeRevokesAllPages() {
        List<Credential> fullPage = java.util.stream.LongStream.range(0, 500).mapToObj(CredentialServiceRevokeOnDeleteTest::gmail).toList();
        when(repository.findByOrganizationIdStrict(ORG, 1, 500)).thenReturn(fullPage);
        when(repository.findByOrganizationIdStrict(ORG, 2, 500)).thenReturn(List.of(gmail(900)));

        assertThat(service.revokeAllForWorkspacePurge(ORG, null)).isEqualTo(501);

        verify(repository, never()).findByOrganizationIdStrict(eq(ORG), eq(3), anyInt());
        verify(audit).recordDeleted(USER, 900L, "gmail", "workspace_purge", "revoked");
    }

    @Test
    @DisplayName("without a revocation service (hand-built instance) the delete still works: 'not_attempted'")
    void worksWithoutCollaborators() {
        CredentialService bare = new CredentialService(repository, redis);
        bare.setLifecycleCollaborators(null, audit);
        when(repository.findById(5L)).thenReturn(Optional.of(gmail(5)));

        assertThat(bare.deleteCredentialForScope(5L, USER, ORG)).isTrue();
        verify(audit).recordDeleted(USER, 5L, "gmail", "user_delete", "not_attempted");
        verify(repository).deleteById(5L);
    }

    // ─────────────── Audit follow-up: sibling grants, after-commit purges ───────────────

    @Test
    @DisplayName("user delete: OTHER usable OAuth2 credentials of the SAME client, in ANY tenant, are handed "
            + "to the revoker as survivors (LC-065 item 1, cross-tenant)")
    void singleDeletePassesSiblingsAsSurvivors() {
        Credential five = gmailWithClient(5, USER, "cid-shared");
        Credential sixOtherTenant = gmailWithClient(6, "someone-else", "cid-shared");
        when(repository.findById(5L)).thenReturn(Optional.of(five));
        when(repository.findUsableOAuth2ByClientId("cid-shared", null)).thenReturn(List.of(sixOtherTenant));

        service.deleteCredentialForScope(5L, USER, ORG);

        verify(revocation).revoke(org.mockito.ArgumentMatchers.eq(five),
                org.mockito.ArgumentMatchers.argThat(s -> s.size() == 1 && s.iterator().next().id() == 6L));
    }

    @Test
    @DisplayName("batch: each member sees only the members still to come, so only the last one of a grant revokes")
    void batchSurvivorsShrink() {
        Credential five = gmail(5);
        Credential six = gmail(6);
        when(repository.findAllByTenantId(USER)).thenReturn(List.of(five, six));

        service.revokeAllForAccountPurge(USER);

        verify(revocation).revoke(org.mockito.ArgumentMatchers.eq(five),
                org.mockito.ArgumentMatchers.argThat(s -> s.size() == 1 && s.iterator().next().id() == 6L));
        verify(revocation).revoke(org.mockito.ArgumentMatchers.eq(six),
                org.mockito.ArgumentMatchers.argThat(java.util.Collection::isEmpty));
    }

    @Test
    @DisplayName("purge inside a transaction: nothing is sent to a provider until the transaction COMMITS")
    void purgeRevokesOnlyAfterCommit() {
        when(repository.findAllByTenantId(USER)).thenReturn(List.of(gmail(5)));
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            assertThat(service.revokeAllForAccountPurge(USER)).isEqualTo(1);
            verify(revocation, never()).revoke(any(), any());

            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);

            verify(revocation).revoke(any(), any());
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("purge rolled back: the snapshot is never revoked")
    void rolledBackPurgeRevokesNothing() {
        when(repository.findAllByTenantId(USER)).thenReturn(List.of(gmail(5)));
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.revokeAllForAccountPurge(USER);
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCompletion(
                            org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
        verify(revocation, never()).revoke(any(), any());
    }

    @Test
    @DisplayName("sibling lookup failure: do NOT revoke (it could break a live sibling), record why, still delete")
    void siblingLookupFailureDoesNotRevoke() {
        Credential five = gmailWithClient(5, USER, "cid-shared");
        when(repository.findById(5L)).thenReturn(Optional.of(five));
        when(repository.findUsableOAuth2ByClientId("cid-shared", null))
                .thenThrow(new IllegalStateException("db down"));

        assertThat(service.deleteCredentialForScope(5L, USER, ORG)).isTrue();

        verify(revocation, never()).revoke(any(), any());
        verify(audit).recordDeleted(USER, 5L, "gmail", "user_delete", "sibling_lookup_failed");
        verify(repository).deleteById(5L);
    }
}
