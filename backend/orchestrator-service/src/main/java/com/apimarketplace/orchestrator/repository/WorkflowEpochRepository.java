package com.apimarketplace.orchestrator.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JdbcTemplate-based repository for workflow_epochs table.
 * Uses UPSERT (INSERT ... ON CONFLICT ... DO UPDATE) for O(1) count increments.
 *
 * <p>Supports two row types:
 * <ul>
 *   <li><b>Counter rows</b> (entry_type = 'NODE'/'EDGE'): UPSERT count += 1</li>
 *   <li><b>Header rows</b> (entry_type = 'EPOCH_HEADER'): full epoch state JSONB, is_active, timestamps</li>
 * </ul>
 *
 * For H2 (E2E tests), see {@link H2WorkflowEpochRepository} which overrides
 * with H2-compatible SQL (MERGE INTO instead of ON CONFLICT).
 */
@Repository
public class WorkflowEpochRepository {

    // ═══════════════════════════════════════════════════════════════════════════
    // Counter SQL (NODE/EDGE rows)
    // ═══════════════════════════════════════════════════════════════════════════

    private static final String UPSERT_SQL = """
            INSERT INTO workflow_epochs (run_id, trigger_id, epoch, entry_type, entry_key, status, count)
            VALUES (?, ?, ?, ?, ?, ?, 1)
            ON CONFLICT (run_id, trigger_id, epoch, entry_type, entry_key, status)
            DO UPDATE SET count = workflow_epochs.count + 1
            """;

    private static final String FIND_BY_RUN_AND_EPOCH_SQL = """
            SELECT entry_type, entry_key, status, count
            FROM workflow_epochs
            WHERE run_id = ? AND epoch = ?
            AND entry_type IN ('NODE', 'EDGE')
            """;

    private static final String FIND_BY_RUN_TRIGGER_AND_EPOCH_SQL = """
            SELECT entry_type, entry_key, status, count
            FROM workflow_epochs
            WHERE run_id = ? AND trigger_id = ? AND epoch = ?
            AND entry_type IN ('NODE', 'EDGE')
            """;

    private static final String ACCUMULATED_COUNTS_SQL = """
            SELECT entry_type, entry_key, status, SUM(count) AS total
            FROM workflow_epochs
            WHERE run_id = ? AND entry_type IN ('NODE', 'EDGE')
            GROUP BY entry_type, entry_key, status
            """;

    private static final String ACCUMULATED_COUNTS_BY_TRIGGER_SQL = """
            SELECT entry_type, entry_key, status, SUM(count) AS total
            FROM workflow_epochs
            WHERE run_id = ? AND trigger_id = ? AND entry_type IN ('NODE', 'EDGE')
            GROUP BY entry_type, entry_key, status
            """;

    // ═══════════════════════════════════════════════════════════════════════════
    // Header SQL (EPOCH_HEADER rows)
    // ═══════════════════════════════════════════════════════════════════════════

    private static final String UPSERT_HEADER_SQL = """
            INSERT INTO workflow_epochs (run_id, trigger_id, epoch, entry_type, entry_key, status, epoch_state, is_active, started_at, updated_at)
            VALUES (?, ?, ?, 'EPOCH_HEADER', '_', '_', CAST(? AS JSONB), TRUE, NOW(), NOW())
            ON CONFLICT (run_id, trigger_id, epoch, entry_type, entry_key, status)
            DO UPDATE SET epoch_state = CAST(EXCLUDED.epoch_state AS JSONB), updated_at = NOW()
            """;

    private static final String CLOSE_HEADER_SQL = """
            UPDATE workflow_epochs
            SET is_active = FALSE, closed_at = NOW(), epoch_state = CAST(? AS JSONB),
                duration_ms = ?, updated_at = NOW()
            WHERE run_id = ? AND trigger_id = ? AND epoch = ? AND entry_type = 'EPOCH_HEADER'
            """;

    private static final String GET_HEADER_BY_RUN_AND_EPOCH_SQL = """
            SELECT epoch_state, is_active, started_at, closed_at, trigger_id, duration_ms
            FROM workflow_epochs
            WHERE run_id = ? AND epoch = ? AND entry_type = 'EPOCH_HEADER'
            LIMIT 1
            """;

    private static final String GET_HEADER_BY_RUN_TRIGGER_AND_EPOCH_SQL = """
            SELECT epoch_state, is_active, started_at, closed_at, trigger_id, duration_ms
            FROM workflow_epochs
            WHERE run_id = ? AND trigger_id = ? AND epoch = ? AND entry_type = 'EPOCH_HEADER'
            LIMIT 1
            """;

    private static final String LIST_EPOCH_TIMESTAMPS_SQL = """
            SELECT epoch, started_at, closed_at
            FROM workflow_epochs
            WHERE run_id = ? AND entry_type = 'EPOCH_HEADER'
            ORDER BY epoch ASC
            """;

    private static final String LIST_EPOCH_HEADERS_SQL = """
            SELECT epoch, epoch_state, is_active, started_at, closed_at, trigger_id, duration_ms
            FROM workflow_epochs
            WHERE run_id = ? AND entry_type = 'EPOCH_HEADER'
            ORDER BY epoch ASC
            """;

    private final JdbcTemplate jdbcTemplate;

    public WorkflowEpochRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    protected JdbcTemplate getJdbcTemplate() {
        return jdbcTemplate;
    }

    private static final String DEFAULT_TRIGGER_ID = "trigger:default";

    protected static final RowMapper<EpochCountRow> COUNT_ROW_MAPPER =
            (rs, rowNum) -> new EpochCountRow(
                    rs.getString("entry_type"),
                    rs.getString("entry_key"),
                    rs.getString("status"),
                    rs.getInt("count")
            );

    protected static final RowMapper<EpochCountRow> TOTAL_ROW_MAPPER =
            (rs, rowNum) -> new EpochCountRow(
                    rs.getString("entry_type"),
                    rs.getString("entry_key"),
                    rs.getString("status"),
                    rs.getInt("total")
            );

    protected static final RowMapper<EpochHeaderRow> HEADER_ROW_MAPPER =
            (rs, rowNum) -> new EpochHeaderRow(
                    rs.getString("epoch_state"),
                    rs.getBoolean("is_active"),
                    rs.getTimestamp("started_at") != null ? rs.getTimestamp("started_at").toInstant() : null,
                    rs.getTimestamp("closed_at") != null ? rs.getTimestamp("closed_at").toInstant() : null,
                    rs.getString("trigger_id"),
                    rs.getObject("duration_ms") != null ? rs.getLong("duration_ms") : null
            );

    protected static final RowMapper<EpochHeaderWithEpochRow> HEADER_WITH_EPOCH_ROW_MAPPER =
            (rs, rowNum) -> new EpochHeaderWithEpochRow(
                    rs.getInt("epoch"),
                    rs.getString("epoch_state"),
                    rs.getBoolean("is_active"),
                    rs.getTimestamp("started_at") != null ? rs.getTimestamp("started_at").toInstant() : null,
                    rs.getTimestamp("closed_at") != null ? rs.getTimestamp("closed_at").toInstant() : null,
                    rs.getString("trigger_id"),
                    rs.getObject("duration_ms") != null ? rs.getLong("duration_ms") : null
            );

    // ═══════════════════════════════════════════════════════════════════════════
    // Counter CRUD
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Upsert a single epoch count row. Increments count if the row already exists.
     */
    public void upsert(String runId, int epoch, String entryType, String entryKey, String status) {
        upsert(runId, DEFAULT_TRIGGER_ID, epoch, entryType, entryKey, status);
    }

    /**
     * Upsert a single epoch count row with explicit trigger ID.
     */
    public void upsert(String runId, String triggerId, int epoch, String entryType, String entryKey, String status) {
        String tid = triggerId != null ? triggerId : DEFAULT_TRIGGER_ID;
        jdbcTemplate.update(UPSERT_SQL, runId, tid, epoch, entryType, entryKey, status);
    }

    /**
     * Batch upsert for multiple entries (e.g., edges flushed from a batch).
     * Each entry in the map is key -> status.
     */
    public void upsertBatch(String runId, int epoch, String entryType, Map<String, String> entries) {
        upsertBatch(runId, DEFAULT_TRIGGER_ID, epoch, entryType, entries);
    }

    /**
     * Batch upsert with explicit trigger ID.
     */
    public void upsertBatch(String runId, String triggerId, int epoch, String entryType, Map<String, String> entries) {
        if (entries == null || entries.isEmpty()) return;

        String tid = triggerId != null ? triggerId : DEFAULT_TRIGGER_ID;
        List<Object[]> batchArgs = new ArrayList<>(entries.size());
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            batchArgs.add(new Object[]{runId, tid, epoch, entryType, entry.getKey(), entry.getValue()});
        }
        jdbcTemplate.batchUpdate(UPSERT_SQL, batchArgs);
    }

    /**
     * Find all epoch count rows for a specific run and epoch (across all triggers).
     * Only returns counter rows (NODE/EDGE), not header rows.
     */
    public List<EpochCountRow> findByRunIdAndEpoch(String runId, int epoch) {
        return jdbcTemplate.query(FIND_BY_RUN_AND_EPOCH_SQL, COUNT_ROW_MAPPER, runId, epoch);
    }

    /**
     * Find epoch count rows for a specific run, trigger, and epoch.
     * Only returns counter rows (NODE/EDGE), not header rows.
     */
    public List<EpochCountRow> findByRunIdTriggerAndEpoch(String runId, String triggerId, int epoch) {
        return jdbcTemplate.query(FIND_BY_RUN_TRIGGER_AND_EPOCH_SQL, COUNT_ROW_MAPPER, runId, triggerId, epoch);
    }

    /**
     * Get accumulated counts across all epochs for a run (across all triggers).
     * Returns SUM(count) GROUP BY entry_type, entry_key, status.
     */
    public List<EpochCountRow> getAccumulatedCounts(String runId) {
        return jdbcTemplate.query(ACCUMULATED_COUNTS_SQL, TOTAL_ROW_MAPPER, runId);
    }

    /**
     * Get accumulated counts across all epochs for a specific trigger.
     * Returns SUM(count) GROUP BY entry_type, entry_key, status.
     */
    public List<EpochCountRow> getAccumulatedCountsByTrigger(String runId, String triggerId) {
        return jdbcTemplate.query(ACCUMULATED_COUNTS_BY_TRIGGER_SQL, TOTAL_ROW_MAPPER, runId, triggerId);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Header CRUD
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Upsert an epoch header row with the full epoch state JSONB.
     * Creates the row if absent; updates epoch_state if present.
     */
    public void upsertHeader(String runId, String triggerId, int epoch, String epochStateJson) {
        String tid = triggerId != null ? triggerId : DEFAULT_TRIGGER_ID;
        jdbcTemplate.update(UPSERT_HEADER_SQL, runId, tid, epoch, epochStateJson);
    }

    /**
     * Close an epoch header: set is_active=false, closed_at=NOW(), update epoch_state and duration_ms.
     */
    public void closeEpochHeader(String runId, String triggerId, int epoch, String epochStateJson, long durationMs) {
        String tid = triggerId != null ? triggerId : DEFAULT_TRIGGER_ID;
        jdbcTemplate.update(CLOSE_HEADER_SQL, epochStateJson, durationMs, runId, tid, epoch);
    }

    /**
     * List lightweight epoch timestamps for all epochs of a run.
     * Replaces the growing metadata.epochTimestamps array - this is O(1) per epoch in DB
     * and the source of truth lives in workflow_epochs, not in workflow_runs.metadata.
     */
    public List<EpochTimestampRow> listEpochTimestamps(String runId) {
        return jdbcTemplate.query(LIST_EPOCH_TIMESTAMPS_SQL,
                (rs, rowNum) -> new EpochTimestampRow(
                        rs.getInt("epoch"),
                        rs.getTimestamp("started_at") != null ? rs.getTimestamp("started_at").toInstant().toString() : null,
                        rs.getTimestamp("closed_at") != null ? rs.getTimestamp("closed_at").toInstant().toString() : null
                ),
                runId);
    }

    /**
     * List all epoch headers for a run in one query (avoids N+1).
     * Includes the epoch number unlike {@link EpochHeaderRow}.
     */
    public List<EpochHeaderWithEpochRow> listEpochHeaders(String runId) {
        return jdbcTemplate.query(LIST_EPOCH_HEADERS_SQL, HEADER_WITH_EPOCH_ROW_MAPPER, runId);
    }

    /**
     * Get the epoch header row for a specific run and epoch (any trigger).
     */
    public EpochHeaderRow getEpochHeader(String runId, int epoch) {
        List<EpochHeaderRow> rows = jdbcTemplate.query(GET_HEADER_BY_RUN_AND_EPOCH_SQL, HEADER_ROW_MAPPER, runId, epoch);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Get the epoch header row for a specific run, trigger, and epoch.
     */
    public EpochHeaderRow getEpochHeader(String runId, String triggerId, int epoch) {
        String tid = triggerId != null ? triggerId : DEFAULT_TRIGGER_ID;
        List<EpochHeaderRow> rows = jdbcTemplate.query(GET_HEADER_BY_RUN_TRIGGER_AND_EPOCH_SQL, HEADER_ROW_MAPPER, runId, tid, epoch);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Aggregation
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Batch-fetch the max epoch number per run ID from EPOCH_HEADER rows.
     * Returns a map of runId → maxEpoch. Runs with no epochs are absent from the map.
     */
    public Map<String, Integer> getMaxEpochByRunIds(List<String> runIds) {
        if (runIds == null || runIds.isEmpty()) return Map.of();
        String placeholders = runIds.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(", "));
        String sql = "SELECT run_id, MAX(epoch) AS max_epoch FROM workflow_epochs " +
                "WHERE run_id IN (" + placeholders + ") AND entry_type = 'EPOCH_HEADER' " +
                "GROUP BY run_id";
        Map<String, Integer> result = new java.util.HashMap<>();
        jdbcTemplate.query(sql, rs -> {
            result.put(rs.getString("run_id"), rs.getInt("max_epoch"));
        }, runIds.toArray());
        return result;
    }

    /**
     * Batch-fetch the count of distinct epochs per run ID from EPOCH_HEADER rows.
     *
     * <p>Returns {@code Map<runId, count>}. Runs with no epochs (single-shot SBS,
     * brand-new AUTOMATIC runs before the first {@code openEpoch}) are absent from
     * the returned map - callers should treat absence as zero.
     *
     * <p>Unlike {@link #getMaxEpochByRunIds(List)} which returns the highest epoch
     * number (per-trigger sequence, may not equal total count on multi-trigger runs),
     * this returns the actual EPOCH_HEADER row count - semantically the total number
     * of distinct {@code (trigger_id, epoch)} pairs the run has produced.
     */
    public Map<String, Long> getEpochCountByRunIds(List<String> runIds) {
        if (runIds == null || runIds.isEmpty()) return Map.of();
        String placeholders = runIds.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(", "));
        String sql = "SELECT run_id, COUNT(*) AS epoch_count FROM workflow_epochs " +
                "WHERE run_id IN (" + placeholders + ") AND entry_type = 'EPOCH_HEADER' " +
                "GROUP BY run_id";
        Map<String, Long> result = new java.util.HashMap<>();
        jdbcTemplate.query(sql, rs -> {
            result.put(rs.getString("run_id"), rs.getLong("epoch_count"));
        }, runIds.toArray());
        return result;
    }

    /**
     * Batch-fetch the most recent epoch start time per run ID from EPOCH_HEADER rows.
     *
     * <p>For reusable trigger runs (schedule, webhook, datasource, chat, manual)
     * a single {@code WorkflowRunEntity} is reused across many fires - its
     * {@code startedAt} is the run's birth time and never advances. The "last
     * fire time" the user sees in the run history panel is the {@code started_at}
     * of the row in {@code workflow_epochs} for the latest epoch. {@code started_at}
     * is set on first upsert (NOW()) and never overwritten on conflict, so taking
     * MAX(started_at) yields exactly that timestamp without a self-join.
     *
     * <p>Runs that have not fired any epoch yet (single-shot SBS, brand-new
     * AUTOMATIC runs before the first {@code openEpoch}) are absent from the
     * returned map - callers should fall back to the run's {@code startedAt}.
     */
    public Map<String, Instant> getLatestEpochStartedAtByRunIds(List<String> runIds) {
        if (runIds == null || runIds.isEmpty()) return Map.of();
        String placeholders = runIds.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(", "));
        String sql = "SELECT run_id, MAX(started_at) AS latest_started_at FROM workflow_epochs " +
                "WHERE run_id IN (" + placeholders + ") AND entry_type = 'EPOCH_HEADER' " +
                "GROUP BY run_id";
        Map<String, Instant> result = new java.util.HashMap<>();
        jdbcTemplate.query(sql, rs -> {
            java.sql.Timestamp ts = rs.getTimestamp("latest_started_at");
            if (ts != null) {
                result.put(rs.getString("run_id"), ts.toInstant());
            }
        }, runIds.toArray());
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Delete
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Delete all epoch rows (counters + headers) for the given run IDs.
     * Used during workflow deletion to clean up orphaned epoch data.
     */
    public int deleteByRunIds(List<String> runIds) {
        if (runIds == null || runIds.isEmpty()) return 0;
        String sql = "DELETE FROM workflow_epochs WHERE run_id IN (" +
                runIds.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(", ")) + ")";
        return jdbcTemplate.update(sql, runIds.toArray());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Records
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Simple row projection for epoch count queries.
     */
    public record EpochCountRow(String entryType, String entryKey, String status, int count) {
    }

    /**
     * Lightweight projection for epoch timeline display.
     * Fields match the frontend contract: {epoch, startedAt, endedAt}.
     */
    public record EpochTimestampRow(int epoch, String startedAt, String endedAt) {
    }

    /**
     * Header row projection for epoch header queries.
     */
    public record EpochHeaderRow(
            String epochStateJson,
            boolean isActive,
            Instant startedAt,
            Instant closedAt,
            String triggerId,
            Long durationMs
    ) {
    }

    /**
     * Header row projection that includes the epoch number.
     * Used by {@link #listEpochHeaders(String)} for batch queries.
     */
    public record EpochHeaderWithEpochRow(
            int epoch,
            String epochStateJson,
            boolean isActive,
            Instant startedAt,
            Instant closedAt,
            String triggerId,
            Long durationMs
    ) {
    }
}
