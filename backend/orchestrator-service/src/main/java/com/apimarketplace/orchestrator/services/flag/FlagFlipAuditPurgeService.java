package com.apimarketplace.orchestrator.services.flag;

import com.apimarketplace.orchestrator.repository.FlagFlipAuditRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Hourly retention purge for {@code orchestrator.flag_flip_audit} (P2.1.7).
 *
 * <p>Modeled on the trigger-service {@code TriggerStateAuditLogPurgeService}
 * (commit {@code 8c4857e8c}). Cross-service "extension" is forbidden by
 * CLAUDE.md schema-isolation: trigger-suspend audit lives in the {@code trigger}
 * schema; flag_flip_audit lives in {@code orchestrator}. So this is a NEW
 * service that follows the same pattern, not a literal extension.
 *
 * <p>Hourly cadence (vs the trigger purge's daily 03:00) because the hot-path
 * use case admits per-tenant flag oscillation during debugging sessions - keeping
 * the purge interval shorter prevents the table from accumulating intra-day
 * noise. ShedLock-singleton ensures only one replica runs the purge per hour.
 *
 * <p>{@code LIMIT 10000} per batch bounds the row-lock window. Multiple batches
 * loop until the deletion count drops to zero, draining backlogs without
 * stalling concurrent flag flips.
 */
@Service
public class FlagFlipAuditPurgeService {

    private static final Logger logger = LoggerFactory.getLogger(FlagFlipAuditPurgeService.class);
    private static final int BATCH_SIZE = 10_000;

    private final FlagFlipAuditRepository repository;
    private final long retentionDays;

    public FlagFlipAuditPurgeService(
            FlagFlipAuditRepository repository,
            @Value("${flag-flip-audit.retention-days:90}") long retentionDays) {
        this.repository = repository;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "0 17 * * * *", zone = "UTC")
    @SchedulerLock(name = "flag_flip_audit_purge", lockAtMostFor = "PT15M")
    @Transactional
    public void purgeOldAuditLog() {
        purgeNow();
    }

    /**
     * Synchronous purge entry point. Runs in a loop, deleting up to
     * {@code BATCH_SIZE} rows per iteration, until the cutoff drains.
     * Returns the total rows deleted; {@code -1} on failure.
     */
    @Transactional
    public int purgeNow() {
        Instant cutoff = Instant.now().minus(Duration.of(retentionDays, ChronoUnit.DAYS));
        int totalDeleted = 0;
        try {
            int deletedInBatch;
            do {
                deletedInBatch = repository.deletePurgeBatch(cutoff, BATCH_SIZE);
                totalDeleted += deletedInBatch;
            } while (deletedInBatch == BATCH_SIZE);

            if (totalDeleted > 0) {
                logger.info("[flag-flip-audit-purge] removed {} row(s) older than {} days (cutoff: {})",
                        totalDeleted, retentionDays, cutoff);
            } else {
                logger.debug("[flag-flip-audit-purge] no rows to remove (cutoff: {})", cutoff);
            }
            return totalDeleted;
        } catch (Exception e) {
            logger.warn("[flag-flip-audit-purge] purge failed (will retry next hour): {}", e.getMessage());
            return -1;
        }
    }
}
