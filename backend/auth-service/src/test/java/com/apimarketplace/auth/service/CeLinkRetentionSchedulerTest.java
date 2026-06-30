package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.CeLink;
import com.apimarketplace.auth.domain.CeLinkHeartbeat;
import com.apimarketplace.auth.repository.CeLinkHeartbeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CeLinkRetentionScheduler")
class CeLinkRetentionSchedulerTest {

    @Mock private CeLinkHeartbeatRepository heartbeatRepository;
    @Mock private CeLinkService ceLinkService;

    private CeLinkRetentionScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new CeLinkRetentionScheduler(heartbeatRepository, ceLinkService, 90L);
    }

    @Test
    @DisplayName("sweepRevokesEveryStaleInstall - issues an adminRevoke per row with SYSTEM reason")
    void revokes_every_stale_install() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        CeLinkHeartbeat hA = new CeLinkHeartbeat(a, Instant.now().minusSeconds(1_000_000L), "h", 1, "v");
        CeLinkHeartbeat hB = new CeLinkHeartbeat(b, Instant.now().minusSeconds(1_000_000L), "h", 1, "v");
        when(heartbeatRepository.findStaleActive(any(Instant.class))).thenReturn(List.of(hA, hB));
        when(ceLinkService.adminRevoke(any(UUID.class), eq(CeLink.RevokeReason.SYSTEM),
                eq(null), any(RequestAuditContext.class))).thenReturn(true);

        scheduler.sweepStaleInstalls();

        verify(ceLinkService, times(2)).adminRevoke(any(UUID.class), eq(CeLink.RevokeReason.SYSTEM),
                eq(null), any(RequestAuditContext.class));
    }

    @Test
    @DisplayName("noopWhenNoStaleInstalls - empty result set short-circuits the revoke loop")
    void noop_when_no_stale() {
        when(heartbeatRepository.findStaleActive(any(Instant.class))).thenReturn(List.of());

        scheduler.sweepStaleInstalls();

        verify(ceLinkService, never()).adminRevoke(any(UUID.class), any(CeLink.RevokeReason.class),
                any(), any(RequestAuditContext.class));
    }

    @Test
    @DisplayName("perInstallFailureDoesNotAbortTheSweep - next entry still processed (idempotent on next run)")
    void per_install_failure_continues() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        CeLinkHeartbeat hA = new CeLinkHeartbeat(a, Instant.now().minusSeconds(1_000_000L), "h", 1, "v");
        CeLinkHeartbeat hB = new CeLinkHeartbeat(b, Instant.now().minusSeconds(1_000_000L), "h", 1, "v");
        when(heartbeatRepository.findStaleActive(any(Instant.class))).thenReturn(List.of(hA, hB));
        when(ceLinkService.adminRevoke(eq(a), eq(CeLink.RevokeReason.SYSTEM),
                eq(null), any(RequestAuditContext.class))).thenThrow(new RuntimeException("DB hiccup"));
        when(ceLinkService.adminRevoke(eq(b), eq(CeLink.RevokeReason.SYSTEM),
                eq(null), any(RequestAuditContext.class))).thenReturn(true);

        scheduler.sweepStaleInstalls();

        // Both attempts made; the b-row succeeded.
        verify(ceLinkService, times(2)).adminRevoke(any(UUID.class), eq(CeLink.RevokeReason.SYSTEM),
                eq(null), any(RequestAuditContext.class));
    }

    @Test
    @DisplayName("computesCutoffFromConfiguredStaleAfterDays - 90 days back from now (default)")
    void cutoff_matches_config() {
        when(heartbeatRepository.findStaleActive(any(Instant.class))).thenReturn(List.of());

        scheduler.sweepStaleInstalls();

        ArgumentCaptor<Instant> cutoffCap = ArgumentCaptor.forClass(Instant.class);
        verify(heartbeatRepository).findStaleActive(cutoffCap.capture());
        Instant expected = Instant.now().minusSeconds(90L * 86_400L);
        long deltaSec = Math.abs(cutoffCap.getValue().getEpochSecond() - expected.getEpochSecond());
        assertThat(deltaSec).isLessThanOrEqualTo(60L);
    }
}
