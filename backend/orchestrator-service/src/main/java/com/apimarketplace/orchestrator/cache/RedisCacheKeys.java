package com.apimarketplace.orchestrator.cache;

/**
 * Centralized Redis key patterns for orchestrator-service.
 * All keys use the prefix "orchestrator:" to avoid collisions with other services.
 *
 * Key families:
 * - orchestrator:execution-graph:{planId}:{hash} - Cached execution graphs
 * - orchestrator:snapshot:{runId} - Workflow run snapshots for streaming
 * - orchestrator:paused:{runId} - Paused workflow state
 * - orchestrator:evaluated-cores:{runId} - Set of evaluated core nodes
 * - orchestrator:state-manager:{runId} - Serialized WorkflowStateManager
 * - orchestrator:lock:{resource}:{id} - Distributed locks
 * - orchestrator:wb-session:{sessionId} / orchestrator:wb-tenant:{tenantId} / orchestrator:wb-conv:{tenantId}:{conversationId} - Workflow builder sessions
 * - orchestrator:running:{runId}[:{epoch}] - Per-run / per-(run,epoch) running-node count hashes
 * - orchestrator:running-epochs:{runId} - SET tracker enumerating active per-epoch hash keys (anti redis.keys blocking, 2026-05-09)
 * - orchestrator:split-barrier:{runId}:{nodeId}:{epoch} - Split-async coalesce barrier hashes
 * - orchestrator:split-barriers:{runId} - SET tracker enumerating active split barriers per run
 * - orchestrator:signal-dedup:{runId}:{signalId} / orchestrator:lock:signal-resume:{runId} - Signal resume coordination
 * - orchestrator:signal-resume-pending:{runId}:{triggerId}:{epoch} - Resolved signal resumes still finalizing
 */
public final class RedisCacheKeys {

    private static final String PREFIX = "orchestrator:";

    private RedisCacheKeys() {
        // Utility class
    }

    // ========================================================================
    // EXECUTION GRAPH CACHE
    // ========================================================================

    /**
     * Key for cached execution graph.
     * @param planId The workflow plan ID
     * @param contentHash Hash of plan content for cache invalidation
     */
    public static String executionGraph(String planId, int contentHash) {
        return PREFIX + "execution-graph:" + planId + ":" + contentHash;
    }

    /**
     * Pattern to find all execution graph keys for a plan.
     */
    public static String executionGraphPattern(String planId) {
        return PREFIX + "execution-graph:" + planId + ":*";
    }

    // ========================================================================
    // SNAPSHOT CACHE
    // ========================================================================

    /**
     * Key for workflow run snapshot (used for WebSocket reconnection).
     */
    public static String snapshot(String runId) {
        return PREFIX + "snapshot:" + runId;
    }

    /**
     * Out-of-tx snapshot read cache (A2 Phase 4, 2026-05-09). Stores the
     * serialized {@code state_snapshot} JSON keyed by run, with the
     * {@code seq} field embedded so readers can validate freshness against
     * {@code workflow_runs.state_snapshot_seq} in O(1) without re-parsing.
     *
     * <p>Hash layout:
     * <pre>
     *   field "seq"     → BIGINT as String, the seq this payload was written for
     *   field "payload" → serialized StateSnapshot JSON
     * </pre>
     *
     * <p>Write contract: read-side warmup via {@code StateSnapshotService.getSnapshot}
     * post-DB-read, wrapped in {@code TransactionalHelper.runAfterCommitOrNow}
     * so the put never observes uncommitted state (Race-2 mitigation).
     * Lua-atomic put-only-if-newer (Race-1). Read contract: SQL is the seq
     * oracle - lookup proceeds {@code SELECT seq → HMGET → parse}; on Redis
     * miss / Redis-down, fail-OPEN to the legacy DB+parse path. TTL bounds
     * memory (~30KB × ~1000 active runs ≈ 30MB shared).
     */
    public static String snapshotCache(String runId) {
        return PREFIX + "snapshot-cache:" + runId;
    }

    // ========================================================================
    // WORKFLOW CACHE MANAGER
    // ========================================================================

    /**
     * Key for paused workflow state.
     */
    public static String pausedWorkflow(String runId) {
        return PREFIX + "paused:" + runId;
    }

    /**
     * Key for set of evaluated core nodes.
     */
    public static String evaluatedCores(String runId) {
        return PREFIX + "evaluated-cores:" + runId;
    }

    /**
     * Key for serialized WorkflowStateManager.
     */
    public static String stateManager(String runId) {
        return PREFIX + "state-manager:" + runId;
    }

    // ========================================================================
    // DISTRIBUTED LOCKS
    // ========================================================================

    /**
     * Lock key for workflow run execution.
     * Prevents duplicate execution of the same run.
     */
    public static String lockWorkflowRun(String runId) {
        return PREFIX + "lock:workflow-run:" + runId;
    }

    /**
     * Lock key for state transitions.
     * Protects concurrent state changes.
     */
    public static String lockStateTransition(String runId) {
        return PREFIX + "lock:state-transition:" + runId;
    }

    // ========================================================================
    // WORKFLOW BUILDER SESSIONS
    // ========================================================================

    /**
     * Key for workflow builder session.
     * @param sessionId The session ID (e.g., wb_abc123)
     */
    public static String workflowBuilderSession(String sessionId) {
        return PREFIX + "wb-session:" + sessionId;
    }

    /**
     * Key for tenant's active session index (legacy, for listing all tenant sessions).
     * Value is a Set of session IDs for quick lookup.
     * @param tenantId The tenant ID
     */
    public static String workflowBuilderTenantIndex(String tenantId) {
        return PREFIX + "wb-tenant:" + tenantId;
    }

    /**
     * Key for conversation-scoped session index.
     * Each conversation has at most ONE active session.
     * @param tenantId The tenant ID
     * @param conversationId The conversation ID
     */
    public static String workflowBuilderConversationIndex(String tenantId, String conversationId) {
        return PREFIX + "wb-conv:" + tenantId + ":" + conversationId;
    }

    /**
     * Pattern to find all workflow builder sessions.
     */
    public static String workflowBuilderSessionPattern() {
        return PREFIX + "wb-session:*";
    }

    // ========================================================================
    // RUNNING NODE TRACKER (Horizontal Scaling)
    // ========================================================================

    /**
     * Hash key for running node counts per workflow run.
     * Hash fields: nodeId → running count (integer).
     *
     * <p>Legacy flat key shape - pre-P2.1. Kept for backward compat: in-flight runs
     * that started under the old code continue to write/read this key shape until
     * the overlap window drains. New code should prefer {@link #runningNodes(String, int)}.
     */
    public static String runningNodes(String runId) {
        return PREFIX + "running:" + runId;
    }

    /**
     * Hash key for per-(runId, epoch) running node counts (P2.1).
     * Adds epoch dimension so the deferred-reset gate at
     * {@code ReusableTriggerService:1586} can ask "is THIS epoch still running?"
     * without conflating active epochs of the same run.
     *
     * <p>Cardinality: ~ active_runs × active_epochs_per_run ≈ 1000 × 2 = 2000 keys
     * in steady state - trivial.
     */
    public static String runningNodes(String runId, int epoch) {
        return PREFIX + "running:" + runId + ":" + epoch;
    }

    /**
     * Pattern for SCAN-based enumeration of all per-epoch running-node keys for a
     * single run.
     *
     * <p><b>Deprecated as a primary lookup path (2026-05-09).</b> Originally used
     * by {@code RunningNodeTracker.getRunningCountsAcrossEpochs} and
     * {@code cleanupRun}; both now read {@link #runningEpochsTracker(String)} (a
     * Redis SET maintained by writers) instead. The KEYS-based path remains as a
     * <i>drift fallback</i> only - invoked when the tracker SET is empty for an
     * in-flight run that started before the tracker was introduced, OR after the
     * SET TTL expires ahead of the per-epoch hash TTLs.
     *
     * <p>Why we moved away: {@code redis.keys(pattern)} is the blocking
     * {@code KEYS} command in Spring Data Redis (NOT {@code SCAN}), and at
     * &gt;500 in-flight runs the latency dominates the SSE/state-reconstructor
     * hot path. The SET tracker is O(1) reads.
     *
     * <p>Note: this pattern matches the per-epoch shape ({@code …:runId:0},
     * {@code …:runId:1}, …) but NOT the legacy flat key ({@code …:runId} with no
     * epoch suffix). Aggregation must explicitly check both.
     */
    public static String runningNodesPattern(String runId) {
        return PREFIX + "running:" + runId + ":*";
    }

    /**
     * Redis SET key tracking the epochs that have a per-epoch running-nodes hash
     * for a given run - populated by {@code RunningNodeTracker} writers
     * ({@code markRunning}, {@code setRunningCount}) and consumed by
     * {@code getRunningCountsAcrossEpochs} + {@code cleanupRun}.
     *
     * <p>Replaces the prior {@link #runningNodesPattern(String)} blocking-KEYS
     * scan: SMEMBERS is O(N) on the size of the SET (~1-5 epochs per run in
     * steady state) and does not block the Redis event loop the way KEYS does
     * over the entire keyspace.
     *
     * <p>Cardinality: ~ active_runs ≈ 1000 keys steady-state.
     *
     * <p>TTL: 1h ({@code RunningNodeTracker.HASH_TTL}) refreshed on every SADD
     * by writers - mirrors the per-epoch hash TTL so the tracker drains
     * naturally with its children. The drift fallback (one-shot KEYS scan +
     * re-seed) handles the rare race where the tracker expires while a run is
     * still alive.
     */
    public static String runningEpochsTracker(String runId) {
        return PREFIX + "running-epochs:" + runId;
    }

    // ========================================================================
    // SPLIT COALESCE BARRIER (Horizontal Scaling)
    // ========================================================================

    /**
     * Hash key for split coalesce barrier.
     * Hash fields: "total", "arrived", "item:{index}" → serialized result.
     * Ensures async split items arriving on different instances coalesce correctly.
     */
    public static String splitBarrier(String runId, String nodeId, int epoch) {
        return PREFIX + "split-barrier:" + runId + ":" + nodeId + ":" + epoch;
    }

    /**
     * Pattern to find all split barrier keys for a run (for cleanup).
     *
     * <p><b>Drift fallback only (2026-05-09).</b> {@code redis.keys(pattern)}
     * in Spring Data Redis is the blocking {@code KEYS} command (NOT
     * {@code SCAN}); on a busy keyspace it stalls the Redis event loop. The
     * primary cleanup path now reads {@link #splitBarriersTracker} (a Redis
     * SET maintained at {@code register} / {@code arrive(sealed)} sites) and
     * uses SMEMBERS for O(N_barriers) enumeration. This pattern remains as
     * the one-shot fallback when the tracker SET is empty (in-flight run
     * predating the fix, OR tracker TTL expired while the per-barrier hashes
     * are still alive).
     */
    public static String splitBarrierPattern(String runId) {
        return PREFIX + "split-barrier:" + runId + ":*";
    }

    /**
     * Redis SET key tracking the {@code nodeId:epoch} pairs that have an active
     * split-barrier hash for a given run - populated by
     * {@code SplitCoalesceTracker.register} (SADD) and pruned at seal
     * (best-effort SREM via {@code arrive}) or at run cleanup (DEL).
     * Replaces the prior blocking-KEYS scan in {@code cleanupRun}.
     *
     * <p>TTL: 2h ({@code SplitCoalesceTracker.BARRIER_TTL}) refreshed at
     * register time so the tracker drains alongside its barrier hashes.
     */
    public static String splitBarriersTracker(String runId) {
        return PREFIX + "split-barriers:" + runId;
    }

    // ========================================================================
    // SIGNAL RESUME (Horizontal Scaling)
    // ========================================================================

    /**
     * Dedup key for signal resolution. Prevents double execution when both
     * sync (controller) and async (event listener) paths fire on different instances.
     * Includes runId because tests and cloned environments can reuse numeric signal
     * ids while Redis still retains short-lived dedup keys.
     */
    public static String signalDedup(String runId, long signalId) {
        String safeRunId = runId != null ? runId : "unknown-run";
        return PREFIX + "signal-dedup:" + safeRunId + ":" + signalId;
    }

    /**
     * Legacy signal-only key shape retained for callers that cannot provide runId.
     */
    public static String signalDedup(long signalId) {
        return PREFIX + "signal-dedup:" + signalId;
    }

    /**
     * Distributed lock key for per-run signal resume serialization.
     * Prevents concurrent signal resolutions for the same run from executing
     * downstream nodes in parallel across instances.
     */
    public static String lockSignalResume(String runId) {
        return PREFIX + "lock:signal-resume:" + runId;
    }

    /**
     * Set key for resolved blocking signals whose asynchronous resume flow has not
     * yet finalized the owning DAG epoch.
     */
    public static String signalResumePending(String runId, String triggerId, int epoch) {
        String safeTriggerId = triggerId != null ? triggerId : "trigger:default";
        return PREFIX + "signal-resume-pending:" + runId + ":" + safeTriggerId + ":" + epoch;
    }

    // ========================================================================
    // CLEANUP PATTERNS
    // ========================================================================

    /**
     * Pattern to find all keys for a specific run (for cleanup).
     */
    public static String allKeysForRun(String runId) {
        return PREFIX + "*:" + runId;
    }

    /**
     * Pattern to find all orchestrator keys.
     */
    public static String allKeys() {
        return PREFIX + "*";
    }
}
