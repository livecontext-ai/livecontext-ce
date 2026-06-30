package com.apimarketplace.orchestrator.execution.v2.async;

import com.apimarketplace.orchestrator.domain.execution.AgentResultMessage;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTree;
import com.apimarketplace.orchestrator.execution.v2.engine.StepByStepExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.services.NodeSearchService;
import com.apimarketplace.orchestrator.execution.v2.services.SignalResumeService;
import com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService;
import com.apimarketplace.orchestrator.execution.v2.services.V2StepByStepContextManager;
import com.apimarketplace.orchestrator.execution.v2.services.V2StepByStepService;
import com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.agent.AgentExecutionResult;
import com.apimarketplace.orchestrator.services.completion.StepCompletionOrchestrator;
import com.apimarketplace.orchestrator.services.resume.ExecutionContextManager;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.services.resume.WorkflowRunState;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Delivers async agent execution results back into the orchestrator's sync persistence
 * pipeline.
 *
 * <h2>Why this exists</h2>
 * <p>When {@code scaling.agent.queue.enabled=true}, an agent node yields with
 * {@link com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult#asyncRunning}
 * after pushing the request to a Redis worker queue. The engine returns immediately and
 * the worker eventually delivers an {@link AgentResultMessage}.</p>
 *
 * <p>This service receives that result, looks up the matching {@link PendingAgent} entry,
 * and calls the <b>same</b> {@link StepCompletionOrchestrator#completeStep} that the
 * inline (sync) execution path uses. This is the central design point: by reusing the
 * sync persistence pipeline, all the existing field-derivation logic
 * (e.g. {@code enrichAgentFields → selectedBranch} for guardrail/classify) runs without
 * duplication, eliminating the silent-bug class that motivated the refactor.</p>
 *
 * <h2>Successor traversal</h2>
 * <p>After persistence, ready-node traversal is needed because the engine has already
 * yielded - nothing else will pick up the agent's successors. The loop body mirrors
 * {@code SignalResumeService.resumeAutoModeUnderLock}: get ready nodes →
 * filter triggers → execute → repeat. The duplication is intentional: the persistence
 * pipeline is shared (the bug-prone part), but the traversal driver is small and
 * keeping it local avoids coupling this service to the signal subsystem.</p>
 *
 * <h2>Concurrency</h2>
 * <p>A per-run mutex (Caffeine TTL cache) serializes concurrent completions for the
 * same run. This protects against H2 lost-update scenarios in tests and against
 * concurrent {@code completeStep} + {@code getReadyNodes} reading stale state.</p>
 */
@Service
public class AgentAsyncCompletionService {

    private static final Logger logger = LoggerFactory.getLogger(AgentAsyncCompletionService.class);

    /** Safety cap for the ready-node loop, mirrors SignalResumeService. */
    private static final int MAX_LOOP_ITERATIONS = 100;

    /**
     * Fixed-size striped lock pool used to serialize concurrent agent result deliveries
     * for the same workflow run. Replaces an earlier Caffeine cache that could evict
     * lock objects mid-critical-section, allowing two deliveries to run without mutual
     * exclusion. 256 stripes give a collision rate of ~0.4% per run pair - cheap
     * compared to the blast radius of losing a lock.
     */
    private static final int LOCK_STRIPE_COUNT = 256;
    private final Object[] runLockStripes = new Object[LOCK_STRIPE_COUNT];

    {
        for (int i = 0; i < LOCK_STRIPE_COUNT; i++) {
            runLockStripes[i] = new Object();
        }
    }

    private Object lockFor(String runId) {
        return runLockStripes[Math.floorMod(runId.hashCode(), LOCK_STRIPE_COUNT)];
    }

    private final PendingAgentRegistry registry;
    private final StepCompletionOrchestrator stepCompletionOrchestrator;
    private final SplitContextManager splitContextManager;
    private final RunningNodeTracker runningNodeTracker;
    private final SplitCoalesceTracker splitCoalesceTracker;
    private final WorkflowRunRepository runRepository;

    /**
     * Durable ack store for the window between {@link PendingAgentRegistry#consume} and
     * {@link StepCompletionOrchestrator#complete}. Optional - present only when
     * {@code scaling.agent.queue.enabled=true} (same gate as
     * {@link RedisInFlightStore}). When absent, the service degrades to the prior
     * (unprotected) ack-then-persist behavior.
     *
     * <p>Post-2026-05-22 21:01 UTC OOM: closes the gap where 10 in-flight items were
     * consumed (registry side-store GETDEL'd) but never persisted because the JVM died
     * mid-flight. Recovery had nothing to replay.
     */
    @Autowired(required = false)
    private RedisInFlightStore inFlightStore;

    /**
     * Local E2E-only delay inserted immediately after {@link RedisInFlightStore#stage}.
     * Default is 0 and production behavior is unchanged. The post-OOM crash/restart E2E
     * enables this for a short window so it can kill the JVM after Redis GETDEL+stage
     * and before the step completion commit.
     */
    @org.springframework.beans.factory.annotation.Value("${orchestrator.e2e.agent-completion-delay-ms:0}")
    private long e2eAgentCompletionDelayMs;

    // Lazy to break the otherwise-circular wiring through the resume / engine layer.
    @Autowired
    @Lazy
    private V2StepByStepService v2StepByStepService;

    @Autowired
    @Lazy
    private WorkflowResumeService workflowResumeService;

    @Autowired
    @Lazy
    private ExecutionContextManager executionContextManager;

    /**
     * Used by {@link #rebuildLoadedExecution} to materialise the {@code LoadedExecution}
     * (tree + execution) once per delivery - passed through {@link #executeReadyNodesLoop}
     * so successor traversals can skip their inner {@code reconstructState}. Cuts ~60-100
     * reconstructState calls per 37-item async burst.
     */
    @Autowired
    @Lazy
    private com.apimarketplace.orchestrator.execution.v2.cache.ExecutionCacheManager executionCacheManager;

    /**
     * Used to perform the deferred cycle reset when the last async agent for a given
     * (runId, dagTriggerId, epoch) triple has been delivered. Reusing
     * {@link SignalResumeService#performDeferredReset} keeps the reset logic single-sourced
     * (WorkflowRunEntity + WorkflowPlan + WorkflowExecution rebuild) instead of duplicating
     * it here - the async subsystem only needs to decide "when" to call it.
     */
    @Autowired
    @Lazy
    private SignalResumeService signalResumeService;

    /**
     * Used to emit the post-persistence downstream events (edge status, decisionEvaluated,
     * full snapshot) that the inline path tacks onto {@code emitNodeComplete}.
     * {@link StepCompletionOrchestrator#completeStep} only covers DB persist + NodeCounts +
     * step event, so without this the async path silently drops edge statusCounts and
     * snapshot pushes - observable as "streams and edges disappeared" once async mode
     * was enabled in split context.
     */
    @Autowired
    @Lazy
    private V2ExecutionEventService v2ExecutionEventService;

    /**
     * Used to look up the cached {@link ExecutionTree} for a run so we can resolve the
     * agent's {@link ExecutionNode} from its id - {@code emitPostPersistenceCompletion}
     * needs the full node to walk outgoing edges and detect branching.
     */
    @Autowired
    @Lazy
    private V2StepByStepContextManager v2StepByStepContextManager;

    /**
     * Discriminates "stopped via stopWorkflow" from "alive between fires" when the run
     * status is WAITING_TRIGGER. Without this, late async results landing on a
     * reusable-trigger run that just cycled to WAITING_TRIGGER were silently dropped -
     * see {@link com.apimarketplace.orchestrator.services.resume.RunCancellationGuard}
     * for the full incident note (Gmail Auto-Labeler run da7994c7, 2026-05-06).
     *
     * <p>{@code required = false} so legacy unit-test fixtures that don't wire this
     * collaborator fall through to the inline {@link #isRunTerminalOrStoppedFallback}
     * path (which preserves the conservative pre-fix behavior). Production always has
     * the bean autowired by Spring.
     */
    @Autowired(required = false)
    private com.apimarketplace.orchestrator.services.resume.RunCancellationGuard runCancellationGuard;

    /**
     * Used to cascade SKIPPED status to descendants when an async agent fails.
     * Mirrors the sync engine's post-FAIL block in
     * {@link com.apimarketplace.orchestrator.execution.v2.engine.UnifiedExecutionEngine}.
     * Without this, async failures only mark direct outgoing edges (via
     * {@code EdgeStatusEmitter}) and leave descendants invisible - no DB rows, no
     * {@code EpochState.skippedNodeIds}, downstream merges hang. Field-injected to
     * keep the constructor stable and match the pattern of other lazy-wired
     * collaborators in this service.
     */
    @Autowired
    private com.apimarketplace.orchestrator.execution.v2.services.V2SkipPropagationService skipPropagationService;

    /**
     * Used to drop split successors that already reached a terminal status in the epoch before
     * re-dispatching them at barrier seal. A recovery re-delivery
     * ({@code AgentRecoveryService.replayInFlightEntries} after a crash) re-seals the barrier and
     * re-enters {@link #traverseSuccessorsPerItem}; without the filter an already-FAILED
     * side-effecting successor (e.g. an unconfigured send_email / http_request that hit the
     * endpoint then errored) would be re-executed, duplicating the outbound call. Mirrors
     * {@code SignalResumeService.filterOutAlreadyTerminalNodes} via the shared
     * {@code StateSnapshot.getTerminalNodeIds} primitive. {@code required = false} so unit
     * fixtures that don't wire it fall through to unfiltered dispatch (fail-open); production
     * always has the bean.
     */
    @Autowired(required = false)
    private com.apimarketplace.orchestrator.services.state.StateSnapshotService stateSnapshotService;

    /**
     * Stateless traversal helper that searches an execution tree for a node by id.
     * Direct injection (not lazy) because it has no cyclical dependencies.
     */
    private final NodeSearchService nodeSearchService;

    /**
     * Optional: used to record agent_executions rows for the metrics dashboard.
     *
     * <p>The inline path in {@link com.apimarketplace.orchestrator.execution.v2.nodes.AgentNode}
     * calls {@code agentClient.recordObservability(...)} after every
     * {@code executeAgent}/{@code executeClassify}/{@code executeGuardrail}. With
     * {@code scaling.agent.queue.enabled=true}, agent/classify/guardrail are routed through
     * {@code executeAgentAsyncQueue} which yields before reaching those recording sites - so
     * without an async-side recorder, the {@code agent_executions} table stops receiving rows
     * and the frontend "Agent Performance" metrics dashboard goes blank.</p>
     *
     * <p>Null-safe: when unwired in focused unit tests, observability is simply skipped.</p>
     */
    @Autowired(required = false)
    private com.apimarketplace.agent.client.AgentClient agentClient;

    /**
     * Optional: persists user/assistant messages to the agent's conversation in async mode.
     *
     * <p>The inline path calls {@code conversationManager.saveAssistantResponse} +
     * {@code completeStream} at the tail of {@code executeAgent}. The async path yields
     * before that tail runs - so without a delivery-time hook the agent's conversation
     * stays empty no matter how many times the workflow fires the agent (regression
     * observed in prod for the "Smart Assistant" agent: 10 successful executions, 0
     * messages in {@code conversation.messages}). The user prompt is saved at enqueue
     * time in {@code AgentNode.executeAgentAsyncQueue}; this field is used here to save
     * the assistant response and emit {@code stream_completed} once the worker delivers.</p>
     *
     * <p>Null-safe: focused unit tests that don't exercise the conversation pipeline
     * leave this unwired and the save is silently skipped - same contract as
     * {@code agentClient} above.</p>
     */
    @Autowired(required = false)
    private com.apimarketplace.orchestrator.services.agent.AgentConversationManager conversationManager;

    /**
     * Phase 1 (2026-04-29 prod-incident fix) feature flag.
     * When false, falls back to legacy global-readiness-walker path that loses
     * per-item routing on partial-failure splits. Default true.
     */
    @org.springframework.beans.factory.annotation.Value(
        "${orchestrator.partial-failure.async-split-per-item-traversal:true}")
    private boolean perItemTraversalEnabled;

    public AgentAsyncCompletionService(
            PendingAgentRegistry registry,
            StepCompletionOrchestrator stepCompletionOrchestrator,
            SplitContextManager splitContextManager,
            RunningNodeTracker runningNodeTracker,
            SplitCoalesceTracker splitCoalesceTracker,
            NodeSearchService nodeSearchService,
            WorkflowRunRepository runRepository) {
        this.registry = registry;
        this.stepCompletionOrchestrator = stepCompletionOrchestrator;
        this.splitContextManager = splitContextManager;
        this.runningNodeTracker = runningNodeTracker;
        this.splitCoalesceTracker = splitCoalesceTracker;
        this.nodeSearchService = nodeSearchService;
        this.runRepository = runRepository;
    }

    /**
     * Process an agent result delivered by the worker pool.
     *
     * <p>Idempotent: if the correlationId is unknown (already processed, or lost on
     * restart) the call returns {@code false} and logs at debug level. Restart-recovery
     * for lost entries is handled by {@link AgentRecoveryService}.</p>
     *
     * <p>This method is intentionally thin - its job is to route the result through the
     * lock, consume the entry and delegate to {@link #deliverUnderLock}. All the
     * per-stage work (context restore, persistence, coalesce, traversal) lives in
     * dedicated helpers so each concern can be reviewed and tested in isolation.</p>
     *
     * @return {@code true} if the result was matched to a pending entry and persisted
     */
    public boolean onAgentResult(AgentResultMessage result) {
        if (result == null || result.correlationId() == null) {
            logger.warn("[AgentAsyncCompletion] Received null result or missing correlationId");
            return false;
        }

        Optional<PendingAgent> opt = registry.consume(result.correlationId());
        if (opt.isEmpty()) {
            logger.debug("[AgentAsyncCompletion] No pending entry for correlationId={} (already processed or unknown)",
                result.correlationId());
            return false;
        }

        PendingAgent pending = opt.get();

        // Site A (post-2026-05-22 OOM fix, post-audit revision): stage the durable ack
        // record IMMEDIATELY after consume succeeds, using the consumed payload. Earlier
        // draft staged BEFORE consume via peek() - that left a hole when peek returned
        // empty but consume then won the cross-replica GETDEL (the entry was Redis-only,
        // not in this replica's local map). The window between consume() and this line
        // is sub-millisecond (one Optional.get + one method call); the window from
        // here through deliverUnderLock is where the prod OOM hit (milliseconds to
        // seconds). The fix correctly protects that window.
        if (inFlightStore != null) {
            inFlightStore.stage(pending, result);
            delayAfterInFlightStageForE2e(pending, result);
        }

        logger.info("[AgentAsyncCompletion] Delivering async agent result: correlationId={}, runId={}, nodeId={}, agentType={}, success={}",
            result.correlationId(), pending.runId(), pending.nodeId(), pending.agentType(), result.success());

        return deliverConsumedPending(pending, result);
    }

    private void delayAfterInFlightStageForE2e(PendingAgent pending, AgentResultMessage result) {
        if (e2eAgentCompletionDelayMs <= 0) {
            return;
        }
        logger.warn("[AgentAsyncCompletion][E2E] Delaying {} ms after in-flight stage: correlationId={}, runId={}, nodeId={}",
            e2eAgentCompletionDelayMs, result.correlationId(), pending.runId(), pending.nodeId());
        try {
            Thread.sleep(e2eAgentCompletionDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("[AgentAsyncCompletion][E2E] Delay interrupted after in-flight stage: correlationId={}, runId={}",
                result.correlationId(), pending.runId());
        }
    }

    /**
     * Replay a result recovered from {@code agent:in_flight:{correlationId}} after the
     * original {@code agent:pending:{correlationId}} key was already consumed before a
     * process crash. This bypasses {@link PendingAgentRegistry#consume(String)} because
     * that Redis GETDEL key is intentionally gone in the crash window being recovered.
     */
    boolean replayInFlightResult(RedisInFlightStore.InFlightEntry entry) {
        if (entry == null || entry.pending() == null || entry.result() == null) {
            logger.warn("[AgentAsyncCompletion] Cannot replay null in-flight entry");
            return false;
        }
        PendingAgent pending = entry.pending();
        AgentResultMessage result = entry.result();
        if (result.correlationId() == null || !result.correlationId().equals(pending.correlationId())) {
            logger.warn("[AgentAsyncCompletion] In-flight replay correlation mismatch: pending={}, result={}",
                pending.correlationId(), result.correlationId());
            return false;
        }

        logger.info("[AgentAsyncCompletion] Replaying in-flight agent result: correlationId={}, runId={}, nodeId={}, agentType={}, success={}",
            result.correlationId(), pending.runId(), pending.nodeId(), pending.agentType(), result.success());

        return deliverConsumedPending(pending, result);
    }

    private boolean deliverConsumedPending(PendingAgent pending, AgentResultMessage result) {
        // Bind organizationId on the thread BEFORE running the delivery pipeline.
        // This method is invoked from two callsites that do NOT carry HTTP request
        // context: (a) AgentResultSubscriber.onMessage running on a Spring Data Redis
        // redisMessageListenerContainer-N thread, (b) AgentRecoveryService scheduled
        // scan on its own executor. Both threads have ZERO TenantResolver binding,
        // so any OrgScopedEntity persist downstream (StorageService.saveJsonWithContext,
        // step_data, etc.) would trip the @PrePersist fail-loud listener post-V263.
        // PendingAgent captures organizationId at AgentNode-yield time, so we have a
        // reliable orgId to thread through. Prod incident 2026-05-20 16:56 UTC: 224/270
        // workflow_step_data rows on epoch 13 landed with output_storage_id=NULL
        // because this wrap was missing; downstream {{...output.text}} templates
        // resolved to null and Telegram received {"text": null}.
        String orgId = pending.organizationId();
        // Site B (post-2026-05-22 OOM fix): clear the in-flight stage in a finally block
        // around deliverUnderLock so BOTH the happy path AND the catch path inside
        // deliverUnderLock (which re-registers the pending into the queue for retry) clear
        // the stage. Leaving a stale in_flight twin while the registry also holds the entry
        // would cause double-delivery on the next AgentRecoveryService startup/scan tick.
        try {
            if (orgId == null || orgId.isBlank()) {
                logger.warn("[AgentAsyncCompletion] PendingAgent missing organizationId for correlationId={}, runId={} - proceeding without org scope (fail-loud listener will reject org-scoped writes)",
                    result.correlationId(), pending.runId());
                synchronized (lockFor(pending.runId())) {
                    return deliverUnderLock(pending, result);
                }
            }
            boolean[] outcome = { false };
            com.apimarketplace.common.web.TenantResolver.runWithOrgScope(orgId, () -> {
                synchronized (lockFor(pending.runId())) {
                    outcome[0] = deliverUnderLock(pending, result);
                }
            });
            return outcome[0];
        } finally {
            if (inFlightStore != null) {
                inFlightStore.clear(result.correlationId());
            }
        }
    }

    /**
     * Run the persistence + coalesce + traversal pipeline for one consumed
     * {@link PendingAgent}. Called with the per-run lock held so concurrent deliveries
     * for the same run serialize here.
     */
    private boolean deliverUnderLock(PendingAgent pending, AgentResultMessage result) {
        String runId = pending.runId();
        String nodeId = pending.nodeId();

        try {
            // 1. Restore split context so the persistence pipeline sees the same per-item
            //    context the inline path would have seen.
            restoreSplitContextIfAny(pending);

            // 2. Persist the assistant message + tool results to the agent's conversation
            //    and emit stream_completed BEFORE any DB-dependent work (rebuildExecution,
            //    persistStepResult). This is the async counterpart to AgentNode.executeAgent
            //    ~lines 899-901 which runs saveAssistantResponse before the engine moves on.
            //    Putting it first has a concrete user-visible benefit: if rebuild or step
            //    persistence throws (transient DB hiccup), the conversation panel still
            //    receives stream_completed/stream_error so the typing indicator stops and
            //    the user sees a definitive state - instead of being stuck on "..."
            //    indefinitely while re-registration retries. The conversation save itself
            //    targets a different DB row than the workflow run, so a workflow-side DB
            //    failure shouldn't block the user-visible message.
            //    Best-effort: a save failure is logged and swallowed so it never breaks
            //    the rest of the delivery pipeline (matches the inline path's contract).
            //
            //    Idempotency note: the registry consumed `pending` in onAgentResult before
            //    we got here, so the happy-path can't re-deliver the same correlationId.
            //    The recovery scanner can theoretically re-deliver after a process crash,
            //    in which case saveAssistantResponse runs twice and produces a duplicate
            //    assistant row. That's an accepted trade-off - the alternative (lose the
            //    message entirely on crash) is worse for the user.
            persistConversationOnDelivery(pending, result);

            // 3. Reconstruct the WorkflowExecution from DB. The engine has already yielded
            //    so the original in-memory execution is gone - this is the same reconstruction
            //    path WorkflowResumeService uses for ordinary resume.
            //    rebuildLoadedExecution returns both tree + execution from a single
            //    reconstructState call, letting executeReadyNodesLoop pass the preloaded
            //    wrapper down so successor executeNode / getReadyNodes calls skip their
            //    inner reconstructState (the saving compounds across split-async bursts).
            com.apimarketplace.orchestrator.execution.v2.cache.ExecutionCacheManager.LoadedExecution loaded =
                rebuildLoadedExecution(runId);
            if (loaded == null || loaded.execution() == null) {
                // Transient DB hiccup: re-register so the recovery scanner can retry.
                // Conversation message is already saved (step 2) so the user isn't stuck.
                logger.warn("[AgentAsyncCompletion] Could not rebuild execution for runId={}, re-registering for retry: correlationId={}",
                    runId, pending.correlationId());
                registry.register(pending);
                return false;
            }
            WorkflowExecution execution = loaded.execution();

            // 3a. Persist step result via the SAME pipeline as inline execution
            //     (enrichAgentFields, selectedBranch derivation, everything) - no
            //     parallel logic.
            StepExecutionResult stepResult = buildStepResult(execution, pending, result);
            persistStepResult(execution, pending, stepResult);

            // 3b. Decrement the running count in RunningNodeTracker under the
            // per-epoch Redis key (P2.3.1). The async path bypasses
            // NodeCompletionService.emitNodeComplete (which calls markCompleted
            // in the inline path), so we must do it explicitly here. For split-async
            // nodes, SplitAwareNodeExecutor set the count to N; each completion decrements
            // by 1, so the frontend sees real-time progress (N → N-1 → ... → 0).
            // The epoch comes from the pending registry entry, NOT the run's
            // "current" epoch - a parallel earlier epoch may own this completion.
            runningNodeTracker.markCompleted(runId, pending.epoch(), pending.nodeId());

            // 3c. Record agent_executions row for the metrics dashboard. This is the
            //     async counterpart to AgentNode.executeAgent/executeClassify/executeGuardrail's
            //     inline agentClient.recordObservability call. Without this, enabling
            //     scaling.agent.queue.enabled=true silently drops every row from the
            //     agent_executions table and the frontend "Agent Performance" panel goes
            //     blank (regression observed on scaling/horizontal-scaling 2026-04-12).
            recordAsyncObservability(execution, pending, result, stepResult);

            // 4. Cancellation guard - check AFTER persistence + observability (steps 3/3a/3b)
            //    so that already-completed work is recorded and metrics are not lost, but
            //    BEFORE successor traversal so cancelled runs don't spawn new node executions.
            //    Without this, a late-arriving async result on a CANCELLED run would drive
            //    successor nodes and potentially un-cancel via triggerDeferredResetIfDrained.
            //    Delegates to RunCancellationGuard so WAITING_TRIGGER (the normal between-fires
            //    state for reusable triggers) is correctly distinguished from a stopWorkflow stop.
            if (isRunStoppedOrTerminal(runId)) {
                logger.info("[AgentAsyncCompletion] Run {} is terminal/stopped - persisted result but skipping successor traversal: correlationId={}, nodeId={}",
                    runId, pending.correlationId(), nodeId);
                return true;
            }

            // 5. Drive successor traversal - split-coalesced if the agent was inside a
            //    split, immediate otherwise. Split path defers edge/snapshot emission
            //    until the barrier seals so we emit once per split, not per item.
            if (isSplitAgent(pending)) {
                return deliverSplitItem(pending, result, stepResult, loaded);
            }
            // Non-split path: emit edges + decisionEvaluated + snapshot immediately after
            // persistence so the frontend sees the same stream of events as the inline path.
            NodeExecutionResult nodeResult = toNodeExecutionResult(pending, result, stepResult);
            emitDownstreamEvents(execution, pending, nodeResult);

            // 5a. Continue a loop whose body ends at this async agent. The async completion path
            //     never runs UnifiedExecutionEngine.executeNodeStepByStep, which is where the inline
            //     engine handles the iterate back-edge (the stepNextNodes.isEmpty() && hasBackEdge
            //     block, ~line 1187). Without this, an async agent/classify/guardrail at the tail of
            //     a loop body drops the iterate edge and the loop runs the body exactly once instead
            //     of N times. Success only (a failed agent goes through the cascade below); delegated
            //     to SignalResumeService so the back-edge + snapshot-reset logic stays single-sourced.
            if (result.success() && signalResumeService != null) {
                signalResumeService.advanceLoopBackEdgeForAsyncCompletedNode(
                    runId, pending.itemId(), pending.nodeId(), pending.itemIndex(),
                    pending.epoch(), pending.dagTriggerId(), nodeResult);
            }

            // 5b. Cascade SKIPPED to descendants when the async agent failed.
            //
            // Mirrors UnifiedExecutionEngine.traverseTree post-FAIL block - both engines
            // converge on V2SkipPropagationService.cascadeFailureToSuccessors. Without
            // this, async failures only mark the direct outgoing edges (via
            // emitDownstreamEvents → EdgeStatusEmitter) and leave descendants invisible:
            // no workflow_step_data rows, EpochState.skippedNodeIds untouched, downstream
            // Merge nodes hang because MergeNodeAnalyzer reads the empty Set.
            //
            // Ordering is load-bearing:
            //   - AFTER emitDownstreamEvents: emitDownstreamEvents → EdgeStatusEmitter only
            //     marks the direct outgoing edges of the failed node, no recursion (verified
            //     EdgeStatusEmitter:264-280). Cascade adds descendant rows + own edges; no
            //     third source of duplicates.
            //   - BEFORE executeReadyNodesLoop: ReadyNodeCalculator reads
            //     EpochState.skippedNodeIds via MergeNodeAnalyzer to walk merges; running it
            //     on stale state would mis-compute readiness.
            //   - BEFORE triggerDeferredResetIfDrained: deferred reset flips epoch and
            //     resetForNextCycle could zero the skippedNodeIds we just wrote.
            //
            // Wrapped in try/catch so a cascade failure never strands RunningNodeTracker,
            // executeReadyNodesLoop or triggerDeferredResetIfDrained - the FAILED row was
            // already persisted at step 3a and the epoch will drain naturally. The outer
            // try/catch at line ~370 would re-register the pending agent for retry, which
            // is the wrong recovery for a cascade-only failure (FAILED row already exists,
            // re-delivery would just retry the cascade).
            if (!result.success() && skipPropagationService != null) {
                try {
                    ExecutionNode failedNode = lookupNode(runId, pending.nodeId());
                    if (failedNode != null) {
                        skipPropagationService.cascadeFailureToSuccessors(
                            execution, failedNode, pending.itemIndex(),
                            pending.epoch(), pending.dagTriggerId(),
                            /*perItemScope=*/ false,
                            com.apimarketplace.orchestrator.execution.v2.services.V2SkipPropagationService.SOURCE_ASYNC);
                    }
                } catch (Exception cascadeEx) {
                    logger.error("[AgentAsyncCompletion] Cascade failed for runId={}, nodeId={}, correlationId={}: {}",
                        runId, pending.nodeId(), pending.correlationId(), cascadeEx.getMessage(), cascadeEx);
                }
            }

            executeReadyNodesLoop(runId, pending.itemId(), pending.dagTriggerId(), pending.epoch(), Set.of(), loaded);
            // 6. If this was the last pending agent for (runId, dagTriggerId, epoch),
            //    close the cycle - equivalent to the "last signal resolved" path in the
            //    signal subsystem. Without this, ReusableTriggerService never gets notified
            //    that the async I/O is done and the run stays RUNNING forever.
            triggerDeferredResetIfDrained(runId, pending.dagTriggerId(), pending.epoch());
            logger.info("[AgentAsyncCompletion] Completed async agent: correlationId={}, runId={}, nodeId={}",
                pending.correlationId(), runId, nodeId);
            return true;

        } catch (Exception e) {
            logger.error("[AgentAsyncCompletion] Failed to deliver async agent result: correlationId={}, runId={}, nodeId={}, error={} - re-registering for retry",
                pending.correlationId(), runId, nodeId, e.getMessage(), e);
            // The entry was already consumed - without re-registration the recovery scanner
            // cannot find it and the run is stranded. register() refreshes the TTL.
            try {
                registry.register(pending);
            } catch (Exception ignored) {
                logger.warn("[AgentAsyncCompletion] Re-registration failed during error handling: correlationId={}",
                    pending.correlationId());
            }
            return false;
        }
    }

    private static boolean isSplitAgent(PendingAgent pending) {
        return pending.splitItemData() != null && !pending.splitItemData().isEmpty();
    }

    private void restoreSplitContextIfAny(PendingAgent pending) {
        if (isSplitAgent(pending)) {
            splitContextManager.restoreContext(pending.runId(), pending.nodeId(), pending.splitItemData());
        }
    }

    private void persistStepResult(WorkflowExecution execution, PendingAgent pending, StepExecutionResult stepResult) {
        // Phase 2.E (2026-04-29): for split-async items, suppress the global EpochState
        // mark so the first per-item failure doesn't poison failedNodeIds for the whole
        // node. The aggregate global status is written ONCE at barrier seal via
        // recordSplitAggregateIfMissing.
        boolean suppressGlobal = isSplitAgent(pending);
        com.apimarketplace.orchestrator.services.completion.StepCompletionContext ctx =
            new com.apimarketplace.orchestrator.services.completion.StepCompletionContext(
                execution,
                pending.nodeId(),
                pending.nodeLabel(),
                stepResult,
                pending.itemIndex(),
                // Loop iteration the body ran at (0 outside a loop / first entry) - records each
                // loop iteration's step at a distinct iteration so per-iteration step_data doesn't
                // overwrite at iter=0, which under-counted async loop-body executions.
                pending.loopIteration() != null ? pending.loopIteration() : 0,
                null,
                pending.epoch(),
                suppressGlobal);
        stepCompletionOrchestrator.complete(ctx, pending.dagTriggerId());
    }

    /**
     * Emit the post-persistence downstream events (edges, decisionEvaluated, snapshot)
     * that the inline path does inside {@code V2ExecutionEventService.emitNodeComplete}.
     *
     * <p>{@link StepCompletionOrchestrator#completeStep} stops at DB persist + NodeCounts
     * + step event - it does not know about edges or the execution tree. Without this
     * tail, async classify/guardrail completions silently skipped edge statusCounts
     * and snapshot pushes (symptom: "streams and edges disappeared" after enabling
     * async in split context).</p>
     *
     * <p>Isolated in try/catch so a tree-lookup hiccup (e.g., transient DB error on the
     * cached tree load) never strands the run - the core state mutation was already
     * done by {@link #persistStepResult}; the missing downstream is merely a degraded
     * UX that the next emission will heal.</p>
     */
    private void emitDownstreamEvents(
            WorkflowExecution execution, PendingAgent pending, NodeExecutionResult result) {
        if (v2ExecutionEventService == null) {
            logger.debug("[AgentAsyncCompletion] Downstream emission skipped - wiring incomplete (test fixture?)");
            return;
        }
        try {
            ExecutionNode node = lookupNode(pending.runId(), pending.nodeId());
            if (node == null) {
                return; // lookupNode already logged the reason
            }
            v2ExecutionEventService.emitPostPersistenceCompletion(
                execution, node, result, pending.itemIndex(), null,
                pending.epoch(), pending.dagTriggerId());
        } catch (Exception e) {
            logger.warn("[AgentAsyncCompletion] Downstream emission failed: runId={}, nodeId={}, error={}",
                pending.runId(), pending.nodeId(), e.getMessage(), e);
        }
    }

    /**
     * Resolve an {@link ExecutionNode} for {@code nodeId} from the cached
     * {@link ExecutionTree} of {@code runId}. Returns {@code null} if the wiring is
     * incomplete (test fixture), the tree cannot be loaded, or the node is not found -
     * each null return path is logged so callers can early-return without re-logging.
     *
     * <p>Single source of truth for node lookup in this service. Used by
     * {@link #emitDownstreamEvents}, {@link #emitDownstreamBatchEvents}, the non-split
     * cascade call site in {@link #deliverUnderLock}, and {@link #traverseSuccessorsPerItem}.
     * (The split cascade call site reuses the local {@code node} variable already resolved
     * inside {@code traverseSuccessorsPerItem} - no redundant lookup per failed item.)
     */
    private ExecutionNode lookupNode(String runId, String nodeId) {
        if (v2StepByStepContextManager == null) {
            logger.debug("[AgentAsyncCompletion] lookupNode skipped - wiring incomplete (test fixture?): runId={}, nodeId={}",
                runId, nodeId);
            return null;
        }
        ExecutionTree tree = v2StepByStepContextManager.getTree(runId);
        if (tree == null) {
            logger.warn("[AgentAsyncCompletion] lookupNode: could not load tree: runId={}, nodeId={}",
                runId, nodeId);
            return null;
        }
        ExecutionNode node = nodeSearchService.findNodeFromAllRoots(tree, nodeId);
        if (node == null) {
            logger.warn("[AgentAsyncCompletion] lookupNode: node not found in tree: runId={}, nodeId={}",
                runId, nodeId);
            return null;
        }
        return node;
    }

    /**
     * Variant of {@link #emitDownstreamEvents} that iterates a sealed split batch so
     * each per-item result contributes to the edge statusCounts and decisionEvaluated
     * stream. Needed because the inline path emits per-item edges inside
     * {@code SplitAwareNodeExecutor.persistItemResult}'s loop, and a single-item
     * emission after the barrier seal would under-count the remaining N-1 items.
     */
    private void emitDownstreamBatchEvents(
            WorkflowExecution execution, PendingAgent pending, List<IndexedNodeResult> batch) {
        if (v2ExecutionEventService == null) {
            logger.debug("[AgentAsyncCompletion] Downstream batch emission skipped - wiring incomplete (test fixture?)");
            return;
        }
        try {
            ExecutionNode node = lookupNode(pending.runId(), pending.nodeId());
            if (node == null) {
                return; // lookupNode already logged the reason
            }
            v2ExecutionEventService.emitPostPersistenceCompletionForSplitBatch(
                execution, node, batch, pending.epoch(), pending.dagTriggerId());
        } catch (Exception e) {
            logger.warn("[AgentAsyncCompletion] Batch downstream emission failed: runId={}, nodeId={}, error={}",
                pending.runId(), pending.nodeId(), e.getMessage(), e);
        }
    }

    /**
     * Handle one per-item arrival of a split-scoped async agent: register the barrier
     * (idempotent - steady-state path already registered it; recovery path pre-registered
     * in {@link AgentRecoveryService#preRegisterSplitBarriers}), record the arrival, and
     * on seal, store the batch in {@link SplitContextManager} and drive successor
     * traversal.
     *
     * @return {@code true} always - the result WAS matched and persisted regardless of
     *         whether the barrier has sealed yet
     */
    private boolean deliverSplitItem(PendingAgent pending, AgentResultMessage result, StepExecutionResult stepResult,
                                      com.apimarketplace.orchestrator.execution.v2.cache.ExecutionCacheManager.LoadedExecution preloaded) {
        String runId = pending.runId();
        String nodeId = pending.nodeId();

        // Safety net for the test path and partial-restart edge: recovery pre-registration
        // is the authoritative sizing, but we also re-register defensively so single-agent
        // tests that don't run recovery still work.
        ensureBarrierRegistered(runId, nodeId, pending);

        Optional<List<IndexedNodeResult>> sealedBatch = splitCoalesceTracker.arrive(
            runId, nodeId, pending.epoch(), pending.itemIndex(),
            toNodeExecutionResult(pending, result, stepResult));

        if (sealedBatch.isEmpty()) {
            logger.info("[AgentAsyncCompletion] Split item arrived, awaiting siblings: correlationId={}, runId={}, nodeId={}, itemIndex={}",
                result.correlationId(), runId, nodeId, pending.itemIndex());
            return true;
        }

        List<IndexedNodeResult> batch = sealedBatch.get();
        logger.info("[AgentAsyncCompletion] Split barrier sealed, traversing successors: runId={}, nodeId={}, totalItems={}",
            runId, nodeId, batch.size());
        storeSplitBatchInContext(runId, nodeId, pending, batch);
        // Emit per-item edges + decisionEvaluated once the whole batch has landed. The
        // inline split path (SplitAwareNodeExecutor.persistItemResult) iterates items
        // inside its loop; we mirror that by iterating the sealed batch inside a single
        // edge-batch scope so all N items contribute to the same coalesced downstream
        // update. Without per-item iteration the frontend saw only 1 edge event (the
        // last arrival's) and split-aware branching (pass/fail / category_N) looked
        // collapsed to a single routing decision.
        // Reuse the wrapper that deliverUnderLock just loaded - its tree+plan are
        // immutable and downstream consumers (traverseSuccessorsPerItem,
        // executeReadyNodesLoop) read state via stateSnapshotService rather than the
        // wrapper's captured runState, so the slightly-stale wrapper is safe and
        // halves reconstructState calls on the split-async path.
        com.apimarketplace.orchestrator.execution.v2.cache.ExecutionCacheManager.LoadedExecution loaded =
            (preloaded != null) ? preloaded : rebuildLoadedExecution(runId);
        WorkflowExecution execution = loaded != null ? loaded.execution() : null;
        if (execution != null) {
            emitDownstreamBatchEvents(execution, pending, batch);
        }

        // Phase 2.E (2026-04-29 prod-incident fix): write the global node status ONCE
        // at barrier seal based on aggregate per-item rows. Per-item completions
        // suppressed the global mark via StepCompletionContext.suppressGlobalMark.
        // Idempotent - safe to re-call from recovery scanner.
        stepCompletionOrchestrator.recordSplitAggregateIfMissing(
            runId, pending.dagTriggerId(), pending.nodeId(), pending.epoch());

        // Phase 1 (2026-04-29 prod-incident fix): per-item successor traversal at
        // barrier seal. Mirrors SplitAwareNodeExecutor.executeForAllItemsAndTraverse:535-543.
        // Routes EACH successful item to its correct port (e.g. classify category_<index>)
        // independently of the global readiness walker - which would otherwise miss 5-of-6
        // categories when classify's getNextNodes returns ONE port from the singleton
        // "last item's selected_category" in the buildNodeExecutionResult global summary.
        Set<String> dispatched = Set.of();
        if (execution != null && perItemTraversalEnabled) {
            dispatched = traverseSuccessorsPerItem(execution, pending, batch, loaded);
        }

        // After barrier seal we drive the split's successor traversal at the OUTER
        // workflow itemIndex where the split executed - NOT at pending.itemId() which
        // is whichever sub-item happened to arrive last (e.g. "1"). The inline path
        // invokes SplitAwareNodeExecutor.execute(successor, ..., workflowItemIndex=0)
        // which then iterates all N sub-items via findSplitContextWithFallback keyed
        // at workflowItemIndex. Passing the sub-item itemId instead causes parseItemIndex
        // to return that sub-item index; SplitAwareNodeExecutor looks up the split
        // context at the wrong workflowItemIndex, doesn't find one, and degenerates to
        // a single non-split execution - the classify/downstream runs exactly ONCE
        // instead of N times, edge counts under-report, and the fan-out is lost.
        // The parent workflowItemIndex was stashed in splitItemData by AgentNode when
        // it yielded (see AgentNode.java:603-609).
        String parentItemId = resolveParentItemIdForSplitSuccessor(pending);
        executeReadyNodesLoop(runId, parentItemId, pending.dagTriggerId(), pending.epoch(), dispatched, loaded);
        // Split barrier seal is the last item for this (runId, dagTriggerId, epoch) -
        // propagate the cycle reset the same way the non-split path does.
        triggerDeferredResetIfDrained(runId, pending.dagTriggerId(), pending.epoch());
        return true;
    }

    /**
     * Phase 1 - Per-item successor dispatch at split-async barrier seal.
     *
     * <p>Mirrors {@code SplitAwareNodeExecutor.executeForAllItemsAndTraverse:535-543}:
     * for each successful item, traverse {@code node.getNextNodes(itemResult)}. De-duplicates
     * distinct successor IDs via {@code LinkedHashSet} - {@code SplitAwareNodeExecutor.execute}
     * on the downstream side iterates per-item via {@code SplitContextManager} +
     * {@code getRoutedItemIndices}, so dispatching once per distinct ID is sufficient.
     *
     * <p>Returns the set of dispatched successor IDs so the subsequent
     * {@code executeReadyNodesLoop} can skip them.
     *
     * <p>The successor is dispatched once at the parent item scope. The downstream
     * {@code SplitAwareNodeExecutor} rehydrates the sealed split batch from
     * {@code SplitContextManager}, computes routed item indices from per-item outputs,
     * and injects each predecessor result while iterating sub-items. Unit coverage in
     * {@code AgentAsyncCompletionPerItemTraversalTest} pins the parent itemId and
     * distinct-successor dispatch contract.
     */
    private Set<String> traverseSuccessorsPerItem(WorkflowExecution execution, PendingAgent pending,
                                                  List<IndexedNodeResult> batch,
                                                  com.apimarketplace.orchestrator.execution.v2.cache.ExecutionCacheManager.LoadedExecution preloaded) {
        String runId = pending.runId();
        String nodeId = pending.nodeId();

        ExecutionNode node = lookupNode(runId, nodeId);
        if (node == null) {
            // lookupNode already logged the reason (wiring/tree/node-not-found)
            return Set.of();
        }

        Set<String> distinctSuccessors = new LinkedHashSet<>();
        // Merge/join successors (>1 predecessor branch) are exempted from the terminal filter
        // below: each predecessor branch's seal legitimately contributes a DISJOINT item subset,
        // so a merge is node-level terminal after the first branch yet must still run for the
        // remaining branches. Node-level status cannot tell those apart, so filtering it would
        // lose the sibling branches' items. Proper cross-pod merge-join-once coordination is a
        // separate feature (a distributed merge barrier); until then we preserve the pre-existing
        // behavior for merges (no data loss) and keep the recovery-replay dedup only for the
        // non-merge side-effecting successors it was written for.
        Set<String> mergeSuccessors = new HashSet<>();
        int success = 0;
        int fail = 0;
        for (IndexedNodeResult item : batch) {
            NodeExecutionResult itemResult = item.result();
            if (itemResult == null) continue;
            if (!itemResult.isSuccess()) {
                fail++;
                // Per-item cascade for the failed item - same routine as non-split,
                // but perItemScope=true so EpochState/global stepResult are not touched
                // (other items in the split may still execute their correctly-routed
                // branches). Reuses the already-resolved `node` local - no redundant
                // lookupNode per failed item. Wrapped so a per-item cascade failure
                // doesn't strand the remaining successful items' traversal.
                if (skipPropagationService != null) {
                    try {
                        skipPropagationService.cascadeFailureToSuccessors(
                            execution, node, item.itemIndex(),
                            pending.epoch(), pending.dagTriggerId(),
                            /*perItemScope=*/ true,
                            com.apimarketplace.orchestrator.execution.v2.services.V2SkipPropagationService.SOURCE_ASYNC);
                    } catch (Exception cascadeEx) {
                        logger.error("[AgentAsyncCompletion] Per-item cascade failed for runId={}, nodeId={}, itemIndex={}: {}",
                            runId, nodeId, item.itemIndex(), cascadeEx.getMessage(), cascadeEx);
                    }
                }
                continue;
            }
            success++;
            for (ExecutionNode successor : node.getNextNodes(itemResult)) {
                distinctSuccessors.add(successor.getNodeId());
                if (successor.isImplicitMerge() || successor.isMergeNode()) {
                    mergeSuccessors.add(successor.getNodeId());
                }
            }
        }
        if (distinctSuccessors.isEmpty()) {
            logger.info("[AgentAsyncCompletion] Per-item traversal - no successful items: runId={}, nodeId={}, total={}, success={}, fail={}",
                runId, nodeId, batch.size(), success, fail);
            return Set.of();
        }
        // Drop NON-merge successors already terminal in this epoch before re-dispatching. The
        // barrier deletes its Redis key on first seal and is re-registered on a recovery replay,
        // so a crash-restart re-enters here for the same (runId, nodeId, epoch); without this
        // filter an already-FAILED side-effecting successor is re-executed and its outbound call
        // (email / HTTP) duplicated. Merge successors are EXEMPT (see mergeSuccessors above) so a
        // disjoint-branch rejoin is never dropped. We return the FILTERED set as `dispatched`: the
        // subsequent executeReadyNodesLoop derives its ready set via ReadyNodeCalculator, which
        // already excludes terminal nodes, so a dropped successor is absent from that sweep
        // regardless - returning it unfiltered would only over-report what was dispatched.
        // Loop/back-edge re-runs do not flow through this method, so they are unaffected.
        distinctSuccessors = filterOutTerminalSuccessors(
            distinctSuccessors, mergeSuccessors, runId, pending.dagTriggerId(), pending.epoch());
        if (distinctSuccessors.isEmpty()) {
            logger.info("[AgentAsyncCompletion] Per-item traversal - all successors already terminal (prior delivery ran them), skipping re-dispatch: runId={}, nodeId={}",
                runId, nodeId);
            return Set.of();
        }
        logger.info("[AgentAsyncCompletion] Per-item traversal: runId={}, nodeId={}, total={}, success={}, fail={}, distinctSuccessors={}",
            runId, nodeId, batch.size(), success, fail, distinctSuccessors);

        String parentItemId = resolveParentItemIdForSplitSuccessor(pending);
        String dagTriggerId = pending.dagTriggerId();
        int epoch = pending.epoch();
        // Share the caller's preloaded LoadedExecution with the FIRST successor only.
        // After that successor persists its row, the wrapper's runState is stale and
        // subsequent calls must re-fetch (engine reads via stateSnapshotService anyway,
        // so the wrapper carries only the immutable tree+plan).
        com.apimarketplace.orchestrator.execution.v2.cache.ExecutionCacheManager.LoadedExecution
            shareable = preloaded;
        for (String successorId : distinctSuccessors) {
            try {
                if (dagTriggerId != null) {
                    if (shareable != null) {
                        v2StepByStepService.executeNode(
                            runId, successorId, parentItemId, epoch, dagTriggerId, shareable);
                    } else {
                        v2StepByStepService.executeNode(
                            runId, successorId, parentItemId, epoch, dagTriggerId);
                    }
                } else {
                    v2StepByStepService.executeNode(runId, successorId, parentItemId, epoch);
                }
                shareable = null;
            } catch (Exception e) {
                logger.warn("[AgentAsyncCompletion] Per-item successor failed (continuing siblings): runId={}, successor={}, error={}",
                    runId, successorId, e.getMessage());
            }
        }
        return distinctSuccessors;
    }

    /**
     * Remove successors that already reached a terminal status (COMPLETED/FAILED/SKIPPED) in this
     * epoch before re-dispatching at barrier seal. Epoch-scoped via the split's DAG trigger
     * ({@code dagTriggerId}, {@code epoch >= 0}); flat union only when {@code dagTriggerId} is null
     * (gating handled inside {@link com.apimarketplace.orchestrator.domain.execution.StateSnapshot#getTerminalNodeIds}).
     * Fails open (returns the input unfiltered) when the snapshot service is unavailable or the read
     * throws, so a transient snapshot error never strands the successor traversal. This is the
     * async-path twin of {@code SignalResumeService.filterOutAlreadyTerminalNodes} - keep them aligned.
     *
     * <p>{@code exemptMergeSuccessors} are NEVER dropped: a merge/join successor (>1 predecessor
     * branch) goes node-level terminal after its FIRST predecessor branch seals, yet must still
     * run for the remaining branches (each contributing a disjoint item subset). Node-level status
     * cannot distinguish "already ran for THIS branch" from "ran for a SIBLING branch", so dropping
     * it would silently lose the siblings' items. Exempting merges restores the pre-existing
     * (no-data-loss) behavior for them; the duplicate-once-per-seal that the exemption leaves in
     * place for async-fed merges is a separate, pre-existing defect whose correct cure is a
     * distributed merge-join barrier (tracked feature), not this node-level dedup. Note the
     * exemption is deliberately broad: {@code isImplicitMerge()} is ANY node with more than one
     * predecessor, so a multi-incoming side-effecting node is exempt too and shares that same
     * pre-existing residual duplicate - accepted here only because the alternative (dropping it)
     * is silent data loss, which is strictly worse.
     */
    private Set<String> filterOutTerminalSuccessors(Set<String> successors, Set<String> exemptMergeSuccessors,
                                                     String runId, String dagTriggerId, int epoch) {
        if (successors == null || successors.isEmpty() || stateSnapshotService == null) {
            return successors == null ? Set.of() : successors;
        }
        Set<String> exempt = exemptMergeSuccessors == null ? Set.of() : exemptMergeSuccessors;
        try {
            com.apimarketplace.orchestrator.domain.execution.StateSnapshot snapshot =
                stateSnapshotService.getSnapshot(runId);
            Set<String> terminal = snapshot.getTerminalNodeIds(dagTriggerId, epoch);
            Set<String> filtered = new LinkedHashSet<>(successors);
            if (filtered.removeIf(id -> terminal.contains(id) && !exempt.contains(id))) {
                logger.info("[AgentAsyncCompletion] Skipping already-terminal non-merge split successors (prior delivery ran them): runId={}, epoch={}, remaining={}",
                    runId, epoch, filtered);
            }
            return filtered;
        } catch (Exception e) {
            logger.warn("[AgentAsyncCompletion] Could not filter terminal successors (proceeding unfiltered): runId={}, error={}",
                runId, e.getMessage());
            return successors;
        }
    }

    /**
     * Trigger {@link SignalResumeService#performDeferredReset} only when no more async
     * agents are in flight for the given (runId, dagTriggerId, epoch) AND no blocking
     * signal exists either. This is the async-path counterpart of the last-signal-resolved
     * check inside {@code SignalResumeService} - it ensures the epoch is closed exactly
     * once, regardless of which subsystem (signals or async agents) drained last.
     *
     * <p>Called after successor traversal so split siblings have been persisted and had
     * their ready-node sweep. If another async agent is still pending for this epoch
     * (parallel split items whose results arrive interleaved), we skip: that agent's own
     * completion will re-check and trigger the reset.</p>
     *
     * <p>The {@code dagTriggerId == null} case is a defensive guard: older test fixtures
     * may leave it unset, and the legacy engine-wide reset path does not apply to the
     * per-DAG scoping we need here.</p>
     */
    private void triggerDeferredResetIfDrained(String runId, String dagTriggerId, int epoch) {
        if (dagTriggerId == null) {
            logger.debug("[AgentAsyncCompletion] Skipping deferred reset - dagTriggerId unset: runId={}", runId);
            return;
        }
        if (registry.hasPendingFor(runId, dagTriggerId, epoch)) {
            logger.debug("[AgentAsyncCompletion] Deferred reset not yet - async agents still in flight: runId={}, triggerId={}, epoch={}",
                runId, dagTriggerId, epoch);
            return;
        }
        try {
            logger.info("[AgentAsyncCompletion] Last async agent delivered - performing deferred cycle reset: runId={}, triggerId={}, epoch={}",
                runId, dagTriggerId, epoch);
            signalResumeService.performDeferredReset(runId, dagTriggerId, epoch);
        } catch (Exception e) {
            logger.error("[AgentAsyncCompletion] Deferred reset failed: runId={}, triggerId={}, epoch={}, error={}",
                runId, dagTriggerId, epoch, e.getMessage(), e);
        }
    }

    /**
     * Ensure the {@link SplitCoalesceTracker} barrier is registered for this split
     * item's (runId, nodeId, epoch) triple before calling {@code arrive}.
     *
     * <p>On the steady-state path, {@code SplitAwareNodeExecutor} already registered
     * the barrier when it launched the split. After a crash/restart the in-memory
     * tracker state is gone, so the first recovered per-item result would otherwise
     * hit an unregistered key and race downstream with only 1-of-N items persisted.
     *
     * <p>The authoritative total comes from {@code pending.splitItemData().items.size()}
     * - {@code AgentNode} snapshotted the full items list when it yielded, so we can
     * rebuild the barrier without touching {@code SplitContextManager}. {@code register}
     * is idempotent, so this is a no-op when the barrier already exists.
     *
     * <p>Package-private for direct unit testing - the surrounding onAgentResult method
     * has a tangle of lazy-wired dependencies that make a focused behavioral test on
     * this single recovery concern significantly cheaper than a full end-to-end mock.
     */
    @SuppressWarnings("unchecked")
    void ensureBarrierRegistered(String runId, String nodeId, PendingAgent pending) {
        if (splitCoalesceTracker.isRegistered(runId, nodeId, pending.epoch())) {
            return;
        }
        Map<String, Object> splitData = pending.splitItemData();
        Object itemsObj = splitData.get("items");
        if (!(itemsObj instanceof List<?> items) || items.isEmpty()) {
            logger.warn("[AgentAsyncCompletion] Cannot re-register split barrier - items missing from pending.splitItemData: correlationId={}, runId={}, nodeId={}",
                pending.correlationId(), runId, nodeId);
            return;
        }
        int totalItems = items.size();
        logger.info("[AgentAsyncCompletion] Re-registering split barrier (recovery path): runId={}, nodeId={}, epoch={}, totalItems={}",
            runId, nodeId, pending.epoch(), totalItems);
        splitCoalesceTracker.register(runId, nodeId, pending.epoch(), totalItems);
    }

    /**
     * After a split barrier seals, the successor traversal must run at the OUTER
     * workflow itemIndex where the split lives - not at the sub-item itemIndex of
     * whichever per-item result arrived last. Mirrors the inline split path where
     * SplitAwareNodeExecutor.execute is called with {@code workflowItemIndex} = the
     * parent scope.
     *
     * <p>Resolution order:</p>
     * <ol>
     *   <li>{@code splitItemData.workflowItemIndex} - the authoritative outer index,
     *       stamped by {@code AgentNode} when it yielded (see
     *       {@code AgentNode.java:603-609}). Always present for top-level splits and
     *       for nested splits whose scoped key parsed cleanly.</li>
     *   <li>Strip the trailing segment of {@code pending.itemId()} - {@code "0.3"} →
     *       {@code "0"}. Used as a secondary fallback for legacy PendingAgent entries
     *       from before workflowItemIndex was stamped.</li>
     *   <li>{@code "0"} - single-item trigger default when nothing else is available.</li>
     * </ol>
     */
    private String resolveParentItemIdForSplitSuccessor(PendingAgent pending) {
        Map<String, Object> splitData = pending.splitItemData();
        if (splitData != null) {
            Object idx = splitData.get("workflowItemIndex");
            if (idx instanceof Number n) {
                return String.valueOf(n.intValue());
            }
        }
        String subItemId = pending.itemId();
        if (subItemId != null) {
            int dot = subItemId.lastIndexOf('.');
            if (dot > 0) {
                return subItemId.substring(0, dot);
            }
            // Single-segment itemId (e.g. "3") from a top-level split: the parent
            // scope is the root workflow item, conventionally "0".
        }
        return "0";
    }

    /**
     * Wrap the worker's payload as a {@link NodeExecutionResult} so the coalesce tracker
     * can hand back an ordered batch of per-item outputs once the barrier seals. The
     * status mirrors the StepExecutionResult so downstream consumers see the same shape
     * as the sync inline path.
     */
    private NodeExecutionResult toNodeExecutionResult(
            PendingAgent pending, AgentResultMessage result, StepExecutionResult stepResult) {
        Map<String, Object> output = stepResult.output() != null
            ? new HashMap<>(stepResult.output())
            : new HashMap<>();
        NodeStatus status = result.success() ? NodeStatus.COMPLETED : NodeStatus.FAILED;
        Optional<String> errorMessage = result.success()
            ? Optional.empty()
            : Optional.ofNullable(result.errorMessage()).or(() -> Optional.of("Async agent execution failed"));
        return new NodeExecutionResult(
            pending.nodeId(),
            status,
            output,
            errorMessage,
            new HashMap<>(),
            stepResult.executionTime()
        );
    }

    /**
     * Stores the sealed per-item batch in {@link SplitContextManager} so subsequent
     * nodes can resolve templates against the agent's per-item outputs (same shape as
     * the inline split path).
     *
     * <p>The split node id is recovered from {@code pending.splitItemData()} which the
     * producing {@code AgentNode} snapshotted before yielding.</p>
     */
    private void storeSplitBatchInContext(
            String runId, String nodeId, PendingAgent pending, List<IndexedNodeResult> batch) {
        Map<String, Object> splitData = pending.splitItemData();
        Object splitNodeIdObj = splitData != null ? splitData.get("splitNodeId") : null;
        if (!(splitNodeIdObj instanceof String splitNodeId) || splitNodeId.isEmpty()) {
            logger.warn("[AgentAsyncCompletion] Cannot store split batch - splitNodeId missing: runId={}, nodeId={}",
                runId, nodeId);
            return;
        }

        Object workflowItemIndexObj = splitData.get("workflowItemIndex");
        int workflowItemIndex = workflowItemIndexObj instanceof Number n ? n.intValue() : 0;

        // SplitContextManager.storeResults is positional: outputs.get(i) is the output
        // for item i. When upstream filtering produces a sparse batch (e.g. items
        // {0, 1, 2, 7} reaching classify out of 15), we must pad the unused slots with
        // null so the list index matches the absolute itemIndex.
        int maxIndex = batch.stream().mapToInt(IndexedNodeResult::itemIndex).max().orElse(-1);
        List<Object> outputs = new ArrayList<>(maxIndex + 1);
        for (int i = 0; i <= maxIndex; i++) {
            outputs.add(null);
        }
        for (IndexedNodeResult indexed : batch) {
            NodeExecutionResult r = indexed.result();
            if (r != null && r.output() != null) {
                outputs.set(indexed.itemIndex(), r.output());
            }
        }

        splitContextManager.storeResults(runId, splitNodeId, workflowItemIndex, nodeId, outputs);
        logger.debug("[AgentAsyncCompletion] Stored {} per-item outputs in split context: runId={}, splitNodeId={}, nodeId={}",
            batch.size(), runId, splitNodeId, nodeId);
    }

    /**
     * Build a {@link StepExecutionResult} from the worker's payload, preserving error
     * details on failure (mirrors {@code NodeCompletionService.convertToStepResult} +
     * the error-meta enrichment in {@code emitNodeComplete}).
     *
     * <p><b>Output normalization:</b> the agent-service worker returns the raw
     * {@code ClassifyResponseDto}/{@code GuardrailResponseDto} JSON as the result
     * payload. Those DTOs do not carry the orchestrator-side fields that
     * {@code StepDataPersistenceService.enrichAgentFields} keys off of to derive
     * {@code selected_branch} ({@code node_type}, {@code selected_category_index},
     * the {@code item_index}/{@code item_id} pair). The inline path adds them in
     * {@code AgentNode.createClassifySuccessResult} / {@code createGuardrailSuccessResult}
     * - we mirror that here so the async path lands the same shape in DB. Without this,
     * per-item {@code selected_branch} is never populated for async classify/guardrail,
     * {@code SplitAwareNodeExecutor.getRoutedItemIndices} queries return 0 rows, and the
     * successor sweep re-enters the empty branch forever - the exact symptom debugged in
     * run {@code *_512defbb} where 5 guardrail items produced 0 routed classify items.
     */
    private StepExecutionResult buildStepResult(WorkflowExecution execution, PendingAgent pending, AgentResultMessage result) {
        long durationMs = 0L;
        if (pending.startedAt() != null && result.completedAt() != null) {
            durationMs = Math.max(0L,
                result.completedAt().toEpochMilli() - pending.startedAt().toEpochMilli());
        }

        Map<String, Object> output = result.result() != null
            ? new HashMap<>(result.result())
            : new HashMap<>();

        injectAgentMetadata(output, execution, pending);

        // Re-inject the resolved input that was snapshotted before the async yield.
        // The worker result only contains the agent output (selected_category, passed,
        // etc.) - without this, StepDataPersistenceService.extractInputData() finds
        // nothing and the inspector "Resolved parameters" panel stays empty.
        if (pending.resolvedInputData() != null && !pending.resolvedInputData().isEmpty()) {
            output.put("resolved_params", pending.resolvedInputData());
        }

        if (result.success()) {
            return StepExecutionResult.success(pending.nodeId(), output, durationMs);
        }

        // Failure: preserve any partial output and stamp error meta on the payload.
        String errorMessage = result.errorMessage() != null
            ? result.errorMessage()
            : "Async agent execution failed";
        output.put("error", errorMessage);
        output.put("status", "error");
        return StepExecutionResult.failureWithOutput(
            pending.nodeId(), errorMessage, output, durationMs);
    }

    /**
     * Mirror the output-shape contract the inline {@code AgentNode.createXxxSuccessResult}
     * methods establish, so {@code enrichAgentFields} on the persistence side keys off the
     * same fields in both paths.
     *
     * <ul>
     *   <li>Always sets {@code node_type} (derived from {@link PendingAgent#agentType()})
     *       and the {@code item_index}/{@code itemIndex}/{@code item_id} trio.</li>
     *   <li>For classify, copies the camelCase DTO field {@code selectedCategory} into the
     *       snake_case {@code selected_category} that {@code enrichAgentFields} reads, and
     *       resolves {@code selected_category_index} from the plan's {@code classifyCategories}
     *       list - the index is what makes {@code selected_branch=category_N} match the port
     *       names {@code SplitAwareNodeExecutor.getRoutedItemIndices} queries.</li>
     *   <li>For guardrail, {@code passed} is already the same name in both the DTO and the
     *       enrichment code, so no renaming is needed - {@code node_type=GUARDRAIL} is
     *       sufficient to trigger the {@code pass}/{@code fail} branch assignment.</li>
     * </ul>
     */
    // Package-private for focused unit tests - the one-shot wrapping logic is small
    // and easier to verify in isolation than through the full onAgentResult graph.
    void injectAgentMetadata(Map<String, Object> output, WorkflowExecution execution, PendingAgent pending) {
        // Item context - the inline path adds these unconditionally.
        output.put("item_index", pending.itemIndex());
        output.put("itemIndex", pending.itemIndex());
        if (pending.itemId() != null) {
            output.put("item_id", pending.itemId());
        }

        // Split context: inject split_item_count so ReadyNodeCalculator detects
        // classify/decision/switch nodes in split context and traverses ALL branch
        // targets instead of only the last item's selected branch.
        // Mirrors SplitAwareNodeExecutor.addItemIndexToResult (sync path).
        if (pending.splitItemData() != null && !pending.splitItemData().isEmpty()) {
            Object itemsObj = pending.splitItemData().get("items");
            if (itemsObj instanceof List<?> items && !items.isEmpty()) {
                output.put("split_item_count", items.size());
            }
        }

        // camelCase → snake_case alias for StepDataPersistenceService.enrichAgentFields,
        // which reads `tokens_used` (not `tokensUsed`). The worker DTO uses the camelCase
        // name, so without this alias the metadata row loses `tokens_used` entirely -
        // silently diverging from the inline path's createClassifySuccessResult /
        // createGuardrailSuccessResult which both write the snake_case key.
        if (output.get("tokens_used") == null && output.get("tokensUsed") != null) {
            output.put("tokens_used", output.get("tokensUsed"));
        }
        if (output.get("totalUsage") instanceof Map<?, ?> usage) {
            Integer prompt = toInt(usage.get("promptTokens"));
            Integer completion = toInt(usage.get("completionTokens"));
            Integer total = toInt(usage.get("totalTokens"));
            if (total == null && (prompt != null || completion != null)) {
                total = (prompt != null ? prompt : 0) + (completion != null ? completion : 0);
            }
            if (output.get("tokens_used") == null && total != null) {
                output.put("tokens_used", total);
            }
            if (output.get("promptTokens") == null && prompt != null) {
                output.put("promptTokens", prompt);
            }
            if (output.get("completionTokens") == null && completion != null) {
                output.put("completionTokens", completion);
            }
        }

        String agentType = pending.agentType();
        if (agentType == null) {
            return;
        }

        switch (agentType.toLowerCase()) {
            case "classify" -> {
                output.put("node_type", "CLASSIFY");
                // camelCase → snake_case alias for enrichAgentFields
                if (output.get("selected_category") == null && output.get("selectedCategory") != null) {
                    output.put("selected_category", output.get("selectedCategory"));
                }
                // Resolve the category index from the plan so enrichAgentFields emits
                // selected_branch=category_N (matching the edge port naming).
                // Mirror AgentNode.createClassifySuccessResult: ALWAYS inject the index,
                // even -1 (no match). Skipping injection on no-match leaves the field
                // absent and diverges from the sync path: enrichAgentFields then falls
                // back to label-as-selectedBranch (e.g. "other"), an opaque value the
                // routing query (selected_branch == "category_N") never matches -
                // the item is lost and the divergence isn't observable from the DB.
                if (output.get("selected_category_index") == null) {
                    Object selected = output.get("selected_category");
                    if (selected instanceof String selectedLabel && execution != null && execution.getPlan() != null) {
                        int idx = resolveCategoryIndex(execution, pending.nodeId(), selectedLabel);
                        output.put("selected_category_index", idx);
                    }
                }
                // Mirror AgentNode.createClassifySuccessResult: frontend message preview
                // reads `response`. Leave untouched if the worker already produced one.
                if (output.get("response") == null) {
                    Object selected = output.get("selected_category");
                    if (selected instanceof String label) {
                        output.put("response", "Classified as: " + label);
                    }
                }
            }
            case "guardrail" -> {
                output.put("node_type", "GUARDRAIL");
                // Mirror AgentNode.createGuardrailSuccessResult: frontend message preview
                // reads `response`. "passed" is same name in both paths, so the alias work
                // is narrower than classify.
                if (output.get("response") == null) {
                    Object passed = output.get("passed");
                    if (passed instanceof Boolean b) {
                        if (b) {
                            output.put("response", "Content passed all checks");
                        } else {
                            Object violations = output.get("violations");
                            int count = violations instanceof List<?> c ? c.size() : 0;
                            output.put("response", "Content violated " + count + " rule(s)");
                        }
                    }
                }
            }
            case "agent" -> {
                output.put("node_type", "AGENT");
                // Mirror AgentNode.createSuccessResult aliases for schema mapper and
                // template resolution (e.g. {{agent.output.content}}).
                if (output.get("content") == null && output.get("response") != null) {
                    output.put("content", output.get("response"));
                }
                if (output.get("iterations_used") == null && output.get("iterations") != null) {
                    output.put("iterations_used", output.get("iterations"));
                }
                // promptTokens / completionTokens - worker DTO may use camelCase or
                // snake_case; enrichAgentFields reads the camelCase form.
                if (output.get("promptTokens") == null && output.get("prompt_tokens") != null) {
                    output.put("promptTokens", output.get("prompt_tokens"));
                }
                if (output.get("completionTokens") == null && output.get("completion_tokens") != null) {
                    output.put("completionTokens", output.get("completion_tokens"));
                }
                // tool_calls_detail - transform toolResults from worker payload into
                // the same shape AgentNode.createSuccessResult builds.
                if (output.get("tool_calls_detail") == null) {
                    Object toolResults = output.get("toolResults");
                    if (toolResults instanceof List<?> results && !results.isEmpty()) {
                        List<Map<String, Object>> detail = new ArrayList<>();
                        for (Object tr : results) {
                            if (tr instanceof Map<?, ?> trMap) {
                                Map<String, Object> d = new LinkedHashMap<>();
                                Object toolCall = trMap.get("toolCall");
                                if (toolCall instanceof Map<?, ?> tcMap) {
                                    d.put("toolName", tcMap.get("toolName"));
                                    d.put("toolCallId", tcMap.get("id"));
                                }
                                d.put("success", trMap.get("success"));
                                d.put("durationMs", trMap.get("durationMs"));
                                detail.add(d);
                            }
                        }
                        if (!detail.isEmpty()) {
                            output.put("tool_calls", detail.size());
                            output.put("tool_calls_detail", detail);
                        }
                    }
                }
            }
            default -> {
                // Unknown type - leave as-is; enrichAgentFields will fall back to
                // NodeType.fromNodeId(stepId) which still gives AGENT for agent:* ids.
            }
        }

        // agent_config_snapshot - frozen config for audit trail and debugging.
        // The async producer snapshots resolved runtime config before yielding. Prefer
        // that snapshot because async delivery can rebuild a plan with default values.
        if (output.get("agent_config_snapshot") == null && execution != null && execution.getPlan() != null) {
            execution.getPlan().findAgent(pending.nodeId()).ifPresent(agentCfg -> {
                Map<String, Object> resolvedInputData = pending.resolvedInputData();
                Map<String, Object> snapshot = new LinkedHashMap<>();
                snapshot.put("agentConfigId", agentCfg.agentConfigId());
                snapshot.put("agentLabel", agentCfg.label());
                snapshot.put("agentType", agentCfg.type() != null ? agentCfg.type() : "agent");
                snapshot.put("provider", firstNonBlank(readString(resolvedInputData, "provider"), agentCfg.provider()));
                snapshot.put("model", firstNonBlank(readString(resolvedInputData, "model"), agentCfg.model()));
                Double runtimeTemperature = readDoubleValue(resolvedInputData, "temperature");
                Integer runtimeMaxTokens = readIntegerValue(resolvedInputData, "maxTokens");
                Integer runtimeMaxIterations = readIntegerValue(resolvedInputData, "maxIterations");
                snapshot.put("temperature", runtimeTemperature != null ? runtimeTemperature : agentCfg.temperature());
                snapshot.put("maxTokens", runtimeMaxTokens != null ? runtimeMaxTokens : agentCfg.maxTokens());
                snapshot.put("maxIterations", runtimeMaxIterations != null ? runtimeMaxIterations : agentCfg.maxIterations());
                snapshot.put("withMemory", agentCfg.withMemory());
                String resolvedSystemPrompt = firstNonBlank(readString(resolvedInputData, "systemPrompt"), agentCfg.systemPrompt());
                if (resolvedSystemPrompt != null) {
                    snapshot.put("systemPromptHash", hashForAudit(resolvedSystemPrompt));
                }
                output.put("agent_config_snapshot", snapshot);
            });
        }
    }

    /**
     * Truncated SHA-256 hash (first 16 hex chars) for audit snapshot.
     * Mirrors {@code AgentNode.hashString}.
     */
    private static String hashForAudit(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8 && i < digest.length; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "hash_error";
        }
    }

    /**
     * Look up the {@code classifyCategories} list for a classify agent and return the
     * 0-based index matching {@code selectedLabel}, or {@code -1} if not found.
     * Mirrors {@code AgentNode.findCategoryIndex} so the async path produces the same
     * index the inline path would have written.
     *
     * <p>Both sides of the match are run through
     * {@link com.apimarketplace.orchestrator.utils.LabelNormalizer#normalizeLabel}
     * - accent/case/whitespace/punctuation differences ("Réseaux" vs "reseaux",
     * "  finance " vs "finance") collapse to the same slug. Without this, an LLM
     * picking a near-correct label still hits the {@code -1} bucket and the item is
     * silently lost.
     */
    private int resolveCategoryIndex(WorkflowExecution execution, String nodeId, String selectedLabel) {
        try {
            String normalizedSelected =
                com.apimarketplace.orchestrator.utils.LabelNormalizer.normalizeLabel(selectedLabel);
            if (normalizedSelected == null) {
                return -1;
            }
            var agentOpt = execution.getPlan().findAgent(nodeId);
            if (agentOpt.isEmpty()) {
                return -1;
            }
            var categories = agentOpt.get().classifyCategories();
            if (categories == null || categories.isEmpty()) {
                return -1;
            }
            for (int i = 0; i < categories.size(); i++) {
                Object label = categories.get(i).get("label");
                if (label instanceof String s) {
                    String normalizedConfig =
                        com.apimarketplace.orchestrator.utils.LabelNormalizer.normalizeLabel(s);
                    if (normalizedConfig != null && normalizedConfig.equals(normalizedSelected)) {
                        return i;
                    }
                }
            }
            return -1;
        } catch (Exception e) {
            logger.warn("[AgentAsyncCompletion] Failed to resolve classify category index: nodeId={}, label={}, error={}",
                nodeId, selectedLabel, e.getMessage());
            return -1;
        }
    }

    /**
     * Record an {@code agent_executions} row for an async agent delivery.
     *
     * <p>Mirror of the inline {@code AgentNode.executeClassify}/{@code executeGuardrail}/
     * {@code executeAgent} observability recording blocks (lines ~856, ~970, ~1082).
     * Without this, flipping {@code scaling.agent.queue.enabled=true} (the default on
     * {@code scaling/horizontal-scaling}) silently drops every agent/classify/guardrail
     * execution from the {@code agent_executions} table - the backing store the frontend
     * "Agent Performance" metrics dashboard queries via
     * {@code AgentMetricsQueryService.getAgentTypeSummary}.</p>
     *
     * <p>Field resolution strategy:</p>
     * <ul>
     *   <li><b>Workflow context</b> (tenantId, runId, epoch, spawn, nodeId, itemIndex, workflowId,
     *       workflowRunId) - from the rebuilt {@link WorkflowExecution} plus the
     *       {@link PendingAgent} (which was stamped when the engine yielded).</li>
     *   <li><b>Agent config</b> (agentEntityId, provider, model, temperature, maxTokens,
     *       maxIterations, systemPrompt, memoryEnabled) - resolved from
     *       {@code execution.getPlan().findAgent(nodeId)}. This is already how
     *       {@link #resolveCategoryIndex} works, so the lookup is known-good in this context.</li>
     *   <li><b>Execution result</b> (tokens, duration, conversationMessages) - extracted from
     *       the worker's camelCase {@code result.result()} map, which is the JSON form of
     *       {@code ClassifyResponseDto}/{@code GuardrailResponseDto}/{@code AgentExecutionResponseDto}.
     *       The agent-type-specific fields (stopReason, iterations, toolResults) come from the
     *       agent-case map keys and are best-effort.</li>
     * </ul>
     *
     * <p>Isolated in try/catch so observability failures never strand the run - matches
     * the inline-path contract ({@code logger.warn} on failure, swallow exception).</p>
     */
    private void recordAsyncObservability(
            WorkflowExecution execution,
            PendingAgent pending,
            AgentResultMessage result,
            StepExecutionResult stepResult) {
        if (agentClient == null) {
            // Unwired in focused unit tests; nothing to do. Normal runtime has the client
            // auto-injected via AgentClientConfig.
            return;
        }
        try {
            // Resolve the static agent config from the plan - same access pattern as
            // resolveCategoryIndex, which proves this lookup is safe post-rebuild.
            com.apimarketplace.orchestrator.domain.workflow.Agent agentConfig = null;
            if (execution != null && execution.getPlan() != null) {
                var agentOpt = execution.getPlan().findAgent(pending.nodeId());
                if (agentOpt.isPresent()) {
                    agentConfig = agentOpt.get();
                }
            }
            if (agentConfig == null) {
                logger.debug("[AgentAsyncCompletion] Skipping observability - agent config not found in plan: runId={}, nodeId={}",
                    pending.runId(), pending.nodeId());
                return;
            }

            String agentType = pending.agentType() != null ? pending.agentType() : "agent";
            String status = result.success() ? "COMPLETED" : "FAILED";

            var req = new com.apimarketplace.agent.client.dto.AgentObservabilityRequest();
            req.setTenantId(pending.tenantId());
            // PR20 - propagate workspace identity from the enqueue-time snapshot
            // so the async-delivery row is scoped identically to the sync path.
            req.setOrganizationId(pending.organizationId());
            req.setAgentType(agentType);
            req.setNodeId(pending.nodeId());
            req.setStatus(status);
            req.setErrorMessage(result.errorMessage());

            // Agent entity reference (same parse + ignore pattern as inline path)
            if (agentConfig.agentConfigId() != null) {
                try {
                    req.setAgentEntityId(java.util.UUID.fromString(agentConfig.agentConfigId()));
                } catch (IllegalArgumentException ignored) {}
            }

            Optional<java.util.UUID> persistedWorkflowId = Optional.empty();
            Optional<WorkflowRunEntity> persistedRun = Optional.empty();
            if (pending.runId() != null) {
                try {
                    Optional<java.util.UUID> workflowId = runRepository.findWorkflowIdByRunIdPublic(pending.runId());
                    if (workflowId != null) {
                        persistedWorkflowId = workflowId;
                    }
                } catch (Exception e) {
                    logger.debug("[AgentAsyncCompletion] Could not resolve workflowId from run row: runId={}",
                            pending.runId(), e);
                }
                try {
                    Optional<WorkflowRunEntity> run = runRepository.findByRunIdPublic(pending.runId());
                    if (run != null) {
                        persistedRun = run;
                    }
                } catch (Exception e) {
                    logger.debug("[AgentAsyncCompletion] Could not resolve workflowRunId from run row: runId={}",
                            pending.runId(), e);
                }
            }

            // Workflow context. The persisted run row is authoritative: async
            // delivery can rebuild a plan map without an id, causing WorkflowPlanParser
            // to generate a synthetic id that must not leak into observability.
            if (persistedWorkflowId.isPresent()) {
                req.setWorkflowId(persistedWorkflowId.get());
            } else if (execution.getPlan() != null && execution.getPlan().getId() != null) {
                try {
                    req.setWorkflowId(java.util.UUID.fromString(execution.getPlan().getId()));
                } catch (IllegalArgumentException ignored) {}
            }
            if (persistedRun.map(WorkflowRunEntity::getId).isPresent()) {
                req.setWorkflowRunId(persistedRun.map(WorkflowRunEntity::getId).orElseThrow());
            } else if (execution.getWorkflowRunId() != null) {
                req.setWorkflowRunId(execution.getWorkflowRunId());
            } else {
                try {
                    req.setWorkflowRunId(java.util.UUID.fromString(pending.runId()));
                } catch (IllegalArgumentException ignored) {}
            }
            req.setRunId(pending.runId());
            req.setEpoch(pending.epoch());
            req.setItemIndex(pending.itemIndex());
            // spawn is not carried on PendingAgent - default to 0 (matches inline for
            // top-level trigger fires; the value is informational, not a primary key).
            req.setSpawn(0);

            // LLM config - runtime values from the worker's response are authoritative
            // (real provider/model selected by the routing layer at execution time).
            // Fall back to the static plan only when the worker didn't propagate them.
            // Without this fallback, plans whose provider/model are resolved at runtime
            // (defaulting, fleet routing) land with provider=NULL,model=NULL in
            // agent_executions - mirrors the inline path in AgentNode.buildObservabilityRequest.
            Map<String, Object> resolvedInputData = pending.resolvedInputData();
            String runtimeProvider = firstNonBlank(
                readString(result.result(), "provider"),
                readString(resolvedInputData, "provider"));
            String runtimeModel = firstNonBlank(
                readString(result.result(), "model"),
                readString(resolvedInputData, "model"));
            Double runtimeTemperature = readDoubleValue(resolvedInputData, "temperature");
            Integer runtimeMaxTokens = readIntegerValue(resolvedInputData, "maxTokens");
            Integer runtimeMaxIterations = readIntegerValue(resolvedInputData, "maxIterations");
            req.setProvider(firstNonBlank(runtimeProvider, agentConfig.provider()));
            req.setModel(firstNonBlank(runtimeModel, agentConfig.model()));
            req.setTemperature(runtimeTemperature != null ? runtimeTemperature : agentConfig.temperature());
            req.setMaxTokensConfig(runtimeMaxTokens != null ? runtimeMaxTokens : agentConfig.maxTokens());
            req.setMaxIterationsConfig(runtimeMaxIterations != null ? runtimeMaxIterations : agentConfig.maxIterations());
            req.setMemoryEnabled(Boolean.TRUE.equals(agentConfig.withMemory()));
            // Cross-layer parity with inline path (AgentNode.buildObservabilityRequest):
            // forward the conversation linkage onto agent_executions so the frontend
            // "Agent Performance" dashboard can deep-link from a metric row to the
            // matching conversation. Inline mode populates this; async mode used to
            // leave it null until PendingAgent started carrying conversationId.
            if (pending.conversationId() != null) {
                req.setConversationId(pending.conversationId());
            }

            // Duration - use the stepResult's already-computed value to stay aligned with
            // what was persisted; falls back to worker's self-reported durationMs.
            long durationMs = stepResult != null ? stepResult.executionTime() : 0L;
            if (durationMs <= 0) {
                Object workerDuration = readNumber(result.result(), "durationMs");
                if (workerDuration instanceof Number n) {
                    durationMs = n.longValue();
                }
            }
            req.setDurationMs(durationMs);

            // Token usage - worker DTOs (ClassifyResponseDto/GuardrailResponseDto) expose
            // flat camelCase counters; AgentExecutionResponseDto wraps them in totalUsage.
            populateTokensFromResult(req, result.result());

            // Per-type enrichment
            Map<String, Object> rawResult = result.result();
            if (rawResult != null) {
                switch (agentType.toLowerCase()) {
                    case "classify", "guardrail" -> {
                        req.setIterationCount(1);
                        String workerSystemPrompt = readString(rawResult, "systemPrompt");
                        String workerUserPrompt = readString(rawResult, "userPrompt");
                        if (workerSystemPrompt != null && !workerSystemPrompt.isBlank()) {
                            req.setSystemPrompt(workerSystemPrompt);
                        }
                        // Prepend SYSTEM + USER from the worker DTO, then append the
                        // worker's conversationMessages - mirrors the inline path
                        // (AgentNode.executeClassify lines 1082-1124, executeGuardrail
                        // lines 1240-1281). Without USER, the Agent Performance metric
                        // view shows only the system prompt and assistant reply, hiding
                        // what was actually classified / guardrailed.
                        prependPromptsAndAppendHistory(req, workerSystemPrompt, workerUserPrompt,
                            rawResult.get("conversationMessages"));
                    }
                    case "agent" -> enrichAgentShape(req, rawResult, agentConfig, pending);
                    default -> {
                        // unknown agent type - leave defaults, the inline path has no
                        // separate branch either (falls through to NodeType.fromNodeId).
                    }
                }
            }

            agentClient.recordObservability(req);
            logger.debug("[AgentAsyncCompletion] Recorded observability: runId={}, nodeId={}, agentType={}, status={}",
                pending.runId(), pending.nodeId(), agentType, status);
        } catch (Exception | LinkageError t) {
            // Observability is best-effort. Linkage failures from a stale optional client
            // jar must not block edge emission or successor traversal after the step row
            // has already been persisted.
            logger.warn("[AgentAsyncCompletion] Failed to record observability: runId={}, nodeId={}, error={}",
                pending.runId(), pending.nodeId(), t.toString(), t);
        }
    }

    /**
     * Persist the assistant message + tool results to the agent's conversation and
     * emit {@code stream_completed} on the conversation channel.
     *
     * <p>Mirror of the inline path's {@code conversationManager.saveAssistantResponse}
     * + {@code completeStream} pair (see {@code AgentNode.executeAgent} ~lines 899-901).
     * The async producer ({@code AgentNode.executeAgentAsyncQueue}) snapshots
     * {@code conversationId}, {@code streamId}, {@code executionId}, and {@code model}
     * onto the {@link PendingAgent} entry at enqueue time; this method consumes them
     * here at delivery to land the assistant turn on the same conversation row the
     * user prompt was written to (so the panel shows them as one turn keyed by
     * {@code executionId}).</p>
     *
     * <p>Scope: only fires when the producer attached a non-null {@code conversationId}
     * - that means {@code agentType="agent"} and a conversation could be resolved
     * (chat trigger or agent entity). classify/guardrail are skipped at the producer
     * (no {@code conversationId} set), so this method is a no-op for them.</p>
     *
     * <p>Best-effort: any failure is logged and swallowed so an unreachable
     * conversation-service can't block successor traversal - matches the inline
     * path's "warn-and-swallow" contract for the same reason.</p>
     */
    private void persistConversationOnDelivery(PendingAgent pending, AgentResultMessage result) {
        if (conversationManager == null || pending.conversationId() == null) {
            return;
        }
        try {
            AgentExecutionResult agentResult = buildAgentExecutionResultForConversation(pending, result);
            conversationManager.saveAssistantResponse(
                pending.conversationId(), pending.tenantId(), agentResult, pending.executionId());
            if (pending.streamId() != null) {
                conversationManager.completeStream(
                    new com.apimarketplace.orchestrator.services.agent.AgentConversationManager.StreamSession(
                        pending.conversationId(), pending.streamId()),
                    agentResult);
            }
        } catch (Exception e) {
            logger.warn("[AgentAsyncCompletion] Failed to persist conversation on delivery: runId={}, nodeId={}, conversationId={}, error={}",
                pending.runId(), pending.nodeId(), pending.conversationId(), e.getMessage());
        }
    }

    /**
     * Build a thin {@link AgentExecutionResult} from the worker's raw result map so we
     * can call the existing {@code AgentConversationManager.saveAssistantResponse} /
     * {@code completeStream} entry points without duplicating their visualization /
     * iconSlug / toolCalls-JSON logic.
     *
     * <p>Only the fields these methods actually read are populated:</p>
     * <ul>
     *   <li>{@code success} - drives whether {@code completeStream} publishes
     *       {@code stream_completed} or {@code stream_error}</li>
     *   <li>{@code finalResponse} - wraps {@code content} so {@code AgentExecutionResult.getContent()}
     *       returns the assistant text, and {@code completeStream} pushes the right body</li>
     *   <li>{@code toolResults} - feeds {@code saveToolResults} +
     *       {@code buildToolCallsJson}; fed from the worker's serialized list of
     *       {@code ToolResult}-shaped maps (toolCall.id/toolName/arguments, success,
     *       content, error, durationMs, metadata)</li>
     *   <li>{@code error} - used by the failure branch of {@code completeStream}</li>
     *   <li>{@code provider}/{@code model} - the inline path passes them; not strictly
     *       required for conversation persistence but cheap to forward and useful for
     *       any downstream observers that key off them</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private AgentExecutionResult buildAgentExecutionResultForConversation(
            PendingAgent pending, AgentResultMessage result) {
        Map<String, Object> raw = result.result() != null ? result.result() : Map.of();

        // Content fallback chain mirrors the worker's output normalization in injectAgentMetadata
        // (see this file ~line 942) - agent-service emits `content` as primary, `finalResponse`
        // as legacy alias, `response` as the workflow-shape alias used by the orchestrator
        // mapper. Try each so we don't miss the message body if the worker shape drifts.
        String content = readString(raw, "content");
        if (content == null) content = readString(raw, "finalResponse");
        if (content == null) content = readString(raw, "response");

        List<com.apimarketplace.agent.domain.ToolResult> toolResults = new java.util.ArrayList<>();
        Object trObj = raw.get("toolResults");
        if (trObj instanceof List<?> trList) {
            for (Object item : trList) {
                if (!(item instanceof Map<?, ?> map)) continue;
                Map<String, Object> trMap = (Map<String, Object>) map;
                com.apimarketplace.agent.domain.ToolCall toolCall = null;
                Object tcObj = trMap.get("toolCall");
                if (tcObj instanceof Map<?, ?> tcMap) {
                    Map<String, Object> tc = (Map<String, Object>) tcMap;
                    Object args = tc.get("arguments");
                    Map<String, Object> argsMap = args instanceof Map<?, ?> am
                        ? (Map<String, Object>) am
                        : null;
                    Object idxObj = tc.get("index");
                    Integer idx = idxObj instanceof Number n ? n.intValue() : null;
                    toolCall = com.apimarketplace.agent.domain.ToolCall.builder()
                        .id(readString(tc, "id"))
                        .toolName(readString(tc, "toolName"))
                        .arguments(argsMap)
                        .index(idx)
                        .build();
                }
                Object durObj = trMap.get("durationMs");
                Long durationMs = durObj instanceof Number n ? n.longValue() : null;
                Object metaObj = trMap.get("metadata");
                Map<String, Object> metadata = metaObj instanceof Map<?, ?> mm
                    ? (Map<String, Object>) mm
                    : null;
                toolResults.add(com.apimarketplace.agent.domain.ToolResult.builder()
                    .toolCall(toolCall)
                    .success(Boolean.TRUE.equals(trMap.get("success")))
                    .content(readString(trMap, "content"))
                    .error(readString(trMap, "error"))
                    .durationMs(durationMs)
                    .metadata(metadata)
                    .build());
            }
        }

        com.apimarketplace.agent.domain.CompletionResponse finalResponse = content != null
            ? com.apimarketplace.agent.domain.CompletionResponse.builder().content(content).build()
            : null;

        return AgentExecutionResult.builder()
            .success(result.success())
            .finalResponse(finalResponse)
            .toolResults(toolResults)
            .error(result.errorMessage())
            .provider(readString(raw, "provider"))
            .model(pending.model() != null ? pending.model() : readString(raw, "model"))
            .build();
    }

    /** Pull a numeric field from the worker result map, handling the missing-key case. */
    private static Object readNumber(Map<String, Object> map, String key) {
        return map != null ? map.get(key) : null;
    }

    /** Pull a string field from the worker result map, handling the missing-key case. */
    private static String readString(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    private static Integer readIntegerValue(Map<String, Object> map, String key) {
        return map != null ? toInt(map.get(key)) : null;
    }

    private static Double readDoubleValue(Map<String, Object> map, String key) {
        return map != null ? toDouble(map.get(key)) : null;
    }

    private static String firstNonBlank(String primary, String fallback) {
        return primary != null && !primary.isBlank() ? primary : fallback;
    }

    /**
     * Populate token fields on the observability request from the worker's result map.
     *
     * <p>Two shapes are supported:</p>
     * <ol>
     *   <li><b>Classify/Guardrail</b>: flat {@code tokensUsed}/{@code promptTokens}/{@code completionTokens}
     *       (from {@code ClassifyResponseDto} / {@code GuardrailResponseDto}).</li>
     *   <li><b>Agent</b>: nested {@code totalUsage} map (from {@code AgentExecutionResponseDto}),
     *       keys mirror {@code TokenUsage}: {@code promptTokens}, {@code completionTokens},
     *       {@code cacheCreationInputTokens}, {@code cacheReadInputTokens}, {@code cachedTokens},
     *       {@code reasoningTokens}.</li>
     * </ol>
     */
    @SuppressWarnings("unchecked")
    private static void populateTokensFromResult(
            com.apimarketplace.agent.client.dto.AgentObservabilityRequest req,
            Map<String, Object> rawResult) {
        if (rawResult == null) {
            return;
        }
        // Agent shape: totalUsage map
        Object totalUsage = rawResult.get("totalUsage");
        if (totalUsage instanceof Map<?, ?> usage) {
            Map<String, Object> u = (Map<String, Object>) usage;
            Integer prompt = toInt(u.get("promptTokens"));
            Integer completion = toInt(u.get("completionTokens"));
            req.setPromptTokens(prompt != null ? prompt : 0);
            req.setCompletionTokens(completion != null ? completion : 0);
            // Prefer the provider-reported totalTokens when present - matches inline
            // path's UsageInfo.getTotal() which returns persisted totalTokens first and
            // only falls back to prompt+completion when the field is null.
            Integer total = toInt(u.get("totalTokens"));
            if (total != null) {
                req.setTotalTokens(total);
            } else {
                req.setTotalTokens((prompt != null ? prompt : 0) + (completion != null ? completion : 0));
            }
            Integer cacheCreate = toInt(u.get("cacheCreationInputTokens"));
            if (cacheCreate != null) req.setCacheCreationTokens(cacheCreate);
            Integer cacheRead = toInt(u.get("cacheReadInputTokens"));
            if (cacheRead != null) req.setCacheReadTokens(cacheRead);
            Integer cached = toInt(u.get("cachedTokens"));
            if (cached != null) req.setCachedTokens(cached);
            Integer reasoning = toInt(u.get("reasoningTokens"));
            if (reasoning != null) req.setReasoningTokens(reasoning);
            return;
        }
        // Classify/Guardrail shape: flat counters
        Integer tokensUsed = toInt(rawResult.get("tokensUsed"));
        if (tokensUsed != null) {
            req.setTotalTokens(tokensUsed);
        }
        Integer prompt = toInt(rawResult.get("promptTokens"));
        if (prompt != null) {
            req.setPromptTokens(prompt);
        }
        Integer completion = toInt(rawResult.get("completionTokens"));
        if (completion != null) {
            req.setCompletionTokens(completion);
        }
    }

    private static Integer toInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Double toDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * Build the observability {@code messages} list for classify / guardrail by
     * prepending SYSTEM + USER from the worker's flat DTO fields and appending the
     * worker's {@code conversationMessages}. Mirrors {@code AgentNode.executeClassify}
     * lines 1082-1124 and {@code executeGuardrail} lines 1240-1281.
     *
     * <p>The agent loop's {@code AgentLoopResult.conversationHistory()} only carries
     * messages produced during execution (ASSISTANT / TOOL turns) - the SYSTEM and
     * USER prompts are stripped by {@code LoopExecutionState.markExecutionStart()}.
     * So the worker DTO's {@code conversationMessages} field is typically just
     * {@code [ASSISTANT]}, and the prompts have to come from the dedicated
     * {@code systemPrompt} / {@code userPrompt} fields. Defensive dedupe on
     * {@code SYSTEM}/{@code USER} roles guards against future worker changes that
     * might echo them back.</p>
     */
    @SuppressWarnings("unchecked")
    private static void prependPromptsAndAppendHistory(
            com.apimarketplace.agent.client.dto.AgentObservabilityRequest req,
            String systemPrompt,
            String userPrompt,
            Object rawMessages) {
        var messages = new java.util.ArrayList<com.apimarketplace.agent.client.dto.AgentObservabilityRequest.MessageData>();
        int seq = 0;
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            var sys = new com.apimarketplace.agent.client.dto.AgentObservabilityRequest.MessageData();
            sys.setSequenceNumber(seq++);
            sys.setRole("SYSTEM");
            sys.setContent(systemPrompt);
            messages.add(sys);
        }
        if (userPrompt != null && !userPrompt.isBlank()) {
            var usr = new com.apimarketplace.agent.client.dto.AgentObservabilityRequest.MessageData();
            usr.setSequenceNumber(seq++);
            usr.setRole("USER");
            usr.setContent(userPrompt);
            usr.setIterationNumber(1);
            messages.add(usr);
        }
        if (rawMessages instanceof java.util.List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> msgMap)) {
                    continue;
                }
                Map<String, Object> src = (Map<String, Object>) msgMap;
                Object role = src.get("role");
                String roleStr = role != null ? role.toString() : "UNKNOWN";
                if ("SYSTEM".equalsIgnoreCase(roleStr) || "USER".equalsIgnoreCase(roleStr)) {
                    continue;
                }
                var md = new com.apimarketplace.agent.client.dto.AgentObservabilityRequest.MessageData();
                md.setSequenceNumber(seq++);
                md.setRole(roleStr);
                Object content = src.get("content");
                md.setContent(content != null ? content.toString() : null);
                Object toolCallId = src.get("toolCallId");
                if (toolCallId != null) md.setToolCallId(toolCallId.toString());
                Object toolName = src.get("toolName");
                if (toolName != null) md.setToolName(toolName.toString());
                md.setIterationNumber(1);
                messages.add(md);
            }
        }
        if (!messages.isEmpty()) {
            req.setMessages(messages);
        }
    }

    /**
     * Mirror {@code AgentNode.buildObservabilityRequest} for the async agent shape
     * (lines 1754-1894). Reads the {@code AgentExecutionResponseDto} JSON form and
     * populates all fields the inline path writes, so the agent_executions row the
     * dashboard renders is indistinguishable between sync and async execution.
     *
     * <p>Covered fields:</p>
     * <ul>
     *   <li>iterationCount, stopReason, totalToolCalls, uniqueToolCount</li>
     *   <li>budgetScope + loopDetected/loopType/loopToolName from metrics map</li>
     *   <li>systemPrompt + memoryEnabled fallback (template-resolved value not in the
     *       async result - best we can do is the static plan text)</li>
     *   <li>Per-iteration {@code IterationData} list from {@code usagePerIteration} +
     *       {@code iterationDurations} + {@code finishReasonsPerIteration} +
     *       {@code toolCallsPerIteration} in metrics</li>
     *   <li>{@code ToolCallData} list from {@code toolResults} (nested {@code toolCall}
     *       map supplies id/name/arguments/index)</li>
     *   <li>{@code MessageData} list from {@code conversationHistory}, with iteration
     *       numbering matching the inline path (increment on each ASSISTANT role)</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private static void enrichAgentShape(
            com.apimarketplace.agent.client.dto.AgentObservabilityRequest req,
            Map<String, Object> rawResult,
            com.apimarketplace.orchestrator.domain.workflow.Agent agentConfig,
            PendingAgent pending) {
        // iterationCount
        Object iters = rawResult.get("iterations");
        if (iters instanceof Number n) {
            req.setIterationCount(n.intValue());
        }

        // stopReason
        Object stopReason = rawResult.get("stopReason");
        if (stopReason instanceof String s && !s.isBlank()) {
            req.setStopReason(s);
        }

        // totalToolCalls + uniqueToolCount from toolResults
        Object toolResultsObj = rawResult.get("toolResults");
        List<Map<String, Object>> toolResults = null;
        if (toolResultsObj instanceof List<?> list) {
            toolResults = new java.util.ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    toolResults.add((Map<String, Object>) m);
                }
            }
            req.setTotalToolCalls(toolResults.size());
            if (!toolResults.isEmpty()) {
                java.util.Set<String> uniqueTools = new java.util.HashSet<>();
                for (Map<String, Object> tr : toolResults) {
                    Object tcObj = tr.get("toolCall");
                    if (tcObj instanceof Map<?, ?> tcMap) {
                        Object name = ((Map<String, Object>) tcMap).get("toolName");
                        if (name != null) {
                            uniqueTools.add(name.toString());
                        }
                    }
                }
                req.setUniqueToolCount(uniqueTools.size());
            }
        }

        // metrics: budgetScope, loopDetected/loopType/loopToolName
        Object metricsObj = rawResult.get("metrics");
        Map<String, Object> metrics = null;
        if (metricsObj instanceof Map<?, ?> m) {
            metrics = (Map<String, Object>) m;
            Object scope = metrics.get("budgetScope");
            if (scope instanceof String s && !s.isBlank()) {
                req.setBudgetScope(s);
            }
            Object loopDetected = metrics.get("loopDetected");
            req.setLoopDetected(Boolean.TRUE.equals(loopDetected));
            Object loopType = metrics.get("loopType");
            if (loopType != null) req.setLoopType(loopType.toString());
            Object loopToolName = metrics.get("loopToolName");
            if (loopToolName != null) req.setLoopToolName(loopToolName.toString());
        }

        // systemPrompt - prefer the resolved snapshot taken at enqueue (mirrors the
        // inline path's modular-prefix + custom-template, with variables substituted),
        // then fall back to the raw plan template if the snapshot is missing (e.g.
        // legacy PendingAgent rows from before the field was added).
        String snapshotSystemPrompt = pending != null ? pending.resolvedSystemPrompt() : null;
        String effectiveSystemPrompt = snapshotSystemPrompt;
        if (effectiveSystemPrompt == null || effectiveSystemPrompt.isBlank()) {
            if (agentConfig != null && agentConfig.systemPrompt() != null) {
                effectiveSystemPrompt = agentConfig.systemPrompt();
            }
        }
        if (effectiveSystemPrompt != null && !effectiveSystemPrompt.isBlank()) {
            req.setSystemPrompt(effectiveSystemPrompt);
        }

        // memoryEnabled is already set by the caller from agentConfig.withMemory();
        // conversationId is forwarded at the recordAsyncObservability call site (not here)
        // because that's where the PendingAgent reference is available - see the
        // setConversationId call right after the AgentObservabilityRequest is built.

        // Conversation history → messages.
        // Prepend SYSTEM + USER from the enqueue-time snapshot so the metric "Conversation"
        // tab matches the chat / sync / sub-agent paths (which all prepend these explicitly
        // via AgentNode.buildObservabilityRequest and SubAgentExecutionHandler.recordObservability).
        // Without the snapshot, the worker only echoes ASSISTANT/TOOL turns (the loop's
        // "current execution messages" excludes the system + user prompts), so the metric
        // view rendered just the agent's reply with no context of what was asked.
        String snapshotUserPrompt = pending != null ? pending.resolvedUserPrompt() : null;
        var messages = new java.util.ArrayList<com.apimarketplace.agent.client.dto.AgentObservabilityRequest.MessageData>();
        int msgSeq = 0;
        int msgIterationCounter = 0;
        if (effectiveSystemPrompt != null && !effectiveSystemPrompt.isBlank()) {
            var sys = new com.apimarketplace.agent.client.dto.AgentObservabilityRequest.MessageData();
            sys.setSequenceNumber(msgSeq++);
            sys.setRole("SYSTEM");
            sys.setContent(effectiveSystemPrompt);
            messages.add(sys);
        }
        if (snapshotUserPrompt != null && !snapshotUserPrompt.isBlank()) {
            var usr = new com.apimarketplace.agent.client.dto.AgentObservabilityRequest.MessageData();
            usr.setSequenceNumber(msgSeq++);
            usr.setRole("USER");
            usr.setContent(snapshotUserPrompt);
            usr.setIterationNumber(msgIterationCounter);
            messages.add(usr);
        }
        Object historyObj = rawResult.get("conversationHistory");
        if (historyObj instanceof List<?> history && !history.isEmpty()) {
            for (Object item : history) {
                if (!(item instanceof Map<?, ?> msgMap)) continue;
                Map<String, Object> src = (Map<String, Object>) msgMap;
                Object role = src.get("role");
                String roleStr = role != null ? role.toString() : "UNKNOWN";
                // Skip echoed SYSTEM/USER turns from the worker - we already prepended
                // the snapshot copies above; rendering both would duplicate the row.
                if ("SYSTEM".equalsIgnoreCase(roleStr) || "USER".equalsIgnoreCase(roleStr)) {
                    continue;
                }
                if ("ASSISTANT".equalsIgnoreCase(roleStr)) {
                    msgIterationCounter++;
                }
                var md = new com.apimarketplace.agent.client.dto.AgentObservabilityRequest.MessageData();
                md.setSequenceNumber(msgSeq++);
                md.setRole(roleStr);
                Object content = src.get("content");
                md.setContent(content != null ? content.toString() : null);
                Object toolCallId = src.get("toolCallId");
                if (toolCallId != null) md.setToolCallId(toolCallId.toString());
                Object toolName = src.get("toolName");
                if (toolName != null) md.setToolName(toolName.toString());
                md.setIterationNumber(msgIterationCounter);
                messages.add(md);
            }
        }
        if (!messages.isEmpty()) {
            req.setMessages(messages);
        }

        // Tool results → toolCalls (with parallelIndex from toolCall.index)
        if (toolResults != null && !toolResults.isEmpty()) {
            var toolCalls = new java.util.ArrayList<com.apimarketplace.agent.client.dto.AgentObservabilityRequest.ToolCallData>();
            int seq = 0;
            for (Map<String, Object> tr : toolResults) {
                var tc = new com.apimarketplace.agent.client.dto.AgentObservabilityRequest.ToolCallData();
                tc.setSequenceNumber(seq++);
                Object tcObj = tr.get("toolCall");
                if (tcObj instanceof Map<?, ?> tcMap) {
                    Map<String, Object> call = (Map<String, Object>) tcMap;
                    Object id = call.get("id");
                    if (id != null) tc.setToolCallId(id.toString());
                    Object name = call.get("toolName");
                    if (name != null) tc.setToolName(name.toString());
                    Object args = call.get("arguments");
                    if (args instanceof Map<?, ?> am) {
                        tc.setArguments((Map<String, Object>) am);
                    }
                    Integer idx = toInt(call.get("index"));
                    if (idx != null) tc.setParallelIndex(idx);
                }
                Object success = tr.get("success");
                tc.setSuccess(Boolean.TRUE.equals(success));
                Object content = tr.get("content");
                if (content != null) tc.setResult(content.toString());
                Object error = tr.get("error");
                if (error != null) tc.setErrorMessage(error.toString());
                Integer durMs = toInt(tr.get("durationMs"));
                tc.setDurationMs(durMs != null ? durMs.longValue() : 0L);
                toolCalls.add(tc);
            }
            req.setToolCalls(toolCalls);
        }

        // Per-iteration usage → IterationData list (mirrors inline path lines 1871-1893)
        Object uiObj = rawResult.get("usagePerIteration");
        if (uiObj instanceof List<?> usageList && !usageList.isEmpty()) {
            // Per-iteration duration + finishReason come from parallel lists on the DTO
            List<?> iterDurations = rawResult.get("iterationDurations") instanceof List<?> d ? d : null;
            List<?> finishReasons = rawResult.get("finishReasonsPerIteration") instanceof List<?> f ? f : null;
            // Per-iteration tool call count lives on the metrics map (matches inline)
            List<?> toolCallsPerIter = null;
            if (metrics != null) {
                Object tcpi = metrics.get("toolCallsPerIteration");
                if (tcpi instanceof List<?> l) toolCallsPerIter = l;
            }
            var iterations = new java.util.ArrayList<com.apimarketplace.agent.client.dto.AgentObservabilityRequest.IterationData>();
            for (int i = 0; i < usageList.size(); i++) {
                Object entry = usageList.get(i);
                if (!(entry instanceof Map<?, ?> em)) continue;
                Map<String, Object> usage = (Map<String, Object>) em;
                var iter = new com.apimarketplace.agent.client.dto.AgentObservabilityRequest.IterationData();
                iter.setIterationNumber(i);
                Integer prompt = toInt(usage.get("promptTokens"));
                Integer completion = toInt(usage.get("completionTokens"));
                iter.setPromptTokens(prompt != null ? prompt : 0);
                iter.setCompletionTokens(completion != null ? completion : 0);
                Integer cacheCreate = toInt(usage.get("cacheCreationInputTokens"));
                if (cacheCreate != null) iter.setCacheCreationTokens(cacheCreate);
                Integer cacheRead = toInt(usage.get("cacheReadInputTokens"));
                if (cacheRead != null) iter.setCacheReadTokens(cacheRead);
                Integer cached = toInt(usage.get("cachedTokens"));
                if (cached != null) iter.setCachedTokens(cached);
                Integer reasoning = toInt(usage.get("reasoningTokens"));
                if (reasoning != null) iter.setReasoningTokens(reasoning);
                if (iterDurations != null && i < iterDurations.size()) {
                    Object d = iterDurations.get(i);
                    if (d instanceof Number n) iter.setDurationMs(n.longValue());
                }
                if (finishReasons != null && i < finishReasons.size()) {
                    Object fr = finishReasons.get(i);
                    if (fr != null) iter.setFinishReason(fr.toString());
                }
                if (toolCallsPerIter != null && i < toolCallsPerIter.size()) {
                    Integer count = toInt(toolCallsPerIter.get(i));
                    if (count != null) iter.setToolCallCount(count);
                }
                iterations.add(iter);
            }
            req.setIterations(iterations);
        }
    }

    /**
     * Reconstruct a {@link WorkflowExecution} from the persisted run state.
     * The engine yielded after offloading the agent, so the original execution is gone;
     * we rebuild it the same way {@code WorkflowResumeService} does for ordinary resume.
     */
    /**
     * Should this late async result be dropped because the run is stopped/terminal?
     *
     * <p>Delegates to {@link com.apimarketplace.orchestrator.services.resume.RunCancellationGuard}
     * when wired (production path). Falls back to the original inline check when the guard
     * is null (legacy unit-test fixtures). The fallback preserves the pre-fix behavior of
     * conservatively treating WAITING_TRIGGER as terminal - which is wrong for reusable
     * triggers but matches what tests expected before 2026-05-06.
     */
    private boolean isRunStoppedOrTerminal(String runId) {
        if (runCancellationGuard != null) {
            return runCancellationGuard.isRunStoppedOrTerminal(runId);
        }
        try {
            Optional<WorkflowRunEntity> runOpt = runRepository.findByRunIdPublic(runId);
            if (runOpt.isEmpty()) return true;
            RunStatus status = runOpt.get().getStatus();
            return status.isTerminal() || status == RunStatus.WAITING_TRIGGER;
        } catch (Exception e) {
            logger.warn("[AgentAsyncCompletion] Status check failed for runId={}: {}", runId, e.getMessage());
            return false;
        }
    }

    private WorkflowExecution rebuildExecution(String runId) {
        try {
            WorkflowRunState state = workflowResumeService.reconstructState(runId);
            if (state == null) {
                return null;
            }
            return executionContextManager.rebuildExecutionContext(runId, state);
        } catch (Exception e) {
            logger.error("[AgentAsyncCompletion] Failed to rebuild execution: runId={}, error={}",
                runId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Rebuild both the {@code ExecutionTree} and {@code WorkflowExecution} in a single
     * pass - same {@code reconstructState} cost as {@link #rebuildExecution} (the heavy
     * part), with the tree build added on top (cheap). Returning the {@link
     * com.apimarketplace.orchestrator.execution.v2.cache.ExecutionCacheManager.LoadedExecution}
     * lets {@link #executeReadyNodesLoop} pass it down to the first {@code executeNode} /
     * {@code getReadyNodes} call so they don't re-pay {@code reconstructState}.
     *
     * <p>When {@code executionCacheManager} is unwired (focused unit-test fixtures that
     * pre-date this optimisation), falls back to {@link #rebuildExecution} and returns a
     * {@code LoadedExecution} with a {@code null} tree. Downstream consumers that read
     * the tree fall back to their own {@code reconstructState} path automatically - the
     * fix-OOM contract still holds and the test path stays untouched.
     */
    private com.apimarketplace.orchestrator.execution.v2.cache.ExecutionCacheManager.LoadedExecution
            rebuildLoadedExecution(String runId) {
        if (executionCacheManager == null) {
            WorkflowExecution exec = rebuildExecution(runId);
            return exec != null
                ? new com.apimarketplace.orchestrator.execution.v2.cache.ExecutionCacheManager.LoadedExecution(null, exec)
                : null;
        }
        try {
            return executionCacheManager.loadTreeAndExecution(runId);
        } catch (Exception e) {
            logger.error("[AgentAsyncCompletion] Failed to rebuild loaded execution: runId={}, error={}",
                runId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Drive ready-node execution after persistence, looping until no more ready nodes
     * remain or a node yields (signal / async).
     *
     * <p>Mirrors {@code SignalResumeService.resumeAutoModeUnderLock}: the loop body is
     * intentionally local - the bug-prone {@code completeStep} pipeline is shared,
     * the small traversal driver is duplicated to keep this service decoupled from the
     * signal subsystem.</p>
     */
    private void executeReadyNodesLoop(String runId, String itemId, String dagTriggerId, int epoch) {
        executeReadyNodesLoop(runId, itemId, dagTriggerId, epoch, Set.of(), null);
    }

    private void executeReadyNodesLoop(String runId, String itemId, String dagTriggerId, int epoch,
                                        Set<String> alreadyDispatched) {
        executeReadyNodesLoop(runId, itemId, dagTriggerId, epoch, alreadyDispatched, null);
    }

    /**
     * Drive ready-node execution. {@code alreadyDispatched} is consulted to skip nodes
     * that Phase 1's per-item traversal already kicked off - avoids redundant
     * {@code executeNode} dispatch (idempotency at {@code V2StepByStepService} layer
     * is the primary defense; this skip is observability-only).
     *
     * <p>{@code preloaded} (when non-null) lets the first {@code getReadyNodes} and the
     * first {@code executeNode} call inside the loop reuse the caller's already-loaded
     * tree+execution wrapper instead of re-paying {@code reconstructState}. After the
     * first {@code executeNode}, state has mutated (a row was persisted) so subsequent
     * iterations clear the preload and let the engine re-fetch.
     */
    private void executeReadyNodesLoop(String runId, String itemId, String dagTriggerId, int epoch,
                                        Set<String> alreadyDispatched,
                                        com.apimarketplace.orchestrator.execution.v2.cache.ExecutionCacheManager.LoadedExecution preloaded) {
        // Correlation token for this traversal - each async agent completion arrives on a
        // separate redisMessageListenerContainer thread and independently runs the loop,
        // which produces multiple interleaved ReadyNodeCalculator traces. Without a token
        // it looks like "the same node is processed 3-4 times" in logs, but each traversal
        // is a legitimate, distinct arrival. This short token lets you group log lines by
        // traversal. Actual double-execution is prevented by the isPending() guard below
        // and by V2StepByStepService.executeNode's idempotency check on the snapshot.
        String traversalToken = Long.toHexString(System.nanoTime() & 0xFFFFFFL);

        // First call: reuse caller's preloaded LoadedExecution if supplied. Falls back
        // to the 3-arg overload when no preload is available so legacy mock-based tests
        // that verify the 3-arg signature stay valid.
        Set<String> currentReady = (preloaded != null)
            ? v2StepByStepService.getReadyNodes(runId, itemId, epoch, preloaded)
            : v2StepByStepService.getReadyNodes(runId, itemId, epoch);
        currentReady = filterTriggers(currentReady);
        if (alreadyDispatched != null && !alreadyDispatched.isEmpty()) {
            Set<String> filtered = new HashSet<>(currentReady);
            filtered.removeAll(alreadyDispatched);
            currentReady = filtered;
        }
        if (currentReady.isEmpty()) {
            logger.info("[AgentAsyncCompletion][t={}] No ready successors after async agent: runId={}, epoch={}, alreadyDispatched={}",
                traversalToken, runId, epoch, alreadyDispatched);
            return;
        }

        logger.info("[AgentAsyncCompletion][t={}] Executing {} ready successors: runId={}, epoch={}, ready={}",
            traversalToken, currentReady.size(), runId, epoch, currentReady);

        int iteration = 0;
        boolean yielded = false;
        // Track whether the preloaded wrapper is still good to share. We hand it to the
        // first executeNode of the very first iteration; that call persists a row, so
        // subsequent calls must re-fetch fresh state.
        com.apimarketplace.orchestrator.execution.v2.cache.ExecutionCacheManager.LoadedExecution
            shareable = preloaded;
        while (!currentReady.isEmpty() && iteration < MAX_LOOP_ITERATIONS && !yielded) {
            iteration++;
            for (String stepId : currentReady) {
                try {
                    StepByStepExecutionResult stepOutcome;
                    if (dagTriggerId != null) {
                        if (shareable != null) {
                            stepOutcome = v2StepByStepService.executeNode(
                                runId, stepId, itemId, epoch, dagTriggerId, shareable);
                        } else {
                            stepOutcome = v2StepByStepService.executeNode(
                                runId, stepId, itemId, epoch, dagTriggerId);
                        }
                    } else {
                        stepOutcome = v2StepByStepService.executeNode(runId, stepId, itemId, epoch);
                    }
                    // After the first node executes, state has mutated; further calls
                    // must read fresh data, not a stale wrapper.
                    shareable = null;

                    // A successor that itself yields (signal or another async agent) stops
                    // the loop - its own resume path will pick up where we left off.
                    // We gate on isPending() rather than isAwaitingSignal() because an
                    // async-running agent yields with status=RUNNING (not AWAITING_SIGNAL).
                    // Missing that case causes the outer while-loop to re-enter getReadyNodes,
                    // which still returns the in-flight node as ready, and the same agent
                    // gets spawned over and over - the bug observed with split successors
                    // that are themselves async agents.
                    if (stepOutcome.isPending()) {
                        logger.info("[AgentAsyncCompletion][t={}] Successor {} yielded (pending), pausing traversal: runId={}",
                            traversalToken, stepId, runId);
                        yielded = true;
                        break;
                    }
                    if (stepOutcome.isFailed()) {
                        logger.warn("[AgentAsyncCompletion][t={}] Successor {} failed: {}",
                            traversalToken, stepId, stepOutcome.getErrorMessage());
                    }
                } catch (Exception e) {
                    logger.error("[AgentAsyncCompletion][t={}] Error executing successor {}: {}",
                        traversalToken, stepId, e.getMessage(), e);
                }
            }
            if (yielded) {
                break;
            }
            currentReady = v2StepByStepService.getReadyNodes(runId, itemId, epoch);
            currentReady = filterTriggers(currentReady);
        }

        if (iteration >= MAX_LOOP_ITERATIONS) {
            logger.warn("[AgentAsyncCompletion][t={}] Reached max loop iterations ({}) for runId={}",
                traversalToken, MAX_LOOP_ITERATIONS, runId);
        }
        logger.info("[AgentAsyncCompletion][t={}] Successor loop done after {} iterations: runId={}",
            traversalToken, iteration, runId);
    }

    /**
     * Triggers must be explicitly fired and never auto-executed by a successor sweep.
     */
    private Set<String> filterTriggers(Set<String> ready) {
        if (ready == null || ready.isEmpty()) {
            return Set.of();
        }
        return ready.stream()
            .filter(id -> !id.startsWith("trigger:"))
            .collect(Collectors.toSet());
    }
}
