package com.apimarketplace.orchestrator.execution.v2.scheduler;

import com.apimarketplace.orchestrator.services.stepbystep.PendingSignalDbService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Step-by-step scheduler - waits for user signal before each node.
 *
 * Now backed by PostgreSQL for pending/pre-approved state persistence.
 * CompletableFutures remain in-memory (required for await semantics),
 * but DB ensures state survives for signal matching after page reload.
 *
 * Pattern:
 * 1. awaitProceed() marks node as pending in DB + creates local Future
 * 2. signalProceed() removes from DB + completes local Future
 * 3. If server restarts, DB state allows signal matching
 *
 * Key format: runId:itemId:nodeId
 */
@Component
public class V2StepByStepScheduler implements V2ExecutionScheduler {

    private static final Logger logger = LoggerFactory.getLogger(V2StepByStepScheduler.class);
    private static final long TIMEOUT_MINUTES = 30;

    // Local futures for await semantics (cannot be persisted)
    private final Map<String, CompletableFuture<Void>> pendingNodes = new ConcurrentHashMap<>();

    // DB service for persistent state (replaces Redis)
    private final PendingSignalDbService pendingSignalService;

    public V2StepByStepScheduler(PendingSignalDbService pendingSignalService) {
        this.pendingSignalService = pendingSignalService;
        logger.info("V2StepByStepScheduler initialized with PendingSignalDbService (DB-backed)");
    }

    @Override
    public SchedulerType getType() {
        return SchedulerType.STEP_BY_STEP;
    }

    @Override
    public CompletableFuture<Void> awaitProceed(String runId, String itemId, String nodeId) {
        String key = buildKey(runId, itemId, nodeId);

        // Check pre-approval in DB (signal received before await)
        if (pendingSignalService.shouldAutoExecute(runId, itemId, nodeId)) {
            logger.debug("[V2StepByStep] Node {} pre-approved in DB, proceeding immediately", nodeId);
            return CompletableFuture.completedFuture(null);
        }

        // Mark as pending in DB
        pendingSignalService.markPending(runId, itemId, nodeId);

        // Create local future to wait on
        CompletableFuture<Void> future = new CompletableFuture<>();
        pendingNodes.put(key, future);

        logger.info("[V2StepByStep] Node {} waiting for proceed signal (runId={}, itemId={})",
            nodeId, runId, itemId);

        // Add timeout to prevent indefinite waiting
        return future.orTimeout(TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .exceptionally(ex -> {
                logger.warn("[V2StepByStep] Timeout waiting for node {} after {} minutes",
                    nodeId, TIMEOUT_MINUTES);
                pendingNodes.remove(key);
                pendingSignalService.removePending(runId, itemId, nodeId);
                return null;
            });
    }

    @Override
    public void signalProceed(String runId, String itemId, String nodeId) {
        String key = buildKey(runId, itemId, nodeId);

        // Remove from DB pending
        pendingSignalService.removePending(runId, itemId, nodeId);

        // Try to complete specific local future first
        CompletableFuture<Void> future = pendingNodes.remove(key);
        if (future != null) {
            logger.info("[V2StepByStep] Signaling proceed for node {} (runId={}, itemId={})",
                nodeId, runId, itemId);
            future.complete(null);
            return;
        }

        // If itemId is wildcard, try to complete all matching local futures
        if ("*".equals(itemId)) {
            String prefix = runId + ":";
            String suffix = ":" + nodeId;
            boolean found = false;

            for (Map.Entry<String, CompletableFuture<Void>> entry : pendingNodes.entrySet()) {
                if (entry.getKey().startsWith(prefix) && entry.getKey().endsWith(suffix)) {
                    pendingNodes.remove(entry.getKey());
                    entry.getValue().complete(null);
                    found = true;
                    logger.info("[V2StepByStep] Signaled proceed for node {} via wildcard (key={})",
                        nodeId, entry.getKey());
                }
            }
            if (found) return;
        }

        // Pre-approve in DB for future awaitProceed call
        logger.debug("[V2StepByStep] Pre-approving node {} in DB (key={})", nodeId, key);
        pendingSignalService.markPreApproved(runId, itemId, nodeId);
    }

    @Override
    public void cleanup(String runId) {
        String prefix = runId + ":";

        // Cancel all local pending futures for this run
        pendingNodes.entrySet().removeIf(entry -> {
            if (entry.getKey().startsWith(prefix)) {
                entry.getValue().cancel(true);
                logger.debug("[V2StepByStep] Cancelled pending node: {}", entry.getKey());
                return true;
            }
            return false;
        });

        // DB cleanup is handled by PendingSignalDbService.cleanupRun()
        // which is called from RunCacheRegistry

        logger.info("[V2StepByStep] Cleaned up local resources for run {}", runId);
    }

    /**
     * Get pending node IDs for a run (from DB).
     *
     * @param runId The run ID to query
     * @return Set of node IDs currently waiting for signal
     */
    public Set<String> getPendingNodeIds(String runId) {
        return pendingSignalService.getPendingNodeIds(runId);
    }

    /**
     * Check if a specific node is currently waiting for signal.
     */
    public boolean isNodeWaiting(String runId, String itemId, String nodeId) {
        return pendingSignalService.isPending(runId, itemId, nodeId);
    }

    /**
     * Get count of pending nodes for a run.
     */
    public int getPendingCount(String runId) {
        return pendingSignalService.getPendingNodeIds(runId).size();
    }

    /**
     * Get all pending itemIds for a specific nodeId.
     * Returns itemIds like "0.1", "0.2", etc. for Split child items.
     *
     * @param runId The run ID
     * @param nodeId The node ID to query
     * @return Set of itemIds pending for this node
     */
    public Set<String> getPendingItemIdsForNode(String runId, String nodeId) {
        return pendingSignalService.getPendingItemIdsForNode(runId, nodeId);
    }

    /**
     * Mark a node as pending without waiting.
     * Used to register ready nodes for later retrieval (e.g., Split spawn items).
     *
     * @param runId The run ID
     * @param itemId The item ID (e.g., "0.1" for Split children)
     * @param nodeId The node ID
     */
    public void markAsPending(String runId, String itemId, String nodeId) {
        pendingSignalService.markPending(runId, itemId, nodeId);
        logger.debug("[V2StepByStep] Marked node as pending for later execution: runId={}, itemId={}, nodeId={}",
            runId, itemId, nodeId);
    }

    /**
     * Remove pending status for a node.
     * Used after executing a node that was marked as pending.
     *
     * @param runId The run ID
     * @param itemId The item ID
     * @param nodeId The node ID
     */
    public void removePending(String runId, String itemId, String nodeId) {
        pendingSignalService.removePending(runId, itemId, nodeId);
        logger.debug("[V2StepByStep] Removed pending status: runId={}, itemId={}, nodeId={}",
            runId, itemId, nodeId);
    }

    private String buildKey(String runId, String itemId, String nodeId) {
        return runId + ":" + itemId + ":" + nodeId;
    }
}
