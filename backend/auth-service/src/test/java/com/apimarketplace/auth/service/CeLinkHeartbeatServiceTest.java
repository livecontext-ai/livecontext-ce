package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.CeLink;
import com.apimarketplace.auth.domain.CeLinkAudit;
import com.apimarketplace.auth.domain.CeLinkHeartbeat;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.repository.CeLinkHeartbeatRepository;
import com.apimarketplace.auth.repository.CeLinkRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CeLinkHeartbeatService")
class CeLinkHeartbeatServiceTest {

    @Mock private CeLinkRepository ceLinkRepository;
    @Mock private CeLinkHeartbeatRepository heartbeatRepository;
    @Mock private IpHashService ipHashService;
    @Mock private CeLinkAuditService auditService;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private CeLinkService ceLinkService;
    @Mock private Subscription activeSubscription;

    private CeLinkHeartbeatService service;

    private static final Long CALLER_ID = 42L;
    private static final UUID INSTALL = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String CE_VERSION = "1.4.0";
    private static final String IP = "203.0.113.5";

    @BeforeEach
    void setUp() {
        service = new CeLinkHeartbeatService(ceLinkRepository, heartbeatRepository, ipHashService,
                auditService, subscriptionRepository, ceLinkService);
        // Default: caller has an active subscription, so the entitlement re-check passes
        // and existing heartbeat behavior is exercised. Lenient - the NOT_FOUND / already-
        // REVOKED paths return before the check and never consult it.
        lenient().when(subscriptionRepository.findActiveByUserId(CALLER_ID))
                .thenReturn(Optional.of(activeSubscription));
    }

    @Test
    @DisplayName("returns NOT_FOUND when install_id is not in caller's namespace (no enumeration oracle)")
    void not_found_when_install_unknown() {
        when(ceLinkRepository.findByInstallIdAndUserId(INSTALL, CALLER_ID)).thenReturn(Optional.empty());

        CeLinkHeartbeatService.Outcome outcome = service.heartbeat(CALLER_ID, INSTALL, CE_VERSION, IP);

        assertThat(outcome).isEqualTo(CeLinkHeartbeatService.Outcome.NOT_FOUND);
        verify(heartbeatRepository, never()).saveAndFlush(any());
        verify(auditService, never()).record(any(), any(), any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("returns REVOKED so CE stops heartbeating once the link is revoked")
    void revoked_when_link_revoked() {
        CeLink revoked = new CeLink(INSTALL, CALLER_ID, "X");
        revoked.revoke(CeLink.RevokeReason.USER, CALLER_ID);
        when(ceLinkRepository.findByInstallIdAndUserId(INSTALL, CALLER_ID)).thenReturn(Optional.of(revoked));

        CeLinkHeartbeatService.Outcome outcome = service.heartbeat(CALLER_ID, INSTALL, CE_VERSION, IP);

        assertThat(outcome).isEqualTo(CeLinkHeartbeatService.Outcome.REVOKED);
        verify(heartbeatRepository, never()).saveAndFlush(any());
        verify(auditService, never()).record(any(), any(), any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("revokes the link (→ REVOKED, mapped to 410 GONE) when the cloud account has no active subscription - entitlement lost")
    void revoked_when_no_active_subscription() {
        when(ceLinkRepository.findByInstallIdAndUserId(INSTALL, CALLER_ID))
                .thenReturn(Optional.of(new CeLink(INSTALL, CALLER_ID, "L")));
        // Entitlement lost: no active (trialing/active) subscription for the caller.
        when(subscriptionRepository.findActiveByUserId(CALLER_ID)).thenReturn(Optional.empty());

        CeLinkHeartbeatService.Outcome outcome = service.heartbeat(CALLER_ID, INSTALL, CE_VERSION, IP);

        assertThat(outcome).isEqualTo(CeLinkHeartbeatService.Outcome.REVOKED);
        // Delegates to the canonical revoke path with a SYSTEM reason.
        verify(ceLinkService).adminRevoke(eq(INSTALL), eq(CeLink.RevokeReason.SYSTEM), any(), any());
        // No heartbeat row is recorded for an unentitled caller.
        verify(heartbeatRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("firstHeartbeatEmitsNetworkChangeAudit - no prior row → audit row at least once per install")
    void first_heartbeat_emits_network_change() {
        when(ceLinkRepository.findByInstallIdAndUserId(INSTALL, CALLER_ID))
                .thenReturn(Optional.of(new CeLink(INSTALL, CALLER_ID, "L")));
        when(heartbeatRepository.findById(INSTALL)).thenReturn(Optional.empty());
        when(ipHashService.hashWithCurrent(INSTALL, IP))
                .thenReturn(new IpHashService.HashResult("hash-v1", 1));

        CeLinkHeartbeatService.Outcome outcome = service.heartbeat(CALLER_ID, INSTALL, CE_VERSION, IP);

        assertThat(outcome).isEqualTo(CeLinkHeartbeatService.Outcome.OK);

        ArgumentCaptor<CeLinkHeartbeat> hbCaptor = ArgumentCaptor.forClass(CeLinkHeartbeat.class);
        verify(heartbeatRepository).saveAndFlush(hbCaptor.capture());
        assertThat(hbCaptor.getValue().getLastSeenIpHash()).isEqualTo("hash-v1");
        assertThat(hbCaptor.getValue().getLastAuditedAt()).isNotNull();      // audit just emitted → recordAuditEmission was called
        assertThat(hbCaptor.getValue().getHeartbeatCountSinceAudit()).isZero();

        verify(auditService).record(
                eq(INSTALL), eq(CALLER_ID),
                eq(CeLinkAudit.ActorRole.OWNER),
                eq(CeLinkAudit.Event.NETWORK_CHANGE),
                eq(1), eq("hash-v1"), any(), any());
    }

    @Test
    @DisplayName("ipChangeEmitsNetworkChangeAndResetsCounter - every IP change is a security signal")
    void ip_change_emits_network_change() {
        CeLinkHeartbeat prior = new CeLinkHeartbeat(INSTALL, Instant.now().minusSeconds(60),
                "old-hash", 1, "1.3.0");
        prior.recordAuditEmission(Instant.now().minusSeconds(60));
        when(ceLinkRepository.findByInstallIdAndUserId(INSTALL, CALLER_ID))
                .thenReturn(Optional.of(new CeLink(INSTALL, CALLER_ID, "L")));
        when(heartbeatRepository.findById(INSTALL)).thenReturn(Optional.of(prior));
        when(ipHashService.hashWithCurrent(INSTALL, IP))
                .thenReturn(new IpHashService.HashResult("new-hash", 1));
        // matches() called with prior.last_seen_ip_hash + prior.key_version - returns false (IP changed).
        when(ipHashService.matches(INSTALL, IP, "old-hash", 1)).thenReturn(false);

        service.heartbeat(CALLER_ID, INSTALL, CE_VERSION, IP);

        verify(auditService).record(any(), any(), any(),
                eq(CeLinkAudit.Event.NETWORK_CHANGE),
                anyInt(), any(), any(), any());
        assertThat(prior.getHeartbeatCountSinceAudit()).isZero();
    }

    @Test
    @DisplayName("sameIpUnderCadenceWindowDoesNOTAudit - stable IP within 24h and below 1000 calls stays silent (audit table stays bounded)")
    void same_ip_under_cadence_does_not_audit() {
        CeLinkHeartbeat prior = new CeLinkHeartbeat(INSTALL, Instant.now().minusSeconds(60),
                "stable-hash", 1, "1.3.0");
        prior.recordAuditEmission(Instant.now().minusSeconds(60));   // last audited 60s ago, well under 24h
        when(ceLinkRepository.findByInstallIdAndUserId(INSTALL, CALLER_ID))
                .thenReturn(Optional.of(new CeLink(INSTALL, CALLER_ID, "L")));
        when(heartbeatRepository.findById(INSTALL)).thenReturn(Optional.of(prior));
        when(ipHashService.hashWithCurrent(INSTALL, IP))
                .thenReturn(new IpHashService.HashResult("stable-hash", 1));
        when(ipHashService.matches(INSTALL, IP, "stable-hash", 1)).thenReturn(true);

        service.heartbeat(CALLER_ID, INSTALL, CE_VERSION, IP);

        verify(auditService, never()).record(any(), any(), any(), any(), anyInt(), any(), any(), any());
        assertThat(prior.getHeartbeatCountSinceAudit()).isEqualTo(1L); // bumped, not reset
    }

    @Test
    @DisplayName("stalenessOver24hEmitsHeartbeatAudit - liveness signal - proves the row is still being touched")
    void staleness_over_24h_emits_heartbeat() {
        CeLinkHeartbeat prior = new CeLinkHeartbeat(INSTALL, Instant.now(), "stable-hash", 1, "1.3.0");
        prior.recordAuditEmission(Instant.now().minus(Duration.ofHours(25)));
        when(ceLinkRepository.findByInstallIdAndUserId(INSTALL, CALLER_ID))
                .thenReturn(Optional.of(new CeLink(INSTALL, CALLER_ID, "L")));
        when(heartbeatRepository.findById(INSTALL)).thenReturn(Optional.of(prior));
        when(ipHashService.hashWithCurrent(INSTALL, IP))
                .thenReturn(new IpHashService.HashResult("stable-hash", 1));
        when(ipHashService.matches(INSTALL, IP, "stable-hash", 1)).thenReturn(true);

        service.heartbeat(CALLER_ID, INSTALL, CE_VERSION, IP);

        ArgumentCaptor<Map<String, Object>> meta = forMapCaptor();
        verify(auditService).record(any(), any(), any(),
                eq(CeLinkAudit.Event.HEARTBEAT), anyInt(), any(), any(), meta.capture());
        assertThat(meta.getValue()).containsEntry("reason", "interval_24h");
    }

    @Test
    @DisplayName("countOver1000EmitsHeartbeatAudit - stable-IP credential abuse pre-detection (§7 threat)")
    void count_over_1000_emits_heartbeat() {
        CeLinkHeartbeat prior = new CeLinkHeartbeat(INSTALL, Instant.now(), "stable-hash", 1, "1.3.0");
        prior.recordAuditEmission(Instant.now().minusSeconds(60));
        // Bump counter to threshold.
        for (long i = 0; i < CeLinkHeartbeatService.AUDIT_CALL_THRESHOLD; i++) {
            prior.applyHeartbeat(Instant.now(), "stable-hash", 1, "1.3.0");
        }
        assertThat(prior.getHeartbeatCountSinceAudit()).isEqualTo(CeLinkHeartbeatService.AUDIT_CALL_THRESHOLD);

        when(ceLinkRepository.findByInstallIdAndUserId(INSTALL, CALLER_ID))
                .thenReturn(Optional.of(new CeLink(INSTALL, CALLER_ID, "L")));
        when(heartbeatRepository.findById(INSTALL)).thenReturn(Optional.of(prior));
        when(ipHashService.hashWithCurrent(INSTALL, IP))
                .thenReturn(new IpHashService.HashResult("stable-hash", 1));
        when(ipHashService.matches(INSTALL, IP, "stable-hash", 1)).thenReturn(true);

        service.heartbeat(CALLER_ID, INSTALL, CE_VERSION, IP);

        ArgumentCaptor<Map<String, Object>> meta = forMapCaptor();
        verify(auditService).record(any(), any(), any(),
                eq(CeLinkAudit.Event.HEARTBEAT), anyInt(), any(), any(), meta.capture());
        assertThat(meta.getValue()).containsEntry("reason", "count_threshold");
        // Audit emitted → counter reset.
        assertThat(prior.getHeartbeatCountSinceAudit()).isZero();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> forMapCaptor() {
        return (ArgumentCaptor<Map<String, Object>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Map.class);
    }
}
