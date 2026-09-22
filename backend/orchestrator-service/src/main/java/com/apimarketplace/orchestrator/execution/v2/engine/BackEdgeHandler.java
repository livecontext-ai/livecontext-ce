package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.BackEdgeSpec;
import com.apimarketplace.orchestrator.domain.workflow.BackEdgeSpecs;
import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.Edge;
import com.apimarketplace.orchestrator.domain.workflow.LoopIterationLimits;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService;
import com.apimarketplace.orchestrator.execution.v2.state.BackEdgeState;
import com.apimarketplace.orchestrator.execution.v2.state.RedisLoopIterationStore;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import com.apimarketplace.orchestrator.services.cache.RunScopedCache;
import com.apimarketplace.orchestrator.services.context.StepOutputsWriter;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.template.ReportedParams;
import com.apimarketplace.orchestrator.services.streaming.EdgeStatusService;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import com.apimarketplace.orchestrator.services.streaming.events.LoopEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drives every loop, whichever way it was authored.
 *
 * <p><b>Two shapes, one path.</b> A loop is either driven by a hub - a Core with
 * {@code type="loop"} carrying the condition and cap, entered through its {@code :body} port, left
 * through {@code :exit}, closed by an edge into its {@code :iterate} port - or declared directly on
 * an edge that points back at a node which already ran (a Decision's {@code else} returning to an
 * earlier step, with no loop node at all). {@code BackEdgeSpecs} resolves both into one
 * {@code BackEdgeSpec}; everything below consumes only that, and hub-only work is guarded on
 * {@link BackEdgeSpec#hasHub()}.
 *
 * <p><b>How an iteration runs.</b> When a node completes with no wired successor and owns a
 * loop-back, this handler evaluates the loop's condition and either resets the span
 * ({@link BackEdgeSpanResolver}) and re-traverses from the re-entry point, or terminates. The
 * loop-back is never wired as a graph edge, so the executed node graph stays acyclic - see
 * {@code WorkflowPlan.isBackEdge}.
 *
 * <p><b>How it ends.</b> {@code condition_false} and {@code iterations_exhausted} are successes.
 * {@code max_iterations_reached} - the cap firing while the loop still wanted to run - fails the
 * run and refuses the exit path, because the loop never reached its own stopping condition.
 */
@Component
public class BackEdgeHandler implements RunScopedCache {

    private static final Logger logger = LoggerFactory.getLogger(BackEdgeHandler.class);

    private static final String BACK_EDGE_STATE_PREFIX = "back_edge_state:";

    /** The author's condition said stop. Normal termination, run stays green. */
    static final String CONDITION_FALSE_REASON = "condition_false";

    /**
     * The cap WAS the declared way out ("repeat N times": a loop hub with no condition).
     * The contract was fulfilled, so the run stays green and takes the exit path.
     */
    static final String ITERATIONS_EXHAUSTED_REASON = "iterations_exhausted";

    /**
     * The cap fired while there was still a reason to iterate. The workflow's intent was NOT
     * satisfied, so the run fails rather than stopping silently mid-graph.
     */
    static final String BACK_EDGE_OVERFLOW_REASON = "max_iterations_reached";

    /** GlobalData key prefix recording an overflow, for diagnostics. */
    static final String BACK_EDGE_OVERFLOW_KEY = "back_edge_overflow:";

    /**
     * Global-data key carrying the loop's current iteration into the body, read by
     * {@code AgentNode.extractCurrentIteration} so an async agent stamps the right iteration
     * onto its persisted step (preventing per-iteration step_data overwrite at iter=0).
     */
    public static final String CURRENT_LOOP_ITERATION_KEY = "current_loop_iteration";

    /**
     * GlobalData key used to communicate the set of node IDs that were reset
     * during a step-by-step back-edge iteration. The caller (V2StepByStepService)
     * reads this key and resets those nodes in the StateSnapshot so that the next
     * getReadyNodes() call (which reconstructs context from DB) sees them as PENDING.
     */
    public static final String BACK_EDGE_RESET_NODES_KEY = "__back_edge_reset_nodes__";

    /**
     * GlobalData key for loop core output overrides. Stores Map&lt;loopCoreKey, outputMap&gt;
     * so V2StepByStepContextManager can apply updated iteration values when rebuilding
     * context from DB, avoiding stale iteration numbers in downstream conditions.
     */
    public static final String LOOP_CORE_OUTPUT_OVERRIDES_KEY = "__back_edge_loop_core_outputs__";

    private final TemplateEngine templateEngine;
    private final EdgeStatusService edgeStatusService;
    private final WorkflowEventPublisher eventPublisher;
    private final WorkflowEpochService workflowEpochService;

    /**
     * Optional so the 4-arg construction used by unit tests keeps working. Present at runtime,
     * where it un-completes the reset span in AUTO mode (see {@link #applySnapshotReset}).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.orchestrator.services.state.StateSnapshotService stateSnapshotService;

    /**
     * Source of the global iteration budget ({@code workflow.execution.default-max-iterations}
     * and {@code .max-allowed-iterations}). Optional for the same reason as above; absent falls
     * back to the historical defaults.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.orchestrator.config.WorkflowExecutionConfig executionConfig;

    /**
     * Cross-replica loop progress. Null in unit tests and whenever no Redis template is
     * present, in which case the handler falls back to the per-JVM state below.
     * See {@link RedisLoopIterationStore} for why the per-JVM state alone is not enough.
     */
    private final RedisLoopIterationStore loopIterationStore;

    /**
     * Tracks which back-edge iterations have been claimed for processing.
     * When a forEach (split) is inside a loop body, multiple split items reach
     * the same back-edge. Only the first one should process it; the rest are no-ops.
     * Key format: "runId:epoch:edgeId:iteration" - the same shape as
     * {@link RedisLoopIterationStore#claimKey}, and for the same reason.
     *
     * <p>Every epoch restarts the loop at completed-iteration 0, so without the epoch this
     * key cannot tell epoch N+1's first advance from epoch N's: the advance is taken for a
     * split sibling and dropped, and a dropped back-edge is silent (no body re-entry, no
     * exit routing, no ready nodes, no error). {@link #cleanupRun} masks it on a single JVM
     * by clearing the set on the epoch reset; with several replicas only the pod that
     * performed the reset clears, so any other pod keeps the stale entry.
     *
     * <p>Scope note, so the next reader does not over-attribute this: only the AUTO
     * traversal claims ({@link #handleBackEdge}). A loop body that YIELDS - a long Wait, an
     * approval, an interface - advances its back-edge from
     * {@link #executeBackEdgeIteration}, which does not claim, so that shape was never
     * affected by this key. Its own cross-epoch leak lived in the run-level globalData
     * mirror, see {@code V2StepByStepContextManager.getCachedGlobalData(String, int)}.
     *
     * <p>Per-JVM fallback only: {@link RedisLoopIterationStore#tryClaim} is the
     * cross-replica claim. Kept so a Redis-less deployment (and the unit tests) keep
     * the previous single-instance semantics.
     */
    private final Set<String> claimedBackEdgeCalls = ConcurrentHashMap.newKeySet();

    /** Test/back-compat constructor - no cross-replica store, per-JVM semantics only. */
    public BackEdgeHandler(TemplateEngine templateEngine, EdgeStatusService edgeStatusService,
                           WorkflowEventPublisher eventPublisher, WorkflowEpochService workflowEpochService) {
        this(templateEngine, edgeStatusService, eventPublisher, workflowEpochService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public BackEdgeHandler(TemplateEngine templateEngine, EdgeStatusService edgeStatusService,
                           WorkflowEventPublisher eventPublisher, WorkflowEpochService workflowEpochService,
                           @org.springframework.beans.factory.annotation.Autowired(required = false)
                           RedisLoopIterationStore loopIterationStore) {
        this.templateEngine = templateEngine;
        this.edgeStatusService = edgeStatusService;
        this.eventPublisher = eventPublisher;
        this.workflowEpochService = workflowEpochService;
        this.loopIterationStore = loopIterationStore;
    }

    /**
     * Merge the cross-replica progress into the locally-known {@link BackEdgeState}.
     *
     * <p>The local state comes from {@code V2StepByStepContextManager}'s per-JVM globalData
     * cache, so on a replica that has never advanced THIS loop it reads 0 (or a stale, lower
     * value) - which is exactly how a {@code maxIterations=60} loop ran 73 body iterations in
     * prod (see {@link RedisLoopIterationStore}). Redis holds what every replica has done, so
     * the shared iteration wins whenever it is ahead, and a termination published by any
     * replica is adopted immediately.
     */
    private BackEdgeState reconcileWithSharedProgress(BackEdgeState local, String runId, int epoch, String edgeId) {
        if (loopIterationStore == null || local == null) {
            return local;
        }
        RedisLoopIterationStore.LoopProgress shared = loopIterationStore.readProgress(runId, epoch, edgeId);
        if (shared == null) {
            return local;
        }
        BackEdgeState merged = local;
        if (shared.iteration() > merged.iteration()) {
            merged = new BackEdgeState(merged.edgeId(), shared.iteration(),
                merged.maxIterations(), merged.condition(), merged.terminated());
            logger.info("[BackEdge] Adopted shared iteration from another replica: edgeId={}, local={}, shared={}",
                edgeId, local.iteration(), shared.iteration());
        }
        if (shared.terminated() && !merged.terminated()) {
            merged = merged.terminate();
            logger.info("[BackEdge] Adopted termination published by another replica: edgeId={}, iteration={}",
                edgeId, merged.iteration());
        }
        return merged;
    }

    /**
     * The per-JVM claim key for one back-edge advance. Always built here, never inline: the
     * purge in {@link #clearNestedBackEdgeState} matches claims by PREFIX, so a key built in
     * two places can silently drift apart. When it did, the purge matched nothing and an inner
     * loop kept its iteration-0 claim across outer passes - its back-edge then dropped in
     * silence, which is the exact failure this key's shape exists to prevent.
     */
    static String localClaimKey(String runId, int epoch, String edgeId, int completedIteration) {
        return localClaimKeyPrefix(runId, epoch, edgeId) + completedIteration;
    }

    /** Everything in {@link #localClaimKey} that identifies the edge, without the iteration. */
    static String localClaimKeyPrefix(String runId, int epoch, String edgeId) {
        return runId + ":" + epoch + ":" + edgeId + ":";
    }

    /**
     * Cross-replica claim for one back-edge advance, falling back to the per-JVM set when no
     * shared store is wired. Both layers are consulted so a Redis outage degrades to the old
     * single-instance behavior instead of double-processing within a JVM.
     */
    private boolean claimBackEdgeAdvance(String runId, int epoch, String edgeId, int completedIteration) {
        if (!claimedBackEdgeCalls.add(localClaimKey(runId, epoch, edgeId, completedIteration))) {
            return false;
        }
        if (loopIterationStore == null) {
            return true;
        }
        return loopIterationStore.tryClaim(runId, epoch, edgeId, completedIteration);
    }

    /**
     * Clean up tracked state for a completed run.
     * Called by RunCacheRegistry during rerun/epoch reset.
     */
    @Override
    public void cleanupRun(String runId) {
        int before = claimedBackEdgeCalls.size();
        claimedBackEdgeCalls.removeIf(key -> key.startsWith(runId + ":"));
        int removed = before - claimedBackEdgeCalls.size();
        if (removed > 0) {
            logger.info("[BackEdge] Cleaned up {} claimed entries for runId={}", removed, runId);
        }
        // Deliberately NOT clearing RedisLoopIterationStore here. Its keys are EPOCH-scoped, so
        // a refire naturally starts from a fresh key, and this method is reached from
        // WorkflowResumeService.clearCachedStateForRerun - whose callers include
        // ReusableTriggerService on a refire while a PREVIOUS epoch's loop may still be mid-Wait.
        // A run-wide wipe there would reset that live epoch's counter to 0 and reproduce the
        // overrun this store exists to prevent. Abandoned keys die on their TTL.
    }

    @Override
    public String getCacheName() {
        return "BackEdgeClaimedCalls";
    }

    @Override
    public CacheDomain getDomain() {
        return CacheDomain.CONTROL_FLOW;
    }

    @Override
    public int getCacheSize() {
        return claimedBackEdgeCalls.size();
    }

    /**
     * Check if the completed node is the source of any loop-back (loop hub or declared back-edge).
     */
    public boolean hasBackEdge(ExecutionNode node, WorkflowPlan plan) {
        if (node == null || plan == null) return false;
        String nodeId = node.getNodeId();
        return !BackEdgeSpecs.forSource(plan, nodeId).isEmpty();
    }

    /**
     * Would this loop admit one more iteration?
     *
     * <p>A read-only probe: it evaluates the condition and the cap without touching state. The
     * decision that actually drives execution is taken inside {@link #handleBackEdge} and
     * {@link #executeBackEdgeIteration}, which also record it.
     */
    public boolean shouldContinue(ExecutionNode node, ExecutionContext context, WorkflowPlan plan) {
        if (node == null || context == null || plan == null) return false;
        List<BackEdgeSpec> specs = BackEdgeSpecs.forSource(plan, node.getNodeId());
        if (specs.isEmpty()) return false;

        for (BackEdgeSpec spec : specs) {
            if (!portMatches(spec, node, context)) continue;

            BackEdgeState state = (BackEdgeState) context.getGlobalData(stateKey(spec)).orElse(null);

            int maxIterations = resolveMaxIterations(spec);

            // Reconcile with what OTHER replicas have already run before answering, so this
            // predicate can never report "room left" on the sole basis of local progress.
            if (state == null) {
                state = BackEdgeState.create(spec.edgeId(), maxIterations, spec.condition());
            }
            state = reconcileWithSharedProgress(state, context.runId(), context.epoch(), spec.edgeId());

            // Only skip if truly terminated (terminated flag set)
            if (state.terminated()) continue;

            // Check if iterations exhausted.
            // state.iteration() is the body iteration that just completed.
            // When no back-edge has fired yet, the initial body entry iter=0 is the only
            // one done, so effective iteration = 0.
            // The next body run would be at iteration+1, which must satisfy
            // (iteration + 1) < maxIterations to fit. Equivalently, skip when
            // (iteration + 1) >= maxIterations.
            int effectiveIteration = state.iteration();
            if ((effectiveIteration + 1) >= maxIterations) {
                continue;
            }

            boolean conditionResult = evaluateCondition(
                spec.condition(), context, spec.edgeId(), spec.hubKey(), effectiveIteration + 1, maxIterations);
            if (conditionResult) {
                return true;
            }
        }
        return false;
    }

    private static String stateKey(BackEdgeSpec spec) {
        return BACK_EDGE_STATE_PREFIX + spec.edgeId();
    }

    /**
     * Resolve the cap for this loop-back: declared value, else the run's budget, then clamped.
     *
     * <p>This is the single place the four historical hardcoded {@code orElse(10)} defaults
     * collapsed into.
     *
     */
    private int resolveMaxIterations(BackEdgeSpec spec) {
        LoopIterationLimits limits = executionConfig != null
            ? executionConfig.resolveLoopIterationLimits(null)
            : LoopIterationLimits.FALLBACK;
        return limits.resolve(spec.maxIterationsOverride());
    }

    /**
     * Is this loop-back the one the source node actually routed to on this pass?
     *
     * <p>A back-edge hanging off a specific port (a Decision's {@code else}, an approval's
     * {@code rejected}) must only fire when THAT port was selected. Without this check the loop
     * fires whenever the source produced no wired successors for ANY reason - including when a
     * different port was chosen, or when no branch matched at all - and the workflow silently
     * iterates on a branch its condition did not select.
     *
     * <p>An unported back-edge (the {@code core:loop} hub shape) always matches.
     */
    private boolean portMatches(BackEdgeSpec spec, ExecutionNode sourceNode, ExecutionContext context) {
        String edgePort = spec.sourcePort();
        if (edgePort == null || edgePort.isBlank()) return true;
        if (sourceNode == null || !sourceNode.isBranchingNode()) return true;

        String selectedPort = sourceNode.getSelectedPort(resultFromContext(sourceNode.getNodeId(), context));
        boolean matches = edgePort.equals(selectedPort);
        if (!matches) {
            logger.info("[BackEdge] Skipping back-edge {}: edge port '{}' was not selected (selected='{}')",
                spec.edgeId(), edgePort, selectedPort);
        }
        return matches;
    }

    /**
     * Rebuild the source node's result from the context so branching nodes can report their
     * selected port. The engine stores the result under the node id before dispatching here.
     */
    private NodeExecutionResult resultFromContext(String nodeId, ExecutionContext context) {
        if (context == null) return NodeExecutionResult.success(nodeId, Map.of());
        Object stored = context.getStepOutput(nodeId).orElse(null);
        if (stored instanceof NodeExecutionResult ner) {
            return ner;
        }
        if (stored instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return NodeExecutionResult.success(nodeId, OutputUnwrapper.unwrapOutputWithBranchDetection(typed));
        }
        return NodeExecutionResult.success(nodeId, Map.of());
    }

    /**
     * Handle loop iteration in AUTO mode.
     * Evaluates condition from Core, resets body subgraph, and re-traverses from body entry.
     *
     * @param nodeFinder Function to find an ExecutionNode by ID from the execution tree
     */
    public ExecutionContext handleBackEdge(
            ExecutionNode sourceNode,
            ExecutionContext context,
            WorkflowExecution execution,
            V2ExecutionEventService eventService,
            TriggerItem item,
            java.util.function.Function<String, ExecutionNode> nodeFinder,
            TreeTraverser traverser) {

        String sourceId = sourceNode.getNodeId();
        WorkflowPlan plan = context.plan();
        List<BackEdgeSpec> specs = BackEdgeSpecs.forSource(plan, sourceId);

        if (specs.isEmpty()) {
            logger.warn("[BackEdge] No back-edges found for source: {}", sourceId);
            return context;
        }

        ExecutionContext currentContext = context;

        for (BackEdgeSpec spec : specs) {
            String edgeId = spec.edgeId();
            String stateKey = stateKey(spec);
            String loopCoreKey = spec.hubKey();
            String bodyTargetKey = spec.bodyEntryKey();
            String condition = spec.condition();
            int maxIterations = resolveMaxIterations(spec);
            String loopLabel = loopLabel(plan, spec);

            if (bodyTargetKey == null) {
                logger.warn("[BackEdge] No body entry resolved for loop-back: {}", edgeId);
                continue;
            }

            // Only iterate when the source actually routed to THIS back-edge's port.
            if (!portMatches(spec, sourceNode, currentContext)) {
                continue;
            }

            // Get or create state.
            // state.iteration() = body iteration that just completed.
            // create() returns iteration=0 because the initial body entry is iter=0;
            // subsequent re-entries iterate at 1, 2, … via increment.
            BackEdgeState state = (BackEdgeState) currentContext.getGlobalData(stateKey).orElse(null);
            if (state == null) {
                state = BackEdgeState.create(edgeId, maxIterations, condition);
            }
            // Adopt progress/termination published by any other replica BEFORE deciding.
            state = reconcileWithSharedProgress(state, execution.getRunId(), currentContext.epoch(), edgeId);

            if (state.terminated()) {
                logger.info("[BackEdge] Already terminated: edgeId={}, iteration={}/{}",
                    edgeId, state.iteration(), state.maxIterations());
                currentContext = currentContext.withGlobalData(stateKey, state);
                continue;
            }

            // Dedup: when a forEach (split) is inside a loop body, multiple split items
            // reach the same back-edge. Only the first should process; others are no-ops.
            // Claimed across replicas, not just within this JVM.
            if (!claimBackEdgeAdvance(execution.getRunId(), currentContext.epoch(), edgeId, state.iteration())) {
                logger.info("[BackEdge] Already claimed by split sibling: edgeId={}, iteration={}",
                    edgeId, state.iteration());
                continue;
            }

            boolean hasNextIterationRoom = state.shouldContinue();
            BackEdgeState nextState = hasNextIterationRoom ? state.incrementIteration() : null;
            BackEdgeConditionOutcome conditionOutcome = hasNextIterationRoom
                ? evaluateConditionDetailed(condition, currentContext, edgeId, loopCoreKey,
                    nextState.iteration(), maxIterations)
                : BackEdgeConditionOutcome.notEvaluated("iteration cap reached");
            boolean conditionResult = conditionOutcome.result();

            // state.iteration() = body iter that just completed. Next body would run
            // at iteration+1; admissible iff (iteration + 1) < maxIterations - encoded
            // in BackEdgeState.shouldContinue() so AUTO and SBS share the same predicate.
            if (conditionResult) {
                // Continue: increment iteration, reset subgraph, re-traverse
                currentContext = currentContext.withGlobalData(stateKey, nextState);
                // Publish BEFORE running the body: the body may yield (Wait/approval) and the
                // resume can land on another replica, which must see this iteration.
                if (loopIterationStore != null) {
                    loopIterationStore.recordIteration(execution.getRunId(), currentContext.epoch(), edgeId, nextState.iteration());
                }

                // Update loop node step output with current iteration (hub-driven loops only -
                // a hub-less back-edge has no controller node to carry the counter)
                if (spec.hasHub()) {
                    currentContext = updateLoopStepOutput(currentContext, loopCoreKey,
                        nextState.iteration(), nextState.maxIterations(), false, null);
                }

                // Expose the current iteration so a node executing in the body can stamp it onto
                // its work (AgentNode.extractCurrentIteration reads this on the async path).
                currentContext = currentContext.withGlobalData(
                    CURRENT_LOOP_ITERATION_KEY, nextState.iteration());

                logger.info("[BackEdge] Iteration starting: edgeId={}, iteration={}/{}, bodyTarget={}",
                    edgeId, nextState.iteration(), nextState.maxIterations(), bodyTargetKey);

                // Emit edge events for loop-back and body re-entry (batched for epoch recording)
                int itemIndex = currentContext.itemIndex();
                int iteration = nextState.iteration();
                final ExecutionContext ctx = currentContext;
                recordLoopEdgesInEpoch(execution, ctx, () -> emitIterationEdges(
                    execution, spec, sourceId, bodyTargetKey, itemIndex, iteration));

                // Emit iteration streaming event
                emitBackEdgeEvent(execution.getRunId(), edgeId, LoopEventType.ITERATION_COMPLETED,
                    nextState.iteration(), nextState.maxIterations(), null, loopCoreKey, loopLabel);

                // Nodes that re-execute this iteration - the SAME span the iteration stamp uses,
                // so a reset node always gets a distinct workflow_step_data row.
                Set<String> subgraphNodes = BackEdgeSpanResolver.resolveSpan(plan, spec);
                logger.info("[BackEdge] Resetting {} subgraph nodes: {}", subgraphNodes.size(), subgraphNodes);

                // Reset execution state for subgraph nodes
                currentContext = currentContext.withoutNodes(subgraphNodes);

                // globalData survives withoutNodes by design, so nested loop-backs inside the span
                // would keep terminated=true and run their body exactly once per outer iteration.
                currentContext = clearNestedBackEdgeState(currentContext, plan, spec, subgraphNodes, execution);

                // Publish the reset set so the caller can un-complete those nodes in the
                // StateSnapshot. Without it a body that yields AWAITING_SIGNAL resumes against a
                // snapshot that still says the body is done.
                currentContext = currentContext.withGlobalData(
                    BACK_EDGE_RESET_NODES_KEY, new HashSet<>(subgraphNodes));

                // AUTO mode re-traverses synchronously right below, so the snapshot must be
                // un-completed HERE - after the caller regains control it is already too late,
                // and a body that yields AWAITING_SIGNAL would resume against a snapshot saying
                // the body is done. (Step-by-step applies it in V2StepByStepService instead,
                // because there the caller does regain control before the next node runs.)
                applySnapshotReset(currentContext, subgraphNodes);

                // Re-traverse from body entry node
                ExecutionNode targetNode = nodeFinder.apply(bodyTargetKey);
                if (targetNode != null) {
                    currentContext = traverser.traverse(targetNode, currentContext, execution, eventService, item);
                } else {
                    logger.error("[BackEdge] Body entry node not found: {}", bodyTargetKey);
                }
            } else {
                // Terminate: condition false, or the iteration cap bit.
                String reason = terminationReason(spec, hasNextIterationRoom, currentContext, state, maxIterations);
                BackEdgeState terminatedState = state.terminate();
                currentContext = currentContext.withGlobalData(stateKey, terminatedState);

                // Update loop node step output with termination info (hub-driven loops only)
                if (spec.hasHub()) {
                    currentContext = updateLoopStepOutput(currentContext, loopCoreKey,
                        terminatedState.iteration(), terminatedState.maxIterations(), true, reason);
                }

                logger.info("[BackEdge] Terminated: edgeId={}, iteration={}/{}, reason={}",
                    edgeId, terminatedState.iteration(), terminatedState.maxIterations(), reason);

                // Emit edge events for final loop-back and exit (batched for epoch recording)
                int itemIndex = currentContext.itemIndex();
                int iteration = terminatedState.iteration();
                String exitTargetKeyForEdge = spec.exitTargetKey();
                final ExecutionContext ctx2 = currentContext;
                recordLoopEdgesInEpoch(execution, ctx2, () -> emitTerminationEdges(
                    execution, spec, sourceId, exitTargetKeyForEdge, itemIndex, iteration));

                // Emit termination streaming event
                emitBackEdgeEvent(execution.getRunId(), edgeId, LoopEventType.COMPLETED,
                    terminatedState.iteration(), terminatedState.maxIterations(), reason, loopCoreKey, loopLabel);

                // Store synthetic output for the loop (for backward compat)
                Map<String, Object> backEdgeOutput = new LinkedHashMap<>();
                backEdgeOutput.put("iteration", terminatedState.iteration());
                backEdgeOutput.put("maxIterations", terminatedState.maxIterations());
                backEdgeOutput.put("terminated", true);
                backEdgeOutput.put("reason", reason);
                currentContext = currentContext.withGlobalData("back_edge_result:" + edgeId, backEdgeOutput);

                // Re-persist the loop core's output to DB with final iteration number.
                // The initial persist (when LoopNode first executed) has terminated=false.
                // Using the final iteration number creates a NEW DB row (different unique key),
                // and StepStateBuilder.processCores() picks the last entity which has terminated=true.
                ExecutionNode loopCoreNode = spec.hasHub() ? nodeFinder.apply(loopCoreKey) : null;
                if (loopCoreNode != null) {
                    Map<String, Object> terminationOutput = new LinkedHashMap<>();
                    terminationOutput.put("node_type", "LOOP");
                    terminationOutput.put("resolved_params",
                        loopResolvedParams(loopCoreNode, terminatedState, conditionOutcome.resolved()));
                    // What the condition became on the iteration that ended the loop. It was
                    // computed every time round and kept only in a DEBUG log. The whole set
                    // enrichLoopFields reads, not half of it: this is the LAST row a looped
                    // run leaves, so it is the one the inspector shows, and a partial set
                    // left its Condition column and its evaluations table blank.
                    addLoopConditionFields(terminationOutput, loopCoreNode, terminatedState,
                        conditionOutcome, conditionResult,
                        BACK_EDGE_OVERFLOW_REASON.equals(reason));
                    terminationOutput.put("loop_node", loopCoreKey);
                    terminationOutput.put("iteration", terminatedState.iteration());
                    terminationOutput.put("maxIterations", terminatedState.maxIterations());
                    terminationOutput.put("terminated", true);
                    // On overflow the loop did NOT reach its own stopping condition, so the exit
                    // path must not be re-armed. Step-by-step rebuilds routing from this row, so
                    // leaving selected_path="exit" here would let the workflow carry on past a
                    // failure the AUTO path refuses to continue past.
                    boolean overflowed = BACK_EDGE_OVERFLOW_REASON.equals(reason);
                    terminationOutput.put("reason", reason);
                    terminationOutput.put("selected_path", overflowed ? "failed" : "exit");

                    NodeExecutionResult terminationResult = NodeExecutionResult.success(loopCoreKey, terminationOutput);
                    if (eventService != null) {
                        int persistenceIteration = terminalPersistenceIteration(terminatedState);
                        eventService.rePublishNodeOutput(execution, loopCoreNode, terminationResult,
                            item, 0, currentContext, persistenceIteration);
                        logger.info("[BackEdge] Re-persisted loop termination: loopCore={}, iteration={}", loopCoreKey, persistenceIteration);
                    } else {
                        logger.warn("[BackEdge] eventService is null, skipping re-persist for loop termination: loopCore={}", loopCoreKey);
                    }
                }

                // Budget overflow with the loop still wanting to run: the workflow's intent was
                // NOT satisfied, so the run must not finish green. The exit path is deliberately
                // NOT taken - continuing would contradict the failure, and for a hub-less
                // back-edge auto-taking the source's other port would fabricate a branch the
                // condition never selected and run downstream side effects on wrong data.
                if (BACK_EDGE_OVERFLOW_REASON.equals(reason)) {
                    currentContext = failOnIterationOverflow(
                        sourceNode, spec, terminatedState, currentContext, execution, eventService, item);
                    continue;
                }

                // Activate exit path: find and traverse the exit target node.
                // A hub-less back-edge has none: the source node's other port already routed
                // on this pass, so there is nothing left stranded.
                String exitTargetKey = spec.exitTargetKey();
                if (exitTargetKey != null) {
                    ExecutionNode exitNode = nodeFinder.apply(exitTargetKey);
                    if (exitNode != null) {
                        logger.info("[BackEdge] Activating exit path: {} -> {}", loopCoreKey, exitTargetKey);
                        currentContext = traverser.traverse(exitNode, currentContext, execution, eventService, item);
                    } else {
                        logger.warn("[BackEdge] Exit target node not found: {}", exitTargetKey);
                    }
                } else {
                    logger.debug("[BackEdge] No exit target for back-edge: {}", edgeId);
                }

                // Publish the termination LAST: it makes every other replica skip this back-edge,
                // so a crash between the flag and the exit activation would strand the run. Dying
                // BEFORE this point simply leaves another replica to re-derive and terminate again.
                if (loopIterationStore != null) {
                    loopIterationStore.markTerminated(execution.getRunId(), currentContext.epoch(), edgeId,
                        terminatedState.iteration());
                }
            }
        }

        return currentContext;
    }

    /**
     * Execute a single loop iteration in step-by-step mode.
     */
    public StepByStepExecutionResult executeBackEdgeIteration(
            ExecutionNode sourceNode,
            String nodeId,
            NodeExecutionResult result,
            ExecutionContext context,
            WorkflowExecution execution,
            V2ExecutionEventService eventService,
            TriggerItem item,
            int itemIndex,
            Map<String, ExecutionNode> nodeMap) {

        WorkflowPlan plan = context.plan();
        List<BackEdgeSpec> specs = BackEdgeSpecs.forSource(plan, nodeId);

        if (specs.isEmpty()) {
            return StepByStepExecutionResult.success(context, result, Set.of());
        }

        ExecutionContext currentContext = context;
        Set<String> readyNodes = new HashSet<>();

        for (BackEdgeSpec spec : specs) {
            String edgeId = spec.edgeId();
            String stateKey = stateKey(spec);
            String loopCoreKey = spec.hubKey();
            String bodyTargetKey = spec.bodyEntryKey();
            String condition = spec.condition();
            int maxIterations = resolveMaxIterations(spec);
            String loopLabel = loopLabel(plan, spec);

            if (bodyTargetKey == null) continue;

            // Only iterate when the source actually routed to THIS back-edge's port. SBS passes
            // the fresh result, which is more reliable than the rehydrated context.
            if (!portMatches(spec, sourceNode, result)) {
                continue;
            }

            // state.iteration() = body iter that just completed. create() returns
            // iteration=0 (the initial body entry).
            BackEdgeState state = (BackEdgeState) currentContext.getGlobalData(stateKey).orElse(null);
            if (state == null) {
                state = BackEdgeState.create(edgeId, maxIterations, condition);
            }
            // THE fix for the prod overrun: this path runs on every WAIT_TIMER / signal resume,
            // and resumes are dispatched cross-instance. Without reconciling here, each replica
            // counted only its own iterations and enforced maxIterations per-replica.
            state = reconcileWithSharedProgress(state, execution.getRunId(), currentContext.epoch(), edgeId);

            if (state.terminated()) {
                currentContext = currentContext.withGlobalData(stateKey, state);
                continue;
            }

            // NOTE: deliberately NO claim here, unlike the AUTO path (where split siblings all
            // reach the same back-edge within one pass). A claim marker is sticky, so a resume
            // that fails after claiming would leave the loop permanently stalled - worse than
            // the race it would close.
            //
            // Callers differ in how well they are already serialized:
            //  - SignalResumeService.advanceBackEdgeAfterSignalResolution holds a per-run Redis
            //    mutex, so it IS single-winner across replicas.
            //  - advanceLoopBackEdgeForAsyncCompletedNode (async agent tail of a loop body) holds
            //    only a JVM-local lock, so two replicas can read the same iteration and both
            //    advance. That costs a duplicate body run, but it can NOT inflate the ceiling:
            //    RedisLoopIterationStore's writes are monotonic, so both land on the same value.

            // In SBS mode, context is freshly loaded from DB each call. The loop core's
            // step output in DB still has iteration=1 from the initial LoopNode execution.
            // Update the in-memory context with the current iteration from BackEdgeState
            // so that condition evaluation (e.g., "{{core:loop.iteration}} < 3") sees the
            // correct iteration number, not the stale DB value.
            if (spec.hasHub() && state.iteration() > 0) {
                currentContext = updateLoopStepOutput(currentContext, loopCoreKey,
                    state.iteration(), state.maxIterations(), false, null);
            }

            boolean hasNextIterationRoom = state.shouldContinue();
            BackEdgeState nextState = hasNextIterationRoom ? state.incrementIteration() : null;
            BackEdgeConditionOutcome conditionOutcome = hasNextIterationRoom
                ? evaluateConditionDetailed(condition, currentContext, edgeId, loopCoreKey,
                    nextState.iteration(), maxIterations)
                : BackEdgeConditionOutcome.notEvaluated("iteration cap reached");
            boolean conditionResult = conditionOutcome.result();

            if (conditionResult) {
                currentContext = currentContext.withGlobalData(stateKey, nextState);
                // Publish before the body runs: the body yields on Wait/approval and the next
                // resume may land on a different replica, which reads this to keep counting.
                if (loopIterationStore != null) {
                    loopIterationStore.recordIteration(execution.getRunId(), currentContext.epoch(), edgeId, nextState.iteration());
                }

                // Expose the current loop iteration so a node executing in the body can stamp it
                // onto its work. The async agent path reads this (AgentNode.extractCurrentIteration)
                // to record each iteration's step at a DISTINCT iteration - without it, an async
                // agent in a loop body persists every iteration at iter=0 and the step_data
                // overwrites, under-counting executions. The key was previously read but never set.
                currentContext = currentContext.withGlobalData(CURRENT_LOOP_ITERATION_KEY, nextState.iteration());

                // Update loop node step output with current iteration (hub-driven loops only)
                if (spec.hasHub()) {
                    currentContext = updateLoopStepOutput(currentContext, loopCoreKey,
                        nextState.iteration(), nextState.maxIterations(), false, null);
                }

                // Emit edge events for loop-back and body re-entry (batched for epoch recording)
                int iteration = nextState.iteration();
                final ExecutionContext sbsCtx = currentContext;
                recordLoopEdgesInEpoch(execution, sbsCtx, () -> emitIterationEdges(
                    execution, spec, nodeId, bodyTargetKey, itemIndex, iteration));

                // Nodes that re-execute this iteration - the SAME span the iteration stamp uses.
                Set<String> subgraphNodes = BackEdgeSpanResolver.resolveSpan(plan, spec);
                currentContext = currentContext.withoutNodes(subgraphNodes);
                currentContext = clearNestedBackEdgeState(currentContext, plan, spec, subgraphNodes, execution);

                // Store reset node IDs in globalData so V2StepByStepService can reset
                // the StateSnapshot accordingly. Without this, the StateSnapshot still
                // has body nodes as COMPLETED, causing getReadyNodes() on the next
                // SBS call to skip them (they look "already done").
                currentContext = currentContext.withGlobalData(
                    BACK_EDGE_RESET_NODES_KEY, new java.util.HashSet<>(subgraphNodes));

                // In step-by-step mode, the body entry node becomes ready
                readyNodes.add(bodyTargetKey);

                // Emit iteration streaming event
                emitBackEdgeEvent(execution.getRunId(), edgeId, LoopEventType.ITERATION_COMPLETED,
                    nextState.iteration(), nextState.maxIterations(), null, loopCoreKey, loopLabel);

                // Note: the LOOP_CORE_OUTPUT_OVERRIDES_KEY publication used to live here.
                // It moved into updateLoopStepOutput (called above at line 438) so AUTO and SBS
                // share a single override-publish path. SplitAwareNodeExecutor.reapplyLoopCoreOverrides
                // and V2StepByStepContextManager.applyLoopCoreOutputOverrides both read this key.

                logger.info("[BackEdge] Step-by-step iteration: edgeId={}, iteration={}/{}, bodyTarget={}, resetNodes={}",
                    edgeId, nextState.iteration(), nextState.maxIterations(), bodyTargetKey, subgraphNodes.size());
            } else {
                String reason = terminationReason(spec, hasNextIterationRoom, currentContext, state, maxIterations);
                BackEdgeState terminatedState = state.terminate();
                currentContext = currentContext.withGlobalData(stateKey, terminatedState);

                // Update loop node step output with termination info (hub-driven loops only)
                if (spec.hasHub()) {
                    currentContext = updateLoopStepOutput(currentContext, loopCoreKey,
                        terminatedState.iteration(), terminatedState.maxIterations(), true, reason);
                }

                // Emit edge events for final loop-back and exit (batched for epoch recording)
                int iteration = terminatedState.iteration();
                String exitTargetKey = spec.exitTargetKey();
                final ExecutionContext sbsCtx2 = currentContext;
                recordLoopEdgesInEpoch(execution, sbsCtx2, () -> emitTerminationEdges(
                    execution, spec, nodeId, exitTargetKey, itemIndex, iteration));

                // Emit termination streaming event
                emitBackEdgeEvent(execution.getRunId(), edgeId, LoopEventType.COMPLETED,
                    terminatedState.iteration(), terminatedState.maxIterations(), reason, loopCoreKey, loopLabel);

                // Note: the LOOP_CORE_OUTPUT_OVERRIDES_KEY clear used to live here.
                // It moved into updateLoopStepOutput (called above at line 484) which removes
                // the override entry when terminated=true. Single source of truth for both AUTO and SBS.

                // Re-persist the loop core's output to DB with termination info.
                // Without this, the DB still has the initial output (enter_body=true,
                // terminated=false) from when LoopNode first executed. When getReadyNodes()
                // reconstructs context from DB, ReadyNodeCalculator would route to body
                // targets instead of exit targets, preventing the exit path from executing.
                ExecutionNode loopCoreNode = spec.hasHub() ? nodeMap.get(loopCoreKey) : null;
                if (loopCoreNode != null && eventService != null) {
                    Map<String, Object> terminationOutput = new LinkedHashMap<>();
                    terminationOutput.put("node_type", "LOOP");
                    terminationOutput.put("resolved_params",
                        loopResolvedParams(loopCoreNode, terminatedState, conditionOutcome.resolved()));
                    // What the condition became on the iteration that ended the loop. It was
                    // computed every time round and kept only in a DEBUG log. The whole set
                    // enrichLoopFields reads, not half of it: this is the LAST row a looped
                    // run leaves, so it is the one the inspector shows, and a partial set
                    // left its Condition column and its evaluations table blank.
                    addLoopConditionFields(terminationOutput, loopCoreNode, terminatedState,
                        conditionOutcome, conditionResult,
                        BACK_EDGE_OVERFLOW_REASON.equals(reason));
                    terminationOutput.put("loop_node", loopCoreKey);
                    terminationOutput.put("iteration", terminatedState.iteration());
                    terminationOutput.put("maxIterations", terminatedState.maxIterations());
                    terminationOutput.put("terminated", true);
                    // On overflow the loop did NOT reach its own stopping condition, so the exit
                    // path must not be re-armed. Step-by-step rebuilds routing from this row, so
                    // leaving selected_path="exit" here would let the workflow carry on past a
                    // failure the AUTO path refuses to continue past.
                    boolean overflowed = BACK_EDGE_OVERFLOW_REASON.equals(reason);
                    terminationOutput.put("enter_body", false);
                    terminationOutput.put("selected_path", overflowed ? "failed" : "exit");
                    terminationOutput.put("reason", reason);

                    NodeExecutionResult terminationResult = NodeExecutionResult.success(loopCoreKey, terminationOutput);
                    TriggerItem triggerItem = TriggerItem.create("0", itemIndex, Map.of());
                    int persistenceIteration = terminalPersistenceIteration(terminatedState);
                    eventService.rePublishNodeOutput(execution, loopCoreNode, terminationResult,
                        triggerItem, 0, currentContext, persistenceIteration);
                    logger.info("[BackEdge] SBS: Re-persisted loop termination: loopCore={}, iteration={}", loopCoreKey, persistenceIteration);
                }

                // Budget overflow with the loop still wanting to run: fail rather than finish
                // green, and do NOT arm the exit path (continuing would contradict the failure).
                if (BACK_EDGE_OVERFLOW_REASON.equals(reason)) {
                    currentContext = failOnIterationOverflow(
                        sourceNode, spec, terminatedState, currentContext, execution, eventService, item);
                    continue;
                }

                // Add exit target to ready nodes
                if (exitTargetKey != null) {
                    readyNodes.add(exitTargetKey);
                    logger.info("[BackEdge] Step-by-step: exit target ready: {}", exitTargetKey);
                }

                // Publish LAST - see the AUTO path: the flag short-circuits every other replica,
                // so it must never outlive a half-finished termination.
                if (loopIterationStore != null) {
                    loopIterationStore.markTerminated(execution.getRunId(), currentContext.epoch(), edgeId,
                        terminatedState.iteration());
                }

                logger.info("[BackEdge] Step-by-step terminated: edgeId={}, reason={}", edgeId, reason);
            }
        }

        return StepByStepExecutionResult.success(currentContext, result, readyNodes);
    }

    /**
     * Compute the set of node IDs in the subgraph between targetNode and sourceNode.
     *
     * @deprecated call {@link BackEdgeSpanResolver#resolveSpan} with the spec instead - it also
     *             applies the ported-branch pass, so the two callers cannot drift.
     */
    @Deprecated
    public Set<String> computeSubgraphBetween(String targetNodeId, String sourceNodeId, WorkflowPlan plan) {
        return BackEdgeSpanResolver.computeSubgraphBetween(targetNodeId, sourceNodeId, plan);
    }

    /**
     * Overload that uses an ExecutionNode map for subgraph computation.
     *
     * @deprecated see {@link #computeSubgraphBetween(String, String, WorkflowPlan)}.
     */
    @Deprecated
    public Set<String> computeSubgraphBetween(String targetNodeId, String sourceNodeId, Map<String, ExecutionNode> nodeMap) {
        return BackEdgeSpanResolver.computeSubgraphBetween(targetNodeId, sourceNodeId, nodeMap);
    }

    /**
     * Un-complete the reset span in the StateSnapshot (AUTO mode).
     *
     * <p>DAG-scoped {@code removeNodesFromDag}, never the all-DAGs {@code resetDagSnapshot}:
     * reactivating unrelated DAGs would stop the run from reaching WAITING_TRIGGER.
     *
     * <p>Optional injection so existing constructions of this handler keep working; when absent
     * the behaviour is the historical one (in-memory reset only).
     */
    private void applySnapshotReset(ExecutionContext context, Set<String> resetNodeIds) {
        if (stateSnapshotService == null || resetNodeIds == null || resetNodeIds.isEmpty()) {
            return;
        }
        String triggerId = context.triggerId();
        if (triggerId == null) {
            logger.debug("[BackEdge] No triggerId on context, skipping AUTO snapshot reset");
            return;
        }
        try {
            stateSnapshotService.removeNodesFromDag(context.runId(), triggerId, resetNodeIds);
            logger.info("[BackEdge] AUTO: reset {} body nodes in StateSnapshot: runId={}, triggerId={}",
                resetNodeIds.size(), context.runId(), triggerId);
        } catch (Exception e) {
            logger.warn("[BackEdge] AUTO snapshot reset failed: runId={}, error={}",
                context.runId(), e.getMessage());
        }
    }

    /** Human-readable label of the loop hub, empty for a hub-less back-edge. */
    private String loopLabel(WorkflowPlan plan, BackEdgeSpec spec) {
        if (!spec.hasHub()) return "";
        return plan.getCores().stream()
            .filter(Core::isLoop)
            .filter(c -> spec.hubKey().equals(c.getNormalizedKey()))
            .map(Core::label)
            .findFirst()
            .orElse("");
    }

    /**
     * Mark the edges traversed by one iteration.
     *
     * <p>A hub-driven loop shows two hops (body-last -> loop core, loop core -> body entry)
     * because the controller node sits between them. A declared back-edge is a single hop from
     * its source straight to its target, which is exactly what the builder draws.
     */
    private void emitIterationEdges(WorkflowExecution execution, BackEdgeSpec spec, String sourceId,
                                    String bodyTargetKey, int itemIndex, int iteration) {
        if (spec.hasHub()) {
            edgeStatusService.markEdgeCompleted(execution, sourceId, spec.hubKey(), itemIndex, iteration);
            edgeStatusService.markEdgeCompleted(execution, spec.hubKey(), bodyTargetKey, itemIndex, iteration);
        } else {
            edgeStatusService.markEdgeCompleted(execution, sourceId, bodyTargetKey, itemIndex, iteration);
        }
    }

    /** Mark the final loop-back hop and, for a hub-driven loop, its exit hop. */
    private void emitTerminationEdges(WorkflowExecution execution, BackEdgeSpec spec, String sourceId,
                                      String exitTargetKey, int itemIndex, int iteration) {
        if (spec.hasHub()) {
            edgeStatusService.markEdgeCompleted(execution, sourceId, spec.hubKey(), itemIndex, iteration);
            if (exitTargetKey != null) {
                edgeStatusService.markEdgeCompleted(execution, spec.hubKey(), exitTargetKey, itemIndex, iteration);
            }
        }
        // A hub-less back-edge emits nothing on termination: its edge was not traversed this pass
        // (the source routed to its other port), so marking it completed would be a lie.
    }

    /**
     * Why the loop stopped.
     *
     * <ul>
     *   <li>{@code condition_false} - the author's condition said stop. Normal, run stays green.</li>
     *   <li>{@code iterations_exhausted} - the cap WAS the declared way out ("repeat N times":
     *       a loop hub with no condition). The contract was fulfilled, run stays green.</li>
     *   <li>{@code max_iterations_reached} - the cap fired while there was still a reason to
     *       iterate. The workflow's intent was not satisfied, so the run must fail.</li>
     * </ul>
     */
    private String terminationReason(BackEdgeSpec spec, boolean hasNextIterationRoom,
                                     ExecutionContext context, BackEdgeState state, int maxIterations) {
        if (hasNextIterationRoom) {
            // Room was available and the condition still said stop.
            return CONDITION_FALSE_REASON;
        }
        if (spec.capIsDeclaredTerminator()) {
            return ITERATIONS_EXHAUSTED_REASON;
        }
        // Out of room on a loop with a live condition. Whether that is an overflow depends on
        // whether the loop still WANTED to run: the condition is not evaluated on the out-of-room
        // path, so evaluate it here rather than failing a run that would have exited cleanly on
        // its own condition anyway.
        //
        // Caveat, deliberate: the out-of-room decision can be taken from progress published by
        // ANOTHER replica (RedisLoopIterationStore), and this replica's context is then behind -
        // it may hold the outputs of iteration 5 of a loop the cluster has run 59 times. The
        // condition is evaluated against that view, so it can answer about a stale iteration.
        // The failure mode is the lenient one (green instead of failed) and it only affects the
        // REASON, never the decision to stop, which is taken from the reconciled counter above.
        boolean stillWantsToIterate = evaluateCondition(
            spec.condition(), context, spec.edgeId(), spec.hubKey(), state.iteration() + 1, maxIterations);
        return stillWantsToIterate ? BACK_EDGE_OVERFLOW_REASON : CONDITION_FALSE_REASON;
    }

    /**
     * Clear iteration state of loop-backs nested INSIDE the span being reset.
     *
     * <p>{@code withoutNodes} deliberately keeps globalData, so without this an inner loop that
     * terminated during outer iteration 1 keeps {@code terminated=true} and its body runs exactly
     * once for every later outer iteration - silently, with no error.
     */
    private ExecutionContext clearNestedBackEdgeState(ExecutionContext context, WorkflowPlan plan,
                                                      BackEdgeSpec current, Set<String> span,
                                                      WorkflowExecution execution) {
        ExecutionContext updated = context;
        for (String key : new HashSet<>(context.getGlobalDataKeys())) {
            if (!key.startsWith(BACK_EDGE_STATE_PREFIX)) continue;
            String edgeId = key.substring(BACK_EDGE_STATE_PREFIX.length());
            if (edgeId.equals(current.edgeId())) continue;

            boolean nested = BackEdgeSpecs.all(plan).stream()
                .filter(s -> s.edgeId().equals(edgeId))
                .anyMatch(s -> span.contains(s.sourceKey()));
            if (!nested) continue;

            updated = updated.withGlobalData(key, null);
            if (execution != null) {
                String prefix = localClaimKeyPrefix(execution.getRunId(), context.epoch(), edgeId);
                claimedBackEdgeCalls.removeIf(claim -> claim.startsWith(prefix));
            }
            logger.info("[BackEdge] Cleared nested back-edge state inside reset span: {}", edgeId);
        }
        return updated;
    }

    /**
     * The iteration cap fired while the loop still had a reason to run.
     *
     * <p>Re-publishes the back-edge SOURCE as FAILED so the run lands FAILED/PARTIAL_SUCCESS
     * instead of finishing green in the middle of the graph. The source's other port is NOT
     * auto-taken: that would fabricate a branch the condition never selected and run downstream
     * side effects on wrong data.
     */
    private ExecutionContext failOnIterationOverflow(ExecutionNode sourceNode, BackEdgeSpec spec,
                                                     BackEdgeState state, ExecutionContext context,
                                                     WorkflowExecution execution,
                                                     V2ExecutionEventService eventService,
                                                     TriggerItem item) {
        String message = String.format(
            "Loop-back %s stopped after %d iterations (limit %d) while its condition was still true",
            spec.edgeId(), state.iteration() + 1, state.maxIterations());
        logger.error("[BackEdge] {}", message);

        if (eventService == null || sourceNode == null) {
            return context;
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("back_edge_terminated", true);
        output.put("back_edge_id", spec.edgeId());
        output.put("reason", BACK_EDGE_OVERFLOW_REASON);
        output.put("iteration", state.iteration());
        output.put("maxIterations", state.maxIterations());

        NodeExecutionResult failure = NodeExecutionResult.failureWithOutput(
            sourceNode.getNodeId(), message, output, 0L);

        // One past the last completed body iteration: the source already owns a row at
        // state.iteration(), and a colliding row would be dropped by ON CONFLICT DO NOTHING.
        int persistenceIteration = state.iteration() + 1;
        TriggerItem triggerItem = item != null ? item : TriggerItem.create("0", context.itemIndex(), Map.of());
        eventService.rePublishNodeOutput(execution, sourceNode, failure, triggerItem,
            context.itemIndex(), context, persistenceIteration);

        return context.withGlobalData(BACK_EDGE_OVERFLOW_KEY + spec.edgeId(), message);
    }

    /** Port match against an explicit result (step-by-step path, which has the fresh result). */
    private boolean portMatches(BackEdgeSpec spec, ExecutionNode sourceNode, NodeExecutionResult result) {
        String edgePort = spec.sourcePort();
        if (edgePort == null || edgePort.isBlank()) return true;
        if (sourceNode == null || !sourceNode.isBranchingNode()) return true;

        String selectedPort = sourceNode.getSelectedPort(result);
        boolean matches = edgePort.equals(selectedPort);
        if (!matches) {
            logger.info("[BackEdge] Skipping back-edge {}: edge port '{}' was not selected (selected='{}')",
                spec.edgeId(), edgePort, selectedPort);
        }
        return matches;
    }

    /**
     * Record loop edge events in batch mode so they are also persisted to per-epoch counts.
     * Without this, loop edges (back-edge, body re-entry, exit) only go to global EdgeCounts
     * and are missing from epoch state queries used for historical epoch viewing.
     */
    private void recordLoopEdgesInEpoch(WorkflowExecution execution, ExecutionContext context, Runnable edgeCalls) {
        edgeStatusService.beginEdgeBatch();
        try {
            edgeCalls.run();
        } finally {
            Map<String, Map.Entry<String, Integer>> edgeBatch = edgeStatusService.flushEdgeBatch(execution.getRunId());
            if (edgeBatch != null && !edgeBatch.isEmpty()) {
                try {
                    int epoch = context != null ? context.epoch() : 0;
                    String triggerId = context != null ? context.triggerId() : null;
                    Map<String, String> flatCompleted = new java.util.LinkedHashMap<>();
                    Map<String, String> flatSkipped = new java.util.LinkedHashMap<>();
                    for (var entry : edgeBatch.entrySet()) {
                        String edgeKey = entry.getKey();
                        String status = entry.getValue().getKey();
                        int suffixIdx = edgeKey.indexOf("::SKIPPED");
                        if (suffixIdx > 0) {
                            flatSkipped.put(edgeKey.substring(0, suffixIdx), status);
                        } else {
                            flatCompleted.put(edgeKey, status);
                        }
                    }
                    workflowEpochService.recordEdgeCounts(execution.getRunId(), epoch, flatCompleted, triggerId);
                    workflowEpochService.recordEdgeCounts(execution.getRunId(), epoch, flatSkipped, triggerId);
                } catch (Exception e) {
                    logger.warn("[BackEdge] Failed to record loop edge epoch counts: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Emit a back-edge iteration/completion streaming event.
     * Reuses the LoopEvent infrastructure so the frontend can track iteration progress.
     */
    private void emitBackEdgeEvent(String runId, String edgeId, LoopEventType eventType,
                                    int iteration, int maxIterations, String exitReason,
                                    String loopCoreKey, String loopLabel) {
        if (eventPublisher == null) return;

        Map<String, Object> payload = new HashMap<>();
        payload.put("loopNodeId", loopCoreKey);
        payload.put("loopLabel", loopLabel);
        payload.put("backEdgeId", edgeId);
        payload.put("currentIteration", iteration);
        payload.put("completedIterations", iteration);
        payload.put("maxIterations", maxIterations);
        if (exitReason != null) {
            payload.put("exitReason", exitReason);
        }

        // Use loopCoreKey as the loopId for direct frontend matching. A hub-less back-edge has
        // no controller node, so it identifies itself by its edge id - LoopEvent REQUIRES a
        // non-null loopId and throws otherwise, which would abort the whole iteration.
        String loopId = loopCoreKey != null ? loopCoreKey : edgeId;
        eventPublisher.emitLoopEvent(runId, loopId, eventType, payload);
    }

    /**
     * Updates the loop core node's step output in the execution context.
     * This makes the iteration counter accessible via {{core:loop_name.iteration}} in conditions.
     */
    ExecutionContext updateLoopStepOutput(ExecutionContext context, String loopCoreKey,  // package-private for tests
                                                   int iteration, int maxIterations,
                                                   boolean terminated, String reason) {
        // enter_body and selected_path must match LoopNode.execute() output format
        // so that ReadyNodeCalculator -> LoopNode.getNextNodes() routes correctly.
        boolean enterBody = !terminated;
        String selectedPath = terminated ? "exit" : "body";

        Map<String, Object> wrappedOutput = buildLoopStepOutput(
            iteration, maxIterations, terminated, enterBody, selectedPath, reason);

        // Write stepOutput in-memory.
        ExecutionContext updated = context.withStepOutput(loopCoreKey, wrappedOutput);

        // ALSO publish into LOOP_CORE_OUTPUT_OVERRIDES_KEY globalData so the live
        // counter survives split×loop per-item enrichment, which clobbers stepOutputs
        // with the per-item snapshot (frozen at LoopNode.execute's initial iteration=0).
        // SplitAwareNodeExecutor.reapplyLoopCoreOverrides reads this on every per-item
        // enrich. Required for BOTH AUTO and SBS modes - without this, AUTO mode body
        // nodes inside a split see iteration=0 forever (regression caught by 3-Opus
        // audit on commit 1aee6cf85, fixed in this change).
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> overrides = new HashMap<>(
            (Map<String, Map<String, Object>>) updated.getGlobalData(LOOP_CORE_OUTPUT_OVERRIDES_KEY)
                .orElse(Map.of()));
        if (terminated) {
            overrides.remove(loopCoreKey);
        } else {
            overrides.put(loopCoreKey, wrappedOutput);
        }
        return updated.withGlobalData(LOOP_CORE_OUTPUT_OVERRIDES_KEY, overrides);
    }

    private Map<String, Object> buildLoopStepOutput(
            int iteration,
            int maxIterations,
            boolean terminated,
            boolean enterBody,
            String selectedPath,
            String reason) {
        Map<String, Object> loopOutput = new LinkedHashMap<>();
        loopOutput.put("iteration", iteration);
        loopOutput.put("maxIterations", maxIterations);
        loopOutput.put("terminated", terminated);
        loopOutput.put("enter_body", enterBody);
        loopOutput.put("selected_path", selectedPath);
        if (reason != null) {
            loopOutput.put("reason", reason);
        }

        Map<String, Object> wrappedOutput = new LinkedHashMap<>();
        wrappedOutput.put("output", loopOutput);
        wrappedOutput.put("iteration", iteration);
        wrappedOutput.put("maxIterations", maxIterations);
        wrappedOutput.put("terminated", terminated);
        wrappedOutput.put("enter_body", enterBody);
        wrappedOutput.put("selected_path", selectedPath);
        if (reason != null) {
            wrappedOutput.put("reason", reason);
        }
        return wrappedOutput;
    }

    private int terminalPersistenceIteration(BackEdgeState terminatedState) {
        // The loop controller's initial execution is persisted with iteration=0.
        // When maxIterations=1, the terminal controller pass also has last-body
        // iteration=0, so using that value would be deduped and NodeCounts would
        // no longer match workflow_step_data. Persist the terminal marker at the
        // completed body count for this single-iteration boundary.
        if (terminatedState.maxIterations() == 1 && terminatedState.iteration() == 0) {
            return 1;
        }
        return terminatedState.iteration();
    }

    /**
     * What a back-edge condition evaluated to, and what it resolved to on the way.
     *
     * <p>The resolved form was computed on every iteration and written only to a DEBUG
     * log, so the row a terminated loop leaves behind said nothing about the condition
     * that ended it. "Why did my loop stop at 3" had no evidence anywhere a user looks.
     *
     * @param resolved the expression with its references substituted, or a sentence
     *                 saying why it was not evaluated
     */
    record BackEdgeConditionOutcome(boolean result, String resolved) {
        static BackEdgeConditionOutcome notEvaluated(String why) {
            return new BackEdgeConditionOutcome(false, "(not evaluated: " + why + ")");
        }
    }

    /**
     * Evaluate the loop condition using TemplateEngine.
     *
     * @param condition            expression from the loop Core or the back-edge marker
     * @param context              execution context with step outputs
     * @param edgeId               edge identifier for state lookup
     * @param loopCoreKey          loop hub key, or null for a hub-less back-edge
     * @param prospectiveIteration the iteration the condition is being asked about
     */
    private boolean evaluateCondition(
            String condition,
            ExecutionContext context,
            String edgeId,
            String loopCoreKey,
            Integer prospectiveIteration,
            Integer maxIterations) {
        return evaluateConditionDetailed(condition, context, edgeId, loopCoreKey,
                prospectiveIteration, maxIterations).result();
    }

    private BackEdgeConditionOutcome evaluateConditionDetailed(
            String condition,
            ExecutionContext context,
            String edgeId,
            String loopCoreKey,
            Integer prospectiveIteration,
            Integer maxIterations) {
        // No condition means always continue (until maxIterations)
        if (condition == null || condition.isBlank()) {
            return new BackEdgeConditionOutcome(true, "(no condition)");
        }

        try {
            // The SAME context the loop node itself uses on entry. It was a bare copy of the raw
            // step outputs, which resolves a literal bound ("{{core:loop.iteration}} < 3") but not
            // a bound that comes from data ("... < {{core:plan.output.result.passes}}"): the extra
            // keys that make a normal template resolve were missing here. So one expression was
            // evaluated against two different contexts - true on entry, unresolvable on every
            // back-edge - and a loop whose length came from the trigger silently ignored it.
            Map<String, Object> evalContext = EvalContextBuilder.buildStandardEvalContext(context);
            if (loopCoreKey != null && prospectiveIteration != null && maxIterations != null) {
                Map<String, Object> prospectiveOutput = buildLoopStepOutput(
                    prospectiveIteration, maxIterations, false, true, "body", null);
                StepOutputsWriter.writeWithAlias(evalContext, loopCoreKey, prospectiveOutput);
            }

            // Add trigger data
            if (context.triggerData() != null) {
                evalContext.putAll(context.triggerData());
            }

            // Add loop iteration info
            String stateKey = BACK_EDGE_STATE_PREFIX + edgeId;
            BackEdgeState state = (BackEdgeState) context.getGlobalData(stateKey).orElse(null);
            if (state != null) {
                Map<String, Object> iterInfo = new HashMap<>();
                iterInfo.put("iteration", state.iteration());
                iterInfo.put("maxIterations", state.maxIterations());
                evalContext.put("_back_edge", iterInfo);
            }

            var evalResult = templateEngine.evaluateConditionWithDetailsWithMap(condition, evalContext);
            logger.debug("[BackEdge] Condition evaluated: condition={}, resolved={}, result={}",
                condition, evalResult.resolvedExpression(), evalResult.result());
            return new BackEdgeConditionOutcome(evalResult.result(), evalResult.resolvedExpression());

        } catch (Exception e) {
            logger.error("[BackEdge] Condition evaluation failed: condition={}, error={}", condition, e.getMessage());
            return new BackEdgeConditionOutcome(false, "(evaluation failed: " + e.getMessage() + ")");
        }
    }

    /**
     * The condition fields {@code StepDataPersistenceService.enrichLoopFields} reads,
     * written onto the row a terminated loop leaves behind.
     *
     * <p>Keys are the snake_case ones the enrichment reads. {@code maxIterations} is
     * also on this output in camelCase for the persisted schema, and the two are not
     * interchangeable: writing only the camelCase one left Max blank in the inspector.
     */
    void addLoopConditionFields(Map<String, Object> output,
                                        ExecutionNode loopCoreNode,
                                        BackEdgeState terminatedState,
                                        BackEdgeConditionOutcome outcome,
                                        boolean conditionResult,
                                        boolean overflowed) {
        output.put("condition_resolved", outcome.resolved());
        output.put("condition_result", conditionResult);

        String condition = null;
        int maxIterations = terminatedState != null ? terminatedState.maxIterations() : 0;
        if (loopCoreNode instanceof com.apimarketplace.orchestrator.execution.v2.nodes.LoopNode loopNode) {
            condition = loopNode.getLoopCondition();
            maxIterations = loopNode.getMaxIterations();
        }
        output.put("loop_condition", condition);
        output.put("condition_expression", condition);
        output.put("max_iterations", maxIterations);

        // The SAME port this row reports as selected_path four lines below. On an overflow
        // the loop hit its cap while its condition still wanted to iterate, which FAILS the
        // run (max_iterations_reached) and deliberately does NOT re-arm the exit. Naming
        // "exit" here would tell the reader, on the one row they open to ask why the loop
        // stopped, that the run took a port it was refused.
        String port = overflowed ? "failed" : "exit";
        boolean hasCondition = condition != null && !condition.isBlank();
        output.put("evaluations", List.of(hasCondition
            ? com.apimarketplace.orchestrator.execution.v2.nodes.BranchEvaluationReport.evaluated(
                0, port, condition, outcome.resolved(), conditionResult, true, null, List.of())
            : com.apimarketplace.orchestrator.execution.v2.nodes.BranchEvaluationReport.fallback(
                0, port, true)));
    }

    /**
     * The loop's configuration, reported the way LoopNode.execute reports it.
     *
     * <p>The termination row is re-persisted AFTER the loop ends and is the last
     * row for that node, so it is the one the inspector reads. Writing it without
     * the node's parameters left every terminated loop with an empty Params
     * column, which reads as "this loop was never configured".
     *
     * <p>The condition here is the CONFIGURED expression, not the resolved value
     * {@code LoopNode.execute} reports: this row is written after the loop ended,
     * and re-resolving then would show a value from a context the loop no longer
     * runs in. Same key, same meaning, and the honest value for this moment.
     */
    private Map<String, Object> loopResolvedParams(ExecutionNode loopCoreNode,
                                                   BackEdgeState terminatedState,
                                                   String lastResolvedCondition) {
        Map<String, Object> resolvedParams = new LinkedHashMap<>();
        if (loopCoreNode instanceof com.apimarketplace.orchestrator.execution.v2.nodes.LoopNode loopNode) {
            String condition = loopNode.getLoopCondition();
            resolvedParams.put("loopCondition", condition != null ? condition : "(none)");
            resolvedParams.put("maxIterations", loopNode.getMaxIterations());
            // The resolved form belongs to the LAST evaluation, the one that ended the
            // loop, and is reported under its own key rather than replacing the
            // configured expression: re-resolving now would read a context the loop no
            // longer runs in, which is why loopCondition deliberately stays configured.
            if (condition != null && !condition.isBlank() && lastResolvedCondition != null) {
                resolvedParams.put("lastConditionResolved", lastResolvedCondition);
            }
        } else if (terminatedState != null) {
            resolvedParams.put("maxIterations", terminatedState.maxIterations());
        }
        return ReportedParams.forReport(resolvedParams);
    }
}
