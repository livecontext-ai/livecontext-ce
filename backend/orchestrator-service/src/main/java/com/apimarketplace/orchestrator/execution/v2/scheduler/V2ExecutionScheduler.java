package com.apimarketplace.orchestrator.execution.v2.scheduler;

import java.util.concurrent.CompletableFuture;

/**
 * V2 Execution Scheduler - Strategy interface for controlling execution flow.
 *
 * Inspired by legacy ExecutionScheduler but adapted for V2 design:
 * - Stateless design - state stored externally in scheduler service
 * - Uses CompletableFuture for non-blocking pause/resume
 * - Supports both AUTO (immediate) and STEP_BY_STEP (wait for signal) modes
 */
public interface V2ExecutionScheduler {

    /**
     * Scheduler type enum.
     */
    enum SchedulerType {
        AUTO,
        STEP_BY_STEP
    }

    /**
     * Get the type of this scheduler.
     */
    SchedulerType getType();

    /**
     * Called before each node execution.
     * Returns a future that completes when execution should proceed.
     *
     * AUTO: Returns completed future immediately.
     * STEP_BY_STEP: Returns future that waits for signal.
     *
     * @param runId   The workflow run ID
     * @param itemId  The trigger item ID
     * @param nodeId  The node ID to execute
     * @return Future that completes when execution should proceed
     */
    CompletableFuture<Void> awaitProceed(String runId, String itemId, String nodeId);

    /**
     * Signals that execution should proceed for a node.
     * Called by external trigger (API endpoint).
     *
     * @param runId   The workflow run ID
     * @param itemId  The trigger item ID (use "*" for all items)
     * @param nodeId  The node ID to proceed
     */
    void signalProceed(String runId, String itemId, String nodeId);

    /**
     * Cleanup resources for a completed or cancelled run.
     *
     * @param runId The workflow run ID to cleanup
     */
    void cleanup(String runId);
}
