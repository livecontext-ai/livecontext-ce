package com.apimarketplace.orchestrator.persistence;

import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Plan v4 §14 - bulk INSERT for workflow_step_data via
 * NamedParameterJdbcTemplate.batchUpdate with
 * {@code ON CONFLICT ON CONSTRAINT idx_workflow_step_data_unique_v6 DO NOTHING}.
 *
 * <p>The unique constraint covers the 8-column tuple
 * {@code (workflow_run_id, step_alias, trigger_id, iteration, item_index,
 * epoch, spawn, status)} (verified against {@code V16__fix_step_data_unique_constraint.sql})
 * - the same tuple used for idempotent retries on fan-out split paths.
 * Using ON CONFLICT ON CONSTRAINT (constraint-name form) sidesteps tuple-
 * order risk vs the comma-separated column form (audit C M2 from plan v3
 * round 2).
 *
 * <h2>Scope and tradeoffs</h2>
 *
 * <p>This MVP covers the columns required for the fan-out pattern: split
 * spawn creates N rows that share most fields except item_index/spawn/
 * iteration. JSONB columns ({@code input_data}, {@code metadata},
 * {@code merge_received_branches}, {@code merge_skipped_branches}) are
 * serialized via Jackson when non-null; null values are passed through as
 * SQL NULL.
 *
 * <p>Callers MUST NOT use this for the "rich result row" pattern (status
 * UPDATE from PENDING → COMPLETED with output_storage_id, http_status,
 * end_time, error_message all populated). Bulk INSERT is the per-spawn-
 * row-creation path; subsequent enrichment uses the JPA path.
 *
 * <h2>Post-batch verification</h2>
 *
 * {@link #saveBatch} returns the count of rows touched by the INSERT
 * (NOT the count of pre-existing rows - Postgres rowCount on ON CONFLICT
 * DO NOTHING returns inserted-rows count). Callers can compare against
 * {@code rows.size()} to detect partial-insert conditions, though the
 * idempotent-retry semantics mean a count &lt; size is not necessarily an
 * error (some rows may have been pre-inserted by a peer retry).
 *
 * <h2>Feature flag</h2>
 *
 * <p>{@code orchestrator.optim.step-data-bulk-insert} defaults to true.
 * When OFF, callers fall back to the per-row JpaRepository.save path.
 * Today no caller exists yet - phase 2g+ wires this in.
 */
@Component
public class WorkflowStepDataBulkInserter {

    private static final Logger log = LoggerFactory.getLogger(WorkflowStepDataBulkInserter.class);

    /** Plan §14 - 30s statement_timeout. */
    static final int STATEMENT_TIMEOUT_MS = 30_000;

    /**
     * Plan §14 - INSERT shape with all entity columns. The eight columns in
     * idx_workflow_step_data_unique_v6 must all be present (NULL-safe per
     * column nullability). JSONB columns get an explicit ::jsonb cast.
     *
     * <p>Built once at class-init; immutable. The placeholder names match
     * {@link #toParams} below.
     */
    // Phase 6 MIGRATION_ORG_ID_NOT_NULL.md (2026-05-19) - organization_id
    // added to the bulk INSERT. Caller MUST stamp entity.organizationId before
    // calling; @PrePersist doesn't fire on native SQL paths.
    private static final String INSERT_SQL = """
            INSERT INTO orchestrator.workflow_step_data (
                workflow_run_id, run_id, step_alias, tool_id,
                status, tenant_id, organization_id, epoch, spawn, iteration, item_index,
                item_id, trigger_id,
                start_time, end_time, error_message, http_status,
                input_data, output_storage_id, metadata,
                node_type, condition_expression, condition_result, selected_branch,
                loop_id, loop_iteration, loop_exit_reason,
                merge_strategy, merge_received_branches, merge_skipped_branches,
                skip_reason, skip_source_node,
                normalized_key, item_number
            ) VALUES (
                :workflow_run_id, :run_id, :step_alias, :tool_id,
                :status, :tenant_id, :organization_id, :epoch, :spawn, :iteration, :item_index,
                :item_id, :trigger_id,
                :start_time, :end_time, :error_message, :http_status,
                CAST(:input_data AS jsonb), :output_storage_id, CAST(:metadata AS jsonb),
                :node_type, :condition_expression, :condition_result, :selected_branch,
                :loop_id, :loop_iteration, :loop_exit_reason,
                :merge_strategy, CAST(:merge_received_branches AS jsonb), CAST(:merge_skipped_branches AS jsonb),
                :skip_reason, :skip_source_node,
                :normalized_key, :item_number
            )
            ON CONFLICT ON CONSTRAINT idx_workflow_step_data_unique_v6 DO NOTHING
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper jsonMapper;
    private final boolean bulkEnabled;
    private final Counter batchOkCounter;
    private final Counter batchSkippedCounter;
    private final Counter batchErrorCounter;
    private final Counter rowsInsertedCounter;

    public WorkflowStepDataBulkInserter(
            NamedParameterJdbcTemplate jdbc,
            @Qualifier("stateSnapshotMapper") ObjectMapper jsonMapper,
            MeterRegistry meterRegistry,
            @Value("${orchestrator.optim.step-data-bulk-insert:true}") boolean bulkEnabled) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.bulkEnabled = bulkEnabled;
        this.batchOkCounter = Counter.builder("orchestrator.step_data.batch_ok_count")
                .description("Plan v4 §14 - bulk batchUpdate calls that committed without throwing")
                .register(meterRegistry);
        this.batchSkippedCounter = Counter.builder("orchestrator.step_data.batch_skipped_count")
                .description("Plan v4 §14 - saveBatch called with empty list (no-op)")
                .register(meterRegistry);
        this.batchErrorCounter = Counter.builder("orchestrator.step_data.batch_error_count")
                .description("Plan v4 §14 - bulk batchUpdate calls that threw (caller falls back to per-row save)")
                .register(meterRegistry);
        this.rowsInsertedCounter = Counter.builder("orchestrator.step_data.rows_inserted_count")
                .description("Plan v4 §14 - total rows actually inserted (ON CONFLICT DO NOTHING returns 0 for dups)")
                .register(meterRegistry);
        log.info("[WorkflowStepDataBulkInserter] bulk insert {}",
                bulkEnabled ? "ENABLED" : "DISABLED (per-row save canonical)");
    }

    /**
     * Plan v4 §14 entry point. Pass a list of entities; one INSERT
     * statement is composed and {@code batchUpdate}'d. Returns the count
     * of rows actually inserted (ON CONFLICT DO NOTHING returns 0 for
     * each dup - net count may be &lt; entities.size()).
     *
     * <p>Empty list → 0, no-op (batch_skipped_count incremented).
     * Feature flag OFF → throws IllegalStateException so callers cleanly
     * detect that they should use the per-row save path.
     *
     * <p>Throws on SQL error (caller catches + falls back to per-row save).
     */
    @Transactional(propagation = Propagation.REQUIRED, timeout = STATEMENT_TIMEOUT_MS / 1000)
    public int saveBatch(List<WorkflowStepDataEntity> entities) {
        Objects.requireNonNull(entities, "entities");
        if (!bulkEnabled) {
            throw new IllegalStateException(
                    "orchestrator.optim.step-data-bulk-insert is OFF - caller must use per-row JpaRepository.save");
        }
        if (entities.isEmpty()) {
            batchSkippedCounter.increment();
            return 0;
        }
        SqlParameterSource[] paramsArray = new SqlParameterSource[entities.size()];
        try {
            for (int i = 0; i < entities.size(); i++) {
                paramsArray[i] = toParams(entities.get(i));
            }
        } catch (RuntimeException ex) {
            batchErrorCounter.increment();
            throw new IllegalStateException(
                    "Failed to build params for bulk INSERT (batch size=" + entities.size() + ")", ex);
        }
        try {
            int[] rowsPerStatement = jdbc.batchUpdate(INSERT_SQL, paramsArray);
            int totalInserted = 0;
            for (int r : rowsPerStatement) {
                if (r > 0) totalInserted += r;
            }
            batchOkCounter.increment();
            rowsInsertedCounter.increment(totalInserted);
            if (totalInserted < entities.size()) {
                log.debug("[WorkflowStepDataBulkInserter] batchUpdate inserted {}/{} rows - "
                                + "{} pre-existed (idempotent dup-skip)",
                        totalInserted, entities.size(), entities.size() - totalInserted);
            }
            return totalInserted;
        } catch (RuntimeException ex) {
            batchErrorCounter.increment();
            log.warn("[WorkflowStepDataBulkInserter] batchUpdate failed for {} rows: {} "
                            + "(caller should fall back to per-row save)",
                    entities.size(), ex.getMessage());
            throw ex;
        }
    }

    /**
     * Build a {@link MapSqlParameterSource} mirroring {@link #INSERT_SQL}.
     * Visible for tests so they can pin the column→bind mapping.
     */
    MapSqlParameterSource toParams(WorkflowStepDataEntity e) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        // 8-col unique constraint members (all idx_workflow_step_data_unique_v6)
        p.addValue("workflow_run_id", e.getWorkflowRunId(), java.sql.Types.OTHER);
        p.addValue("step_alias", e.getStepAlias());
        p.addValue("trigger_id", e.getTriggerId());
        p.addValue("iteration", e.getIteration());
        p.addValue("item_index", e.getItemIndex());
        p.addValue("epoch", e.getEpoch());
        p.addValue("spawn", e.getSpawn());
        p.addValue("status", e.getStatus());
        // Other mandatory columns
        p.addValue("run_id", e.getRunId());
        p.addValue("tool_id", e.getToolId());
        p.addValue("tenant_id", e.getTenantId());
        p.addValue("organization_id", e.getOrganizationId());
        // Item-tracking
        p.addValue("item_id", e.getItemId());
        // Timing + result
        p.addValue("start_time", e.getStartTime() != null ? java.sql.Timestamp.from(e.getStartTime()) : null);
        p.addValue("end_time", e.getEndTime() != null ? java.sql.Timestamp.from(e.getEndTime()) : null);
        p.addValue("error_message", e.getErrorMessage());
        p.addValue("http_status", e.getHttpStatus());
        // JSONB columns (serialize null/empty Maps as SQL NULL, not "{}")
        p.addValue("input_data", serializeJsonbOrNull(e.getInputData()));
        p.addValue("metadata", serializeJsonbOrNull(e.getMetadata()));
        p.addValue("merge_received_branches", serializeJsonbOrNull(e.getMergeReceivedBranches()));
        p.addValue("merge_skipped_branches", serializeJsonbOrNull(e.getMergeSkippedBranches()));
        p.addValue("output_storage_id", e.getOutputStorageId(), java.sql.Types.OTHER);
        // Node-type-specific
        p.addValue("node_type", e.getNodeType() != null ? e.getNodeType().name() : null);
        p.addValue("condition_expression", e.getConditionExpression());
        p.addValue("condition_result", e.getConditionResult());
        p.addValue("selected_branch", e.getSelectedBranch());
        p.addValue("loop_id", e.getLoopId());
        p.addValue("loop_iteration", e.getLoopIteration());
        p.addValue("loop_exit_reason", e.getLoopExitReason());
        p.addValue("merge_strategy", e.getMergeStrategy());
        // Skip tracking
        p.addValue("skip_reason", e.getSkipReason());
        p.addValue("skip_source_node", e.getSkipSourceNode());
        // Display helpers
        p.addValue("normalized_key", e.getNormalizedKey());
        p.addValue("item_number", e.getItemNumber());
        return p;
    }

    private String serializeJsonbOrNull(Object value) {
        if (value == null) return null;
        if (value instanceof Map<?, ?> map && map.isEmpty()) return null;
        if (value instanceof List<?> list && list.isEmpty()) return null;
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            // Fail-loud - corrupting JSONB silently is worse than failing the batch.
            throw new IllegalStateException("Failed to serialize JSONB value: " + value, ex);
        }
    }

    boolean isBulkEnabled() {
        return bulkEnabled;
    }
}
