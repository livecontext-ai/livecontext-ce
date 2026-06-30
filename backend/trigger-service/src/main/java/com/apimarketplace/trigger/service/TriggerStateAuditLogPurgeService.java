package com.apimarketplace.trigger.service;

import com.apimarketplace.trigger.repository.TriggerStateAuditLogRepository;
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
 * Daily retention purge for {@code trigger.trigger_state_audit_log}.
 *
 * <p>V169 created the audit log table without any TTL or partition. Without
 * a purge job, the table grows unbounded - production-day-1 problem the
 * Phase-5 migration audit flagged. This service runs once per day at 03:00
 * UTC and deletes rows older than the configured retention window
 * ({@code trigger.audit-log.retention-days}, default 30).
 *
 * <p>Single-replica safety: the @Scheduled annotation triggers on every
 * replica, but the DELETE is idempotent (rows older than cutoff are
 * deleted; second replica deletes nothing because the first already cleared
 * them). For multi-replica deployments at scale, swap to ShedLock - but
 * pre-prod single-replica is fine without it.
 *
 * <p>Time bounds: when the table is empty or all rows are recent, the
 * DELETE matches 0 rows and returns immediately. When a backlog exists
 * (e.g. retention bumped from 30→7 days), the first run deletes a large
 * slice; subsequent daily runs delete only the day's worth. Bulk DELETE
 * uses a native query (not load-then-delete) - see
 * {@link TriggerStateAuditLogRepository#deleteAllByCreatedAtBefore}.
 *
 * <p>Cron expression {@code 0 0 3 * * *} (UTC): off-hours for most
 * regions, avoids contention with peak-trafic trigger writes.
 */
@Service
public class TriggerStateAuditLogPurgeService {

    private static final Logger logger = LoggerFactory.getLogger(TriggerStateAuditLogPurgeService.class);

    private final TriggerStateAuditLogRepository auditLogRepository;
    private final long retentionDays;

    public TriggerStateAuditLogPurgeService(
            TriggerStateAuditLogRepository auditLogRepository,
            @Value("${trigger.audit-log.retention-days:365}") long retentionDays) {
        this.auditLogRepository = auditLogRepository;
        this.retentionDays = retentionDays;
    }

    /**
     * Daily purge - 03:00 UTC. Removes rows older than the retention
     * window. Logs the deletion count at INFO; logs failures at WARN
     * (the next day's run will retry).
     *
     * <p>v5: bumped retention default 30d → 365d for forensic coverage of
     * the F4 PUB-HIJACK class. Added {@code @SchedulerLock} so multi-replica
     * deploys don't double-delete; the single-replica claim above is no
     * longer load-bearing.
     */
    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    @SchedulerLock(name = "AuditLogPurge", lockAtMostFor = "PT15M", lockAtLeastFor = "PT5M")
    @Transactional
    public void purgeOldAuditLog() {
        purgeNow();
    }

    /**
     * Batched purge entry point - exposed for unit tests + manual ops
     * (admin REST endpoint, debug shell). Returns the total number of rows
     * deleted across all batches; -1 on failure.
     *
     * <p>v5 (audit C tech debt #9): switched from a single bulk DELETE to a
     * batched-LIMIT loop. The previous bulk pattern could lock the table for
     * minutes on a fresh retention bump (e.g. 30d → 365d created a 12× backlog).
     * Each pass deletes at most {@code BATCH_SIZE} rows; the loop exits when
     * a pass returns less than the batch size (no more eligible rows).
     */
    private static final int BATCH_SIZE = 10_000;

    public int purgeNow() {
        Instant cutoff = Instant.now().minus(Duration.of(retentionDays, ChronoUnit.DAYS));
        int total = 0;
        try {
            int deleted;
            do {
                deleted = deleteOneBatch(cutoff);
                total += deleted;
                if (deleted > 0) {
                    logger.info("[audit-log-purge] batch removed {} row(s) older than {} days (cutoff: {}, running total: {})",
                            deleted, retentionDays, cutoff, total);
                }
            } while (deleted == BATCH_SIZE);
            if (total == 0) {
                logger.debug("[audit-log-purge] no rows to remove (cutoff: {})", cutoff);
            } else {
                logger.info("[audit-log-purge] purge complete: total {} row(s) removed", total);
            }
            return total;
        } catch (Exception e) {
            logger.warn("[audit-log-purge] purge failed after {} rows (will retry tomorrow): {}", total, e.getMessage());
            return -1;
        }
    }

    /**
     * Single batch, own transaction so each batch commits independently. This
     * lets a failure on batch N preserve the deletions from batches 1..N-1.
     */
    @Transactional
    protected int deleteOneBatch(Instant cutoff) {
        return auditLogRepository.deleteOldestBatch(cutoff, BATCH_SIZE);
    }
}
