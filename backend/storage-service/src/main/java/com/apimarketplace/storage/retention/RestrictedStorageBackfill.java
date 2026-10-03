package com.apimarketplace.storage.retention;

import com.apimarketplace.common.classification.RestrictedDataPolicy;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Classifies the storage rows written BEFORE data classification existed (CASA LC-066 backlog),
 * so the retention promise also covers them. Idempotent: every step only touches rows still
 * {@code NORMAL} that match, so a re-run finds nothing left to do. It runs 5 minutes after boot,
 * then every 24 hours ({@code data-classification.restricted.backfill.*}).
 *
 * <p>Batched by primary-key keyset, one short transaction per batch: no statement ever touches
 * the whole table, which is why this is a job and not a migration.
 *
 * <p>What it tags, mirroring what the write path tags today:
 * <ol>
 *   <li>workflow step outputs whose catalog metadata names a restricted integration
 *       ({@code iconSlug} in {@link RestrictedDataPolicy#RESTRICTED_INTEGRATIONS});</li>
 *   <li>every other JSON payload (downstream steps, DECISION snapshots) of a run that holds such
 *       an output, stored at or after it: the run-level taint, which the write path applies to
 *       every LATER payload only. A production run's history from before it first read Gmail
 *       keeps its own class and retention. A payload stored without a {@code run_id} (skipped
 *       markers are) is outside this step;</li>
 *   <li>files referenced (FileRef id) by a restricted payload of the same run: Gmail attachments,
 *       Drive downloads.</li>
 * </ol>
 * A newly tagged row gets a fresh retention deadline ({@code retention_expires_at = now +
 * retention-days}), never an immediate deletion.
 *
 * <p><b>Retention catch-up (LC-011).</b> Before tagging, every row ALREADY tagged RESTRICTED but
 * with no {@code retention_expires_at} gets {@code created_at + retention-days}: rows written before
 * the column existed (V562), while the purge was disarmed, or by a pod of a previous release during
 * a rollout. Without this they would outlive the window for good once the purge is armed. The
 * window is counted from when the row was written, not from now, so arming the purge reaches
 * everything older than the window. It reads the partial index of RESTRICTED rows with no deadline
 * (V562), so a repeat run with nothing left costs one probe of an empty index.
 *
 * <p><b>Never {@code expires_at}.</b> That column is the caller's TTL, and earlier releases act on
 * it by themselves (generic cleanup, reads, execution-log purge), so a deadline written there
 * could delete restricted data during a rollout or after a rollback even with the purge off. Old
 * code never reads {@code retention_expires_at}.
 *
 * <p>Everything here only TAGS and STAMPS, so it runs whether or not the purge is armed
 * ({@code data-classification.restricted.sweep.enabled}): a stamp deletes nothing, and only
 * {@link RestrictedStorageRetentionSweeper}, gated on that switch, acts on it.
 */
@Component
public class RestrictedStorageBackfill {

    private static final Logger log = LoggerFactory.getLogger(RestrictedStorageBackfill.class);

    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final int retentionDays;
    private final int batchSize;

    public RestrictedStorageBackfill(
            JdbcTemplate jdbc,
            @Value("${data-classification.restricted.backfill.enabled:true}") boolean enabled,
            @Value("${data-classification.restricted.retention-days:" + RestrictedDataPolicy.DEFAULT_RETENTION_DAYS + "}") int retentionDays,
            @Value("${data-classification.restricted.backfill.batch-size:1000}") int batchSize) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.retentionDays = Math.max(1, retentionDays);
        this.batchSize = Math.max(1, batchSize);
    }

    /**
     * What one run did: rows tagged by each rule, and RESTRICTED rows whose missing retention
     * deadline was stamped by the catch-up.
     */
    public record Report(int stepOutputs, int runPayloads, int files, int retentionDeadlinesStamped) {
    }

    /** Once per boot, a few minutes after startup, on one replica. */
    @Scheduled(initialDelayString = "${data-classification.restricted.backfill.initial-delay-ms:300000}",
            fixedDelayString = "${data-classification.restricted.backfill.interval-ms:86400000}")
    @SchedulerLock(name = "restricted_storage_backfill", lockAtMostFor = "PT2H", lockAtLeastFor = "PT10M")
    public void scheduledBackfill() {
        try {
            run(Instant.now());
        } catch (Exception e) {
            log.error("[RestrictedBackfill] storage backfill failed: {}", e.getMessage(), e);
        }
    }

    public Report run(Instant now) {
        if (!enabled) {
            return new Report(0, 0, 0, 0);
        }
        // First, and on its own: the rows the purge would otherwise never reach.
        int deadlinesStamped = stampMissingRetentionDeadlines(now);

        Timestamp deadline = Timestamp.from(now.plus(Duration.ofDays(retentionDays)));
        String integrations = sqlList(RestrictedDataPolicy.RESTRICTED_INTEGRATIONS);

        int stepOutputs = keyset(
                "UPDATE storage.storage s SET data_sensitivity = 'RESTRICTED', "
                        + "retention_expires_at = LEAST(COALESCE(s.retention_expires_at, ?::timestamptz), ?::timestamptz) "
                        + "WHERE s.id = ANY(CAST(? AS uuid[])) AND s.data_sensitivity = 'NORMAL' "
                        + "AND s.storage_type = 'JSON' AND s.s3_key IS NULL "
                        + "AND lower(COALESCE(s.data #>> '{metadata,iconSlug}', "
                        + "  s.data #>> '{output,metadata,iconSlug}', '')) IN (" + integrations + ")",
                deadline);

        int runPayloads = keyset(
                "UPDATE storage.storage s SET data_sensitivity = 'RESTRICTED', "
                        + "retention_expires_at = LEAST(COALESCE(s.retention_expires_at, ?::timestamptz), ?::timestamptz) "
                        + "WHERE s.id = ANY(CAST(? AS uuid[])) AND s.data_sensitivity = 'NORMAL' "
                        + "AND s.storage_type = 'JSON' AND s.s3_key IS NULL AND s.run_id IS NOT NULL "
                        + "AND EXISTS (SELECT 1 FROM storage.storage r WHERE r.run_id = s.run_id "
                        + "  AND r.data_sensitivity = 'RESTRICTED' AND r.created_at <= s.created_at)",
                deadline);

        int files = keyset(
                "UPDATE storage.storage s SET data_sensitivity = 'RESTRICTED', "
                        + "retention_expires_at = LEAST(COALESCE(s.retention_expires_at, ?::timestamptz), ?::timestamptz) "
                        + "WHERE s.id = ANY(CAST(? AS uuid[])) AND s.data_sensitivity = 'NORMAL' "
                        + "AND s.s3_key IS NOT NULL AND s.run_id IS NOT NULL "
                        + "AND EXISTS (SELECT 1 FROM storage.storage r WHERE r.run_id = s.run_id "
                        + "  AND r.data_sensitivity = 'RESTRICTED' AND r.storage_type = 'JSON' "
                        + "  AND r.data::text LIKE '%' || s.id::text || '%')",
                deadline);

        if (stepOutputs + runPayloads + files > 0) {
            log.info("[RestrictedBackfill] storage: tagged {} step output(s), {} other run payload(s), {} file(s)",
                    stepOutputs, runPayloads, files);
        }
        return new Report(stepOutputs, runPayloads, files, deadlinesStamped);
    }

    /**
     * Gives every RESTRICTED row with no retention deadline {@code retention_expires_at =
     * created_at + retention-days} (a row with no {@code created_at} counts from {@code now}).
     * Touches nothing else: NORMAL rows, rows that already carry a deadline and {@code expires_at}
     * (the caller's TTL, which earlier releases enforce on their own) are outside the statement, so
     * a repeat run stamps nothing and a rollback finds nothing it would act on. Batched, one short
     * statement per batch.
     *
     * @return rows stamped
     */
    int stampMissingRetentionDeadlines(Instant now) {
        Timestamp nowTs = Timestamp.from(now);
        int stamped = 0;
        for (int round = 0; round < 100_000; round++) {
            int updated = jdbc.update(
                    "UPDATE storage.storage SET retention_expires_at = "
                            // 24-hour days, as the write path counts them (Duration.ofDays): a
                            // calendar 'day' would follow the session time zone across DST.
                            + "COALESCE(created_at, ?::timestamptz) + CAST(? AS integer) * INTERVAL '24 hours' "
                            + "WHERE id IN (SELECT id FROM storage.storage "
                            + "  WHERE data_sensitivity = 'RESTRICTED' AND retention_expires_at IS NULL LIMIT ?) "
                            + "AND data_sensitivity = 'RESTRICTED' AND retention_expires_at IS NULL",
                    nowTs, retentionDays, batchSize);
            stamped += updated;
            if (updated < batchSize) {
                break;
            }
        }
        if (stamped > 0) {
            log.info("[RestrictedBackfill] storage: stamped a retention deadline (created_at + {} days) on {} "
                    + "restricted row(s) written without one", retentionDays, stamped);
        }
        return stamped;
    }

    /** Applies {@code update} to the table in primary-key batches; returns rows updated. */
    private int keyset(String update, Timestamp deadline) {
        int updated = 0;
        UUID after = null;
        while (true) {
            List<UUID> ids = after == null
                    ? jdbc.queryForList("SELECT id FROM storage.storage ORDER BY id LIMIT ?", UUID.class, batchSize)
                    : jdbc.queryForList("SELECT id FROM storage.storage WHERE id > ? ORDER BY id LIMIT ?",
                            UUID.class, after, batchSize);
            if (ids.isEmpty()) {
                return updated;
            }
            String[] batch = ids.stream().map(UUID::toString).toArray(String[]::new);
            updated += jdbc.update(update, deadline, deadline, batch);
            after = ids.get(ids.size() - 1);
            if (ids.size() < batchSize) {
                return updated;
            }
        }
    }

    static String sqlList(java.util.Collection<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (!v.matches("[a-z0-9_]+")) {
                throw new IllegalStateException("Unexpected integration identifier: " + v);
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append('\'').append(v).append('\'');
        }
        return sb.toString();
    }
}
