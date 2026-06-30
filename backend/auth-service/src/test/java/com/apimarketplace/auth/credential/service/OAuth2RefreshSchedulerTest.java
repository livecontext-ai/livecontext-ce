package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.CredentialModels.Credential;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialEnvironment;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialStatus;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialType;
import com.apimarketplace.auth.credential.repository.CredentialRepository;
import com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshErrorBucket;
import com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshTerminalException;
import com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshTransientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OAuth2RefreshScheduler")
class OAuth2RefreshSchedulerTest {

    @Mock
    private CredentialRepository credentialRepository;

    @Mock
    private OAuth2Service oAuth2Service;

    private OAuth2RefreshScheduler scheduler;

    @BeforeEach
    void setUp() {
        // 10-minute look-ahead matches the production default and is asserted below.
        scheduler = new OAuth2RefreshScheduler(credentialRepository, oAuth2Service, 10);
    }

    @Test
    @DisplayName("no candidates → no-op, never calls refreshToken")
    void noCandidates_isNoOp() {
        when(credentialRepository.findOAuth2CredentialsExpiringBefore(any(Instant.class)))
                .thenReturn(List.of());

        scheduler.refreshExpiringTokens();

        verifyNoInteractions(oAuth2Service);
    }

    @Test
    @DisplayName("look-ahead window: queries with now + configured minutes, not past")
    void queriesWithLookAheadWindow() {
        when(credentialRepository.findOAuth2CredentialsExpiringBefore(any(Instant.class)))
                .thenReturn(List.of());
        Instant before = Instant.now();

        scheduler.refreshExpiringTokens();

        ArgumentCaptor<Instant> cap = ArgumentCaptor.forClass(Instant.class);
        verify(credentialRepository).findOAuth2CredentialsExpiringBefore(cap.capture());
        Instant after = Instant.now();

        // Threshold must be in [now+10m-slack, now+10m+slack] - a little slack for scheduler jitter.
        assertThat(cap.getValue())
                .isAfterOrEqualTo(before.plusSeconds(10 * 60 - 1))
                .isBeforeOrEqualTo(after.plusSeconds(10 * 60 + 1));
    }

    @Test
    @DisplayName("each candidate → one refreshToken call with its own (id, tenantId)")
    void happyPath_refreshesEach() {
        Credential c1 = credential(1L, "tenant-a");
        Credential c2 = credential(2L, "tenant-b");
        when(credentialRepository.findOAuth2CredentialsExpiringBefore(any(Instant.class)))
                .thenReturn(List.of(c1, c2));

        scheduler.refreshExpiringTokens();

        verify(oAuth2Service).refreshToken(1L, "tenant-a");
        verify(oAuth2Service).refreshToken(2L, "tenant-b");
    }

    @Test
    @DisplayName("refresh_not_supported on one credential does not abort the sweep")
    void unsupportedProvider_doesNotAbortSweep() {
        Credential c1 = credential(1L, "tenant-a");
        Credential c2 = credential(2L, "tenant-b");
        Credential c3 = credential(3L, "tenant-c");
        when(credentialRepository.findOAuth2CredentialsExpiringBefore(any(Instant.class)))
                .thenReturn(List.of(c1, c2, c3));

        when(oAuth2Service.refreshToken(2L, "tenant-b"))
                .thenThrow(new IllegalStateException("refresh_not_supported: client_credentials only"));

        scheduler.refreshExpiringTokens();

        verify(oAuth2Service).refreshToken(1L, "tenant-a");
        verify(oAuth2Service).refreshToken(2L, "tenant-b");
        verify(oAuth2Service).refreshToken(3L, "tenant-c");
    }

    @Test
    @DisplayName("refresh_in_progress is not counted as a failure (scheduler retries next tick)")
    void lockContention_isSwallowed() {
        Credential c = credential(1L, "tenant-a");
        when(credentialRepository.findOAuth2CredentialsExpiringBefore(any(Instant.class)))
                .thenReturn(List.of(c));
        when(oAuth2Service.refreshToken(eq(1L), anyString()))
                .thenThrow(new IllegalStateException("refresh_in_progress"));

        // Must not propagate - the sweep is allowed to silently yield on contention.
        scheduler.refreshExpiringTokens();

        verify(oAuth2Service, times(1)).refreshToken(1L, "tenant-a");
    }

    @Test
    @DisplayName("unexpected exception on one credential does not abort the sweep")
    void unexpectedException_doesNotAbortSweep() {
        Credential c1 = credential(1L, "tenant-a");
        Credential c2 = credential(2L, "tenant-b");
        when(credentialRepository.findOAuth2CredentialsExpiringBefore(any(Instant.class)))
                .thenReturn(List.of(c1, c2));

        when(oAuth2Service.refreshToken(1L, "tenant-a"))
                .thenThrow(new RuntimeException("boom"));

        scheduler.refreshExpiringTokens();

        verify(oAuth2Service).refreshToken(1L, "tenant-a");
        verify(oAuth2Service).refreshToken(2L, "tenant-b");
    }

    @Test
    @DisplayName("repository failure during lookup → swept silently, no refresh attempts")
    void repoFailure_swallowed() {
        when(credentialRepository.findOAuth2CredentialsExpiringBefore(any(Instant.class)))
                .thenThrow(new RuntimeException("db down"));

        scheduler.refreshExpiringTokens();

        // A dead database must not take the whole scheduler thread down - next tick will retry.
        verify(oAuth2Service, never()).refreshToken(anyLong(), anyString());
    }

    /**
     * RefreshTerminalException on one credential must not abort the sweep. The credential has
     * already been flipped to needs_reauth / error by OAuth2Service - the scheduler's only job
     * here is structured logging (and metrics, once added). Subsequent sweeps skip it via the
     * status-NOT-IN predicate.
     */
    @Test
    @DisplayName("terminal on one credential: logged, classified separately, sweep continues")
    void terminalBucket_doesNotAbortSweep() {
        Credential c1 = credential(1L, "tenant-a");
        Credential c2 = credential(2L, "tenant-b");
        Credential c3 = credential(3L, "tenant-c");
        when(credentialRepository.findOAuth2CredentialsExpiringBefore(any(Instant.class)))
                .thenReturn(List.of(c1, c2, c3));
        when(oAuth2Service.refreshToken(2L, "tenant-b"))
                .thenThrow(new RefreshTerminalException(
                        RefreshErrorBucket.TERMINAL_USER, "invalid_grant", 400,
                        "provider rejected refresh_token"));

        scheduler.refreshExpiringTokens();

        verify(oAuth2Service).refreshToken(1L, "tenant-a");
        verify(oAuth2Service).refreshToken(2L, "tenant-b");
        verify(oAuth2Service).refreshToken(3L, "tenant-c");
    }

    /**
     * RefreshTransientException is the common case for a provider blip (5xx, 429, socket
     * timeout). OAuth2Service already persisted the cooldown-until + Redis cooldown sentinel;
     * the sweep's job is to keep going and let the next tick skip this row via the cooldown
     * predicate. Must NOT be conflated with failed (which implies an unclassified error).
     */
    @Test
    @DisplayName("transient on one credential: debug-logged as transient_retry, sweep continues")
    void transientBucket_doesNotAbortSweep() {
        Credential c1 = credential(1L, "tenant-a");
        Credential c2 = credential(2L, "tenant-b");
        when(credentialRepository.findOAuth2CredentialsExpiringBefore(any(Instant.class)))
                .thenReturn(List.of(c1, c2));
        when(oAuth2Service.refreshToken(1L, "tenant-a"))
                .thenThrow(new RefreshTransientException(
                        RefreshErrorBucket.TRANSIENT, null, 503, null, 1,
                        new RuntimeException("503 Service Unavailable")));

        scheduler.refreshExpiringTokens();

        verify(oAuth2Service).refreshToken(1L, "tenant-a");
        verify(oAuth2Service).refreshToken(2L, "tenant-b");
    }

    // ────────────────────────── helpers ──────────────────────────

    private static Credential credential(long id, String tenantId) {
        return new Credential(
                id, tenantId, "cred-" + id, "gmail",
                CredentialType.OAuth2,
                CredentialEnvironment.Production,
                CredentialStatus.active,
                null,
                Map.of("refresh_token", "r", "expires_at", Instant.now().toString()),
                List.of(), List.of(),
                null, null, false, null,
                Instant.now(), Instant.now()
        );
    }
}
