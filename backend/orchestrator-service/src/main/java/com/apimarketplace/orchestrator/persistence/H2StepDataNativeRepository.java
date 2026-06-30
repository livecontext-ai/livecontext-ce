package com.apimarketplace.orchestrator.persistence;

import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * H2-compatible override of {@link StepDataNativeRepository} for E2E tests.
 *
 * H2 does not support PostgreSQL-specific syntax:
 * - {@code ?::jsonb} casts
 * - {@code ON CONFLICT (...) DO NOTHING}
 *
 * This version uses plain INSERT + catch {@link DuplicateKeyException} with
 * {@code @Transactional(propagation = REQUIRES_NEW)} to isolate the sub-transaction,
 * preventing Spring from marking the parent transaction as rollback-only.
 *
 * Activated only when {@code orchestrator.mock.enabled=true} (E2E test profile).
 */
@Repository
@Primary
@ConditionalOnProperty(name = "orchestrator.mock.enabled", havingValue = "true")
public class H2StepDataNativeRepository extends StepDataNativeRepository {

    private static final Logger logger = LoggerFactory.getLogger(H2StepDataNativeRepository.class);

    // Phase 6 MIGRATION_ORG_ID_NOT_NULL.md (2026-05-19) - organization_id
    // added to the H2 INSERT (test-mode mirror of StepDataNativeRepository).
    private static final String H2_INSERT_SQL = """
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
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?,
                ?, ?, ?,
                ?, ?, ?, ?,
                ?, ?, ?
            )
            """;

    public H2StepDataNativeRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        super(jdbcTemplate, objectMapper);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean insertIgnoringDuplicate(WorkflowStepDataEntity entity) {
        // Ensure trigger_id is never null (required by V164 NOT NULL constraint)
        if (entity.getTriggerId() == null) {
            entity.setTriggerId("trigger:default");
        }
        try {
            int rows = getJdbcTemplate().update(H2_INSERT_SQL,
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
                    entity.getOrganizationId(),
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

            return rows > 0;
        } catch (DuplicateKeyException e) {
            logger.debug("[H2NativeRepo] Duplicate detected, INSERT skipped: alias={}, epoch={}, itemIndex={}, status={}",
                    entity.getStepAlias(), entity.getEpoch(), entity.getItemIndex(), entity.getStatus());
            return false;
        }
    }
}
