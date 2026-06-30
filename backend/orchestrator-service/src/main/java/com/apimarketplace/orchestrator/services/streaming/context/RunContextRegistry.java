package com.apimarketplace.orchestrator.services.streaming.context;

import com.apimarketplace.orchestrator.services.cache.RunCacheRegistry;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.state.RunState;
import com.apimarketplace.orchestrator.services.streaming.state.RunStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry for all active workflow run contexts.
 *
 * <p>This is the SINGLE source of truth for run state management.
 * All services should use this registry instead of maintaining their own maps.
 *
 * <p>Benefits:
 * <ul>
 *   <li>One registry instead of 9+ scattered maps</li>
 *   <li>One close() call cleans up everything via RunCacheRegistry</li>
 *   <li>Impossible to forget cleanup for a specific service</li>
 *   <li>Clear ownership and lifecycle management</li>
 *   <li>Built-in stale run cleanup as safety net (every 10 min, max 2h age)</li>
 * </ul>
 *
 * <p>Cache cleanup is delegated to {@link RunCacheRegistry} which auto-discovers
 * all services implementing {@link com.apimarketplace.orchestrator.services.cache.RunScopedCache}.
 */
@Component
public class RunContextRegistry {

    private static final Logger log = LoggerFactory.getLogger(RunContextRegistry.class);

    /**
     * Maximum age for a run context before it's considered stale (2 hours).
     * This is a safety net - normally contexts are closed explicitly.
     */
    private static final long MAX_CONTEXT_AGE_MS = 2 * 60 * 60 * 1000L;
    private static final String ACTIVE_RUNS_KEY_PREFIX = "orch:instance:active-runs:";
    /** TTL for active-runs keys - auto-expires if instance crashes without cleanup. */
    private static final long ACTIVE_RUNS_TTL_SECONDS = 300; // 5 minutes

    private final Map<String, RunContext> contexts = new ConcurrentHashMap<>();

    // Dependencies for creating RunState
    private final StateSnapshotService stateSnapshotService;

    // Single dependency for ALL cache cleanup (SOLID - Single Responsibility)
    private final RunCacheRegistry cacheRegistry;

    /**
     * Optional Redis template for tracking active runs per instance (multi-instance mode).
     * When present, enables InstanceFailoverService to detect orphaned runs.
     */
    @Nullable
    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    /**
     * Instance ID for Redis active-run tracking. Injected from OrchestratorInstanceRegistrar.
     */
    @Nullable
    private volatile String instanceId;

    public RunContextRegistry(StateSnapshotService stateSnapshotService, @Lazy RunCacheRegistry cacheRegistry) {
        this.stateSnapshotService = stateSnapshotService;
        this.cacheRegistry = cacheRegistry;
        log.info("RunContextRegistry initialized with RunCacheRegistry");
    }

    /**
     * Set the instance ID for distributed active-run tracking.
     * Called by OrchestratorInstanceRegistrar after registration.
     */
    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
        log.info("[RunContextRegistry] Instance ID set for active-run tracking: {}", instanceId);
    }

    // ==================== Core Operations ====================

    /**
     * Gets or creates a RunContext for the given runId.
     *
     * @param runId The workflow run ID
     * @return The RunContext (never null)
     */
    public RunContext getOrCreate(String runId) {
        RunContext[] created = {null};
        RunContext ctx = contexts.computeIfAbsent(runId, id -> {
            RunContext c = createContext(id);
            created[0] = c;
            return c;
        });
        if (created[0] != null) {
            trackActiveRun(runId);
        }
        return ctx;
    }

    /**
     * Gets an existing RunContext if it exists.
     *
     * @param runId The workflow run ID
     * @return Optional containing the context, or empty if not found
     */
    public Optional<RunContext> get(String runId) {
        return Optional.ofNullable(contexts.get(runId));
    }

    /**
     * Checks if a context exists for the given runId.
     *
     * @param runId The workflow run ID
     * @return true if context exists
     */
    public boolean exists(String runId) {
        return contexts.containsKey(runId);
    }

    /**
     * Closes and removes the context for the given runId.
     * This is the ONLY way to clean up a run - one call cleans everything.
     *
     * @param runId The workflow run ID
     * @return true if a context was closed, false if not found
     */
    public boolean close(String runId) {
        RunContext context = contexts.remove(runId);
        if (context == null) {
            log.debug("No context found to close for runId={}", runId);
            return false;
        }

        context.close();
        untrackActiveRun(runId);
        return true;
    }

    /**
     * Closes an epoch using the rerun pattern (resetDag + setReady).
     *
     * <p>This is the preferred method for STEP_BY_STEP mode as it uses the same
     * pattern as node rerun, ensuring consistency across the codebase.
     *
     * <p>Instead of just resetting execution state, this method:
     * <ul>
     *   <li>Removes all nodes from completed/failed/skipped/running/ready sets</li>
     *   <li>Explicitly marks the trigger as READY (no recalculation needed)</li>
     *   <li>Clears all in-memory caches</li>
     * </ul>
     *
     * @param runId The workflow run ID
     * @param allNodeIds All node IDs in the workflow
     * @param triggerId The trigger node ID to mark as ready
     */
    public void closeEpochWithRerun(String runId, Set<String> allNodeIds, String triggerId) {
        log.info("[RunContextRegistry] Closing epoch (rerun pattern) for runId={}, triggerId={}", runId, triggerId);

        // 1. Reset execution state and mark trigger as ready (same as rerun pattern)
        stateSnapshotService.resetDagAndSetReady(runId, allNodeIds, triggerId);

        // 2. NOTE 2026-05-05: cacheRegistry.cleanupRun(runId) was REMOVED here
        // for the same reasons as closeEpochForDagByTriggerId - see the comment
        // there. Run is still active; cleanup at terminal only.

        // 3. Remove the RunContext (per-execution state) so it gets recreated fresh.
        RunContext context = contexts.remove(runId);
        if (context != null) {
            context.close();
        }

        log.info("[RunContextRegistry] Epoch closed (rerun pattern) for runId={}", runId);
    }

    /**
     * Closes an epoch for a specific DAG using the rerun pattern.
     *
     * <p>Resets the execution state of nodes belonging to the specified DAG
     * and explicitly marks the trigger as READY.
     *
     * @param runId The workflow run ID
     * @param dagNodeIds Set of node IDs belonging to the DAG to reset
     * @param triggerId The trigger node ID to mark as ready
     */
    public void closeEpochForDagWithRerun(String runId, Set<String> dagNodeIds, String triggerId) {
        log.info("[RunContextRegistry] Closing epoch for DAG (rerun pattern) in runId={}, triggerId={}", runId, triggerId);

        // 1. Reset execution state and mark trigger as ready (rerun pattern)
        stateSnapshotService.resetDagAndSetReady(runId, dagNodeIds, triggerId);

        // 2. NOTE 2026-05-05: cacheRegistry.cleanupRun(runId) was REMOVED here
        // for the same reasons as closeEpochForDagByTriggerId - see the comment
        // there. Run is still active; cleanup at terminal only.

        // 3. Remove the RunContext to free per-execution state (RunState, RunNodeState).
        //    getOrCreate() will lazily recreate a fresh context when the next cycle starts.
        RunContext context = contexts.remove(runId);
        if (context != null) {
            context.close();
        }

        log.info("[RunContextRegistry] DAG epoch closed (rerun pattern) for runId={}, triggerId={}", runId, triggerId);
    }

    /**
     * Close epoch for a specific DAG using triggerId-based reset (no BFS collection needed).
     * Uses the new per-DAG StateSnapshot structure to reset by triggerId directly.
     *
     * @param runId The workflow run ID
     * @param triggerId The trigger ID for the DAG to reset
     * @param newGlobalEpoch The new global epoch number for this DAG
     */
    public void closeEpochForDagByTriggerId(String runId, String triggerId, int newGlobalEpoch) {
        log.info("[RunContextRegistry] Closing epoch for DAG by triggerId: runId={}, triggerId={}, newEpoch={}", runId, triggerId, newGlobalEpoch);

        // 1. Prepare DAG for next cycle WITHOUT creating an active epoch.
        // Uses prepareDagForNextCycle instead of resetDagEpochAndSetReady to avoid
        // creating phantom active epochs that block WAITING_TRIGGER transition.
        stateSnapshotService.prepareDagForNextCycle(runId, triggerId, newGlobalEpoch);

        // 2. NOTE 2026-05-05: cacheRegistry.cleanupRun(runId) was REMOVED here.
        // Reusable triggers transition to WAITING_TRIGGER (non-terminal), and the
        // run keeps publishing events on the same runId for fire #2, fire #3, ….
        // Calling cleanupRun on the active runId broke two invariants:
        //   (a) WsEventSequencer.cleanupRun purged the seq counter; in-flight
        //       publishes from fire #N still resolved with their already-bumped
        //       seq while fire #N+1's reseed gave a colliding seq, so late events
        //       arrived under the FE's lastKnownSeq high-water and got
        //       strict-< dropped - including the final batch-update that flipped
        //       a node from RUNNING → COMPLETED, leaving the shimmer stuck.
        //   (b) SnapshotService.cleanupRun pre-warmed the terminatedRunsCache
        //       tombstone, so fire #N+1's markDirty short-circuited and no
        //       batch-update ever reached the wire (frozen UI on fire #2).
        // True terminal cleanup happens elsewhere when the run actually
        // terminates (RunStatus.isTerminal()). Keep the RunContext removal
        // below - it's per-execution context (RunState/RunNodeState), not
        // run-scoped, and benefits from a fresh allocation per cycle.

        // 3. Remove the RunContext to free per-execution state.
        // getOrCreate() will lazily recreate a fresh context on the next cycle.
        RunContext context = contexts.remove(runId);
        if (context != null) {
            context.close();
        }

        log.info("[RunContextRegistry] DAG epoch closed by triggerId for runId={}, triggerId={}", runId, triggerId);
    }

    /**
     * Closes all contexts. Used for shutdown.
     */
    public void closeAll() {
        log.info("Closing all {} RunContexts", contexts.size());
        List<String> runIds = new ArrayList<>(contexts.keySet());
        for (String runId : runIds) {
            close(runId);
        }
    }

    // ==================== Convenience Methods ====================

    /**
     * Gets the RunState for a runId, creating context if needed.
     *
     * @param runId The workflow run ID
     * @return The RunState
     */
    public RunState getRunState(String runId) {
        return getOrCreate(runId).getRunState();
    }

    /**
     * Gets the RunNodeState for a runId, creating context if needed.
     *
     * @param runId The workflow run ID
     * @return The RunNodeState
     */
    public RunNodeState getNodeState(String runId) {
        return getOrCreate(runId).getNodeState();
    }

    /**
     * Gets a snapshot for a runId.
     *
     * @param runId The workflow run ID
     * @return The snapshot, or null if context doesn't exist
     */
    public RunStateStore.RunSnapshot snapshot(String runId) {
        RunContext context = contexts.get(runId);
        return context != null ? context.snapshot() : null;
    }

    /**
     * Checks if a run is finalized.
     *
     * @param runId The workflow run ID
     * @return true if finalized
     */
    public boolean isFinalized(String runId) {
        RunContext context = contexts.get(runId);
        return context != null && context.isFinalized();
    }

    /**
     * Marks a run as finalized.
     *
     * @param runId The workflow run ID
     * @return true if successfully marked (was not already finalized)
     */
    public boolean markFinalized(String runId) {
        RunContext context = contexts.get(runId);
        return context != null && context.markFinalized();
    }

    // ==================== Batch State Methods ====================

    /**
     * Gets the last payload for a run.
     *
     * @param runId The workflow run ID
     * @return The last payload, or null
     */
    public Map<String, Object> getLastPayload(String runId) {
        RunContext context = contexts.get(runId);
        return context != null ? context.getLastPayload() : null;
    }

    /**
     * Sets the last payload for a run.
     *
     * @param runId The workflow run ID
     * @param payload The payload to cache
     */
    public void setLastPayload(String runId, Map<String, Object> payload) {
        RunContext context = contexts.get(runId);
        if (context != null) {
            context.setLastPayload(payload);
        }
    }

    // ==================== Statistics ====================

    /**
     * Gets the number of active contexts.
     */
    public int size() {
        return contexts.size();
    }

    // ==================== Stale Context Cleanup ====================

    /**
     * Periodic cleanup of stale contexts.
     * This is a safety net - normally contexts are closed explicitly when runs complete.
     * Runs every 10 minutes.
     */
    @Scheduled(fixedDelay = 600000) // 10 minutes
    public void cleanupStaleContexts() {
        try {
            long now = System.currentTimeMillis();
            int cleaned = 0;

            // Collect stale keys first to avoid ConcurrentModificationException
            // (close() calls contexts.remove() which would modify the map during iteration)
            List<String> staleRunIds = new ArrayList<>();
            for (Map.Entry<String, RunContext> entry : contexts.entrySet()) {
                long age = now - entry.getValue().getCreatedAt();
                if (age > MAX_CONTEXT_AGE_MS) {
                    staleRunIds.add(entry.getKey());
                }
            }

            for (String runId : staleRunIds) {
                try {
                    log.warn("Cleaning up stale RunContext: runId={}", runId);
                    close(runId);
                    cleaned++;
                } catch (Exception e) {
                    log.error("Error cleaning up stale context for runId={}: {}", runId, e.getMessage(), e);
                }
            }

            if (cleaned > 0) {
                log.info("Cleaned up {} stale RunContexts", cleaned);
            }
        } catch (Exception e) {
            log.error("[RunContextRegistry] Error in stale context cleanup: {}", e.getMessage(), e);
        }
    }

    // ==================== Private ====================

    private RunContext createContext(String runId) {
        log.debug("Creating new RunContext for runId={}", runId);

        // Create all state objects - OWNED by the context
        RunState runState = new RunState(runId, stateSnapshotService);
        RunNodeState nodeState = new RunNodeState(runId);

        RunContext context = new RunContext(runId, runState, nodeState);

        // 2026-05-05: registerCleanupCallback REMOVED. The callback wrongly tied
        // RunScopedCache lifecycle (run-scoped - should survive across epochs)
        // to RunContext lifecycle (epoch-scoped - recreated on each epoch close
        // for reusable triggers via closeEpochForDagByTriggerId → context.close()
        // → callback → cacheRegistry.cleanupRun). The chain purged
        // WsEventSequencer.counters mid-run, so fire #N+1 reseeded from a stale
        // DB last_event_seq and emitted seqs already used by fire #N still in
        // flight. The duplicate seqs were strict-< dropped by the FE
        // lastKnownSeq guard, leaving terminal node states (e.g. echo
        // running → completed) silently lost. Verified locally 2026-05-05:
        // run_<id> fire #N+1 emitted seq=423 + late stale
        // seq=423/424 - UI froze with echo stuck running.
        //
        // Cache cleanup must happen on TERMINAL run state, not on epoch close.
        // For now: no auto-cleanup. Reusable triggers naturally never terminate;
        // truly terminal runs already get explicit cleanup via the existing
        // WorkflowResumeService.clearCachedStateForRerun and the planned
        // terminal-status hook (separate work item).

        return context;
    }

    /**
     * Registers a single cleanup callback that uses RunCacheRegistry.
     *
     * <p>This is much simpler than the previous approach of manually registering
     * callbacks for each service. The RunCacheRegistry auto-discovers all services
     * implementing RunScopedCache and cleans them all with one call.
     *
     * <p>Benefits:
     * <ul>
     *   <li>Single point of cleanup - impossible to forget a cache</li>
     *   <li>New caches are automatically included (no code changes needed)</li>
     *   <li>Clear separation of concerns (SOLID - Single Responsibility)</li>
     * </ul>
     */
    private void registerCleanupCallback(RunContext context, String runId) {
        context.addCleanupCallback(() -> {
            try {
                int cleaned = cacheRegistry.cleanupRun(runId);
                log.debug("Cleaned up {} caches for runId={}", cleaned, runId);
            } catch (Exception e) {
                log.warn("Error cleaning up caches for runId={}: {}", runId, e.getMessage());
            }
        });
    }

    // ==================== Distributed Active Run Tracking ====================

    /**
     * Redis key for this instance's active runs set.
     */
    private String activeRunsKey() {
        return ACTIVE_RUNS_KEY_PREFIX + instanceId;
    }

    /**
     * Static accessor for building a key for any instance (used by InstanceFailoverService).
     */
    public static String activeRunsKeyFor(String instanceId) {
        return ACTIVE_RUNS_KEY_PREFIX + instanceId;
    }

    private void trackActiveRun(String runId) {
        String id = instanceId;
        if (id == null || redisTemplate == null) return;
        try {
            String key = activeRunsKey();
            redisTemplate.opsForSet().add(key, runId);
            // Refresh TTL so key auto-expires if instance crashes
            redisTemplate.expire(key, ACTIVE_RUNS_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("[RunContextRegistry] Failed to track active run in Redis: {}", e.getMessage());
        }
    }

    private void untrackActiveRun(String runId) {
        String id = instanceId;
        if (id == null || redisTemplate == null) return;
        try {
            redisTemplate.opsForSet().remove(activeRunsKey(), runId);
        } catch (Exception e) {
            log.debug("[RunContextRegistry] Failed to untrack active run in Redis: {}", e.getMessage());
        }
    }

    /**
     * Refresh TTL on the active-runs key (called during heartbeat cycle).
     * Prevents the key from expiring while the instance is alive.
     */
    public void refreshActiveRunsTtl() {
        String id = instanceId;
        if (id == null || redisTemplate == null) return;
        try {
            redisTemplate.expire(activeRunsKey(), ACTIVE_RUNS_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("[RunContextRegistry] Failed to refresh active-runs TTL: {}", e.getMessage());
        }
    }

    /**
     * Remove the entire active-runs key for this instance (graceful shutdown).
     */
    public void clearActiveRuns() {
        String id = instanceId;
        if (id == null || redisTemplate == null) return;
        try {
            redisTemplate.delete(activeRunsKey());
            log.info("[RunContextRegistry] Cleared active-runs key for instance {}", id);
        } catch (Exception e) {
            log.warn("[RunContextRegistry] Failed to clear active-runs key: {}", e.getMessage());
        }
    }
}
