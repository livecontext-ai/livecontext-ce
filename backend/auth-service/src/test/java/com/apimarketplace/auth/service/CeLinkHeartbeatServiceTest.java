package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.CeLink;
import com.apimarketplace.auth.domain.CeLinkAudit;
import com.apimarketplace.auth.domain.CeLinkHeartbeat;
import com.apimarketplace.auth.repository.CeLinkHeartbeatRepository;
import com.apimarketplace.auth.repository.CeLinkRepository;
import com.apimarketplace.common.plan.CeLinkAccessResult;
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
    @Mock private CeLinkService ceLinkService;

    private CeLinkHeartbeatService service;

    private static final Long CALLER_ID = 42L;
    private static final UUID INSTALL = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String CE_VERSION = "1.4.0";
    private static final String IP = "203.0.113.5";

    @BeforeEach
    void setUp() {
        service = new CeLinkHeartbeatService(ceLinkRepository, heartbeatRepository, ipHashService,
                auditService, ceLinkService);
        // Default: the governing plan is paid, so the existing heartbeat behavior is exercised.
        // Lenient - the NOT_FOUND / already-REVOKED paths return before the check.
        lenient().when(ceLinkService.planAccess(CALLER_ID)).thenReturn(CeLinkAccessResult.active("PRO"));
    }

    @Test
    @DisplayName("returns NOT_FOUND when install_id is not in caller's namespace (no enumeration oracle)")
    void not_found_when_install_unknown() {
        when(ceLinkRepository.findByInstallIdAndUserId(INSTALL, CALLER_ID)).thenReturn(Optional.empty());

        CeLinkHeartbeatService.Outcome outcome = service.heartbeat(CALLER_ID, INSTALL, CE_VERSION, IP).outcome();

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

        CeLinkHeartbeatService.Outcome outcome = service.heartbeat(CALLER_ID, INSTALL, CE_VERSION, IP).outcome();

        assertThat(outcome).isEqualTo(CeLinkHeartbeatService.Outcome.REVOKED);
        verify(heartbeatRepository, never()).saveAndFlush(any());
        verify(auditService, never()).record(any(), any(), any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("regression: a link whose account is not on a paid plan is SUSPENDED, never revoked and never logged out")
    void plan_required_suspends_without_revoking() {
        CeLink link = new CeLink(INSTALL, CALLER_ID, "L");
        when(ceLinkRepository.findByInstallIdAndUserId(INSTALL, CALLER_ID)).thenReturn(Optional.of(link));
        when(ceLinkService.planAccess(CALLER_ID)).thenReturn(CeLinkAccessResult.planRequired("FREE"));
        when(heartbeatRepository.findById(INSTALL)).thenReturn(Optional.empty());
        when(ipHashService.hashWithCurrent(INSTALL, IP)).thenReturn(new IpHashService.HashResult("hash-v1", 1));

        CeLinkHeartbeatService.Result result = service.heartbeat(CALLER_ID, INSTALL, CE_VERSION, IP);

        assertThat(result.outcome()).isEqualTo(CeLinkHeartbeatService.Outcome.PLAN_REQUIRED);
        assertThat(result.planCode()).isEqualTo("FREE");
        // The old code revoked here (SYSTEM) and the revoke event logged the user out of Keycloak.
        verify(ceLinkService, never()).adminRevoke(any(), any(), any(), any());
        verify(ceLinkService, never()).revoke(any(), any(), any());
        assertThat(link.getStatus()).isEqualTo(CeLink.Status.ACTIVE);
    }

    @Test
    @DisplayName("a suspended link still RECORDS its heartbeat, so the liveness retention sweep never revokes it")
    void plan_required_still_records_heartbeat() {
        when(ceLinkRepository.findByInstallIdAndUserId(INSTALL, CALLER_ID))
                .thenReturn(Optional.of(new CeLink(INSTALL, CALLER_ID, "L")));
        when(ceLinkService.planAccess(CALLER_ID)).thenReturn(CeLinkAccessResult.planRequired("CREDIT_PACK"));
        when(heartbeatRepository.findById(INSTALL)).thenReturn(Optional.empty());
        when(ipHashService.hashWithCurrent(INSTALL, IP)).thenReturn(new IpHashService.HashResult("hash-v1", 1));

        CeLinkHeartbeatService.Result result = service.heartbeat(CALLER_ID, INSTALL, CE_VERSION, IP);

        assertThat(result.outcome()).isEqualTo(CeLinkHeartbeatService.Outcome.PLAN_REQUIRED);
        assertThat(result.planCode()).isEqualTo("CREDIT_PACK");
        ArgumentCaptor<CeLinkHeartbeat> saved = ArgumentCaptor.forClass(CeLinkHeartbeat.class);
        verify(heartbeatRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getLastSeenAt()).isNotNull();
    }

    @Test
    @DisplayName("a suspended link answers OK again on the first heartbeat after the account pays (no re-link)")
    void suspended_link_is_restored_after_upgrade() {
        when(ceLinkRepository.findByInstallIdAndUserId(INSTALL, CALLER_ID))
                .thenReturn(Optional.of(new CeLink(INSTALL, CALLER_ID, "L")));
        when(ceLinkService.planAccess(CALLER_ID))
                .thenReturn(CeLinkAccessResult.planRequired("FREE"), CeLinkAccessResult.active("PRO"));
        when(heartbeatRepository.findById(INSTALL)).thenReturn(Optional.empty());
        when(ipHashService.hashWithCurrent(INSTALL, IP)).thenReturn(new IpHashService.HashResult("hash-v1", 1));

        assertThat(service.heartbeat(CALLER_ID, INSTALL, CE_VERSION, IP).outcome())
                .isEqualTo(CeLinkHeartbeatService.Outcome.PLAN_REQUIRED);
        assertThat(service.heartbeat(CALLER_ID, INSTALL, CE_VERSION, IP).outcome())
                .isEqualTo(CeLinkHeartbeatService.Outcome.OK);
    }

    @Test
    @DisplayName("firstHeartbeatEmitsNetworkChangeAudit - no prior row → audit row at least once per install")
    void first_heartbeat_emits_network_change() {
        when(ceLinkRepository.findByInstallIdAndUserId(INSTALL, CALLER_ID))
                .thenReturn(Optional.of(new CeLink(INSTALL, CALLER_ID, "L")));
        when(heartbeatRepository.findById(INSTALL)).thenReturn(Optional.empty());
        when(ipHashService.hashWithCurrent(INSTALL, IP))
                .thenReturn(new IpHashService.HashResult("hash-v1", 1));

        CeLinkHeartbeatService.Outcome outcome = service.heartbeat(CALLER_ID, INSTALL, CE_VERSION, IP).outcome();

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
