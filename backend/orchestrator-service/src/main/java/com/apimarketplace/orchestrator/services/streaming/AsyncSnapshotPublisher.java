package com.apimarketplace.orchestrator.services.streaming;

import com.apimarketplace.orchestrator.services.cache.RunScopedCache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Plan v4 §8 - bounded-executor wrapper for snapshot publishing.
 *
 * <p>Decouples the snapshot-publish path from the writer thread. The CAS hot
 * path (#1) registers a {@code runAfterCommit} hook that calls
 * {@link #publishAsync} with the freshly-committed {@code (seq, json, nodeId,
 * epochId)} tuple. The wrapper:
 *
 * <ol>
 *   <li><b>Per-(nodeId, epochId) max-seq guard</b> - drops events whose
 *       {@code seq} is &lt;= the highest already published for that
 *       {@code (nodeId, epochId)} pair. Mirrors the frontend
 *       {@code WsEventSequencer} so a slow consumer that reconnects can
 *       be back-filled correctly. Map is in-memory per replica; loss on
 *       restart is acceptable (the next legit publish reseeds).</li>
 *   <li><b>Caffeine LRU dedup</b> on {@code (runId, seq)} with 100ms TTL.
 *       Catches the case where two callers (e.g. SBS resume + a parallel
 *       trigger fire) both write the same seq within the same 100ms.</li>
 *   <li><b>Bounded executor dispatch</b> - submits to
 *       {@code snapshotPublishExecutor} (5 threads, queue 100). On
 *       {@link RejectedExecutionException} (queue full): drop + increment
 *       {@code async_publish_drop_count} metric + WARN log.</li>
 * </ol>
 *
 * <p>The bounded-executor path is enabled by feature flag
 * {@code orchestrator.optim.async-publish=true}. When the flag is OFF, the
 * wrapper still applies the dedup + max-seq guards (these are correctness,
 * not perf) but the publish runs synchronously on the caller thread - same
 * shape as today's {@code SnapshotService.markDirty}.
 *
 * <p>Why a sibling component (not a method on {@link SnapshotService}):
 * the existing service is large + centrally injected. Adding the @Async
 * surface there would introduce circular AOP issues (SnapshotService is its
 * own self-invocation chain in places). A sibling wrapper keeps the seam
 * narrow.
 */
@Component
public class AsyncSnapshotPublisher implements RunScopedCache {

    private static final Logger log = LoggerFactory.getLogger(AsyncSnapshotPublisher.class);
    private static final String METRIC_DROP = "orchestrator.async_publish.drop_count";
    private static final String METRIC_STALE = "orchestrator.async_publish.stale_drop_count";
    private static final String METRIC_DEDUP = "orchestrator.async_publish.dedup_drop_count";
    private static final String METRIC_OK = "orchestrator.async_publish.dispatched_count";

    private final SnapshotService snapshotService;
    private final TaskExecutor executor;
    private final Counter dropCounter;
    private final Counter staleDropCounter;
    private final Counter dedupDropCounter;
    private final Counter dispatchedCounter;
    private final boolean asyncEnabled;

    /**
     * Per-(nodeId, epochId) max-seq seen. Bounded growth: keys are interned
     * by {@link NodeEpochKey} and stay only for the lifetime of an active
     * run. The map is cleared per-runId via {@link #onRunTerminated(String)}.
     * For deployments with high run churn (thousands of runs/hour), a TTL
     * eviction could be added - out of scope for the initial ship since
     * peak observed key count in prod has been ≤ 10K (a few KB of overhead).
     */
    private final ConcurrentHashMap<NodeEpochKey, AtomicLong> maxSeqByNodeEpoch =
            new ConcurrentHashMap<>();

    /** Caffeine LRU dedup on (runId, seq) - 100ms TTL. */
    private final Cache<RunSeqKey, Boolean> recentlyPublished = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMillis(100))
            .maximumSize(10_000)
            .build();

    public AsyncSnapshotPublisher(
            SnapshotService snapshotService,
            @Qualifier("snapshotPublishExecutor") TaskExecutor executor,
            MeterRegistry meterRegistry,
            @Value("${orchestrator.optim.async-publish:true}") boolean asyncEnabled) {
        this.snapshotService = snapshotService;
        this.executor = executor;
        this.asyncEnabled = asyncEnabled;
        this.dropCounter = Counter.builder(METRIC_DROP)
                .description("Plan v4 §8 - async snapshot publishes dropped due to executor queue overflow")
                .register(meterRegistry);
        this.staleDropCounter = Counter.builder(METRIC_STALE)
                .description("Plan v4 §8 - async snapshot publishes dropped because seq <= maxSeqSeen for (nodeId, epochId)")
                .register(meterRegistry);
        this.dedupDropCounter = Counter.builder(METRIC_DEDUP)
                .description("Plan v4 §8 - async snapshot publishes dropped by (runId, seq) Caffeine dedup")
                .register(meterRegistry);
        this.dispatchedCounter = Counter.builder(METRIC_OK)
                .description("Plan v4 §8 - async snapshot publishes successfully dispatched to executor")
                .register(meterRegistry);
        if (asyncEnabled) {
            log.info("[AsyncSnapshotPublisher] async-publish ENABLED - bounded executor path");
        } else {
            log.info("[AsyncSnapshotPublisher] async-publish DISABLED - sync path, dedup+stale-drop still applied");
        }
    }

    /**
     * Plan v4 §8 minimal overload - for the event-driven coalesced-publish
     * path (markDirty from {@code WorkflowEventPublisher}). No
     * {@code (seq, nodeId, epochId)} is known at this layer; the per-runId
     * throttle inside {@link SnapshotService#sendSnapshot(String)} already
     * de-dupes at 200ms granularity. This overload's value is the
     * bounded-executor dispatch - decouples the event-publish hot path
     * from the SSE/Redis publish round-trip.
     *
     * <p>When flag OFF: synchronous fallback (same as today's
     * {@code snapshotService.markDirty(runId)} call inline).
     */
    public void publishAsync(String runId) {
        if (runId == null) {
            return;
        }
        if (asyncEnabled) {
            try {
                executor.execute(() -> doPublish(runId));
                dispatchedCounter.increment();
            } catch (RejectedExecutionException ex) {
                dropCounter.increment();
                log.warn("[AsyncSnapshotPublisher] executor queue full - dropped markDirty for runId={}: {}",
                        runId, ex.getMessage());
            }
        } else {
            doPublish(runId);
            dispatchedCounter.increment();
        }
    }

    /**
     * Plan v4 §8 entry point.
     *
     * @param runId    workflow run public ID (e.g. {@code "abc-123"})
     * @param seq      the {@code state_snapshot.seq} value just committed
     * @param nodeId   the node whose status mutation triggered this publish
     *                 (e.g. {@code "mcp:step1"}); may be {@code null} for
     *                 run-level events
     * @param epochId  the trigger epoch the mutation belongs to; ignored if
     *                 {@code nodeId} is null
     */
    public void publishAsync(String runId, long seq, String nodeId, int epochId) {
        if (runId == null) {
            return;
        }

        // Guard 1: stale-event drop (per-(nodeId, epochId))
        if (nodeId != null) {
            NodeEpochKey key = new NodeEpochKey(runId, nodeId, epochId);
            AtomicLong currentMax = maxSeqByNodeEpoch.computeIfAbsent(key, k -> new AtomicLong(-1L));
            // CAS-style update: keep the max
            while (true) {
                long observed = currentMax.get();
                if (seq <= observed) {
                    staleDropCounter.increment();
                    return;  // stale event - frontend has already seen >= seq
                }
                if (currentMax.compareAndSet(observed, seq)) {
                    break;
                }
            }
        }

        // Guard 2: Caffeine LRU dedup on (runId, seq) - 100ms window
        RunSeqKey dedupKey = new RunSeqKey(runId, seq);
        if (recentlyPublished.asMap().putIfAbsent(dedupKey, Boolean.TRUE) != null) {
            dedupDropCounter.increment();
            return;
        }

        // Dispatch
        if (asyncEnabled) {
            try {
                executor.execute(() -> doPublish(runId));
                dispatchedCounter.increment();
            } catch (RejectedExecutionException ex) {
                dropCounter.increment();
                log.warn("[AsyncSnapshotPublisher] executor queue full - dropped publish for runId={} seq={}: {}",
                        runId, seq, ex.getMessage());
            }
        } else {
            // Sync fallback - guards still applied
            doPublish(runId);
            dispatchedCounter.increment();
        }
    }

    private void doPublish(String runId) {
        try {
            snapshotService.markDirty(runId);
        } catch (RuntimeException ex) {
            log.warn("[AsyncSnapshotPublisher] markDirty failed for runId={}: {} (publish dropped)",
                    runId, ex.getMessage());
        }
    }

    /**
     * Called from run-lifecycle hooks when a run reaches a terminal state.
     * Drops per-(nodeId, epochId) max-seq entries for the run, capping memory
     * growth. The dedup cache evicts naturally via Caffeine TTL.
     *
     * <p>Wired automatically by {@link com.apimarketplace.orchestrator.services.cache.RunCacheRegistry}
     * via the {@link RunScopedCache} interface - no manual caller needed; the
     * registry invokes {@link #cleanupRun(String)} on every run lifecycle
     * termination.
     */
    public void onRunTerminated(String runId) {
        if (runId == null) return;
        maxSeqByNodeEpoch.keySet().removeIf(k -> k.runId().equals(runId));
    }

    // --- RunScopedCache implementation ---

    @Override
    public void cleanupRun(String runId) {
        onRunTerminated(runId);
    }

    @Override
    public String getCacheName() {
        return "AsyncSnapshotPublisher.maxSeqByNodeEpoch";
    }

    @Override
    public CacheDomain getDomain() {
        return CacheDomain.STREAMING;
    }

    @Override
    public int getCacheSize() {
        return maxSeqByNodeEpoch.size();
    }

    // Internal value types - kept package-private for tests.

    record NodeEpochKey(String runId, String nodeId, int epochId) {
        NodeEpochKey {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(nodeId, "nodeId");
        }
    }

    record RunSeqKey(String runId, long seq) {
        RunSeqKey {
            Objects.requireNonNull(runId, "runId");
        }
    }
}
