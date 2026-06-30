package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.Edge;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService;
import com.apimarketplace.orchestrator.execution.v2.state.BackEdgeState;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import com.apimarketplace.orchestrator.services.cache.RunScopedCache;
import com.apimarketplace.orchestrator.services.context.StepOutputsWriter;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.streaming.EdgeStatusService;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import com.apimarketplace.orchestrator.services.streaming.events.LoopEventType;
import com.apimarketplace.orchestrator.utils.EdgeRefParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handler for loop iteration logic using iterate-port edges.
 *
 * A loop is defined by a Core with type="loop" which has:
 * - loopCondition: SpEL expression evaluated each iteration
 * - maxIterations: safety limit
 *
 * Loop edges use ports:
 * - "core:label:body" → first body node (forward)
 * - "core:label:exit" → node after loop (forward)
 * - "mcp:last_step" → "core:label:iterate" (loop-back)
 *
 * When the last body node completes and has an iterate edge,
 * BackEdgeHandler evaluates the loop condition and either:
 * - Resets the body subgraph and re-traverses from the body entry
 * - Terminates and lets normal successors (exit path) proceed
 */
@Component
public class BackEdgeHandler implements RunScopedCache {

    private static final Logger logger = LoggerFactory.getLogger(BackEdgeHandler.class);

    private static final String BACK_EDGE_STATE_PREFIX = "back_edge_state:";

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
     * Tracks which back-edge iterations have been claimed for processing.
     * When a forEach (split) is inside a loop body, multiple split items reach
     * the same back-edge. Only the first one should process it; the rest are no-ops.
     * Key format: "runId:edgeId:iteration"
     */
    private final Set<String> claimedBackEdgeCalls = ConcurrentHashMap.newKeySet();

    public BackEdgeHandler(TemplateEngine templateEngine, EdgeStatusService edgeStatusService,
                           WorkflowEventPublisher eventPublisher, WorkflowEpochService workflowEpochService) {
        this.templateEngine = templateEngine;
        this.edgeStatusService = edgeStatusService;
        this.eventPublisher = eventPublisher;
        this.workflowEpochService = workflowEpochService;
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
     * Check if the completed node is the source of any iterate (loop-back) edge.
     */
    public boolean hasBackEdge(ExecutionNode node, WorkflowPlan plan) {
        if (node == null || plan == null) return false;
        String nodeId = node.getNodeId();
        return !plan.getIterateEdgesForSource(nodeId).isEmpty();
    }

    /**
     * Check if the loop should continue iterating.
     */
    public boolean shouldContinue(ExecutionNode node, ExecutionContext context, WorkflowPlan plan) {
        if (node == null || context == null || plan == null) return false;
        String nodeId = node.getNodeId();
        List<Edge> iterateEdges = plan.getIterateEdgesForSource(nodeId);
        if (iterateEdges.isEmpty()) return false;

        for (Edge iterateEdge : iterateEdges) {
            String stateKey = BACK_EDGE_STATE_PREFIX + iterateEdge.getEdgeId();
            BackEdgeState state = (BackEdgeState) context.getGlobalData(stateKey).orElse(null);
            // Only skip if truly terminated (terminated flag set)
            if (state != null && state.terminated()) continue;

            // Get condition from the loop Core
            Optional<Core> loopCore = plan.findLoopCoreForIterateEdge(iterateEdge);
            String condition = loopCore.map(Core::loopCondition).orElse(null);
            int maxIterations = loopCore.map(Core::maxIterations).orElse(10);

            // Check if iterations exhausted.
            // state.iteration() is the body iteration that just completed.
            // When state is null, no back-edge has fired yet - the LoopNode initial
            // body iter=0 is the only one done, so effective iteration = 0.
            // The next body run would be at iteration+1, which must satisfy
            // (iteration + 1) < maxIterations to fit. Equivalently, skip when
            // (iteration + 1) >= maxIterations.
            int effectiveIteration = (state != null) ? state.iteration() : 0;
            if ((effectiveIteration + 1) >= maxIterations) {
                continue;
            }

            String loopCoreKey = EdgeRefParser.getNodeKey(iterateEdge.to());
            boolean conditionResult = evaluateCondition(
                condition, context, iterateEdge.getEdgeId(), loopCoreKey, effectiveIteration + 1, maxIterations);
            if (conditionResult) {
                return true;
            }
        }
        return false;
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
        List<Edge> iterateEdges = plan.getIterateEdgesForSource(sourceId);

        if (iterateEdges.isEmpty()) {
            logger.warn("[BackEdge] No iterate edges found for source: {}", sourceId);
            return context;
        }

        ExecutionContext currentContext = context;

        for (Edge iterateEdge : iterateEdges) {
            String edgeId = iterateEdge.getEdgeId();
            String stateKey = BACK_EDGE_STATE_PREFIX + edgeId;
            String loopCoreKey = EdgeRefParser.getNodeKey(iterateEdge.to());

            if (loopCoreKey == null) {
                logger.warn("[BackEdge] Could not parse loop core key from: {}", iterateEdge.to());
                continue;
            }

            // Find the loop Core to get condition and maxIterations
            Optional<Core> loopCore = plan.findLoopCoreForIterateEdge(iterateEdge);
            String condition = loopCore.map(Core::loopCondition).orElse(null);
            int maxIterations = loopCore.map(Core::maxIterations).orElse(10);
            String loopLabel = loopCore.map(Core::label).orElse("");

            // Find the body entry target (first node inside the loop body)
            String bodyTargetKey = plan.findLoopBodyTarget(loopCoreKey);
            if (bodyTargetKey == null) {
                logger.warn("[BackEdge] No body target found for loop core: {}", loopCoreKey);
                continue;
            }

            // Get or create state.
            // state.iteration() = body iteration that just completed.
            // create() returns iteration=0 because the LoopNode initial body entry
            // is iter=0; subsequent re-entries iterate at 1, 2, … via increment.
            BackEdgeState state = (BackEdgeState) currentContext.getGlobalData(stateKey).orElse(null);
            if (state == null) {
                state = BackEdgeState.create(edgeId, maxIterations, condition);
            }

            if (state.terminated()) {
                logger.info("[BackEdge] Already terminated: edgeId={}, iteration={}/{}",
                    edgeId, state.iteration(), state.maxIterations());
                continue;
            }

            // Dedup: when a forEach (split) is inside a loop body, multiple split items
            // reach the same back-edge. Only the first should process; others are no-ops.
            String claimKey = execution.getRunId() + ":" + edgeId + ":" + state.iteration();
            if (!claimedBackEdgeCalls.add(claimKey)) {
                logger.info("[BackEdge] Already claimed by split sibling: edgeId={}, iteration={}",
                    edgeId, state.iteration());
                continue;
            }

            boolean hasNextIterationRoom = state.shouldContinue();
            BackEdgeState nextState = hasNextIterationRoom ? state.incrementIteration() : null;
            boolean conditionResult = hasNextIterationRoom && evaluateCondition(
                condition, currentContext, edgeId, loopCoreKey, nextState.iteration(), maxIterations);

            // state.iteration() = body iter that just completed. Next body would run
            // at iteration+1; admissible iff (iteration + 1) < maxIterations - encoded
            // in BackEdgeState.shouldContinue() so AUTO and SBS share the same predicate.
            if (conditionResult) {
                // Continue: increment iteration, reset subgraph, re-traverse
                currentContext = currentContext.withGlobalData(stateKey, nextState);

                // Update loop node step output with current iteration
                currentContext = updateLoopStepOutput(currentContext, loopCoreKey,
                    nextState.iteration(), nextState.maxIterations(), false, null);

                logger.info("[BackEdge] Iteration starting: edgeId={}, iteration={}/{}, bodyTarget={}",
                    edgeId, nextState.iteration(), nextState.maxIterations(), bodyTargetKey);

                // Emit edge events for loop-back and body re-entry (batched for epoch recording)
                int itemIndex = currentContext.itemIndex();
                int iteration = nextState.iteration();
                final ExecutionContext ctx = currentContext;
                recordLoopEdgesInEpoch(execution, ctx, () -> {
                    // Loop-back edge: body-last-node -> loop-core (iterate)
                    edgeStatusService.markEdgeCompleted(execution, sourceId, loopCoreKey, itemIndex, iteration);
                    // Body re-entry edge: loop-core -> body-entry-node
                    edgeStatusService.markEdgeCompleted(execution, loopCoreKey, bodyTargetKey, itemIndex, iteration);
                });
                logger.info("[BackEdge] Emitted edge events: {} -> {} and {} -> {}, iteration={}",
                    sourceId, loopCoreKey, loopCoreKey, bodyTargetKey, iteration);

                // Emit iteration streaming event
                emitBackEdgeEvent(execution.getRunId(), edgeId, LoopEventType.ITERATION_COMPLETED,
                    nextState.iteration(), nextState.maxIterations(), null, loopCoreKey, loopLabel);

                // Compute subgraph between body entry and source using plan edges
                Set<String> subgraphNodes = computeSubgraphBetween(bodyTargetKey, sourceId, plan);
                // Also include ported branch targets of the source node (e.g., decision→stop).
                // computeSubgraphBetween stops at sourceNode, missing branch targets like stop
                // nodes that are inside the loop body via decision/fork ports.
                addPortedBranchDescendants(subgraphNodes, sourceId, plan);
                logger.info("[BackEdge] Resetting {} subgraph nodes: {}", subgraphNodes.size(), subgraphNodes);

                // Reset execution state for subgraph nodes
                currentContext = currentContext.withoutNodes(subgraphNodes);

                // Re-traverse from body entry node
                ExecutionNode targetNode = nodeFinder.apply(bodyTargetKey);
                if (targetNode != null) {
                    currentContext = traverser.traverse(targetNode, currentContext, execution, eventService, item);
                } else {
                    logger.error("[BackEdge] Body entry node not found: {}", bodyTargetKey);
                }
            } else {
                // Terminate: condition false or max iterations reached
                String reason = hasNextIterationRoom ? "condition_false" : "max_iterations_reached";
                BackEdgeState terminatedState = state.terminate();
                currentContext = currentContext.withGlobalData(stateKey, terminatedState);

                // Update loop node step output with termination info
                currentContext = updateLoopStepOutput(currentContext, loopCoreKey,
                    terminatedState.iteration(), terminatedState.maxIterations(), true, reason);

                logger.info("[BackEdge] Terminated: edgeId={}, iteration={}/{}, reason={}",
                    edgeId, terminatedState.iteration(), terminatedState.maxIterations(), reason);

                // Emit edge events for final loop-back and exit (batched for epoch recording)
                int itemIndex = currentContext.itemIndex();
                int iteration = terminatedState.iteration();
                String exitTargetKeyForEdge = plan.findLoopExitTarget(loopCoreKey);
                final ExecutionContext ctx2 = currentContext;
                recordLoopEdgesInEpoch(execution, ctx2, () -> {
                    // Final loop-back edge: body-last-node -> loop-core
                    edgeStatusService.markEdgeCompleted(execution, sourceId, loopCoreKey, itemIndex, iteration);
                    // Exit edge: loop-core -> exit-target
                    if (exitTargetKeyForEdge != null) {
                        edgeStatusService.markEdgeCompleted(execution, loopCoreKey, exitTargetKeyForEdge, itemIndex, iteration);
                    }
                });
                if (exitTargetKeyForEdge != null) {
                    logger.info("[BackEdge] Emitted termination edge events: {} -> {} and {} -> {}, iteration={}",
                        sourceId, loopCoreKey, loopCoreKey, exitTargetKeyForEdge, iteration);
                } else {
                    logger.info("[BackEdge] Emitted loop-back edge event: {} -> {}, iteration={}",
                        sourceId, loopCoreKey, iteration);
                }

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
                ExecutionNode loopCoreNode = nodeFinder.apply(loopCoreKey);
                if (loopCoreNode != null) {
                    Map<String, Object> terminationOutput = new LinkedHashMap<>();
                    terminationOutput.put("node_type", "LOOP");
                    terminationOutput.put("loop_node", loopCoreKey);
                    terminationOutput.put("iteration", terminatedState.iteration());
                    terminationOutput.put("maxIterations", terminatedState.maxIterations());
                    terminationOutput.put("terminated", true);
                    terminationOutput.put("reason", reason);
                    terminationOutput.put("selected_path", "exit");

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

                // Activate exit path: find and traverse the exit target node
                String exitTargetKey = plan.findLoopExitTarget(loopCoreKey);
                if (exitTargetKey != null) {
                    ExecutionNode exitNode = nodeFinder.apply(exitTargetKey);
                    if (exitNode != null) {
                        logger.info("[BackEdge] Activating exit path: {} -> {}", loopCoreKey, exitTargetKey);
                        currentContext = traverser.traverse(exitNode, currentContext, execution, eventService, item);
                    } else {
                        logger.warn("[BackEdge] Exit target node not found: {}", exitTargetKey);
                    }
                } else {
                    logger.debug("[BackEdge] No exit target defined for loop: {}", loopCoreKey);
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
        List<Edge> iterateEdges = plan.getIterateEdgesForSource(nodeId);

        if (iterateEdges.isEmpty()) {
            return StepByStepExecutionResult.success(context, result, Set.of());
        }

        ExecutionContext currentContext = context;
        Set<String> readyNodes = new HashSet<>();

        for (Edge iterateEdge : iterateEdges) {
            String edgeId = iterateEdge.getEdgeId();
            String stateKey = BACK_EDGE_STATE_PREFIX + edgeId;
            String loopCoreKey = EdgeRefParser.getNodeKey(iterateEdge.to());

            if (loopCoreKey == null) continue;

            // Get condition/maxIterations from Core
            Optional<Core> loopCore = plan.findLoopCoreForIterateEdge(iterateEdge);
            String condition = loopCore.map(Core::loopCondition).orElse(null);
            int maxIterations = loopCore.map(Core::maxIterations).orElse(10);
            String loopLabel = loopCore.map(Core::label).orElse("");

            // Find the body entry target
            String bodyTargetKey = plan.findLoopBodyTarget(loopCoreKey);
            if (bodyTargetKey == null) continue;

            // state.iteration() = body iter that just completed. create() returns
            // iteration=0 (the LoopNode initial body entry).
            BackEdgeState state = (BackEdgeState) currentContext.getGlobalData(stateKey).orElse(null);
            if (state == null) {
                state = BackEdgeState.create(edgeId, maxIterations, condition);
            }

            if (state.terminated()) {
                continue;
            }

            // In SBS mode, context is freshly loaded from DB each call. The loop core's
            // step output in DB still has iteration=1 from the initial LoopNode execution.
            // Update the in-memory context with the current iteration from BackEdgeState
            // so that condition evaluation (e.g., "{{core:loop.iteration}} < 3") sees the
            // correct iteration number, not the stale DB value.
            if (state.iteration() > 0) {
                currentContext = updateLoopStepOutput(currentContext, loopCoreKey,
                    state.iteration(), state.maxIterations(), false, null);
            }

            boolean hasNextIterationRoom = state.shouldContinue();
            BackEdgeState nextState = hasNextIterationRoom ? state.incrementIteration() : null;
            boolean conditionResult = hasNextIterationRoom && evaluateCondition(
                condition, currentContext, edgeId, loopCoreKey, nextState.iteration(), maxIterations);

            if (conditionResult) {
                currentContext = currentContext.withGlobalData(stateKey, nextState);

                // Expose the current loop iteration so a node executing in the body can stamp it
                // onto its work. The async agent path reads this (AgentNode.extractCurrentIteration)
                // to record each iteration's step at a DISTINCT iteration - without it, an async
                // agent in a loop body persists every iteration at iter=0 and the step_data
                // overwrites, under-counting executions. The key was previously read but never set.
                currentContext = currentContext.withGlobalData(CURRENT_LOOP_ITERATION_KEY, nextState.iteration());

                // Update loop node step output with current iteration
                currentContext = updateLoopStepOutput(currentContext, loopCoreKey,
                    nextState.iteration(), nextState.maxIterations(), false, null);

                // Emit edge events for loop-back and body re-entry (batched for epoch recording)
                int iteration = nextState.iteration();
                final ExecutionContext sbsCtx = currentContext;
                recordLoopEdgesInEpoch(execution, sbsCtx, () -> {
                    edgeStatusService.markEdgeCompleted(execution, nodeId, loopCoreKey, itemIndex, iteration);
                    edgeStatusService.markEdgeCompleted(execution, loopCoreKey, bodyTargetKey, itemIndex, iteration);
                });

                // Compute and reset subgraph from body entry to source
                // Use plan-based overload (not nodeMap-based) because DecisionNode/OptionNode
                // branches are not in getSuccessors() - only the plan edges capture all connections.
                Set<String> subgraphNodes = computeSubgraphBetween(bodyTargetKey, nodeId, plan);
                // Also include ported branch targets of the source node (e.g., decision→stop)
                addPortedBranchDescendants(subgraphNodes, nodeId, plan);
                currentContext = currentContext.withoutNodes(subgraphNodes);

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
                String reason = hasNextIterationRoom ? "condition_false" : "max_iterations_reached";
                BackEdgeState terminatedState = state.terminate();
                currentContext = currentContext.withGlobalData(stateKey, terminatedState);

                // Update loop node step output with termination info
                currentContext = updateLoopStepOutput(currentContext, loopCoreKey,
                    terminatedState.iteration(), terminatedState.maxIterations(), true, reason);

                // Emit edge events for final loop-back and exit (batched for epoch recording)
                int iteration = terminatedState.iteration();
                String exitTargetKey = plan.findLoopExitTarget(loopCoreKey);
                final ExecutionContext sbsCtx2 = currentContext;
                recordLoopEdgesInEpoch(execution, sbsCtx2, () -> {
                    edgeStatusService.markEdgeCompleted(execution, nodeId, loopCoreKey, itemIndex, iteration);
                    if (exitTargetKey != null) {
                        edgeStatusService.markEdgeCompleted(execution, loopCoreKey, exitTargetKey, itemIndex, iteration);
                    }
                });

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
                ExecutionNode loopCoreNode = nodeMap.get(loopCoreKey);
                if (loopCoreNode != null && eventService != null) {
                    Map<String, Object> terminationOutput = new LinkedHashMap<>();
                    terminationOutput.put("node_type", "LOOP");
                    terminationOutput.put("loop_node", loopCoreKey);
                    terminationOutput.put("iteration", terminatedState.iteration());
                    terminationOutput.put("maxIterations", terminatedState.maxIterations());
                    terminationOutput.put("terminated", true);
                    terminationOutput.put("enter_body", false);
                    terminationOutput.put("selected_path", "exit");
                    terminationOutput.put("reason", reason);

                    NodeExecutionResult terminationResult = NodeExecutionResult.success(loopCoreKey, terminationOutput);
                    TriggerItem triggerItem = TriggerItem.create("0", itemIndex, Map.of());
                    int persistenceIteration = terminalPersistenceIteration(terminatedState);
                    eventService.rePublishNodeOutput(execution, loopCoreNode, terminationResult,
                        triggerItem, 0, currentContext, persistenceIteration);
                    logger.info("[BackEdge] SBS: Re-persisted loop termination: loopCore={}, iteration={}", loopCoreKey, persistenceIteration);
                }

                // Add exit target to ready nodes
                if (exitTargetKey != null) {
                    readyNodes.add(exitTargetKey);
                    logger.info("[BackEdge] Step-by-step: exit target ready: {}", exitTargetKey);
                }

                logger.info("[BackEdge] Step-by-step terminated: edgeId={}, reason={}", edgeId, reason);
            }
        }

        return StepByStepExecutionResult.success(currentContext, result, readyNodes);
    }

    /**
     * Compute the set of node IDs in the subgraph between targetNode and sourceNode.
     * BFS from target following forward edges (from plan), collecting all nodes until reaching source (inclusive).
     *
     * Uses plan edges instead of ExecutionNode tree to avoid needing the full node map.
     */
    public Set<String> computeSubgraphBetween(String targetNodeId, String sourceNodeId, WorkflowPlan plan) {
        // Build adjacency list from plan edges (excluding iterate/loop-back edges)
        Map<String, Set<String>> successors = new HashMap<>();
        for (Edge edge : plan.getEdges()) {
            // Skip iterate-port edges (loop-back connections)
            if (edge.to() != null && "iterate".equals(EdgeRefParser.getPort(edge.to()))) continue;
            String fromKey = EdgeRefParser.getNodeKey(edge.from());
            String toKey = EdgeRefParser.getNodeKey(edge.to());
            if (fromKey != null && toKey != null) {
                successors.computeIfAbsent(fromKey, k -> new HashSet<>()).add(toKey);
            }
        }

        Set<String> subgraph = new LinkedHashSet<>();
        Queue<String> queue = new LinkedList<>();
        Set<String> visited = new HashSet<>();

        queue.add(targetNodeId);
        visited.add(targetNodeId);
        subgraph.add(targetNodeId);

        while (!queue.isEmpty()) {
            String currentId = queue.poll();

            // Don't traverse beyond source node (boundary of loop body)
            if (currentId.equals(sourceNodeId)) {
                subgraph.add(currentId);
                continue;
            }

            Set<String> succs = successors.getOrDefault(currentId, Set.of());
            for (String successorId : succs) {
                if (!visited.contains(successorId)) {
                    visited.add(successorId);
                    subgraph.add(successorId);
                    queue.add(successorId);
                }
            }
        }

        return subgraph;
    }

    /**
     * Overload that uses an ExecutionNode map for subgraph computation.
     * Used when a node map is already available.
     */
    public Set<String> computeSubgraphBetween(String targetNodeId, String sourceNodeId, Map<String, ExecutionNode> nodeMap) {
        Set<String> subgraph = new LinkedHashSet<>();
        Queue<String> queue = new LinkedList<>();
        Set<String> visited = new HashSet<>();

        queue.add(targetNodeId);
        visited.add(targetNodeId);
        subgraph.add(targetNodeId);

        while (!queue.isEmpty()) {
            String currentId = queue.poll();

            if (currentId.equals(sourceNodeId)) {
                subgraph.add(currentId);
                continue;
            }

            ExecutionNode currentNode = nodeMap.get(currentId);
            if (currentNode == null) continue;

            for (ExecutionNode successor : currentNode.getSuccessors()) {
                String successorId = successor.getNodeId();
                if (!visited.contains(successorId)) {
                    visited.add(successorId);
                    subgraph.add(successorId);
                    queue.add(successorId);
                }
            }
        }

        return subgraph;
    }

    /**
     * Add ported branch descendants of sourceNode to the subgraph.
     * When sourceNode is a decision/fork, its ported branch targets (e.g., decision:if → stop)
     * are inside the loop body but not reached by computeSubgraphBetween (which stops at source).
     * Only includes targets reachable via ported edges (with a port on the from-side),
     * not simple forward edges (which typically go outside the loop).
     */
    private void addPortedBranchDescendants(Set<String> subgraph, String sourceId, WorkflowPlan plan) {
        // Build adjacency list (excluding iterate edges)
        Map<String, Set<String>> successors = new HashMap<>();
        for (Edge edge : plan.getEdges()) {
            if (edge.to() != null && "iterate".equals(EdgeRefParser.getPort(edge.to()))) continue;
            String fromKey = EdgeRefParser.getNodeKey(edge.from());
            String toKey = EdgeRefParser.getNodeKey(edge.to());
            if (fromKey != null && toKey != null) {
                successors.computeIfAbsent(fromKey, k -> new HashSet<>()).add(toKey);
            }
        }

        // Find ported edges FROM sourceId (decision/fork branches)
        Set<String> portedTargets = new HashSet<>();
        for (Edge edge : plan.getEdges()) {
            String fromKey = EdgeRefParser.getNodeKey(edge.from());
            String fromPort = EdgeRefParser.getPort(edge.from());
            if (sourceId.equals(fromKey) && fromPort != null && !fromPort.isEmpty()) {
                String toKey = EdgeRefParser.getNodeKey(edge.to());
                String toPort = EdgeRefParser.getPort(edge.to());
                // Skip iterate edges
                if ("iterate".equals(toPort)) continue;
                if (toKey != null && !subgraph.contains(toKey)) {
                    portedTargets.add(toKey);
                }
            }
        }

        // BFS from ported targets to include all their descendants
        Queue<String> queue = new LinkedList<>(portedTargets);
        Set<String> visited = new HashSet<>(portedTargets);
        subgraph.addAll(portedTargets);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String succ : successors.getOrDefault(current, Set.of())) {
                if (!visited.contains(succ) && !subgraph.contains(succ)) {
                    visited.add(succ);
                    subgraph.add(succ);
                    queue.add(succ);
                }
            }
        }
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

        // Use loopCoreKey as the loopId for direct frontend matching
        eventPublisher.emitLoopEvent(runId, loopCoreKey, eventType, payload);
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
     * Evaluate the loop condition using TemplateEngine.
     *
     * @param condition SpEL expression from Core.loopCondition
     * @param context   execution context with step outputs
     * @param edgeId    edge identifier for state lookup
     */
    private boolean evaluateCondition(String condition, ExecutionContext context, String edgeId) {
        return evaluateCondition(condition, context, edgeId, null, null, null);
    }

    private boolean evaluateCondition(
            String condition,
            ExecutionContext context,
            String edgeId,
            String loopCoreKey,
            Integer prospectiveIteration,
            Integer maxIterations) {
        // No condition means always continue (until maxIterations)
        if (condition == null || condition.isBlank()) {
            return true;
        }

        try {
            // Build evaluation context from step outputs
            Map<String, Object> evalContext = new HashMap<>(context.getAllStepOutputs());
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
            return evalResult.result();

        } catch (Exception e) {
            logger.error("[BackEdge] Condition evaluation failed: condition={}, error={}", condition, e.getMessage());
            return false;
        }
    }
}
