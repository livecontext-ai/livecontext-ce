package com.apimarketplace.orchestrator.execution.v2.scheduler;

import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Auto scheduler - immediate execution without waiting.
 *
 * This is the default scheduler for automatic workflow execution.
 * All methods return immediately without blocking.
 */
@Component
public class V2AutoScheduler implements V2ExecutionScheduler {

    @Override
    public SchedulerType getType() {
        return SchedulerType.AUTO;
    }

    @Override
    public CompletableFuture<Void> awaitProceed(String runId, String itemId, String nodeId) {
        // Auto mode: immediate execution, no waiting
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void signalProceed(String runId, String itemId, String nodeId) {
        // No-op - auto mode doesn't wait for signals
    }

    @Override
    public void cleanup(String runId) {
        // No-op - no state to clean in auto mode
    }
}
