package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.CredentialModels.Credential;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialEnvironment;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialStatus;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialType;
import com.apimarketplace.auth.credential.repository.CredentialRepository;
import com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshErrorBucket;
import com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshTerminalException;
import com.apimarketplace.notification.client.NotificationClient;
import com.apimarketplace.notification.client.dto.NotificationEmitRequest;
import com.apimarketplace.auth.repository.OrganizationRepository;
import com.apimarketplace.auth.domain.Organization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P5 - verifies OAuth2RefreshScheduler emits CRED_EXPIRED via NotificationClient
 * when {@code RefreshTerminalException} is caught (token bucket, invalid_grant
 * etc.). Transient failures and successful refreshes do NOT emit.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OAuth2RefreshScheduler - P5 CRED_EXPIRED emission")
class OAuth2RefreshSchedulerNotificationTest {

    @Mock private CredentialRepository credentialRepository;
    @Mock private OAuth2Service oAuth2Service;
    @Mock private NotificationClient notificationClient;
    @Mock private OrganizationRepository organizationRepository;

    private OAuth2RefreshScheduler scheduler;

    @BeforeEach
    void setUp() throws Exception {
        scheduler = new OAuth2RefreshScheduler(credentialRepository, oAuth2Service, 10);
        Field f = OAuth2RefreshScheduler.class.getDeclaredField("notificationClient");
        f.setAccessible(true);
        f.set(scheduler, notificationClient);
        Field orgF = OAuth2RefreshScheduler.class.getDeclaredField("organizationRepository");
        orgF.setAccessible(true);
        orgF.set(scheduler, organizationRepository);
    }

    private static Credential credential(long id, String tenantId) {
        return new Credential(
                id, tenantId, "Gmail prod cred", "gmail",
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

    @Test
    @DisplayName("RefreshTerminalException → emits CRED_EXPIRED with payload + deterministic UUID subject_id")
    void terminalEmitsCredExpired() {
        long credId = 42L;
        Credential c = credential(credId, "tenant-x");
        when(credentialRepository.findOAuth2CredentialsExpiringBefore(any(Instant.class)))
                .thenReturn(List.of(c));
        when(oAuth2Service.refreshToken(credId, "tenant-x"))
                .thenThrow(new RefreshTerminalException(
                        RefreshErrorBucket.TERMINAL_USER, "invalid_grant", 400,
                        "provider rejected refresh_token"));
        lenient().when(notificationClient.emit(any())).thenReturn(true);

        scheduler.refreshExpiringTokens();

        ArgumentCaptor<NotificationEmitRequest> captor = ArgumentCaptor.forClass(NotificationEmitRequest.class);
        verify(notificationClient).emit(captor.capture());
        NotificationEmitRequest req = captor.getValue();

        assertThat(req.getCategory()).isEqualTo("CRED_EXPIRED");
        assertThat(req.getSeverity()).isEqualTo("warning");
        assertThat(req.getSubjectType()).isEqualTo("CREDENTIAL");
        assertThat(req.getTenantId()).isEqualTo("tenant-x");
        // Deterministic UUID from "cred-42"
        UUID expectedUuid = UUID.nameUUIDFromBytes(("cred-" + credId).getBytes(StandardCharsets.UTF_8));
        assertThat(req.getSubjectId()).isEqualTo(expectedUuid);
        // source_id = credId + ":" + epoch_day
        long expectedDay = Instant.now().truncatedTo(ChronoUnit.DAYS).getEpochSecond() / 86_400L;
        assertThat(req.getSourceId()).isEqualTo(credId + ":" + expectedDay);

        assertThat(req.getPayload()).containsEntry("status", "expired");
        assertThat(req.getPayload()).containsEntry("reason", "TERMINAL_USER");
        assertThat(req.getPayload()).containsEntry("provider", "invalid_grant");
        assertThat(req.getPayload()).containsEntry("integration", "gmail");
        assertThat(req.getPayload()).containsEntry("subjectName", "Gmail prod cred");
        assertThat(req.getPayload()).containsEntry("credentialId", credId);
    }

    @Test
    @DisplayName("Successful refresh → no CRED_EXPIRED emit")
    void successfulRefreshDoesNotEmit() {
        Credential c = credential(1L, "tenant-x");
        when(credentialRepository.findOAuth2CredentialsExpiringBefore(any(Instant.class)))
                .thenReturn(List.of(c));
        // refreshToken returns normally, no exception

        scheduler.refreshExpiringTokens();

        verify(notificationClient, never()).emit(any());
    }

    @Test
    @DisplayName("RefreshTransientException → no CRED_EXPIRED emit (transient is not terminal)")
    void transientDoesNotEmit() {
        Credential c = credential(1L, "tenant-x");
        when(credentialRepository.findOAuth2CredentialsExpiringBefore(any(Instant.class)))
                .thenReturn(List.of(c));
        when(oAuth2Service.refreshToken(1L, "tenant-x"))
                .thenThrow(new com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshTransientException(
                        RefreshErrorBucket.TRANSIENT, null, 503, null, 1,
                        new RuntimeException("503")));

        scheduler.refreshExpiringTokens();

        verify(notificationClient, never()).emit(any());
    }

    @Test
    @DisplayName("Multiple credentials, mixed outcomes → emit only for terminal ones")
    void mixedOutcomesEmitsOnlyTerminal() {
        Credential c1 = credential(1L, "tenant-a"); // success
        Credential c2 = credential(2L, "tenant-b"); // terminal
        Credential c3 = credential(3L, "tenant-c"); // transient
        Credential c4 = credential(4L, "tenant-d"); // terminal
        when(credentialRepository.findOAuth2CredentialsExpiringBefore(any(Instant.class)))
                .thenReturn(List.of(c1, c2, c3, c4));
        when(oAuth2Service.refreshToken(2L, "tenant-b"))
                .thenThrow(new RefreshTerminalException(
                        RefreshErrorBucket.TERMINAL_USER, "invalid_grant", 400, "rejected"));
        when(oAuth2Service.refreshToken(3L, "tenant-c"))
                .thenThrow(new com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshTransientException(
                        RefreshErrorBucket.TRANSIENT, null, 503, null, 1, new RuntimeException("503")));
        when(oAuth2Service.refreshToken(4L, "tenant-d"))
                .thenThrow(new RefreshTerminalException(
                        RefreshErrorBucket.TERMINAL_CONFIG, "expired_token", 401, "expired"));
        lenient().when(notificationClient.emit(any())).thenReturn(true);

        scheduler.refreshExpiringTokens();

        ArgumentCaptor<NotificationEmitRequest> captor = ArgumentCaptor.forClass(NotificationEmitRequest.class);
        verify(notificationClient, org.mockito.Mockito.times(2)).emit(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(NotificationEmitRequest::getTenantId)
                .containsExactlyInAnyOrder("tenant-b", "tenant-d");
    }

    @Test
    @DisplayName("NotificationClient unwired (null) → terminal still flows, no NPE, sweep continues")
    void unwiredClientIsNoOp() throws Exception {
        OAuth2RefreshScheduler bare = new OAuth2RefreshScheduler(credentialRepository, oAuth2Service, 10);
        // notificationClient field stays null
        Credential c = credential(1L, "tenant-x");
        when(credentialRepository.findOAuth2CredentialsExpiringBefore(any(Instant.class)))
                .thenReturn(List.of(c));
        when(oAuth2Service.refreshToken(1L, "tenant-x"))
                .thenThrow(new RefreshTerminalException(
                        RefreshErrorBucket.TERMINAL_USER, "invalid_grant", 400, "rejected"));

        // Must not throw
        bare.refreshExpiringTokens();
    }

    @Test
    @DisplayName("emit() throwing does NOT abort the sweep (defense-in-depth swallow)")
    void emitThrowDoesNotAbort() {
        Credential c1 = credential(1L, "tenant-a");
        Credential c2 = credential(2L, "tenant-b");
        when(credentialRepository.findOAuth2CredentialsExpiringBefore(any(Instant.class)))
                .thenReturn(List.of(c1, c2));
        when(oAuth2Service.refreshToken(anyLong(), anyString()))
                .thenThrow(new RefreshTerminalException(
                        RefreshErrorBucket.TERMINAL_USER, "invalid_grant", 400, "rejected"));
        when(notificationClient.emit(any())).thenThrow(new RuntimeException("emit boom"));

        // Both candidates must still be attempted despite emit throwing
        scheduler.refreshExpiringTokens();

        verify(oAuth2Service).refreshToken(1L, "tenant-a");
        verify(oAuth2Service).refreshToken(2L, "tenant-b");
    }

    /** Canonical 18-arg constructor so we can set a non-null team-scope organizationId. */
    private static Credential credentialWithOrg(long id, String tenantId, String orgId) {
        return new Credential(
                id, tenantId, orgId, "Gmail prod cred", "gmail",
                CredentialType.OAuth2, CredentialEnvironment.Production, CredentialStatus.active,
                null, Map.of("refresh_token", "r", "expires_at", Instant.now().toString()),
                List.of(), List.of(), null, null, false, null,
                Instant.now(), Instant.now()
        );
    }

    @Test
    @DisplayName("Regression (org bleed) - team-scope CRED_EXPIRED carries the credential's own org")
    void teamScopeCredExpiredCarriesCredentialOrg() {
        Credential c = credentialWithOrg(42L, "39", "org-team-7");
        when(credentialRepository.findOAuth2CredentialsExpiringBefore(any(Instant.class)))
                .thenReturn(List.of(c));
        when(oAuth2Service.refreshToken(42L, "39"))
                .thenThrow(new RefreshTerminalException(
                        RefreshErrorBucket.TERMINAL_USER, "invalid_grant", 400, "rejected"));
        lenient().when(notificationClient.emit(any())).thenReturn(true);

        scheduler.refreshExpiringTokens();

        ArgumentCaptor<NotificationEmitRequest> captor = ArgumentCaptor.forClass(NotificationEmitRequest.class);
        verify(notificationClient).emit(captor.capture());
        // Pre-fix the org was never set -> the org-scoped notification row was dropped by the
        // fail-loud listener on the scheduler thread. It must now carry the credential's org.
        assertThat(captor.getValue().getOrganizationId()).isEqualTo("org-team-7");
    }

    @Test
    @DisplayName("Regression (org bleed) - personal-scope CRED_EXPIRED routes to the owner's personal org")
    void personalScopeCredExpiredRoutesToPersonalOrg() {
        UUID personalOrg = UUID.fromString("22222222-2222-2222-2222-222222222222");
        Organization org = mock(Organization.class);
        when(org.getId()).thenReturn(personalOrg);
        when(organizationRepository.findByOwnerIdAndIsPersonalTrue(39L)).thenReturn(Optional.of(org));

        Credential c = credential(42L, "39"); // legacy ctor -> organizationId null (personal scope)
        when(credentialRepository.findOAuth2CredentialsExpiringBefore(any(Instant.class)))
                .thenReturn(List.of(c));
        when(oAuth2Service.refreshToken(42L, "39"))
                .thenThrow(new RefreshTerminalException(
                        RefreshErrorBucket.TERMINAL_USER, "invalid_grant", 400, "rejected"));
        lenient().when(notificationClient.emit(any())).thenReturn(true);

        scheduler.refreshExpiringTokens();

        ArgumentCaptor<NotificationEmitRequest> captor = ArgumentCaptor.forClass(NotificationEmitRequest.class);
        verify(notificationClient).emit(captor.capture());
        assertThat(captor.getValue().getOrganizationId()).isEqualTo(personalOrg.toString());
    }
}
