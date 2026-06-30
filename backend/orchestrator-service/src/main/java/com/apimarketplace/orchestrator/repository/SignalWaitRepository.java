package com.apimarketplace.orchestrator.repository;

import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity.SignalWaitStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository for signal wait entities (workflow_signal_waits table).
 *
 * Supports claim-before-process pattern with atomic UPDATE queries.
 * Uses FOR UPDATE SKIP LOCKED for batched polling without thundering herd.
 *
 * COMPLETELY SEPARATE from PendingSignalRepository (step-by-step mode).
 */
@Repository
public interface SignalWaitRepository extends JpaRepository<SignalWaitEntity, Long> {

    // ========================================================================
    // CLAIM + RESOLVE (Atomic operations for idempotent processing)
    // ========================================================================

    /**
     * Atomically claim a single PENDING signal.
     * Returns number of rows updated (0 = already claimed/resolved).
     */
    @Modifying
    @Query(value = """
        UPDATE orchestrator.workflow_signal_waits
        SET status = 'CLAIMED', claimed_at = :now, claimed_by = :claimedBy
        WHERE id = :id AND status = 'PENDING'
        """, nativeQuery = true)
    int claimSignal(@Param("id") Long id, @Param("now") Instant now, @Param("claimedBy") String claimedBy);

    /**
     * Atomically resolve a CLAIMED signal.
     * Returns number of rows updated (0 = not in CLAIMED state).
     */
    @Modifying
    @Query(value = """
        UPDATE orchestrator.workflow_signal_waits
        SET status = 'RESOLVED', resolution = :resolution,
            resolved_at = :now, resolved_by = :resolvedBy
        WHERE id = :id AND status = 'CLAIMED'
        """, nativeQuery = true)
    int resolveClaimedSignal(
            @Param("id") Long id,
            @Param("resolution") String resolution,
            @Param("now") Instant now,
            @Param("resolvedBy") String resolvedBy);

    /**
     * Claim expired timer signals in batch with SKIP LOCKED.
     * Returns list of claimed entities via RETURNING *.
     * Has its own @Transactional because the caller (pollExpiredTimers) is intentionally
     * non-transactional so each subsequent resolveSignal() fires its own AFTER_COMMIT event.
     */
    @Transactional
    @Query(value = """
        UPDATE orchestrator.workflow_signal_waits
        SET status = 'CLAIMED', claimed_at = :now, claimed_by = :claimedBy
        WHERE id IN (
            SELECT id FROM orchestrator.workflow_signal_waits
            WHERE status = 'PENDING' AND signal_type = 'WAIT_TIMER' AND expires_at <= :now
            ORDER BY expires_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
        )
        RETURNING *
        """, nativeQuery = true)
    List<SignalWaitEntity> claimExpiredTimers(
            @Param("now") Instant now,
            @Param("claimedBy") String claimedBy,
            @Param("batchSize") int batchSize);

    // ========================================================================
    // CANCEL
    // ========================================================================

    /**
     * Cancel all active signals for a specific DAG and epoch.
     */
    @Modifying
    @Query(value = """
        UPDATE orchestrator.workflow_signal_waits
        SET status = 'CANCELLED', resolution = 'CANCELLED', resolved_at = :now
        WHERE run_id = :runId AND dag_trigger_id = :dagTriggerId AND epoch = :epoch
              AND status IN ('PENDING', 'CLAIMED')
        """, nativeQuery = true)
    int cancelByDagAndEpoch(
            @Param("runId") String runId,
            @Param("dagTriggerId") String dagTriggerId,
            @Param("epoch") int epoch,
            @Param("now") Instant now);

    /**
     * Cancel active blocking signals for a specific DAG and epoch.
     * Non-blocking signals (e.g., auto-advance interfaces) persist across trigger cycles.
     */
    @Modifying
    @Query(value = """
        UPDATE orchestrator.workflow_signal_waits
        SET status = 'CANCELLED', resolution = 'CANCELLED', resolved_at = :now
        WHERE run_id = :runId AND dag_trigger_id = :dagTriggerId AND epoch = :epoch
              AND status IN ('PENDING', 'CLAIMED') AND blocking = true
        """, nativeQuery = true)
    int cancelBlockingByDagAndEpoch(
            @Param("runId") String runId,
            @Param("dagTriggerId") String dagTriggerId,
            @Param("epoch") int epoch,
            @Param("now") Instant now);

    /**
     * Cancel all active signals for a run.
     */
    @Modifying
    @Query(value = """
        UPDATE orchestrator.workflow_signal_waits
        SET status = 'CANCELLED', resolution = 'CANCELLED', resolved_at = :now
        WHERE run_id = :runId AND status IN ('PENDING', 'CLAIMED')
        """, nativeQuery = true)
    int cancelByRunId(@Param("runId") String runId, @Param("now") Instant now);

    /**
     * Cancel a single active signal by ID.
     * Used by the closed-epoch zombie cleanup in {@code UnifiedSignalService.resolveSignal}.
     * Idempotent: only updates rows still in PENDING or CLAIMED state.
     */
    @Modifying
    @Query(value = """
        UPDATE orchestrator.workflow_signal_waits
        SET status = 'CANCELLED', resolution = 'CANCELLED', resolved_at = :now
        WHERE id = :id AND status IN ('PENDING', 'CLAIMED')
        """, nativeQuery = true)
    int cancelById(@Param("id") Long id, @Param("now") Instant now);

    /**
     * Lightweight projection used by the closed-epoch zombie guard to read the fields it
     * needs WITHOUT loading the managed {@link SignalWaitEntity} into the Hibernate L1 cache.
     *
     * <p>This is critical: if the guard called {@code findById}, the entity would be cached
     * with its current status (PENDING) and a subsequent {@code save(entity)} after the
     * native claim+resolve UPDATEs would re-emit the cached PENDING status, silently
     * reverting the resolution. The DB poller would then find the signal "still pending"
     * and resolve it a second time - exactly the regression observed on Gmail Auto-Labeler
     * (run_<id>) on 2026-05-06.
     */
    record EpochInfo(String runId, String dagTriggerId, int epoch, Instant createdAt) {}

    @Query("""
        SELECT new com.apimarketplace.orchestrator.repository.SignalWaitRepository$EpochInfo(
            s.runId, s.dagTriggerId, s.epoch, s.createdAt)
        FROM SignalWaitEntity s WHERE s.id = :id
        """)
    Optional<EpochInfo> findEpochInfoById(@Param("id") Long id);

    /**
     * Lightweight projection used before native claim/resolve updates. Do not replace
     * with {@code findById}: loading the entity before the native UPDATE would cache
     * stale resolution/status fields in Hibernate L1.
     */
    record ResumeInfo(String runId, String dagTriggerId, int epoch, boolean blocking) {}

    @Query("""
        SELECT new com.apimarketplace.orchestrator.repository.SignalWaitRepository$ResumeInfo(
            s.runId, s.dagTriggerId, s.epoch, s.blocking)
        FROM SignalWaitEntity s WHERE s.id = :id
        """)
    Optional<ResumeInfo> findResumeInfoById(@Param("id") Long id);

    /**
     * Hard-delete all signal waits for the given run IDs.
     * Used during workflow deletion to clean up orphaned signal data.
     */
    @Modifying
    @Query("DELETE FROM SignalWaitEntity s WHERE s.runId IN :runIds")
    int deleteByRunIdIn(@Param("runIds") List<String> runIds);

    // ========================================================================
    // RESCHEDULE
    // ========================================================================

    /**
     * Update the expiration time of a PENDING signal.
     * Returns 0 if the signal is no longer PENDING (already resolved/cancelled).
     */
    @Modifying
    @Query(value = """
        UPDATE orchestrator.workflow_signal_waits
        SET expires_at = :newExpiresAt
        WHERE id = :id AND status = 'PENDING'
        """, nativeQuery = true)
    int updateExpiresAt(@Param("id") Long id, @Param("newExpiresAt") Instant newExpiresAt);

    // ========================================================================
    // POLLING QUERIES
    // ========================================================================

    /**
     * Find expired timer signals for polling.
     * Has its own @Transactional because FOR UPDATE SKIP LOCKED requires a transaction,
     * and the caller (pollExpiredTimers) is intentionally non-transactional.
     * Each signal is then resolved individually via resolveSignal() in its own TX.
     */
    @Transactional
    @Query(value = """
        SELECT * FROM orchestrator.workflow_signal_waits
        WHERE status = 'PENDING' AND signal_type = 'WAIT_TIMER'
              AND expires_at IS NOT NULL AND expires_at <= :now
        ORDER BY expires_at
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<SignalWaitEntity> findExpiredTimers(@Param("now") Instant now, @Param("batchSize") int batchSize);

    /**
     * Plan v3 #9 - keyset-paginated variant of {@link #findExpiredTimers}.
     * Uses the V179 covering index {@code (status, signal_type, expires_at, id)}
     * and an {@code id > :lastId} predicate so a multi-replica poller does not
     * keep re-scanning the same head of the queue. Each replica persists its
     * own {@code lastId} in {@code instance_lease.last_id}.
     *
     * <p>Default batch size in production is 500 (vs the legacy 50): the
     * covering index keeps the scan index-only, and {@code SKIP LOCKED}
     * prevents dup-claim across replicas - bumping the limit raises throughput
     * linearly without serialization risk.
     */
    @Transactional
    @Query(value = """
        SELECT * FROM orchestrator.workflow_signal_waits
        WHERE status = 'PENDING' AND signal_type = 'WAIT_TIMER'
              AND expires_at IS NOT NULL AND expires_at <= :now
              AND id > :lastId
        ORDER BY id
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<SignalWaitEntity> findExpiredTimersKeyset(@Param("now") Instant now,
                                                   @Param("lastId") long lastId,
                                                   @Param("batchSize") int batchSize);

    /**
     * Find expired non-timer signals (USER_APPROVAL, WEBHOOK_WAIT) for timeout processing.
     * Has its own @Transactional because FOR UPDATE SKIP LOCKED requires a transaction,
     * and the caller (processTimeouts) is intentionally non-transactional.
     */
    @Transactional
    @Query(value = """
        SELECT * FROM orchestrator.workflow_signal_waits
        WHERE status = 'PENDING'
              AND signal_type != 'WAIT_TIMER'
              AND expires_at IS NOT NULL AND expires_at <= :now
        ORDER BY expires_at
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<SignalWaitEntity> findExpiredNonTimers(@Param("now") Instant now, @Param("batchSize") int batchSize);

    /**
     * Plan v3 #9 - keyset variant of {@link #findExpiredNonTimers}. See
     * {@link #findExpiredTimersKeyset} for the per-replica cursor pattern.
     */
    @Transactional
    @Query(value = """
        SELECT * FROM orchestrator.workflow_signal_waits
        WHERE status = 'PENDING'
              AND signal_type != 'WAIT_TIMER'
              AND expires_at IS NOT NULL AND expires_at <= :now
              AND id > :lastId
        ORDER BY id
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<SignalWaitEntity> findExpiredNonTimersKeyset(@Param("now") Instant now,
                                                     @Param("lastId") long lastId,
                                                     @Param("batchSize") int batchSize);

    /**
     * Find stale claimed signals (claimed but not resolved within threshold).
     */
    @Query(value = """
        SELECT * FROM orchestrator.workflow_signal_waits
        WHERE status = 'CLAIMED' AND claimed_at < :staleThreshold
        """, nativeQuery = true)
    List<SignalWaitEntity> findStaleClaims(@Param("staleThreshold") Instant staleThreshold);

    /**
     * Reset stale claims back to PENDING.
     * Has its own @Transactional because callers (startup recovery, @Scheduled poller)
     * are intentionally non-transactional to avoid holding locks across long operations.
     */
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE orchestrator.workflow_signal_waits
        SET status = 'PENDING', claimed_at = NULL, claimed_by = NULL
        WHERE status = 'CLAIMED' AND claimed_at < :staleThreshold
        """, nativeQuery = true)
    int resetStaleClaims(@Param("staleThreshold") Instant staleThreshold);

    // ========================================================================
    // QUERY METHODS
    // ========================================================================

    /**
     * Find all active signals for a run.
     */
    @Query("SELECT s FROM SignalWaitEntity s WHERE s.runId = :runId AND s.status IN ('PENDING', 'CLAIMED')")
    List<SignalWaitEntity> findActiveByRunId(@Param("runId") String runId);

    /**
     * Find all active signals for a run filtered by epoch.
     */
    @Query("SELECT s FROM SignalWaitEntity s WHERE s.runId = :runId AND s.epoch = :epoch AND s.status IN ('PENDING', 'CLAIMED')")
    List<SignalWaitEntity> findActiveByRunIdAndEpoch(@Param("runId") String runId, @Param("epoch") int epoch);

    /**
     * Count active signals for a run.
     */
    @Query("SELECT COUNT(s) FROM SignalWaitEntity s WHERE s.runId = :runId AND s.status IN ('PENDING', 'CLAIMED')")
    long countActiveByRunId(@Param("runId") String runId);

    /**
     * Check if active signals exist for a run.
     */
    default boolean hasActiveSignals(String runId) {
        return countActiveByRunId(runId) > 0;
    }

    /**
     * Count active signals that should block workflow completion.
     * Non-blocking signals (e.g., auto-advance interfaces) don't prevent completion.
     */
    @Query("SELECT COUNT(s) FROM SignalWaitEntity s WHERE s.runId = :runId AND s.status IN ('PENDING', 'CLAIMED') AND s.blocking = true")
    long countActiveBlockingByRunId(@Param("runId") String runId);

    /**
     * Check if blocking signals exist for a run.
     */
    default boolean hasBlockingSignals(String runId) {
        return countActiveBlockingByRunId(runId) > 0;
    }

    /**
     * Count active signals for a specific DAG.
     */
    @Query("SELECT COUNT(s) FROM SignalWaitEntity s WHERE s.runId = :runId AND s.dagTriggerId = :dagTriggerId AND s.status IN ('PENDING', 'CLAIMED')")
    long countActiveByRunIdAndDag(@Param("runId") String runId, @Param("dagTriggerId") String dagTriggerId);

    /**
     * Count active blocking signals for a specific DAG.
     * Non-blocking signals don't prevent workflow progression.
     */
    @Query("SELECT COUNT(s) FROM SignalWaitEntity s WHERE s.runId = :runId AND s.dagTriggerId = :dagTriggerId AND s.status IN ('PENDING', 'CLAIMED') AND s.blocking = true")
    long countActiveBlockingByRunIdAndDag(@Param("runId") String runId, @Param("dagTriggerId") String dagTriggerId);

    /**
     * Check if active signals exist for a specific DAG.
     */
    default boolean hasActiveSignalsForDag(String runId, String dagTriggerId) {
        return countActiveByRunIdAndDag(runId, dagTriggerId) > 0;
    }

    /**
     * Check if blocking signals exist for a specific DAG.
     */
    default boolean hasBlockingSignalsForDag(String runId, String dagTriggerId) {
        return countActiveBlockingByRunIdAndDag(runId, dagTriggerId) > 0;
    }

    /**
     * Count active blocking signals for a specific DAG and epoch.
     * Non-blocking signals don't prevent epoch closure.
     * Used for parallel epoch support: each epoch closes independently.
     */
    @Query("SELECT COUNT(s) FROM SignalWaitEntity s WHERE s.runId = :runId AND s.dagTriggerId = :dagTriggerId AND s.epoch = :epoch AND s.status IN ('PENDING', 'CLAIMED') AND s.blocking = true")
    long countActiveBlockingByRunIdAndDagAndEpoch(@Param("runId") String runId, @Param("dagTriggerId") String dagTriggerId, @Param("epoch") int epoch);

    /**
     * Check if blocking signals exist for a specific DAG and epoch.
     * Used for parallel epoch support: each epoch's signals are checked independently.
     */
    default boolean hasBlockingSignalsForDagAndEpoch(String runId, String dagTriggerId, int epoch) {
        return countActiveBlockingByRunIdAndDagAndEpoch(runId, dagTriggerId, epoch) > 0;
    }

    /**
     * Find all signals for a run (all statuses).
     */
    List<SignalWaitEntity> findByRunId(String runId);

    /**
     * Find active signal IDs for a run (for bulk timer cancellation).
     */
    @Query("SELECT s.id FROM SignalWaitEntity s WHERE s.runId = :runId AND s.status IN ('PENDING', 'CLAIMED')")
    List<Long> findActiveSignalIdsByRunId(@Param("runId") String runId);

    /**
     * Find active signal IDs for a DAG+epoch (for bulk timer cancellation).
     */
    @Query("SELECT s.id FROM SignalWaitEntity s WHERE s.runId = :runId AND s.dagTriggerId = :dagTriggerId AND s.epoch = :epoch AND s.status IN ('PENDING', 'CLAIMED')")
    List<Long> findActiveSignalIdsByDagAndEpoch(
            @Param("runId") String runId,
            @Param("dagTriggerId") String dagTriggerId,
            @Param("epoch") int epoch);

    // ========================================================================
    // USER_APPROVAL pre-cancel filters
    // (P2a notification-system: bulk-cancel paths bypass SignalResolvedEvent;
    //  the IDs harvested here flow into SignalsCancelledEvent so
    //  NotificationEmitter can bulk-DELETE the matching APPROVAL_PENDING rows.)
    // ========================================================================

    /** Active USER_APPROVAL IDs for a run. */
    @Query("SELECT s.id FROM SignalWaitEntity s "
        + "WHERE s.runId = :runId "
        + "  AND s.signalType = com.apimarketplace.orchestrator.domain.execution.SignalType.USER_APPROVAL "
        + "  AND s.status IN ('PENDING', 'CLAIMED')")
    List<Long> findActiveUserApprovalIdsByRunId(@Param("runId") String runId);

    /** Active USER_APPROVAL IDs for a (run, dag, epoch). */
    @Query("SELECT s.id FROM SignalWaitEntity s "
        + "WHERE s.runId = :runId "
        + "  AND s.dagTriggerId = :dagTriggerId "
        + "  AND s.epoch = :epoch "
        + "  AND s.signalType = com.apimarketplace.orchestrator.domain.execution.SignalType.USER_APPROVAL "
        + "  AND s.status IN ('PENDING', 'CLAIMED')")
    List<Long> findActiveUserApprovalIdsByDagAndEpoch(
            @Param("runId") String runId,
            @Param("dagTriggerId") String dagTriggerId,
            @Param("epoch") int epoch);

    /** Active USER_APPROVAL IDs for a (run, dag, epoch), blocking only. */
    @Query("SELECT s.id FROM SignalWaitEntity s "
        + "WHERE s.runId = :runId "
        + "  AND s.dagTriggerId = :dagTriggerId "
        + "  AND s.epoch = :epoch "
        + "  AND s.signalType = com.apimarketplace.orchestrator.domain.execution.SignalType.USER_APPROVAL "
        + "  AND s.blocking = true "
        + "  AND s.status IN ('PENDING', 'CLAIMED')")
    List<Long> findActiveBlockingUserApprovalIdsByDagAndEpoch(
            @Param("runId") String runId,
            @Param("dagTriggerId") String dagTriggerId,
            @Param("epoch") int epoch);

    /** Resolve the signalType for a given signal id (for the zombie-guard cancel path). */
    @Query("SELECT s.signalType FROM SignalWaitEntity s WHERE s.id = :id")
    Optional<com.apimarketplace.orchestrator.domain.execution.SignalType> findSignalTypeById(@Param("id") Long id);

    /**
     * Find all PENDING signals with expiresAt (for startup timer recovery).
     */
    @Query("SELECT s FROM SignalWaitEntity s WHERE s.status = 'PENDING' AND s.expiresAt IS NOT NULL")
    List<SignalWaitEntity> findPendingWithExpiration();

    /**
     * Find a signal by webhook token in signal_config JSONB.
     */
    @Query(value = """
        SELECT * FROM orchestrator.workflow_signal_waits
        WHERE signal_type = 'WEBHOOK_WAIT' AND status = 'PENDING'
              AND signal_config @> :tokenJson\\:\\:jsonb
        """, nativeQuery = true)
    Optional<SignalWaitEntity> findByWebhookToken(@Param("tokenJson") String tokenJson);

    /**
     * Find resolved signals for runs that are still RUNNING (for startup recovery).
     */
    @Query(value = """
        SELECT sw.* FROM orchestrator.workflow_signal_waits sw
        INNER JOIN orchestrator.workflow_runs wr ON sw.run_id = wr.run_id_public
        WHERE sw.status = 'RESOLVED'
              AND sw.resolved_at > :since
              AND wr.status = 'RUNNING'
        """, nativeQuery = true)
    List<SignalWaitEntity> findResolvedSignalsForRunningWorkflows(@Param("since") Instant since);

    /**
     * Find run IDs (from a candidate set) that have at least one active USER_APPROVAL signal.
     * Used by the board endpoint to batch-detect "needs review" workflows.
     */
    @Query(value = """
        SELECT DISTINCT sw.run_id FROM orchestrator.workflow_signal_waits sw
        WHERE sw.run_id IN :runIds
          AND sw.signal_type = 'USER_APPROVAL'
          AND sw.status IN ('PENDING', 'CLAIMED')
        """, nativeQuery = true)
    List<String> findRunIdsWithPendingApprovals(@Param("runIds") Collection<String> runIds);

    /**
     * Find signal by run, node, item, and epoch (any status).
     */
    Optional<SignalWaitEntity> findByRunIdAndNodeIdAndItemIdAndEpoch(
            String runId, String nodeId, String itemId, int epoch);

    /**
     * Find active signals for a specific node, ordered by itemId.
     * Used by the pending signals endpoint to expose per-item detail to the frontend.
     */
    @Query("SELECT s FROM SignalWaitEntity s WHERE s.runId = :runId AND s.nodeId = :nodeId AND s.status IN ('PENDING', 'CLAIMED') ORDER BY s.itemId")
    List<SignalWaitEntity> findActiveByRunIdAndNodeId(@Param("runId") String runId, @Param("nodeId") String nodeId);

    /**
     * Count ALL signals (any status) for a specific node in a run and epoch.
     * Used for split context detection: if total > 1, the node had multiple signals (split context).
     * Epoch-scoped to avoid false positives from signals in other trigger cycles.
     */
    @Query("SELECT COUNT(s) FROM SignalWaitEntity s WHERE s.runId = :runId AND s.nodeId = :nodeId AND s.epoch = :epoch")
    long countAllByRunIdAndNodeIdAndEpoch(@Param("runId") String runId, @Param("nodeId") String nodeId, @Param("epoch") int epoch);

    /**
     * Find resolved signals for a split-context node in one epoch.
     * Used by resume reconciliation when one async resume path won the dedup key
     * before persisting that signal's workflow_step_data output.
     */
    @Query("SELECT s FROM SignalWaitEntity s WHERE s.runId = :runId AND s.nodeId = :nodeId AND s.epoch = :epoch AND s.status = 'RESOLVED' ORDER BY s.itemId")
    List<SignalWaitEntity> findResolvedByRunIdAndNodeIdAndEpoch(
            @Param("runId") String runId,
            @Param("nodeId") String nodeId,
            @Param("epoch") int epoch);

    /**
     * Count ACTIVE signals (PENDING or CLAIMED) for a specific node in a run, scoped to a
     * single (dag_trigger_id, epoch). Used by {@code UnifiedSignalService.resolveSignal}
     * to compute split-context "hasRemainingSignals" - a signal in another epoch for the
     * same node must NOT keep the resolving epoch's wait stuck in awaitingSignalNodeIds.
     *
     * <p>Regression context (2026-05-06, run_<id>): the previous run-wide
     * {@code findActiveByRunIdAndNodeId} returned signals from OTHER epochs of the same
     * reusable-trigger run, causing {@code keepInAwaiting=true} on the resolving epoch's
     * EpochState mutation, which silently dropped the wait→completed transition. Echo
     * never fired, run force-FAILed by the watchdog 5 min later.
     */
    @Query("SELECT COUNT(s) FROM SignalWaitEntity s "
        + "WHERE s.runId = :runId AND s.nodeId = :nodeId "
        + "AND s.dagTriggerId = :dagTriggerId AND s.epoch = :epoch "
        + "AND s.status IN ('PENDING', 'CLAIMED')")
    long countActiveByRunIdAndNodeIdAndDagAndEpoch(
            @Param("runId") String runId,
            @Param("nodeId") String nodeId,
            @Param("dagTriggerId") String dagTriggerId,
            @Param("epoch") int epoch);

    /**
     * Find a PENDING signal by correlationId (for async agent execution resolution).
     */
    Optional<SignalWaitEntity> findByCorrelationIdAndStatus(String correlationId, SignalWaitStatus status);

    /**
     * Find pending agent execution signals created before a cutoff (for missed result polling).
     */
    @Query(value = """
        SELECT * FROM orchestrator.workflow_signal_waits
        WHERE signal_type = 'AGENT_EXECUTION' AND status = 'PENDING'
              AND created_at < :cutoff
        ORDER BY created_at
        LIMIT :limit
        """, nativeQuery = true)
    List<SignalWaitEntity> findPendingAgentExecutionsBefore(
            @Param("cutoff") Instant cutoff,
            @Param("limit") int limit);
}
