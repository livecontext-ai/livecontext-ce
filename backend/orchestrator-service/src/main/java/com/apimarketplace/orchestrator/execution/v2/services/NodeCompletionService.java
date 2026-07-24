package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.Edge;
import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeType;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.TriggerItem;
import com.apimarketplace.orchestrator.execution.v2.state.BackEdgeState;
import com.apimarketplace.conversation.client.ConversationClient;
import com.apimarketplace.orchestrator.services.completion.CompletionKind;
import com.apimarketplace.orchestrator.services.completion.StepCompletionOrchestrator;
import com.apimarketplace.orchestrator.services.completion.StepCompletionResult;
import com.apimarketplace.orchestrator.services.context.ReadinessContextCache;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import org.springframework.beans.factory.annotation.Autowired;
import com.apimarketplace.orchestrator.services.streaming.NodeEventEmitterService;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import com.apimarketplace.orchestrator.services.streaming.events.StepLifecycle;
import com.apimarketplace.orchestrator.services.streaming.context.RunNodeState.StatusCounts;
import com.apimarketplace.orchestrator.utils.EdgeRefParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Service responsible for handling node completion events.
 *
 * <p>This service manages:
 * <ul>
 *   <li>Emitting node start events (RUNNING status)</li>
 *   <li>Emitting node completion events (SUCCESS/FAILURE)</li>
 *   <li>Converting V2 results to legacy format</li>
 *   <li>Extracting iteration information from execution context</li>
 * </ul>
 *
 * <p>Part of the V2ExecutionEventService refactoring - extracted for Single Responsibility.
 *
 * @see EdgeStatusEmitter for edge status management
 * @see SkipPropagationService for skip propagation
 */
@Service
public class NodeCompletionService {

    private static final Logger logger = LoggerFactory.getLogger(NodeCompletionService.class);

    private final StepCompletionOrchestrator stepCompletionOrchestrator;
    private final NodeEventEmitterService nodeEventEmitterService;
    private final WorkflowEventPublisher eventPublisher;
    private final RunningNodeTracker runningNodeTracker;
    private final ConversationClient conversationServiceClient;

    // Piste 4 - invalidate the readiness cache on every node completion so the next
    // getReadyNodes() traversal sees fresh state. Optional injection preserves existing
    // tests that construct NodeCompletionService with the 5-arg constructor.
    @Autowired(required = false)
    private ReadinessContextCache readinessCache;

    public NodeCompletionService(
            StepCompletionOrchestrator stepCompletionOrchestrator,
            NodeEventEmitterService nodeEventEmitterService,
            WorkflowEventPublisher eventPublisher,
            RunningNodeTracker runningNodeTracker,
            ConversationClient conversationServiceClient) {
        this.stepCompletionOrchestrator = stepCompletionOrchestrator;
        this.nodeEventEmitterService = nodeEventEmitterService;
        this.eventPublisher = eventPublisher;
        this.runningNodeTracker = runningNodeTracker;
        this.conversationServiceClient = conversationServiceClient;
    }

    /**
    /**
     * Emit node execution start event (per-epoch).
     * Updates in-memory state with RUNNING status and emits streaming event.
     *
     * @param epoch the trigger-fire epoch this node is executing under; threaded
     *              from {@code ExecutionContext.epoch()} at the call site.
     */
    public void emitNodeStart(
            WorkflowExecution execution,
            ExecutionNode node,
            TriggerItem item,
            int itemIndex,
            int epoch) {

        // Early validation - fail fast if required parameters are missing
        if (execution == null || node == null) {
            logger.warn("▶️ [NodeCompletion] Skipping emitNodeStart: execution={}, node={}",
                execution != null, node != null);
            return;
        }

        String runId = execution.getRunId();
        String nodeId = node.getNodeId();
        String nodeLabel = extractLabel(nodeId);

        logger.info("▶️ [NodeCompletion] Node start: nodeId={}, type={}, runId={}, item={}, predecessors={}, successorCount={}",
            nodeId, node.getType(), runId, itemIndex,
            node.getPredecessorIds(),
            node.getSuccessors().size());

        // Record RUNNING status in NodeEventStore for accurate statusCounts
        // This ensures the UI shows the node as running with correct counts
        StatusCounts counts = nodeEventEmitterService.recordNodeExecution(
            runId, nodeLabel, itemIndex, 0, "RUNNING");

        // Build event data - use RUNNING status so UI shows node as active
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("nodeId", nodeId);
        eventData.put("label", nodeLabel);
        eventData.put("itemIndex", itemIndex);
        eventData.put("status", "RUNNING");
        if (counts != null) {
            eventData.put("statusCounts", counts.toMap());
        }

        // Emit streaming event with RUNNING lifecycle
        eventPublisher.emitStep(
            runId,
            nodeId,
            eventData,
            StepLifecycle.RUNNING
        );

        // Track RUNNING in-memory under the per-epoch Redis key (P2.3.1).
        // {orchestrator:running:{runId}:{epoch}} HASH<nodeId, count>. The
        // deferred-reset gate at ReusableTriggerService:1614 reads exactly
        // this key shape via getRunningCountsOrThrow(runId, epoch).
        runningNodeTracker.markRunning(runId, epoch, nodeId);
    }

    /**
     * Emit node execution completion event (auto-extract iteration).
     *
     * @param execution The workflow execution
     * @param node The execution node that completed
     * @param result The execution result
     * @param item The trigger item processed
     * @param itemIndex The index of the item
     * @param context The execution context
     */
    public StepCompletionResult emitNodeComplete(
            WorkflowExecution execution,
            ExecutionNode node,
            NodeExecutionResult result,
            TriggerItem item,
            int itemIndex,
            ExecutionContext context) {
        Integer iteration = extractCurrentIteration(context, node, result);
        return emitNodeComplete(execution, node, result, item, itemIndex, context, iteration);
    }

    /**
     * Emit node execution completion event with explicit iteration.
     *
     * @param execution The workflow execution
     * @param node The execution node that completed
     * @param result The execution result
     * @param item The trigger item processed
     * @param itemIndex The index of the item
     * @param context The execution context
     * @param explicitIteration The iteration number (can be null)
     */
    public StepCompletionResult emitNodeComplete(
            WorkflowExecution execution,
            ExecutionNode node,
            NodeExecutionResult result,
            TriggerItem item,
            int itemIndex,
            ExecutionContext context,
            Integer explicitIteration) {
        return emitNodeComplete(execution, node, result, item, itemIndex, context,
            explicitIteration, CompletionKind.TERMINAL, false);
    }

    /**
     * Per-item terminal completion that suppresses the node-level EpochState mark
     * (StepCompletionContext.suppressGlobalMark, Phase 2.E). Used by the per-item
     * continuation walks (approval continuationMode=per_item in a split): each item's
     * row / NodeCounts / billing / WS event land normally, but the node-level
     * completedNodeIds/failedNodeIds mark is written ONCE at seal via
     * {@code StepCompletionOrchestrator.recordSplitAggregateIfMissing} - exactly like
     * the split-async path.
     */
    public StepCompletionResult emitNodeCompletePerItem(
            WorkflowExecution execution,
            ExecutionNode node,
            NodeExecutionResult result,
            TriggerItem item,
            int itemIndex,
            ExecutionContext context) {
        Integer iteration = extractCurrentIteration(context, node, result);
        return emitNodeComplete(execution, node, result, item, itemIndex, context,
            iteration, CompletionKind.TERMINAL, true);
    }

    /**
     * SINGLE parameterized completion pipeline at the node-completion layer - used by
     * BOTH the terminal path ({@link #emitNodeComplete(WorkflowExecution, ExecutionNode,
     * NodeExecutionResult, TriggerItem, int, ExecutionContext, Integer)} →
     * {@code TERMINAL}) and the non-final NodePolicy retry-attempt path
     * ({@link #emitNodeFailedAttempt} → {@code NON_FINAL_ATTEMPT}). Every divergence
     * between the two dispositions branches on an explicit {@link CompletionKind}
     * accessor at the line where it is decided, so there are no mirror methods to keep
     * in sync. Downstream, both dispositions enter the same
     * {@link StepCompletionOrchestrator#complete(com.apimarketplace.orchestrator.services.completion.StepCompletionContext, String, CompletionKind, boolean)}
     * pipeline (the {@code completeStep}/{@code completeAttempt} entry points used below
     * are thin back-compat shims over it).
     *
     * @param explicitIteration the loop iteration stamp (null outside loops) - the
     *                          public entry points derive it via
     *                          {@link #extractCurrentIteration} or pass it explicitly
     * @param kind              disposition - see {@link CompletionKind} for the
     *                          per-branch contract
     */
    public StepCompletionResult emitNodeComplete(
            WorkflowExecution execution,
            ExecutionNode node,
            NodeExecutionResult result,
            TriggerItem item,
            int itemIndex,
            ExecutionContext context,
            Integer explicitIteration,
            CompletionKind kind) {
        return emitNodeComplete(execution, node, result, item, itemIndex, context, explicitIteration, kind, false);
    }

    /**
     * Full pipeline with the suppressGlobalMark pass-through (see
     * {@link #emitNodeCompletePerItem}). suppressGlobalMark only applies to the
     * TERMINAL disposition - attempts never mutate the snapshot anyway.
     */
    public StepCompletionResult emitNodeComplete(
            WorkflowExecution execution,
            ExecutionNode node,
            NodeExecutionResult result,
            TriggerItem item,
            int itemIndex,
            ExecutionContext context,
            Integer explicitIteration,
            CompletionKind kind,
            boolean suppressGlobalMark) {

        // Early validation - fail fast if required parameters are missing
        if (execution == null || node == null || result == null) {
            if (kind == CompletionKind.TERMINAL) {
                logger.warn("✅ [NodeCompletion] Skipping emitNodeComplete: execution={}, node={}, result={}",
                    execution != null, node != null, result != null);
            } else {
                logger.warn("🔁 [NodeCompletion] Skipping emitNodeFailedAttempt: execution={}, node={}, result={}",
                    execution != null, node != null, result != null);
            }
            return null;
        }

        String nodeId = node.getNodeId();
        String nodeLabel = extractLabel(nodeId);
        Integer iteration = explicitIteration;
        boolean loopContext = iteration != null;

        if (kind == CompletionKind.TERMINAL) {
            logger.info("✅ [NodeCompletion] Node complete: nodeId={}, type={}, status={}, success={}, collecting={}, iteration={}, durationMs={}, outputKeys={}, errorMessage={}, runId={}",
                nodeId, node.getType(), result.status(), result.isSuccess(), result.isCollecting(), iteration,
                result.durationMs(),
                result.output() != null ? result.output().keySet() : "null",
                result.errorMessage().orElse("none"),
                execution.getRunId());
        } else {
            logger.info("🔁 [NodeCompletion] Non-final failed attempt: nodeId={}, attempt={}/{}, loopContext={}, iteration={}, itemIndex={}, runId={}",
                nodeId,
                result.metadata() != null ? result.metadata().get(com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys.POLICY_ATTEMPT) : "?",
                result.metadata() != null ? result.metadata().get(com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys.POLICY_MAX_ATTEMPTS) : "?",
                loopContext, iteration, itemIndex, execution.getRunId());
        }

        // COLLECTING status = aggregate node still collecting items
        // Don't persist intermediate results - only the final aggregated result.
        // (Non-final attempts are always failures, never COLLECTING - no-op for them.)
        if (result.isCollecting()) {
            logger.info("⏳ [NodeCompletion] Skipping persistence for COLLECTING status: nodeId={}, type={}, outputKeys={}",
                nodeId, node.getType(), result.output() != null ? result.output().keySet() : "null");
            return null;
        }

        // Convert V2 result to legacy StepExecutionResult (+ error meta for failures)
        StepExecutionResult stepResultWithMeta = convertToStepResultWithErrorMeta(nodeId, result);

        // Use StepCompletionOrchestrator - single entry point for:
        // 1. DB persistence
        // 2. In-memory state update (TERMINAL only - see CompletionKind.mutatesSnapshotCounts)
        // 3. Streaming event emission
        // Pass epoch/triggerId from ExecutionContext for epoch-scoped snapshot mutations
        // Capture the orchestrator's completion result: it carries the
        // payload-lost rewrite (tier 2) that engine callers must honor for
        // their traversal decision.
        StepCompletionResult completion = null;
        if (kind == CompletionKind.TERMINAL) {
            if (context != null && context.triggerId() != null) {
                completion = stepCompletionOrchestrator.completeStepWithResult(
                    execution, nodeId, nodeLabel, stepResultWithMeta,
                    itemIndex, iteration, context.epoch(), context.triggerId(), suppressGlobalMark);
            } else if (suppressGlobalMark) {
                completion = stepCompletionOrchestrator.completeStepWithResult(
                    execution, nodeId, nodeLabel, stepResultWithMeta,
                    itemIndex, iteration, context != null ? context.epoch() : 0, null, true);
            } else {
                completion = stepCompletionOrchestrator.complete(
                    com.apimarketplace.orchestrator.services.completion.StepCompletionContext.of(
                        execution, nodeId, nodeLabel, stepResultWithMeta, itemIndex, iteration));
            }
        } else {
            // persistRowInLoopContext divergence - loop context → WS-only (persistRow=false);
            // non-loop → the attempt row IS persisted. WHY (2026-06-10 audit item 4):
            //  - Non-loop: the v6 unique index (…, iteration, item_index, epoch, spawn,
            //    status) admits ONE FAILED row per logical execution, so the FIRST failed
            //    attempt claims it; later failed attempts - and the terminal FAILED row,
            //    when all attempts fail - dedupe onto it via ON CONFLICT DO NOTHING (the
            //    terminal failure's snapshot marks, WS event and billing still apply
            //    exactly once; only the row's payload keeps the first attempt's annotation).
            //  - Loop: loop-history reconstruction reads the per-iteration rows; if an
            //    attempt row claimed the iteration's FAILED slot, the iteration's TERMINAL
            //    row (carrying policy_attempt=N/N and the final error) would be silently
            //    ON-CONFLICT-dropped and the DB would record "attempt 1/N" as the
            //    iteration's terminal state. Skipping attempt rows in loops keeps the
            //    terminal row authoritative; the attempt history remains visible on the
            //    WS stream, and the terminal row's policy_attempt/policy_max_attempts
            //    annotation records how many attempts the iteration consumed.
            // See CompletionKind.persistsRowInLoopContext.
            boolean persistRow = !loopContext;
            if (context != null && context.triggerId() != null) {
                completion = stepCompletionOrchestrator.completeAttempt(
                    execution, nodeId, nodeLabel, stepResultWithMeta,
                    itemIndex, iteration, context.epoch(), context.triggerId(), persistRow);
            } else {
                completion = stepCompletionOrchestrator.completeAttempt(
                    execution, nodeId, nodeLabel, stepResultWithMeta,
                    itemIndex, iteration, context != null ? context.epoch() : 0, null, persistRow);
            }
        }

        // Save response node output to conversation (chat trigger → response node flow).
        // TERMINAL only (CompletionKind.savesResponseToConversation): a non-final attempt
        // produced no user-visible assistant message - the node is about to retry.
        if (kind.savesResponseToConversation()
                && node.getType() == NodeType.RESPONSE && result.isSuccess() && context != null) {
            saveResponseToConversation(execution, result, context);
        }

        // Clear in-memory running count under the per-epoch Redis key (P2.3.1).
        // {orchestrator:running:{runId}:{epoch}}. When context is null (rare -
        // legacy callers) fall back to epoch 0 to preserve current behavior.
        // TERMINAL only (CompletionKind.marksTrackerCompleted): after a non-final
        // attempt the node is still RUNNING - it is about to retry.
        if (kind.marksTrackerCompleted()) {
            int epoch = context != null ? context.epoch() : 0;
            runningNodeTracker.markCompleted(execution.getRunId(), epoch, nodeId);
        }

        // Piste 4 - invalidate the readiness cache for this run so the next getReadyNodes()
        // traversal sees the just-completed node's output instead of the pre-completion
        // snapshot. Broad invalidation by runId is intentional: a node's output can change
        // any successor's readiness, so partial keys would risk staleness.
        // TERMINAL only (CompletionKind.invalidatesReadiness): a non-final attempt changes
        // nothing observable by successors.
        if (kind.invalidatesReadiness() && readinessCache != null) {
            readinessCache.invalidateRun(execution.getRunId());
        }

        return completion;
    }

    /**
     * Emit a NON-FINAL failed retry attempt of a node executing under a
     * {@link com.apimarketplace.orchestrator.domain.workflow.NodePolicy} with retries.
     * Called by the engine / split fan-out for every failed attempt EXCEPT the last;
     * the terminal attempt (retry-then-success or exhausted failure) flows through
     * {@link #emitNodeComplete} unchanged.
     *
     * <p>Thin back-compat alias over the SINGLE parameterized pipeline,
     * {@link #emitNodeComplete(WorkflowExecution, ExecutionNode, NodeExecutionResult,
     * TriggerItem, int, ExecutionContext, Integer, CompletionKind)} with
     * {@code NON_FINAL_ATTEMPT}. The attempt-emission contract (2026-06-10 audit items
     * 2/3/4: always WS-emitted with {@code policy_attempt}/{@code policy_max_attempts},
     * never mutates StateSnapshot/workflow_epochs/edges, never billed, leaves the node
     * RUNNING, and the loop/non-loop row-persistence asymmetry) is decided inside that
     * pipeline at the {@link CompletionKind} branch points - see the
     * {@code CompletionKind} accessor javadocs and the inline branch comments for each
     * skip's rationale.
     */
    public void emitNodeFailedAttempt(
            WorkflowExecution execution,
            ExecutionNode node,
            NodeExecutionResult result,
            TriggerItem item,
            int itemIndex,
            ExecutionContext context) {
        emitNodeComplete(execution, node, result, item, itemIndex, context,
            extractCurrentIteration(context, node, result), CompletionKind.NON_FINAL_ATTEMPT);
    }

    /**
     * Initialize total items count for proper "X/Y" display in UI.
     *
     * @param execution The workflow execution
     * @param totalItems The total number of items to be processed
     */
    public void initializeTotalItems(WorkflowExecution execution, int totalItems) {
        if (execution == null || totalItems <= 0) {
            return;
        }

        String runId = execution.getRunId();
        logger.info("📊 [NodeCompletion] Initializing total items: runId={}, totalItems={}", runId, totalItems);

        nodeEventEmitterService.initializeTotalItems(runId, totalItems);
    }

    /**
     * Persist a SKIPPED record for a specific item without updating the node's state.
     *
     * <p>Used by SplitAwareNodeExecutor when a Decision/Switch routes items to different
     * branches. Items not routed to this branch need SKIPPED records for accurate statusCounts.
     *
     * <p>IMPORTANT: This creates a step data record with SKIPPED status but does NOT mark
     * the node as SKIPPED in the StateSnapshot. The node may still be executing normally
     * for other items.
     *
     * @param execution The workflow execution
     * @param node The node that was skipped for this item
     * @param itemIndex The index of the item that was skipped
     * @param skipReason The reason the item was skipped
     */
    public void emitNodeSkippedForItem(
            WorkflowExecution execution,
            ExecutionNode node,
            int itemIndex,
            String skipReason) {
        emitNodeSkippedForItem(execution, node, itemIndex, skipReason, 0, null);
    }

    /**
     * Per-item SKIPPED variant with explicit epoch + triggerId for parallel-epoch isolation.
     *
     * <p>The per-epoch UI view's {@code statusCounts} is sourced from
     * {@code StepAggregationService.getAggregatedSteps(runId, epoch)} which filters
     * {@code workflow_step_data.epoch}. Pre-fix, this method's 4-arg form let the
     * persistence layer fall back to the global epoch resolver - late-arriving per-item
     * skips on a closed parallel epoch landed under the run's current epoch (post-cycle)
     * instead of their own, so the per-epoch view rendered null for split successors.
     * The 6-arg form forwards both the epoch (used for the {@code workflow_step_data}
     * row) and the triggerId (used for the {@code workflow_epochs} counter row keyed
     * by DAG).
     */
    public void emitNodeSkippedForItem(
            WorkflowExecution execution,
            ExecutionNode node,
            int itemIndex,
            String skipReason,
            int epoch,
            String triggerId) {

        if (execution == null || node == null) {
            return;
        }

        String nodeId = node.getNodeId();
        String nodeLabel = extractLabel(nodeId);

        logger.debug("[NodeCompletion] Persisting per-item SKIPPED record: nodeId={}, itemIndex={}, reason={}, epoch={}, triggerId={}",
            nodeId, itemIndex, skipReason, epoch, triggerId);

        // Use StepCompletionOrchestrator to persist ONLY the step data record.
        // The completeSkippedStep method also updates StateSnapshot, so we use the
        // persistence service directly to avoid marking the node as globally SKIPPED.
        stepCompletionOrchestrator.completeSkippedStepWithoutStateUpdate(
            execution, nodeId, nodeLabel, skipReason, nodeId, itemIndex, epoch, triggerId);
    }

    /**
     * Batch-increment NodeCounts.skipped for per-item records in split branches.
     * Single DB transaction - does NOT touch EpochState.
     */
    public void batchIncrementSkippedCounts(String runId, String nodeId, int count) {
        stepCompletionOrchestrator.batchIncrementSkippedCounts(runId, nodeId, count);
    }

    /**
     * Batch-increment NodeCounts.skipped + emit ONE aggregated streaming step event
     * with the post-increment counts. Closes the frontend "no badge" bug on
     * apply_X branches when classify routes all items to one category.
     *
     * <p>See {@link StepCompletionOrchestrator#batchIncrementSkippedCountsAndEmit}
     * for the design rationale.
     */
    public void batchIncrementSkippedCountsAndEmit(
            WorkflowExecution execution,
            String nodeId,
            String nodeLabel,
            int count,
            int epoch,
            String triggerId) {
        stepCompletionOrchestrator.batchIncrementSkippedCountsAndEmit(
            execution, nodeId, nodeLabel, count, epoch, triggerId);
    }

    /**
     * Save a response node's output message to the conversation as an assistant message.
     * Called after a RESPONSE node completes successfully when a conversationId is present
     * in the trigger data (chat trigger → response node flow).
     *
     * <p>Guards:
     * <ul>
     *   <li>spawn > 0 → re-execution of same epoch, skip to avoid duplicates</li>
     *   <li>inside a loop (iteration != null) → skip, loops produce multiple outputs per epoch</li>
     *   <li>itemIndex > 0 → split context, only save for first item</li>
     * </ul>
     *
     * <p>Wrapped in try-catch to never fail the workflow execution on conversation errors.
     */
    private void saveResponseToConversation(WorkflowExecution execution,
                                             NodeExecutionResult result,
                                             ExecutionContext context) {
        try {
            // Skip re-executions (spawns) to avoid duplicate messages
            if (context.spawn() > 0) {
                logger.debug("Skipping conversation save for spawn {} (re-execution)", context.spawn());
                return;
            }

            // Skip if inside a loop - loops produce multiple outputs per epoch
            Integer iteration = extractCurrentIteration(context, null, result);
            if (iteration != null) {
                logger.debug("Skipping conversation save inside loop iteration {}", iteration);
                return;
            }

            // Skip split items beyond the first - only save one response per epoch
            if (context.itemIndex() > 0) {
                logger.debug("Skipping conversation save for split item {}", context.itemIndex());
                return;
            }

            Object convId = context.triggerData() != null
                ? context.triggerData().get("conversationId") : null;
            if (convId == null || result.output() == null) return;

            String message = result.output().get("message") instanceof String s ? s : null;
            if (message == null || message.isBlank()) return;

            conversationServiceClient.saveMessage(
                convId.toString(), "assistant", message,
                null, context.tenantId(), context.workflowRunId()
            );
            logger.debug("Saved response message to conversation {} (epoch={})",
                convId, context.epoch());
        } catch (Exception e) {
            logger.warn("Failed to save response to conversation: {}", e.getMessage());
        }
    }

    /**
     * Convert V2 NodeExecutionResult to legacy StepExecutionResult.
     *
     * @param nodeId The node ID
     * @param result The V2 execution result
     * @return The legacy StepExecutionResult
     */
    public StepExecutionResult convertToStepResult(String nodeId, NodeExecutionResult result) {
        if (result.isSuccess()) {
            return StepExecutionResult.success(
                nodeId,
                result.output(),
                result.durationMs()
            );
        } else if (result.isSkipped()) {
            Map<String, Object> output = new HashMap<>();
            if (result.output() != null) {
                output.putAll(result.output());
            }
            if (result.metadata() != null) {
                output.putAll(result.metadata());
            }
            String reason = result.errorMessage()
                .orElseGet(() -> String.valueOf(output.getOrDefault("skip_reason", "Skipped")));
            output.putIfAbsent("skip_reason", reason);
            return new StepExecutionResult(
                nodeId,
                NodeStatus.SKIPPED,
                reason,
                output,
                result.durationMs(),
                null
            );
        } else {
            // Preserve output even on failure (error details, partial responses)
            return StepExecutionResult.failureWithOutput(
                nodeId,
                result.errorMessage().orElse("Unknown error"),
                result.output(),
                result.durationMs()
            );
        }
    }

    /**
     * {@link #convertToStepResult} plus the failure error-meta enrichment shared by
     * {@link #emitNodeComplete} and {@link #emitNodeFailedAttempt}: failed results
     * carry {@code error} and {@code status=error} in their output map.
     */
    private StepExecutionResult convertToStepResultWithErrorMeta(String nodeId, NodeExecutionResult result) {
        StepExecutionResult stepResult = convertToStepResult(nodeId, result);

        Map<String, Object> outputWithMeta = stepResult.output() != null
            ? new HashMap<>(stepResult.output())
            : new HashMap<>();
        if (result.isFailure() && result.errorMessage().isPresent()) {
            outputWithMeta.put("error", result.errorMessage().get());
            outputWithMeta.putIfAbsent("status", "error");
        }
        return new StepExecutionResult(
            stepResult.stepId(),
            stepResult.status(),
            stepResult.message(),
            outputWithMeta,
            stepResult.executionTime(),
            stepResult.error()
        );
    }

    /**
     * Extract label from node ID (e.g., "mcp:my_step" -> "my_step").
     * Delegates to {@link LabelNormalizer#extractLabel(String)}.
     *
     * @param nodeId The node ID
     * @return The extracted label
     */
    public String extractLabel(String nodeId) {
        if (nodeId == null) {
            return "unknown";
        }
        String label = LabelNormalizer.extractLabel(nodeId);
        return label != null ? label : "unknown";
    }

    /**
     * Extract current iteration from context.
     * Looks for any active (non-terminated) back-edge state in the execution context.
     *
     * <p>The {@code !terminated} filter - NOT {@code shouldContinue()} - is intentional:
     * during the body of the LAST iteration, {@code shouldContinue()} returns
     * {@code false} (no NEXT iteration would fit), but the body still owns this
     * iteration's stamp. Filtering on {@code shouldContinue()} would drop the
     * iteration value, the row would fall back to the default {@code iteration=0},
     * and the unique index {@code idx_workflow_step_data_unique_v6} would silently
     * collide with the LoopNode initial body row (also {@code iteration=0}) -
     * persisting via {@code ON CONFLICT DO NOTHING} would drop the last iteration's
     * row from storage. {@code !terminated} is true throughout body execution and
     * only flips after the next back-edge call activates the exit path.
     *
     * @param context The execution context
     * @param node The execution node
     * @param result The execution result
     * @return The current iteration, or null if not in a loop
     */
    public Integer extractCurrentIteration(ExecutionContext context, ExecutionNode node, NodeExecutionResult result) {
        if (context == null || node == null) {
            return null;
        }

        Map<String, BackEdgeState> activeStatesByEdgeId = new HashMap<>();
        for (String key : context.getGlobalDataKeys()) {
            if (key.startsWith("back_edge_state:")) {
                Object stateObj = context.getGlobalData(key).orElse(null);
                if (stateObj instanceof BackEdgeState backEdgeState && !backEdgeState.terminated()) {
                    activeStatesByEdgeId.put(backEdgeState.edgeId(), backEdgeState);
                }
            }
        }

        if (activeStatesByEdgeId.isEmpty()) {
            return null;
        }

        WorkflowPlan plan = context.plan();
        if (plan != null) {
            String nodeId = node.getNodeId();

            // Fast path for the final loop-body node, which owns the iterate edge.
            for (Edge iterateEdge : plan.getIterateEdgesForSource(nodeId)) {
                BackEdgeState state = activeStatesByEdgeId.get(iterateEdge.getEdgeId());
                if (state != null) {
                    return state.iteration();
                }
            }

            // Body nodes before the final iterate source still need the same loop stamp.
            for (Edge iterateEdge : plan.getIterateEdges()) {
                BackEdgeState state = activeStatesByEdgeId.get(iterateEdge.getEdgeId());
                if (state != null && isNodeInsideLoopBody(plan, iterateEdge, nodeId)) {
                    return state.iteration();
                }
            }

            logger.debug("Active loop states found but none matched node {}; leaving iteration unset",
                nodeId);
            return null;
        }

        if (activeStatesByEdgeId.size() == 1) {
            return activeStatesByEdgeId.values().iterator().next().iteration();
        }

        logger.debug("Multiple active loop states found but none matched node {}; leaving iteration unset",
            node.getNodeId());
        return null;
    }

    private boolean isNodeInsideLoopBody(WorkflowPlan plan, Edge iterateEdge, String nodeId) {
        if (plan == null || iterateEdge == null || nodeId == null) {
            return false;
        }

        String sourceKey = EdgeRefParser.getNodeKey(iterateEdge.from());
        String loopCoreKey = EdgeRefParser.getNodeKey(iterateEdge.to());
        String bodyTargetKey = plan.findLoopBodyTarget(loopCoreKey);
        if (sourceKey == null || bodyTargetKey == null) {
            return false;
        }

        Map<String, Set<String>> successors = buildNonIterateSuccessors(plan);
        Queue<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(bodyTargetKey);
        visited.add(bodyTargetKey);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (nodeId.equals(current)) {
                return true;
            }
            if (sourceKey.equals(current)) {
                continue;
            }
            for (String successor : successors.getOrDefault(current, Set.of())) {
                if (visited.add(successor)) {
                    queue.add(successor);
                }
            }
        }

        return false;
    }

    private Map<String, Set<String>> buildNonIterateSuccessors(WorkflowPlan plan) {
        Map<String, Set<String>> successors = new HashMap<>();
        for (Edge edge : plan.getEdges()) {
            if ("iterate".equals(EdgeRefParser.getPort(edge.to()))) {
                continue;
            }
            String fromKey = EdgeRefParser.getNodeKey(edge.from());
            String toKey = EdgeRefParser.getNodeKey(edge.to());
            if (fromKey != null && toKey != null) {
                successors.computeIfAbsent(fromKey, ignored -> new HashSet<>()).add(toKey);
            }
        }
        return successors;
    }
}
