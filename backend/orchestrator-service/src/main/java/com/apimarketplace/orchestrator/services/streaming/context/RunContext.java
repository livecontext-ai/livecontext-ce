package com.apimarketplace.orchestrator.services.streaming.context;

import com.apimarketplace.orchestrator.services.streaming.state.RunState;
import com.apimarketplace.orchestrator.services.streaming.state.RunStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unified context for a single workflow run.
 *
 * <p>Holds ALL state for a run in one place:
 * <ul>
 *   <li>RunState - steps, loops, merges, logs, workflow status</li>
 *   <li>RunNodeState - node execution status counts</li>
 *   <li>LastPayload - for batch deduplication</li>
 *   <li>Finalized flag - prevents double finalization</li>
 * </ul>
 *
 * <p>Implements {@link Closeable} for clean resource management.
 * When closed, ALL resources for this run are released in one call.
 */
public class RunContext implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(RunContext.class);

    private final String runId;
    private final long createdAt;

    // Core state - OWNED by this context
    private final RunState runState;
    private final RunNodeState nodeState;

    // Batch state
    private final AtomicReference<Map<String, Object>> lastPayload = new AtomicReference<>();

    // Lifecycle flags
    private final AtomicBoolean finalized = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // Additional cleanup callbacks (merge state, Redis cache, etc.)
    private final List<Runnable> cleanupCallbacks = new ArrayList<>();

    /**
     * Creates a new RunContext that OWNS all state for this run.
     *
     * @param runId The workflow run ID
     * @param runState The run state (created by factory)
     * @param nodeState The node state for status counts
     */
    public RunContext(String runId,
                      RunState runState,
                      RunNodeState nodeState) {
        this.runId = runId;
        this.runState = runState;
        this.nodeState = nodeState;
        this.createdAt = System.currentTimeMillis();
        log.debug("RunContext created for runId={}", runId);
    }

    // ==================== Getters ====================

    public String getRunId() {
        return runId;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public RunState getRunState() {
        return runState;
    }

    /**
     * Gets the node state for this run.
     * Used by NodeEventStore to access run-specific state.
     */
    public RunNodeState getNodeState() {
        return nodeState;
    }

    // ==================== Batch State ====================

    public Map<String, Object> getLastPayload() {
        return lastPayload.get();
    }

    public void setLastPayload(Map<String, Object> payload) {
        lastPayload.set(payload);
    }

    // ==================== Finalization ====================

    public boolean isFinalized() {
        return finalized.get();
    }

    public boolean markFinalized() {
        return finalized.compareAndSet(false, true);
    }

    // ==================== Cleanup Callbacks ====================

    /**
     * Registers a cleanup callback to be executed when this context is closed.
     * Used for external resources like merge state and Redis cache.
     *
     * @param callback The cleanup action to execute
     */
    public void addCleanupCallback(Runnable callback) {
        if (callback != null && !closed.get()) {
            synchronized (cleanupCallbacks) {
                cleanupCallbacks.add(callback);
            }
        }
    }

    // ==================== Snapshot ====================

    /**
     * Get a snapshot of the run state.
     */
    public RunStateStore.RunSnapshot snapshot() {
        return runState.snapshot();
    }

    // ==================== Lifecycle ====================

    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Closes this context and releases ALL resources.
     * Safe to call multiple times (idempotent).
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            log.debug("RunContext already closed for runId={}", runId);
            return;
        }

        log.info("Closing RunContext for runId={}", runId);

        // 1. Clear last payload
        lastPayload.set(null);

        // 2. Clear node state (owned by this context)
        if (nodeState != null) {
            nodeState.clear();
        }

        // 3. Execute all registered cleanup callbacks (merge state, Redis cache, etc.)
        List<Runnable> callbacks;
        synchronized (cleanupCallbacks) {
            callbacks = new ArrayList<>(cleanupCallbacks);
            cleanupCallbacks.clear();
        }
        for (Runnable cleanupCallback : callbacks) {
            try {
                cleanupCallback.run();
            } catch (Exception e) {
                log.warn("Error executing cleanup callback for runId={}: {}", runId, e.getMessage());
            }
        }

        log.info("RunContext closed for runId={} (lifetime={}ms)", runId, System.currentTimeMillis() - createdAt);
    }

    @Override
    public String toString() {
        return "RunContext{" +
                "runId='" + runId + '\'' +
                ", hasState=" + (runState != null) +
                ", finalized=" + finalized.get() +
                ", closed=" + closed.get() +
                ", lifetime=" + (System.currentTimeMillis() - createdAt) + "ms" +
                '}';
    }
}
