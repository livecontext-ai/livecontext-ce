package com.apimarketplace.orchestrator.execution.v2.async;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * In-memory registry tracking agent executions that have been offloaded to async
 * worker pools and are waiting for a result callback.
 *
 * <p>The orchestrator engine yields with
 * {@link com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult#asyncRunning}
 * after publishing a task to the worker queue. The corresponding {@link PendingAgent} is
 * registered here, and {@link AgentAsyncCompletionService} consumes the entry when the
 * worker delivers a result, restoring all the context needed to drive the result back
 * through the sync persistence pipeline.</p>
 *
 * <h2>Restart safety</h2>
 * <p>The in-memory map is the fast path. When {@code scaling.agent.queue.enabled=true},
 * a {@link RedisPendingAgentStore} is autowired and every register/consume mirrors
 * to a Redis side-store with a TTL longer than the worker result-key TTL. On startup
 * the {@link AgentRecoveryService} reads from the side-store to rebuild the registry
 * and immediately polls for any results that arrived while the orchestrator was down.</p>
 *
 * <h2>Thread safety</h2>
 * <p>Backed by a {@link ConcurrentHashMap}; {@link #consume(String)} is the only
 * operation that mutates state, and it is atomic.</p>
 */
@Component
public class PendingAgentRegistry {

    private static final Logger logger = LoggerFactory.getLogger(PendingAgentRegistry.class);

    private final ConcurrentHashMap<String, PendingAgent> pending = new ConcurrentHashMap<>();

    /**
     * Optional Redis side-store. Wired only when {@code scaling.agent.queue.enabled=true}
     * (otherwise the bean is absent and the registry runs memory-only).
     */
    private RedisPendingAgentStore redisStore;

    @Autowired(required = false)
    public void setRedisStore(RedisPendingAgentStore redisStore) {
        this.redisStore = redisStore;
        if (redisStore != null) {
            logger.info("[PendingAgentRegistry] Redis side-store wired - entries will be mirrored for restart recovery");
        }
    }

    /**
     * Register a newly-offloaded agent execution.
     * Called immediately after the queue producer publishes the task.
     */
    public void register(PendingAgent agent) {
        if (agent == null || agent.correlationId() == null) {
            throw new IllegalArgumentException("PendingAgent and correlationId must not be null");
        }
        PendingAgent previous = pending.put(agent.correlationId(), agent);
        if (previous != null) {
            logger.warn("[PendingAgentRegistry] Overwrote existing entry for correlationId={}, runId={}",
                agent.correlationId(), agent.runId());
        } else {
            logger.debug("[PendingAgentRegistry] Registered: correlationId={}, runId={}, nodeId={}, agentType={}, size={}",
                agent.correlationId(), agent.runId(), agent.nodeId(), agent.agentType(), pending.size());
        }
        if (redisStore != null) {
            redisStore.store(agent);
        }
    }

    /**
     * Re-register an entry recovered from the Redis side-store on startup.
     * Skips writing back to Redis to avoid refreshing the TTL during recovery.
     */
    public void registerFromRecovery(PendingAgent agent) {
        if (agent == null || agent.correlationId() == null) {
            return;
        }
        pending.putIfAbsent(agent.correlationId(), agent);
        logger.debug("[PendingAgentRegistry] Recovered from Redis: correlationId={}, runId={}, nodeId={}",
            agent.correlationId(), agent.runId(), agent.nodeId());
    }

    /**
     * Atomically remove and return the entry for the given correlationId.
     * Returns {@link Optional#empty()} if no entry is registered (already consumed,
     * lost on restart, or unknown id).
     *
     * <h2>Cross-replica single-writer guarantee</h2>
     * <p>When the Redis side-store is wired, an atomic {@code GETDEL} is the source of
     * truth for ownership. Only the replica whose {@code GETDEL} actually removed the
     * key receives the {@link PendingAgent} payload; every other racing replica observes
     * an empty Optional and returns empty - even if its in-memory map still held the
     * entry (recovery-populated entries are shared across replicas). This makes
     * {@code consume} the single barrier that protects against duplicate delivery
     * (startup recovery, pub/sub broadcast, periodic scan - all converge here).</p>
     *
     * <h2>Redis winner uses Redis payload, not local map</h2>
     * <p>When the claim is won, we return the payload {@code GETDEL} produced, not the
     * local map entry. This matters for the strand-avoidance case: a crash-recovered
     * replica may have the Redis key but no local entry yet (it hasn't reached
     * {@code registerFromRecovery}). Returning the local map value would strand the run
     * - the Redis key is already deleted, nobody else will deliver.</p>
     *
     * <h2>Failure mode (fail-closed, audit P0 #2 - 2026-05-06)</h2>
     * <p>If Redis throws on the GETDEL, we return {@link Optional#empty()} instead of
     * falling back to the local map. The fallback was a cross-replica double-delivery
     * vector: Redis hiccup + 2 replicas with the same correlationId in their local
     * maps → both pop and deliver, double-executing the successor (and double-billing
     * if the agent was paid).</p>
     *
     * <p>Trade-off: a sustained Redis outage now blocks delivery rather than risking
     * duplicates. {@code AgentRecoveryService} catches the missed deliveries on the
     * next periodic scan via the result-key TTL (1h), so the worst case is delayed
     * delivery, not lost work. Without the side-store wired (memory-only mode), the
     * {@code ConcurrentHashMap.remove} atomicity is sufficient because there is no
     * other replica to race with.</p>
     */
    public Optional<PendingAgent> consume(String correlationId) {
        if (correlationId == null) {
            return Optional.empty();
        }

        if (redisStore == null) {
            // Memory-only mode: no cross-replica contention, local map is the barrier.
            PendingAgent removed = pending.remove(correlationId);
            if (removed != null) {
                logger.debug("[PendingAgentRegistry] Consumed (memory-only): correlationId={}, runId={}, size={}",
                    correlationId, removed.runId(), pending.size());
            }
            return Optional.ofNullable(removed);
        }

        // Redis-wired mode: atomic GETDEL is the cross-replica claim barrier.
        Optional<PendingAgent> claimed;
        try {
            claimed = redisStore.claim(correlationId);
        } catch (Exception e) {
            // Fail-closed: returning empty is safer than falling back to the local
            // map. The local map's entry only exists on this replica - if Redis is
            // down, we can't tell whether another replica has already won the claim.
            // Returning the local copy here would cause double-delivery on cross-replica
            // races. AgentRecoveryService picks up missed deliveries on its next scan
            // (typically 30s) via the result-key TTL, so total stalled time is bounded.
            // Drop the local copy too so a transient hiccup doesn't leave a ghost entry
            // that subsequent consume() calls would see as the authoritative source.
            logger.warn("[PendingAgentRegistry] Redis claim failed ({}): correlationId={} - fail-closed, returning empty (recovery scanner will retry)",
                e.getMessage(), correlationId);
            pending.remove(correlationId);
            return Optional.empty();
        }

        // Drop any local copy regardless of outcome so subsequent peek()/consume() cannot
        // see a stale entry. We won't trust the local copy - authoritative value comes
        // from Redis (or empty on lost claim).
        PendingAgent localCopy = pending.remove(correlationId);

        if (claimed.isEmpty()) {
            if (localCopy != null) {
                logger.info("[PendingAgentRegistry] Lost cross-replica claim: correlationId={}, runId={} - another replica already delivered",
                    correlationId, localCopy.runId());
            }
            return Optional.empty();
        }

        PendingAgent winner = claimed.get();
        logger.debug("[PendingAgentRegistry] Consumed (claim won): correlationId={}, runId={}, nodeId={}, size={}",
            correlationId, winner.runId(), winner.nodeId(), pending.size());
        return claimed;
    }

    /**
     * Remove all pending entries for a given run. Called when a run is cancelled or
     * stopped to prevent late-arriving async results from driving successor traversal
     * on a dead run. Also cleans the Redis side-store entries.
     *
     * @return the number of entries removed
     */
    public int removeByRunId(String runId) {
        if (runId == null) {
            return 0;
        }
        List<String> toRemove = pending.entrySet().stream()
            .filter(e -> runId.equals(e.getValue().runId()))
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());

        for (String correlationId : toRemove) {
            pending.remove(correlationId);
            if (redisStore != null) {
                try {
                    redisStore.claim(correlationId); // atomic GETDEL cleans the Redis key
                } catch (Exception e) {
                    logger.debug("[PendingAgentRegistry] Redis cleanup failed for correlationId={}: {}",
                        correlationId, e.getMessage());
                }
            }
        }
        if (!toRemove.isEmpty()) {
            logger.info("[PendingAgentRegistry] Removed {} entries for cancelled/stopped runId={}", toRemove.size(), runId);
        }
        return toRemove.size();
    }

    /**
     * Look up an entry without removing it. Used by the zombie scanner to inspect
     * stale entries before deciding to retry or cancel.
     */
    public Optional<PendingAgent> peek(String correlationId) {
        if (correlationId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(pending.get(correlationId));
    }

    /**
     * Return all entries that were registered before {@code now - timeout}.
     * Used by the zombie scanner to find agents whose worker likely died.
     */
    public List<PendingAgent> findStale(Duration timeout) {
        if (timeout == null) {
            return List.of();
        }
        Instant cutoff = Instant.now().minus(timeout);
        return pending.values().stream()
            .filter(p -> p.startedAt() != null && p.startedAt().isBefore(cutoff))
            .collect(Collectors.toList());
    }

    /**
     * Iterate over every pending entry. Iteration is weakly consistent.
     */
    public void forEach(BiConsumer<String, PendingAgent> action) {
        pending.forEach(action);
    }

    /**
     * Number of currently registered entries - exposed for metrics and tests.
     */
    public int size() {
        return pending.size();
    }

    /**
     * True if any pending agent is currently in flight for the given (runId, dagTriggerId,
     * epoch) tuple.
     *
     * <p>This is the async equivalent of {@code UnifiedSignalService.hasBlockingSignalsForDagAndEpoch}:
     * {@link com.apimarketplace.orchestrator.trigger.ReusableTriggerService#resetForNextCycle}
     * uses it to defer closing the epoch while async agents are still running. Without this
     * check, the engine yields immediately after dispatching the agents to the worker pool,
     * the cycle reset closes the epoch, and successor traversal on result arrival finds an
     * empty ready-node set - the classic "classify never executes after a guardrail split"
     * failure mode that motivated the whole asyncRunning refactor.</p>
     *
     * <p>Iteration is weakly consistent over the concurrent map; a race window where a
     * completion consumes the last entry between the check and {@code resetForNextCycle}
     * is tolerable because the completion itself calls {@code performDeferredReset} once
     * it observes the registry is empty for that epoch.</p>
     *
     * @param runId         workflow run id to match
     * @param dagTriggerId  trigger id whose DAG is being checked (required - the whole
     *                      epoch-scoping machinery is per-DAG)
     * @param epoch         the epoch to check
     * @return {@code true} if at least one {@link PendingAgent} matches
     */
    public boolean hasPendingFor(String runId, String dagTriggerId, int epoch) {
        if (runId == null || dagTriggerId == null) {
            return false;
        }
        // Local fast-path - authoritative when the dispatcher and the watchdog/reset
        // run on the same replica.
        for (PendingAgent p : pending.values()) {
            if (runId.equals(p.runId())
                && dagTriggerId.equals(p.dagTriggerId())
                && p.epoch() == epoch) {
                return true;
            }
        }
        // Redis fallback for cross-replica coverage. Without this, replica B's
        // resetForNextCycle would close the epoch while the 5 PendingAgents from
        // replica A's dispatch were invisible - exactly the Gmail Auto-Labeler
        // run da7994c7 regression (2026-05-06). Mirror of hasAnyPendingForRun's
        // two-tier strategy, scoped to (run, trigger, epoch).
        if (redisStore != null) {
            return redisStore.hasPendingFor(runId, dagTriggerId, epoch);
        }
        return false;
    }

    /**
     * True if any pending agent is currently in flight for the given run, regardless of
     * dagTriggerId or epoch.
     *
     * <p>Used by {@link com.apimarketplace.orchestrator.config.OrchestrationRecoveryService}
     * to decide whether a long-running RUNNING workflow is a true zombie or just waiting
     * on an offloaded async agent. Before horizontal scaling, agent execution was tracked
     * via {@code AGENT_EXECUTION} blocking signals in {@code workflow_signal_waits} and
     * the recovery service consulted only that table. After commits 730389011/d0a24209d/
     * 7d9aadeb1 moved async agents into this registry, the recovery service was missing
     * the second source of truth - any async agent taking longer than the 5-minute zombie
     * threshold (LLM read timeout is 600s) was force-FAILED while still in flight.</p>
     *
     * <h2>Cross-replica safety</h2>
     * <p>The local {@link ConcurrentHashMap} only contains entries registered by THIS
     * replica. Under {@code scaling.backend=redis} with multiple orchestrator instances,
     * the watchdog ShedLock can elect any replica - not necessarily the one that holds
     * the pending entry. So the lookup is two-tiered:</p>
     * <ol>
     *   <li><b>Local fast-path</b> - scan the in-memory map. Cheap and authoritative
     *       when the registering replica IS the watchdog winner.</li>
     *   <li><b>Redis fallback</b> - when the local map has nothing, consult the Redis
     *       reverse index ({@link RedisPendingAgentStore#hasAnyForRun(String)}). This
     *       closes the cross-replica gap without forcing the watchdog to do an
     *       unconditional Redis round-trip on every tick.</li>
     * </ol>
     *
     * <p>Redis failures are propagated up - the watchdog applies its own skip-on-error
     * policy so a transient Redis hiccup does not produce a false-positive zombie kill.</p>
     *
     * @param runId workflow run id to match
     * @return {@code true} if at least one {@link PendingAgent} is registered for the run
     *         either in this replica's heap OR in the Redis side-store
     */
    public boolean hasAnyPendingForRun(String runId) {
        if (runId == null) {
            return false;
        }
        // Local fast-path
        for (PendingAgent p : pending.values()) {
            if (runId.equals(p.runId())) {
                return true;
            }
        }
        // Redis fallback for cross-replica coverage
        if (redisStore != null) {
            return redisStore.hasAnyForRun(runId);
        }
        return false;
    }

    /**
     * Test/maintenance hook to clear the registry.
     */
    public void clear() {
        pending.clear();
    }
}
