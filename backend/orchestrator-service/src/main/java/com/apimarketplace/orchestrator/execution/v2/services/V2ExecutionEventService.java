package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.agent.client.queue.AgentExecutionRequestMessage;
import com.apimarketplace.agent.client.queue.AgentQueueProducer;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.TriggerItem;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.completion.CompletionKind;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.persistence.WorkflowEntityResolverService;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.NodeEventEmitterService;
import com.apimarketplace.orchestrator.services.streaming.SnapshotService;
import com.apimarketplace.orchestrator.services.streaming.StepByStepEventService;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import com.apimarketplace.orchestrator.services.streaming.EdgeStatusService;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Coordinator service that bridges V2 execution engine with existing infrastructure.
 *
 * <p>This service acts as a facade, delegating to specialized services:
 * <ul>
 *   <li>{@link NodeCompletionService} - Node start/completion events</li>
 *   <li>{@link EdgeStatusEmitter} - Edge status events (RUNNING, COMPLETED, SKIPPED)</li>
 *   <li>{@link SkipPropagationService} - Skip propagation through workflow</li>
 * </ul>
 *
 * <p>This refactoring follows the Single Responsibility Principle by extracting
 * specialized concerns into focused services while maintaining the original API.
 */
@Service
public class V2ExecutionEventService {

    private static final Logger logger = LoggerFactory.getLogger(V2ExecutionEventService.class);

    private final WorkflowStreamingService streamingService;
    private final NodeEventEmitterService nodeEventEmitterService;
    private final WorkflowEventPublisher eventPublisher;
    private final EdgeStatusService edgeStatusService;
    private final SnapshotService snapshotService;
    private final StateSnapshotService stateSnapshotService;

    // Extracted services
    private final NodeCompletionService nodeCompletionService;
    private final EdgeStatusEmitter edgeStatusEmitter;
    private final StepByStepEventService stepByStepEventService;
    private final WorkflowEpochService workflowEpochService;
    private final WorkflowEntityResolverService entityResolverService;
    private final RunningNodeTracker runningNodeTracker;

    @Autowired(required = false)
    private AgentQueueProducer agentQueueProducer;

    public V2ExecutionEventService(
            WorkflowStreamingService streamingService,
            NodeEventEmitterService nodeEventEmitterService,
            WorkflowEventPublisher eventPublisher,
            EdgeStatusService edgeStatusService,
            SnapshotService snapshotService,
            StateSnapshotService stateSnapshotService,
            NodeCompletionService nodeCompletionService,
            EdgeStatusEmitter edgeStatusEmitter,
            StepByStepEventService stepByStepEventService,
            WorkflowEpochService workflowEpochService,
            WorkflowEntityResolverService entityResolverService,
            RunningNodeTracker runningNodeTracker) {
        this.streamingService = streamingService;
        this.nodeEventEmitterService = nodeEventEmitterService;
        this.eventPublisher = eventPublisher;
        this.edgeStatusService = edgeStatusService;
        this.snapshotService = snapshotService;
        this.stateSnapshotService = stateSnapshotService;
        this.nodeCompletionService = nodeCompletionService;
        this.edgeStatusEmitter = edgeStatusEmitter;
        this.stepByStepEventService = stepByStepEventService;
        this.workflowEpochService = workflowEpochService;
        this.entityResolverService = entityResolverService;
        this.runningNodeTracker = runningNodeTracker;
    }

    /**
     * Initialize streaming and persistence for a workflow execution.
     */
    public void initializeExecution(WorkflowExecution execution) {
        initializeExecution(execution, false);
    }

    /**
     * Initialize streaming and persistence for a workflow execution.
     *
     * @param execution The workflow execution
     * @param stepByStepMode Whether the workflow is in step-by-step mode
     */
    public void initializeExecution(WorkflowExecution execution, boolean stepByStepMode) {
        logger.info("🎬 [V2Event] Initializing execution: runId={}, workflowRunId={}, stepByStepMode={}, status={}, planMcps={}, planEdges={}, planTriggers={}, planCores={}, planAgents={}",
                execution.getRunId(),
                execution.getWorkflowRunId(),
                stepByStepMode,
                execution.getStatus(),
                execution.getPlan() != null ? execution.getPlan().getMcps().size() : 0,
                execution.getPlan() != null ? execution.getPlan().getEdges().size() : 0,
                execution.getPlan() != null ? execution.getPlan().getTriggers().size() : 0,
                execution.getPlan() != null ? execution.getPlan().getCores().size() : 0,
                execution.getPlan() != null ? execution.getPlan().getAgents().size() : 0);

        // Initialize streaming
        streamingService.initializeStreaming(execution);

        // Register all workflow edges for status tracking
        edgeStatusService.registerWorkflowEdges(execution);

        logger.info("✅ [V2Event] Execution initialized: runId={}", execution.getRunId());
    }

    /**
     * Initialize total items count for proper "X/Y" display in UI.
     * Should be called after trigger execution when total items count is known.
     *
     * @param execution  The workflow execution
     * @param totalItems The total number of items to be processed
     */
    public void initializeTotalItems(WorkflowExecution execution, int totalItems) {
        nodeCompletionService.initializeTotalItems(execution, totalItems);
    }

    /**
     * Emit node execution start event (per-epoch).
     *
     * @param epoch the trigger-fire epoch this node is executing under;
     *              threaded from {@code ExecutionContext.epoch()} at the
     *              UnifiedExecutionEngine call site.
     */
    public void emitNodeStart(
            WorkflowExecution execution,
            ExecutionNode node,
            TriggerItem item,
            int itemIndex,
            int epoch) {

        // Remove node from readyNodeIds in StateSnapshot.
        // In SBS mode this is already done by claimNodeForExecution(), but in auto mode
        // nothing else does it - leaving stale ready triggers in batch-update snapshots.
        stateSnapshotService.removeReadyNode(execution.getRunId(), node.getNodeId());

        // Emit incoming edge events (from predecessors to this node)
        edgeStatusEmitter.emitIncomingEdges(execution, node, itemIndex);

        // Emit node start event (persists RUNNING to DB) - thread epoch into
        // the per-epoch Redis key shape (P2.3.1).
        nodeCompletionService.emitNodeStart(execution, node, item, itemIndex, epoch);

        // Publish snapshot so frontend sees RUNNING status before node executes
        snapshotService.sendSnapshot(execution.getRunId());
    }

    /**
     * Emit node awaiting signal event.
     * Called when a node yields with AWAITING_SIGNAL status (e.g., WaitNode > 3s, UserApproval).
     * Publishes event to frontend to display the waiting state.
     */
    public void emitNodeAwaitingSignal(
            WorkflowExecution execution,
            ExecutionNode node,
            NodeExecutionResult result,
            TriggerItem item,
            int itemIndex,
            ExecutionContext context) {

        String runId = execution.getRunId();
        String nodeId = node.getNodeId();
        String signalType = result.metadata() != null
            ? String.valueOf(result.metadata().get("signal_type")) : "UNKNOWN";

        logger.info("[SignalEvent] Node awaiting signal: runId={}, nodeId={}, nodeType={}, signalType={}, itemIndex={}, expiresAt={}, metadataKeys={}",
            runId, nodeId, node.getType(), signalType, itemIndex,
            result.metadata() != null ? result.metadata().get("expires_at") : "none",
            result.metadata() != null ? result.metadata().keySet() : "null");

        // Emit step event with AWAITING_SIGNAL lifecycle
        Map<String, Object> payload = new HashMap<>();
        payload.put("signalType", signalType);
        payload.put("statusCounts", awaitingSignalStatusCounts());
        if (result.metadata() != null) {
            payload.put("expiresAt", result.metadata().get("expires_at"));
        }

        eventPublisher.emitStep(runId, nodeId, payload,
            com.apimarketplace.orchestrator.services.streaming.events.StepLifecycle.AWAITING_SIGNAL);

        // Clear in-memory running count under the per-epoch Redis key (P2.3.1).
        // The node is no longer "running", it's "awaiting signal". Without this,
        // RunningNodeTracker accumulates stale running counts across epoch cycles
        // for reusable triggers (each epoch fire increments but yield never decrements).
        // SignalResumeService.persistSignalResolutionOutput() also calls markCompleted as a safety net,
        // but that call becomes a no-op since the counter is already cleared here.
        int epoch = context != null ? context.epoch() : 0;
        runningNodeTracker.markCompleted(runId, epoch, nodeId);

        // Send updated snapshot
        snapshotService.sendSnapshot(runId);

        // If this is an AGENT_EXECUTION signal and queue mode is active, enqueue the task
        if (agentQueueProducer != null && "AGENT_EXECUTION".equals(signalType)
                && result.output() != null && result.output().get("queueMessage") != null) {
            AgentExecutionRequestMessage queueMessage =
                    (AgentExecutionRequestMessage) result.output().get("queueMessage");
            agentQueueProducer.enqueue(queueMessage);
            logger.info("[SignalEvent] Enqueued agent task to Redis queue: runId={}, nodeId={}, correlationId={}",
                    runId, nodeId, queueMessage.correlationId());
        }
    }

    private Map<String, Object> awaitingSignalStatusCounts() {
        Map<String, Object> counts = new HashMap<>();
        counts.put("running", 0);
        counts.put("completed", 0);
        counts.put("failed", 0);
        counts.put("skipped", 0);
        counts.put("awaitingSignal", 1);
        counts.put("processed", 0);
        counts.put("total", 0);
        return counts;
    }

    /**
     * Emit "node async running" event - engine-owned async I/O yield path.
     *
     * <p>Used by results created via {@link NodeExecutionResult#asyncRunning} (e.g., agent
     * execution offloaded to a Redis worker pool). Unlike {@link #emitNodeAwaitingSignal},
     * the visible status stays {@code RUNNING} so the frontend reflects intent rather than
     * the implementation mechanism. The node's start was already persisted as RUNNING by
     * {@link #emitNodeStart}; this method only enqueues the worker task and clears the
     * in-memory running tracker so the slot frees up while the worker pool processes the task.</p>
     *
     * <p>Completion is delivered later by the async-completion service, which calls back
     * into the same sync persistence pipeline as inline node execution - keeping
     * {@code selectedBranch} derivation and other agent-result enrichment in one place.</p>
     */
    public void emitNodeAsyncRunning(
            WorkflowExecution execution,
            ExecutionNode node,
            NodeExecutionResult result,
            TriggerItem item,
            int itemIndex,
            ExecutionContext context) {

        String runId = execution.getRunId();
        String nodeId = node.getNodeId();
        String correlationId = result.metadata() != null
            ? String.valueOf(result.metadata().get(NodeExecutionResult.ASYNC_CORRELATION_ID)) : "unknown";
        String agentType = result.metadata() != null
            ? String.valueOf(result.metadata().get(NodeExecutionResult.ASYNC_AGENT_TYPE)) : "unknown";

        logger.info("[AsyncRunning] Node offloaded to async worker: runId={}, nodeId={}, nodeType={}, agentType={}, correlationId={}, itemIndex={}",
            runId, nodeId, node.getType(), agentType, correlationId, itemIndex);

        // Enqueue to Redis worker pool. The queue message is supplied via result.output()
        // by the producing node (#65 will populate this in AgentNode.executeAgentAsyncQueue).
        if (agentQueueProducer != null && result.output() != null
                && result.output().get("queueMessage") instanceof AgentExecutionRequestMessage queueMessage) {
            agentQueueProducer.enqueue(queueMessage);
            logger.info("[AsyncRunning] Enqueued agent task to Redis queue: runId={}, nodeId={}, correlationId={}",
                    runId, nodeId, queueMessage.correlationId());
        }

        // DO NOT clear the running count here. The node is still executing asynchronously
        // in the worker pool - it should remain visible as RUNNING in snapshots until the
        // actual async result arrives (via AgentAsyncCompletionService → NodeCompletionService
        // → markCompleted). The previous markCompleted call here was premature: it cleared the
        // running overlay before the agent even started executing, causing the node to jump
        // from RUNNING to PENDING in the frontend snapshot (the "disappearing guardrail" bug).
        //
        // For split contexts with N items: emitNodeStart marked the node as running once (+1).
        // Each async completion will call markCompleted (-1). The first completion brings it to
        // 0, and subsequent completions are no-ops (RunningNodeTracker deletes at ≤0).
        // The node correctly shows as RUNNING until the first item completes.

        // Note: no extra step event is emitted - the start event already persisted RUNNING.
        // No extra snapshot needed either - emitNodeStart already sent one with RUNNING status.
    }

    /**
     * Emit signal resolved event.
     * Called when a signal is resolved (timer expired, user approved, webhook received).
     */
    public void emitSignalResolved(String runId, String nodeId, String resolution) {
        logger.info("[SignalEvent] Signal resolved: runId={}, nodeId={}, resolution={}",
            runId, nodeId, resolution);

        Map<String, Object> payload = new HashMap<>();
        payload.put("resolution", resolution);

        eventPublisher.emitStep(runId, nodeId, payload,
            com.apimarketplace.orchestrator.services.streaming.events.StepLifecycle.SUCCESS);

        // Send updated snapshot immediately - signal resolution is a critical event
        snapshotService.sendSnapshotImmediate(runId);
    }

    /**
     * Emit node execution completion event.
     */
    public void emitNodeComplete(
            WorkflowExecution execution,
            ExecutionNode node,
            NodeExecutionResult result,
            TriggerItem item,
            int itemIndex,
            ExecutionContext context) {
        emitNodeComplete(execution, node, result, item, itemIndex, context, CompletionKind.TERMINAL);
    }

    /**
     * Emit a NON-FINAL failed retry attempt (NodePolicy retries) - used by the engine's
     * failed-attempt listener. The terminal attempt still flows through
     * {@link #emitNodeComplete(WorkflowExecution, ExecutionNode, NodeExecutionResult,
     * TriggerItem, int, ExecutionContext)}.
     *
     * <p>Thin back-compat alias over the SINGLE parameterized pipeline with
     * {@code NON_FINAL_ATTEMPT} - the attempt's skips (no edge tail, no
     * {@code decisionEvaluated}, no snapshot push, and downstream: no StateSnapshot /
     * workflow_epochs writes, no billing, loop rows WS-only) are decided inside that
     * pipeline at the {@link CompletionKind} branch points.
     */
    public void emitNodeAttemptFailed(
            WorkflowExecution execution,
            ExecutionNode node,
            NodeExecutionResult result,
            TriggerItem item,
            int itemIndex,
            ExecutionContext context) {
        emitNodeComplete(execution, node, result, item, itemIndex, context, CompletionKind.NON_FINAL_ATTEMPT);
    }

    /**
     * SINGLE parameterized completion pipeline at the event layer - used by BOTH the
     * terminal path ({@code TERMINAL}) and the non-final NodePolicy retry-attempt path
     * ({@code NON_FINAL_ATTEMPT}, via {@link #emitNodeAttemptFailed}). Every divergence
     * between the two dispositions branches on an explicit {@link CompletionKind}
     * accessor at the line where it is decided (2026-06-10 audit item 2 - snapshot
     * pollution), so there are no mirror methods to keep in sync.
     *
     * @param kind disposition - see {@link CompletionKind} for the per-branch contract
     */
    public void emitNodeComplete(
            WorkflowExecution execution,
            ExecutionNode node,
            NodeExecutionResult result,
            TriggerItem item,
            int itemIndex,
            ExecutionContext context,
            CompletionKind kind) {

        // Extract iteration first (before any persistence) - consumed only by the
        // terminal edge tail below; the attempt path re-derives it inside
        // NodeCompletionService for its row-persistence decision.
        Integer iteration = kind.emitsEdges()
            ? nodeCompletionService.extractCurrentIteration(context, node, result)
            : null;

        // Detect branching nodes for WS event emission (not for persistence routing)
        boolean isBranchingNode = kind.emitsEdges() && isBranchingNode(node, result);

        // All nodes go through the standard persistence path:
        // DB persistence (with OutputSchemaMapper) → StateSnapshot update → streaming event.
        // Both dispositions enter the SAME NodeCompletionService → StepCompletionOrchestrator
        // pipeline (these two entry points are thin shims over it); the disposition decides
        // snapshot mutation, row persistence and billing downstream.
        if (kind == CompletionKind.TERMINAL) {
            nodeCompletionService.emitNodeComplete(execution, node, result, item, itemIndex, context);
        } else {
            nodeCompletionService.emitNodeFailedAttempt(execution, node, result, item, itemIndex, context);
        }

        // Outgoing-edge tail - TERMINAL only (CompletionKind.emitsEdges): edges record
        // the single terminal traversal, not one COMPLETED/SKIPPED set per attempt; and
        // a failed attempt selected no branch, so there is no decisionEvaluated to emit.
        if (kind.emitsEdges()) {
            // Emit edge events for outgoing connections (batched: single DB transaction for all edges)
            int epoch = context != null ? context.epoch() : 0;
            String ctxTriggerId = context != null ? context.triggerId() : null;
            edgeStatusService.beginEdgeBatch();
            try {
                edgeStatusEmitter.emitOutgoingEdges(execution, node, itemIndex, iteration, result, false, epoch, ctxTriggerId);
            } finally {
                Map<String, Map.Entry<String, Integer>> edgeBatch = edgeStatusService.flushEdgeBatch(execution.getRunId());
                recordEdgeEpochCounts(execution, edgeBatch, epoch, ctxTriggerId);
            }

            // Emit decisionEvaluated WS event for branching nodes so frontend gets
            // selectedBranch/skippedBranches data without relying on HTTP response
            if (isBranchingNode) {
                emitDecisionEvaluatedEvent(execution, node, result);
            }
        }

        // Send full snapshot from DB (direct, no batch scheduler) - TERMINAL only
        // (CompletionKind.mutatesSnapshotCounts): a non-final attempt performs no
        // StateSnapshot / workflow_epochs writes, so the DB snapshot is unchanged
        // and there is nothing to push.
        if (kind.mutatesSnapshotCounts()) {
            snapshotService.sendSnapshot(execution.getRunId());
        }
    }

    /**
     * Emit node execution completion event with explicit iteration.
     * Used for body nodes where the iteration is known from the parent context.
     */
    public void emitNodeComplete(
            WorkflowExecution execution,
            ExecutionNode node,
            NodeExecutionResult result,
            TriggerItem item,
            int itemIndex,
            ExecutionContext context,
            Integer explicitIteration) {

        // Detect branching nodes for WS event emission (not for persistence routing)
        boolean isBranchingNode = isBranchingNode(node, result);

        // All nodes go through the standard persistence path
        nodeCompletionService.emitNodeComplete(execution, node, result, item, itemIndex, context, explicitIteration);

        // Emit edge events for outgoing connections (batched: single DB transaction for all edges)
        int epoch2 = context != null ? context.epoch() : 0;
        String ctxTriggerId2 = context != null ? context.triggerId() : null;
        edgeStatusService.beginEdgeBatch();
        try {
            edgeStatusEmitter.emitOutgoingEdges(execution, node, itemIndex, explicitIteration, result, false, epoch2, ctxTriggerId2);
        } finally {
            Map<String, Map.Entry<String, Integer>> edgeBatch = edgeStatusService.flushEdgeBatch(execution.getRunId());
            recordEdgeEpochCounts(execution, edgeBatch, epoch2, ctxTriggerId2);
        }

        // Emit decisionEvaluated WS event for branching nodes
        if (isBranchingNode) {
            emitDecisionEvaluatedEvent(execution, node, result);
        }

        // Send full snapshot from DB (direct, no batch scheduler)
        snapshotService.sendSnapshot(execution.getRunId());
    }

    /**
     * Emit the cross-graph downstream events that follow a node completion, assuming
     * persistence has already been done elsewhere.
     *
     * <p>Used by {@link com.apimarketplace.orchestrator.execution.v2.async.AgentAsyncCompletionService}
     * after it hands an async agent result to {@code StepCompletionOrchestrator.completeStep}
     * (which takes care of DB persist + NodeCounts + step event). That orchestrator is a
     * node-level service - it does not know about edges, decisionEvaluated, or snapshots.
     * On the inline path, {@link #emitNodeComplete} tacks those on after persistence. The
     * async path needs the same tail work without re-persisting: this method is the
     * single-source entry point for that tail.</p>
     *
     * <p>Mirrors the post-persistence block of {@link #emitNodeComplete} exactly:</p>
     * <ol>
     *   <li>Batched outgoing edge emission ({@code beginEdgeBatch}/{@code flushEdgeBatch})</li>
     *   <li>Per-epoch edge count recording</li>
     *   <li>{@code decisionEvaluated} WS event for branching nodes (classify / decision / switch)</li>
     *   <li>Full snapshot push so the frontend edge statusCounts refresh</li>
     * </ol>
     *
     * <p>Without this tail the async classify/guardrail path silently skipped edge
     * status events, decision-evaluated events, and snapshots - observable as
     * "edges and statusCounts disappeared" once async mode was enabled in split context.</p>
     *
     * @param execution  rebuilt workflow execution (from the async completion service's
     *                   {@code rebuildExecution})
     * @param node       the agent {@link ExecutionNode} looked up from the cached execution tree
     * @param result     the persisted node result - same shape the inline path would have built
     * @param itemIndex  the per-item index this delivery covers
     * @param iteration  iteration value, or {@code null} if not applicable
     * @param epoch      epoch the node executed in (from {@code PendingAgent.epoch()})
     * @param triggerId  dag trigger id (from {@code PendingAgent.dagTriggerId()})
     */
    public void emitPostPersistenceCompletion(
            WorkflowExecution execution,
            ExecutionNode node,
            NodeExecutionResult result,
            int itemIndex,
            Integer iteration,
            int epoch,
            String triggerId) {

        boolean isBranchingNode = isBranchingNode(node, result);

        // Batched outgoing edge emission - identical to the inline tail in emitNodeComplete.
        edgeStatusService.beginEdgeBatch();
        try {
            edgeStatusEmitter.emitOutgoingEdges(execution, node, itemIndex, iteration, result, false, epoch, triggerId);
        } finally {
            Map<String, Map.Entry<String, Integer>> edgeBatch = edgeStatusService.flushEdgeBatch(execution.getRunId());
            recordEdgeEpochCounts(execution, edgeBatch, epoch, triggerId);
        }

        // Branching nodes (classify / decision / switch) need the frontend to learn
        // which branch was selected so SBS navigation and split routing work.
        if (isBranchingNode) {
            emitDecisionEvaluatedEvent(execution, node, result);
        }

        // Direct snapshot push - the runId's in-memory batcher is already drained by the
        // step event emitted inside StepCompletionOrchestrator, but only a full snapshot
        // carries the updated edge statusCounts the frontend uses for progress display.
        snapshotService.sendSnapshot(execution.getRunId());
    }

    /**
     * Emit the post-persistence downstream events for an async split batch, iterating
     * every per-item result so the frontend edge statusCounts reflect all N items
     * (not just the last arrival).
     *
     * <p>This is the async-split counterpart of {@link #emitPostPersistenceCompletion}:
     * the inline sync path emits per-item edges inside {@code SplitAwareNodeExecutor.persistItemResult}'s
     * loop, but the async path persisted each item in isolation across worker callbacks
     * and must wait for the {@code SplitCoalesceTracker} barrier to seal before it can
     * emit downstream events in a single atomic pass. A single {@code beginEdgeBatch /
     * flushEdgeBatch} scope wraps all N emissions so the frontend sees one consolidated
     * edge update (statusCounts: {completed=N} or per-branch split) rather than N
     * interleaved partial events.
     *
     * <p>For branching agents (classify / guardrail) the {@code decisionEvaluated} event
     * is emitted once PER item because each item may route to a different port (classify
     * to {@code :category_K}, guardrail to {@code :pass} or {@code :fail}). The frontend
     * needs one event per branching decision to paint the correct progress state.
     *
     * @param execution rebuilt workflow execution
     * @param node      the agent {@link ExecutionNode} looked up from the cached execution tree
     * @param batch     the sealed ordered list of per-item results - may contain null slots
     *                  if a delivery was dropped (defensive - normal path always has all items)
     * @param epoch     epoch the node executed in
     * @param triggerId dag trigger id
     */
    public void emitPostPersistenceCompletionForSplitBatch(
            WorkflowExecution execution,
            ExecutionNode node,
            List<com.apimarketplace.orchestrator.execution.v2.async.IndexedNodeResult> batch,
            int epoch,
            String triggerId) {

        if (batch == null || batch.isEmpty()) {
            logger.warn("[V2Event] emitPostPersistenceCompletionForSplitBatch called with empty batch: runId={}, nodeId={}",
                execution.getRunId(), node.getNodeId());
            return;
        }

        // Per-item batched emission. The edge batch is a Map<edgeKey, status> - a
        // single batch spanning all N items would deduplicate repeated puts to the
        // same edge key (the common case: 5 items all traversing the same pass
        // edge). Deduplication turns N increments into 1, yielding `completed=1`
        // instead of `completed=N` on the frontend edge statusCounts.
        //
        // Per-item scoping gives each item its own flush, so recordEdgeStatuses →
        // StateSnapshot.incrementEdge runs once per item per edge key and the
        // counts match the sync inline path (SplitAwareNodeExecutor.persistItemResult,
        // which naturally flushes per-item). Inside a single item, different edges
        // (pass vs fail, category_0 vs category_4) still coalesce into that item's
        // batch - the batching optimization is preserved where it was meaningful.
        //
        // NOTE: itemIndex comes from IndexedNodeResult (absolute index from the upstream
        // split), NOT from the batch position. When upstream filtering produces a sparse
        // batch (e.g. items {0,1,2,7} after an is_new decision), iterating by position
        // would attribute results to the wrong items and lose the 4th item's edges.
        for (com.apimarketplace.orchestrator.execution.v2.async.IndexedNodeResult indexed : batch) {
            NodeExecutionResult itemResult = indexed.result();
            if (itemResult == null) {
                continue;
            }
            int itemIndex = indexed.itemIndex();
            edgeStatusService.beginEdgeBatch();
            try {
                // splitScope=true - per-item skip must not poison EpochState.skippedNodeIds:
                // siblings in the batch may have routed to a wired branch that still needs to execute.
                // For FAILED branching items, the direct branch edges are still emitted here, but
                // recursive descendant propagation is left to cascadeFailureToSuccessors(perItemScope=true)
                // so the same branch target is not propagated twice.
                boolean suppressSkipPropagation = itemResult.isFailure() && node.isBranchingNode();
                edgeStatusEmitter.emitOutgoingEdges(
                    execution, node, itemIndex, null, itemResult, suppressSkipPropagation, epoch, triggerId, true);
            } finally {
                Map<String, Map.Entry<String, Integer>> edgeBatch = edgeStatusService.flushEdgeBatch(execution.getRunId());
                recordEdgeEpochCounts(execution, edgeBatch, epoch, triggerId);
            }
        }

        // Emit decisionEvaluated per item - each item's routing decision is independent
        // (item 2 pass → pass edge, item 3 fail → fail edge). Without per-item events the
        // frontend can't highlight the correct branch for every spawn.
        for (com.apimarketplace.orchestrator.execution.v2.async.IndexedNodeResult indexed : batch) {
            NodeExecutionResult itemResult = indexed.result();
            if (itemResult == null) continue;
            if (isBranchingNode(node, itemResult)) {
                emitDecisionEvaluatedEvent(execution, node, itemResult);
            }
        }

        // Single snapshot push - edge counts are now up to date for all N items.
        snapshotService.sendSnapshot(execution.getRunId());
    }

    /**
     * Re-persist a node's output to DB with a new iteration value.
     * Used when a loop terminates and the loop core's output needs to be updated
     * with the final termination state (terminated=true).
     *
     * <p>Only does persistence + state snapshot + step event.
     * Does NOT update edge status (already done during initial completion).
     */
    public void rePublishNodeOutput(
            WorkflowExecution execution,
            ExecutionNode node,
            NodeExecutionResult result,
            TriggerItem item,
            int itemIndex,
            ExecutionContext context,
            Integer explicitIteration) {
        nodeCompletionService.emitNodeComplete(execution, node, result, item, itemIndex, context, explicitIteration);
    }

    /**
     * Emit outgoing edge events for a branching node after signal resolution.
     *
     * <p>Used by SignalResumeService when a branching signal node (e.g., UserApproval) completes
     * via signal resolution. Mirrors the edge emission in emitNodeComplete() but without
     * re-persisting node completion status (already handled by persistSignalResolutionOutput).
     *
     * <p>This ensures non-selected branches are marked SKIPPED and skip propagation reaches
     * downstream nodes, same as Decision nodes during normal execution.
     *
     * @param execution The workflow execution
     * @param node The branching node (e.g., UserApprovalNode)
     * @param itemIndex The item index being processed
     * @param result Synthetic result containing "selected_port" for branch routing
     * @param epoch The epoch for epoch-scoped mutations
     * @param triggerId The trigger ID for epoch-scoped mutations (null for flat fallback)
     */
    public void emitBranchingEdgesForSignalNode(
            WorkflowExecution execution,
            ExecutionNode node,
            int itemIndex,
            NodeExecutionResult result,
            int epoch,
            String triggerId) {
        emitBranchingEdgesForSignalNode(execution, node, itemIndex, result, epoch, triggerId, false);
    }

    /**
     * Emit branching/linear edges after signal resolution.
     *
     * @param suppressSkipPropagation When true, skip propagation to downstream nodes is
     *        suppressed. Used in split context where individual item rejections should NOT
     *        mark successor nodes as SKIPPED - SplitAwareNodeExecutor handles per-item
     *        routing when it executes successor nodes after all signals resolve.
     */
    public void emitBranchingEdgesForSignalNode(
            WorkflowExecution execution,
            ExecutionNode node,
            int itemIndex,
            NodeExecutionResult result,
            int epoch,
            String triggerId,
            boolean suppressSkipPropagation) {

        String runId = execution.getRunId();
        logger.info("[V2Event] Emitting branching edges for signal node: runId={}, nodeId={}, itemIndex={}, suppressSkip={}",
            runId, node.getNodeId(), itemIndex, suppressSkipPropagation);

        // Ensure edges are registered (idempotent) - needed after server restart.
        // Only register edges, do NOT call initializeExecution() which sends an initial snapshot.
        edgeStatusService.registerWorkflowEdges(execution);

        // Emit edge events (batched: single DB transaction for all edges)
        edgeStatusService.beginEdgeBatch();
        try {
            edgeStatusEmitter.emitOutgoingEdges(execution, node, itemIndex, null, result, suppressSkipPropagation, epoch, triggerId);
        } finally {
            Map<String, Map.Entry<String, Integer>> edgeBatch = edgeStatusService.flushEdgeBatch(runId);
            recordEdgeEpochCounts(execution, edgeBatch, epoch, triggerId);
        }

        // Send snapshot immediately - branching edges are critical for frontend navigation
        snapshotService.sendSnapshotImmediate(runId);
    }

    /**
     * Emit workflow completion event.
     */
    public void emitWorkflowComplete(
            WorkflowExecution execution,
            boolean success,
            String message) {

        logger.info("🏁 [V2Event] Workflow complete: runId={}, workflowRunId={}, success={}, status={}, message={}, completedSteps={}, failedSteps={}, skippedSteps={}, totalExecutionTimeMs={}",
            execution.getRunId(),
            execution.getWorkflowRunId(),
            success,
            execution.getStatus(),
            message,
            execution.getStatistics().completedSteps(),
            execution.getStatistics().failedSteps(),
            execution.getStatistics().skippedSteps(),
            execution.getTotalExecutionTime());

        RunStatus status = success ? RunStatus.COMPLETED : RunStatus.FAILED;

        Map<String, Object> eventData = new HashMap<>();
        eventData.put("success", success);
        eventData.put("message", message);

        // Emit workflow status event
        eventPublisher.emitWorkflowStatus(
            execution.getRunId(),
            status.toString(),
            message,
            eventData,
            true  // terminal
        );

        // Send workflow statistics
        streamingService.sendWorkflowStatisticsEvent(execution);

        // Send final snapshot from DB (immediate - workflow end is critical)
        snapshotService.sendSnapshotImmediate(execution.getRunId());
    }

    /**
     * Emit step-by-step ready nodes event.
     * This event tells the frontend which nodes are ready to be executed next.
     * Used in step-by-step mode after each node completes.
     *
     * @param execution The workflow execution
     * @param readyNodeIds Set of node IDs that are ready for execution
     * @param completedNodeId The node ID that just completed (null for initial state)
     * @param workflowComplete Whether the workflow is complete
     */
    public void emitStepByStepReady(
            WorkflowExecution execution,
            Set<String> readyNodeIds,
            String completedNodeId,
            boolean workflowComplete) {

        String runId = execution.getRunId();
        logger.info("⏸️ [V2Event] Step-by-step ready: runId={}, readyNodes={}, workflowComplete={}",
            runId, readyNodeIds, workflowComplete);

        Map<String, Object> eventData = new HashMap<>();
        eventData.put("readyNodes", readyNodeIds);
        eventData.put("workflowComplete", workflowComplete);
        if (completedNodeId != null) {
            eventData.put("completedNodeId", completedNodeId);
        }

        // Emit event for step-by-step status using workflow status event
        // The event type is encoded in the status field
        eventPublisher.emitWorkflowStatus(
            runId,
            "STEP_BY_STEP_READY",
            "Ready for next node selection",
            eventData,
            workflowComplete  // terminal if workflow is complete
        );

        // Also send the dedicated "readySteps" event that the frontend listens for.
        // The workflowStatus event above uses status "STEP_BY_STEP_READY" which the frontend
        // does not recognize as a known workflow status and silently drops.
        // The "readySteps" event is handled by a separate case in the frontend's handleEvent().
        StateSnapshot snapshot = stateSnapshotService.getSnapshot(runId);
        stepByStepEventService.sendReadyStepsEvent(execution, readyNodeIds, snapshot);
    }

    /**
     * Emit step-by-step paused event.
     * This event is sent when the workflow is paused waiting for user to select next node.
     *
     * @param execution The workflow execution
     * @param readyNodeIds Set of node IDs that are ready for execution
     */
    public void emitStepByStepPaused(
            WorkflowExecution execution,
            Set<String> readyNodeIds) {

        String runId = execution.getRunId();
        logger.info("⏸️ [V2Event] Step-by-step paused: runId={}, readyNodes={}",
            runId, readyNodeIds);

        Map<String, Object> eventData = new HashMap<>();
        eventData.put("readyNodes", readyNodeIds);
        eventData.put("status", "PAUSED");
        eventData.put("message", "Waiting for user to select next node");

        // Emit event using workflow status
        eventPublisher.emitWorkflowStatus(
            runId,
            "STEP_BY_STEP_PAUSED",
            "Waiting for user to select next node",
            eventData,
            false  // not terminal
        );
    }

    /**
     * Clean up execution resources.
     */
    public void cleanupExecution(String runId) {
        logger.info("🧹 [V2Event] Cleaning up execution: runId={}", runId);
        // Purge the per-run RunningNodeTracker overlay. This is the end-of-execution
        // chokepoint: every caller runs after the execution finishes
        // (V2WorkflowFinalizer.finalizeWorkflow / finalizeWithError, after updateRunStatus;
        // V2StepByStepService.cleanup; and the tree-build failure path in
        // WorkflowExecutionServiceV2). cleanupRun had no end-of-run caller before this
        // (only the rerun cache purge reached it), so the overlay only expired via the
        // 1h TTL - a single dropped markCompleted left a node painted "running" long
        // after COMPLETED. For a reusable trigger this runs per fire and clears every
        // per-epoch overlay for the runId: correct at end-of-run, and self-healing
        // (the next markRunning re-populates) in the rare overlapping-fire case. The WS
        // and REST read paths also short-circuit the overlay for terminal runs, so the
        // display is correct even if this best-effort purge is missed.
        runningNodeTracker.cleanupRun(runId);
    }

    /**
     * Records per-epoch edge counts from a flushed batch.
     * Isolated in try/catch so failures never block execution.
     *
     * @param execution The workflow execution
     * @param edgeBatch The edge status batch
     * @param explicitEpoch The epoch from ExecutionContext (-1 = fallback to global current epoch, 0+ = use as-is)
     * @param triggerId The trigger ID for trigger-scoped recording (null = default trigger)
     */
    private void recordEdgeEpochCounts(WorkflowExecution execution, Map<String, Map.Entry<String, Integer>> edgeBatch, int explicitEpoch, String triggerId) {
        if (edgeBatch == null || edgeBatch.isEmpty()) return;
        try {
            UUID wfRunId = execution.getWorkflowRunId();
            if (wfRunId != null) {
                int epoch = (explicitEpoch >= 0)
                    ? explicitEpoch
                    : entityResolverService.getCurrentEpochFromRun(wfRunId);
                // Convert to flat maps for epoch recording (one per status).
                // Dual-status edges use "edgeKey::SKIPPED" suffix - split into separate maps
                // so the epoch table records both COMPLETED and SKIPPED rows for the same edge.
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
            }
        } catch (Exception e) {
            logger.warn("[V2Event] edge epoch counts failed: {}", e.getMessage());
        }
    }

    /**
     * Record per-epoch SKIPPED edge counts for non-routed items in a split body.
     *
     * <p>Companion to {@link com.apimarketplace.orchestrator.execution.v2.split.SplitAwareNodeExecutor#persistSkippedItemRecords}.
     * That method already writes to the top-level {@code state_snapshot.edges} map
     * via {@link com.apimarketplace.orchestrator.services.state.StateSnapshotService#recordEdgeStatusesBatch};
     * this helper additionally lands the same counts in the per-epoch
     * {@code workflow_epochs} table so the frontend epoch viewer surfaces them.
     *
     * <p>Without this, decision-branch SKIPPED edges (e.g. {@code is_new:if→exit}
     * for items that took the {@code else} branch) appeared blank in the epoch
     * selector even though they were correctly recorded at the run-level.
     *
     * <p>Audit fix MF-1, 2026-05-08.
     *
     * @param execution      workflow execution
     * @param edgeIncrements map of edgeKey → (status, count) for SKIPPED edges
     * @param epoch          from {@code context.epoch()}
     * @param triggerId      from {@code context.triggerId()}
     */
    public void recordSkipEdgesPerEpoch(
            WorkflowExecution execution,
            Map<String, Map.Entry<String, Integer>> edgeIncrements,
            int epoch,
            String triggerId) {
        if (execution == null || edgeIncrements == null || edgeIncrements.isEmpty()) {
            return;
        }
        recordEdgeEpochCounts(execution, edgeIncrements, epoch, triggerId);
    }

    /**
     * Emit edges for a per-item split-body node completion, with full lifecycle:
     * begin batch → emit → flush → record per-epoch counts.
     *
     * <p>Used by {@link com.apimarketplace.orchestrator.execution.v2.split.SplitAwareNodeExecutor#persistItemResult}.
     * Mirrors the batch+epoch flow used by {@link #emitNodeComplete} but exposes
     * the {@code splitScope}/{@code suppressSkipPropagation} parameters required
     * for per-item correctness inside a split body.
     *
     * <p><strong>Why this method exists</strong>: prior to its introduction,
     * {@code SplitAwareNodeExecutor.persistItemResult} called
     * {@link EdgeStatusEmitter#emitOutgoingEdges} directly without opening a
     * batch. {@code EdgeStatusService.emitEdgeEvent} fell into "immediate mode"
     * - it wrote edges into the top-level {@code state_snapshot.edges} map but
     * NEVER into the per-epoch {@code workflow_epochs} table. This left the
     * epoch viewer (frontend epoch selector) blind to every edge sourced from
     * a split body node (e.g. {@code check_memory→is_new},
     * {@code is_new:if→exit}, etc.). Fixed 2026-05-08.
     *
     * <p><strong>Hard-coded {@code splitScope=true}</strong>: this method is
     * split-only by design (its only caller is {@code SplitAwareNodeExecutor.persistItemResult}).
     * Calling it from a non-split context would incorrectly suppress global skip
     * propagation. The name {@code …InSplit} is a deliberate guard rail.
     *
     * @param execution            workflow execution (must be non-null)
     * @param node                 source node whose outgoing edges to emit (must be non-null)
     * @param itemIndex            split sub-item index
     * @param iteration            loop iteration if this node is in a loop body, else null
     * @param result               node execution result (must be non-null - caller filters
     *                             null/async/awaiting-signal items before reaching here)
     * @param suppressSkipPropagation true for Decision/Switch in split context - see persistItemResult
     * @param epoch                from {@code context.epoch()}
     * @param triggerId            from {@code context.triggerId()}
     */
    public void emitItemOutgoingEdgesInSplit(
            WorkflowExecution execution,
            ExecutionNode node,
            int itemIndex,
            Integer iteration,
            NodeExecutionResult result,
            boolean suppressSkipPropagation,
            int epoch,
            String triggerId) {
        if (execution == null || node == null) {
            return;
        }
        edgeStatusService.beginEdgeBatch();
        try {
            edgeStatusEmitter.emitOutgoingEdges(execution, node, itemIndex, iteration, result,
                suppressSkipPropagation, epoch, triggerId, /*splitScope*/ true);
        } finally {
            Map<String, Map.Entry<String, Integer>> edgeBatch = edgeStatusService.flushEdgeBatch(execution.getRunId());
            recordEdgeEpochCounts(execution, edgeBatch, epoch, triggerId);
        }
    }

    /**
     * Checks if a node is a branching node (Decision, Switch, Classify, or Guardrail).
     * Used only for WS event emission, not for persistence routing.
     *
     * <p>Guardrail emits a {@code decisionEvaluated} event too because it routes
     * items to {@code :pass} or {@code :fail} ports exactly like classify routes
     * to {@code :category_N}. Without this, the frontend progress view never
     * learns which guardrail branch was selected and the pass/fail edges stay
     * unhighlighted.
     */
    private boolean isBranchingNode(ExecutionNode node, NodeExecutionResult result) {
        if ((node.isDecisionNode() || node.isOptionNode()) && result.isSuccess()) {
            return true;
        }
        if (node.isAgentNode() && result.isSuccess() && result.output() != null) {
            String nodeType = (String) result.output().get("node_type");
            return "CLASSIFY".equals(nodeType) || "GUARDRAIL".equals(nodeType);
        }
        return false;
    }

    /**
     * Emit a {@code decisionEvaluated} WebSocket event for a branching node
     * (decision, switch, or classify). This gives the frontend the selected/skipped
     * branch information via the real-time channel, so SBS execution doesn't need
     * to rely on the HTTP response for this data.
     */
    private void emitDecisionEvaluatedEvent(
            WorkflowExecution execution,
            ExecutionNode node,
            NodeExecutionResult result) {
        try {
            String coreId = node.getNodeId();

            // Extract selected target and skipped targets from the execution tree
            List<ExecutionNode> nextNodes = node.getNextNodes(result);
            List<ExecutionNode> skippedNodes = node.getSkippedChildNodes(result);

            String selectedTarget = nextNodes.isEmpty() ? "" : nextNodes.get(0).getNodeId();
            Set<String> skippedTargets = skippedNodes.stream()
                    .map(ExecutionNode::getNodeId)
                    .collect(Collectors.toSet());

            // Extract evaluations from node result output
            Object evaluationsObj = result.output() != null ? result.output().get("evaluations") : null;
            List<?> evaluations = evaluationsObj instanceof List ? (List<?>) evaluationsObj : List.of();

            stepByStepEventService.sendDecisionEvaluatedEvent(
                    execution, coreId, selectedTarget, skippedTargets, evaluations);

            logger.debug("[V2Event] Emitted decisionEvaluated: runId={}, coreId={}, selected={}, skipped={}",
                    execution.getRunId(), coreId, selectedTarget, skippedTargets);
        } catch (Exception e) {
            logger.warn("[V2Event] Failed to emit decisionEvaluated event: nodeId={}, error={}",
                    node.getNodeId(), e.getMessage());
        }
    }
}
