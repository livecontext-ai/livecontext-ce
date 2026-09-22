package com.apimarketplace.orchestrator.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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

    /**
     * Reopen a CLOSED header so a rerun re-executes inside the epoch that holds its outputs.
     *
     * <p>{@link #UPSERT_HEADER_SQL} cannot serve this: its ON CONFLICT clause touches only
     * {@code epoch_state} and {@code updated_at}, leaving {@code is_active=false} and the old
     * {@code closed_at} behind.
     *
     * <p>{@code started_at} is deliberately NOT touched, exactly as on the upsert path: it
     * answers "when did this epoch first fire" and {@link #getLatestEpochStartedAtByRunIds}
     * derives the run history's last-fire time from {@code MAX(started_at)}. A rerun is not a
     * fire. The epoch's execution clock lives in {@code epoch_state.startedAt}, which the
     * reopening caller re-stamps.
     */
    private static final String REOPEN_HEADER_SQL = """
            UPDATE workflow_epochs
            SET is_active = TRUE, closed_at = NULL,
                epoch_state = CAST(? AS JSONB), updated_at = NOW()
            WHERE run_id = ? AND trigger_id = ? AND epoch = ? AND entry_type = 'EPOCH_HEADER'
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

    /**
     * The timeline of a run's epochs, with the raw material of each epoch's OUTCOME:
     * {@code epoch_state} + {@code is_active} come from the same header rows, so the
     * verdict is read from the stored {@link
     * com.apimarketplace.orchestrator.domain.execution.EpochState} - the very set the
     * run's own cycle verdict is computed from - without a second round trip.
     *
     * <p><b>Known cost, deliberate.</b> This ships one JSONB document per epoch instead
     * of three scalars, and the caller deserializes each one. It is bounded by the run's
     * epoch count, which is unbounded for a long-lived reusable-trigger run (a
     * once-a-minute schedule accumulates ~10k epochs a week), and every caller of
     * {@code WorkflowEpochService#listEpochTimestamps} pays it - the polled {@code /state},
     * the SSE snapshot, the showcase capture. Accepted because correctness had no cheaper
     * source: the epoch COUNTER rows are additive and count a continue-anyway split
     * failure the cycle verdict excludes, so they answer a different question. If this
     * becomes hot, the fix is to stop recomputing on every read - stamp the outcome into
     * a column when the epoch closes (write-once, and the timeline goes back to scalars).
     * Computing it in SQL is NOT the way out: the "did anything but the trigger run" test
     * needs array-element inspection, which the H2 test repository cannot express.
     */
    private static final String LIST_EPOCH_TIMESTAMPS_SQL = """
            SELECT epoch, started_at, closed_at, is_active, epoch_state
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
     * Reopen a closed epoch header (see {@link #REOPEN_HEADER_SQL}). No-op when the row is absent.
     */
    public void reopenHeader(String runId, String triggerId, int epoch, String epochStateJson) {
        String tid = triggerId != null ? triggerId : DEFAULT_TRIGGER_ID;
        jdbcTemplate.update(REOPEN_HEADER_SQL, epochStateJson, runId, tid, epoch);
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
    public List<EpochTimelineRow> listEpochTimestamps(String runId) {
        return jdbcTemplate.query(LIST_EPOCH_TIMESTAMPS_SQL,
                (rs, rowNum) -> new EpochTimelineRow(
                        rs.getInt("epoch"),
                        rs.getTimestamp("started_at") != null ? rs.getTimestamp("started_at").toInstant().toString() : null,
                        rs.getTimestamp("closed_at") != null ? rs.getTimestamp("closed_at").toInstant().toString() : null,
                        rs.getBoolean("is_active"),
                        rs.getString("epoch_state")
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

    // NOTE - "how long did the last execution take" is deliberately NOT answered from
    // this table. `duration_ms` on the header is stamped at CLOSE time as
    // (now - startedAt), and an epoch closes only when it is reconciled: the next
    // fire's resetForNextCycle (skipped while a blocking signal or in-flight agent
    // remains), a resume, or a restart recovery sweep. Any of those can land hours or
    // days later, so the column measures the epoch's LIFETIME. Prod reported 6h01m
    // and 32h42m for runs whose epochs really execute in 5 to 35 seconds. The work
    // window comes from the step rows instead:
    // WorkflowStepDataRepository#findEpochWorkWindows.

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

    /**
     * Batch-fetch the LAST epoch header of each given run - the raw material of the "how did
     * this automation's last fire end" badge.
     *
     * <p><b>Selected by {@code MAX(epoch)}.</b> {@code epoch} is a GLOBAL counter per run, not
     * a per-DAG one: {@code TriggerEpochManager.incrementEpoch(run, triggerId)} keeps the
     * per-trigger tally in {@code metadata.dagFireCount} and returns
     * {@code previousGlobal + 1}, which is the value {@code openEpoch} writes here. So the
     * highest epoch of a run IS its most recent fire, whichever trigger caused it. That also
     * makes this indexable - by the partial {@code idx_we_header (run_id, trigger_id, epoch)
     * WHERE entry_type = 'EPOCH_HEADER'}, which covers both the filter and the grouping key;
     * ordering by {@code started_at}
     * instead would sort on an unindexed column, and would additionally pick a REOPENED older
     * epoch's original stamp (see {@link #REOPEN_HEADER_SQL}: {@code started_at} is never
     * rewritten), which is a rerun, not a fire.
     *
     * <p><b>The run filter is repeated on the outer relation on purpose.</b> Postgres does not
     * push an {@code IN (...)} list from the grouped subquery into the joined table, so leaving
     * it out turns this into a full scan of {@code workflow_epochs} - the largest table in the
     * schema, carrying a counter row per node per epoch per run - on an endpoint the frontend
     * polls. Hence the bind list is passed twice.
     *
     * <p>Runs with no epoch header (never fired) are absent from the map: callers render no
     * badge rather than inventing one.
     *
     * <p>Written as a join against a grouped subquery rather than {@code DISTINCT ON} so the
     * H2 test repository inherits it unchanged.
     */
    public Map<String, LatestEpochHeaderRow> getLatestEpochHeaderByRunIds(List<String> runIds) {
        if (runIds == null || runIds.isEmpty()) return Map.of();
        String placeholders = runIds.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(", "));
        String sql = "SELECT e.run_id, e.epoch, e.trigger_id, e.epoch_state, e.is_active, e.started_at, e.closed_at " +
                "FROM workflow_epochs e " +
                "JOIN (SELECT run_id, MAX(epoch) AS latest_epoch FROM workflow_epochs " +
                "      WHERE run_id IN (" + placeholders + ") AND entry_type = 'EPOCH_HEADER' " +
                "      GROUP BY run_id) m " +
                "  ON m.run_id = e.run_id AND m.latest_epoch = e.epoch " +
                "WHERE e.run_id IN (" + placeholders + ") AND e.entry_type = 'EPOCH_HEADER'";
        Object[] args = new Object[runIds.size() * 2];
        for (int i = 0; i < runIds.size(); i++) {
            args[i] = runIds.get(i);
            args[runIds.size() + i] = runIds.get(i);
        }
        Map<String, LatestEpochHeaderRow> result = new java.util.HashMap<>();
        jdbcTemplate.query(sql, rs -> {
            String runId = rs.getString("run_id");
            java.sql.Timestamp startedTs = rs.getTimestamp("started_at");
            java.sql.Timestamp closedTs = rs.getTimestamp("closed_at");
            LatestEpochHeaderRow row = new LatestEpochHeaderRow(
                    rs.getInt("epoch"),
                    rs.getString("trigger_id"),
                    rs.getString("epoch_state"),
                    rs.getBoolean("is_active"),
                    startedTs != null ? startedTs.toInstant() : null,
                    closedTs != null ? closedTs.toInstant() : null);
            result.merge(runId, row, WorkflowEpochRepository::preferSettledRow);
        }, args);
        return result;
    }

    /**
     * Deterministic pick when a run somehow has two headers at its highest epoch.
     *
     * <p>A global epoch belongs to ONE fire, so a live run produces one row per run here. The
     * merge is for legacy data: the V2 to V3 migration left {@code "trigger:default"} rows
     * alongside real trigger ids, and the header key includes {@code trigger_id}. Preferring
     * the CLOSED row keeps the only header that can state an outcome, and between two closed
     * ones the later close is the more recent word - so the answer never depends on the order
     * the driver happens to return the rows in.
     */
    private static LatestEpochHeaderRow preferSettledRow(LatestEpochHeaderRow a, LatestEpochHeaderRow b) {
        if (a.isActive() != b.isActive()) return a.isActive() ? b : a;
        if (a.closedAt() != null && b.closedAt() != null && !a.closedAt().equals(b.closedAt())) {
            return b.closedAt().isAfter(a.closedAt()) ? b : a;
        }
        if (a.closedAt() == null && b.closedAt() != null) return b;
        if (b.closedAt() == null && a.closedAt() != null) return a;
        // Nothing left to rank them by outcome or recency - both open, or closed at the same
        // instant. Fall back to the trigger id, which is stable and is what the caller reads
        // to decide whose fire this was: without it the answer would be whichever row the
        // driver happened to hand over first.
        return compareTriggerIds(a, b) <= 0 ? a : b;
    }

    /** Null-safe, nulls last, so the tie-break is total. */
    private static int compareTriggerIds(LatestEpochHeaderRow a, LatestEpochHeaderRow b) {
        if (a.triggerId() == null) return b.triggerId() == null ? 0 : 1;
        if (b.triggerId() == null) return -1;
        return a.triggerId().compareTo(b.triggerId());
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
     * Fields match the frontend contract: {epoch, startedAt, endedAt, workDurationMs}.
     *
     * <p>{@code startedAt}/{@code endedAt} are the header's own timestamps: they place
     * the epoch on the timeline and {@code endedAt == null} is how the UI knows an
     * epoch is still open. Their SPAN is not a duration - the close is deferred, so
     * it counts the idle tail (see the note above {@code getLatestEpochStartedAtByRunIds}).
     * {@code workDurationMs} is the executed window, filled in from the step rows by
     * {@code WorkflowEpochService}; it is null for an epoch that has produced no step
     * row yet.
     *
     * <p>{@code status} is the epoch's OUTCOME (COMPLETED or FAILED, the same binary
     * verdict the run badge uses for a cycle). {@code WorkflowEpochService} derives it
     * from the epoch's stored {@link
     * com.apimarketplace.orchestrator.domain.execution.EpochState} - NOT from the epoch's
     * node tallies, which are additive and count a continue-anyway split failure the cycle
     * verdict excludes (see {@code deriveEpochOutcome}). It is null in the two cases the
     * epoch cannot be spoken for: it executed nothing but its trigger, or it is still
     * ACTIVE, whose stored state is the one written when it opened.
     *
     * <p>It never says RUNNING either: an epoch stays open long after its last node
     * finished (the close is deferred, see the note on
     * {@code getLatestEpochStartedAtByRunIds}), so "is it still executing" is the caller's
     * call from the RUN status, not this field's.
     */
    public record EpochTimestampRow(int epoch, String startedAt, String endedAt, Long workDurationMs, String status) {

        /** Header-only row, before the step-derived work window and outcome are attached. */
        public EpochTimestampRow(int epoch, String startedAt, String endedAt) {
            this(epoch, startedAt, endedAt, null, null);
        }

        /** Header row + work window, before the outcome is attached (test/back-compat shape). */
        public EpochTimestampRow(int epoch, String startedAt, String endedAt, Long workDurationMs) {
            this(epoch, startedAt, endedAt, workDurationMs, null);
        }

        public EpochTimestampRow withWorkDurationMs(Long millis) {
            return new EpochTimestampRow(epoch, startedAt, endedAt, millis, status);
        }

        public EpochTimestampRow withStatus(String epochStatus) {
            return new EpochTimestampRow(epoch, startedAt, endedAt, workDurationMs, epochStatus);
        }

        public EpochTimestampRow withEpoch(int renumbered) {
            return new EpochTimestampRow(renumbered, startedAt, endedAt, workDurationMs, status);
        }

        /**
         * The wire shape consumed by the epoch timeline. Shared by every hand-built
         * epoch payload (the public app, the showcase snapshot) so a new field cannot
         * reach one client and be silently dropped by another - which is exactly how
         * {@code workDurationMs} would have been lost.
         */
        public Map<String, Object> toWireMap() {
            Map<String, Object> wire = new java.util.LinkedHashMap<>();
            wire.put("epoch", epoch);
            wire.put("startedAt", startedAt);
            wire.put("endedAt", endedAt);
            wire.put("workDurationMs", workDurationMs);
            wire.put("status", status);
            return wire;
        }
    }

    /**
     * The most recently fired epoch header of ONE run, as stored: the raw material of the
     * last-run badge. Same "internal payload, never the wire shape" contract as
     * {@link EpochTimelineRow} - the {@code epochStateJson} must be turned into an outcome
     * before it leaves the backend.
     *
     * <p>{@code triggerId} says WHICH trigger fired this epoch, which is what lets a caller
     * showing one row per trigger decide whether this fire is the row's own.
     */
    public record LatestEpochHeaderRow(int epoch, String triggerId, String epochStateJson,
                                       boolean isActive, Instant startedAt, Instant closedAt) {}

    /**
     * One epoch of the timeline, as stored: its timestamps plus the raw material of its
     * status badge ({@code isActive} + the persisted {@code EpochState} JSON).
     *
     * <p>Kept separate from {@link EpochTimestampRow}, which is the WIRE shape: the epoch
     * state JSON is an internal payload and must not reach a client.
     */
    public record EpochTimelineRow(
            int epoch,
            String startedAt,
            String endedAt,
            boolean isActive,
            String epochStateJson
    ) {
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
     * Every trigger fire inside a window for a whole WORKSPACE, with the resource it belongs
     * to, in one query.
     *
     * <p>Joins outwards from the epoch headers rather than inwards from a list of run ids,
     * for one reason: there is no pin predicate anywhere on this path. Resolving runs through
     * {@code findProductionRunsBatch} (which requires {@code pinned_version IS NOT NULL AND
     * plan_version = pinned_version}) made the calendar's history vanish the moment a
     * workflow was unpinned, and lose everything before a re-pin. A run that happened last
     * Tuesday happened whether or not the workflow is pinned today, and whichever plan
     * version it was on.
     *
     * <p>It also stops the caller loading every {@code WorkflowEntity} in the workspace,
     * whose eager JSONB {@code plan} column is by far the most expensive thing on the page.
     * Only the four display fields are selected.
     *
     * <p><b>Two things this query deliberately does NOT filter on.</b>
     * <ul>
     *   <li>The pin, as above.</li>
     *   <li>{@code w.is_active}. Archiving a workflow would otherwise retroactively erase
     *       its runs from every past month - the same "quietly rewriting history" the pin
     *       predicate caused. What ran, ran.</li>
     * </ul>
     *
     * <p>{@code limit} bounds the answer and takes the NEWEST rows ({@code ORDER BY
     * started_at DESC}); the caller re-sorts ascending. Truncating the oldest is the only
     * useful direction: a busy workspace asking for a month would otherwise get the first
     * two thousand fires and an empty second half, which is exactly the part the user is
     * looking at. A caller that receives {@code limit} rows must assume the window holds
     * more and say so, rather than presenting a partial day as a complete one.
     */
    public List<WorkspaceFireRow> findWorkspaceFiresBetween(String organizationId, Instant from,
                                                            Instant to, int limit) {
        if (organizationId == null || organizationId.isBlank()
                || from == null || to == null || limit < 1) {
            return List.of();
        }
        String sql = "SELECT e.run_id, e.trigger_id, e.epoch, e.started_at, e.closed_at, "
                + "e.is_active, e.epoch_state, w.id AS workflow_id, w.name AS workflow_name, "
                + "COALESCE(r.plan->'triggers', '[]'::jsonb)::text AS triggers_json, "
                + "w.workflow_type, w.source_publication_id "
                + "FROM workflow_epochs e "
                + "JOIN workflow_runs r ON r.run_id_public = e.run_id "
                + "JOIN workflows w ON w.id = r.workflow_id "
                + "WHERE e.entry_type = 'EPOCH_HEADER' "
                + "AND e.started_at >= ? AND e.started_at <= ? "
                + "AND w.organization_id = ? "
                + "ORDER BY e.started_at DESC LIMIT " + limit;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new WorkspaceFireRow(
                new EpochFireRow(
                        rs.getString("run_id"),
                        rs.getString("trigger_id"),
                        rs.getInt("epoch"),
                        rs.getTimestamp("started_at") != null ? rs.getTimestamp("started_at").toInstant() : null,
                        rs.getTimestamp("closed_at") != null ? rs.getTimestamp("closed_at").toInstant() : null,
                        rs.getBoolean("is_active"),
                        rs.getString("epoch_state")),
                (java.util.UUID) rs.getObject("workflow_id"),
                rs.getString("workflow_name"),
                rs.getString("workflow_type"),
                rs.getString("triggers_json"),
                rs.getObject("source_publication_id") != null
                        ? rs.getObject("source_publication_id").toString() : null
        ), Timestamp.from(from), Timestamp.from(to), organizationId);
    }

    /** A past fire plus the resource that produced it, for the agenda's history rows. */
    public record WorkspaceFireRow(
            EpochFireRow fire,
            java.util.UUID workflowId,
            String workflowName,
            String workflowType,
            String triggersJson,
            String sourcePublicationId
    ) {
        public WorkspaceFireRow(EpochFireRow fire, java.util.UUID workflowId, String workflowName,
                                String workflowType, String sourcePublicationId) {
            this(fire, workflowId, workflowName, workflowType, null, sourcePublicationId);
        }
    }

    /**
     * One trigger fire that actually happened: an epoch header located in time.
     * {@code epochStateJson} is left raw so the caller decides whether it needs the
     * outcome (deserializing is not free and the calendar only needs it for closed
     * epochs).
     */
    public record EpochFireRow(
            String runId,
            String triggerId,
            int epoch,
            Instant startedAt,
            Instant closedAt,
            boolean isActive,
            String epochStateJson
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
