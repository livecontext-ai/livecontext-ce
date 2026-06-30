package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.common.scaling.lock.DistributedSemaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Tracks active parallel epochs for a given trigger within a run.
 *
 * <p>Used for release/cleanup tracking: acquire on epoch start, release on epoch close.
 * Epoch parallelism within a single run is NOT limited - plan-based concurrency
 * controls the number of parallel RUNS (via ExecutionQueueService), not epochs.
 *
 * <p>Delegates to {@link DistributedSemaphore} for permit management.
 * In single-instance mode, the default InMemorySemaphore is used.
 * For horizontal scaling, a Redis-backed implementation can be injected.
 *
 * <h2>LOAD-BEARING INVARIANT - instance-local epoch ownership</h2>
 *
 * <p>Acquire ({@link #tryAcquire}) and release ({@link #release}) for a given epoch
 * MUST happen on the SAME JVM. The {@code ownerIds} deque is in-memory only - it
 * is never replicated, recovered, or repopulated from outside the process.
 *
 * <p>The O(1) {@link #hasActiveEpochs} / {@link #hasAnyActiveEpochs} optimisation
 * (added 2026-05-06) reads {@code ownerIds.size()} as the authoritative count,
 * deliberately bypassing the distributed semaphore to avoid Redis round-trips on
 * the hot path of {@code resetForNextCycle} and the recovery watchdog.
 *
 * <p><b>Breaking the invariant silently breaks correctness:</b>
 * <ul>
 *   <li>If a future change adds a cross-replica recovery path that calls
 *       {@link #release} from a different JVM than the one that acquired,
 *       {@code hasAnyActiveEpochs} will return {@code false} on the original
 *       JVM while Redis still tracks the permit, causing premature epoch close.</li>
 *   <li>If a JVM crashes mid-run, the {@code ownerIds} deque is gone - on restart
 *       {@code hasAnyActiveEpochs} returns {@code false} even when Redis still
 *       holds orphan permits. This is acceptable on crash recovery (the orchestrator
 *       restarts always re-evaluate epoch state from {@code state_snapshot}), but
 *       no other path may rely on cross-JVM continuity here.</li>
 * </ul>
 *
 * <p>If you need a cross-replica check in the future, add a SEPARATE method that
 * explicitly hits the distributed semaphore - do not change the semantics of
 * the existing {@code hasActiveEpochs} / {@code hasAnyActiveEpochs} methods.
 */
@Service
public class EpochConcurrencyLimiter {

    private static final Logger log = LoggerFactory.getLogger(EpochConcurrencyLimiter.class);

    private final DistributedSemaphore distributedSemaphore;

    /**
     * Tracks the max permits for each key so hasActiveEpochs / hasAnyActiveEpochs
     * can compare available permits against the configured maximum.
     */
    private final ConcurrentHashMap<String, Integer> maxPermits = new ConcurrentHashMap<>();

    /**
     * Tracks ownerIds per key (FIFO) so release() can pass the correct ownerId
     * to the distributed semaphore. Without this, Redis-mode ZREM would fail
     * to find the member because it was stored under a unique ownerId at acquire time.
     * FIFO order ensures the first-acquired epoch is released first.
     */
    private final ConcurrentHashMap<String, Deque<String>> ownerIds = new ConcurrentHashMap<>();

    public EpochConcurrencyLimiter(DistributedSemaphore distributedSemaphore) {
        this.distributedSemaphore = distributedSemaphore;
    }

    /**
     * Try to acquire a slot for a new epoch with a plan-specific limit.
     * Non-blocking: returns false immediately if no permits are available.
     *
     * @param runId       the workflow run ID
     * @param triggerId   the trigger node ID
     * @param maxConcurrent max concurrent epochs for the user's plan
     * @return true if a slot was acquired
     */
    public boolean tryAcquire(String runId, String triggerId, int maxConcurrent) {
        String key = buildKey(runId, triggerId);
        maxPermits.putIfAbsent(key, maxConcurrent);
        // ownerId is unique per acquisition for distributed owner tracking
        String ownerId = key + ":" + Thread.currentThread().getId() + ":" + System.nanoTime();
        boolean acquired = distributedSemaphore.tryAcquire(key, maxConcurrent, ownerId);
        if (acquired) {
            ownerIds.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>()).addLast(ownerId);
            log.info("[EpochLimiter] Acquired slot: key={}, owner={}, available={}, max={}",
                    key, ownerId, distributedSemaphore.availablePermits(key, maxConcurrent), maxConcurrent);
        } else {
            log.warn("[EpochLimiter] Max concurrent epochs reached: key={}, max={}",
                    key, maxConcurrent);
        }
        return acquired;
    }

    /**
     * Backward-compatible overload - uses a default of 1 (FREE plan).
     * Callers that don't have plan info fall back to the most restrictive limit.
     */
    public boolean tryAcquire(String runId, String triggerId) {
        return tryAcquire(runId, triggerId, 1);
    }

    /**
     * Release a slot when an epoch completes or fails.
     * Safe against over-release: the underlying semaphore guards against it.
     */
    public void release(String runId, String triggerId) {
        String key = buildKey(runId, triggerId);
        Deque<String> owners = ownerIds.get(key);
        String ownerId = (owners != null) ? owners.poll() : key;
        if (ownerId == null) ownerId = key; // fallback for safety
        distributedSemaphore.release(key, ownerId);
        log.info("[EpochLimiter] Released slot: key={}, owner={}, available={}",
                key, ownerId, distributedSemaphore.availablePermits(key, maxPermits.getOrDefault(key, 1)));
    }

    /**
     * Check if any epoch is currently active for this trigger. O(1) - reads the
     * local {@link #ownerIds} deque size instead of round-tripping to the
     * distributed semaphore. {@code ownerIds[key].size()} is exactly the number
     * of permits this instance has acquired and not yet released, which IS the
     * "active epochs" count for this (run, trigger) on this JVM (epoch ownership
     * is instance-local - see {@link #hasAnyActiveEpochs} javadoc).
     *
     * <p>Pre-fix this called {@link DistributedSemaphore#availablePermits} which
     * in horizontal-scaling mode is a Redis round-trip per invocation. The
     * function is on the hot path of {@code resetForNextCycle} and the
     * orchestration recovery watchdog - hot path Redis trips were measurably
     * the bottleneck per the 2026-05-06 scalability audit.
     */
    public boolean hasActiveEpochs(String runId, String triggerId) {
        Deque<String> owners = ownerIds.get(buildKey(runId, triggerId));
        return owners != null && !owners.isEmpty();
    }

    /**
     * Check if any epoch is active across ALL triggers for a run. O(N) over
     * the local {@link #ownerIds} map where N = active triggers on this run on
     * this JVM (typically 1-3, never more than the number of trigger nodes in
     * the plan). No Redis round-trips.
     *
     * <p>Note: this checks LOCAL state. In multi-instance mode it only sees
     * permits acquired by THIS replica - which is the correct semantic because
     * workflow execution is instance-local: the same JVM owns acquire and
     * release for a given epoch.
     */
    public boolean hasAnyActiveEpochs(String runId) {
        String prefix = runId + ":";
        for (var entry : ownerIds.entrySet()) {
            if (entry.getKey().startsWith(prefix) && !entry.getValue().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Cleanup all limiters for a run (when run is deleted/stopped).
     */
    public void cleanup(String runId) {
        String prefix = runId + ":";
        distributedSemaphore.cleanupByPrefix(prefix);
        maxPermits.keySet().removeIf(k -> k.startsWith(prefix));
        ownerIds.keySet().removeIf(k -> k.startsWith(prefix));
        log.info("[EpochLimiter] Cleaned up limiters for runId={}", runId);
    }

    /**
     * Get the max concurrent epochs for a specific key (for testing/debugging).
     */
    public int getMaxConcurrentEpochs(String runId, String triggerId) {
        return maxPermits.getOrDefault(buildKey(runId, triggerId), 1);
    }

    private String buildKey(String runId, String triggerId) {
        return runId + ":" + triggerId;
    }
}
