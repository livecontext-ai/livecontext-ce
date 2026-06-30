package com.apimarketplace.orchestrator.persistence;

import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Native SQL repository for WorkflowStepDataEntity inserts.
 * Uses JdbcTemplate with INSERT ... ON CONFLICT DO NOTHING (PostgreSQL) to handle
 * deduplication at the DB level, replacing in-memory ConcurrentHashMap dedup.
 *
 * This bypasses JPA session to avoid Hibernate session corruption
 * on constraint violation (DataIntegrityViolationException marks session as rollback-only).
 *
 * Relies on unique index: idx_workflow_step_data_unique_v6
 * (workflow_run_id, step_alias, trigger_id, iteration, item_index, epoch, spawn, status)
 *
 * For H2 (E2E tests), see {@link H2StepDataNativeRepository} which overrides
 * with H2-compatible SQL (no ::jsonb, no ON CONFLICT).
 */
@Repository
public class StepDataNativeRepository {

    private static final Logger logger = LoggerFactory.getLogger(StepDataNativeRepository.class);

    // Phase 6 MIGRATION_ORG_ID_NOT_NULL.md (2026-05-19) - organization_id stamped
    // on every step row. Native INSERT bypasses JPA @PrePersist, so we use
    // COALESCE(?, parent_run.organization_id) as a self-healing default - when
    // the caller forgets to stamp, the DB pulls it from the parent. The parent
    // run is guaranteed non-null post-Phase 4 backfill + Phase 6 NOT NULL.
    // V264 (2026-05-20) aligned the column type to VARCHAR(255); no ::uuid cast
    // here - both COALESCE branches are varchar, the parameter is already a
    // String from entity.getOrganizationId(). A previous ?::uuid cast broke
    // every INSERT with "COALESCE types uuid and character varying cannot be
    // matched" because PG resolves COALESCE arg types at parse time.
    private static final String INSERT_ON_CONFLICT_SQL = """
            INSERT INTO workflow_step_data (
                workflow_run_id, run_id, step_alias, tool_id, input_data,
                output_storage_id, http_status, status, start_time, end_time,
                error_message, tenant_id, organization_id, epoch, spawn,
                iteration, item_index, metadata, node_type, condition_expression,
                condition_result, selected_branch, loop_id, loop_iteration,
                loop_exit_reason, merge_strategy, merge_received_branches,
                merge_skipped_branches, item_id, trigger_id, skip_reason,
                skip_source_node, normalized_key, item_number
            ) VALUES (
                ?, ?, ?, ?, ?::jsonb,
                ?, ?, ?, ?, ?,
                ?, ?,
                COALESCE(?, (SELECT organization_id FROM workflow_runs WHERE id = ? LIMIT 1)),
                ?, ?,
                ?, ?, ?::jsonb, ?, ?,
                ?, ?, ?, ?,
                ?, ?, ?::jsonb,
                ?::jsonb, ?, ?, ?,
                ?, ?, ?
            ) ON CONFLICT (workflow_run_id, step_alias, trigger_id, iteration, item_index, epoch, spawn, status)
              DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public StepDataNativeRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Inserts a WorkflowStepDataEntity using native SQL with ON CONFLICT DO NOTHING.
     * Returns true if inserted, false if duplicate (conflict on unique index).
     */
    public boolean insertIgnoringDuplicate(WorkflowStepDataEntity entity) {
        // Ensure trigger_id is never null (required by V164 NOT NULL constraint)
        if (entity.getTriggerId() == null) {
            entity.setTriggerId("trigger:default");
        }
        int rows = jdbcTemplate.update(INSERT_ON_CONFLICT_SQL,
                entity.getWorkflowRunId(),
                entity.getRunId(),
                entity.getStepAlias(),
                entity.getToolId(),
                toJson(entity.getInputData()),
                entity.getOutputStorageId(),
                entity.getHttpStatus(),
                entity.getStatus(),
                toTimestamp(entity.getStartTime()),
                toTimestamp(entity.getEndTime()),
                entity.getErrorMessage(),
                entity.getTenantId(),
                entity.getOrganizationId(),  // varchar - explicit stamp from caller (V264)
                entity.getWorkflowRunId(),   // ? for sub-SELECT fallback on parent run
                entity.getEpoch(),
                entity.getSpawn(),
                entity.getIteration(),
                entity.getItemIndex(),
                toJson(entity.getMetadata()),
                entity.getNodeType() != null ? entity.getNodeType().name() : null,
                entity.getConditionExpression(),
                entity.getConditionResult(),
                entity.getSelectedBranch(),
                entity.getLoopId(),
                entity.getLoopIteration(),
                entity.getLoopExitReason(),
                entity.getMergeStrategy(),
                toJson(entity.getMergeReceivedBranches()),
                toJson(entity.getMergeSkippedBranches()),
                entity.getItemId(),
                entity.getTriggerId(),
                entity.getSkipReason(),
                entity.getSkipSourceNode(),
                entity.getNormalizedKey(),
                entity.getItemNumber()
        );

        if (rows == 0) {
            logger.debug("[NativeRepo] Duplicate detected, INSERT skipped: alias={}, epoch={}, itemIndex={}, status={}",
                    entity.getStepAlias(), entity.getEpoch(), entity.getItemIndex(), entity.getStatus());
        }

        return rows > 0;
    }

    /**
     * Plan v4 §14 phase 2q - bulk INSERT via JdbcTemplate.batchUpdate.
     *
     * <p>Same SQL shape + ON CONFLICT semantics as
     * {@link #insertIgnoringDuplicate}, but in a single round-trip
     * regardless of batch size. Used by fan-out callers that emit N
     * step_data rows in one go (e.g. SplitAwareNodeExecutor planting
     * pending rows for N items).
     *
     * <p>Returns the count of rows actually inserted (N when no conflict,
     * less when some rows were idempotent dups).
     *
     * @param entities batch (empty list returns 0)
     * @return total rows inserted
     */
    public int insertBatchIgnoringDuplicates(java.util.List<WorkflowStepDataEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return 0;
        }
        // Default trigger_id like the single-row path
        for (WorkflowStepDataEntity entity : entities) {
            if (entity.getTriggerId() == null) {
                entity.setTriggerId("trigger:default");
            }
        }
        int[] perRow = jdbcTemplate.batchUpdate(INSERT_ON_CONFLICT_SQL,
                new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                        WorkflowStepDataEntity entity = entities.get(i);
                        int p = 1;
                        ps.setObject(p++, entity.getWorkflowRunId());
                        ps.setString(p++, entity.getRunId());
                        ps.setString(p++, entity.getStepAlias());
                        ps.setString(p++, entity.getToolId());
                        ps.setString(p++, toJson(entity.getInputData()));
                        ps.setObject(p++, entity.getOutputStorageId());
                        if (entity.getHttpStatus() == null) ps.setNull(p++, java.sql.Types.INTEGER);
                        else ps.setInt(p++, entity.getHttpStatus());
                        ps.setString(p++, entity.getStatus());
                        ps.setTimestamp(p++, toTimestamp(entity.getStartTime()));
                        ps.setTimestamp(p++, toTimestamp(entity.getEndTime()));
                        ps.setString(p++, entity.getErrorMessage());
                        ps.setString(p++, entity.getTenantId());
                        ps.setString(p++, entity.getOrganizationId()); // varchar stamp (V264)
                        ps.setObject(p++, entity.getWorkflowRunId());  // sub-SELECT fallback
                        if (entity.getEpoch() == null) ps.setNull(p++, java.sql.Types.INTEGER);
                        else ps.setInt(p++, entity.getEpoch());
                        if (entity.getSpawn() == null) ps.setNull(p++, java.sql.Types.INTEGER);
                        else ps.setInt(p++, entity.getSpawn());
                        if (entity.getIteration() == null) ps.setNull(p++, java.sql.Types.INTEGER);
                        else ps.setInt(p++, entity.getIteration());
                        if (entity.getItemIndex() == null) ps.setNull(p++, java.sql.Types.INTEGER);
                        else ps.setInt(p++, entity.getItemIndex());
                        ps.setString(p++, toJson(entity.getMetadata()));
                        ps.setString(p++, entity.getNodeType() != null ? entity.getNodeType().name() : null);
                        ps.setString(p++, entity.getConditionExpression());
                        if (entity.getConditionResult() == null) ps.setNull(p++, java.sql.Types.BOOLEAN);
                        else ps.setBoolean(p++, entity.getConditionResult());
                        ps.setString(p++, entity.getSelectedBranch());
                        ps.setString(p++, entity.getLoopId());
                        if (entity.getLoopIteration() == null) ps.setNull(p++, java.sql.Types.INTEGER);
                        else ps.setInt(p++, entity.getLoopIteration());
                        ps.setString(p++, entity.getLoopExitReason());
                        ps.setString(p++, entity.getMergeStrategy());
                        ps.setString(p++, toJson(entity.getMergeReceivedBranches()));
                        ps.setString(p++, toJson(entity.getMergeSkippedBranches()));
                        ps.setString(p++, entity.getItemId());
                        ps.setString(p++, entity.getTriggerId());
                        ps.setString(p++, entity.getSkipReason());
                        ps.setString(p++, entity.getSkipSourceNode());
                        ps.setString(p++, entity.getNormalizedKey());
                        if (entity.getItemNumber() == null) ps.setNull(p++, java.sql.Types.INTEGER);
                        else ps.setInt(p++, entity.getItemNumber());
                    }

                    @Override
                    public int getBatchSize() {
                        return entities.size();
                    }
                });
        int totalInserted = 0;
        for (int r : perRow) {
            if (r > 0) totalInserted += r;
        }
        if (totalInserted < entities.size()) {
            logger.debug("[NativeRepo] batchUpdate inserted {}/{} rows ({} idempotent dups)",
                    totalInserted, entities.size(), entities.size() - totalInserted);
        }
        return totalInserted;
    }

    protected JdbcTemplate getJdbcTemplate() {
        return jdbcTemplate;
    }

    protected Timestamp toTimestamp(Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
    }

    protected String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            logger.warn("[NativeRepo] Failed to serialize to JSON: {}", e.getMessage());
            return null;
        }
    }
}
