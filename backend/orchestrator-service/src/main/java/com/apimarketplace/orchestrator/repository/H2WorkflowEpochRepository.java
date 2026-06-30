package com.apimarketplace.orchestrator.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;

/**
 * H2-compatible override of {@link WorkflowEpochRepository} for E2E tests.
 *
 * H2 does not support PostgreSQL-specific {@code ON CONFLICT ... DO UPDATE SET count = count + 1}.
 * This version uses H2's {@code MERGE INTO} syntax which acts as an upsert.
 *
 * Activated only when {@code orchestrator.mock.enabled=true} (E2E test profile).
 */
@Repository
@Primary
@ConditionalOnProperty(name = "orchestrator.mock.enabled", havingValue = "true")
public class H2WorkflowEpochRepository extends WorkflowEpochRepository {

    private static final Logger logger = LoggerFactory.getLogger(H2WorkflowEpochRepository.class);

    private static final String DEFAULT_TRIGGER_ID = "trigger:default";

    private static final String H2_MERGE_SQL = """
            MERGE INTO workflow_epochs (run_id, trigger_id, epoch, entry_type, entry_key, status, count)
            KEY (run_id, trigger_id, epoch, entry_type, entry_key, status)
            VALUES (?, ?, ?, ?, ?, ?, 1)
            """;

    /**
     * SQL to increment count for an existing row.
     * Used after MERGE to handle the "increment" semantics that MERGE doesn't natively support.
     */
    private static final String H2_INCREMENT_SQL = """
            UPDATE workflow_epochs
            SET count = count + 1
            WHERE run_id = ? AND trigger_id = ? AND epoch = ? AND entry_type = ? AND entry_key = ? AND status = ?
            AND count > 0
            """;

    /**
     * UPDATE-first leg of the header upsert. Mirrors PG's
     * {@code ON CONFLICT DO UPDATE SET epoch_state = …, updated_at = NOW()} -
     * critically does NOT touch {@code started_at} or {@code is_active} on
     * conflict, so the row's first-fire timestamp is preserved across every
     * subsequent state-rewrite. Required so {@code MAX(started_at)} per run
     * (used by {@code getLatestEpochStartedAtByRunIds}) reflects when each
     * epoch first fired, not when its state was last serialized.
     */
    private static final String H2_UPDATE_HEADER_SQL = """
            UPDATE workflow_epochs
            SET epoch_state = ?, updated_at = CURRENT_TIMESTAMP
            WHERE run_id = ? AND trigger_id = ? AND epoch = ? AND entry_type = 'EPOCH_HEADER'
            """;

    private static final String H2_INSERT_HEADER_SQL = """
            INSERT INTO workflow_epochs (run_id, trigger_id, epoch, entry_type, entry_key, status, epoch_state, is_active, started_at, updated_at)
            VALUES (?, ?, ?, 'EPOCH_HEADER', '_', '_', ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """;

    private static final String H2_CLOSE_HEADER_SQL = """
            UPDATE workflow_epochs
            SET is_active = FALSE, closed_at = CURRENT_TIMESTAMP, epoch_state = ?,
                duration_ms = ?, updated_at = CURRENT_TIMESTAMP
            WHERE run_id = ? AND trigger_id = ? AND epoch = ? AND entry_type = 'EPOCH_HEADER'
            """;

    public H2WorkflowEpochRepository(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }

    @Override
    public void upsert(String runId, String triggerId, int epoch, String entryType, String entryKey, String status) {
        String tid = triggerId != null ? triggerId : DEFAULT_TRIGGER_ID;
        // Try increment first (most common case after first insert)
        int updated = getJdbcTemplate().update(H2_INCREMENT_SQL, runId, tid, epoch, entryType, entryKey, status);
        if (updated == 0) {
            // Row doesn't exist yet - insert with count=1
            try {
                getJdbcTemplate().update(H2_MERGE_SQL, runId, tid, epoch, entryType, entryKey, status);
            } catch (Exception e) {
                // Race condition: another thread inserted between our check and insert - just increment
                getJdbcTemplate().update(H2_INCREMENT_SQL, runId, tid, epoch, entryType, entryKey, status);
            }
        }
    }

    @Override
    public void upsertBatch(String runId, String triggerId, int epoch, String entryType, Map<String, String> entries) {
        if (entries == null || entries.isEmpty()) return;

        for (Map.Entry<String, String> entry : entries.entrySet()) {
            upsert(runId, triggerId, epoch, entryType, entry.getKey(), entry.getValue());
        }
    }

    @Override
    public void upsertHeader(String runId, String triggerId, int epoch, String epochStateJson) {
        String tid = triggerId != null ? triggerId : DEFAULT_TRIGGER_ID;
        // UPDATE-first preserves started_at on every state rewrite - matches PG's
        // ON CONFLICT DO UPDATE that only touches epoch_state + updated_at.
        // The previous H2 MERGE re-stamped started_at to CURRENT_TIMESTAMP on every
        // call, which silently broke the MAX(started_at) per run contract used by
        // getLatestEpochStartedAtByRunIds (and any future "first fire" semantics).
        int updated = getJdbcTemplate().update(H2_UPDATE_HEADER_SQL, epochStateJson, runId, tid, epoch);
        if (updated == 0) {
            try {
                getJdbcTemplate().update(H2_INSERT_HEADER_SQL, runId, tid, epoch, epochStateJson);
            } catch (DataIntegrityViolationException e) {
                // Race: another thread inserted between UPDATE and INSERT - fall back
                // to UPDATE so this caller's epoch_state still lands. Narrowed catch
                // (was Exception) so non-race failures (NOT NULL, FK, schema drift)
                // surface instead of being silently retried. DuplicateKeyException
                // (the canonical race signal Spring throws via SQLErrorCodeSQLExceptionTranslator)
                // is a subclass of DataIntegrityViolationException so it's covered.
                int retried = getJdbcTemplate().update(H2_UPDATE_HEADER_SQL, epochStateJson, runId, tid, epoch);
                if (retried == 0) {
                    logger.warn("[H2 upsertHeader] race fallback UPDATE matched 0 rows for runId={}, triggerId={}, epoch={} - write lost", runId, tid, epoch);
                }
            }
        }
    }

    @Override
    public void closeEpochHeader(String runId, String triggerId, int epoch, String epochStateJson, long durationMs) {
        String tid = triggerId != null ? triggerId : DEFAULT_TRIGGER_ID;
        getJdbcTemplate().update(H2_CLOSE_HEADER_SQL, epochStateJson, durationMs, runId, tid, epoch);
    }
}
