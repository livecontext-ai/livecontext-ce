package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.execution.v2.lifecycle.V2WorkflowFinalizer;
import com.apimarketplace.orchestrator.execution.v2.nodes.AgentNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.BaseNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.DecisionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;

import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.OptionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.UserApprovalNode;
import com.apimarketplace.orchestrator.execution.v2.split.SplitAggregateHandler;
import com.apimarketplace.orchestrator.execution.v2.split.SplitAwareNodeExecutor;
import com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager;
import com.apimarketplace.orchestrator.execution.v2.split.SplitMergeHandler;
import com.apimarketplace.orchestrator.execution.v2.split.SplitNodeExecutor;
import com.apimarketplace.orchestrator.execution.v2.scheduler.V2AutoScheduler;
import com.apimarketplace.orchestrator.execution.v2.scheduler.V2ExecutionScheduler;
import com.apimarketplace.orchestrator.execution.v2.scheduler.V2StepByStepScheduler;
import com.apimarketplace.orchestrator.execution.v2.services.NodeSearchService;
import com.apimarketplace.orchestrator.execution.v2.services.ReadyNodeCalculator;
import com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService;
import com.apimarketplace.orchestrator.execution.v2.services.V2SkipPropagationService;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import com.apimarketplace.orchestrator.services.credit.CreditBudgetService;
import com.apimarketplace.orchestrator.services.resume.MergeNodeAnalyzer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

/**
 * Unified Execution Engine - THE SINGLE EXECUTION ALGORITHM
 *
 * Core Principle: Everything is a tree node, traversed recursively.
 *
 * Flow:
 * 1. Check if can execute (prerequisites, conditions)
 * 2. Execute the node
 * 3. Update context with result (immutable)
 * 4. Lifecycle callback (emit events, persist, metrics)
 * 5. Get next nodes based on result
 * 6. Recurse to children
 *
 * This class orchestrates execution and delegates specific concerns to:
 * - {@link SplitNodeExecutor} for split node execution
 * - {@link SplitAwareNodeExecutor} for split-aware node execution
 * - {@link ReadyNodeCalculator} for determining ready nodes
 */
@Service
public class UnifiedExecutionEngine {

    private static final Logger logger = LoggerFactory.getLogger(UnifiedExecutionEngine.class);

    private final ExecutorService executorService;
    private final V2WorkflowFinalizer workflowFinalizer;
    private final V2AutoScheduler autoScheduler;
    private final V2StepByStepScheduler stepByStepScheduler;
    private final ReadyNodeCalculator readyNodeCalculator;
    private final BackEdgeHandler backEdgeHandler;

    // Simplified split system
    private final SplitNodeExecutor splitNodeExecutor;
    private final SplitAwareNodeExecutor splitAwareExecutor;
    private final SplitMergeHandler splitMergeHandler;
    private final SplitAggregateHandler splitAggregateHandler;
    private final SplitContextManager splitContextManager;

    // Node search service (centralized node lookup)
    private final NodeSearchService nodeSearchService;
    private final V2SkipPropagationService skipPropagationService;
    private final CreditBudgetService creditBudgetService;

    // Cancel signal checker - used to abort traversal when the run is cancelled/stopped.
    // Optional: not wired in unit tests that don't use Redis.
    @Autowired(required = false)
    private com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher workflowRedisPublisher;

    // Per-run {{$vars.*}} bundle (fetched once per run from auth-service).
    // Optional: not wired in plain unit-test construction - resolution then
    // degrades to "no variables defined".
    @Autowired(required = false)
    private com.apimarketplace.orchestrator.services.context.WorkflowVariableBundleCache workflowVariableBundleCache;

    // Generic per-node execution policy application (retry / backoff / continue-on-failure).
    // Default instance keeps plain unit-test construction working; Spring overrides via setter.
    // A node WITHOUT a nodePolicy block resolves to NodePolicy.DEFAULT → the runner is a pure
    // passthrough (byte-identical legacy behavior, exceptions included).
    private NodePolicyRunner nodePolicyRunner = new NodePolicyRunner();

    @Autowired(required = false)
    public void setNodePolicyRunner(NodePolicyRunner nodePolicyRunner) {
        if (nodePolicyRunner != null) {
            this.nodePolicyRunner = nodePolicyRunner;
        }
    }

    // Root nodes per runId for back-edge node lookup (populated during executeItem)
    private final java.util.concurrent.ConcurrentHashMap<String, List<ExecutionNode>> rootNodesByRunId = new java.util.concurrent.ConcurrentHashMap<>();

    @Autowired
    public UnifiedExecutionEngine(
            V2WorkflowFinalizer workflowFinalizer,
            V2AutoScheduler autoScheduler,
            V2StepByStepScheduler stepByStepScheduler,
            ReadyNodeCalculator readyNodeCalculator,
            BackEdgeHandler backEdgeHandler,
            SplitNodeExecutor splitNodeExecutor,
            SplitAwareNodeExecutor splitAwareExecutor,
            SplitMergeHandler splitMergeHandler,
            SplitAggregateHandler splitAggregateHandler,
            SplitContextManager splitContextManager,
            NodeSearchService nodeSearchService,
            V2SkipPropagationService skipPropagationService,
            CreditBudgetService creditBudgetService) {
        this.workflowFinalizer = workflowFinalizer;
        this.autoScheduler = autoScheduler;
        this.stepByStepScheduler = stepByStepScheduler;
        this.readyNodeCalculator = readyNodeCalculator;
        this.backEdgeHandler = backEdgeHandler;
        this.splitNodeExecutor = splitNodeExecutor;
        this.splitAwareExecutor = splitAwareExecutor;
        this.splitMergeHandler = splitMergeHandler;
        this.splitAggregateHandler = splitAggregateHandler;
        this.splitContextManager = splitContextManager;
        this.nodeSearchService = nodeSearchService;
        this.skipPropagationService = skipPropagationService;
        this.creditBudgetService = creditBudgetService;
        this.executorService = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors() * 2
        );
    }

    /**
     * Get the appropriate scheduler based on execution mode.
     */
    private V2ExecutionScheduler getScheduler(ExecutionTree tree) {
        return tree.isStepByStepMode() ? stepByStepScheduler : autoScheduler;
    }

    /**
     * Get the step-by-step scheduler (for external access by controller).
     */
    public V2StepByStepScheduler getStepByStepScheduler() {
        return stepByStepScheduler;
    }

    // =========================================================================
    // WORKFLOW EXECUTION - Auto Mode
    // =========================================================================

    /**
     * Execute workflow for multiple items (reactive).
     * Returns a CompletableFuture that completes when all items are done.
     */
    public CompletableFuture<WorkflowResult> executeWorkflow(
            ExecutionTree tree,
            List<TriggerItem> items,
            WorkflowExecution execution,
            V2ExecutionEventService eventService) {

        logger.info("🚀 Starting workflow execution: runId={}, workflowRunId={}, tenantId={}, items={}, mode={}, rootNodes={}, planSteps={}, planEdges={}",
            tree.getRunId(),
            tree.getWorkflowRunId(),
            tree.getTenantId(),
            items.size(),
            tree.getExecutionMode(),
            tree.getRootNodes() != null ? tree.getRootNodes().stream().map(ExecutionNode::getNodeId).toList() : "[]",
            tree.getPlan() != null ? tree.getPlan().getMcps().size() : 0,
            tree.getPlan() != null ? tree.getPlan().getEdges().size() : 0);

        // Initialize local credit budget (single HTTP call, then all checks are in-memory)
        // Uses computeIfAbsent - safe for concurrent workflows sharing the same tenant
        if (creditBudgetService != null) {
            creditBudgetService.initBudget(tree.getTenantId());
            creditBudgetService.incrementActiveWorkflows(tree.getTenantId());
        }

        eventService.initializeTotalItems(execution, items.size());

        // Store root nodes for back-edge node lookup during traversal
        rootNodesByRunId.put(tree.getRunId(), new ArrayList<>(tree.getRootNodes()));

        List<CompletableFuture<ItemResult>> itemFutures = items.stream()
            .map(item -> executeItem(tree, item, execution, eventService))
            .toList();

        return CompletableFuture.allOf(itemFutures.toArray(new CompletableFuture[0]))
            .thenApply(v -> {
                List<ItemResult> results = itemFutures.stream()
                    .map(CompletableFuture::join)
                    .toList();

                WorkflowResult result = WorkflowResult.aggregate(tree.getRunId(), results);

                logger.info("✅ Workflow execution completed: runId={}, workflowRunId={}, items={}, successItems={}, failedItems={}, overallStatus={}",
                    tree.getRunId(), tree.getWorkflowRunId(), items.size(),
                    result.successItems(), result.failedItems(), result.overallStatus());

                if (workflowFinalizer != null) {
                    workflowFinalizer.finalizeWorkflow(execution, result);
                }

                // Clean up root nodes cache and credit budget (ref-counted)
                rootNodesByRunId.remove(tree.getRunId());
                if (creditBudgetService != null) {
                    creditBudgetService.decrementActiveWorkflows(tree.getTenantId());
                }

                return result;
            })
            .exceptionally(throwable -> {
                logger.error("❌ Workflow execution failed: runId={}, workflowRunId={}, items={}, error={}",
                    tree.getRunId(), tree.getWorkflowRunId(), items.size(), throwable.getMessage(), throwable);

                if (workflowFinalizer != null) {
                    workflowFinalizer.finalizeWithError(execution, throwable);
                }

                rootNodesByRunId.remove(tree.getRunId());
                if (creditBudgetService != null) {
                    creditBudgetService.decrementActiveWorkflows(tree.getTenantId());
                }

                return WorkflowResult.failed(tree.getRunId(), throwable.getMessage());
            });
    }

    /**
     * Execute workflow for a single item.
     */
    private CompletableFuture<ItemResult> executeItem(
            ExecutionTree tree,
            TriggerItem item,
            WorkflowExecution execution,
            V2ExecutionEventService eventService) {

        // Capture orgId for the async worker thread - the executorService strips
        // the request ThreadLocal, so V261 NOT NULL inserts (storage.storage,
        // workflow_step_data) would fail without re-binding the org scope.
        final String orgIdForWorker = tree.getOrganizationId();
        return CompletableFuture.supplyAsync(() -> {
            ItemResult[] resultHolder = new ItemResult[1];
            com.apimarketplace.common.web.TenantResolver.runWithOrgScope(orgIdForWorker, () -> {
                try {
                    logger.info("📦 Executing item: itemId={}, index={}, runId={}, triggerDataKeys={}",
                        item.itemId(), item.index(), tree.getRunId(),
                        item.data() != null ? item.data().keySet() : "null");

                    // Derive triggerId from root node for AUTO mode context
                    String rootTriggerId = tree.getRootNode() != null ? tree.getRootNode().getNodeId() : null;
                    ExecutionContext context = ExecutionContext.create(
                        tree.getRunId(),
                        tree.getWorkflowRunId(),
                        tree.getTenantId(),
                        item.itemId(),
                        item.index(),
                        rootTriggerId,
                        0,  // epoch: initial AUTO execution starts at 0; SBS uses V2StepByStepContextManager
                        0,  // spawn: initial execution starts at 0
                        item.data(),
                        tree.getPlan()
                    )
                    // PR15 - stamp the workspace identity onto the context so node
                    // executors (AgentNode, future credential resolvers) read the
                    // active org from the context rather than re-reading the
                    // legacy metadata['__orgId__'] stash.
                    .withOrganization(tree.getOrganizationId(), tree.getOrganizationRole());

                    // Attach the {{$vars.*}} bundle under globalData "vars" so
                    // EvalContextBuilder and V2TemplateAdapter see it on every node.
                    if (workflowVariableBundleCache != null) {
                        context = context.withGlobalData(
                            com.apimarketplace.orchestrator.services.template.VarsSyntaxNormalizer.VARS_NAMESPACE,
                            workflowVariableBundleCache.getBundle(
                                tree.getRunId(), tree.getTenantId(), tree.getOrganizationId()));
                    }

                    ExecutionContext finalContext = traverseTree(
                        tree.getRootNode(),
                        context,
                        execution,
                        eventService,
                        item
                    );

                    logger.info("✅ Item execution completed: itemId={}, index={}, runId={}, stepOutputsCount={}",
                        item.itemId(), item.index(), tree.getRunId(),
                        finalContext.stepOutputs() != null ? finalContext.stepOutputs().size() : 0);

                    resultHolder[0] = ItemResult.success(item.itemId(), finalContext);

                } catch (Exception e) {
                    logger.error("❌ Item execution failed: itemId={}, index={}, runId={}, errorClass={}, error={}",
                        item.itemId(), item.index(), tree.getRunId(), e.getClass().getSimpleName(), e.getMessage(), e);

                    resultHolder[0] = ItemResult.failure(item.itemId(), e.getMessage());
                }
            });
            return resultHolder[0];
        }, executorService);
    }

    // =========================================================================
    // TREE TRAVERSAL - The Core Algorithm
    // =========================================================================

    /**
     * Outcome of core node execution - captures context, result, and whether execution yielded.
     * Used to communicate the result of executeNodeCore() to the caller.
     */
    private record NodeExecutionOutcome(
            ExecutionContext context,
            NodeExecutionResult result,
            boolean yielded
    ) {
        static NodeExecutionOutcome yielded(ExecutionContext ctx, NodeExecutionResult result) {
            return new NodeExecutionOutcome(ctx, result, true);
        }
        static NodeExecutionOutcome completed(ExecutionContext ctx, NodeExecutionResult result) {
            return new NodeExecutionOutcome(ctx, result, false);
        }
    }

    /**
     * Core node execution sequence - shared logic between traverseTree and traverseTreeWithMergeTracking.
     *
     * This method encapsulates the common execution steps:
     * 1. Record start in context
     * 2. Emit node start event
     * 3. Execute the node with split awareness
     * 4. Handle errors during execution
     * 5. Update context with result
     * 6. Handle AWAITING_SIGNAL (yield)
     * 7. Emit node complete event (respecting split persistence flag)
     * 8. Call lifecycle callback
     *
     * @param node The node to execute
     * @param context Current execution context
     * @param execution Workflow execution instance
     * @param eventService Service for emitting events
     * @param item The trigger item being processed
     * @return NodeExecutionOutcome containing updated context, result, and yield status
     */
    private NodeExecutionOutcome executeNodeCore(
            ExecutionNode node,
            ExecutionContext context,
            WorkflowExecution execution,
            V2ExecutionEventService eventService,
            TriggerItem item) {

        String nodeId = node.getNodeId();
        int itemIndex = context.itemIndex();
        String runId = context.runId();

        // 0. Credit budget check - fail fast if user has no credits left
        //    Agent/classify/guardrail nodes cost 2 credits (estimated min LLM cost),
        //    other nodes cost 1 credit. Per-iteration guard handles additional agent iterations.
        //    Parallel-branch safety (audited 2026-06-10): tryConsume is a CAS loop on the
        //    shared DistributedBudgetCache - each consume is atomic, and each node executes
        //    exactly once across branches (merge claim registry), so concurrent branches
        //    cannot double-spend. No per-branch reservation is needed.
        String tenantId = execution.getPlan() != null ? execution.getPlan().getTenantId() : null;
        if (creditBudgetService != null && tenantId != null) {
            java.math.BigDecimal nodeCost = (node instanceof AgentNode)
                    ? java.math.BigDecimal.valueOf(2) // estimated minimum LLM call cost
                    : java.math.BigDecimal.ONE;
            if (!creditBudgetService.tryConsume(tenantId, nodeCost)) {
                logger.warn("⛔ Credit budget exhausted for user {}, failing node: nodeId={}, runId={}, cost={}",
                        tenantId, nodeId, runId, nodeCost);
                NodeExecutionResult insufficientResult = NodeExecutionResult.failure(
                        nodeId, "Insufficient credits - execution stopped", 0);
                ExecutionContext failCtx = context.withStart(nodeId).withResult(nodeId, insufficientResult);
                return NodeExecutionOutcome.completed(failCtx, insufficientResult);
            }
        }

        // 1. Record start
        ExecutionContext contextWithStart = context.withStart(nodeId);

        // 2. Emit node start event (null-safe for split-internal traversals where eventService may be null).
        //    Thread the per-epoch context (P2.3.1) so the running marker lands
        //    under {orchestrator:running:runId:epoch} for the deferred-reset gate.
        if (eventService != null) {
            eventService.emitNodeStart(execution, node, item, itemIndex, contextWithStart.epoch());
        }

        // 3. Execute the node with split awareness - engine-level wall-clock timing.
        //    This is the SINGLE generic timing point for ALL node types.
        //    For AWAITING_SIGNAL results the engine time is near-zero setup;
        //    real duration comes later from signal resolution (resolvedAt - createdAt).
        NodeExecutionResult result;
        long engineStartMs = System.currentTimeMillis();
        try {
            logger.info("▶️  Executing node: nodeId={}, type={}, runId={}, itemId={}, itemIndex={}, predecessors={}, successors={}",
                nodeId, node.getType(), runId, context.itemId(), itemIndex,
                node.getPredecessorIds(), node.getSuccessors().stream().map(ExecutionNode::getNodeId).toList());

            // Per-node execution policy (retry / backoff / continue-on-failure) - the SINGLE
            // generic wrapping point for ALL node types in tree traversal. Default policy is a
            // pure passthrough (exceptions propagate to the catch below exactly as before).
            // This call sits ABOVE the split fan-out: a direct-split-successor's per-item
            // executions apply the policy PER ITEM inside SplitAwareNodeExecutor, and the
            // runner never retries a fan-out summary (split_already_persisted guard).
            // Under fork parallelism this method runs on the branch's own worker thread, so
            // the retry backoff blocks only that branch - never its siblings.
            // Idempotency: a retry re-executes the node with the same context; side-effectful
            // nodes re-run their side effects (documented on NodePolicy - opt-in per node).
            com.apimarketplace.orchestrator.domain.workflow.NodePolicy policy =
                nodePolicyRunner.resolve(context.plan(), nodeId);
            result = nodePolicyRunner.run(policy, nodeId,
                () -> executeNodeWithSplitAwareness(node, contextWithStart, runId, execution, eventService, item, itemIndex),
                (annotatedFailure, attempt, maxAttempts) -> {
                    // Every NON-final failed attempt is surfaced through the ATTEMPT-AWARE
                    // pipeline (emitNodeAttemptFailed): WS step event (+ step_data row in
                    // non-loop contexts), annotated with policy_attempt / policy_max_attempts
                    // so the frontend can show "attempt k/N" - but WITHOUT any StateSnapshot /
                    // edge / workflow_epochs mutation and WITHOUT billing. Only the TERMINAL
                    // result (success or exhausted failure) flows through emitNodeComplete and
                    // mutates counts/failedNodeIds/edges + bills the node's single credit.
                    // (2026-06-10 audit items 2/3/4 - see NodeCompletionService.emitNodeFailedAttempt.)
                    if (eventService != null) {
                        eventService.emitNodeAttemptFailed(execution, node, annotatedFailure, item, itemIndex,
                            contextWithStart);
                    }
                });

            // Override duration with engine-measured wall-clock time for non-yielding nodes.
            // AWAITING_SIGNAL nodes keep durationMs=0 - their real duration is recorded
            // when the signal resolves (generically via UnifiedSignalService).
            // Async-running nodes (engine-owned async I/O, e.g. agent queue) also keep
            // durationMs=0 - completion service measures wall-clock from start to delivery.
            if (!result.isAwaitingSignal() && !result.isAsyncRunning()) {
                long engineDurationMs = System.currentTimeMillis() - engineStartMs;
                result = result.withDuration(engineDurationMs);
            }

            logger.info("✅ Node executed: nodeId={}, type={}, status={}, durationMs={}, outputKeys={}, errorMessage={}, metadataKeys={}",
                nodeId, node.getType(), result.status(), result.durationMs(),
                result.output() != null ? result.output().keySet() : "null",
                result.errorMessage().orElse("none"),
                result.metadata() != null ? result.metadata().keySet() : "null");
        } catch (Exception e) {
            long engineDurationMs = System.currentTimeMillis() - engineStartMs;
            logger.error("❌ Node execution failed: nodeId={}, type={}, runId={}, itemId={}, itemIndex={}, errorClass={}, error={}",
                nodeId, node.getType(), runId, context.itemId(), itemIndex, e.getClass().getSimpleName(), e.getMessage(), e);
            result = NodeExecutionResult.failure(nodeId, e.getMessage(), engineDurationMs);
        }

        // 4. Update context with result
        ExecutionContext updatedContext = contextWithStart.withResult(nodeId, result);

        // 6. Handle AWAITING_SIGNAL - yield without completing
        if (result.isAwaitingSignal()) {
            logger.info("⏳ Node awaiting signal, yielding: nodeId={}, type={}",
                nodeId, result.metadata().get("signal_type"));
            if (eventService != null) {
                eventService.emitNodeAwaitingSignal(execution, node, result, item, itemIndex, updatedContext);
            }
            return NodeExecutionOutcome.yielded(updatedContext, result);
        }

        // 6b. Handle async-running (engine-owned async I/O, e.g. agent queue) - yield without
        // completing. The visible status stays RUNNING; completion is delivered later by
        // the async-completion service which calls back into the sync persistence pipeline.
        // SPLIT_ALREADY_PERSISTED gate mirrors emitNodeComplete: when SplitAwareNodeExecutor
        // dispatched per-item async tasks itself, the engine must NOT re-dispatch - that would
        // double-call markCompleted on the running tracker (the per-item loop already cleared it).
        if (result.isAsyncRunning()) {
            logger.info("⏳ Node async running, yielding: nodeId={}, correlationId={}",
                nodeId, result.metadata().get(NodeExecutionResult.ASYNC_CORRELATION_ID));
            if (!ExecutionMetadataKeys.isSplitAlreadyPersisted(result.metadata())) {
                if (eventService != null) {
                    eventService.emitNodeAsyncRunning(execution, node, result, item, itemIndex, updatedContext);
                }
            } else {
                logger.debug("⏭️  Skipping emitNodeAsyncRunning (split already persisted): nodeId={}", nodeId);
            }
            return NodeExecutionOutcome.yielded(updatedContext, result);
        }

        // 7. Emit node complete event (skip if split already persisted each item)
        // Null-safe for split-internal traversals where eventService may be null
        if (!ExecutionMetadataKeys.isSplitAlreadyPersisted(result.metadata())) {
            if (eventService != null) {
                var completion = eventService.emitNodeComplete(execution, node, result, item, itemIndex, updatedContext);
                // Payload-lost rewrite (tier 2, traversal truth): persistence
                // flipped this SUCCESS to FAILED because its output blob could
                // not be stored (row + snapshot + WS event + billing already
                // reflect FAILED). Rewrite the in-memory result so the caller
                // (traverseTree) takes the FAILURE branch: no success-path
                // successors, and shouldCascadeSkipFromResult drives the
                // SKIPPED cascade via V2SkipPropagationService - a step whose
                // output is not durable is NOT COMPLETED.
                if (completion != null && completion.payloadLost() && result.isSuccess()) {
                    logger.error("💥 Output payload lost - treating node as FAILED for traversal: nodeId={}, runId={}, message={}",
                        nodeId, runId, completion.payloadLostMessage());
                    result = NodeExecutionResult.failure(nodeId, completion.payloadLostMessage(), result.durationMs());
                    updatedContext = contextWithStart.withResult(nodeId, result);
                }
            }
        } else {
            logger.debug("⏭️  Skipping emitNodeComplete (split already persisted): nodeId={}", nodeId);
        }

        // 8. Lifecycle callback
        node.onComplete(updatedContext, result);

        return NodeExecutionOutcome.completed(updatedContext, result);
    }

    /**
     * THE SINGLE EXECUTION ALGORITHM
     *
     * Recursive tree traversal that works for ALL node types.
     * This is the core of the execution engine.
     *
     * NEW SIMPLIFIED SPLIT SYSTEM:
     * - Split nodes: Use SplitNodeExecutor (creates context, completes immediately)
     * - Merge nodes: Use SplitMergeHandler (aggregates results, closes context)
     * - Other nodes: Use SplitAwareNodeExecutor (detects context, executes for ALL items)
     */
    public ExecutionContext traverseTree(
            ExecutionNode node,
            ExecutionContext context,
            WorkflowExecution execution,
            V2ExecutionEventService eventService,
            TriggerItem item) {
        return traverseTree(node, context, execution, eventService, item, /*mergeScope=*/ null);
    }

    /**
     * Scoped variant of {@link #traverseTree}. When {@code mergeScope} is non-null
     * (the traversal is the continuation of a claimed merge inside a fork join),
     * two behaviors change vs the plain entry point:
     * <ul>
     *   <li>a merge node that cannot execute yet is {@linkplain ParallelMergeRegistry#defer
     *       deferred} into the scope instead of silently dropped - the join's
     *       fixed-point loop retries it once a sibling continuation completes its
     *       predecessors;</li>
     *   <li>a multi-successor node forks into a {@linkplain ParallelMergeRegistry#child
     *       child scope} of {@code mergeScope} instead of a fresh root, so merges
     *       spanning scopes propagate up the chain instead of being orphaned.</li>
     * </ul>
     */
    private ExecutionContext traverseTree(
            ExecutionNode node,
            ExecutionContext context,
            WorkflowExecution execution,
            V2ExecutionEventService eventService,
            TriggerItem item,
            ParallelMergeRegistry mergeScope) {

        String nodeId = node.getNodeId();
        int itemIndex = context.itemIndex();

        // 0. Cancellation check - abort traversal if the run was cancelled/stopped.
        //    This prevents executing remaining nodes in the tree after cancelWorkflow()
        //    or stopWorkflow() has been called. Uses Redis cancel key (same key agent-service
        //    checks via shouldStop()) so both LLM calls and node traversal respect the signal.
        if (workflowRedisPublisher != null && workflowRedisPublisher.isAgentCancelSignalSet(context.runId())) {
            logger.info("🛑 Run cancelled - aborting traversal before node: nodeId={}, runId={}", nodeId, context.runId());
            return context;
        }

        logger.info("🔹 Traversing node: nodeId={}, type={}, itemId={}, itemIndex={}, runId={}, contextNodeStates={}, isMerge={}, isImplicitMerge={}, predecessors={}",
            nodeId, node.getType(), context.itemId(), itemIndex, context.runId(),
            context.state().nodeStates().size(),
            node.isMergeNode(), node.isImplicitMerge(), node.getPredecessorIds());

        // 1. Check if can execute - with special handling for merge nodes
        if (!node.canExecute(context)) {
            // Special handling for Merge nodes in parallel Fork branches:
            // Don't mark as SKIPPED - the branch just waits here.
            if (node.isMergeNode()) {
                logger.info("⏸️  Merge node waiting for other branches: nodeId={}, type={}, predecessors={}, completedInContext={}",
                    nodeId, node.getType(), node.getPredecessorIds(),
                    node.getPredecessorIds().stream().filter(context::isCompleted).toList());
                // Inside a fork-join continuation: defer so the join's fixed-point
                // loop retries once a sibling merge continuation completes the
                // missing predecessors (otherwise the merge would be orphaned).
                if (mergeScope != null) {
                    mergeScope.defer(node);
                }
                return context;
            }

            // Special handling for implicit merges (nodes with multiple predecessors)
            if (node.isImplicitMerge()) {
                logger.info("⏸️  Implicit merge node waiting for other branches: nodeId={}, type={}, predecessors={}, completedInContext={}",
                    nodeId, node.getType(), node.getPredecessorIds(),
                    node.getPredecessorIds().stream().filter(context::isCompleted).toList());
                if (mergeScope != null) {
                    mergeScope.defer(node);
                }
                return context;
            }

            logger.info("⏭️  Skipping node (cannot execute): nodeId={}, type={}, predecessors={}, completedInContext={}",
                nodeId, node.getType(), node.getPredecessorIds(),
                node.getPredecessorIds().stream().filter(context::isCompleted).toList());
            NodeExecutionResult skipResult = NodeExecutionResult.skipped(
                nodeId, ExecutionMetadataKeys.SKIPPED_MESSAGE);
            ExecutionContext updatedContext = context.withResult(nodeId, skipResult);
            if (eventService != null) {
                eventService.emitNodeComplete(execution, node, skipResult, item, itemIndex, updatedContext);
            }
            return updatedContext;
        }

        // 2. Execute the node using shared core logic
        NodeExecutionOutcome outcome = executeNodeCore(node, context, execution, eventService, item);

        // 3. If yielded (AWAITING_SIGNAL), return immediately
        if (outcome.yielded()) {
            return outcome.context();
        }

        ExecutionContext updatedContext = outcome.context();
        NodeExecutionResult result = outcome.result();

        // 3b. StopOnError - hard-stop the entire workflow (all branches).
        //     The node's output was already persisted by executeNodeCore.
        //     Throw WorkflowStoppedException so fork handlers cancel parallel branches.
        if (node.isStopOnErrorNode()) {
            String errorMsg = result.errorMessage().orElse("Workflow stopped due to error");
            String errorCode = result.output() != null
                    ? String.valueOf(result.output().getOrDefault("error_code", ""))
                    : "";
            logger.info("🛑 StopOnError detected - stopping entire workflow: nodeId={}, errorMessage={}", nodeId, errorMsg);
            throw new WorkflowStoppedException(nodeId, errorMsg, errorCode, result);
        }

        // 4. Check if split already handled successors (parallel traversals launched)
        if (ExecutionMetadataKeys.isSplitSuccessorsHandled(result.metadata())) {
            logger.debug("⏭️  Skipping successor traversal (split handled): nodeId={}", nodeId);
            return updatedContext;
        }

        // 5. Propagate SKIPPED to downstream nodes on failure (single shared cascade routine,
        //    same call site as V2StepByStepService.executeNodeStepByStep - both sync engines
        //    converge on V2SkipPropagationService.cascadeFailureToSuccessors).
        //    Also fires on a SKIPPED result that carries the CASCADE_SKIP_TO_SUCCESSORS
        //    metadata flag - used by terminal-skip nodes (e.g. SplitAggregateHandler when
        //    no items routed through the aggregate's predecessor branch). Routing skips
        //    from Decision/Switch are NOT marked with the flag - they rely on per-port
        //    edge filtering and must not over-cascade across sibling branches.
        if (shouldCascadeSkipFromResult(result) && skipPropagationService != null && node instanceof BaseNode baseNode) {
            skipPropagationService.cascadeFailureToSuccessors(
                execution, baseNode, context.itemIndex(),
                context.epoch(), context.triggerId(),
                /*perItemScope=*/ false,
                V2SkipPropagationService.SOURCE_SYNC);
        }

        // 6. Get next nodes
        List<ExecutionNode> nextNodes = node.getNextNodes(result);

        // 6b. Continue-on-failure continuation: the node is FAILED (already emitted &
        //     persisted as such) but its policy asks traversal to proceed. Reuses the
        //     SKIPPED-with-error semantic - BaseNode.getNextNodes exposes ALL successors
        //     for any non-failed result, and downstream readiness already treats FAILED
        //     as resolved (ExecutionContext.isCompleted covers success/failure/skip) -
        //     so successors run exactly as they do after a terminal SKIPPED node.
        if (nextNodes.isEmpty() && result.isFailure()
                && ExecutionMetadataKeys.isContinueOnFailure(result.metadata())) {
            nextNodes = node.getSuccessors();
            logger.info("➡️  continueOnFailure: traversing successors of FAILED node: nodeId={}, successors={}",
                nodeId, nextNodes.stream().map(ExecutionNode::getNodeId).toList());
        }

        logger.info("🔀 Next nodes: nodeId={}, type={}, nextCount={}, nextNodeIds={}",
            nodeId, node.getType(), nextNodes.size(),
            nextNodes.stream().map(ExecutionNode::getNodeId).toList());

        // 7. Handle back-edge iteration (only when no regular successors - decision nodes
        // with iterate edges on one port must still follow their selected branch)
        if (nextNodes.isEmpty() && backEdgeHandler.hasBackEdge(node, updatedContext.plan())) {
            java.util.function.Function<String, ExecutionNode> nodeFinder = createNodeFinder(updatedContext, node);
            return backEdgeHandler.handleBackEdge(
                node, updatedContext, execution, eventService, item, nodeFinder, this::traverseTree);
        }

        // 8. Handle parallel execution when a node has multiple successors (Fork or implicit fork).
        //    When already inside a fork-join continuation (mergeScope != null) the new scope
        //    chains to it so cross-scope merges propagate; otherwise this starts a root scope.
        if (nextNodes.size() > 1) {
            logger.info("🔱 [Parallel] Multiple successors detected: nodeId={}, type={}, branches={}",
                nodeId, node.getType(), nextNodes.size());
            return handleForkParallelExecution(nextNodes, updatedContext, execution, eventService, item, mergeScope);
        }

        // 8. Recurse to children (sequential for single-successor chains; same scope)
        ExecutionContext currentContext = updatedContext;
        for (ExecutionNode child : nextNodes) {
            currentContext = traverseTree(child, currentContext, execution, eventService, item, mergeScope);
        }

        return currentContext;
    }

    /**
     * Handle parallel execution when a node has multiple successors.
     * All branches execute in parallel using CompletableFuture.
     * Returns the merged context from all branches.
     *
     * This applies to:
     * - Explicit Fork nodes (core:fork type)
     * - Implicit forks (any node with multiple outgoing edges, including triggers)
     * - Nested forks INSIDE a parallel branch (multi-successor node reached via
     *   traverseTreeWithMergeTracking) - these chain a child {@link ParallelMergeRegistry}
     *   scope so merges spanning scopes propagate to the join that can satisfy them.
     *
     * Merge handling (explicit core:merge AND implicit multi-predecessor nodes):
     * - Branches never execute merges; they defer them into the scope registry.
     * - After all branches complete, contexts are merged and the join runs a
     *   fixed-point loop: every deferred merge whose predecessors are all resolved
     *   is atomically claimed ({@link ParallelMergeRegistry#tryClaim}) and executed
     *   exactly once by this joining thread; merges still blocked are promoted to
     *   the parent scope (nested case) or left for signal-resume (root case).
     *
     * Concurrency-hardening notes (audited 2026-06-10, Phase 1):
     * - WS events + persistence: every branch thread emits node lifecycle events
     *   through V2ExecutionEventService exactly as the sequential path does. The
     *   emit path is thread-safe: EdgeStatusService batches are ThreadLocal, and
     *   all StateSnapshot writes funnel through StepCompletionOrchestrator →
     *   StateSnapshotService → JsonbPatchExecutor (CAS with [1ms,5ms,15ms] retries
     *   then pessimistic-lock fallback - progress guaranteed under contention, seq
     *   stays monotonic). No nested DB locks are held across node execution: events
     *   are emitted after execute() returns, outside any node-owned transaction.
     * - Credit budget: CreditBudgetService.tryConsume is a CAS loop on the shared
     *   DistributedBudgetCache - atomic per consume. Combined with the claim
     *   registry (each node executes exactly once across branches) there is no
     *   double-spend window; no per-branch reservation is needed.
     */
    private ExecutionContext handleForkParallelExecution(
            List<ExecutionNode> branches,
            ExecutionContext context,
            WorkflowExecution execution,
            V2ExecutionEventService eventService,
            TriggerItem item,
            ParallelMergeRegistry parentScope) {

        logger.info("🔱 [Fork] Starting parallel execution: branches={}, branchNodeIds={}, itemId={}, itemIndex={}, nested={}",
            branches.size(),
            branches.stream().map(ExecutionNode::getNodeId).toList(),
            context.itemId(), context.itemIndex(), parentScope != null);

        // Scope registry for merge nodes deferred by this fork's branches.
        // Root scope for a top-level fork; child scope (shared claim set) when nested.
        ParallelMergeRegistry scope = parentScope == null
            ? ParallelMergeRegistry.root()
            : parentScope.child();

        // Execute all branches in parallel using ForkJoinPool.commonPool() to avoid deadlock.
        // The common pool uses work-stealing, so blocked threads can still execute tasks.
        // This prevents thread starvation when nested forks occur.
        //
        // HOTFIX-2 (2026-05-20) - Capture orgId for the worker threads. ForkJoinPool
        // worker threads have no request ThreadLocal binding, so V261 NOT NULL inserts
        // (storage.storage, workflow_step_data) cascade-fail the TX without re-binding.
        final String orgIdForWorker = context.organizationId();
        List<CompletableFuture<ExecutionContext>> branchFutures = branches.stream()
            .map(branch -> CompletableFuture.supplyAsync(() -> {
                ExecutionContext[] holder = new ExecutionContext[1];
                RuntimeException[] errorHolder = new RuntimeException[1];
                com.apimarketplace.common.web.TenantResolver.runWithOrgScope(orgIdForWorker, () -> {
                    try {
                        logger.info("🔱 [Fork] Branch starting: nodeId={}, type={}", branch.getNodeId(), branch.getType());
                        ExecutionContext branchContext = traverseTreeWithMergeTracking(
                            branch, context, execution, eventService, item, scope);
                        logger.info("🔱 [Fork] Branch completed: nodeId={}, type={}, contextStepOutputs={}",
                            branch.getNodeId(), branch.getType(), branchContext.stepOutputs().size());
                        holder[0] = branchContext;
                    } catch (WorkflowStoppedException e) {
                        // StopOnError - let it propagate without wrapping
                        errorHolder[0] = e;
                    } catch (Exception e) {
                        logger.error("🔱 [Fork] Branch failed: {}, error={}", branch.getNodeId(), e.getMessage(), e);
                        errorHolder[0] = new RuntimeException("Branch execution failed: " + branch.getNodeId(), e);
                    }
                });
                if (errorHolder[0] != null) throw errorHolder[0];
                return holder[0];
            }, ForkJoinPool.commonPool()))
            .toList();

        // Wait for all branches to complete with timeout to prevent indefinite blocking
        try {
            CompletableFuture.allOf(branchFutures.toArray(new CompletableFuture[0]))
                .get(30, TimeUnit.MINUTES);
        } catch (java.util.concurrent.ExecutionException e) {
            // Check if a StopOnError node triggered the failure
            Throwable cause = e.getCause();
            while (cause instanceof RuntimeException && cause.getCause() != null
                    && !(cause instanceof WorkflowStoppedException)) {
                cause = cause.getCause();
            }
            if (cause instanceof WorkflowStoppedException stopped) {
                logger.info("🛑 [Fork] StopOnError detected in parallel branch - cancelling {} remaining branches",
                    branchFutures.stream().filter(f -> !f.isDone()).count());
                branchFutures.forEach(f -> f.cancel(true));
                throw stopped; // Re-throw to propagate up
            }
            logger.error("🔱 [Fork] Error waiting for branches: {}", e.getMessage(), e);
            branchFutures.forEach(f -> f.cancel(true));
            throw new RuntimeException("Fork execution failed", e);
        } catch (Exception e) {
            logger.error("🔱 [Fork] Error waiting for branches: {}", e.getMessage(), e);
            branchFutures.forEach(f -> f.cancel(true));
            throw new RuntimeException("Fork execution failed or timed out", e);
        }

        // Merge results from all branches
        ExecutionContext mergedContext = context;
        for (CompletableFuture<ExecutionContext> future : branchFutures) {
            try {
                ExecutionContext branchContext = future.get();
                mergedContext = mergedContext.merge(branchContext);
            } catch (Exception e) {
                logger.error("🔱 [Fork] Error getting branch result: {}", e.getMessage(), e);
            }
        }

        logger.info("🔱 [Fork] All {} branches completed, deferredMerges={}, mergedContextOutputs={}",
            branches.size(),
            scope.unclaimedDeferred().stream().map(ExecutionNode::getNodeId).toList(),
            mergedContext.stepOutputs().size());

        // Continue from deferred merge nodes now that all branches have completed.
        // Fixed-point loop: executing one merge's continuation can complete the
        // predecessors of (or defer) another merge in this scope - keep sweeping
        // until a full pass makes no progress. Each merge is claimed atomically
        // before execution so it fires exactly once per scope-tree, regardless of
        // which join level or continuation reaches it.
        boolean progress = true;
        while (progress) {
            progress = false;
            for (ExecutionNode mergeNode : scope.unclaimedDeferred()) {
                String mergeNodeId = mergeNode.getNodeId();

                // Already executed inline by an earlier merge's continuation
                // (diamond: merge1 → merge2 with merge2 also deferred by a sibling
                // branch). Claim it so no later sweep - or outer join - re-executes.
                if (mergedContext.isCompleted(mergeNodeId)) {
                    scope.tryClaim(mergeNodeId);
                    continue;
                }

                if (!mergeNode.canExecute(mergedContext)) {
                    continue;
                }

                if (!scope.tryClaim(mergeNodeId)) {
                    continue; // claimed elsewhere in the scope-tree
                }

                logger.info("🔱 [Fork] Continuing from Merge node: {}", mergeNodeId);
                mergedContext = traverseTree(mergeNode, mergedContext, execution, eventService, item, scope);
                progress = true;
            }
        }

        // Merges still blocked: their remaining predecessors live in an outer
        // sibling branch (nested scope → promote to the parent join) or are
        // awaiting a signal / abandoned by a failed branch (root scope → leave
        // for SignalResumeService, which resumes from persisted DB state).
        if (scope.hasParent()) {
            int promoted = scope.promoteUnclaimedToParent();
            if (promoted > 0) {
                logger.info("🔱 [Fork] Promoted {} unclaimed merge(s) to parent scope", promoted);
            }
        } else {
            for (ExecutionNode mergeNode : scope.unclaimedDeferred()) {
                logger.warn("🔱 [Fork] Merge node still cannot execute at root join (predecessor awaiting signal or failed branch - resume handles it from DB state): {}",
                    mergeNode.getNodeId());
            }
        }

        return mergedContext;
    }

    /**
     * Traverse tree with tracking of waiting Merge nodes.
     * Used within parallel Fork execution to defer merge nodes until all branches complete.
     *
     * IMPORTANT: In parallel execution, each branch has its own context that only
     * contains that branch's completions. Merge nodes (explicit or implicit) must ALWAYS be
     * deferred until all parallel branches complete and contexts are merged.
     *
     * Key differences from traverseTree:
     * - Merge nodes are ALWAYS deferred (into the {@link ParallelMergeRegistry} scope)
     *   regardless of canExecute
     * - A multi-successor node inside the branch launches its own nested parallel
     *   execution through the SAME path as an explicit Fork (handleForkParallelExecution
     *   with a child scope) - branch-internal forks are as parallel as top-level ones
     * - Uses shared executeNodeCore() for consistent execution logic
     */
    private ExecutionContext traverseTreeWithMergeTracking(
            ExecutionNode node,
            ExecutionContext context,
            WorkflowExecution execution,
            V2ExecutionEventService eventService,
            TriggerItem item,
            ParallelMergeRegistry scope) {

        String nodeId = node.getNodeId();
        int itemIndex = context.itemIndex();

        // 0. Cancellation check - same as traverseTree.
        if (workflowRedisPublisher != null && workflowRedisPublisher.isAgentCancelSignalSet(context.runId())) {
            logger.info("🛑 Run cancelled - aborting merge-tracking traversal before node: nodeId={}, runId={}", nodeId, context.runId());
            return context;
        }

        logger.info("🔹 Traversing node (merge tracking): nodeId={}, type={}, itemId={}, itemIndex={}, isMerge={}, isImplicitMerge={}, predecessors={}",
            nodeId, node.getType(), context.itemId(), context.itemIndex(),
            node.isMergeNode(), node.isImplicitMerge(), node.getPredecessorIds());

        // 1. CRITICAL: Defer all merge nodes to be processed after parallel branches complete.
        // This happens BEFORE canExecute() to ensure merges are always deferred.
        if (node.isMergeNode()) {
            logger.info("⏸️  Merge node deferred (parallel branch): nodeId={}, type={}, predecessors={}",
                nodeId, node.getType(), node.getPredecessorIds());
            scope.defer(node);
            return context;
        }

        if (node.isImplicitMerge()) {
            logger.info("⏸️  Implicit merge deferred (parallel branch): nodeId={}, type={}, predecessors={}",
                nodeId, node.getType(), node.getPredecessorIds());
            scope.defer(node);
            return context;
        }

        // 2. Check if can execute (for non-merge nodes)
        if (!node.canExecute(context)) {
            logger.info("⏭️  Skipping node in merge tracking (cannot execute): nodeId={}, type={}, predecessors={}",
                nodeId, node.getType(), node.getPredecessorIds());
            NodeExecutionResult skipResult = NodeExecutionResult.skipped(
                nodeId, ExecutionMetadataKeys.SKIPPED_MESSAGE);
            ExecutionContext updatedContext = context.withResult(nodeId, skipResult);
            if (eventService != null) {
                eventService.emitNodeComplete(execution, node, skipResult, item, itemIndex, updatedContext);
            }
            return updatedContext;
        }

        // 3. Execute the node using shared core logic
        NodeExecutionOutcome outcome = executeNodeCore(node, context, execution, eventService, item);

        // 4. If yielded (AWAITING_SIGNAL), return immediately
        if (outcome.yielded()) {
            return outcome.context();
        }

        ExecutionContext updatedContext = outcome.context();
        NodeExecutionResult result = outcome.result();

        // 4b. StopOnError - hard-stop the entire workflow (all branches).
        //     Same check as traverseTree: output already persisted, throw to cancel fork siblings.
        if (node.isStopOnErrorNode()) {
            String errorMsg = result.errorMessage().orElse("Workflow stopped due to error");
            String errorCode = result.output() != null
                    ? String.valueOf(result.output().getOrDefault("error_code", ""))
                    : "";
            logger.info("🛑 StopOnError detected in merge-tracking branch - stopping entire workflow: nodeId={}, errorMessage={}", nodeId, errorMsg);
            throw new WorkflowStoppedException(nodeId, errorMsg, errorCode, result);
        }

        // 5. Get next nodes (needed before back-edge check)
        List<ExecutionNode> nextNodes = node.getNextNodes(result);

        // 5a. Continue-on-failure continuation inside a parallel branch - same reuse of
        //     the SKIPPED-with-error semantic as traverseTree step 6b. With successors
        //     restored, merge successors are deferred via the normal merge-tracking path
        //     below (so 5b's failure-only merge discovery is naturally bypassed).
        if (nextNodes.isEmpty() && result.isFailure()
                && ExecutionMetadataKeys.isContinueOnFailure(result.metadata())) {
            nextNodes = node.getSuccessors();
            logger.info("➡️  continueOnFailure (parallel branch): traversing successors of FAILED node: nodeId={}, successors={}",
                nodeId, nextNodes.stream().map(ExecutionNode::getNodeId).toList());
        }

        // 5b. On failure, getNextNodes returns empty. Walk successors to discover
        // merge nodes that need to be deferred - mirrors traverseTree's skip propagation
        // but only defers merge nodes into the scope registry.
        if (result.isFailure() && nextNodes.isEmpty() && node instanceof BaseNode baseNode) {
            for (ExecutionNode successor : baseNode.getSuccessors()) {
                if (successor.isMergeNode() || successor.isImplicitMerge()) {
                    logger.info("⏸️  Merge node discovered via failed branch: nodeId={}, failedNode={}",
                        successor.getNodeId(), nodeId);
                    scope.defer(successor);
                }
            }
        }

        // 6. Handle back-edge iteration (only when no regular successors)
        if (nextNodes.isEmpty() && backEdgeHandler.hasBackEdge(node, updatedContext.plan())) {
            java.util.function.Function<String, ExecutionNode> nodeFinder = createNodeFinder(updatedContext, node);
            return backEdgeHandler.handleBackEdge(
                node, updatedContext, execution, eventService, item, nodeFinder, this::traverseTree);
        }

        // 7. Multiple successors inside a branch - nested implicit fork. Launch the
        //    child branches through the SAME parallel path as an explicit Fork,
        //    chaining a child merge scope so cross-scope merges promote correctly.
        if (nextNodes.size() > 1) {
            logger.info("🔱 [Parallel] Multiple successors detected inside branch: nodeId={}, type={}, branches={}",
                nodeId, node.getType(), nextNodes.size());
            return handleForkParallelExecution(nextNodes, updatedContext, execution, eventService, item, scope);
        }

        // 8. Recurse to children (with merge tracking, single-successor chain)
        ExecutionContext currentContext = updatedContext;
        for (ExecutionNode child : nextNodes) {
            currentContext = traverseTreeWithMergeTracking(
                child, currentContext, execution, eventService, item, scope);
        }

        return currentContext;
    }

    // =========================================================================
    // STEP-BY-STEP EXECUTION
    // =========================================================================

    /**
     * Execute a SINGLE node in step-by-step mode.
     *
     * This method does NOT recurse to children - it returns after one node completes.
     * State is persisted to DB via emitNodeComplete().
     *
     * NEW SIMPLIFIED SPLIT SYSTEM:
     * - Split nodes: Use SplitNodeExecutor (creates context, completes immediately)
     * - Merge nodes: Use SplitMergeHandler (aggregates results, closes context)
     * - Other nodes: Use SplitAwareNodeExecutor (detects context, executes for ALL items)
     */
    public StepByStepExecutionResult executeSingleNode(
            String nodeId,
            ExecutionTree tree,
            ExecutionContext context,
            WorkflowExecution execution,
            V2ExecutionEventService eventService,
            TriggerItem item) {
        return executeSingleNode(nodeId, tree, context, execution, eventService, item,
            com.apimarketplace.orchestrator.execution.v2.split.SplitExecutionOptions.NONE);
    }

    /**
     * Options-aware variant: {@code options.perItemContinuation()} flips the split
     * fan-out into the per-item continuation walk disposition (see
     * {@link com.apimarketplace.orchestrator.execution.v2.split.SplitExecutionOptions}).
     */
    public StepByStepExecutionResult executeSingleNode(
            String nodeId,
            ExecutionTree tree,
            ExecutionContext context,
            WorkflowExecution execution,
            V2ExecutionEventService eventService,
            TriggerItem item,
            com.apimarketplace.orchestrator.execution.v2.split.SplitExecutionOptions options) {

        logger.info("[V2StepByStep] Executing single node: nodeId={}, runId={}, workflowRunId={}, itemIndex={}, itemId={}",
            nodeId, tree.getRunId(), tree.getWorkflowRunId(), context.itemIndex(), context.itemId());

        // Search from ALL root nodes (multi-workflow support)
        ExecutionNode node = findNodeFromAllRoots(tree, nodeId);
        if (node == null) {
            throw new IllegalArgumentException("Node not found in execution tree: " + nodeId);
        }

        int itemIndex = context.itemIndex();
        String runId = tree.getRunId();

        // Handle skipped node. Per-item continuation walks BYPASS this node-level guard:
        // their upstream chain nodes deliberately have no node-level completion mark yet
        // (suppressGlobalMark until the seal), so canExecute - which reads node-level
        // completion - would wrongly SKIP-cascade a mid-region node. Reachability in a
        // walk is decided PER ITEM by the fan-out's row-derived routing instead: a node
        // whose predecessor rows route nothing to it no-ops with a benign deferred
        // result, never a skip.
        boolean perItemContinuationWalk = options != null && options.perItemContinuation();
        if (!perItemContinuationWalk && !node.canExecute(context)) {
            logger.info("[V2StepByStep] Node cannot execute, handling as skipped: nodeId={}, type={}, predecessors={}, completedInContext={}",
                nodeId, node.getType(), node.getPredecessorIds(),
                node.getPredecessorIds().stream().filter(context::isCompleted).toList());
            return handleSkippedNode(node, nodeId, context, execution, eventService, item, itemIndex, tree);
        }

        // Build node map for split context lookups (from ALL roots)
        Map<String, ExecutionNode> nodeMap = buildNodeMapFromAllRoots(tree);

        // Initialize context
        logger.info("[V2StepByStep] Initializing context for nodeId={}", nodeId);
        ExecutionContext contextWithStart = initializeNodeContextSimplified(node, nodeId, context);
        logger.info("[V2StepByStep] Context initialized, isStarted({})={}", nodeId, contextWithStart.isStarted(nodeId));

        // Emit node start with per-epoch context (P2.3.1).
        eventService.emitNodeStart(execution, node, item, itemIndex, contextWithStart.epoch());

        NodeExecutionResult result;
        long engineStartMs = System.currentTimeMillis();

        // === NEW SIMPLIFIED SPLIT HANDLING ===
        if (node.isSplitNode()) {
            // Check for nested split (split inside another split scope)
            String parentScopeKey = SplitContextManager.extractParentScopeKey(contextWithStart);

            // Fallback: check predecessor IDs against registered contexts
            if (parentScopeKey == null && splitContextManager.hasContexts(runId)) {
                for (String predId : node.getPredecessorIds()) {
                    String basePredId = predId;
                    com.apimarketplace.orchestrator.utils.EdgeRefParser.EdgeRef ref =
                        com.apimarketplace.orchestrator.utils.EdgeRefParser.parse(predId);
                    if (ref != null && ref.port() != null && !ref.port().isEmpty()) {
                        basePredId = ref.nodeType() + ":" + ref.nodeLabel();
                    }
                    if (splitContextManager.getContext(runId, basePredId, itemIndex).isPresent()) {
                        parentScopeKey = "predecessor_split";
                        logger.info("[V2StepByStep] Nested split detected via predecessor context: nodeId={}, predecessorSplit={}",
                            nodeId, basePredId);
                        break;
                    }
                }
            }

            if (parentScopeKey != null) {
                // Nested split in step-by-step mode: execute the inner split via SplitAwareNodeExecutor
                logger.info("[V2StepByStep] Nested split detected: nodeId={}, parentScope={}", nodeId, parentScopeKey);
                result = splitAwareExecutor.executeNestedSplit(
                    node, contextWithStart, runId, nodeMap,
                    execution, item, itemIndex, null, splitNodeExecutor);
            } else {
                // Top-level split node: use SplitNodeExecutor
                logger.info("[V2StepByStep] Executing split node with simplified system: nodeId={}", nodeId);
                result = executeSplitNodeSimplified(node, nodeId, runId, contextWithStart, itemIndex);
            }

        } else if (node.isMergeNode() && splitMergeHandler.isSplitMerge(runId, nodeId, itemIndex, nodeMap)) {
            // Merge node after split: use SplitMergeHandler
            logger.info("[V2StepByStep] Executing split merge: nodeId={}, workflowItem={}", nodeId, itemIndex);
            result = splitMergeHandler.handleMerge(runId, nodeId, itemIndex, contextWithStart, nodeMap);

        } else if (node.isAggregateNode() && splitAggregateHandler.isSplitAggregate(runId, nodeId, itemIndex, nodeMap)) {
            // Aggregate node after split: use SplitAggregateHandler (N -> 1 reduction)
            logger.info("[V2StepByStep] Executing split aggregate: nodeId={}, workflowItem={}", nodeId, itemIndex);
            result = splitAggregateHandler.handleAggregate(runId, nodeId, itemIndex, contextWithStart, nodeMap);

        } else {
            // Other nodes: use SplitAwareNodeExecutor (handles split context automatically)
            // Each item result is persisted individually with its own item_index
            // In step-by-step mode, successors are handled via calculateReadyNodes, so pass null
            logger.info("[V2StepByStep] Executing node (split-aware): nodeId={}", nodeId);
            // Default disposition keeps the LEGACY call shape (zero change for pre-feature
            // callers and test doubles); only a per-item continuation walk threads options.
            result = perItemContinuationWalk
                ? splitAwareExecutor.execute(node, contextWithStart, runId, nodeMap, execution, item, itemIndex, null,
                    options)
                : splitAwareExecutor.execute(node, contextWithStart, runId, nodeMap, execution, item, itemIndex, null);
        }

        // Override duration with engine-measured wall-clock time for non-yielding nodes.
        // Same as executeNodeCore - ensures ALL node types get accurate duration,
        // not just nodes that measure their own time internally.
        if (!result.isAwaitingSignal() && !result.isAsyncRunning()) {
            long engineDurationMs = System.currentTimeMillis() - engineStartMs;
            result = result.withDuration(engineDurationMs);
        }

        logger.info("[V2StepByStep] Node executed: nodeId={}, status={}, durationMs={}, hasOutput={}, outputSize={}",
            nodeId, result.status(), result.durationMs(),
            result.output() != null, result.output() != null ? result.output().size() : 0);

        ExecutionContext updatedContext = contextWithStart.withResult(nodeId, result);

        // Handle AWAITING_SIGNAL - yield without completing (same as auto mode)
        if (result.isAwaitingSignal()) {
            logger.info("[V2StepByStep] Node awaiting signal, yielding: nodeId={}, type={}",
                nodeId, result.metadata().get("signal_type"));
            eventService.emitNodeAwaitingSignal(execution, node, result, item, itemIndex, updatedContext);
            return new StepByStepExecutionResult(updatedContext, result, Set.of(), false);
        }

        // Handle async-running (engine-owned async I/O) - yield without completing.
        // Same semantics as auto mode: status stays RUNNING, completion is delivered later.
        if (result.isAsyncRunning()) {
            logger.info("[V2StepByStep] Node async running, yielding: nodeId={}, correlationId={}",
                nodeId, result.metadata().get(NodeExecutionResult.ASYNC_CORRELATION_ID));
            eventService.emitNodeAsyncRunning(execution, node, result, item, itemIndex, updatedContext);
            return new StepByStepExecutionResult(updatedContext, result, Set.of(), false);
        }

        // Emit node complete event (skip if split already persisted each item)
        if (!ExecutionMetadataKeys.isSplitAlreadyPersisted(result.metadata())) {
            var completion = eventService.emitNodeComplete(execution, node, result, item, itemIndex, updatedContext);
            // Payload-lost rewrite (tier 2, traversal truth) - same contract as
            // executeNodeCore: the row/snapshot already say FAILED, so the
            // step-by-step path must decide successors/readyNodes and run the
            // skip cascade below from the SAME failure, not the node's
            // original success.
            if (completion != null && completion.payloadLost() && result.isSuccess()) {
                logger.error("💥 [V2StepByStep] Output payload lost - treating node as FAILED for traversal: nodeId={}, runId={}, message={}",
                    nodeId, runId, completion.payloadLostMessage());
                result = NodeExecutionResult.failure(nodeId, completion.payloadLostMessage(), result.durationMs());
                updatedContext = contextWithStart.withResult(nodeId, result);
            }
        } else {
            logger.debug("[V2StepByStep] Skipping emitNodeComplete (split already persisted): nodeId={}", nodeId);
        }

        // StopOnError - hard-stop the entire workflow (all branches).
        // In step-by-step mode, throw WorkflowStoppedException so the caller
        // (V2StepByStepService) can mark the run as FAILED.
        if (node.isStopOnErrorNode()) {
            String errorMsg = result.errorMessage().orElse("Workflow stopped due to error");
            String errorCode = result.output() != null
                    ? String.valueOf(result.output().getOrDefault("error_code", ""))
                    : "";
            logger.info("🛑 [V2StepByStep] StopOnError - stopping entire workflow: nodeId={}, errorMessage={}", nodeId, errorMsg);
            throw new WorkflowStoppedException(nodeId, errorMsg, errorCode, result);
        }

        // Propagate SKIPPED to downstream nodes on failure (same routine as auto-mode traverseTree).
        // Also fires on a SKIPPED result with CASCADE_SKIP_TO_SUCCESSORS metadata - see
        // the comment at the auto-mode call site for the routing-skip exclusion.
        if (shouldCascadeSkipFromResult(result) && skipPropagationService != null && node instanceof BaseNode baseNode) {
            skipPropagationService.cascadeFailureToSuccessors(
                execution, baseNode, context.itemIndex(),
                context.epoch(), context.triggerId(),
                /*perItemScope=*/ false,
                V2SkipPropagationService.SOURCE_SYNC);
        }

        // Lifecycle callback
        node.onComplete(updatedContext, result);

        // Handle back-edge (loop) iteration in step-by-step mode.
        // Uses executeBackEdgeIteration which returns ready nodes instead of auto-traversing.
        // Only when no regular successors (decision nodes with iterate on one port must follow selected branch).
        List<ExecutionNode> stepNextNodes = node.getNextNodes(result);
        if (stepNextNodes.isEmpty() && backEdgeHandler.hasBackEdge(node, updatedContext.plan())) {
            Map<String, ExecutionNode> backEdgeNodeMap = buildNodeMapFromAllRoots(tree);
            StepByStepExecutionResult backEdgeResult = backEdgeHandler.executeBackEdgeIteration(
                node, nodeId, result, updatedContext, execution, eventService, item, context.itemIndex(), backEdgeNodeMap);
            updatedContext = backEdgeResult.context();
        }

        // Calculate ready nodes
        Set<String> readyNodes = readyNodeCalculator.calculateReadyNodes(updatedContext, tree);

        logger.info("[V2StepByStep] Node {} completed. Ready nodes: {}", nodeId, readyNodes);
        return StepByStepExecutionResult.success(updatedContext, result, readyNodes);
    }

    /**
     * Execute a split node using the simplified system.
     * Split evaluates source expression, creates context, and completes immediately.
     * Context is scoped to the workflow item index for proper isolation.
     */
    private NodeExecutionResult executeSplitNodeSimplified(
            ExecutionNode node, String nodeId, String runId, ExecutionContext context, int workflowItemIndex) {

        // Get split configuration from the node using polymorphic interface methods
        if (!node.isSplitNode()) {
            logger.warn("[V2StepByStep] Node is not a SplitNode: nodeId={}", nodeId);
            return node.execute(context);
        }

        String sourceExpression = node.getListExpression();
        int maxItems = node.getSplitMaxItems();

        logger.info("[V2StepByStep] Split executing: nodeId={}, expression={}, maxItems={}, workflowItem={}",
            nodeId, sourceExpression, maxItems, workflowItemIndex);

        return splitNodeExecutor.execute(runId, nodeId, sourceExpression, maxItems, workflowItemIndex, context);
    }


    /**
     * Initialize node context (simplified version without legacy split state).
     */
    private ExecutionContext initializeNodeContextSimplified(ExecutionNode node, String nodeId, ExecutionContext context) {
        return context.withStart(nodeId);
    }

    /**
     * Returns true when {@code result} should drive a SKIPPED cascade through
     * the failed-cascade routine. Two cases:
     *
     * <ol>
     *   <li>{@code result.isFailure()} - original contract: failure cascades
     *       SKIPPED to all successors via
     *       {@link com.apimarketplace.orchestrator.execution.v2.services.V2SkipPropagationService#cascadeFailureToSuccessors}.</li>
     *   <li>{@code result.isSkipped() &&
     *       metadata[CASCADE_SKIP_TO_SUCCESSORS]==true} - explicit
     *       handshake from a terminal-skip node (e.g.
     *       {@link com.apimarketplace.orchestrator.execution.v2.split.SplitAggregateHandler}
     *       when no items routed through the aggregate's predecessor
     *       branch). Without this flag a SKIPPED still exposes its
     *       successors through {@link com.apimarketplace.orchestrator.execution.v2.nodes.BaseNode#getNextNodes}
     *       (which only filters on isFailure), so downstream linear nodes
     *       would run with empty input.</li>
     * </ol>
     *
     * <p>Routing skips from Decision/Switch nodes don't set the flag - they
     * rely on per-port edge filtering and must not cascade across sibling
     * branches.
     */
    // Package-private for direct unit testing - branch coverage on the four
    // states (failure, skipped+flag, skipped-no-flag, completed) belongs at the
    // engine level since this is the engine's contract with handler nodes.
    static boolean shouldCascadeSkipFromResult(NodeExecutionResult result) {
        if (result == null) return false;
        if (result.isFailure()) {
            // continueOnFailure policy: the node stays FAILED but traversal proceeds to
            // its successors - the SKIPPED cascade must NOT pre-mark them (it would race
            // the continuation and dead-end the branch). See ExecutionMetadataKeys.
            return !ExecutionMetadataKeys.isContinueOnFailure(result.metadata());
        }
        if (!result.isSkipped()) return false;
        Map<String, Object> meta = result.metadata();
        if (meta == null) return false;
        Object flag = meta.get(com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys.CASCADE_SKIP_TO_SUCCESSORS);
        return flag instanceof Boolean b && b;
    }

    /**
     * Handle a node that cannot execute (prerequisites not met).
     */
    private StepByStepExecutionResult handleSkippedNode(
            ExecutionNode node, String nodeId, ExecutionContext context,
            WorkflowExecution execution, V2ExecutionEventService eventService,
            TriggerItem item, int itemIndex, ExecutionTree tree) {

        logger.debug("[V2StepByStep] Skipping node (cannot execute): nodeId={}", nodeId);

        NodeExecutionResult skipResult = NodeExecutionResult.skipped(nodeId, ExecutionMetadataKeys.SKIPPED_MESSAGE);
        ExecutionContext updatedContext = context.withResult(nodeId, skipResult);
        eventService.emitNodeComplete(execution, node, skipResult, item, itemIndex, updatedContext);

        Set<String> readyNodes = readyNodeCalculator.calculateReadyNodes(updatedContext, tree);
        return StepByStepExecutionResult.skipped(updatedContext, skipResult, readyNodes);
    }

    /**
     * Execute a node with split awareness (AUTO mode).
     *
     * Uses the new simplified split system:
     * - Split nodes: SplitNodeExecutor creates context and completes immediately
     * - Merge nodes: SplitMergeHandler aggregates results if in split scope
     * - First node after split: SplitAwareNodeExecutor executes N times, launches N traversals
     * - Other nodes: Normal execution (already in a split traversal)
     *
     * For split-aware execution:
     * - Each item result is persisted individually with item_index
     * - N parallel traversals are launched for successors
     * - Returns with split_successors_handled=true to prevent engine from re-traversing
     */
    private NodeExecutionResult executeNodeWithSplitAwareness(
            ExecutionNode node,
            ExecutionContext context,
            String runId,
            WorkflowExecution execution,
            V2ExecutionEventService eventService,
            TriggerItem item,
            int itemIndex) {

        String nodeId = node.getNodeId();

        // Build node map for split context lookups (cached per execution would be better)
        // For now, we build a minimal map - in production, this should be optimized
        Map<String, ExecutionNode> nodeMap = new HashMap<>();
        nodeMap.put(nodeId, node);

        // Add predecessors to map for context detection
        // Note: predecessorIds are now available via ExecutionNode interface
        // The full tree would be needed for complete context detection
        // for (String predId : node.getPredecessorIds()) { ... }

        if (node.isSplitNode()) {
            // Check if this split node is inside a parent split scope (nested split).
            // If the execution context already has parent split data (current_split_id set),
            // the node needs to be executed N times, once per parent item.
            String parentScopeKey = SplitContextManager.extractParentScopeKey(context);

            // Fallback: when the context doesn't have parent split data yet (e.g., direct
            // successor of another split node), check if any predecessor has a registered
            // split context. This handles the case where outer_split -> inner_split and
            // the context hasn't been enriched with item data yet.
            if (parentScopeKey == null && splitContextManager.hasContexts(runId)) {
                for (String predId : node.getPredecessorIds()) {
                    // Strip port from predecessor ID if present
                    String basePredId = predId;
                    com.apimarketplace.orchestrator.utils.EdgeRefParser.EdgeRef ref =
                        com.apimarketplace.orchestrator.utils.EdgeRefParser.parse(predId);
                    if (ref != null && ref.port() != null && !ref.port().isEmpty()) {
                        basePredId = ref.nodeType() + ":" + ref.nodeLabel();
                    }
                    if (splitContextManager.getContext(runId, basePredId, itemIndex).isPresent()) {
                        // Found a parent split context via predecessor - treat as nested split
                        parentScopeKey = "predecessor_split";
                        logger.info("[AUTO] Nested split detected via predecessor context: nodeId={}, predecessorSplit={}, itemIndex={}",
                            nodeId, basePredId, itemIndex);
                        break;
                    }
                }
            }

            if (parentScopeKey != null) {
                // Nested split: this split node is inside another split scope.
                // Let SplitAwareNodeExecutor handle it by executing N times, once per parent item.
                // Each execution will create an inner split context with a unique scoped key.
                logger.info("[AUTO] Nested split detected: nodeId={}, parentScope={}, listExpression={}, itemIndex={}",
                    nodeId, parentScopeKey, node.getListExpression(), itemIndex);

                SplitAwareNodeExecutor.SuccessorTraverser successorTraverser =
                    (successor, ctx, subItemIndex) -> {
                        logger.debug("[Split] Traversing successor for subItem {}: nodeId={}",
                            subItemIndex, successor.getNodeId());
                        return traverseTree(successor, ctx, execution, eventService, item);
                    };

                return splitAwareExecutor.executeNestedSplit(
                    node, context, runId, nodeMap,
                    execution, item, itemIndex, successorTraverser,
                    splitNodeExecutor);
            }

            // Top-level split node: use SplitNodeExecutor directly
            logger.info("[AUTO] Executing split node: nodeId={}, type={}, listExpression={}, maxItems={}, itemIndex={}",
                nodeId, node.getType(), node.getListExpression(), node.getSplitMaxItems(), itemIndex);
            return executeSplitNodeSimplified(node, nodeId, runId, context, itemIndex);

        } else if (node.isMergeNode()) {
            // Merge node: check if it's aggregating split results
            // Note: For full detection, we need the node map with predecessors
            // The merge handler will check if there's an active split context
            if (splitContextManager.hasContexts(runId)) {
                logger.info("🔀 [AUTO] Checking if merge is for split: nodeId={}, type={}, workflowItem={}, hasContexts={}",
                    nodeId, node.getType(), itemIndex, splitContextManager.hasContexts(runId));
                // Try to find split context for this merge (scoped to workflow item)
                // If found, handle as split merge (aggregation N→1)
                if (splitMergeHandler.isSplitMerge(runId, nodeId, itemIndex, nodeMap)) {
                    return splitMergeHandler.handleMerge(runId, nodeId, itemIndex, context, nodeMap);
                }
                // isSplitMerge() returned false but there IS a split context upstream.
                // This is a branch-rejoin merge (e.g., classify → 5 branches → merge).
                // Route through SplitAwareNodeExecutor for per-item execution.
                SplitAwareNodeExecutor.SuccessorTraverser successorTraverser =
                    (successor, ctx, subItemIndex) -> {
                        logger.debug("🔁 [Split] Traversing successor for subItem {}: nodeId={}",
                            subItemIndex, successor.getNodeId());
                        return traverseTree(successor, ctx, execution, eventService, item);
                    };
                return splitAwareExecutor.execute(
                    node, context, runId, nodeMap,
                    execution, item, itemIndex, successorTraverser);
            }
            // Regular merge (no split context) - execute normally
            return node.execute(context);

        } else if (node.isAggregateNode()) {
            // Aggregate node: reduces N split items to 1 output
            // Similar to merge, but specifically for data aggregation (N -> 1 reduction)
            if (splitContextManager.hasContexts(runId)) {
                logger.info("📊 [AUTO] Checking if aggregate is for split: nodeId={}, type={}, workflowItem={}, hasContexts={}",
                    nodeId, node.getType(), itemIndex, splitContextManager.hasContexts(runId));
                if (splitAggregateHandler.isSplitAggregate(runId, nodeId, itemIndex, nodeMap)) {
                    return splitAggregateHandler.handleAggregate(runId, nodeId, itemIndex, context, nodeMap);
                }
            }
            // No split context - execute normally (standalone aggregate)
            return node.execute(context);

        } else {
            // Other nodes: use SplitAwareNodeExecutor
            // For the FIRST node after split, this will:
            // 1. Execute N times (once per split item)
            // 2. Persist each result individually
            // 3. Launch N parallel traversals for successors
            //
            // For subsequent nodes (already in a split traversal), this executes normally
            SplitAwareNodeExecutor.SuccessorTraverser successorTraverser =
                (successor, ctx, subItemIndex) -> {
                    logger.debug("🔁 [Split] Traversing successor for subItem {}: nodeId={}",
                        subItemIndex, successor.getNodeId());
                    // Note: We use the parent item but the context has subItem data
                    return traverseTree(successor, ctx, execution, eventService, item);
                };

            return splitAwareExecutor.execute(
                node, context, runId, nodeMap,
                execution, item, itemIndex, successorTraverser);
        }
    }

    // =========================================================================
    // UTILITY METHODS
    // =========================================================================

    /**
     * Create a node finder function that can locate any ExecutionNode by ID.
     * Searches from cached root nodes (populated at execution start) to find
     * upstream nodes needed by BackEdgeHandler.
     */
    private java.util.function.Function<String, ExecutionNode> createNodeFinder(ExecutionContext context, ExecutionNode currentNode) {
        return targetId -> {
            // First try from cached root nodes
            List<ExecutionNode> roots = rootNodesByRunId.get(context.runId());
            if (roots != null) {
                for (ExecutionNode root : roots) {
                    ExecutionNode found = nodeSearchService.findNodeById(root, targetId);
                    if (found != null) return found;
                }
            }
            // Fallback: search from current node (only finds successors)
            return nodeSearchService.findNodeById(currentNode, targetId);
        };
    }

    /**
     * Shutdown executor service gracefully.
     * Called automatically by Spring on application shutdown.
     */
    @PreDestroy
    public void shutdown() {
        logger.info("🛑 Shutting down execution engine");
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.warn("⚠️ Executor did not terminate in 30s, forcing shutdown");
                executorService.shutdownNow();
                if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                    logger.error("❌ Executor did not terminate after force shutdown");
                }
            }
        } catch (InterruptedException e) {
            logger.warn("⚠️ Shutdown interrupted, forcing immediate shutdown");
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("✅ Execution engine shutdown complete");
    }

    /**
     * Get the initial ready nodes for step-by-step mode.
     */
    public Set<String> getInitialReadyNodes(ExecutionTree tree) {
        return readyNodeCalculator.getInitialReadyNodes(tree);
    }

    /**
     * Calculate which nodes are ready to execute after a node completion.
     */
    public Set<String> calculateReadyNodes(ExecutionContext context, ExecutionTree tree) {
        return readyNodeCalculator.calculateReadyNodes(context, tree);
    }

    /**
     * Find a node by ID in the execution tree.
     * Delegates to NodeSearchService.
     */
    public ExecutionNode findNodeById(ExecutionNode root, String nodeId) {
        return nodeSearchService.findNodeById(root, nodeId);
    }

    /**
     * Find a node by ID searching from ALL root nodes (multi-workflow support).
     * Delegates to NodeSearchService.
     *
     * @param tree The execution tree with potentially multiple roots
     * @param nodeId The node ID to find
     * @return The found node, or null if not found in any tree
     */
    public ExecutionNode findNodeFromAllRoots(ExecutionTree tree, String nodeId) {
        return nodeSearchService.findNodeFromAllRoots(tree, nodeId);
    }

    /**
     * Build a node map from ALL root nodes (multi-workflow support).
     * Delegates to NodeSearchService.
     *
     * @param tree The execution tree with potentially multiple roots
     * @return Map of nodeId -> ExecutionNode for ALL nodes in all trees
     */
    public Map<String, ExecutionNode> buildNodeMapFromAllRoots(ExecutionTree tree) {
        return nodeSearchService.buildNodeMapFromAllRoots(tree);
    }

    /**
     * Build a map of nodeId -> ExecutionNode for the entire tree.
     * Delegates to NodeSearchService.
     */
    public Map<String, ExecutionNode> buildNodeMap(ExecutionNode root) {
        return nodeSearchService.buildNodeMap(root);
    }
}
