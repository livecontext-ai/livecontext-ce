package com.apimarketplace.publication.service;

import com.apimarketplace.publication.domain.CeCloudLinkEntity;
import com.apimarketplace.publication.repository.CeCloudLinkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Periodically pings the cloud {@code /api/ce-link/{installId}/heartbeat} endpoint
 * for every linked tenant. Drives the doc §1 #27 audit cadence on the cloud side
 * (NETWORK_CHANGE on IP change, HEARTBEAT every 24h or every 1000 calls) and gives
 * the cloud the liveness signal that backs {@code last_seen_at} on /mine.
 *
 * <p>One @Scheduled, no per-tenant thread fanout - the CE side typically links a
 * single tenant per install, so iteration cost is trivial. If a deployment ever
 * grows to thousands of linked tenants per install, swap the linear loop for an
 * @Async dispatch.
 *
 * <p>Failures are absorbed inside {@link CloudLinkService#sendHeartbeat} (transient
 * network blips, token-fetch failures, etc.) - this loop never propagates anything
 * upstream. The scheduler thread MUST survive whatever the cloud throws back.
 */
public class CeCloudLinkHeartbeatScheduler {

    private static final Logger logger = LoggerFactory.getLogger(CeCloudLinkHeartbeatScheduler.class);

    private final CeCloudLinkRepository cloudLinkRepository;
    private final CloudLinkService cloudLinkService;

    public CeCloudLinkHeartbeatScheduler(CeCloudLinkRepository cloudLinkRepository,
                                         CloudLinkService cloudLinkService) {
        this.cloudLinkRepository = cloudLinkRepository;
        this.cloudLinkService = cloudLinkService;
    }

    /**
     * Default cadence: every 5 minutes. The cloud side caps the audit-row rate at
     * 1/24h per stable IP regardless of how often we ping (§1 #27), so the actual
     * audit-table cost is bounded by the cadence rules, not by this schedule.
     */
    @Scheduled(fixedDelayString = "${cloud-link.heartbeat.interval-minutes:5}",
               initialDelayString = "${cloud-link.heartbeat.initial-delay-minutes:1}",
               timeUnit = TimeUnit.MINUTES)
    public void sweepLinkedTenants() {
        List<CeCloudLinkEntity> all = cloudLinkRepository.findAll();
        if (all.isEmpty()) {
            logger.debug("CeCloudLinkHeartbeatScheduler: no linked tenants - skipping tick");
            return;
        }
        int ok = 0, revoked = 0, errors = 0;
        for (CeCloudLinkEntity link : all) {
            try {
                CloudLinkService.HeartbeatOutcome outcome = cloudLinkService.sendHeartbeat(link);
                switch (outcome) {
                    case OK, REGISTERED -> ok++;
                    case REVOKED, NOT_FOUND -> revoked++;
                    default -> errors++;
                }
            } catch (RuntimeException unexpected) {
                // sendHeartbeat already swallows known categories; this is a last-line
                // catch so the loop can move on to the next tenant.
                errors++;
                logger.warn("CeCloudLinkHeartbeatScheduler unexpected error for tenant {}: {}",
                        link.getTenantId(), unexpected.getMessage());
            }
        }
        logger.debug("CeCloudLinkHeartbeatScheduler tick: ok={} revoked={} errors={} (total {})",
                ok, revoked, errors, all.size());
    }
}
