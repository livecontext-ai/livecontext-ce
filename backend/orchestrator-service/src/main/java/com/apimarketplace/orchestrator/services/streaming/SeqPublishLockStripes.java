package com.apimarketplace.orchestrator.services.streaming;

import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * Per-runId lock stripes that serialize the {@link WsEventSequencer#nextSeq}
 * → Redis publish window across the three publish chokepoints
 * ({@code WorkflowEventPublisher#publish}, {@code WorkflowStreamingService#sendEvent},
 * {@code StepByStepEventService#publishEvent}).
 *
 * <p><b>Why it exists.</b> {@code WsEventSequencer.nextSeq} assigns a
 * monotonic seq atomically per runId, but the assignment-then-publish window
 * (atomic ~10 ns vs. Redis PUBLISH ~10-100 µs) is non-atomic. With N
 * concurrent publishers on the same runId - typically multiple parallel
 * trigger fires on a multi-trigger workflow - two threads can obtain
 * consecutive seqs (e.g. 5 then 6) and publish to Redis in inverted order.
 * The frontend strict-{@code <} stale filter
 * ({@code WorkflowRunManager.handleBatchUpdate}, line ~303) then drops the
 * older seq silently, freezing the run page on partial state.
 *
 * <p>The frontend filter is correct as-is: it protects against cross-epoch
 * staleness, multi-user rerun staleness, and REST/WS arbitration (see
 * {@code RUN_PAGE_ARCHITECTURE_ISSUES.md} #1 and the rerunGuard test
 * suite). It assumes seq order = arrival order, which only holds when the
 * backend serializes the assign+publish per runId.
 *
 * <p>Striping pattern mirrors {@code SnapshotService.sendLocks}: 4096
 * native {@link ReentrantLock} instances, indexed by
 * {@code Math.floorMod(runId.hashCode(), STRIPES)}. ~96 KB static, never
 * evicted (held-lock eviction reintroduces the race).
 *
 * <p><b>Performance envelope.</b> Lock hold = the Redis publish latency
 * (~100 µs). For a multi-trigger workflow with 11 triggers × ~50 events on
 * the same runId, ~550 events serialize through the same stripe → ~55 ms
 * total publish time on that run. Across distinct runs, contention is
 * statistically negligible (birthday collision ≤ 0.5 % at 1000 concurrent
 * runs).
 */
@Component
public class SeqPublishLockStripes {

    public static final int STRIPES = 4096;
    private final ReentrantLock[] locks = new ReentrantLock[STRIPES];

    public SeqPublishLockStripes() {
        for (int i = 0; i < STRIPES; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    public ReentrantLock lockFor(String runId) {
        // Match SnapshotService striping so collisions on the same runId
        // resolve to the same stripe across services.
        return locks[Math.floorMod(runId == null ? 0 : runId.hashCode(), STRIPES)];
    }

    /**
     * Run {@code action} while holding the per-runId stripe lock. If
     * {@code runId} is null the action runs without locking (fall-through
     * matches the legacy unsequenced path). The lock is always released,
     * even if the action throws.
     */
    public void withRunIdLock(String runId, Runnable action) {
        if (runId == null) {
            action.run();
            return;
        }
        ReentrantLock lock = lockFor(runId);
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }
}
