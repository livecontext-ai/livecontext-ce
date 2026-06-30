package com.apimarketplace.orchestrator.trigger.queue;

import com.apimarketplace.orchestrator.trigger.TriggerExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Durable idempotency ledger for Redis queue deliveries.
 *
 * <p>Redis stream ownership is a delivery concern; this table records whether a
 * specific queue request crossed the workflow-execution boundary. Once an epoch
 * is associated with a request, a reclaimed Redis message must not execute the
 * same request again.
 */
public interface ExecutionQueueClaimStore {

    String STATUS_RUNNING = "RUNNING";
    String STATUS_DONE = "DONE";
    String STATUS_FAILED = "FAILED";
    String STATUS_CANCELLED = "CANCELLED";

    ClaimRecord claimForExecution(QueuedExecutionMessage message, String ownerId);

    void markEpochStarted(String requestId, String runIdPublic, String triggerId, int epoch);

    void complete(QueuedExecutionMessage message, String status, TriggerExecutionResult result);

    int purgeCompletedBefore(Instant cutoff, int limit);

    record ClaimRecord(
            String requestId,
            String status,
            String ownerId,
            Integer epoch,
            TriggerExecutionResult result,
            String message,
            boolean newlyClaimed) {

        boolean isFinal() {
            return STATUS_DONE.equals(status) || STATUS_FAILED.equals(status) || STATUS_CANCELLED.equals(status);
        }

        boolean hasStartedExecutionBoundary() {
            return STATUS_RUNNING.equals(status) && epoch != null;
        }
    }
}

@Service
class JdbcExecutionQueueClaimStore implements ExecutionQueueClaimStore {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    JdbcExecutionQueueClaimStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public ClaimRecord claimForExecution(QueuedExecutionMessage message, String ownerId) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO orchestrator.workflow_execution_queue_claims (
                    request_id, run_id_public, trigger_id, trigger_type, status, claimed_by, claimed_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, now(), now())
                ON CONFLICT (request_id) DO NOTHING
                """,
                message.requestId(),
                message.runIdPublic(),
                message.triggerId(),
                message.triggerType() != null ? message.triggerType().name() : null,
                STATUS_RUNNING,
                ownerId);

        if (inserted == 1) {
            return new ClaimRecord(message.requestId(), STATUS_RUNNING, ownerId, null, null, null, true);
        }

        ClaimRecord existing = find(message.requestId())
                .orElseThrow(() -> new IllegalStateException("Execution claim disappeared: " + message.requestId()));
        if (STATUS_RUNNING.equals(existing.status()) && existing.epoch() == null) {
            jdbcTemplate.update("""
                    UPDATE orchestrator.workflow_execution_queue_claims
                    SET claimed_by = ?, updated_at = now()
                    WHERE request_id = ? AND status = ? AND epoch IS NULL
                    """, ownerId, message.requestId(), STATUS_RUNNING);
            return new ClaimRecord(message.requestId(), STATUS_RUNNING, ownerId, null, null, null, true);
        }
        return existing;
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void markEpochStarted(String requestId, String runIdPublic, String triggerId, int epoch) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        jdbcTemplate.update("""
                UPDATE orchestrator.workflow_execution_queue_claims
                SET epoch = COALESCE(epoch, ?),
                    run_id_public = COALESCE(run_id_public, ?),
                    trigger_id = COALESCE(trigger_id, ?),
                    updated_at = now()
                WHERE request_id = ? AND status = ?
                """, epoch, runIdPublic, triggerId, requestId, STATUS_RUNNING);
    }

    @Override
    @Transactional
    public void complete(QueuedExecutionMessage message, String status, TriggerExecutionResult result) {
        String encodedResult = null;
        try {
            if (result != null) {
                encodedResult = objectMapper.writeValueAsString(result);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize durable execution result", e);
        }

        jdbcTemplate.update("""
                UPDATE orchestrator.workflow_execution_queue_claims
                SET status = ?,
                    result = CASE WHEN ? IS NULL THEN NULL ELSE cast(? as jsonb) END,
                    message = ?,
                    completed_at = now(),
                    updated_at = now()
                WHERE request_id = ?
                """,
                status,
                encodedResult,
                encodedResult,
                result != null ? result.message() : null,
                message.requestId());
    }

    @Override
    @Transactional
    public int purgeCompletedBefore(Instant cutoff, int limit) {
        return jdbcTemplate.update("""
                DELETE FROM orchestrator.workflow_execution_queue_claims
                WHERE request_id IN (
                    SELECT request_id
                    FROM orchestrator.workflow_execution_queue_claims
                    WHERE completed_at IS NOT NULL AND completed_at < ?
                    ORDER BY completed_at
                    LIMIT ?
                )
                """, Timestamp.from(cutoff), Math.max(1, limit));
    }

    private Optional<ClaimRecord> find(String requestId) {
        List<ClaimRecord> records = jdbcTemplate.query("""
                SELECT request_id, status, claimed_by, epoch, result::text AS result_json, message
                FROM orchestrator.workflow_execution_queue_claims
                WHERE request_id = ?
                """, this::mapClaim, requestId);
        return records.stream().findFirst();
    }

    private ClaimRecord mapClaim(ResultSet rs, int rowNum) throws SQLException {
        String resultJson = rs.getString("result_json");
        TriggerExecutionResult result = null;
        if (resultJson != null && !resultJson.isBlank()) {
            try {
                result = objectMapper.readValue(resultJson, TriggerExecutionResult.class);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to deserialize durable execution result", e);
            }
        }
        Object epochObject = rs.getObject("epoch");
        Integer epoch = epochObject instanceof Number number ? number.intValue() : null;
        return new ClaimRecord(
                rs.getString("request_id"),
                rs.getString("status"),
                rs.getString("claimed_by"),
                epoch,
                result,
                rs.getString("message"),
                false);
    }
}

@Service
class ExecutionQueueClaimCleanupService {

    private final ExecutionQueueClaimStore claimStore;
    private final long retentionHours;
    private final int batchSize;

    ExecutionQueueClaimCleanupService(
            ExecutionQueueClaimStore claimStore,
            @Value("${workflow.execution-queue.claim-retention-hours:168}") long retentionHours,
            @Value("${workflow.execution-queue.claim-cleanup-batch-size:1000}") int batchSize) {
        this.claimStore = claimStore;
        this.retentionHours = retentionHours;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${workflow.execution-queue.claim-cleanup-interval-ms:3600000}",
            initialDelayString = "${workflow.execution-queue.claim-cleanup-initial-delay-ms:300000}")
    @SchedulerLock(name = "execution_queue_claim_cleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    void purgeCompletedClaims() {
        if (retentionHours <= 0) {
            return;
        }
        claimStore.purgeCompletedBefore(Instant.now().minusSeconds(retentionHours * 3600), batchSize);
    }
}
