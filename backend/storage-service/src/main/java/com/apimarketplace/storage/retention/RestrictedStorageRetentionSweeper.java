package com.apimarketplace.storage.retention;

import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.common.storage.service.StorageRowCategories;
import com.apimarketplace.common.storage.service.api.QuotaOperations;
import com.apimarketplace.storage.service.file.FileStorageService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Hard-deletes RESTRICTED storage rows (Google restricted-scope data: Gmail, Drive) once their
 * deadline has passed, together with their stored objects (CASA LC-011 / LC-066). The deadline is
 * the earlier of the row's retention deadline ({@code retention_expires_at}, V562) and the caller's
 * own TTL ({@code expires_at}), whichever is set.
 *
 * <p><b>Why a hard delete, and why here.</b> The generic {@code cleanupExpired} only flips a row
 * to DELETED and leaves both the JSON payload and the object behind, which is retention, not
 * deletion. Restricted rows are therefore excluded from it ({@code findExpiredStorages}) and owned
 * by this sweeper, which runs in storage-service because storage-service owns the bucket: object
 * deletion is local, not an internal HTTP hop.
 *
 * <p><b>Order: objects first, rows second.</b> The object keys exist only in the rows. If an
 * object delete fails the row is KEPT, so the next sweep retries it instead of losing track of a
 * paid, unreachable object. A row whose object is gone but whose delete failed is harmless and is
 * retried too (object deletes are idempotent).
 *
 * <p><b>Safe with several replicas and without a lock.</b> Rows are removed with
 * {@code DELETE ... RETURNING}, and the quota is debited only for rows THIS instance actually
 * deleted, so two replicas sweeping the same batch cannot debit a row twice.
 *
 * <p>On by default: the tag only exists on data the platform must not keep, and the window
 * ({@code data-classification.restricted.retention-days}, 30 by default) is applied when the row
 * is written, not here.
 *
 * <p><b>The ONLY destructive step (LC-011).</b> {@code data-classification.restricted.sweep.enabled}
 * gates this delete and nothing else: the retention deadline is stamped on every restricted row
 * whether or not the sweep is armed ({@code StorageService}, and {@link RestrictedStorageBackfill}
 * for rows written without one), and nothing else destructive reads it. Disarmed, a sweep issues no
 * statement at all. Armed, its first pass deletes every restricted row whose deadline has passed,
 * the backlog written while it was disarmed included. The deadline lives in its own column, never
 * in {@code expires_at}, because releases before V562 act on {@code expires_at} by themselves: a
 * rollback therefore finds nothing new to delete.
 */
@Component
public class RestrictedStorageRetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(RestrictedStorageRetentionSweeper.class);

    /**
     * A restricted row's deadline: the earlier of its retention deadline and the caller's TTL
     * (PostgreSQL's LEAST skips a NULL side, and is NULL only when both are). Same expression as
     * the partial index idx_storage_restricted_deadline (V562), so the planner can use it.
     */
    static final String DEADLINE = "LEAST(expires_at, retention_expires_at)";

    private final JdbcTemplate jdbc;
    private final FileStorageService files;
    private final StorageBreakdownService breakdownService;
    private final QuotaOperations quotaService;
    private final boolean enabled;
    private final int batchSize;

    public RestrictedStorageRetentionSweeper(JdbcTemplate jdbc,
                                             FileStorageService files,
                                             StorageBreakdownService breakdownService,
                                             QuotaOperations quotaService,
                                             @Value("${data-classification.restricted.sweep.enabled:true}") boolean enabled,
                                             @Value("${data-classification.restricted.sweep.batch-size:500}") int batchSize) {
        this.jdbc = jdbc;
        this.files = files;
        this.breakdownService = breakdownService;
        this.quotaService = quotaService;
        this.enabled = enabled;
        this.batchSize = Math.max(1, batchSize);
    }

    /** What one sweep did. */
    public record SweepReport(int rowsDeleted, int objectsDeleted, int objectsFailed) {
    }

    /**
     * Every 15 minutes: the window is a promise, so it is enforced close to its deadline. One
     * replica at a time (ShedLock on storage.shedlock): the per-row delete is already safe to race,
     * this only avoids doing the object deletes twice.
     */
    @Scheduled(fixedDelayString = "${data-classification.restricted.sweep.interval-ms:900000}",
            initialDelayString = "${data-classification.restricted.sweep.initial-delay-ms:120000}")
    @SchedulerLock(name = "restricted_storage_retention_sweep", lockAtMostFor = "PT30M")
    public void scheduledSweep() {
        try {
            sweep(Instant.now());
        } catch (Exception e) {
            log.error("[RestrictedRetention] sweep failed: {}", e.getMessage(), e);
        }
    }

    /** One full sweep, batch after batch, until no expired restricted row is left. */
    public SweepReport sweep(Instant now) {
        if (!enabled) {
            return new SweepReport(0, 0, 0);
        }
        int rows = 0;
        int objects = 0;
        int failedObjects = 0;
        // Keyset on (deadline, id): a row whose object or delete failed is SKIPPED for the rest
        // of this sweep (and retried next tick) instead of being selected again at the head of
        // every batch, which would stall the sweep behind it. The deadline expression is exactly
        // the one indexed by idx_storage_restricted_deadline (V562); LEAST ignores a NULL side.
        Timestamp afterDeadline = null;
        UUID afterId = null;
        for (int round = 0; round < 10_000; round++) {
            List<Candidate> batch = afterDeadline == null
                    ? jdbc.query(
                        "SELECT id, s3_key, run_id, " + DEADLINE + " AS deadline FROM storage.storage "
                                + "WHERE data_sensitivity = 'RESTRICTED' AND " + DEADLINE + " < ? "
                                + "ORDER BY " + DEADLINE + ", id LIMIT ?",
                        CANDIDATE, Timestamp.from(now), batchSize)
                    : jdbc.query(
                        "SELECT id, s3_key, run_id, " + DEADLINE + " AS deadline FROM storage.storage "
                                + "WHERE data_sensitivity = 'RESTRICTED' AND " + DEADLINE + " < ? "
                                + "AND (" + DEADLINE + ", id) > (?, ?) "
                                + "ORDER BY " + DEADLINE + ", id LIMIT ?",
                        CANDIDATE, Timestamp.from(now), afterDeadline, afterId, batchSize);
            if (batch.isEmpty()) {
                break;
            }
            Candidate last = batch.get(batch.size() - 1);
            afterDeadline = last.deadline();
            afterId = last.id();
            if (!recordRestrictedSince(batch)) {
                // Nothing of this batch is deleted (objects included): retried next tick.
                if (batch.size() < batchSize) {
                    break;
                }
                continue;
            }
            List<UUID> deletable = new ArrayList<>(batch.size());
            for (Candidate candidate : batch) {
                if (candidate.s3Key() == null || candidate.s3Key().isBlank()) {
                    deletable.add(candidate.id());
                    continue;
                }
                try {
                    // An object that is already gone reports false; the row can go either way.
                    if (files.delete(candidate.s3Key()) || !files.exists(candidate.s3Key())) {
                        objects++;
                        deletable.add(candidate.id());
                    } else {
                        failedObjects++;
                    }
                } catch (Exception e) {
                    failedObjects++;
                    log.warn("[RestrictedRetention] object delete failed for storage row {}: {}",
                            candidate.id(), e.getMessage());
                }
            }
            if (!deletable.isEmpty()) {
                rows += deleteBatchOrRowByRow(deletable);
            }
            if (batch.size() < batchSize) {
                break;
            }
        }
        if (rows > 0 || failedObjects > 0) {
            log.info("[RestrictedRetention] deleted {} restricted storage row(s) and {} object(s); {} object(s) FAILED and remain",
                    rows, objects, failedObjects);
        }
        return new SweepReport(rows, objects, failedObjects);
    }

    /**
     * LC-066: keeps, for every run of the batch, when it first held restricted data, BEFORE any of
     * its restricted rows is deleted. publication-service dates a run's restriction from it to judge
     * a showcase snapshot captured earlier; read off the rows that remain, it would move LATER with
     * every purge (the oldest rows go first) and clear a copy that holds restricted data. An
     * existing value is kept unless this one is earlier (the storage backfill can tag rows older than
     * the first one the write path recorded). Same statement shape as V564's backfill.
     */
    static final String RECORD_RESTRICTED_SINCE =
            "INSERT INTO storage.restricted_run_since (run_id, restricted_since) "
                    + "SELECT run_id, MIN(created_at) FROM storage.storage "
                    + "WHERE data_sensitivity = 'RESTRICTED' AND created_at IS NOT NULL "
                    + "AND run_id = ANY(CAST(? AS varchar[])) GROUP BY run_id "
                    + "ON CONFLICT (run_id) DO UPDATE SET restricted_since = EXCLUDED.restricted_since "
                    + "WHERE storage.restricted_run_since.restricted_since > EXCLUDED.restricted_since";

    /** @return false when the record could not be written: the batch must then not be deleted. */
    private boolean recordRestrictedSince(List<Candidate> batch) {
        String[] runIds = batch.stream().map(Candidate::runId)
                .filter(id -> id != null && !id.isBlank()).distinct().toArray(String[]::new);
        if (runIds.length == 0) {
            return true;
        }
        try {
            jdbc.update(RECORD_RESTRICTED_SINCE, (Object) runIds);
            return true;
        } catch (Exception e) {
            log.warn("[RestrictedRetention] could not record when {} run(s) became restricted, batch kept "
                    + "for the next sweep: {}", runIds.length, e.getMessage());
            return false;
        }
    }

    /**
     * Deletes the batch in one statement; if that fails (a row locked or broken), falls back to
     * one statement per row so a single bad row costs only itself, never the rest of the sweep.
     */
    private int deleteBatchOrRowByRow(List<UUID> ids) {
        try {
            return deleteRowsAndDebit(ids);
        } catch (Exception batchFailure) {
            log.warn("[RestrictedRetention] batch delete failed ({}), retrying row by row", batchFailure.getMessage());
            int deleted = 0;
            for (UUID id : ids) {
                try {
                    deleted += deleteRowsAndDebit(List.of(id));
                } catch (Exception rowFailure) {
                    log.warn("[RestrictedRetention] could not delete restricted storage row {}: {}",
                            id, rowFailure.getMessage());
                }
            }
            return deleted;
        }
    }

    private static final org.springframework.jdbc.core.RowMapper<Candidate> CANDIDATE = (rs, i) ->
            new Candidate((UUID) rs.getObject("id"), rs.getString("s3_key"), rs.getString("run_id"),
                    rs.getTimestamp("deadline"));

    private int deleteRowsAndDebit(List<UUID> ids) {
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Deleted> deleted = jdbc.query(
                "DELETE FROM storage.storage WHERE id IN (" + placeholders + ") "
                        + "RETURNING tenant_id, organization_id, size_bytes, storage_type, source_type, status",
                (rs, i) -> new Deleted(rs.getString("tenant_id"), rs.getString("organization_id"),
                        rs.getLong("size_bytes"), rs.getString("storage_type"), rs.getString("source_type"),
                        rs.getString("status")),
                ids.toArray());
        Set<String> tenants = new LinkedHashSet<>();
        Set<String> organizations = new LinkedHashSet<>();
        for (Deleted row : deleted) {
            // Only ACTIVE rows were ever counted: a soft-deleted row was debited when it was
            // soft-deleted, and debiting it again would drive a billed number below the truth.
            if (!"ACTIVE".equals(row.status())) {
                continue;
            }
            breakdownService.trackDelete(row.tenantId(),
                    StorageRowCategories.categoryFor(row.storageType(), row.sourceType()),
                    row.sizeBytes(), row.organizationId());
            tenants.add(row.tenantId());
            if (row.organizationId() != null && !row.organizationId().isBlank()) {
                organizations.add(row.organizationId());
            }
        }
        tenants.forEach(quotaService::updateUsage);
        organizations.forEach(quotaService::updateOrganizationUsage);
        return deleted.size();
    }

    private record Candidate(UUID id, String s3Key, String runId, Timestamp deadline) {
    }

    private record Deleted(String tenantId, String organizationId, long sizeBytes,
                           String storageType, String sourceType, String status) {
    }
}
