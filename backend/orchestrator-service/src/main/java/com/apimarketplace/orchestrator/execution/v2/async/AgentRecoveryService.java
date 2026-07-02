package com.apimarketplace.orchestrator.execution.v2.async;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.AgentResultMessage;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.completion.StepCompletionOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Restart and zombie recovery for async agent executions.
 *
 * <h2>Failure modes covered</h2>
 * <ol>
 *   <li><b>Orchestrator restart while a worker is still processing</b> - the in-memory
 *       {@link PendingAgentRegistry} is wiped on JVM exit. Without recovery, the worker's
 *       eventual pub/sub message would have no matching pending entry and the run would
 *       hang indefinitely. On startup we list every entry in {@link RedisPendingAgentStore}
 *       and re-register it.</li>
 *   <li><b>Pub/sub message lost while orchestrator was down</b> - Redis pub/sub is
 *       fire-and-forget; if the orchestrator was offline when the worker published the
 *       result, the message is gone. The result key
 *       ({@code agent:result:{correlationId}}, 1h TTL) is the durable backup. After
 *       re-registering, we immediately poll each entry's result key and deliver any hits.</li>
 *   <li><b>Pub/sub message lost while orchestrator was up</b> - same fix as #2 but
 *       discovered by the periodic scan rather than startup recovery.</li>
 *   <li><b>Worker crashed before publishing</b> - startedAt grows stale with no result
 *       key ever appearing. Past the hard timeout (default 30 min, well under the 1h
 *       result-key TTL) we synthesize a failure result and deliver it so the run does
 *       not stay stuck in RUNNING forever.</li>
 * </ol>
 *
 * <h2>Activation</h2>
 * <p>Wired only when {@code scaling.agent.queue.enabled=true}; with the queue disabled
 * there is nothing to recover.</p>
 *
 * <h2>Why not in {@link AgentAsyncCompletionService}?</h2>
 * <p>Recovery has its own lifecycle (startup hook + periodic scan) and its own dependencies
 * (Redis side-store, result-key polling). Keeping it in a dedicated service avoids muddying
 * the completion-delivery hot path.</p>
 */
@Service
@ConditionalOnProperty(name = "scaling.agent.queue.enabled", havingValue = "true")
public class AgentRecoveryService {

    private static final Logger logger = LoggerFactory.getLogger(AgentRecoveryService.class);

    /** Must match {@code AgentQueueWorkerService.RESULT_KEY_PREFIX} in agent-service. */
    static final String RESULT_KEY_PREFIX = "agent:result:";

    private final RedisPendingAgentStore pendingStore;
    private final PendingAgentRegistry registry;
    private final AgentAsyncCompletionService completionService;
    private final SplitCoalesceTracker splitCoalesceTracker;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final WorkflowRunRepository runRepository;
    private final StepCompletionOrchestrator stepCompletionOrchestrator;
    private final WorkflowStepDataRepository stepDataRepository;

    /** Hard timeout - past this we give up and deliver a synthetic failure. */
    private final Duration hardTimeout;

    /**
     * Discriminates "stopped via stopWorkflow" from "alive between fires" when the run
     * status is WAITING_TRIGGER. Field-injected (vs constructor) to keep the existing
     * test fixtures' constructor signatures stable. Optional in tests - when null the
     * old inline check is used (which conservatively treats WAITING_TRIGGER as terminal),
     * matching the pre-fix behavior.
     */
    @Autowired(required = false)
    private com.apimarketplace.orchestrator.services.resume.RunCancellationGuard runCancellationGuard;

    /**
     * Site C of the ack-before-commit fix (post-2026-05-22 21:01 UTC OOM). Optional -
     * only wired when {@code scaling.agent.queue.enabled=true} (same gate as
     * {@link RedisInFlightStore}). When absent, {@link #replayInFlightEntries} is a no-op.
     */
    @Autowired(required = false)
    private RedisInFlightStore inFlightStore;

    /**
     * Backward-compat 8-arg constructor for existing test fixtures that don't yet
     * wire the Phase 2.F collaborators. The orphan-aggregate scan no-ops without
     * them.
     */
    public AgentRecoveryService(
            RedisPendingAgentStore pendingStore,
            PendingAgentRegistry registry,
            AgentAsyncCompletionService completionService,
            SplitCoalesceTracker splitCoalesceTracker,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            WorkflowRunRepository runRepository,
            long hardTimeoutMs) {
        this(pendingStore, registry, completionService, splitCoalesceTracker,
            redisTemplate, objectMapper, runRepository, null, null, hardTimeoutMs);
    }

    @Autowired
    public AgentRecoveryService(
            RedisPendingAgentStore pendingStore,
            PendingAgentRegistry registry,
            AgentAsyncCompletionService completionService,
            SplitCoalesceTracker splitCoalesceTracker,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            WorkflowRunRepository runRepository,
            @Autowired(required = false) StepCompletionOrchestrator stepCompletionOrchestrator,
            @Autowired(required = false) WorkflowStepDataRepository stepDataRepository,
            @Value("${scaling.agent.recovery.hard-timeout-ms:1800000}") long hardTimeoutMs) {
        this.pendingStore = pendingStore;
        this.registry = registry;
        this.completionService = completionService;
        this.splitCoalesceTracker = splitCoalesceTracker;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.runRepository = runRepository;
        this.stepCompletionOrchestrator = stepCompletionOrchestrator;
        this.stepDataRepository = stepDataRepository;
        this.hardTimeout = Duration.ofMillis(hardTimeoutMs);
    }

    /**
     * Startup recovery - runs once when the application is fully ready.
     *
     * <p>Sequence:
     * <ol>
     *   <li>List every entry in the Redis side-store</li>
     *   <li><b>Pre-register split coalesce barriers</b> with the post-restart in-flight count
     *       (grouped by {@code runId+nodeId+epoch}) BEFORE any delivery can run. Without
     *       this step, the first per-item delivery would fall back to
     *       {@link AgentAsyncCompletionService#ensureBarrierRegistered} which uses the
     *       ORIGINAL {@code splitItemData.items.size()} - i.e. the initial dispatch count.
     *       When some items were already delivered pre-restart the barrier would then
     *       wait forever for arrivals that will never come, deadlocking the split.</li>
     *   <li>Re-register each entry in the in-memory registry (without re-writing to Redis)</li>
     *   <li>Immediately poll each result key - if present, deliver via the completion service
     *       (this catches results that arrived while the orchestrator was down)</li>
     * </ol>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        logger.info("[AgentRecovery] Startup recovery beginning...");
        List<PendingAgent> recovered;
        try {
            recovered = pendingStore.listAll();
        } catch (Exception e) {
            logger.error("[AgentRecovery] Startup listAll failed - skipping recovery: {}", e.getMessage(), e);
            return;
        }

        List<RedisInFlightStore.InFlightEntry> stagedInFlight = listInFlightEntries();

        if (recovered.isEmpty()) {
            logger.info("[AgentRecovery] No pending entries to recover");
        } else {
            // Step 0: filter out entries whose workflow run is in a terminal state (CANCELLED,
            // COMPLETED, FAILED, TIMEOUT) OR genuinely stopped via stopWorkflow. WAITING_TRIGGER
            // alone is NOT terminal - it's the normal between-fires state for reusable triggers,
            // and a pending async result from the previous epoch must still be rehydrated.
            // RunCancellationGuard handles the WAITING_TRIGGER vs cancel-signal disambiguation.
            recovered = recovered.stream()
                .filter(agent -> {
                    try {
                        if (runCancellationGuard != null) {
                            if (runCancellationGuard.isRunStoppedOrTerminal(agent.runId())) {
                                logger.info("[AgentRecovery] Skipping entry for terminal/stopped run: correlationId={}, runId={}",
                                    agent.correlationId(), agent.runId());
                                return false;
                            }
                            return true;
                        }
                        // Fallback for test fixtures that don't wire the guard - preserves
                        // pre-fix behavior (overly conservative: drops alive-between-fires entries).
                        Optional<WorkflowRunEntity> runOpt = runRepository.findByRunIdPublic(agent.runId());
                        if (runOpt.isEmpty()) {
                            logger.info("[AgentRecovery] Skipping entry for non-existent run: correlationId={}, runId={}",
                                agent.correlationId(), agent.runId());
                            return false;
                        }
                        RunStatus status = runOpt.get().getStatus();
                        if (status.isTerminal() || status == RunStatus.WAITING_TRIGGER) {
                            logger.info("[AgentRecovery] Skipping entry for terminal/stopped run: correlationId={}, runId={}, status={}",
                                agent.correlationId(), agent.runId(), status);
                            return false;
                        }
                        return true;
                    } catch (Exception e) {
                        logger.warn("[AgentRecovery] Failed to check run status: correlationId={}, runId={} - including for safety",
                            agent.correlationId(), agent.runId());
                        return true; // fail-open: include the entry if we can't check
                    }
                })
                .collect(Collectors.toList());

            if (recovered.isEmpty()) {
                logger.info("[AgentRecovery] All pending entries belong to terminal runs - no pending side-store entries to recover");
            } else {
                logger.info("[AgentRecovery] Recovering {} pending entries from Redis side-store (after terminal filter)", recovered.size());

                // Step 1: pre-register split coalesce barriers with the actual post-restart in-flight
                // count (NOT the pre-restart items.size()). Must happen before registry population so
                // any concurrent pub/sub arrival sees a correctly-sized barrier. Include staged
                // in-flight entries because a restart can split one logical batch across
                // agent:pending:* and agent:in_flight:*.
                List<PendingAgent> barrierAgents = new ArrayList<>(recovered);
                stagedInFlight.stream()
                    .map(RedisInFlightStore.InFlightEntry::pending)
                    .forEach(barrierAgents::add);
                preRegisterSplitBarriers(barrierAgents);

                int reregistered = 0;
                int delivered = 0;
                for (PendingAgent agent : recovered) {
                    try {
                        registry.registerFromRecovery(agent);
                        // Re-assert the per-run reverse-index membership in Redis. Two reasons:
                        //  1. Historical orphans: pre-fix, store() did SET-then-SADD; if it
                        //     crashed between the two writes, the value exists in side-store
                        //     but the index is missing. listAll finds the value here, but
                        //     hasAnyForRun would still return false on every replica until
                        //     TTL - defeating the watchdog skip-gate that keeps long-running
                        //     async agents alive past the 5-minute zombie threshold.
                        //  2. Defensive: even after the store() reorder (SADD-then-SET), a
                        //     Redis EXPIRE refresh is cheap insurance during recovery so the
                        //     index TTL aligns with the value TTL we just observed.
                        pendingStore.touchIndex(agent);
                        reregistered++;
                        if (tryDeliverResult(agent)) {
                            delivered++;
                        }
                    } catch (Exception e) {
                        logger.warn("[AgentRecovery] Failed to recover entry: correlationId={}, error={}",
                            agent.correlationId(), e.getMessage());
                    }
                }
                logger.info("[AgentRecovery] Startup recovery complete: reregistered={}, delivered={}",
                    reregistered, delivered);
            }
        }

        // Site C (post-2026-05-22 OOM fix): replay any agent:in_flight:{cid} entries
        // staged immediately AFTER consume but never cleared because the JVM died between consume
        // and complete. The standard `recover` loop above only sees entries still in
        // agent:pending:* - the prod-fire scenario at 21:01 UTC had those entries already
        // GETDEL'd by consume on the pre-crash replica, leaving zero pending to recover.
        // The in-flight store is the second-chance buffer for that exact window.
        //
        // Idempotency: re-delivery is absorbed by idx_workflow_step_data_unique_v6 (the
        // INSERT … ON CONFLICT DO NOTHING contract in WorkflowStepDataBulkInserter), by
        // EpochState set semantics on completedNodeIds/failedNodeIds, and by
        // SplitCoalesceTracker.arrive's Lua atomicity on per-item barriers. Re-running
        // a successful complete() is a no-op.
        replayInFlightEntries(stagedInFlight, recovered.isEmpty());

        // Phase 2.F (2026-04-29 prod-incident fix): seal-then-crash recovery scan.
        // If the JVM crashed AFTER SplitCoalesceTracker.arrive() returned the sealed
        // batch but BEFORE recordSplitAggregateIfMissing wrote the global mark, the
        // step_data rows are persisted but EpochState has the node in NEITHER
        // completedNodeIds NOR failedNodeIds - the run hangs forever. The pending
        // entries were already consumed (registry empty above) so the standard
        // recovery loop above doesn't see them.
        //
        // Scan for split-aware nodes with persisted item rows (suppressGlobalMark=true
        // path) but no aggregate marker. Idempotent - safe to call repeatedly.
        recoverOrphanedSplitAggregates();
    }

    /**
     * Replay every staged {@link RedisInFlightStore.InFlightEntry} through
     * {@link AgentAsyncCompletionService#replayInFlightResult}. The ordinary
     * {@code onAgentResult} path is intentionally not used here: the pre-crash Redis GETDEL
     * is what made the entry orphaned, so {@code agent:pending:{correlationId}} no longer
     * exists and {@link PendingAgentRegistry#consume(String)} must keep rejecting it.
     *
     * <p>Safe to call repeatedly: idempotency is enforced at the persistence layer
     * ({@code idx_workflow_step_data_unique_v6}) and at the in-memory state
     * ({@link com.apimarketplace.orchestrator.domain.execution.EpochState} set semantics).
     * No-op when the in-flight store is not wired (queue disabled).
     */
    void replayInFlightEntries() {
        replayInFlightEntries(listInFlightEntries(), true);
    }

    private List<RedisInFlightStore.InFlightEntry> listInFlightEntries() {
        if (inFlightStore == null) return List.of();
        try {
            return inFlightStore.listAll();
        } catch (Exception e) {
            logger.warn("[AgentRecovery] in_flight listAll failed - skipping replay: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private void replayInFlightEntries(List<RedisInFlightStore.InFlightEntry> staged, boolean preRegisterBarriers) {
        if (staged.isEmpty()) {
            logger.info("[AgentRecovery] in_flight replay: 0 entries");
            return;
        }
        // Same partial-restart split guard as the pending side-store path: the in-flight
        // store can contain only the items consumed before the crash, so use the staged
        // count instead of the original split item count snapshotted in splitItemData.
        if (preRegisterBarriers) {
            preRegisterSplitBarriers(staged.stream()
                .map(RedisInFlightStore.InFlightEntry::pending)
                .collect(Collectors.toList()));
        }
        int replayed = 0;
        int claimedElsewhere = 0;
        for (RedisInFlightStore.InFlightEntry e : staged) {
            String correlationId = e.pending().correlationId();
            if (correlationId == null || correlationId.isEmpty()) {
                // Malformed staged entry (no correlation id): nothing downstream could match
                // it either - skip it explicitly rather than mislabeling it as peer-claimed.
                logger.warn("[AgentRecovery] in_flight replay skipped - staged entry has no correlationId: runId={}",
                    e.pending().runId());
                continue;
            }
            // Cross-pod claim: during a rolling restart two replicas boot near-simultaneously
            // and both enumerate the same staged entries. Step-row persistence is idempotent
            // (idx_workflow_step_data_unique_v6) but successor traversal can re-fire
            // non-idempotent side-effecting nodes, so exactly one pod may replay each entry.
            // tryClaimReplay fails open on Redis errors (returns true) - at-least-once wins.
            if (inFlightStore != null && !inFlightStore.tryClaimReplay(correlationId)) {
                claimedElsewhere++;
                logger.info("[AgentRecovery] in_flight replay skipped - claimed by another replica: correlationId={}, runId={}",
                    correlationId, e.pending().runId());
                continue;
            }
            try {
                // The original Redis pending key was already GETDEL'd pre-crash. Replaying
                // must therefore bypass PendingAgentRegistry.consume(), whose Redis path
                // correctly refuses missing agent:pending:{cid} keys for ordinary delivery.
                boolean accepted = completionService.replayInFlightResult(e);
                if (accepted) replayed++;
            } catch (Exception ex) {
                // Best-effort claim release. On most throw paths the delivery pipeline's own
                // finally has ALREADY cleared the staged entry (nothing left to re-scan), so
                // this only matters for the narrow pre-delivery throws (e.g. org-scope
                // binding) where the entry survives - there, holding the claim would delay
                // the retry by the claim TTL for no reason.
                if (inFlightStore != null) {
                    inFlightStore.releaseReplayClaim(correlationId);
                }
                logger.warn("[AgentRecovery] in_flight replay failed: correlationId={}, runId={}, error={}",
                    correlationId, e.pending().runId(), ex.getMessage());
            }
        }
        logger.info("[AgentRecovery] in_flight replay complete: staged={}, replayed={}, claimedElsewhere={}",
            staged.size(), replayed, claimedElsewhere);
    }

    /**
     * Phase 2.F - find runs where a split-async node has at least one persisted
     * step_data row in an epoch but no aggregate global mark in EpochState. Calls
     * {@link StepCompletionOrchestrator#recordSplitAggregateIfMissing} for each.
     *
     * <p>Cheap due to V155 index on {@code (run_id, normalized_key, epoch, status)}
     * - but could be expensive if many active runs exist; the called method
     * short-circuits via its own idempotency guard before issuing the COUNT queries
     * for already-aggregated nodes.</p>
     *
     * <p>Package-private for direct unit testing.</p>
     */
    void recoverOrphanedSplitAggregates() {
        if (stepCompletionOrchestrator == null || stepDataRepository == null) {
            logger.debug("[AgentRecovery] Phase 2.F scan skipped (collaborators unavailable)");
            return;
        }
        try {
            // Find all runs that are still active (RUNNING / PENDING) - terminal runs
            // don't need recovery (their state is final regardless of orphan markers).
            List<WorkflowRunEntity> activeRuns = new ArrayList<>(runRepository.findByStatus(RunStatus.RUNNING));
            activeRuns.addAll(runRepository.findByStatus(RunStatus.PENDING));
            if (activeRuns.isEmpty()) {
                logger.debug("[AgentRecovery] Phase 2.F: no active runs");
                return;
            }
            int recovered = 0;
            for (WorkflowRunEntity run : activeRuns) {
                try {
                    // Bind orgId on the startup-recovery thread. recordSplitAggregateIfMissing
                    // is idempotent and usually short-circuits, but a fresh aggregate write
                    // would trip the @PrePersist OrgScopedEntityListener fail-loud post-V263
                    // (same shape as the 2026-05-20 16:57 UTC AgentResultSubscriber prod fire).
                    String orgId = run.getOrganizationId();
                    int[] perRun = { 0 };
                    Runnable runRecover = () -> perRun[0] = recoverOrphanedAggregatesForRun(run.getRunIdPublic());
                    if (orgId != null && !orgId.isBlank()) {
                        com.apimarketplace.common.web.TenantResolver.runWithOrgScope(orgId, runRecover);
                    } else {
                        runRecover.run();
                    }
                    recovered += perRun[0];
                } catch (Exception e) {
                    logger.warn("[AgentRecovery] Phase 2.F: failed for runId={}: {}",
                        run.getRunIdPublic(), e.getMessage());
                }
            }
            if (recovered > 0) {
                logger.info("[AgentRecovery] Phase 2.F: recovered {} orphaned split aggregates across {} active runs",
                    recovered, activeRuns.size());
            }
        } catch (Exception e) {
            logger.error("[AgentRecovery] Phase 2.F scan failed: {}", e.getMessage(), e);
        }
    }

    /**
     * For one run: scan {@code workflow_step_data} for split-aware nodes that have at
     * least one persisted item row but might be missing the aggregate global mark.
     * For each (normalizedKey, epoch) tuple seen, call {@code recordSplitAggregateIfMissing} -
     * which is itself idempotent and skips when the global mark is already present.
     *
     * <p>Post-2026-05-22 OOM hardening: previous implementation called
     * {@code stepDataRepository.findByRunId(runId)} which loads every row's full JSONB
     * (~100-500 KB each on long-lived runs, 17k rows possible - the 2026-05-07 OOM shape).
     * Now uses {@link com.apimarketplace.orchestrator.persistence.SplitAggregateProjection}
     * which projects {@code (normalizedKey, epoch, COUNT(*))} directly via SQL
     * {@code GROUP BY HAVING COUNT &gt;= 2} - zero JSONB into heap, server-side aggregation.
     */
    private int recoverOrphanedAggregatesForRun(String runId) {
        List<com.apimarketplace.orchestrator.persistence.SplitAggregateProjection> projections =
            stepDataRepository.findSplitAggregateProjectionsByRunId(runId);
        if (projections == null || projections.isEmpty()) return 0;

        int recovered = 0;
        for (var p : projections) {
            // recordSplitAggregateIfMissing resolves null triggerId via snapshot's
            // getDefaultTriggerId. Multi-trigger workflows: we let it resolve from
            // the snapshot. The idempotency guard handles re-invocation safely.
            try {
                stepCompletionOrchestrator.recordSplitAggregateIfMissing(
                    runId, null, p.normalizedKey(), p.epoch());
                recovered++;
            } catch (Exception ex) {
                logger.warn("[AgentRecovery] Phase 2.F: aggregate recovery failed: runId={}, key={}, epoch={}, error={}",
                    runId, p.normalizedKey(), p.epoch(), ex.getMessage());
            }
        }
        return recovered;
    }

    /**
     * Group recovered entries by {@code (runId, nodeId, epoch)} and register a
     * {@link SplitCoalesceTracker} barrier per group, using the size of the group as
     * the expected arrival total.
     *
     * <p>Only entries with a non-empty {@code splitItemData} participate - non-split
     * agents do not use the coalesce tracker.</p>
     *
     * <p>Package-private for direct unit testing.</p>
     */
    void preRegisterSplitBarriers(List<PendingAgent> recovered) {
        Map<SplitBarrierKey, Integer> groupCounts = new HashMap<>();
        for (PendingAgent agent : recovered) {
            if (agent.splitItemData() == null || agent.splitItemData().isEmpty()) {
                continue;
            }
            SplitBarrierKey key = new SplitBarrierKey(agent.runId(), agent.nodeId(), agent.epoch());
            groupCounts.merge(key, 1, Integer::sum);
        }
        for (Map.Entry<SplitBarrierKey, Integer> e : groupCounts.entrySet()) {
            SplitBarrierKey k = e.getKey();
            int inFlightCount = e.getValue();
            try {
                splitCoalesceTracker.register(k.runId(), k.nodeId(), k.epoch(), inFlightCount);
                logger.info("[AgentRecovery] Pre-registered split barrier (recovery): runId={}, nodeId={}, epoch={}, inFlight={}",
                    k.runId(), k.nodeId(), k.epoch(), inFlightCount);
            } catch (Exception ex) {
                logger.warn("[AgentRecovery] Failed to pre-register split barrier: key={}, error={}",
                    k, ex.getMessage());
            }
        }
    }

    /** Composite key for split barrier grouping during recovery pre-registration. */
    private record SplitBarrierKey(String runId, String nodeId, int epoch) {}

    /**
     * Periodic scan - polls each in-memory pending entry for a result key.
     *
     * <p>Two responsibilities:
     * <ul>
     *   <li><b>Pub/sub miss recovery</b>: if the result key exists but the entry is still
     *       pending, the pub/sub message was dropped - deliver it now.</li>
     *   <li><b>Hard timeout</b>: if startedAt is past {@link #hardTimeout} and no result
     *       key has appeared, deliver a synthetic failure so the run doesn't hang.</li>
     * </ul>
     *
     * <p>Default interval 30s. Iteration is weakly consistent - entries added during the
     * scan are picked up on the next pass.</p>
     */
    @Scheduled(fixedDelayString = "${scaling.agent.recovery.scan-interval-ms:30000}")
    public void scanPending() {
        if (registry.size() == 0) {
            return;
        }
        logger.debug("[AgentRecovery] Periodic scan starting: pendingSize={}", registry.size());
        int delivered = 0;
        int failed = 0;
        Instant cutoff = Instant.now().minus(hardTimeout);

        // Snapshot the entries to avoid concurrent-modification issues - forEach is
        // weakly consistent and we want a stable view to iterate over.
        List<PendingAgent> snapshot = new ArrayList<>();
        registry.forEach((id, agent) -> snapshot.add(agent));

        int skippedTerminal = 0;
        for (PendingAgent agent : snapshot) {
            try {
                // Skip entries for runs that have been cancelled/stopped since the entry
                // was registered. The deliverUnderLock guard also checks, but skipping here
                // avoids unnecessary result-key lookups and synthetic failure generation.
                if (isRunTerminalOrStopped(agent.runId())) {
                    registry.consume(agent.correlationId()); // clean up the entry
                    skippedTerminal++;
                    continue;
                }
                if (tryDeliverResult(agent)) {
                    delivered++;
                    continue;
                }
                if (agent.startedAt() != null && agent.startedAt().isBefore(cutoff)) {
                    deliverSyntheticFailure(agent, "Hard timeout reached after " + hardTimeout);
                    failed++;
                }
            } catch (Exception e) {
                logger.warn("[AgentRecovery] Scan iteration failed for correlationId={}: {}",
                    agent.correlationId(), e.getMessage());
            }
        }
        if (delivered > 0 || failed > 0 || skippedTerminal > 0) {
            logger.info("[AgentRecovery] Periodic scan complete: delivered={}, failed={}, skippedTerminal={}",
                delivered, failed, skippedTerminal);
        }
    }

    /**
     * Check if the workflow run should drop late async work. Delegates to
     * {@link com.apimarketplace.orchestrator.services.resume.RunCancellationGuard}
     * so the WAITING_TRIGGER vs cancel-signal disambiguation is consistent across the
     * delivery hot path and this recovery scan.
     *
     * <p>Test-fallback path: when the guard is not wired (legacy fixtures) we
     * preserve the conservative pre-fix behavior - including WAITING_TRIGGER as
     * "stopped" - so existing tests still pass.
     */
    private boolean isRunTerminalOrStopped(String runId) {
        if (runCancellationGuard != null) {
            return runCancellationGuard.isRunStoppedOrTerminal(runId);
        }
        try {
            Optional<WorkflowRunEntity> runOpt = runRepository.findByRunIdPublic(runId);
            if (runOpt.isEmpty()) {
                return true;
            }
            RunStatus status = runOpt.get().getStatus();
            return status.isTerminal() || status == RunStatus.WAITING_TRIGGER;
        } catch (Exception e) {
            return false; // fail-open
        }
    }

    /**
     * Check the worker's result key. If a payload exists, reconstruct an
     * {@link AgentResultMessage} and deliver it through the completion service.
     *
     * @return true if a result was delivered, false if no result key was found
     */
    @SuppressWarnings("unchecked")
    boolean tryDeliverResult(PendingAgent agent) {
        String resultKey = RESULT_KEY_PREFIX + agent.correlationId();
        String json;
        try {
            json = redisTemplate.opsForValue().get(resultKey);
        } catch (Exception e) {
            logger.warn("[AgentRecovery] Result key fetch failed: correlationId={}, error={}",
                agent.correlationId(), e.getMessage());
            return false;
        }
        if (json == null) {
            return false;
        }

        try {
            Map<String, Object> raw = objectMapper.readValue(json, Map.class);
            AgentResultMessage result = AgentResultPayloadParser.decode(
                agent.correlationId(), raw, agent.runId(), agent.nodeId(), agent.agentType());

            logger.info("[AgentRecovery] Delivering recovered result: correlationId={}, runId={}, success={}",
                agent.correlationId(), agent.runId(), result.success());
            boolean accepted = completionService.onAgentResult(result);
            if (accepted) {
                // Clean up the result key - the worker won't, and the orchestrator no longer needs it
                try {
                    redisTemplate.delete(resultKey);
                } catch (Exception e) {
                    logger.debug("[AgentRecovery] Failed to delete consumed result key: {}", e.getMessage());
                }
            }
            return accepted;
        } catch (Exception e) {
            logger.warn("[AgentRecovery] Failed to parse/deliver result: correlationId={}, error={}",
                agent.correlationId(), e.getMessage());
            return false;
        }
    }

    /**
     * Deliver a synthetic failure for an entry that has exceeded the hard timeout.
     * The completion service treats this exactly like a worker-published failure,
     * so the run transitions to FAILED via the normal pipeline.
     *
     * <p>The payload is decoded through {@link AgentResultPayloadParser} - the same
     * decoder the pub/sub and result-key paths use - so the success-boolean logic
     * is guaranteed to match (an {@code error} key forces {@code success=false}).</p>
     */
    void deliverSyntheticFailure(PendingAgent agent, String reason) {
        logger.warn("[AgentRecovery] Delivering synthetic failure: correlationId={}, runId={}, nodeId={}, reason={}",
            agent.correlationId(), agent.runId(), agent.nodeId(), reason);

        Map<String, Object> rawResult = Map.of(
            "success", false,
            "error", reason,
            "synthetic", true
        );
        AgentResultMessage failure = AgentResultPayloadParser.decode(
            agent.correlationId(), rawResult, agent.runId(), agent.nodeId(), agent.agentType());
        completionService.onAgentResult(failure);
    }
}
