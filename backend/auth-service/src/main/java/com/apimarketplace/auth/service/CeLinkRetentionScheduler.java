package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.CeLink;
import com.apimarketplace.auth.domain.CeLinkHeartbeat;
import com.apimarketplace.auth.repository.CeLinkHeartbeatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Auto-revokes CE installs whose heartbeat has been silent for too long
 * (default 90 days, configurable). Doc §1 - REVOKED rows persist in the
 * registry but with {@code status='REVOKED'} so historical attribution
 * survives; the install_id no longer matches ACTIVE on mandatory-header
 * checks.
 *
 * <p>Runs daily. {@code @ConditionalOnProperty(cloud-link.retention.enabled=true)}
 * - disabled in tests + embedded CE. Phase-1 deployment is single-replica; once
 * we scale, swap the plain {@code @Scheduled} for ShedLock to keep two replicas
 * from racing on the same revoke (the work itself is idempotent so the worst
 * case is duplicate audit rows).
 *
 * <p>Best-effort: a per-install failure is logged + skipped; the loop moves on.
 * The next daily run picks up anything missed.
 */
@Component
@ConditionalOnExpression("'${auth.mode:keycloak}' == 'keycloak' && '${cloud-link.retention.enabled:false}' == 'true'")
public class CeLinkRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(CeLinkRetentionScheduler.class);

    private final CeLinkHeartbeatRepository heartbeatRepository;
    private final CeLinkService ceLinkService;
    private final long staleAfterDays;

    public CeLinkRetentionScheduler(CeLinkHeartbeatRepository heartbeatRepository,
                                    CeLinkService ceLinkService,
                                    @Value("${cloud-link.retention.stale-after-days:90}") long staleAfterDays) {
        this.heartbeatRepository = heartbeatRepository;
        this.ceLinkService = ceLinkService;
        this.staleAfterDays = staleAfterDays;
    }

    /**
     * Runs daily at 03:15 UTC (low-traffic window). Cron expression in UTC by
     * convention - Spring honors the JVM TZ unless one is set via property; we
     * set it explicitly to avoid DST surprises.
     */
    @Scheduled(cron = "${cloud-link.retention.cron:0 15 3 * * *}", zone = "UTC")
    public void sweepStaleInstalls() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(staleAfterDays));
        List<CeLinkHeartbeat> stale = heartbeatRepository.findStaleActive(cutoff);
        if (stale.isEmpty()) {
            log.debug("CeLinkRetentionScheduler: no stale installs past cutoff={} ({} days)", cutoff, staleAfterDays);
            return;
        }
        int revoked = 0;
        for (CeLinkHeartbeat hb : stale) {
            try {
                boolean ok = ceLinkService.adminRevoke(
                        hb.getInstallId(),
                        CeLink.RevokeReason.SYSTEM,
                        null,                         // SYSTEM actor - no human user
                        RequestAuditContext.none());
                if (ok) {
                    revoked++;
                }
            } catch (RuntimeException perInstallFailure) {
                log.warn("CeLinkRetentionScheduler: failed to revoke installId={} ({}) - will retry next sweep",
                        hb.getInstallId(), perInstallFailure.getMessage());
            }
        }
        log.info("CeLinkRetentionScheduler: revoked {}/{} stale installs (cutoff={})",
                revoked, stale.size(), cutoff);
    }
}
