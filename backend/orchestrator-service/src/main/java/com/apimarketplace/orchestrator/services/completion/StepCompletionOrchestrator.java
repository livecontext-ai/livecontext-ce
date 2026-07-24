package com.apimarketplace.orchestrator.services.completion;

import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.workflow.DiagnosticFieldLimits;
import com.apimarketplace.orchestrator.domain.workflow.Step;
import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import com.apimarketplace.orchestrator.services.WorkflowPersistenceService;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.persistence.StepPersistenceResult;
import com.apimarketplace.orchestrator.services.persistence.WorkflowEntityResolverService;
import com.apimarketplace.orchestrator.services.merge.MergeIntegrationService;
import com.apimarketplace.orchestrator.services.merge.MergeResult;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.orchestrator.metrics.WorkflowMetrics;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import com.apimarketplace.orchestrator.services.streaming.events.StepLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Single Responsibility: Orchestrates the completion of workflow steps.
 *
 * <h2>Purpose</h2>
 * This is the <b>SINGLE ENTRY POINT</b> for step completion in the entire system.
 * No other service should directly call {@code persistenceService.recordStep()}.
 *
 * <h2>Responsibilities (Option A - DB Only)</h2>
 * <ol>
 *   <li>Persist to DB (via WorkflowPersistenceService)</li>
 *   <li>Update StateSnapshot in DB (SINGLE source of truth for statusCounts)</li>
 *   <li>Emit streaming event (via WorkflowEventPublisher) - reads counts from DB</li>
 * </ol>
 *
 * <h2>Epoch Management</h2>
 * StatusCounts accumulate across epochs in StateSnapshot. This is intentional for display
 * purposes (e.g., "total webhooks processed"). Execution state is managed separately
 * by WorkflowStateManager which is reset between epochs.
 *
 * @see StepCompletionContext
 * @see SkipContext
 * @see StepCompletionResult
 */
@Service
public class StepCompletionOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(StepCompletionOrchestrator.class);

    private final WorkflowPersistenceService persistenceService;
    private final WorkflowEventPublisher eventPublisher;
    private final MergeIntegrationService mergeIntegrationService;
    private final StateSnapshotService stateSnapshotService;
    private final WorkflowEpochService workflowEpochService;
    private final WorkflowEntityResolverService entityResolverService;
    private final CreditConsumptionClient creditClient;
    private final WorkflowMetrics workflowMetrics;
    private final WorkflowStepDataRepository stepDataRepository;

    /**
     * Flat per-node platform fee toggle. The fee is a CLOUD monetization (the platform runs the
     * orchestrator for the user). CE self-hosts the orchestrator, so it runs workflows itself and
     * owes no platform fee - {@code application-ce.yml} sets this {@code false}. Default {@code true}
     * (cloud). The field initializer keeps it {@code true} for constructor-based unit tests that
     * build this service without a Spring context (so existing billing assertions still hold).
     */
    @Value("${workflow.node-billing.enabled:true}")
    private boolean nodeBillingEnabled = true;

    @Autowired
    public StepCompletionOrchestrator(
            WorkflowPersistenceService persistenceService,
            WorkflowEventPublisher eventPublisher,
            MergeIntegrationService mergeIntegrationService,
            StateSnapshotService stateSnapshotService,
            WorkflowEpochService workflowEpochService,
            WorkflowEntityResolverService entityResolverService,
            CreditConsumptionClient creditClient,
            WorkflowMetrics workflowMetrics,
            @Autowired(required = false) WorkflowStepDataRepository stepDataRepository) {
        this.persistenceService = persistenceService;
        this.eventPublisher = eventPublisher;
        this.mergeIntegrationService = mergeIntegrationService;
        this.stateSnapshotService = stateSnapshotService;
        this.workflowEpochService = workflowEpochService;
        this.entityResolverService = entityResolverService;
        this.creditClient = creditClient;
        this.workflowMetrics = workflowMetrics;
        this.stepDataRepository = stepDataRepository;
    }

    /**
     * Backward-compat 8-arg constructor for existing test fixtures that don't yet
     * wire the {@link WorkflowStepDataRepository}. The Phase 2.E aggregate path
     * (recordSplitAggregateIfMissing) is a no-op when the repository is null.
     */
    public StepCompletionOrchestrator(
            WorkflowPersistenceService persistenceService,
            WorkflowEventPublisher eventPublisher,
            MergeIntegrationService mergeIntegrationService,
            StateSnapshotService stateSnapshotService,
            WorkflowEpochService workflowEpochService,
            WorkflowEntityResolverService entityResolverService,
            CreditConsumptionClient creditClient,
            WorkflowMetrics workflowMetrics) {
        this(persistenceService, eventPublisher, mergeIntegrationService, stateSnapshotService,
            workflowEpochService, entityResolverService, creditClient, workflowMetrics, null);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API - Step Completion
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Complete a step execution using a context object.
     *
     * <p>This is the preferred method for step completion. It uses an immutable
     * context object that validates all required parameters at construction time.</p>
     *
     * <p><b>Option A (DB Only):</b> StatusCounts are read exclusively from StateSnapshotService (DB).
     * No in-memory cache is used. Counts accumulate across epochs for display purposes.</p>
     *
     * @param ctx The completion context containing all required data
     * @return The completion result with persistence status and statusCounts
     */
    public StepCompletionResult complete(StepCompletionContext ctx) {
        return complete(ctx, null);
    }

    /**
     * Complete a step execution with optional triggerId for epoch-scoped snapshot updates.
     */
    public StepCompletionResult complete(StepCompletionContext ctx, String triggerId) {
        return complete(ctx, triggerId, CompletionKind.TERMINAL, /*persistRow*/ true);
    }

    /**
     * SINGLE parameterized completion pipeline - used by BOTH the terminal path
     * ({@link #complete(StepCompletionContext, String)} → {@code TERMINAL},
     * {@code persistRow=true}) and the non-final NodePolicy retry-attempt path
     * ({@link #completeAttempt(StepCompletionContext, String, boolean)} →
     * {@code NON_FINAL_ATTEMPT}). Every divergence between the two dispositions
     * branches on an explicit {@link CompletionKind} accessor at the line where it
     * is decided, so there are no mirror methods to keep in sync.
     *
     * @param ctx        completion context (attempt path: carries the ANNOTATED
     *                   failed attempt, stamped {@code policy_attempt}/{@code policy_max_attempts})
     * @param triggerId  DAG trigger id (row placement; snapshot writes happen only
     *                   for {@code TERMINAL})
     * @param kind       disposition - see {@link CompletionKind} for the per-branch contract
     * @param persistRow when true, insert the step_data row. {@code TERMINAL} always
     *                   passes true; attempts pass false in loop contexts (see
     *                   {@link CompletionKind#persistsRowInLoopContext()}). Subject to
     *                   the v6 unique index: only the FIRST failed attempt of a logical
     *                   execution actually lands; later ones dedupe.
     */
    public StepCompletionResult complete(StepCompletionContext ctx, String triggerId,
            CompletionKind kind, boolean persistRow) {
        if (kind == CompletionKind.TERMINAL) {
            logger.info("[StepCompletion] Completing: runId={}, nodeId={}, nodeLabel={}, item={}, iter={}, status={}, epoch={}, triggerId={}, durationMs={}, outputKeys={}, errorMessage={}",
                ctx.runId(), ctx.nodeId(), ctx.nodeLabel(), ctx.itemIndex(), ctx.iteration(),
                ctx.result().status(), ctx.epoch(), triggerId, ctx.result().executionTime(),
                ctx.result().output() != null ? ctx.result().output().keySet() : "null",
                ctx.result().error() != null ? ctx.result().error().getMessage() : "none");
        } else {
            logger.info("[StepCompletion] Non-final attempt: runId={}, nodeId={}, item={}, iter={}, status={}, epoch={}, triggerId={}, persistRow={}",
                ctx.runId(), ctx.nodeId(), ctx.itemIndex(), ctx.iteration(),
                ctx.result().status(), ctx.epoch(), triggerId, persistRow);
        }

        // 1. Enrich result with iteration context - same enrichment for both dispositions
        // so the persisted row / event payload carry item_index + iteration exactly alike.
        StepExecutionResult enrichedResult = enrichResultWithContext(ctx.result(), ctx.itemIndex(), ctx.iteration());
        boolean emitDeferredSkippedAggregate = shouldEmitDeferredSkippedAggregate(enrichedResult);
        StepCompletionContext completionCtx = withResult(
            ctx, stripInternalCompletionMetadata(enrichedResult));

        // 2. Persist to DB - returns result with storage_id (use explicit epoch
        // for correct epoch isolation AND explicit triggerId so workflow_step_data
        // rows land under the right DAG instead of drifting to "trigger:default"
        // - CRITICAL 2 fix, 2026-05-21 e2e audit).
        // persistRow=false → loop-context non-final attempts are WS-only: an attempt
        // row would claim the iteration's single FAILED slot in the v6 unique index
        // and silently drop the iteration's TERMINAL row - see
        // CompletionKind.persistsRowInLoopContext for the full rationale.
        boolean persisted = false;
        StepPersistenceResult persistenceResult = null;
        if (persistRow) {
            persistenceResult = persistenceService.recordStep(
                completionCtx.execution(), completionCtx.nodeId(), completionCtx.nodeLabel(), completionCtx.nodeId(),
                completionCtx.result(), completionCtx.epoch(), triggerId);
            persisted = persistenceResult.persisted();
        }

        // 2b. Traversal truth (tier 2 of the payload-loss contract): when
        // persistence reports payloadLost for a SUCCESS result, the row already
        // landed as FAILED (tier 1) - rewrite the in-memory result to FAILED
        // with the same cause BEFORE the snapshot write below, so NodeCounts,
        // workflow_epochs, the WS step event and billing all follow the row
        // truth automatically. The rewritten result is also surfaced on the
        // returned StepCompletionResult (payloadLost/payloadLostMessage) so
        // engine callers that decide successors from their own copy of the
        // result treat this node as FAILED (skip-cascade) instead of
        // traversing its success path with a vanished output blob.
        //
        // Edge case (documented, not solved): the v6 unique index includes
        // status, so a crash-redelivery of the same completion after this
        // FAILED row landed could insert a COMPLETED sibling row if a later
        // delivery's payload write succeeds. Aggregation views tolerate the
        // FAILED+COMPLETED coexistence - see the attempt-row trade-off comment
        // on the billing-dedup branch below (same index, same tolerance).
        boolean payloadLost = false;
        String payloadLostMessage = null;
        if (persistenceResult != null && persistenceResult.payloadLost()
                && completionCtx.result().isSuccess()) {
            payloadLost = true;
            payloadLostMessage = "[storage] Output payload lost: "
                + persistenceResult.payloadLossCause().userMessage();
            StepExecutionResult originalResult = completionCtx.result();
            StepExecutionResult rewritten = new StepExecutionResult(
                originalResult.stepId(),
                com.apimarketplace.orchestrator.domain.execution.NodeStatus.FAILED,
                payloadLostMessage,
                originalResult.output(),
                originalResult.executionTime(),
                null);
            completionCtx = withResult(completionCtx, rewritten);
            logger.error("[StepCompletion] Output payload lost - rewriting SUCCESS result to FAILED: runId={}, nodeId={}, cause={}",
                completionCtx.runId(), completionCtx.nodeId(), persistenceResult.payloadLossCause());
        }

        // 3. Update NodeCounts to stay in sync with EdgeCounts.
        // Even when DB persistence is skipped (duplicate), we must increment NodeCounts
        // because EdgeCounts are always incremented by EdgeStatusService regardless of persistence.
        //
        // Phase 2.E (2026-04-29 prod-incident fix): when ctx.suppressGlobalMark() is true
        // (split-async path), skip the EpochState global mark - it'll be written ONCE at
        // barrier seal via recordSplitAggregateIfMissing. Per-item NodeCounts still update
        // so frontend statusCounts reflect each item's status.
        //
        // INVARIANT: any path that increments NodeCounts MUST also increment workflow_epochs
        // for the same (run, node, status, epoch). The two stores are parallel additive
        // counters and the per-epoch UI reads exclusively from workflow_epochs via
        // WorkflowEpochService.getEpochState. The suppression introduced in Phase 2.E
        // is about avoiding poisoning of EpochState.failedNodeIds (set semantics) -
        // it does NOT apply to the additive counter row in workflow_epochs.
        StateSnapshot.NodeCounts snapshotCounts;
        if (kind.mutatesSnapshotCounts()) {
            if (ctx.suppressGlobalMark()) {
                snapshotCounts = stateSnapshotService.incrementNodeCountsOnly(
                    completionCtx.runId(), completionCtx.nodeId(), completionCtx.result().status().name(), 1);
                // Per-epoch counter row - additive, status-keyed, mirrors the in-memory
                // increment above. Without this, split-async COMPLETED/FAILED items never
                // reach workflow_epochs and the per-epoch inspector shows completed=0.
                recordEpochNodeCount(
                    completionCtx.execution(), completionCtx.nodeId(), completionCtx.result().status().name(),
                    completionCtx.epoch(), triggerId);
                logger.debug("[StepCompletion] Suppressed global mark (split-async): runId={}, nodeId={}, item={}",
                    ctx.runId(), ctx.nodeId(), ctx.itemIndex());
            } else {
                snapshotCounts = stateSnapshotService.recordNodeCompletionAndGetCounts(
                    completionCtx.runId(), completionCtx.nodeId(), completionCtx.result().status().name(), triggerId,
                    completionCtx.epoch(), completionCtx.result().executionTime());
                // Record per-epoch node count (uses explicit epoch from context for parallel epoch isolation)
                recordEpochNodeCount(
                    completionCtx.execution(), completionCtx.nodeId(), completionCtx.result().status().name(),
                    completionCtx.epoch(), triggerId);
            }

            // Phase 2.A: also mark partial-failure when the sync inline path (createSummaryResult)
            // signaled it via output.split_partial_failure (StepExecutionResult has no metadata
            // field - the engine's NodeExecutionResult.metadata is dropped in conversion).
            // Async path writes this via recordSplitAggregateIfMissing instead.
            if (completionCtx.result().output() != null
                    && Boolean.TRUE.equals(completionCtx.result().output().get(ExecutionMetadataKeys.SPLIT_PARTIAL_FAILURE))
                    && triggerId != null
                    && !completionCtx.suppressGlobalMark()) {
                stateSnapshotService.markNodePartialFailure(
                    completionCtx.runId(), triggerId, completionCtx.epoch(), completionCtx.nodeId());
                logger.info("[StepCompletion] Marked partial-failure: runId={}, nodeId={}, epoch={}",
                    completionCtx.runId(), completionCtx.nodeId(), completionCtx.epoch());
            }
        } else {
            // READ-ONLY counts - a NON_FINAL_ATTEMPT mutates nothing (snapshot-pollution
            // fix, 2026-06-10 audit item 2): neither NodeCounts.failed nor
            // EpochState.failedNodeIds is touched. failedNodeIds is append-only
            // (markNodeCompleted does not remove entries), so recording the attempt there
            // would permanently poison the epoch state of a retry-then-success execution
            // and inflate failed+completed counts. The per-epoch workflow_epochs counter
            // row mirrors NodeCounts - both stay terminal-only. The counts fetched here
            // only feed the WS event payload. See CompletionKind.mutatesSnapshotCounts.
            snapshotCounts = stateSnapshotService.getNodeCounts(completionCtx.runId(), completionCtx.nodeId());
        }

        // Billing + merge-collector recording - TERMINAL only (CompletionKind.bills):
        // one platform credit per LOGICAL execution, charged on the terminal attempt;
        // and the merge collector must never observe a node that has not completed.
        if (kind.bills() && persisted) {
            logger.info("[StepCompletion] Persisted: runId={}, nodeId={}, nodeLabel={}, item={}, iter={}, status={}, storageId={}",
                completionCtx.runId(), completionCtx.nodeId(), completionCtx.nodeLabel(), completionCtx.itemIndex(), completionCtx.iteration(),
                completionCtx.result().status(), persistenceResult.storageId());

            // Consume 1 credit per node traversed (fire-and-forget, never blocks execution)
            // Count both success and failure - only skipped nodes are free
            if (completionCtx.result().isSuccess() || completionCtx.result().isFailure()) {
                consumeCreditForNode(completionCtx);
                // V148+ markup billing moved to catalog-service's
                // CatalogToolBillingService (post-success hook in
                // ToolExecutionManager). Workflow callers propagate the scope
                // via X-Lc-Billing-Scope-Kind=RUN + X-Lc-Billing-Scope-Id headers
                // in CatalogToolsGateway.executeTool. The orchestrator no
                // longer issues a separate consumeMarkupForNode call - the
                // unified path bills exactly once per tool call.
            }

            // Record completion to merge collector for ForEach merge tracking.
            // Payload-lost rewrite: the collector must observe the FAILED
            // result (the merge must not aggregate a success whose output blob
            // is gone) - pass the rewritten context in that case.
            recordMergeCompletion(payloadLost
                ? completionCtx
                : withResult(ctx, stripInternalCompletionMetadata(ctx.result())));
        } else if (kind.bills()) {
            logger.info("[StepCompletion] Duplicate (DB skipped, NodeCounts updated): runId={}, nodeId={}, item={}, iter={}, status={}",
                completionCtx.runId(), completionCtx.nodeId(), completionCtx.itemIndex(), completionCtx.iteration(),
                completionCtx.result().status());

            // Node-policy billing invariant: ONE platform credit per LOGICAL node execution,
            // regardless of retry attempts (non-final attempts are never billed - they route
            // through completeAttempt which has no billing call). A TERMINAL failure after
            // retries is `persisted=false` by construction in non-loop contexts: the first
            // failed attempt already occupies the single FAILED slot of the v6 unique index
            // (workflow_run_id, step_alias, trigger_id, iteration, item_index, epoch, spawn,
            // status), so the terminal FAILED INSERT dedupes. Without this branch the whole
            // logical execution would be billed ZERO credits. Detection is exact: only
            // NodePolicyRunner stamps POLICY_ATTEMPT/POLICY_MAX_ATTEMPTS, and only a
            // terminal failure arrives here with attempt == maxAttempts > 1 (non-final
            // attempts never enter complete()).
            //
            // TRADE-OFF (accepted): this branch flips duplicate handling from at-most-once
            // to AT-LEAST-ONCE billing for retried terminal failures - a crash-recovery
            // re-delivery of the SAME terminal FAILED result re-enters here (persisted=false
            // again) and bills a second credit. Pre-policy behavior silently absorbed such
            // duplicates unbilled; we prefer a rare double-billed failure over systematically
            // billing retried failures zero. Aggregation note: in non-loop contexts the
            // attempt-1 FAILED row coexists with a COMPLETED terminal row after a
            // retry-then-success, so DB-sourced views (StepAggregationService) may show
            // partial_success for a logically-successful node; the WS NodeCounts stay clean.
            if (completionCtx.result().isFailure()
                    && isRetriedTerminalAttempt(completionCtx.result())) {
                logger.info("[StepCompletion] Billing deduped terminal failure (retried node): runId={}, nodeId={}, item={}, iter={}",
                    completionCtx.runId(), completionCtx.nodeId(), completionCtx.itemIndex(), completionCtx.iteration());
                consumeCreditForNode(completionCtx);
            }
        }

        // 4. Build statusCounts map (counts already retrieved from write or fallback read)
        Map<String, Object> statusCountsMap = buildStatusCountsMap(snapshotCounts);

        // 5. Build and emit streaming event - BOTH dispositions (no silent attempts):
        // for NON_FINAL_ATTEMPT this is the FAILURE-lifecycle step event annotated with
        // policy_attempt/policy_max_attempts that lets the frontend render
        // "attempt k/N failed, retrying", carrying the read-only statusCounts from above.
        Map<String, Object> eventData = buildStepEventData(completionCtx, statusCountsMap);
        StepLifecycle lifecycle = mapStatusToLifecycle(completionCtx.result());
        eventPublisher.emitStep(completionCtx.runId(), completionCtx.nodeId(), eventData, lifecycle);
        if (emitDeferredSkippedAggregate) {
            emitAggregatedSkippedEvent(
                completionCtx.execution(), completionCtx.nodeId(), completionCtx.nodeLabel(), statusCountsMap,
                completionCtx.epoch(), triggerId,
                extractSkipReason(enrichedResult, "deferred split skip aggregate"));
        }

        if (kind == CompletionKind.TERMINAL) {
            logger.info("[StepCompletion] Done: runId={}, nodeId={}, nodeLabel={}, persisted={}, status={}, durationMs={}, counts={}",
                completionCtx.runId(), completionCtx.nodeId(), completionCtx.nodeLabel(), persisted,
                completionCtx.result().status(), completionCtx.result().executionTime(), statusCountsMap);
        } else {
            logger.info("[StepCompletion] Non-final attempt done: runId={}, nodeId={}, persistedRow={}, counts={}",
                completionCtx.runId(), completionCtx.nodeId(), persisted, statusCountsMap);
        }

        if (persisted && payloadLost) {
            return StepCompletionResult.persistedPayloadLost(statusCountsMap, eventData, payloadLostMessage);
        }
        return persisted
            ? StepCompletionResult.persisted(statusCountsMap, eventData)
            : StepCompletionResult.duplicate(statusCountsMap, eventData);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API - Non-final retry attempts (NodePolicy)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Record a NON-FINAL failed retry attempt of a node executing under a
     * {@link com.apimarketplace.orchestrator.domain.workflow.NodePolicy} with
     * {@code retryCount > 0}. The terminal attempt (success or exhausted failure)
     * goes through {@link #complete(StepCompletionContext, String)} as usual.
     *
     * <p>Thin back-compat shim over the SINGLE parameterized pipeline,
     * {@link #complete(StepCompletionContext, String, CompletionKind, boolean)} with
     * {@code NON_FINAL_ATTEMPT}. What an attempt does (persist its row outside loops,
     * always emit the annotated FAILURE step event) and does NOT do (no StateSnapshot /
     * workflow_epochs mutation, no billing, no merge recording - and callers skip the
     * edge tail) is decided inside that pipeline at the {@link CompletionKind} branch
     * points; see the {@code CompletionKind} accessor javadocs for the rationale of
     * each skip.
     *
     * @param ctx        completion context carrying the ANNOTATED failed attempt
     * @param triggerId  DAG trigger id (row placement only; no snapshot writes)
     * @param persistRow when true, insert the attempt's step_data row (subject to
     *                   the v6 unique index: only the FIRST failed attempt of a
     *                   logical execution actually lands; later ones dedupe).
     *                   False in loop contexts - see
     *                   {@link CompletionKind#persistsRowInLoopContext()}.
     * @return completion result with read-only statusCounts (persisted flag reflects
     *         the row insert when {@code persistRow}, else always duplicate)
     */
    public StepCompletionResult completeAttempt(StepCompletionContext ctx, String triggerId, boolean persistRow) {
        return complete(ctx, triggerId, CompletionKind.NON_FINAL_ATTEMPT, persistRow);
    }

    /**
     * Convenience overload mirroring the {@code completeStep} parameter shape used
     * by {@code NodeCompletionService}.
     */
    public StepCompletionResult completeAttempt(
            WorkflowExecution execution,
            String nodeId,
            String nodeLabel,
            StepExecutionResult result,
            Integer itemIndex,
            Integer iteration,
            int epoch,
            String triggerId,
            boolean persistRow) {
        StepCompletionContext ctx = StepCompletionContext.of(
            execution, nodeId, nodeLabel, result, itemIndex, iteration, epoch);
        return completeAttempt(ctx, triggerId, persistRow);
    }

    /**
     * True when this result is the TERMINAL attempt of a retried execution:
     * {@code policy_attempt == policy_max_attempts > 1} (stamped by
     * NodePolicyRunner.annotate). Non-final attempts never reach
     * {@link #complete} - they go through {@link #completeAttempt} - so within
     * complete() this identifies "a retried node's last attempt" exactly.
     */
    private boolean isRetriedTerminalAttempt(StepExecutionResult result) {
        if (result.output() == null) return false;
        Integer attempt = asInteger(result.output().get(ExecutionMetadataKeys.POLICY_ATTEMPT));
        Integer maxAttempts = asInteger(result.output().get(ExecutionMetadataKeys.POLICY_MAX_ATTEMPTS));
        return attempt != null && maxAttempts != null
            && maxAttempts > 1 && attempt.intValue() == maxAttempts.intValue();
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    /**
     * Complete a step execution (legacy signature for backward compatibility).
     *
     * @deprecated Use {@link #complete(StepCompletionContext)} instead
     */
    public boolean completeStep(
            WorkflowExecution execution,
            String nodeId,
            String nodeLabel,
            StepExecutionResult result,
            Integer itemIndex,
            Integer iteration) {

        StepCompletionContext ctx = StepCompletionContext.of(
            execution, nodeId, nodeLabel, result, itemIndex, iteration);
        return complete(ctx).persisted();
    }

    /**
     * Complete a step execution with explicit epoch for parallel epoch isolation.
     */
    public boolean completeStep(
            WorkflowExecution execution,
            String nodeId,
            String nodeLabel,
            StepExecutionResult result,
            Integer itemIndex,
            Integer iteration,
            int epoch) {
        return completeStep(execution, nodeId, nodeLabel, result, itemIndex, iteration, epoch, null);
    }

    /**
     * Complete a step execution with explicit epoch and triggerId for parallel epoch isolation.
     */
    public boolean completeStep(
            WorkflowExecution execution,
            String nodeId,
            String nodeLabel,
            StepExecutionResult result,
            Integer itemIndex,
            Integer iteration,
            int epoch,
            String triggerId) {
        return completeStep(execution, nodeId, nodeLabel, result, itemIndex, iteration, epoch, triggerId, false);
    }

    /**
     * Complete a step execution with the Phase 2.E suppressGlobalMark disposition:
     * per-item NodeCounts / row / billing / WS land normally, the node-level
     * EpochState mark is deferred to {@link #recordSplitAggregateIfMissing} (seal).
     * Used by split-async per-item deliveries and per-item continuation walks.
     */
    public boolean completeStep(
            WorkflowExecution execution,
            String nodeId,
            String nodeLabel,
            StepExecutionResult result,
            Integer itemIndex,
            Integer iteration,
            int epoch,
            String triggerId,
            boolean suppressGlobalMark) {
        return completeStepWithResult(execution, nodeId, nodeLabel, result,
            itemIndex, iteration, epoch, triggerId, suppressGlobalMark).persisted();
    }

    /**
     * Full-result variant of {@link #completeStep(WorkflowExecution, String,
     * String, StepExecutionResult, Integer, Integer, int, String, boolean)}.
     * Returns the whole {@link StepCompletionResult} so callers can observe
     * the payload-lost rewrite (tier 2) and drive their traversal decision
     * from the EFFECTIVE (possibly FAILED) status instead of the node's
     * original success.
     */
    public StepCompletionResult completeStepWithResult(
            WorkflowExecution execution,
            String nodeId,
            String nodeLabel,
            StepExecutionResult result,
            Integer itemIndex,
            Integer iteration,
            int epoch,
            String triggerId,
            boolean suppressGlobalMark) {

        StepCompletionContext ctx = StepCompletionContext.of(
            execution, nodeId, nodeLabel, result, itemIndex, iteration, epoch);
        if (suppressGlobalMark) {
            ctx = ctx.withSuppressGlobalMark(true);
        }
        return complete(ctx, triggerId);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API - Skip Completion
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Complete a skipped step using a context object.
     *
     * @param ctx The skip context containing all required data
     * @return The completion result
     */
    public StepCompletionResult completeSkipped(SkipContext ctx) {
        return completeSkipped(ctx, null);
    }

    /**
     * Complete a skipped step with optional triggerId for epoch-scoped snapshot updates.
     */
    public StepCompletionResult completeSkipped(SkipContext ctx, String triggerId) {
        logger.info("[StepCompletion] Skipping: runId={} nodeId={} item={} reason={} epoch={} triggerId={}",
            ctx.runId(), ctx.nodeId(), ctx.itemIndex(), ctx.skipReason(), ctx.epoch(), triggerId);

        // 1. Persist skipped node (use explicit epoch for correct epoch isolation)
        boolean persisted = ctx.iteration() > 0
            ? persistenceService.recordSkippedNode(
                ctx.execution(), ctx.nodeId(), ctx.nodeLabel(),
                ctx.skipReason(), ctx.skipSourceNode(), ctx.itemIndex(), ctx.iteration(), ctx.epoch(), triggerId)
            : persistenceService.recordSkippedNode(
                ctx.execution(), ctx.nodeId(), ctx.nodeLabel(),
                ctx.skipReason(), ctx.skipSourceNode(), ctx.itemIndex(), ctx.epoch(), triggerId);

        // 2. Update state if persisted AND get counts in single transaction
        // Use epoch-scoped version when triggerId is available for parallel epoch isolation.
        StateSnapshot.NodeCounts snapshotCounts;
        if (persisted) {
            snapshotCounts = stateSnapshotService.recordNodeCompletionAndGetCounts(
                ctx.runId(), ctx.nodeId(), "SKIPPED", triggerId, ctx.epoch());
            // Record per-epoch node count (uses explicit epoch from context for parallel epoch isolation)
            recordEpochNodeCount(ctx.execution(), ctx.nodeId(), "SKIPPED", ctx.epoch(), triggerId);
        } else {
            snapshotCounts = stateSnapshotService.getNodeCounts(ctx.runId(), ctx.nodeId());
        }

        // 3. Build statusCounts and emit streaming event
        Map<String, Object> statusCountsMap = buildStatusCountsMap(snapshotCounts);
        Map<String, Object> eventData = buildSkippedEventData(ctx, statusCountsMap);
        eventPublisher.emitStep(ctx.runId(), ctx.nodeId(), eventData, StepLifecycle.SKIPPED);

        return persisted
            ? StepCompletionResult.persisted(statusCountsMap, eventData)
            : StepCompletionResult.duplicate(statusCountsMap, eventData);
    }

    /**
     * Complete a skipped step but WITHOUT updating the StateSnapshot.
     *
     * <p>Used for per-item skip records in split context where a Decision/Switch routes
     * items to different branches. The node is not globally SKIPPED - only specific items
     * are skipped for this branch. The node still executes for other routed items.
     *
     * <p>This method:
     * <ol>
     *   <li>Persists the SKIPPED step data record (for statusCounts)</li>
     *   <li>Does NOT update the StateSnapshot (node is not globally SKIPPED)</li>
     *   <li>Emits streaming event for UI update</li>
     * </ol>
     *
     * @param execution The workflow execution
     * @param nodeId The node ID
     * @param nodeLabel The node label
     * @param skipReason The reason for skipping
     * @param skipSourceNode The node that caused the skip
     * @param itemIndex The item index being skipped
     */
    public void completeSkippedStepWithoutStateUpdate(
            WorkflowExecution execution,
            String nodeId,
            String nodeLabel,
            String skipReason,
            String skipSourceNode,
            int itemIndex) {
        completeSkippedStepWithoutStateUpdate(
            execution, nodeId, nodeLabel, skipReason, skipSourceNode, itemIndex, 0, null);
    }

    /**
     * Per-item SKIPPED variant with explicit epoch + triggerId for parallel-epoch isolation.
     *
     * <p><b>Bug this overload addresses.</b> The per-epoch UI view's {@code statusCounts}
     * is computed by {@code StepAggregationService.getAggregatedSteps(runId, epoch)} which
     * filters {@code workflow_step_data} by the {@code epoch} column. Pre-fix, the legacy
     * 6-arg overload routed through {@code SkippedNodePersistenceService.recordSkippedNode}
     * with {@code explicitEpoch=0}, which the {@code > 0} threshold demoted to the global
     * resolver - for late-arriving per-item skips on a closed parallel epoch, the resolver
     * returned the run's CURRENT epoch (already advanced past the closed one), so the row
     * landed under the wrong epoch and the per-epoch view rendered {@code statusCounts=null}
     * for split successors (classify, mcp:apply_*, table:record_*, exit, …). Cumulative
     * "all" view stayed correct because it reads global {@link StateSnapshot.NodeCounts}
     * which never gets reset.
     *
     * <p>Secondary effect: the per-epoch counter row in {@code workflow_epochs} (written by
     * {@code WorkflowEpochService.recordNodeCount}) was bucketed under
     * {@code triggerId="trigger:default"} when the caller didn't pass one, polluting the
     * default DAG bucket. The triggerId parameter routes the counter row to the correct
     * DAG key.
     */
    public void completeSkippedStepWithoutStateUpdate(
            WorkflowExecution execution,
            String nodeId,
            String nodeLabel,
            String skipReason,
            String skipSourceNode,
            int itemIndex,
            int epoch,
            String triggerId) {

        SkipContext ctx = SkipContext.of(
            execution, nodeId, nodeLabel, skipReason, skipSourceNode, itemIndex, epoch);
        completeSkippedStepWithoutStateUpdate(execution, nodeId, nodeLabel, skipReason,
            skipSourceNode, itemIndex, ctx.iteration(), epoch, triggerId);
    }

    /**
     * Per-item SKIPPED variant with explicit iteration, epoch, and triggerId.
     */
    public void completeSkippedStepWithoutStateUpdate(
            WorkflowExecution execution,
            String nodeId,
            String nodeLabel,
            String skipReason,
            String skipSourceNode,
            int itemIndex,
            int iteration,
            int epoch,
            String triggerId) {

        SkipContext ctx = SkipContext.of(
            execution, nodeId, nodeLabel, skipReason, skipSourceNode, itemIndex, iteration, epoch);

        logger.debug("[StepCompletion] Per-item SKIPPED (no state update): runId={} nodeId={} item={} epoch={} triggerId={}",
            ctx.runId(), ctx.nodeId(), ctx.itemIndex(), ctx.epoch(), triggerId);

        // Drift guard - split-context per-item skips MUST carry a real triggerId so the
        // workflow_epochs counter row lands under the right DAG. A null here means the
        // caller dropped the trigger somewhere in the plumbing; the row will silently
        // bucket into "trigger:default" and the per-epoch UI view will look stale.
        // We log WARN (not throw) to preserve the legacy 6-arg compatibility shim, which
        // legitimately routes here with triggerId=null and is consumed only by tests.
        if (triggerId == null) {
            logger.warn("[StepCompletion] Per-item SKIPPED with triggerId=null - workflow_epochs row will be bucketed under 'trigger:default'. runId={} nodeId={} item={} epoch={}. Callers in split context MUST pass the real triggerId from ExecutionContext.",
                ctx.runId(), ctx.nodeId(), ctx.itemIndex(), ctx.epoch());
        }

        // 1. Persist skipped node record with explicit epoch (for per-epoch statusCounts)
        if (ctx.iteration() > 0) {
            persistenceService.recordSkippedNode(
                ctx.execution(), ctx.nodeId(), ctx.nodeLabel(),
                ctx.skipReason(), ctx.skipSourceNode(), ctx.itemIndex(), ctx.iteration(), ctx.epoch(), triggerId);
        } else {
            persistenceService.recordSkippedNode(
                ctx.execution(), ctx.nodeId(), ctx.nodeLabel(),
                ctx.skipReason(), ctx.skipSourceNode(), ctx.itemIndex(), ctx.epoch(), triggerId);
        }

        // Record per-epoch node count under the real (epoch, triggerId) so the per-epoch
        // view aggregates correctly. triggerId=null still falls back to "trigger:default"
        // - guarded above by the drift WARN.
        recordEpochNodeCount(ctx.execution(), ctx.nodeId(), "SKIPPED", ctx.epoch(), triggerId);

        // 2. Do NOT update StateSnapshot - the node is not globally skipped

        // 3. Get current counts and emit streaming event
        StateSnapshot.NodeCounts snapshotCounts = stateSnapshotService.getNodeCounts(ctx.runId(), ctx.nodeId());
        Map<String, Object> statusCountsMap = buildStatusCountsMap(snapshotCounts);
        Map<String, Object> eventData = buildSkippedEventData(ctx, statusCountsMap);
        eventPublisher.emitStep(ctx.runId(), ctx.nodeId(), eventData, StepLifecycle.SKIPPED);
    }

    /**
     * Batch-increment NodeCounts for per-item SKIPPED records without touching EpochState.
     * Single DB transaction for all items.
     */
    public StateSnapshot.NodeCounts batchIncrementSkippedCounts(String runId, String nodeId, int count) {
        return stateSnapshotService.incrementNodeCountsOnly(runId, nodeId, "SKIPPED", count);
    }

    /**
     * Batch-increment NodeCounts.skipped AND emit a streaming step event carrying the
     * post-increment counts so the frontend NodeStatusBadge renders the badge.
     *
     * <p>2026-05-21 fix - pre-fix, persistSkippedItemRecords called
     * batchIncrementSkippedCounts (DB-only) after a per-item loop where each emit
     * read the snapshot BEFORE the increment (statusCounts.SKIPPED=0). The frontend
     * received N events all with SKIPPED=0 and {@link NodeStatusBadge} suppressed
     * the badge (it renders nothing when all counters are zero). The post-increment
     * counts were correct in the DB but no SSE/WS event carried them. This method
     * closes the gap by emitting ONE aggregated event after the increment.
     *
     * <p>Aggregated payload: no {@code itemIndex} field (per-item events were already
     * sent inside the loop). The frontend's count-merge logic at
     * {@code statusUpdater.ts:updateNodeFromStep} replaces {@code node.data.statusCounts}
     * with the latest event's payload, so the final aggregated emit wins.
     */
    public StateSnapshot.NodeCounts batchIncrementSkippedCountsAndEmit(
            WorkflowExecution execution,
            String nodeId,
            String nodeLabel,
            int count,
            int epoch,
            String triggerId) {

        String runId = execution.getRunId();

        // 1. Increment DB counts.
        StateSnapshot.NodeCounts updated =
            stateSnapshotService.incrementNodeCountsOnly(runId, nodeId, "SKIPPED", count);

        Map<String, Object> statusCountsMap = buildStatusCountsMap(updated);
        emitAggregatedSkippedEvent(
            execution, nodeId, nodeLabel, statusCountsMap, epoch, triggerId,
            "batch-aggregated post-cascade");

        return updated;
    }

    private void emitAggregatedSkippedEvent(
            WorkflowExecution execution,
            String nodeId,
            String nodeLabel,
            Map<String, Object> statusCountsMap,
            int epoch,
            String triggerId,
            String skipReason) {

        String runId = execution.getRunId();
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("type", "step_skipped");
        eventData.put("runId", runId);
        eventData.put("nodeId", nodeId);
        eventData.put("stepId", nodeId);
        eventData.put("stepAlias", nodeLabel);
        eventData.put("label", nodeLabel);
        eventData.put("status", "SKIPPED");
        eventData.put("skipReason", skipReason);
        eventData.put("aggregated", true);
        eventData.put("timestamp", System.currentTimeMillis());
        eventData.put("statusCounts", statusCountsMap);
        if (epoch > 0) eventData.put("epoch", epoch);
        if (triggerId != null) eventData.put("triggerId", triggerId);

        eventPublisher.emitStep(runId, nodeId, eventData, StepLifecycle.SKIPPED);
    }

    private boolean shouldEmitDeferredSkippedAggregate(StepExecutionResult result) {
        return result != null
            && result.isSkipped()
            && result.output() != null
            && Boolean.TRUE.equals(result.output().get(ExecutionMetadataKeys.DEFER_SKIPPED_AGGREGATE_EVENT));
    }

    private String extractSkipReason(StepExecutionResult result, String fallback) {
        if (result == null) return fallback;
        if (result.output() != null) {
            Object skipReason = result.output().get("skip_reason");
            if (skipReason == null) skipReason = result.output().get("skipReason");
            if (skipReason != null && !skipReason.toString().isBlank()) {
                return skipReason.toString();
            }
        }
        return result.message() != null && !result.message().isBlank() ? result.message() : fallback;
    }

    /**
     * Complete a skipped step (legacy signature for backward compatibility).
     *
     * @deprecated Use {@link #completeSkipped(SkipContext)} instead
     */
    public boolean completeSkippedStep(
            WorkflowExecution execution,
            String nodeId,
            String nodeLabel,
            String skipReason,
            String skipSourceNode,
            int itemIndex) {

        SkipContext ctx = SkipContext.of(
            execution, nodeId, nodeLabel, skipReason, skipSourceNode, itemIndex);
        return completeSkipped(ctx).persisted();
    }

    /**
     * Complete a skipped step with explicit epoch for parallel epoch isolation.
     *
     * @deprecated Use {@link #completeSkippedStep(WorkflowExecution, String, String,
     *             String, String, int, int, String)} (8-arg form) to also thread
     *             the triggerId - without it, the global skip-cascade path
     *             buckets {@code workflow_epochs} rows under
     *             {@code "trigger:default"} (CRITICAL 1, 2026-05-21 e2e audit).
     */
    public boolean completeSkippedStep(
            WorkflowExecution execution,
            String nodeId,
            String nodeLabel,
            String skipReason,
            String skipSourceNode,
            int itemIndex,
            int epoch) {

        SkipContext ctx = SkipContext.of(
            execution, nodeId, nodeLabel, skipReason, skipSourceNode, itemIndex, epoch);
        return completeSkipped(ctx).persisted();
    }

    /**
     * Complete a skipped step with explicit epoch AND triggerId for parallel epoch
     * isolation under the correct DAG.
     *
     * <p>2026-05-21 fix (CRITICAL 1, e2e audit): the 7-arg overload above falls
     * through to {@link #completeSkipped(SkipContext)} which delegates to
     * {@code completeSkipped(ctx, null)}, dropping the triggerId. The global
     * skip-cascade path ({@code V2SkipPropagationService.emitSkipAndPropagate},
     * perItemScope=false) THEN routes every descendant SKIPPED row under
     * {@code "trigger:default"} in {@code workflow_epochs}, breaking the
     * per-epoch UI view for any cascaded skip. This 8-arg form forwards the
     * triggerId all the way to {@link #completeSkipped(SkipContext, String)},
     * which writes the row under the correct DAG.
     */
    public boolean completeSkippedStep(
            WorkflowExecution execution,
            String nodeId,
            String nodeLabel,
            String skipReason,
            String skipSourceNode,
            int itemIndex,
            int epoch,
            String triggerId) {

        SkipContext ctx = SkipContext.of(
            execution, nodeId, nodeLabel, skipReason, skipSourceNode, itemIndex, epoch);
        return completeSkipped(ctx, triggerId).persisted();
    }

    /**
     * Complete a skipped step with explicit iteration, epoch, and triggerId.
     */
    public boolean completeSkippedStep(
            WorkflowExecution execution,
            String nodeId,
            String nodeLabel,
            String skipReason,
            String skipSourceNode,
            int itemIndex,
            int iteration,
            int epoch,
            String triggerId) {

        SkipContext ctx = SkipContext.of(
            execution, nodeId, nodeLabel, skipReason, skipSourceNode, itemIndex, iteration, epoch);
        return completeSkipped(ctx, triggerId).persisted();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API - Streaming Only (No Persistence)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Emit streaming event only, without persistence or state update.
     *
     * <p>Use this method sparingly, only for cases where:
     * <ul>
     *   <li>Decision nodes that persist via a different mechanism</li>
     *   <li>UI notifications that don't represent actual step completion</li>
     * </ul>
     *
     * @param execution The workflow execution
     * @param nodeId The node ID
     * @param nodeLabel The node label
     * @param result The execution result
     */
    public void emitEventOnly(
            WorkflowExecution execution,
            String nodeId,
            String nodeLabel,
            StepExecutionResult result) {

        String runId = execution.getRunId();
        StateSnapshot.NodeCounts snapshotCounts = stateSnapshotService.getNodeCounts(runId, nodeId);
        Map<String, Object> statusCountsMap = buildStatusCountsMap(snapshotCounts);

        StepCompletionContext ctx = StepCompletionContext.of(execution, nodeId, nodeLabel, result);
        Map<String, Object> eventData = buildStepEventData(ctx, statusCountsMap);

        StepLifecycle lifecycle = mapStatusToLifecycle(result);
        eventPublisher.emitStep(runId, nodeId, eventData, lifecycle);
    }

    /**
     * @deprecated Use {@link #emitEventOnly(WorkflowExecution, String, String, StepExecutionResult)}
     */
    public void emitStepEventOnly(
            WorkflowExecution execution,
            String nodeId,
            String nodeLabel,
            StepExecutionResult result) {
        emitEventOnly(execution, nodeId, nodeLabel, result);
    }

    /**
     * Emit streaming event without persistence (persistence already done elsewhere).
     *
     * <p>Use when persistence has already been done elsewhere (e.g., by V2 engine)
     * but you need to notify the frontend.</p>
     *
     * @deprecated This method exists for migration purposes. New code should use
     *             {@link #complete(StepCompletionContext)} which handles everything.
     */
    public void recordAndEmitOnly(
            WorkflowExecution execution,
            String nodeId,
            String nodeLabel,
            StepExecutionResult result,
            Integer itemIndex,
            Integer iteration) {

        String runId = execution.getRunId();

        // Get statusCounts from DB (single source of truth)
        StateSnapshot.NodeCounts snapshotCounts = stateSnapshotService.getNodeCounts(runId, nodeId);
        Map<String, Object> statusCountsMap = buildStatusCountsMap(snapshotCounts);

        StepCompletionContext ctx = StepCompletionContext.of(
            execution, nodeId, nodeLabel, result, itemIndex, iteration);
        Map<String, Object> eventData = buildStepEventData(ctx, statusCountsMap);

        StepLifecycle lifecycle = mapStatusToLifecycle(result);
        eventPublisher.emitStep(runId, nodeId, eventData, lifecycle);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private StepExecutionResult enrichResultWithContext(StepExecutionResult result, int itemIndex, int iteration) {
        Map<String, Object> enrichedOutput = new HashMap<>(
            result.output() != null ? result.output() : Map.of());

        enrichedOutput.put("item_index", itemIndex);
        enrichedOutput.put("itemIndex", itemIndex);
        enrichedOutput.put("iteration", iteration);
        enrichedOutput.put("currentIteration", iteration);

        return new StepExecutionResult(
            result.stepId(),
            result.status(),
            result.message(),
            enrichedOutput,
            result.executionTime(),
            result.error()
        );
    }

    private StepCompletionContext withResult(StepCompletionContext ctx, StepExecutionResult result) {
        return new StepCompletionContext(
            ctx.execution(),
            ctx.nodeId(),
            ctx.nodeLabel(),
            result,
            ctx.itemIndex(),
            ctx.iteration(),
            ctx.itemId(),
            ctx.epoch(),
            ctx.suppressGlobalMark()
        );
    }

    private StepExecutionResult stripInternalCompletionMetadata(StepExecutionResult result) {
        if (result.output() == null
                || !result.output().containsKey(ExecutionMetadataKeys.DEFER_SKIPPED_AGGREGATE_EVENT)) {
            return result;
        }
        Map<String, Object> sanitizedOutput = new HashMap<>(result.output());
        sanitizedOutput.remove(ExecutionMetadataKeys.DEFER_SKIPPED_AGGREGATE_EVENT);
        return new StepExecutionResult(
            result.stepId(),
            result.status(),
            result.message(),
            sanitizedOutput,
            result.executionTime(),
            result.error()
        );
    }

    /**
     * Build statusCounts map from StateSnapshot.NodeCounts (DB source).
     */
    private Map<String, Object> buildStatusCountsMap(StateSnapshot.NodeCounts counts) {
        Map<String, Object> map = new HashMap<>();
        map.put("running", counts.running());
        map.put("completed", counts.completed());
        map.put("failed", counts.failed());
        map.put("skipped", counts.skipped());
        map.put("processed", counts.completed() + counts.failed() + counts.skipped());
        map.put("total", counts.total());
        return map;
    }

    private Map<String, Object> buildStepEventData(StepCompletionContext ctx, Map<String, Object> statusCounts) {
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("type", "step_executed");
        eventData.put("runId", ctx.runId());
        eventData.put("nodeId", ctx.nodeId());
        eventData.put("stepId", ctx.nodeId());
        eventData.put("stepAlias", ctx.nodeLabel());
        eventData.put("label", ctx.nodeLabel());
        eventData.put("status", ctx.result().status().toWireValue());
        eventData.put("timestamp", System.currentTimeMillis());

        if (statusCounts != null) {
            eventData.put("statusCounts", statusCounts);
        }

        eventData.put("itemIndex", ctx.itemIndex());
        eventData.put("iteration", ctx.iteration());

        if (ctx.result().output() != null) {
            eventData.put("output", ctx.result().output());
        }

        if (ctx.result().error() != null) {
            eventData.put("error", ctx.result().error().getMessage());
        }

        if (ctx.result().executionTime() > 0) {
            eventData.put("executionTime", ctx.result().executionTime());
        }

        return eventData;
    }

    private Map<String, Object> buildSkippedEventData(SkipContext ctx, Map<String, Object> statusCounts) {
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("type", "step_skipped");
        eventData.put("runId", ctx.runId());
        eventData.put("nodeId", ctx.nodeId());
        eventData.put("stepId", ctx.nodeId());
        eventData.put("stepAlias", ctx.nodeLabel());
        eventData.put("label", ctx.nodeLabel());
        eventData.put("status", "SKIPPED");
        eventData.put("skipReason", ctx.skipReason());
        eventData.put("itemIndex", ctx.itemIndex());
        eventData.put("timestamp", System.currentTimeMillis());

        if (statusCounts != null) {
            eventData.put("statusCounts", statusCounts);
        }

        return eventData;
    }

    private StepLifecycle mapStatusToLifecycle(StepExecutionResult result) {
        if (result.isSuccess()) {
            return StepLifecycle.SUCCESS;
        } else if (result.isSkipped()) {
            return StepLifecycle.SKIPPED;
        } else if (result.isFailure()) {
            return StepLifecycle.FAILURE;
        }
        return StepLifecycle.RUNNING;
    }

    /**
     * Consume 1 platform credit for a completed node (fire-and-forget).
     * Isolated in try/catch so credit failures never block step completion.
     *
     * Every node pays this flat platform fee. Agent nodes (agent:*, including
     * classify and guardrail) additionally pay token-based LLM costs via
     * AgentObservabilityService.recordFromRequest() in agent-service.
     */
    private void consumeCreditForNode(StepCompletionContext ctx) {
        // Cloud-only: CE self-hosts the orchestrator, so there is no platform per-node fee.
        // Skipping here means no WORKFLOW_NODE ledger row is written in CE at all (not even $0).
        if (!nodeBillingEnabled) {
            return;
        }
        try {
            String tenantId = ctx.execution().getPlan().getTenantId();
            if (tenantId == null || tenantId.isBlank()) {
                return;
            }

            // Include epoch, spawn, iteration and itemIndex in sourceId so each execution is unique
            UUID wfRunId = ctx.execution().getWorkflowRunId();
            int epoch = (ctx.epoch() >= 0) ? ctx.epoch()
                : (wfRunId != null ? entityResolverService.getCurrentEpochFromRun(wfRunId) : 0);
            int spawn = wfRunId != null ? entityResolverService.getCurrentSpawnFromRun(wfRunId) : 0;
            String sourceId = ctx.runId() + ":" + ctx.nodeId() + ":" + epoch + ":" + spawn
                + ":" + ctx.iteration() + ":" + ctx.itemIndex();
            creditClient.consumeCreditsAsync(
                tenantId,
                "WORKFLOW_NODE",
                sourceId,
                null, null, null, null
            );

            // Record Prometheus metric for workflow node credit consumption
            workflowMetrics.recordNodeCreditConsumed(ctx.nodeId());

            // Track per-run accumulated cost for SSE statistics events
            workflowMetrics.recordRunNodeCredit(ctx.runId());
        } catch (Exception e) {
            logger.warn("[StepCompletion] Credit consumption failed for node {}: {}", ctx.nodeId(), e.getMessage());
        }
    }

    // consumeMarkupForNode + findMcpStepByNodeId removed in V148+ refactor:
    // markup billing migrated to catalog-side CatalogToolBillingService.billImmediate
    // (driven by X-Lc-Billing-Scope-* headers from CatalogToolsGateway).

    /**
     * Records a per-epoch node count to the epoch counts table.
     * Isolated in try/catch so failures never block step completion.
     *
     * @param execution The workflow execution
     * @param nodeId The node ID
     * @param status The node status
     * @param explicitEpoch The epoch from ExecutionContext (-1 = fallback to global current epoch, 0+ = use as-is)
     * @param triggerId The trigger ID for trigger-scoped recording (null = default trigger)
     */
    private void recordEpochNodeCount(WorkflowExecution execution, String nodeId, String status, int explicitEpoch, String triggerId) {
        try {
            UUID wfRunId = execution.getWorkflowRunId();
            if (wfRunId != null) {
                int epoch = (explicitEpoch >= 0)
                    ? explicitEpoch
                    : entityResolverService.getCurrentEpochFromRun(wfRunId);
                workflowEpochService.recordNodeCount(execution.getRunId(), epoch, nodeId, status, triggerId);
            }
        } catch (Exception e) {
            logger.warn("[StepCompletion] epoch count failed: {}", e.getMessage());
        }
    }

    /**
     * Records step completion to the merge integration service for Split merge tracking.
     *
     * <p>This enables the merge system to track when all Split items have completed
     * and produce merged results when all expected items are received.
     *
     * @param ctx The step completion context
     */
    private void recordMergeCompletion(StepCompletionContext ctx) {
        if (mergeIntegrationService == null) {
            return;
        }

        try {
            MergeResult result = mergeIntegrationService.recordCompletion(
                ctx.runId(),
                ctx.itemId(),
                ctx.itemIndex(),
                ctx.nodeId(),
                ctx.result().output(),
                ctx.result()
            );

            if (result != null && result.isReady()) {
                logger.info("[StepCompletion] Merge ready: nodeId={} itemId={} mergePoint={}",
                    ctx.nodeId(), ctx.itemId(), result.mergePointId());
            }
        } catch (Exception e) {
            // Log but don't fail the completion - merge tracking is supplementary
            logger.warn("[StepCompletion] Failed to record merge completion for {}: {}",
                ctx.nodeId(), e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Phase 2.E - Split-async aggregate write at barrier seal
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Idempotently write the global node status for a split-async node based on
     * aggregated per-item {@code workflow_step_data} rows. Called at barrier seal
     * and as a recovery sweep on startup.
     *
     * <p>Decision matrix (epoch-scoped):
     * <ul>
     *   <li>0 completed, 0 failed → no-op (no items persisted yet, not our turn)</li>
     *   <li>≥1 completed, 0 failed → markNodeCompletedEpochOnly</li>
     *   <li>0 completed, ≥1 failed → markNodeFailedEpochOnly</li>
     *   <li>≥1 completed, ≥1 failed → markNodeCompletedEpochOnly + markNodePartialFailure</li>
     * </ul>
     *
     * <p><b>NodeCounts are NOT incremented at seal</b> - the per-item path already
     * wrote them via {@code incrementNodeCountsOnly} (Phase 2.E). The seal only
     * updates {@code EpochState.completedNodeIds} / {@code failedNodeIds} so the
     * DAG-readiness walker observes the global mark. Reusing the regular
     * {@code markNodeCompleted}/{@code markNodeFailed} here would re-increment
     * {@code NodeCounts.completed} on top of the per-item writes - that was the
     * 2026-05-02 prod bug (run_<id>: 4 items → completed=5).
     *
     * <p>Idempotent guard: if the node is already in {@code completedNodeIds} or
     * {@code failedNodeIds} for this epoch, return early. Re-running this method
     * after the barrier already settled is a no-op.
     *
     * <p>Multi-instance safety: only one instance receives the seal (Lua atomicity
     * in {@code SplitCoalesceTracker.ARRIVE_LUA}); but the recovery scanner can
     * also invoke this - the idempotent guard makes that safe.
     *
     * @param runId          the workflow run id
     * @param triggerId      the DAG trigger id (must not be null for epoch-scoped writes)
     * @param normalizedKey  the normalized node key (e.g. "agent:classify")
     * @param epoch          the epoch in which the split executed
     */
    public void recordSplitAggregateIfMissing(String runId, String triggerId, String normalizedKey, int epoch) {
        if (stepDataRepository == null) {
            logger.warn("[StepCompletion] recordSplitAggregateIfMissing skipped (stepDataRepository unavailable): runId={}, nodeId={}",
                runId, normalizedKey);
            return;
        }

        // Phase 2.F (2026-04-29): when triggerId is null (recovery scan path), resolve
        // from the snapshot's default DAG. Falls back gracefully if no DAG can be resolved.
        StateSnapshot snapshot = stateSnapshotService.getSnapshot(runId);
        String resolvedTriggerId = triggerId;
        if (resolvedTriggerId == null && snapshot != null) {
            try {
                resolvedTriggerId = snapshot.getDefaultTriggerId();
            } catch (Exception e) {
                logger.debug("[StepCompletion] recordSplitAggregateIfMissing: cannot resolve triggerId for runId={}: {}",
                    runId, e.getMessage());
            }
        }
        if (resolvedTriggerId == null) {
            logger.debug("[StepCompletion] recordSplitAggregateIfMissing skipped (no triggerId resolvable): runId={}, nodeId={}, epoch={}",
                runId, normalizedKey, epoch);
            return;
        }

        // F1 bundle 2 read-side cap symmetry: rows persist with normalized_key
        // capped via WorkflowStepDataEntity.setNormalizedKey (hash-suffix at
        // NORMALIZED_KEY_MAX). The whole method MUST use the capped key - the
        // idempotency guard reads from completedNodeIds/failedNodeIds (set by
        // markNodeCompletedEpochOnly using the same cappedKey), the count
        // queries read the persisted column (capped at write time), and the
        // markNode* writes back the same key. A mix of capped/raw here would
        // break the symmetry on one of those three surfaces.
        // Other finder callsites pass system-derived short keys and are safe
        // by construction. See DiagnosticFieldLimits javadoc for the contract.
        String cappedKey = DiagnosticFieldLimits.capWithCollisionHash(normalizedKey,
                DiagnosticFieldLimits.NORMALIZED_KEY_MAX);

        // Idempotency guard - if the global mark is already written, no-op
        if (snapshot != null) {
            var dag = snapshot.getDagState(resolvedTriggerId);
            if (dag != null) {
                var es = dag.getEpochState(epoch);
                if (es != null && (es.getCompletedNodeIds().contains(cappedKey)
                        || es.getFailedNodeIds().contains(cappedKey))) {
                    logger.debug("[StepCompletion] recordSplitAggregateIfMissing - already aggregated, skipping: runId={}, nodeId={}, epoch={}",
                        runId, cappedKey, epoch);
                    return;
                }
            }
        }

        long completed = stepDataRepository.countByRunIdAndNormalizedKeyAndEpochAndStatus(runId, cappedKey, epoch, "COMPLETED");
        long failed = stepDataRepository.countByRunIdAndNormalizedKeyAndEpochAndStatus(runId, cappedKey, epoch, "FAILED");

        if (completed == 0 && failed == 0) {
            logger.debug("[StepCompletion] recordSplitAggregateIfMissing - no items persisted yet: runId={}, nodeId={}, epoch={}",
                runId, cappedKey, epoch);
            return;
        }

        // EpochState-only mark - per-item NodeCounts were already incremented via
        // incrementNodeCountsOnly while suppressGlobalMark was active; calling the
        // regular markNodeCompleted/markNodeFailed at the seal would re-increment
        // NodeCounts and inflate `completed` by one (was N items, becomes N+1 in
        // statusCounts). See StateSnapshot.markNodeCompletedEpochOnly for context.
        if (completed > 0 && failed == 0) {
            stateSnapshotService.markNodeCompletedEpochOnly(runId, resolvedTriggerId, epoch, cappedKey);
            logger.info("[StepCompletion] Aggregate at seal - all completed: runId={}, nodeId={}, epoch={}, completed={}",
                runId, cappedKey, epoch, completed);
        } else if (failed > 0 && completed == 0) {
            stateSnapshotService.markNodeFailedEpochOnly(runId, resolvedTriggerId, epoch, cappedKey);
            logger.info("[StepCompletion] Aggregate at seal - all failed: runId={}, nodeId={}, epoch={}, failed={}",
                runId, cappedKey, epoch, failed);
        } else {
            stateSnapshotService.markNodeCompletedEpochOnly(runId, resolvedTriggerId, epoch, cappedKey);
            stateSnapshotService.markNodePartialFailure(runId, resolvedTriggerId, epoch, cappedKey);
            logger.info("[StepCompletion] Aggregate at seal - partial: runId={}, nodeId={}, epoch={}, completed={}, failed={}",
                runId, cappedKey, epoch, completed, failed);
        }
    }

}
