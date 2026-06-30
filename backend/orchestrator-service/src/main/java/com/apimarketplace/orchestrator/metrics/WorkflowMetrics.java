package com.apimarketplace.orchestrator.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Workflow execution metrics exposed via Prometheus/Micrometer.
 *
 * Cardinality policy:
 * - No PII tags (no userId, no tenantId).
 * - Only bounded tags: result (success/failure), step_type (node prefix).
 * - Per-run backlog is internal only (not exposed as metric tags).
 */
@Component
public class WorkflowMetrics {

    // --- Metric names ---
    public static final String WORKFLOWS_STARTED_TOTAL = "workflow_executions_started_total";
    public static final String WORKFLOWS_COMPLETED_TOTAL = "workflow_executions_completed_total";
    public static final String WORKFLOWS_RUNNING = "workflow_executions_running";
    public static final String WORKFLOW_DURATION_MS = "workflow_execution_duration_ms";
    public static final String STEP_EXECUTIONS_TOTAL = "workflow_step_executions_total";
    public static final String STEP_DURATION_MS = "workflow_step_duration_ms";
    public static final String STREAMING_BATCH_FLUSHES_TOTAL = "workflow_streaming_batch_flushes_total";
    public static final String STREAMING_BATCH_DURATION_MS = "workflow_streaming_batch_duration_ms";
    public static final String EVENTS_ENQUEUED_TOTAL = "workflow_events_enqueued_total";
    public static final String EVENTS_PROCESSED_TOTAL = "workflow_events_processed_total";
    public static final String EVENTS_DROPPED_TOTAL = "workflow_events_dropped_total";
    public static final String EVENT_QUEUE_HIGH_WATER_MARK = "workflow_event_queue_high_water_mark";
    public static final String NODE_CREDITS_CONSUMED_TOTAL = "workflow_node_credits_consumed_total";
    public static final String STATE_SNAPSHOT_SAVE_COUNT = "orchestrator_state_snapshot_save_count";
    public static final String STATE_SNAPSHOT_SAVE_BYTES = "orchestrator_state_snapshot_save_bytes";
    public static final String STATE_SNAPSHOT_SAVE_LATENCY_MS = "orchestrator_state_snapshot_save_latency_ms";
    public static final String STATE_SNAPSHOT_PATCH_PAYLOAD_BYTES = "orchestrator_state_snapshot_patch_payload_bytes";
    public static final String STATE_SNAPSHOT_PATCH_FALLBACK_COUNT = "orchestrator_state_snapshot_patch_fallback_count";
    public static final String RUNNING_TRACKER_WRITE_FAILURE_COUNT = "orchestrator_running_tracker_write_failure_count";
    public static final String STATE_SNAPSHOT_CACHE_OUTCOME_COUNT = "orchestrator_state_snapshot_cache_outcome_count";

    // ── Context build (V2StepByStepContextManager / RunContextService) ──
    public static final String CONTEXT_BUILD_DURATION_MS = "orchestrator_context_build_duration_ms";
    public static final String CONTEXT_BUILD_STEP_OUTPUTS_SIZE = "orchestrator_context_build_step_outputs_size";
    public static final String CONTEXT_ALIAS_COLLISION_COUNT = "orchestrator_context_alias_collision_count";
    public static final String CONTEXT_PER_ITEM_FALLBACK_COUNT = "orchestrator_context_per_item_fallback_count";

    private final MeterRegistry registry;

    // Gauges need backing atomics
    private final AtomicInteger currentlyRunningWorkflows = new AtomicInteger(0);
    private final AtomicLong eventQueueHighWaterMark = new AtomicLong(0);

    // Per-run backlog (internal, not exposed as high-cardinality metric)
    private final Map<String, AtomicInteger> workflowEventBacklog = new ConcurrentHashMap<>();

    // Per-run node credit accumulator (1 credit per completed/failed node)
    private final Map<String, AtomicLong> runNodeCreditAccumulator = new ConcurrentHashMap<>();

    // Counters (pre-registered)
    private final Counter workflowsStartedCounter;
    private final Counter workflowsSucceededCounter;
    private final Counter workflowsFailedCounter;
    private final Counter streamingBatchFlushCounter;
    private final Counter eventsEnqueuedCounter;
    private final Counter eventsProcessedCounter;
    private final Counter eventsDroppedCounter;
    private final Counter nodeCreditsConsumedCounter;

    // Distribution summaries
    private final DistributionSummary workflowDurationSummary;
    private final DistributionSummary streamingBatchDurationSummary;

    public WorkflowMetrics(MeterRegistry registry) {
        this.registry = registry;

        // --- Workflow execution counters ---
        this.workflowsStartedCounter = Counter.builder(WORKFLOWS_STARTED_TOTAL)
                .description("Total workflow executions started")
                .register(registry);

        this.workflowsSucceededCounter = Counter.builder(WORKFLOWS_COMPLETED_TOTAL)
                .tags("result", "success")
                .description("Total workflow executions completed")
                .register(registry);

        this.workflowsFailedCounter = Counter.builder(WORKFLOWS_COMPLETED_TOTAL)
                .tags("result", "failure")
                .description("Total workflow executions completed")
                .register(registry);

        // --- Running gauge ---
        Gauge.builder(WORKFLOWS_RUNNING, currentlyRunningWorkflows, AtomicInteger::get)
                .description("Currently running workflow executions")
                .register(registry);

        // --- Duration distribution ---
        this.workflowDurationSummary = DistributionSummary.builder(WORKFLOW_DURATION_MS)
                .description("Workflow execution duration in milliseconds")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        // --- Streaming batch ---
        this.streamingBatchFlushCounter = Counter.builder(STREAMING_BATCH_FLUSHES_TOTAL)
                .description("Total streaming batch flushes")
                .register(registry);

        this.streamingBatchDurationSummary = DistributionSummary.builder(STREAMING_BATCH_DURATION_MS)
                .description("Streaming batch flush duration in milliseconds")
                .publishPercentiles(0.5, 0.95)
                .register(registry);

        // --- Event bus ---
        this.eventsEnqueuedCounter = Counter.builder(EVENTS_ENQUEUED_TOTAL)
                .description("Total workflow events enqueued")
                .register(registry);

        this.eventsProcessedCounter = Counter.builder(EVENTS_PROCESSED_TOTAL)
                .description("Total workflow events processed")
                .register(registry);

        this.eventsDroppedCounter = Counter.builder(EVENTS_DROPPED_TOTAL)
                .description("Total workflow events dropped (queue full)")
                .register(registry);

        Gauge.builder(EVENT_QUEUE_HIGH_WATER_MARK, eventQueueHighWaterMark, AtomicLong::get)
                .description("Highest event queue size observed")
                .register(registry);

        // --- Node credit consumption ---
        this.nodeCreditsConsumedCounter = Counter.builder(NODE_CREDITS_CONSUMED_TOTAL)
                .description("Total credits consumed for workflow node executions (1 per node)")
                .register(registry);
    }

    // ===== WORKFLOW EXECUTION =====

    public void recordWorkflowStarted(String tenantId) {
        workflowsStartedCounter.increment();
        currentlyRunningWorkflows.incrementAndGet();
    }

    public void recordWorkflowCompleted(String tenantId, long executionTimeMs, boolean success) {
        currentlyRunningWorkflows.decrementAndGet();

        if (success) {
            workflowsSucceededCounter.increment();
        } else {
            workflowsFailedCounter.increment();
        }

        workflowDurationSummary.record(executionTimeMs);
    }

    // ===== STEP EXECUTION =====

    public void recordStepExecution(String stepType, long executionTimeMs, boolean success) {
        String safeType = safeTag(stepType);
        String result = success ? "success" : "failure";

        Counter.builder(STEP_EXECUTIONS_TOTAL)
                .tags(Tags.of("step_type", safeType, "result", result))
                .register(registry)
                .increment();

        DistributionSummary.builder(STEP_DURATION_MS)
                .tags(Tags.of("step_type", safeType))
                .publishPercentiles(0.5, 0.95)
                .register(registry)
                .record(executionTimeMs);
    }

    // ===== STATE SNAPSHOT SAVE (P2.0 instrumentation) =====

    /**
     * Record a single saveSnapshot persistence. Used to baseline write rate by mutator
     * and dual-write cost during P2.3 → P2.5 (per the project docs).
     *
     * @param mutator   the mutator method that triggered the save (e.g. "markNodeCompleted")
     * @param bytes     bytes written to state_snapshot column for this save
     * @param durationNs wall-clock time spent in saveSnapshot (Jackson serialize + DB write)
     */
    public void recordStateSnapshotSave(String mutator, long bytes, long durationNs) {
        recordStateSnapshotSave(mutator, "full_rewrite", bytes, durationNs);
    }

    /**
     * Variant tagging the persistence path: {@code full_rewrite} (legacy Jackson +
     * full JSONB UPDATE) vs {@code jsonb_set} (new minimal-patch UPDATE).
     * Allows a side-by-side comparison during canari.
     */
    public void recordStateSnapshotSave(String mutator, String path, long bytes, long durationNs) {
        String safeMutator = safeTag(mutator);
        String safePath = safeTag(path);
        Counter.builder(STATE_SNAPSHOT_SAVE_COUNT)
                .tags(Tags.of("mutator", safeMutator, "path", safePath))
                .description("Total state_snapshot persistence calls, labeled by mutator and path")
                .register(registry)
                .increment();
        DistributionSummary.builder(STATE_SNAPSHOT_SAVE_BYTES)
                .tags(Tags.of("mutator", safeMutator, "path", safePath))
                .baseUnit("bytes")
                .publishPercentiles(0.5, 0.95, 0.99)
                .description("Bytes written per state_snapshot save, labeled by mutator and path")
                .register(registry)
                .record(bytes);
        DistributionSummary.builder(STATE_SNAPSHOT_SAVE_LATENCY_MS)
                .tags(Tags.of("mutator", safeMutator, "path", safePath))
                .baseUnit("milliseconds")
                .publishPercentiles(0.5, 0.95, 0.99)
                .description("Latency per state_snapshot save, labeled by mutator and path")
                .register(registry)
                .record(durationNs / 1_000_000.0);
    }

    /**
     * Record the wire bytes of a {@code jsonb_set} patch payload. Distinct from
     * {@link #STATE_SNAPSHOT_SAVE_BYTES}: that one tracks total bytes written
     * (which the patch path doesn't fully know - Postgres rewrites the row
     * tuple anyway), this one tracks the SQL parameter wire size, the
     * Java-side serialization cost.
     */
    public void recordPatchPayloadBytes(String mutator, long bytes) {
        DistributionSummary.builder(STATE_SNAPSHOT_PATCH_PAYLOAD_BYTES)
                .tags(Tags.of("mutator", safeTag(mutator)))
                .baseUnit("bytes")
                .publishPercentiles(0.5, 0.95, 0.99)
                .description("Bytes of the jsonb_set patch payload (path + JSON value), labeled by mutator")
                .register(registry)
                .record(bytes);
    }

    /**
     * Record a fallback from the patch path to the full-rewrite path.
     * {@code reason} is one of: {@code builder_returned_fallback},
     * {@code epoch_not_materialized}, {@code serialization_error},
     * {@code feature_flag_off}. Alert if the ratio fallback/total &gt; 5%
     * during canari.
     */
    public void recordPatchFallback(String mutator, String reason) {
        Counter.builder(STATE_SNAPSHOT_PATCH_FALLBACK_COUNT)
                .tags(Tags.of("mutator", safeTag(mutator), "reason", safeTag(reason)))
                .description("Patch-path fallbacks to full rewrite, labeled by mutator and reason")
                .register(registry)
                .increment();
    }

    /**
     * Record a fail-open Redis write failure on RunningNodeTracker (markRunning,
     * setRunningCount, markCompleted). The catch block swallows the exception by design
     * (per execution-kernel roadmap §3.4.1 - markRunning runs post-commit, fail-CLOSED
     * writes are unimplementable without XA-class coordination), but the metric must
     * fire so on-call sees Redis health degradation. Alert at &gt; 1/min sustained 5min.
     *
     * @param operation the failing op: "markRunning" / "setRunningCount" / "markCompleted"
     */
    public void recordRunningTrackerWriteFailure(String operation) {
        Counter.builder(RUNNING_TRACKER_WRITE_FAILURE_COUNT)
                .tags(Tags.of("operation", safeTag(operation)))
                .description("RunningNodeTracker fail-open write losses, labeled by operation")
                .register(registry)
                .increment();
    }

    /**
     * Record an outcome of {@code StateSnapshotJsonCache} (A2 Phase 4 -
     * out-of-tx Redis cache for serialized state_snapshot JSON).
     *
     * <p>Outcome tag values:
     * <ul>
     *   <li>{@code hit} - payload returned, seq matched the SQL oracle</li>
     *   <li>{@code miss} - no entry, missing field</li>
     *   <li>{@code miss_seq_mismatch} - entry present but seq stale (peer-instance write)</li>
     *   <li>{@code miss_corrupt} - cached seq unparseable (legacy / data corruption)</li>
     *   <li>{@code put_applied} - Lua atomic put accepted (newer seq)</li>
     *   <li>{@code put_dropped_stale} - Lua atomic put rejected (current ≥ proposed)</li>
     *   <li>{@code error_read} / {@code error_put} - Redis exception swallowed (fail-OPEN)</li>
     * </ul>
     *
     * <p>Hit-rate target: &gt;90% on SSE-poll path. Sustained &lt;50% indicates
     * either cache TTL too short (eviction) or seq-mismatch storm (multi-replica
     * thrash). Investigate via {@code seq_mismatch}/{@code put_dropped_stale}
     * ratios.
     */
    public void recordSnapshotCacheOutcome(String outcome) {
        Counter.builder(STATE_SNAPSHOT_CACHE_OUTCOME_COUNT)
                .tags(Tags.of("outcome", safeTag(outcome)))
                .description("StateSnapshotJsonCache outcomes (hit/miss/seq_mismatch/error/put), per call")
                .register(registry)
                .increment();
    }

    // ===== NODE CREDIT CONSUMPTION =====

    /**
     * Record 1 node credit consumed, tagged by step type prefix.
     * @param nodeId full node ID (e.g. "mcp:step1", "agent:classify")
     */
    public void recordNodeCreditConsumed(String nodeId) {
        nodeCreditsConsumedCounter.increment();
        String stepType = extractStepTypePrefix(nodeId);
        Counter.builder(NODE_CREDITS_CONSUMED_TOTAL + "_by_type")
                .tags(Tags.of("step_type", safeTag(stepType)))
                .register(registry)
                .increment();
    }

    /**
     * Extract the type prefix from a node ID (e.g. "mcp" from "mcp:step1").
     */
    public static String extractStepTypePrefix(String nodeId) {
        if (nodeId == null || !nodeId.contains(":")) return "unknown";
        return nodeId.substring(0, nodeId.indexOf(':'));
    }

    // ===== PER-RUN NODE COST TRACKING =====

    /**
     * Record 1 node credit consumed for a workflow run.
     * Used by StepCompletionOrchestrator and SignalResumeService.
     */
    public void recordRunNodeCredit(String runId) {
        if (runId == null) return;
        runNodeCreditAccumulator
                .computeIfAbsent(runId, k -> new AtomicLong(0))
                .incrementAndGet();
    }

    /**
     * Get accumulated node credits for a workflow run.
     * This is the platform fee (1 credit per node), NOT including agent LLM costs.
     */
    public long getRunNodeCredits(String runId) {
        if (runId == null) return 0;
        AtomicLong counter = runNodeCreditAccumulator.get(runId);
        return counter != null ? counter.get() : 0;
    }

    /**
     * Clean up per-run cost tracking when workflow completes.
     */
    public void clearRunNodeCredits(String runId) {
        if (runId != null) {
            runNodeCreditAccumulator.remove(runId);
        }
    }

    // ===== STREAMING BATCH =====

    public void recordStreamingBatchFlush(long durationMs, int stepCount, int edgeCount) {
        streamingBatchFlushCounter.increment();
        streamingBatchDurationSummary.record(durationMs);
    }

    // ===== EVENT BUS =====

    public void recordWorkflowEventEnqueued(String runId, int queueSize) {
        eventsEnqueuedCounter.increment();
        updateQueueBacklog(runId, queueSize);
        updateHighWaterMark(queueSize);
    }

    public void recordWorkflowEventProcessed(String runId, int queueSize) {
        eventsProcessedCounter.increment();
        updateQueueBacklog(runId, queueSize);
    }

    public void recordWorkflowEventDropped(String runId) {
        eventsDroppedCounter.increment();
    }

    // ===== INTERNAL BACKLOG (not exposed as metrics - high cardinality) =====

    public Map<String, Integer> getWorkflowEventBacklogSnapshot() {
        Map<String, Integer> snapshot = new ConcurrentHashMap<>();
        workflowEventBacklog.forEach((runId, counter) -> snapshot.put(runId, counter.get()));
        return snapshot;
    }

    public void clearWorkflowEventBacklog(String runId) {
        if (runId != null) {
            workflowEventBacklog.remove(runId);
        }
    }

    /**
     * Clean up all per-run tracking data when a workflow run terminates.
     */
    public void cleanupRun(String runId) {
        clearWorkflowEventBacklog(runId);
        clearRunNodeCredits(runId);
    }

    // ===== GAUGE ACCESSORS (for non-Prometheus consumers) =====

    public int getCurrentlyRunningWorkflows() {
        return currentlyRunningWorkflows.get();
    }

    public long getWorkflowEventQueueHighWaterMark() {
        return eventQueueHighWaterMark.get();
    }

    // ===== HELPERS =====

    private void updateQueueBacklog(String runId, int queueSize) {
        if (runId == null) return;
        workflowEventBacklog
                .computeIfAbsent(runId, key -> new AtomicInteger())
                .set(Math.max(queueSize, 0));
    }

    private void updateHighWaterMark(int queueSize) {
        if (queueSize < 0) return;
        eventQueueHighWaterMark.updateAndGet(current -> Math.max(current, queueSize));
    }

    /**
     * Record a context build (V2StepByStepContextManager.getOrCreateContextWithTriggerData).
     * Captures both latency (DistributionSummary) and resulting stepOutputs size.
     *
     * <p>Tag {@code mode} ∈ {auto, sbs}, {@code has_split} ∈ {true, false}.
     * Both bounded → cardinality stays at 4 series per metric.
     *
     * <p>Alert: {@code histogram_quantile(0.99, ...) > 100ms} suggests a DB regression
     * (compare to the V168 lightweight query baseline mentioned in memory).
     */
    public void recordContextBuild(String mode, boolean hasSplit, long durationNs, int stepOutputsSize) {
        Tags tags = Tags.of("mode", safeTag(mode), "has_split", String.valueOf(hasSplit));
        DistributionSummary.builder(CONTEXT_BUILD_DURATION_MS)
                .tags(tags)
                .baseUnit("milliseconds")
                .publishPercentiles(0.5, 0.95, 0.99)
                .description("Latency per ExecutionContext build, tagged by mode and split presence")
                .register(registry)
                .record(durationNs / 1_000_000.0);
        DistributionSummary.builder(CONTEXT_BUILD_STEP_OUTPUTS_SIZE)
                .tags(tags)
                .publishPercentiles(0.5, 0.95, 0.99)
                .description("Number of step output entries in the built context (full-keys + aliases)")
                .register(registry)
                .record(stepOutputsSize);
    }

    /**
     * Record an alias collision detected at runtime.
     *
     * <p>Tag {@code type} ∈ {plan_boot, loop_override, normalization} - bounded.
     * Plan-boot collisions are detected by {@code PlanAliasValidator}; loop-override
     * collisions can theoretically happen if a loop core writes an alias that already
     * resolves to a different node (today: producer contract precludes this - metric
     * is a tripwire).
     *
     * <p>Alert: any non-zero rate over 5 min should page on-call.
     */
    public void recordAliasCollision(String type) {
        recordAliasCollision(type, 1);
    }

    /**
     * Variant that increments by an arbitrary amount in one call - cheaper than a tight
     * caller-side loop when many collisions are detected at once (e.g. boot-time validation
     * over a plan with multiple ambiguous labels).
     */
    public void recordAliasCollision(String type, long amount) {
        if (amount <= 0) return;
        Counter.builder(CONTEXT_ALIAS_COLLISION_COUNT)
                .tags(Tags.of("type", safeTag(type)))
                .description("Alias collisions detected during context assembly - should be zero")
                .register(registry)
                .increment(amount);
    }

    /**
     * Record a Pass-2 shared-storage fallback in {@code RunContextService.buildPerItemContext}.
     *
     * <p>Pass-2 fires when the per-item map (Pass-1) didn't fill a step_key - the validator
     * falls back to storages with {@code item_index IS NULL OR 0}. A high rate compared to
     * total context builds means a lot of trigger/pre-split nodes are being shared, which is
     * normal - but a SUDDEN spike means routing changed.
     */
    public void recordPerItemFallback() {
        Counter.builder(CONTEXT_PER_ITEM_FALLBACK_COUNT)
                .description("Pass-2 shared-storage fallbacks in buildPerItemContext")
                .register(registry)
                .increment();
    }

    private static String safeTag(String v) {
        if (v == null || v.isBlank()) return "unknown";
        // Truncate-then-lowercase. Earlier shape returned the truncated value as-is, which
        // produced split tag values across the 48-char boundary (e.g. "MyMutator" stayed
        // CamelCase, "mymutator" was lowered) and silently leaked cardinality.
        String truncated = v.length() > 48 ? v.substring(0, 48) : v;
        return truncated.toLowerCase();
    }
}
