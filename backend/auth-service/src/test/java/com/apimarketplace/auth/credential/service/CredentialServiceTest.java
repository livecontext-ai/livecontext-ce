package com.apimarketplace.auth.credential.service;

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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CredentialService")
class CredentialServiceTest {

    @Mock private CredentialRepository credentialRepository;
    @Mock private StringRedisTemplate redisTemplate;

    private CredentialService service;

    @BeforeEach
    void setUp() {
        service = new CredentialService(credentialRepository, redisTemplate);
    }

    @Test
    @DisplayName("deleteCredentialForScope reassigns default to the most recent remaining credential")
    void deleteCredentialForScopeReassignsDefaultToMostRecentRemainingCredential() {
        Credential deletedDefault = credential(1L, true, "2026-05-04T10:00:00Z");
        Credential olderDefault = credential(2L, true, "2026-05-01T10:00:00Z");
        Credential mostRecent = credential(3L, false, "2026-05-03T10:00:00Z");
        Credential middle = credential(4L, false, "2026-05-02T10:00:00Z");
        when(credentialRepository.findById(1L)).thenReturn(Optional.of(deletedDefault));
        when(credentialRepository.findByScopeAndIntegration("tenant-1", "org-1", "gmail"))
                .thenReturn(List.of(olderDefault, middle, mostRecent));

        boolean result = service.deleteCredentialForScope(1L, "tenant-1", "org-1");

        assertThat(result).isTrue();
        verify(credentialRepository).deleteById(1L);
        verify(credentialRepository).setAsDefaultInScope("tenant-1", "org-1", 3L);
    }

    @Test
    @DisplayName("deleteCredentialForScope leaves existing default when deleting a non-default credential")
    void deleteCredentialForScopeLeavesExistingDefaultWhenDeletingNonDefaultCredential() {
        Credential deletedNonDefault = credential(1L, false, "2026-05-04T10:00:00Z");
        Credential remainingDefault = credential(2L, true, "2026-05-01T10:00:00Z");
        when(credentialRepository.findById(1L)).thenReturn(Optional.of(deletedNonDefault));
        when(credentialRepository.findByScopeAndIntegration("tenant-1", "org-1", "gmail"))
                .thenReturn(List.of(remainingDefault));

        boolean result = service.deleteCredentialForScope(1L, "tenant-1", "org-1");

        assertThat(result).isTrue();
        verify(credentialRepository).deleteById(1L);
        verify(credentialRepository, never()).setAsDefaultInScope("tenant-1", "org-1", 2L);
    }

    @Test
    @DisplayName("deleteCredentialForScope retries when a concurrent delete removes the fallback default")
    void deleteCredentialForScopeRetriesWhenConcurrentDeleteRemovesFallbackDefault() {
        Credential deletedDefault = credential(1L, true, "2026-05-04T10:00:00Z");
        Credential remainingFallback = credential(2L, false, "2026-05-01T10:00:00Z");
        Credential vanishedFallback = credential(3L, false, "2026-05-03T10:00:00Z");
        when(credentialRepository.findById(1L)).thenReturn(Optional.of(deletedDefault));
        // The parallel request already deleted credential 3 by the time we look it up.
        when(credentialRepository.findById(3L)).thenReturn(Optional.empty());
        when(credentialRepository.findByScopeAndIntegration("tenant-1", "org-1", "gmail"))
                .thenReturn(List.of(remainingFallback, vanishedFallback))
                .thenReturn(List.of(remainingFallback));
        // PRODUCTION-FAITHFUL: setAsDefaultInScope raises a bare IllegalArgumentException,
        // but the @Repository proxy translates it into a Spring DataAccessException
        // (InvalidDataAccessApiUsageException) before the service sees it. The catch
        // must handle THIS wrapped type, not the bare IAE - a narrowed catch regresses.
        doThrow(new InvalidDataAccessApiUsageException(
                "Credential not found: 3", new IllegalArgumentException("Credential not found: 3")))
                .when(credentialRepository)
                .setAsDefaultInScope("tenant-1", "org-1", 3L);

        boolean result = service.deleteCredentialForScope(1L, "tenant-1", "org-1");

        assertThat(result).isTrue();
        verify(credentialRepository).deleteById(1L);
        verify(credentialRepository).setAsDefaultInScope("tenant-1", "org-1", 3L);
        verify(credentialRepository).setAsDefaultInScope("tenant-1", "org-1", 2L);
        // The retry path is only reached because the catch re-probes existence;
        // pre-fix code (no try/catch) never looks the vanished fallback up again.
        verify(credentialRepository).findById(3L);
    }

    @Test
    @DisplayName("deleteCredentialForScope stops without a default when every fallback vanishes concurrently")
    void deleteCredentialForScopeStopsWhenEveryFallbackVanishes() {
        Credential deletedDefault = credential(1L, true, "2026-05-04T10:00:00Z");
        Credential vanishedFallback = credential(2L, false, "2026-05-01T10:00:00Z");
        when(credentialRepository.findById(1L)).thenReturn(Optional.of(deletedDefault));
        when(credentialRepository.findById(2L)).thenReturn(Optional.empty());
        when(credentialRepository.findByScopeAndIntegration("tenant-1", "org-1", "gmail"))
                .thenReturn(List.of(vanishedFallback));
        doThrow(new InvalidDataAccessApiUsageException(
                "Credential not found: 2", new IllegalArgumentException("Credential not found: 2")))
                .when(credentialRepository)
                .setAsDefaultInScope("tenant-1", "org-1", 2L);

        boolean result = service.deleteCredentialForScope(1L, "tenant-1", "org-1");

        assertThat(result).isTrue();
        verify(credentialRepository).deleteById(1L);
        // The only candidate vanished, so no default could be assigned; the loop
        // must terminate rather than retry the same vanished id forever. Exactly
        // two scope queries prove it: one that found the doomed fallback, one
        // that finds it filtered out and returns.
        verify(credentialRepository).setAsDefaultInScope("tenant-1", "org-1", 2L);
        verify(credentialRepository).findById(2L);
        verify(credentialRepository, times(2))
                .findByScopeAndIntegration("tenant-1", "org-1", "gmail");
    }

    @Test
    @DisplayName("deleteCredentialForScope propagates an unrelated failure when the fallback still exists")
    void deleteCredentialForScopePropagatesUnrelatedFailureWhenFallbackStillExists() {
        Credential deletedDefault = credential(1L, true, "2026-05-04T10:00:00Z");
        Credential presentFallback = credential(2L, false, "2026-05-01T10:00:00Z");
        when(credentialRepository.findById(1L)).thenReturn(Optional.of(deletedDefault));
        // The fallback is still present, so the failure is NOT a concurrent delete.
        when(credentialRepository.findById(2L)).thenReturn(Optional.of(presentFallback));
        when(credentialRepository.findByScopeAndIntegration("tenant-1", "org-1", "gmail"))
                .thenReturn(List.of(presentFallback));
        // An out-of-scope error is also surfaced as a translated DataAccessException.
        doThrow(new InvalidDataAccessApiUsageException(
                "Credential not in active org scope",
                new IllegalArgumentException("Credential not in active org scope")))
                .when(credentialRepository)
                .setAsDefaultInScope("tenant-1", "org-1", 2L);

        assertThatThrownBy(() -> service.deleteCredentialForScope(1L, "tenant-1", "org-1"))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("Credential not in active org scope");

        verify(credentialRepository).setAsDefaultInScope("tenant-1", "org-1", 2L);
        // The catch re-probes existence to decide rethrow-vs-skip; the present
        // fallback means the failure is real and must propagate. This is the load-
        // bearing distinction: skip only when the row is actually gone.
        verify(credentialRepository).findById(2L);
    }

    @Test
    @DisplayName("deleteCredentialForScope propagates a genuine infrastructure failure (fallback still present)")
    void deleteCredentialForScopePropagatesInfrastructureFailureWhenFallbackStillExists() {
        Credential deletedDefault = credential(1L, true, "2026-05-04T10:00:00Z");
        Credential fallback = credential(2L, false, "2026-05-01T10:00:00Z");
        when(credentialRepository.findById(1L)).thenReturn(Optional.of(deletedDefault));
        // The re-probe finds the fallback alive, so the failure is NOT a concurrent delete.
        when(credentialRepository.findById(2L)).thenReturn(Optional.of(fallback));
        when(credentialRepository.findByScopeAndIntegration("tenant-1", "org-1", "gmail"))
                .thenReturn(List.of(fallback));
        // A real infrastructure failure (a DataAccessException that is NOT a vanished row).
        doThrow(new DataAccessResourceFailureException("db down"))
                .when(credentialRepository)
                .setAsDefaultInScope("tenant-1", "org-1", 2L);

        assertThatThrownBy(() -> service.deleteCredentialForScope(1L, "tenant-1", "org-1"))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasMessage("db down");

        // Existence re-probe runs (the catch is type-agnostic); the row is present,
        // so the error is genuine and must propagate - never silently swallowed.
        verify(credentialRepository).findById(2L);
    }

    @Test
    @DisplayName("findActiveIntegrationsForScope returns the org-wide set when an organization is supplied")
    void findActiveIntegrationsForScopeUsesOrgScope() {
        when(credentialRepository.findActiveIntegrationsByOrganizationId("org-1"))
                .thenReturn(java.util.Set.of("twitter", "gmail"));

        java.util.Set<String> result = service.findActiveIntegrationsForScope("tenant-1", "org-1");

        assertThat(result).containsExactlyInAnyOrder("twitter", "gmail");
        verify(credentialRepository, never()).findActiveIntegrationsByTenantId(anyString());
    }

    @Test
    @DisplayName("findActiveIntegrationsForScope falls back to tenant scope when no organization is supplied")
    void findActiveIntegrationsForScopeFallsBackToTenant() {
        when(credentialRepository.findActiveIntegrationsByTenantId("tenant-1"))
                .thenReturn(java.util.Set.of("slack"));

        java.util.Set<String> result = service.findActiveIntegrationsForScope("tenant-1", null);

        assertThat(result).containsExactly("slack");
        verify(credentialRepository, never()).findActiveIntegrationsByOrganizationId(anyString());
    }

    @Test
    @DisplayName("touchLastUsed delegates to the repository's single-column last_used UPDATE")
    void touchLastUsedDelegatesToRepository() {
        service.touchLastUsed(243L);

        // Must use the targeted UPDATE - never the full save() (which would re-encrypt
        // credential_data and bump updated_at, making a mere use look like an edit).
        verify(credentialRepository).touchLastUsed(243L);
        verify(credentialRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private Credential credential(Long id, boolean isDefault, String createdAt) {
        Instant created = Instant.parse(createdAt);
        return new Credential(
                id,
                "tenant-1",
                "org-1",
                "Gmail " + id,
                "gmail",
                CredentialType.OAuth2,
                CredentialEnvironment.Production,
                CredentialStatus.active,
                "Test credential",
                Map.of("access_token", "enc-token-" + id),
                List.of("email"),
                List.of(),
                "tenant-1",
                "icon",
                isDefault,
                null,
                created,
                created);
    }
}
