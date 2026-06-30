package com.apimarketplace.orchestrator.services.stepbystep;

import com.apimarketplace.orchestrator.domain.PendingSignalEntity;
import com.apimarketplace.orchestrator.domain.PendingSignalEntity.SignalType;
import com.apimarketplace.orchestrator.repository.PendingSignalRepository;
import com.apimarketplace.orchestrator.services.cache.RunScopedCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;

/**
 * Database-backed service for pending and pre-approved signals in step-by-step mode.
 * Replaces RedisStepByStepCache for signal persistence.
 *
 * Benefits over Redis:
 * - Single source of truth (DB only, no cache sync issues)
 * - Survives server restart
 * - Transactional consistency with other DB operations
 * - No separate infrastructure to manage
 *
 * Signal semantics:
 * - PENDING: Node is ready and waiting for user to click "Execute"
 * - PRE_APPROVED: User clicked "Execute" before node was ready (queued approval)
 */
@Service
public class PendingSignalDbService implements RunScopedCache {

    private static final Logger log = LoggerFactory.getLogger(PendingSignalDbService.class);

    // Default expiry for pre-approved signals (2 hours)
    private static final long PRE_APPROVAL_EXPIRY_HOURS = 2;

    private final PendingSignalRepository repository;

    public PendingSignalDbService(PendingSignalRepository repository) {
        this.repository = repository;
        log.info("PendingSignalDbService initialized (replaces Redis for step-by-step signals)");
    }

    // ========================================================================
    // PENDING SIGNAL OPERATIONS
    // ========================================================================

    /**
     * Mark a node as pending (waiting for user action).
     *
     * @param runId  The workflow run ID
     * @param itemId The trigger item ID
     * @param nodeId The node ID waiting for execution
     */
    @Transactional
    public void markPending(String runId, String itemId, String nodeId) {
        if (repository.existsPending(runId, itemId, nodeId)) {
            log.debug("[PendingSignal] Already pending: runId={}, itemId={}, nodeId={}", runId, itemId, nodeId);
            return;
        }

        PendingSignalEntity entity = PendingSignalEntity.pending(runId, itemId, nodeId);
        repository.save(entity);
        log.debug("[PendingSignal] Marked pending: runId={}, itemId={}, nodeId={}", runId, itemId, nodeId);
    }

    /**
     * Check if a node is pending.
     */
    @Transactional(readOnly = true)
    public boolean isPending(String runId, String itemId, String nodeId) {
        return repository.existsPending(runId, itemId, nodeId);
    }

    /**
     * Remove pending status (node is no longer waiting).
     */
    @Transactional
    public void removePending(String runId, String itemId, String nodeId) {
        repository.deletePending(runId, itemId, nodeId);
        log.debug("[PendingSignal] Removed pending: runId={}, itemId={}, nodeId={}", runId, itemId, nodeId);
    }

    /**
     * Get all pending node IDs for a run.
     */
    @Transactional(readOnly = true)
    public Set<String> getPendingNodeIds(String runId) {
        return repository.findPendingNodeIds(runId);
    }

    // ========================================================================
    // PRE-APPROVED SIGNAL OPERATIONS
    // ========================================================================

    /**
     * Mark a node as pre-approved (user clicked Execute before node was ready).
     *
     * @param runId  The workflow run ID
     * @param itemId The trigger item ID
     * @param nodeId The node ID to pre-approve
     */
    @Transactional
    public void markPreApproved(String runId, String itemId, String nodeId) {
        if (repository.existsPreApproved(runId, itemId, nodeId)) {
            log.debug("[PendingSignal] Already pre-approved: runId={}, itemId={}, nodeId={}", runId, itemId, nodeId);
            return;
        }

        Instant expiresAt = Instant.now().plus(PRE_APPROVAL_EXPIRY_HOURS, ChronoUnit.HOURS);
        PendingSignalEntity entity = PendingSignalEntity.preApprovedWithExpiry(runId, itemId, nodeId, expiresAt);
        repository.save(entity);
        log.debug("[PendingSignal] Marked pre-approved: runId={}, itemId={}, nodeId={}", runId, itemId, nodeId);
    }

    /**
     * Check if a node is pre-approved.
     */
    @Transactional(readOnly = true)
    public boolean isPreApproved(String runId, String itemId, String nodeId) {
        Optional<PendingSignalEntity> signal = repository.findPreApproved(runId, itemId, nodeId);
        return signal.isPresent() && !signal.get().isExpired();
    }

    /**
     * Consume a pre-approval (use it and delete).
     * Returns true if there was a valid pre-approval to consume.
     */
    @Transactional
    public boolean consumePreApproval(String runId, String itemId, String nodeId) {
        Optional<PendingSignalEntity> signal = repository.findPreApproved(runId, itemId, nodeId);

        if (signal.isEmpty()) {
            return false;
        }

        if (signal.get().isExpired()) {
            repository.delete(signal.get());
            log.debug("[PendingSignal] Pre-approval expired: runId={}, itemId={}, nodeId={}", runId, itemId, nodeId);
            return false;
        }

        repository.delete(signal.get());
        log.debug("[PendingSignal] Consumed pre-approval: runId={}, itemId={}, nodeId={}", runId, itemId, nodeId);
        return true;
    }

    /**
     * Consume wildcard pre-approval (any item ID).
     * Used when user pre-approves a node without knowing the item ID.
     */
    @Transactional
    public boolean consumeWildcardPreApproval(String runId, String nodeId) {
        // Try with wildcard item ID first
        if (consumePreApproval(runId, "*", nodeId)) {
            return true;
        }
        // Try with "0" as default item ID
        return consumePreApproval(runId, "0", nodeId);
    }

    /**
     * Get all pre-approved node IDs for a run.
     */
    @Transactional(readOnly = true)
    public Set<String> getPreApprovedNodeIds(String runId) {
        return repository.findPreApprovedNodeIds(runId);
    }

    /**
     * Get all pending item IDs for a specific node.
     * Used for Split scenarios where multiple items wait on the same node.
     */
    @Transactional(readOnly = true)
    public Set<String> getPendingItemIdsForNode(String runId, String nodeId) {
        return repository.findPendingItemIdsForNode(runId, nodeId);
    }

    // ========================================================================
    // COMBINED OPERATIONS
    // ========================================================================

    /**
     * Signal a node (remove pending, optionally consume pre-approval).
     * Returns true if the node can proceed (was pending or had pre-approval).
     */
    @Transactional
    public boolean signalNode(String runId, String itemId, String nodeId) {
        // Remove pending status
        removePending(runId, itemId, nodeId);

        // Consume any pre-approval
        consumePreApproval(runId, itemId, nodeId);

        return true;
    }

    /**
     * Check if a node should auto-execute (has pre-approval).
     */
    @Transactional
    public boolean shouldAutoExecute(String runId, String itemId, String nodeId) {
        // Check for specific pre-approval
        if (consumePreApproval(runId, itemId, nodeId)) {
            log.info("[PendingSignal] Auto-executing via pre-approval: runId={}, nodeId={}", runId, nodeId);
            return true;
        }

        // Check for wildcard pre-approval
        if (consumeWildcardPreApproval(runId, nodeId)) {
            log.info("[PendingSignal] Auto-executing via wildcard pre-approval: runId={}, nodeId={}", runId, nodeId);
            return true;
        }

        return false;
    }

    // ========================================================================
    // CLEANUP OPERATIONS
    // ========================================================================

    /**
     * Delete all signals for a run (called on run completion).
     */
    @Transactional
    public void deleteByRunId(String runId) {
        repository.deleteByRunId(runId);
        log.debug("[PendingSignal] Deleted all signals for runId={}", runId);
    }

    /**
     * Periodic cleanup of expired pre-approvals.
     * Runs every 10 minutes.
     */
    @Scheduled(fixedDelay = 600000) // 10 minutes
    @SchedulerLock(name = "pending_signal_cleanup", lockAtMostFor = "PT5M")
    @Transactional
    public void cleanupExpiredPreApprovals() {
        try {
            int deleted = repository.deleteExpiredPreApprovals(Instant.now());
            if (deleted > 0) {
                log.info("[PendingSignal] Cleaned up {} expired pre-approvals", deleted);
            }
        } catch (Exception e) {
            log.error("[PendingSignal] Error in expired pre-approvals cleanup: {}", e.getMessage(), e);
        }
    }

    // ========================================================================
    // RunScopedCache IMPLEMENTATION
    // ========================================================================

    @Override
    public String getCacheName() {
        return "PendingSignalDbService";
    }

    @Override
    public CacheDomain getDomain() {
        return CacheDomain.CONTROL_FLOW;
    }

    @Override
    @Transactional
    public void cleanupRun(String runId) {
        deleteByRunId(runId);
    }

    @Override
    @Transactional(readOnly = true)
    public int getCacheSize() {
        return (int) repository.count();
    }
}
