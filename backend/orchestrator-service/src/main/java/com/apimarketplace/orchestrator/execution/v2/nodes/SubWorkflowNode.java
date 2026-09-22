package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.services.template.ReportedParams;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.StepOutputService;
import com.apimarketplace.orchestrator.trigger.ReusableTriggerService;
import com.apimarketplace.orchestrator.trigger.TriggerExecutionResult;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * SubWorkflow node - Executes another workflow by firing its trigger (reusable run pattern).
 *
 * The node loads the target workflow, finds its active run (respecting pinned versions),
 * fires the trigger via ReusableTriggerService, WAITS for that epoch to finish, and only then
 * collects its outputs. The wait is the point: firing returns as soon as nothing is left to run
 * inline, which also happens when a node in the child yields, so reading the outputs at that
 * moment yields a prefix of the epoch rather than its result.
 *
 * Anti-recursion: Tracks call depth via ExecutionContext global data.
 * If the depth exceeds the configured maxDepth, the node fails immediately.
 *
 * Usage:
 * - Compose workflows by calling reusable sub-workflows
 * - Target workflow must have an active run (start it first)
 * - Pass data in via inputMapping, receive results as output
 * - timeoutSeconds bounds how long THIS node waits, never the sub-workflow itself, which
 *   keeps running after the wait gives up
 *
 * <p><b>Scope:</b> the target workflow id is resolvable at runtime (it goes through
 * {@code templateAdapter.resolveTemplates}, so it can come from an upstream node
 * output or the trigger payload). The node therefore enforces a cross-workspace
 * guard before touching the target: the caller must own it or share its
 * organization, or the call is refused as "not found". This is the same intent as
 * {@code WorkflowTriggerDispatchService} / {@code ErrorTriggerDispatchService} but a
 * deliberately more tolerant predicate, for the reason documented on
 * {@link #isSameWorkspace}.
 *
 * <h2>Known open defect, NOT fixed here</h2>
 *
 * A production report describes a core:loop calling the same sub-workflow N times where the chain
 * silently stops one iteration short: the node stays PENDING with no error until timeoutSeconds and
 * the external service called by the child receives N-1 requests. That stall did NOT reproduce in a
 * single-instance e2e stack, in three shapes (mock child, two epochs, http_request child), so its
 * cause is still unknown and nothing in this class fixes it. What this class does carry is the
 * marker block around the child fire below, which tells you WHERE a fire is parked.
 *
 * <p>The second reported symptom ("No active run found" while a live run exists) is diagnosed but
 * also not fixed: {@link #describeMissingActiveRun} explains why the lookup rejected the run it
 * found, it does not make that run eligible.
 */
public class SubWorkflowNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(SubWorkflowNode.class);
    private static final int SUB_RUN_LOCK_STRIPES = 64;
    private static final Object[] SUB_RUN_LOCKS = createSubRunLocks();

    /** Global data key used to track sub-workflow recursion depth. */
    static final String DEPTH_KEY = ExecutionMetadataKeys.SUB_WORKFLOW_DEPTH;

    /** Global data key used to track sub-workflow workflow-id ancestry. */
    static final String ANCESTRY_KEY = ExecutionMetadataKeys.SUB_WORKFLOW_ANCESTRY;

    /**
     * Run statuses considered "active" for sub-workflow dispatch, unchanged from what
     * this node accepted before it adopted the resolver. Single source of truth lives
     * on {@code ProductionRunResolver} because the resolver documents why this lane is
     * narrower than the error lane's non-terminal set.
     */
    static final List<RunStatus> ACTIVE_STATUSES =
        com.apimarketplace.orchestrator.trigger.ProductionRunResolver.SUB_WORKFLOW_ACTIVE_STATUSES;

    /**
     * Every non-terminal status, derived from {@link RunStatus#isTerminal()} so it cannot drift when
     * a status is added. Used ONLY by {@link #describeMissingActiveRun} to explain a failed lookup;
     * it is deliberately wider than {@link #ACTIVE_STATUSES} (which omits {@code AWAITING_SIGNAL}
     * and {@code PENDING}) and must never be used to select a run to fire.
     */
    private static final List<RunStatus> NON_TERMINAL_STATUSES =
        com.apimarketplace.orchestrator.trigger.ProductionRunResolver.NON_TERMINAL_STATUSES;

    /** Trigger types that cannot be fired by sub-workflow node. */
    private static final Set<String> UNFIREABLE_TYPES = Set.of("workflow", "error");

    private final Core.SubWorkflowConfig config;

    // Services injected via setters or acceptServices
    private WorkflowRepository workflowRepository;
    private WorkflowRunRepository workflowRunRepository;
    private ReusableTriggerService reusableTriggerService;
    /** Single production-identity rule; null only in plain unit constructions. */
    private com.apimarketplace.orchestrator.trigger.ProductionRunResolver productionRunResolver;
    private StepOutputService stepOutputService;
    private WorkflowStepDataRepository workflowStepDataRepository;
    /** F2.2 - optional; null in unit tests that don't wire Redis. */
    private com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher workflowRedisPublisher;

    public SubWorkflowNode(String nodeId, Core.SubWorkflowConfig config) {
        super(nodeId, NodeType.SUB_WORKFLOW);
        this.config = config;
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        logger.info("SubWorkflow node executing: nodeId={}, itemId={}", nodeId, context.itemId());

        // Build minimal resolved_params early so it is available in ALL failure paths.
        // Enriched with workflowId once resolved.
        String rawWorkflowId = config != null ? config.workflowId() : null;
        Map<String, Object> resolvedParams = new LinkedHashMap<>();
        if (rawWorkflowId != null) resolvedParams.put("workflowId", rawWorkflowId);
        if (config != null) {
            if (config.inputMapping() != null) resolvedParams.put("inputMapping", config.inputMapping());
            resolvedParams.put("timeoutSeconds", config.timeoutSeconds());
            resolvedParams.put("maxDepth", config.maxDepth());
            if (config.triggerId() != null) resolvedParams.put("triggerId", config.triggerId());
        }
        // Through the gate once, here, rather than at each of the exits below: the
        // `inputMapping` is an author expression with no ceiling, and it is the payload this
        // node hands the child, so it can carry whatever the author put in it.
        resolvedParams = ReportedParams.forReport(resolvedParams);

        try {
            // 1. Anti-recursion depth guard
            int currentDepth = getCurrentDepth(context);
            int maxDepth = config != null ? config.maxDepth() : 5;
            if (currentDepth >= maxDepth) {
                String msg = String.format(
                    "Sub-workflow recursion depth %d exceeds maximum %d", currentDepth, maxDepth);
                logger.error("SubWorkflow recursion guard: nodeId={}, {}", nodeId, msg);
                Map<String, Object> failOutput = new HashMap<>();
                failOutput.put("resolved_params", resolvedParams);
                return NodeExecutionResult.failureWithOutput(nodeId, msg, failOutput, 0);
            }

            // 2. Resolve workflowId
            String workflowIdStr = resolveWorkflowId(context);
            if (workflowIdStr == null || workflowIdStr.isBlank()) {
                Map<String, Object> failOutput = new HashMap<>();
                failOutput.put("resolved_params", resolvedParams);
                return NodeExecutionResult.failureWithOutput(nodeId, "workflowId is required but was null or empty", failOutput, 0);
            }

            // Update resolvedParams with the resolved (possibly SpEL-evaluated) workflowId
            resolvedParams.put("workflowId", workflowIdStr);

            UUID workflowId;
            try {
                workflowId = UUID.fromString(workflowIdStr);
            } catch (IllegalArgumentException e) {
                Map<String, Object> failOutput = new HashMap<>();
                failOutput.put("resolved_params", resolvedParams);
                return NodeExecutionResult.failureWithOutput(nodeId,
                    "Invalid workflowId format: " + workflowIdStr, failOutput, 0);
            }

            List<String> currentAncestry = getCurrentAncestry(context);
            if (containsWorkflowId(currentAncestry, workflowId.toString())) {
                String msg = "Sub-workflow recursion cycle detected: workflow " + workflowId
                    + " is already in the call chain " + currentAncestry;
                logger.error("SubWorkflow cycle guard: nodeId={}, {}", nodeId, msg);
                Map<String, Object> failOutput = new HashMap<>();
                failOutput.put("resolved_params", resolvedParams);
                return NodeExecutionResult.failureWithOutput(nodeId, msg, failOutput, 0);
            }
            List<String> childAncestry = appendWorkflowId(currentAncestry, workflowId.toString());

            // 3. Load the target workflow
            if (workflowRepository == null) {
                Map<String, Object> failOutput = new HashMap<>();
                failOutput.put("resolved_params", resolvedParams);
                return NodeExecutionResult.failureWithOutput(nodeId, "WorkflowRepository not injected", failOutput, 0);
            }
            Optional<WorkflowEntity> entityOpt = workflowRepository.findById(workflowId);
            if (entityOpt.isEmpty()) {
                Map<String, Object> failOutput = new HashMap<>();
                failOutput.put("resolved_params", resolvedParams);
                return NodeExecutionResult.failureWithOutput(nodeId,
                    notFoundMessage(workflowId), failOutput, 0);
            }

            WorkflowEntity entity = entityOpt.get();

            // Cross-workspace guard. workflowId is runtime-resolvable (see class
            // javadoc), so without this a template could point the node at any
            // workflow in the database: it would fire that workflow's run and read
            // its step outputs back into this run's output. Refuse rather than
            // re-bind, which is what the org re-scope below the fire used to do
            // implicitly. See isSameWorkspace for why the predicate is the tolerant
            // one and not the strict crossResourceMatches the dispatch services use.
            if (!isSameWorkspace(context, entity)) {
                // The refusal itself is logged inside isSameWorkspace, which owns the
                // scope decision (and is the only method here that reads both scope
                // getters - see the OrgScopePredicateInvariantTest ArchUnit rule).
                Map<String, Object> failOutput = new HashMap<>();
                failOutput.put("resolved_params", resolvedParams);
                // Same wording as the genuine not-found branch on purpose: the caller must
                // not be able to tell an out-of-scope workflow from a non-existent one.
                // The hint is safe because it is true in both cases.
                return NodeExecutionResult.failureWithOutput(nodeId,
                    notFoundMessage(workflowId), failOutput, 0);
            }

            Map<String, Object> planMap = entity.getPlan();
            if (planMap == null || planMap.isEmpty()) {
                Map<String, Object> failOutput = new HashMap<>();
                failOutput.put("resolved_params", resolvedParams);
                return NodeExecutionResult.failureWithOutput(nodeId,
                    "Workflow has no plan: " + workflowId, failOutput, 0);
            }

            // 4. Find the run to fire, via the shared resolver
            if (workflowRunRepository == null) {
                Map<String, Object> failOutput = new HashMap<>();
                failOutput.put("resolved_params", resolvedParams);
                return NodeExecutionResult.failureWithOutput(nodeId, "WorkflowRunRepository not injected", failOutput, 0);
            }
            if (productionRunResolver == null) {
                Map<String, Object> failOutput = new HashMap<>();
                failOutput.put("resolved_params", resolvedParams);
                return NodeExecutionResult.failureWithOutput(nodeId, "ProductionRunResolver not injected", failOutput, 0);
            }

            WorkflowRunEntity run = findActiveRun(entity);
            if (run == null) {
                Map<String, Object> failOutput = new HashMap<>();
                failOutput.put("resolved_params", resolvedParams);
                // describeMissingActiveRun explains WHY the lookup rejected what it found
                // (parked on a signal, transient status, wrong plan version) and names the
                // action that clears each case - strictly more than a flat "start it first".
                return NodeExecutionResult.failureWithOutput(nodeId,
                    describeMissingActiveRun(entity, workflowId), failOutput, 0);
            }
            // No terminal-status re-check here: ACTIVE_STATUSES is applied inside the
            // resolver's query, so the run RESOLVED here is never terminal. Note the run
            // actually FIRED is re-read unfiltered inside the stripe lock below, so it can
            // have gone terminal in between - a pre-existing race this change does not
            // widen (the old check ran at the same point, before the same re-read).

            // 5. Parse plan → resolve trigger
            WorkflowPlan subPlan = WorkflowPlan.fromMap(planMap, workflowId.toString(), context.tenantId());
            String triggerId = resolveTriggerId(subPlan);
            if (triggerId == null) {
                Map<String, Object> failOutput = new HashMap<>();
                failOutput.put("resolved_params", resolvedParams);
                return NodeExecutionResult.failureWithOutput(nodeId,
                    "No fireable trigger found in workflow " + workflowId, failOutput, 0);
            }

            TriggerType triggerType = resolveTriggerType(subPlan, triggerId);

            // 6. Resolve input data.
            // Defense-in-depth: PLAN_FROM_PAYLOAD_MARKER is an internal control
            // signal set ONLY by TriggerController after a successful
            // updateRunPlan. A workflow author who pipes that key through a
            // Transform node into the sub-workflow input could otherwise forge
            // the marker and trick the sub-workflow's executeTriggerInternal
            // into skipping its workflow.plan refresh. Strip the key here so
            // the sub-workflow always runs with the proper passive-fire
            // semantics (its own run.plan was never written via updateRunPlan
            // by us - we only carry data, never plan-control intent).
            Map<String, Object> inputData = com.apimarketplace.orchestrator.trigger.ReusableTriggerService
                    .sanitizePlanMarker(resolveInputData(context));
            Map<String, Object> childGlobalData = buildChildSubWorkflowGlobalData(currentDepth, childAncestry);

            // 7. Fire trigger (bypass queue, force auto mode)
            if (reusableTriggerService == null) {
                Map<String, Object> failOutput = new HashMap<>();
                failOutput.put("resolved_params", resolvedParams);
                return NodeExecutionResult.failureWithOutput(nodeId, "ReusableTriggerService not injected", failOutput, 0);
            }

            String subRunId = run.getRunIdPublic();
            logger.info("SubWorkflow firing trigger: nodeId={}, subRunId={}, triggerId={}, type={}",
                nodeId, subRunId, triggerId, triggerType);

            int timeoutSeconds = config != null ? config.timeoutSeconds() : 300;
            // ONE budget for the whole call, started before the fire. timeoutSeconds is documented
            // as "maximum time to wait for the sub-workflow to complete", so it has to cover the
            // fire AND the wait for the child epoch to close below; giving the wait its own fresh
            // budget would silently double the ceiling an author configured. It also covers the
            // wait for the per-sub-run lock, so sibling calls to one target share this budget.
            final long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            TriggerExecutionResult triggerResult;

            // F2.2 - register the parent→child link BEFORE firing so an in-flight
            // cancel on the parent run propagates downward. The engine's
            // isAgentCancelSignalSet walks workflow:parent:{childRunId} pointers
            // up to find a cancelled ancestor.
            //
            // Cleared once the whole blocking section below is done, fire AND wait, which is what
            // F2.2 meant by "around the blocking call". That used to be the fire alone; now the
            // child does most of its work during the wait, so clearing when the fire returns would
            // leave the longest part of the call with no route from a parent stop to the child.
            // It must not outlive the section either: this is a SHARED reusable run, and a pointer
            // left behind would let a later stop of THIS run abort somebody else's fire of it.
            //
            // The pointer is a single key per child, so two parents firing the same target still
            // overwrite each other's and the first to finish clears the survivor's. That race
            // predates this change, but widening the section from the fire to the whole wait makes
            // a missing or mis-aimed pointer likelier, so a cancel reaching the child is
            // best-effort, not a guarantee.
            if (workflowRedisPublisher != null && context.runId() != null
                    && subRunId != null && !subRunId.equals(context.runId())) {
                workflowRedisPublisher.registerSubWorkflowParent(subRunId, context.runId());
            }
            try {

                // Between the "firing trigger" line above and the completion of the wait below, this
                // node used to emit nothing, so a fire that never returned was indistinguishable in the
                // logs from a fire that was never dispatched. The markers below close that gap: for a
                // given (subRunId, epoch, spawn, itemIndex) tuple, the last marker emitted names the
                // stage it is parked in.
                //
                // All four identifiers are needed. itemIndex separates concurrent split/fork branches;
                // spawn separates re-executions of the same node, which is what a loop iteration is, so
                // without it five sequential iterations emit byte-identical lines. epoch separates
                // trigger fires of the same run.
                //
                // LEVELS: the two that answer "was it dispatched, and did the worker start" are INFO so
                // they are usable on a default prod configuration (logging level for this package is
                // INFO). The two lock markers are DEBUG: they only matter when contention is suspected,
                // and reading them in prod needs a log-level change for this class.
                logger.debug("SubWorkflow awaiting sub-run lock: nodeId={}, parentRunId={}, subRunId={}, epoch={}, spawn={}, itemIndex={}",
                    nodeId, context.runId(), subRunId, context.epoch(), context.spawn(), context.itemIndex());
                synchronized (lockForSubRun(subRunId)) {
                    logger.debug("SubWorkflow holding sub-run lock: nodeId={}, parentRunId={}, subRunId={}, epoch={}, spawn={}, itemIndex={}",
                        nodeId, context.runId(), subRunId, context.epoch(), context.spawn(), context.itemIndex());
                    // Re-read the freshest child run entity inside the stripe lock so
                    // concurrent fires of the same sub-run serialize on a single,
                    // up-to-date row instead of racing on a stale snapshot.
                    WorkflowRunEntity runForFire = workflowRunRepository.findByRunIdPublic(subRunId)
                        .orElse(run);
                    // HOTFIX-2 (2026-05-20) - sub-workflow trigger executes step nodes
                    // that persist workflow_step_data + storage.storage; both are
                    // OrgScopedEntity and would trip V261 NOT NULL on the FJP worker
                    // without re-binding the org scope.
                    final String orgIdForWorker = runForFire.getOrganizationId();
                    // Seconds LEFT, not the full timeoutSeconds: acquiring the stripe lock above can
                    // itself take time, and a fresh budget here would let one call consume the lock
                    // wait PLUS the whole configured budget, and then hand a negative remainder to the
                    // epoch wait. Floored at 1s so a call that arrives with the budget already gone
                    // still gets one honest attempt rather than an instant timeout.
                    long fireBudgetSeconds = Math.max(1L,
                        TimeUnit.NANOSECONDS.toSeconds(deadlineNanos - System.nanoTime()));
                    logger.info("SubWorkflow awaiting child epoch: nodeId={}, subRunId={}, epoch={}, spawn={}, itemIndex={}, fireBudgetSeconds={}",
                        nodeId, subRunId, context.epoch(), context.spawn(), context.itemIndex(), fireBudgetSeconds);
                    try {
                        triggerResult = CompletableFuture.supplyAsync(() -> {
                            // First statement on the worker: distinguishes "the task never got a
                            // thread" (this line absent) from "the worker ran and then parked inside
                            // the child DAG" (this line present, no completion line after it).
                            logger.info("SubWorkflow worker entered: nodeId={}, subRunId={}, epoch={}, spawn={}, itemIndex={}, thread={}",
                                nodeId, subRunId, context.epoch(), context.spawn(), context.itemIndex(),
                                Thread.currentThread().getName());
                            TriggerExecutionResult[] holder = new TriggerExecutionResult[1];
                            com.apimarketplace.common.web.TenantResolver.runWithOrgScope(orgIdForWorker, () ->
                                holder[0] = reusableTriggerService.executeTriggerInternal(
                                    runForFire, triggerId, triggerType, inputData, true, childGlobalData)
                            );
                            return holder[0];
                        }).get(fireBudgetSeconds, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        logger.error("SubWorkflow timeout during fire: nodeId={}, subRunId={}, timeoutSeconds={}",
                            nodeId, subRunId, timeoutSeconds);
                        Map<String, Object> failOutput = new HashMap<>();
                        failOutput.put("resolved_params", resolvedParams);
                        return NodeExecutionResult.failureWithOutput(
                            nodeId, stillRunningMessage(timeoutSeconds, subRunId), failOutput, 0);
                    }

                    // 8. Check trigger result
                    if (!triggerResult.success()) {
                        String errorMsg = triggerResult.message() != null
                            ? triggerResult.message()
                            : "Sub-workflow trigger failed";
                        logger.warn("SubWorkflow trigger failed: nodeId={}, subRunId={}, error={}",
                            nodeId, subRunId, errorMsg);
                        Map<String, Object> failOutput = new HashMap<>();
                        failOutput.put("resolved_params", resolvedParams);
                        return NodeExecutionResult.failureWithOutput(nodeId, errorMsg, failOutput, 0);
                    }
                }

                // 8b. The fire returned. That is NOT the same as the child having finished.
                //
                // The engine hands control back as soon as it has nothing left to run INLINE, which
                // also happens when a node yields and the remainder of the child's DAG is deferred to
                // the signal-resume path: a wait longer than 3 seconds, a user approval, an interface
                // awaiting __continue, or an agent handed to the async queue (agent nodes take that
                // path by DEFAULT, so this is the common case, not the exotic one). Reading the
                // child's step rows at that instant yields a PREFIX of the epoch, and this node used
                // to publish that prefix under success=true. In production it shipped a green run
                // carrying 4 of the child's 11 steps.
                //
                // So wait for the epoch to actually close, inside the budget the author already
                // configured for exactly this. A child that is merely slow now WORKS instead of
                // returning half its data; a child parked on a human (approval, interface) spends the
                // budget and fails honestly, which is the only truthful answer available.
                // The DAG key comes from the RESULT, not from our local triggerId. The engine indexes
                // an epoch's signals, agents and running nodes by the exact string the fire ran under,
                // and the result carries that string. Passing our own copy would work only as long as
                // the two never diverge, and a key that misses simply reports "nothing pending",
                // which would make this whole guard silently inert rather than fail loudly.
                String childDagId = triggerResult.triggerId() != null ? triggerResult.triggerId() : triggerId;
                WaitOutcome waitOutcome =
                    awaitChildEpochClosed(subRunId, childDagId, triggerResult.epoch(), deadlineNanos, context);
                if (waitOutcome != WaitOutcome.CLOSED) {
                    Map<String, Object> failOutput = new HashMap<>();
                    failOutput.put("resolved_params", resolvedParams);
                    return NodeExecutionResult.failureWithOutput(
                        nodeId, waitFailureMessage(waitOutcome, timeoutSeconds, subRunId), failOutput, 0);
                }
            } finally {
                // Best-effort (the key's TTL backstops a miss). Runs on every exit from the
                // blocking section, including the early returns above.
                //
                // Guarded because a throw here would complete the finally ABRUPTLY and discard
                // the pending return: a Redis blip during cleanup would turn a finished
                // sub-workflow, or an honest failure message, into an unrelated Redis error.
                if (workflowRedisPublisher != null) {
                    try {
                        workflowRedisPublisher.clearSubWorkflowParent(subRunId);
                    } catch (Exception cleanupError) {
                        logger.warn("SubWorkflow could not clear the parent pointer, TTL will reclaim it: subRunId={}, error={}",
                            subRunId, cleanupError.getMessage());
                    }
                }
            }

            // 9. Collect outputs from step data (epoch-scoped).
            //
            // Deliberately OUTSIDE the stripe lock. The invariant that makes this safe is epoch
            // monotonicity, not the monitor: the read is scoped to (subRunId, triggerResult.epoch()),
            // TriggerEpochManager only ever increments the epoch (under the run's advisory + row
            // lock), and nothing on the re-fire path deletes or re-keys the rows of a previous
            // epoch. So a concurrent fire of the same sub-run can only write epoch N+1 and cannot
            // contaminate this read.
            // The rows are insert-only: StepDataPersistenceService resolves the output storage id
            // first and writes one INSERT ... ON CONFLICT DO NOTHING with the terminal status
            // already set, and status is part of the row's uniqueness key, so there is no
            // in-place status transition to observe half-written.
            //
            // The monitor was never the guarantee in any case: it is a per-JVM `static` monitor, so
            // with more than one orchestrator replica two fires of the same subRunId on different
            // pods have always run this read unsynchronised.
            //
            // The caveat that used to sit here - the fire short-circuiting with the child epoch
            // still open, so that later resumes kept inserting COMPLETED rows this read had
            // already missed - is gone: step 8b above does not let execution reach this line until
            // the epoch is closed. Reaching it means the rows below are the whole epoch.
            logger.debug("SubWorkflow collecting epoch outputs: nodeId={}, subRunId={}, spawn={}, itemIndex={}, childEpoch={}",
                nodeId, subRunId, context.spawn(), context.itemIndex(), triggerResult.epoch());
            Map<String, Object> resultOutputs = collectEpochOutputs(
                subRunId, triggerResult.epoch(), context.tenantId());

            // 10. Build output (backward-compatible format)
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("result", resultOutputs);
            output.put("subWorkflowId", workflowId.toString());
            output.put("subRunId", subRunId);
            output.put("success", true);

            // MANDATORY metadata
            output.put("node_type", "SUB_WORKFLOW");
            output.put("item_index", context.itemIndex());
            output.put("itemIndex", context.itemIndex());
            output.put("item_id", context.itemId());
            output.put("resolved_params", resolvedParams);

            logger.info("SubWorkflow completed: nodeId={}, subRunId={}, epoch={}, outputKeys={}",
                nodeId, subRunId, triggerResult.epoch(), resultOutputs.keySet());
            return NodeExecutionResult.success(nodeId, output);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("SubWorkflow interrupted: nodeId={}", nodeId);
            Map<String, Object> failOutput = new HashMap<>();
            failOutput.put("resolved_params", resolvedParams);
            return NodeExecutionResult.failureWithOutput(nodeId, "Sub-workflow execution interrupted", failOutput, 0);
        } catch (Exception e) {
            logger.error("SubWorkflow execution failed: nodeId={}, error={}",
                nodeId, e.getMessage(), e);
            Map<String, Object> failOutput = new HashMap<>();
            failOutput.put("resolved_params", resolvedParams);
            return NodeExecutionResult.failureWithOutput(nodeId, e.getMessage(), failOutput, 0);
        }
    }

    /**
     * The single wording used for BOTH "no such workflow" and "out of your workspace".
     * They must stay byte-identical: a different message would let a caller probe which
     * workflow ids exist in other tenants. Shared so the two call sites cannot drift.
     */
    private static String notFoundMessage(UUID workflowId) {
        return "Workflow not found: " + workflowId
            + ". Check the id with workflow(action='list'); a sub-workflow target must be "
            + "a workflow you own or that belongs to your organization.";
    }

    /**
     * True when the calling run and the target workflow live in the same workspace.
     *
     * <p>Same INTENT as the guards in {@code WorkflowTriggerDispatchService} and
     * {@code ErrorTriggerDispatchService}, but not the same predicate: those compare
     * two {@code workflows} rows with the strict
     * {@code ScopeGuard.crossResourceMatches}, and both sides are NOT NULL since V263.
     * The caller side here comes from the {@link ExecutionContext}, which can be null
     * even though {@code workflow_runs.organization_id} is NOT NULL, because several
     * paths degrade it on lookup failure ({@code WorkflowExecutionServiceV2},
     * {@code ExecutionCacheManager}, {@code V2StepByStepService}). A null caller org
     * therefore means a DEGRADED or legacy run, not a personal workspace.
     *
     * <p>So the predicate is chosen per case, never looser than the case needs:
     * <ul>
     *   <li><b>Caller org known</b> (the normal case): strict
     *       {@code crossResourceMatches}, identical to the dispatch services. Members
     *       of one organization reach each other's workflows, and nothing else. No
     *       owner fallback, because that would let a run in org A reach the caller's
     *       own workflow in org B.</li>
     *   <li><b>Caller org missing</b> (degraded run): {@code isInOwnerOrOrgScope}, which
     *       here reduces to ownership since there is no org to match on. That is the
     *       tolerance, and it exists only so a degraded run can still reach the
     *       workflows it owns instead of having every sub-workflow call refused with a
     *       misleading "Workflow not found".</li>
     * </ul>
     * Refused in both cases, and the reason this guard exists since the target id is
     * runtime-resolvable: a workflow the caller neither owns nor shares an organization
     * with. A crafted template cannot reach an unrelated tenant.
     *
     * <p><b>Known limit:</b> on a degraded run, a target owned by a DIFFERENT member of
     * the same org is refused, where it would have worked. That is availability lost on
     * an already-degraded run; the alternative, trusting an absent org, is worse.
     */
    @com.apimarketplace.common.scope.TolerantScope(reason =
        "Internal channel: authority is established upstream by the parent run, which the "
        + "engine only executes for an authorized owner. Tolerance is required because the "
        + "caller-side org comes from the ExecutionContext, which degrades to null on several "
        + "lookup-failure paths even though workflow_runs.organization_id is NOT NULL since "
        + "V263; strict isolation would refuse every sub-workflow call from such a run. The "
        + "ownerMatch branch is the tolerance that keeps those runs working. What stays refused "
        + "is the property this guard exists for: a runtime-resolved workflowId reaching a "
        + "workflow the caller neither owns nor shares an organization with.")
    private boolean isSameWorkspace(ExecutionContext context, WorkflowEntity target) {
        String callerOrg = context.organizationId();
        if (callerOrg == null || callerOrg.isBlank()) {
            // WARN: workflow_runs.organization_id is NOT NULL since V263, so a missing org
            // here is a real anomaly (a degraded lookup or a legacy run), not a routine
            // shape. It also marks the calls that fall back to owner-only matching.
            logger.warn("[SCOPE] Sub-workflow caller has no organization on its execution context "
                    + "(runId={}, nodeId={}) - matching on owner scope only", context.runId(), nodeId);
        }
        boolean callerHasOrg = callerOrg != null && !callerOrg.isBlank();
        boolean allowed = callerHasOrg
            // Org known: strict workspace match, same rule as the dispatch services. No
            // owner fallback here - it would let an org-A run reach the caller's own
            // workflow in org-B, which is the isolation isInStrictScope exists to keep.
            ? com.apimarketplace.common.scope.ScopeGuard.crossResourceMatches(
                callerOrg, target.getOrganizationId())
            // Org unknown (degraded run): fall back to ownership, the narrowest rule that
            // still lets that run reach its own workflows.
            : com.apimarketplace.common.scope.ScopeGuard.isInOwnerOrOrgScope(
                context.tenantId(), callerOrg, target.getTenantId(), target.getOrganizationId());
        if (!allowed) {
            logger.warn("[SCOPE] Cross-workspace sub-workflow blocked: nodeId={}, "
                    + "caller(tenant={}, org={}) -> target workflow {} (tenant={}, org={})",
                nodeId, context.tenantId(), callerOrg, target.getId(),
                target.getTenantId(), target.getOrganizationId());
        }
        return allowed;
    }

    /**
     * Finds the run the sub-workflow call should fire.
     *
     * <p>Delegates to
     * {@link com.apimarketplace.orchestrator.trigger.ProductionRunResolver#resolveActiveRun}
     * so this node inherits one shared rule instead of hand-rolling a lookup.
     *
     * <p>Selection is still "newest by {@code started_at}", scoped to the pinned
     * version when the target is pinned - identical to what this node did before.
     * The ONE behavioural addition is that showcase clones are excluded: a frozen
     * marketplace copy shares workflow_id, plan_version and status with the real run,
     * and firing it silently does nothing. {@code production_run_id} is deliberately
     * NOT consulted (see {@code resolveActiveRun}), so a child's editor run can still
     * win here, exactly as before. {@link #ACTIVE_STATUSES} is passed explicitly so this
     * node keeps the exact set it accepted before, and a pin is still not required.
     */
    private WorkflowRunEntity findActiveRun(WorkflowEntity target) {
        return productionRunResolver
            .resolveActiveRun(target, ACTIVE_STATUSES)
            .run()
            .orElse(null);
    }

    /**
     * Builds the failure message for "no eligible run". The single "Start the workflow first." text
     * used to collapse three genuinely different causes, and it was actively misleading in two of
     * them because the run it told the caller to start already existed.
     *
     * <p>The probe is deliberately WIDER than {@link #findActiveRun} on both axes: every
     * non-terminal status (not just {@link #ACTIVE_STATUSES}, which omits both
     * {@code AWAITING_SIGNAL} and {@code PENDING}) and every plan version. That width is what lets
     * it name the run the lookup could not use, and
     * it is safe precisely because it feeds a message and nothing else: it runs ONLY on the failure
     * path, after {@code findActiveRun} already returned null, and it never changes which run is
     * fired. A run refused by the lookup stays refused, it is just named and explained.
     *
     * <p>Deliberately NOT routed through {@code ProductionRunResolver}, despite the overlap: that
     * resolver refuses an unpinned workflow outright, while sub-workflow calls are supported on
     * unpinned workflows. Adopting it here would turn every unpinned sub-workflow into a hard
     * failure. If the two are ever unified, that is the constraint to solve first.
     *
     * <p>Caveat, accepted: the probe takes the newest non-terminal run and does not exclude
     * showcase clones, so on a workflow that has been published and cloned it can name a clone.
     * The alternative (the production-run finders, which do exclude clones) filters on the pin and
     * would therefore return nothing in exactly the version-mismatch case this exists to explain.
     */
    private String describeMissingActiveRun(WorkflowEntity entity, UUID workflowId) {
        // Showcase-safe probe: the selection this explains excludes frozen marketplace
        // clones, so the diagnostic must too. Reporting "run X is alive but ..." about an
        // inert clone would send the agent chasing a run it can never make eligible.
        Optional<WorkflowRunEntity> probe = workflowRunRepository
            .findFirstProductionRunByWorkflowIdAndStatusIn(workflowId, NON_TERMINAL_STATUSES);
        if (probe.isEmpty()) {
            // The workflow has no run at all. Naming the action that creates one is the whole
            // point of this branch: this node fires a trigger on an EXISTING run and never
            // creates one, so "start the workflow first" alone leaves the caller guessing how.
            // Pinned workflows get the version too, matching the startAtPin idiom below.
            Integer pinned = entity.getPinnedVersion();
            String start = "workflow(action='execute', id='" + workflowId + "'"
                + (pinned != null ? ", version=" + pinned : "") + ")";
            return "No active run found for workflow " + workflowId + ". Start the workflow first with "
                + start + "; this node never creates a run, it only fires a trigger on one that"
                + " already exists.";
        }

        WorkflowRunEntity live = probe.get();
        String head = "No run of workflow " + workflowId + " is eligible for a sub-workflow call. Run "
            + live.getRunIdPublic() + " is alive with status " + live.getStatus() + " but ";

        // Cause 1: the status is non-terminal yet outside the set this node accepts. Two distinct
        // cases, and they need different advice, so they get different branches.
        if (live.getStatus() == RunStatus.AWAITING_SIGNAL) {
            // Parked on an approval, interface or wait node. Resolving the signal resumes the DAG,
            // which is what makes the run fireable again; it does not go straight back to
            // WAITING_TRIGGER, and RUNNING is accepted here anyway.
            return head + "it is parked on a blocking node and cannot accept a new fire until that"
                + " node is resolved. Resolve it with resolve_approval or continue_interface,"
                + " depending on which node is blocking, then call this sub-workflow again.";
        }
        if (!ACTIVE_STATUSES.contains(live.getStatus())) {
            // PENDING is the entity default, so this is typically a run created moments ago that has
            // not started yet. It is transient: retrying is the right move.
            return head + "a sub-workflow call only fires a run that is waiting for its trigger,"
                + " running, or paused. Status " + live.getStatus() + " is a transient state the run"
                + " has not left yet. Call again once it settles.";
        }

        // Cause 2: right status, wrong version. Only reachable when the workflow is pinned, because
        // the unpinned lookup does not filter on version at all.
        Integer pinnedVersion = entity.getPinnedVersion();
        Integer runVersion = live.getPlanVersion();
        if (pinnedVersion != null && !pinnedVersion.equals(runVersion)) {
            // "Start a run at the pinned version" stays the remedy HERE even though pin no
            // longer asks for one (2026-08-25: pin provisions its own production run). This
            // branch is the other failure: a run exists, it is simply at the wrong version,
            // and re-pinning would move production rather than fix the call.
            String startAtPin = "Start a run with workflow(action='execute', id='" + workflowId
                + "', version=" + pinnedVersion + ").";
            if (runVersion == null) {
                return head + "carries no plan version, so it can never match the pinned version "
                    + pinnedVersion + ". " + startAtPin;
            }
            return head + "sits at plan version " + runVersion + " while the workflow is pinned to version "
                + pinnedVersion + ", and a sub-workflow call only fires the pinned version. " + startAtPin
                + " Re-pinning to version " + runVersion + " with workflow(action='pin', workflow_id='"
                + workflowId + "', version=" + runVersion + ") is the alternative, but only if that"
                + " version is the one you want in production: it redirects every production trigger of"
                + " this workflow (webhook, schedule, chained workflow) to it, and it fails outright if"
                + " this run is a clone of a published workflow, because clones cannot be pinned.";
        }

        // Cause 3: right status, and either unpinned or already at the pinned version, yet the lookup
        // still missed it. Only reachable when the two queries disagree, i.e. the run changed between
        // findActiveRun and this probe. Kept as an explicit branch rather than folded into the plain
        // message so the caller is told to retry rather than told to start a run that exists.
        return head + "the sub-workflow lookup did not select it, which means its state changed while"
            + " this node was resolving it. Call this sub-workflow again.";
    }

    /**
     * Resolves the trigger ID to fire on the sub-workflow.
     * Uses config.triggerId if set, otherwise finds the first fireable trigger.
     */
    private String resolveTriggerId(WorkflowPlan plan) {
        // If config has explicit triggerId, use it
        if (config != null && config.triggerId() != null && !config.triggerId().isBlank()) {
            return config.triggerId();
        }

        // Find first fireable trigger from the plan
        List<Trigger> triggers = plan.getTriggers();
        if (triggers == null || triggers.isEmpty()) {
            return null;
        }

        for (Trigger trigger : triggers) {
            String type = trigger.type();
            if (type != null && !UNFIREABLE_TYPES.contains(type.toLowerCase(Locale.ROOT))) {
                return trigger.getNormalizedKey();
            }
        }
        return null;
    }

    /**
     * Resolves the TriggerType for the given triggerId from the plan.
     */
    private TriggerType resolveTriggerType(WorkflowPlan plan, String triggerId) {
        List<Trigger> triggers = plan.getTriggers();
        if (triggers != null) {
            for (Trigger trigger : triggers) {
                if (trigger.getNormalizedKey().equals(triggerId)) {
                    try {
                        return TriggerType.fromString(trigger.type());
                    } catch (Exception e) {
                        // Fall through to default
                    }
                }
            }
        }
        return TriggerType.MANUAL;
    }

    // ========================================================================
    // WAITING FOR THE CHILD EPOCH TO CLOSE
    // ========================================================================

    /** First poll delay. Short, so a child that was almost done is not padded by a fixed tick. */
    static final long EPOCH_POLL_MIN_MS = 200L;
    /**
     * Ceiling for the backoff, so a long wait settles at about one round of checks per second.
     *
     * <p>A "round" is not one read: the epoch predicate alone consults the signal table, both
     * async-agent stores and the run's snapshot, and it logs at INFO when it finds work. A long
     * wait is therefore chatty in the logs as well as on the wire, which is the price of asking
     * the engine's own gate instead of inventing a second answer.
     */
    static final long EPOCH_POLL_MAX_MS = 1_000L;
    /** How many polls between child-run status reads. The deadline bounds a missed transition. */
    static final int TERMINAL_CHECK_EVERY_N_POLLS = 5;

    /**
     * How the wait ended. Each value gets its OWN message, because they are different facts about
     * the child and imply different actions. Collapsing them into one wording is how an agent ends
     * up being told to go stop a run that already stopped.
     */
    enum WaitOutcome {
        /** The epoch closed. The only outcome that lets the node read the child's outputs. */
        CLOSED,
        /** The budget ran out with the child still working. */
        BUDGET_SPENT,
        /** The child run ended (cancelled/failed/...) without finishing this epoch. */
        CHILD_ENDED,
        /** THIS run was stopped while waiting. */
        RUN_STOPPED,
        /** The worker was interrupted (shutdown/drain). */
        INTERRUPTED,
        /** Cannot wait here: doing so would hold a database transaction open for the budget. */
        CANNOT_WAIT_IN_TRANSACTION
    }

    /**
     * Block until the child's epoch is no longer in flight, or the budget runs out.
     *
     * <p>The predicate is {@link ReusableTriggerService#isEpochStillOpen}, the very check the
     * engine uses to decide whether that epoch may be closed. Re-reading it rather than trusting
     * something the fire returned is deliberate: an epoch can be left open by three different
     * paths (the deferred-reset branch, the under-lock re-check inside the reset, and the reset's
     * terminal-status guard) and only the first is knowable when the result is built.
     *
     * <p>Fail-OPEN on everything it cannot determine. If the trigger service was never injected,
     * or a read throws, this reports CLOSED and the node proceeds exactly as it did before this
     * check existed. A diagnostic must not become a new way for a healthy run to fail.
     */
    private WaitOutcome awaitChildEpochClosed(String subRunId, String triggerId, int childEpoch,
                                              long deadlineNanos, ExecutionContext context) {
        if (reusableTriggerService == null || subRunId == null || triggerId == null || childEpoch < 0) {
            // Nothing to ask. Pre-check behaviour: assume finished and read what is there.
            //
            // childEpoch < 0 is a real value: a fire dispatched to the QUEUE reports epoch -1.
            // This node always fires with the queue bypassed, so that cannot happen today, and if
            // it did the outcome would be exactly the pre-change behaviour. If the fire ever stops
            // bypassing the queue, this line becomes a hole and must fail instead of proceeding.
            return WaitOutcome.CLOSED;
        }

        // One check is always safe, and it carries the correctness guarantee: never publish a
        // partial epoch as a success. Only the LOOP below is conditional.
        //
        // The guarantee is conditional on ONE thing, stated here rather than left implicit: the
        // predicate must be readable. safeIsEpochStillOpen answers "closed" when the read throws,
        // so a persistent Redis or DB failure degrades this node back to its old behaviour, a
        // success over whatever rows exist. That is a deliberate availability-over-correctness
        // choice, matching how the engine's own epoch gate fails open, and it is logged at WARN.
        if (!safeIsEpochStillOpen(subRunId, triggerId, childEpoch)) {
            return WaitOutcome.CLOSED;
        }

        // Refuse to poll while a transaction is pinned by this call chain. On the step-by-step
        // lane this node runs inside a read-write @Transactional method, so sleeping here would
        // hold a pooled DB connection and an open Hibernate session for the whole budget, which
        // Postgres eventually kills on idle_in_transaction_session_timeout and which starves the
        // pool for every other request meanwhile. Failing fast with the truth is strictly better,
        // and the correctness guarantee above already held before we get here.
        //
        // TWO questions, because neither alone is sufficient:
        //   - isInsideTransaction() catches the plain same-thread case, but it reads a
        //     THREAD-LOCAL, and this node is not always reached on the thread that opened the
        //     transaction: a split fans items onto the common pool, and a node-level timeout
        //     policy hands the body to its own executor. Across either hop the flag reads false
        //     while the caller is still blocked on the join holding the transaction open.
        //   - the run's execution MODE travels with the run, not the thread, so it survives both
        //     hops. It is the hop-proof half of the answer.
        //
        // One combination still slips past both: a run stored as AUTOMATIC that is nonetheless
        // driven through the step-by-step executor AND crosses one of those hops. Nothing does
        // that today, and closing it would mean threading the caller's transaction state through
        // every dispatch, so it is recorded rather than coded around.
        if (isInsideTransaction() || isStepByStepRun(context)) {
            logger.warn("SubWorkflow will not wait inside a transaction: nodeId={}, subRunId={}, childEpoch={}",
                nodeId, subRunId, childEpoch);
            return WaitOutcome.CANNOT_WAIT_IN_TRANSACTION;
        }

        long pollMs = EPOCH_POLL_MIN_MS;
        long startNanos = System.nanoTime();
        int terminalCheckCountdown = TERMINAL_CHECK_EVERY_N_POLLS;
        logger.info("SubWorkflow waiting for child epoch to close: nodeId={}, subRunId={}, childEpoch={}, budgetMs={}",
            nodeId, subRunId, childEpoch, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));

        while (true) {
            // The child cannot finish this epoch any more: say so now rather than at the deadline.
            //
            // Checked on a slow cadence, not every poll. It is a second DB round trip whose answer
            // changes at most once for the whole wait, and the deadline already bounds the case
            // where it is missed.
            if (--terminalCheckCountdown <= 0) {
                terminalCheckCountdown = TERMINAL_CHECK_EVERY_N_POLLS;
                RunStatus childStatus = readChildRunStatus(subRunId);
                if (childStatus != null && childStatus.isTerminal()) {
                    // Re-read the epoch ONCE before giving up. The two reads are not atomic, so a
                    // child that finished between them would otherwise be reported as unfinished.
                    if (!safeIsEpochStillOpen(subRunId, triggerId, childEpoch)) {
                        return WaitOutcome.CLOSED;
                    }
                    logger.warn("SubWorkflow child run is terminal ({}) with its epoch unfinished: nodeId={}, subRunId={}, childEpoch={}",
                        childStatus, nodeId, subRunId, childEpoch);
                    return WaitOutcome.CHILD_ENDED;
                }
            }

            // A stop on this run (or on an ancestor) must not be held up by a child we are only
            // observing. The child keeps its own cancellation path; this is about OUR worker.
            if (isCancelled(context)) {
                logger.info("SubWorkflow wait abandoned, run stopped: nodeId={}, subRunId={}, childEpoch={}",
                    nodeId, subRunId, childEpoch);
                return WaitOutcome.RUN_STOPPED;
            }

            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                logger.error("SubWorkflow child epoch still open at the deadline: nodeId={}, subRunId={}, childEpoch={}, waitedMs={}",
                    nodeId, subRunId, childEpoch,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
                return WaitOutcome.BUDGET_SPENT;
            }

            long sleepMs = Math.min(pollMs, TimeUnit.NANOSECONDS.toMillis(remainingNanos) + 1);
            if (!compensatedSleep(sleepMs)) {
                logger.warn("SubWorkflow wait interrupted: nodeId={}, subRunId={}, childEpoch={}", nodeId, subRunId, childEpoch);
                return WaitOutcome.INTERRUPTED;
            }
            // Geometric backoff: responsive for a child that is nearly done, cheap for a long one.
            pollMs = Math.min(pollMs * 2, EPOCH_POLL_MAX_MS);

            if (!safeIsEpochStillOpen(subRunId, triggerId, childEpoch)) {
                logger.info("SubWorkflow child epoch closed after waiting: nodeId={}, subRunId={}, childEpoch={}, waitedMs={}",
                    nodeId, subRunId, childEpoch,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
                return WaitOutcome.CLOSED;
            }
        }
    }

    /**
     * Is this run being stepped one node at a time?
     *
     * <p>Hop-proof companion to {@link #isInsideTransaction()}: the mode belongs to the run, so it
     * still answers correctly on a thread the transactional caller merely dispatched to. That lane
     * holds an open read-write transaction across the whole node call, so it must never be polled
     * in.
     *
     * <p>Fail-safe direction matches its companion: an unreadable mode is treated as step-by-step,
     * because wrongly skipping the wait costs one honest failure while wrongly waiting pins a
     * pooled connection for up to the whole budget.
     */
    private boolean isStepByStepRun(ExecutionContext context) {
        if (workflowRunRepository == null || context == null || context.runId() == null) {
            return false;
        }
        try {
            return workflowRunRepository.findByRunIdPublic(context.runId())
                .map(WorkflowRunEntity::getExecutionMode)
                .map(com.apimarketplace.orchestrator.domain.workflow.ExecutionMode::isStepByStep)
                .orElse(false);
        } catch (Exception e) {
            logger.warn("SubWorkflow could not read this run's execution mode, not waiting: runId={}, error={}",
                context.runId(), e.getMessage());
            return true;
        }
    }

    /**
     * Is a real database transaction open on this thread?
     *
     * <p>Fail-safe direction is deliberately the opposite of the other checks here: if this cannot
     * be determined, assume there IS one and skip the wait. Wrongly skipping costs one honest
     * failure; wrongly waiting pins a pooled connection for up to the whole budget.
     */
    private static boolean isInsideTransaction() {
        try {
            return org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive();
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * The epoch predicate, with the fail-open policy applied in one place.
     *
     * <p>A read that throws is not evidence the child is unfinished, so it resolves to "closed"
     * and the node proceeds exactly as it did before this check existed.
     */
    private boolean safeIsEpochStillOpen(String subRunId, String triggerId, int childEpoch) {
        try {
            return reusableTriggerService.isEpochStillOpen(subRunId, triggerId, childEpoch);
        } catch (Exception e) {
            logger.warn("SubWorkflow epoch-open check failed, proceeding as if closed: nodeId={}, subRunId={}, childEpoch={}, error={}",
                nodeId, subRunId, childEpoch, e.getMessage());
            return false;
        }
    }

    /**
     * The child run's CURRENT status, or null when it cannot be read.
     *
     * <p>Deliberately the scalar projection rather than {@code findByRunIdPublic}: the child run
     * row was already loaded earlier in this same call, so an entity lookup can be served from
     * Hibernate's first-level cache and keep reporting the status as of that first load. This
     * method exists to observe a status CHANGE, so a cached answer would make it permanently
     * blind and the branch it guards would be dead code that reads as coverage.
     *
     * <p>Callers must treat only {@link RunStatus#isTerminal()} as an ending. WAITING_TRIGGER is
     * where a healthy reusable run rests between fires; reading it as an end would abandon the
     * wait on essentially every child.
     */
    private RunStatus readChildRunStatus(String subRunId) {
        if (workflowRunRepository == null) {
            return null;
        }
        try {
            return workflowRunRepository.findStatusByRunIdPublic(subRunId).orElse(null);
        } catch (Exception e) {
            logger.debug("SubWorkflow could not read child run status while waiting: subRunId={}, error={}",
                subRunId, e.getMessage());
            return null;
        }
    }

    /** Was THIS run stopped while we were waiting? Fail-open, a missing publisher means no stop. */
    private boolean isCancelled(ExecutionContext context) {
        if (workflowRedisPublisher == null || context == null || context.runId() == null) {
            return false;
        }
        try {
            return workflowRedisPublisher.isAgentCancelSignalSet(context.runId());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Sleep without stealing a worker from the pool this node runs on.
     *
     * <p>{@code SubWorkflowNode.execute} is dispatched onto {@link ForkJoinPool#commonPool} by
     * every path that runs nodes concurrently (fork branches, split items, and any cycle with
     * more than one ready node). A plain {@code Thread.sleep} there occupies a worker with no
     * replacement, and the common pool's parallelism is {@code availableProcessors - 1}, which is
     * ONE on a 2-vCPU pod: a single waiting sub-workflow node would stall every concurrent
     * dispatch on that pod for the whole budget, including the child's own continuation, which
     * needs the same pool. That turns the wait into a self-inflicted timeout.
     *
     * <p>{@link ForkJoinPool#managedBlock} tells the pool to compensate by starting a replacement
     * worker for the duration. This is the same property the pre-existing
     * {@code CompletableFuture.get(timeout)} relied on: its internal Signaller is itself a
     * {@code ManagedBlocker}, which is why the previous blocking call was safe and a bare sleep
     * would not have been.
     *
     * <p>Scope, so this comment does not claim more than it delivers: only the SLEEP is
     * compensated. The three reads around it (the epoch predicate, the child status, the cancel
     * key) are ordinary short JDBC/Redis calls on this worker, same as every other node in the
     * engine does.
     *
     * @return false if interrupted (the interrupt flag is restored), true otherwise
     */
    static boolean compensatedSleep(long millis) {
        try {
            ForkJoinPool.managedBlock(new SleepBlocker(TimeUnit.MILLISECONDS.toNanos(millis)));
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** A timed sleep expressed as a {@link ForkJoinPool.ManagedBlocker}. */
    static final class SleepBlocker implements ForkJoinPool.ManagedBlocker {
        private long remainingNanos;

        SleepBlocker(long nanos) {
            this.remainingNanos = nanos;
        }

        @Override
        public boolean block() throws InterruptedException {
            long startedAt = System.nanoTime();
            try {
                TimeUnit.NANOSECONDS.sleep(remainingNanos);
            } finally {
                // A ManagedBlocker may be resumed early, so carry the remainder forward instead
                // of assuming one call slept the whole duration.
                remainingNanos -= (System.nanoTime() - startedAt);
            }
            return isReleasable();
        }

        @Override
        public boolean isReleasable() {
            return remainingNanos <= 0;
        }
    }

    /**
     * What to tell the author, per way the wait ended.
     *
     * <p>One message per outcome on purpose. They are different facts about the child and call
     * for different actions: telling someone their sub-workflow "is still executing and can still
     * perform external actions" when it was in fact CANCELLED sends them to stop a run that has
     * already stopped, and reads as a warning about side effects that cannot happen.
     */
    static String waitFailureMessage(WaitOutcome outcome, int timeoutSeconds, String subRunId) {
        String budget = timeoutSeconds + (timeoutSeconds == 1 ? " second" : " seconds");
        switch (outcome) {
            case CHILD_ENDED:
                return String.format(
                    "Sub-workflow run %s ended before finishing the work this node asked for, so its"
                    + " outputs do not exist and nothing downstream of this node ran. Read that run"
                    + " with workflow(action='get_run') to see which of its steps failed or was"
                    + " cancelled.", subRunId);
            case RUN_STOPPED:
                return String.format(
                    "This run was stopped while waiting for sub-workflow run %s, so its outputs were"
                    + " never collected. The sub-workflow was not stopped by this node and may still"
                    + " be running; stop it with workflow(action='stop_run', run_id='%s') if you need"
                    + " it stopped.", subRunId, subRunId);
            case INTERRUPTED:
                return String.format(
                    "Waiting for sub-workflow run %s was interrupted before it finished, so its"
                    + " outputs were not collected. The sub-workflow itself was not stopped and may"
                    + " still be running. Re-run this workflow to try again.", subRunId);
            case CANNOT_WAIT_IN_TRANSACTION:
                return String.format(
                    "Sub-workflow run %s had not finished when this node was reached, and this"
                    + " execution mode cannot wait for it. Run this workflow normally rather than"
                    + " stepping through it, or make the sub-workflow finish in one pass by moving"
                    + " any wait, user approval or interface step out of it.", subRunId);
            case BUDGET_SPENT:
            default:
                return String.format(
                    "Sub-workflow did not finish within %s. Run %s was NOT stopped: it is still"
                    + " executing and can still perform external actions such as sending or"
                    + " publishing. Nothing downstream of this node ran, because the sub-workflow's"
                    + " outputs do not exist yet. Either raise timeoutSeconds if the sub-workflow is"
                    + " simply slow, or stop it with workflow(action='stop_run', run_id='%s'). If it"
                    + " is waiting on a person (a user"
                    + " approval, or an interface waiting for __continue) no timeout will help:"
                    + " resolve it, or move that step out of the sub-workflow and into this"
                    + " workflow.", budget, subRunId, subRunId);
        }
    }

    /** The fire itself outliving the budget: the child is still going, same as BUDGET_SPENT. */
    private static String stillRunningMessage(int timeoutSeconds, String subRunId) {
        return waitFailureMessage(WaitOutcome.BUDGET_SPENT, timeoutSeconds, subRunId);
    }

    /**
     * Collects outputs from step data for a specific epoch.
     * Loads raw output for each completed step in the epoch.
     */
    private Map<String, Object> collectEpochOutputs(String runId, int epoch, String tenantId) {
        Map<String, Object> outputs = new LinkedHashMap<>();

        if (workflowStepDataRepository == null || stepOutputService == null) {
            logger.warn("Cannot collect epoch outputs: step data repository or output service not injected");
            return outputs;
        }

        List<WorkflowStepDataRepository.EpochOutputProjection> outputRefs =
            workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(runId, epoch);
        for (WorkflowStepDataRepository.EpochOutputProjection outputRef : outputRefs) {
            try {
                Map<String, Object> stepOutput = stepOutputService.loadRawOutput(
                    outputRef.getOutputStorageId(), tenantId);
                if (stepOutput != null && !stepOutput.isEmpty()) {
                    outputs.put(outputRef.getStepAlias(), stepOutput);
                }
            } catch (Exception e) {
                logger.warn("Failed to load output for step {}: {}", outputRef.getStepAlias(), e.getMessage());
            }
        }
        return outputs;
    }

    /**
     * Gets the current sub-workflow recursion depth from context global data.
     */
    private int getCurrentDepth(ExecutionContext context) {
        return context.getGlobalData(DEPTH_KEY)
            .map(v -> {
                if (v instanceof Number) {
                    return ((Number) v).intValue();
                }
                return 0;
            })
            .orElse(0);
    }

    /**
     * Builds the workflow-id chain for cycle detection. The current workflow id
     * is added on first sub-workflow entry so direct self-calls fail fast.
     */
    private List<String> getCurrentAncestry(ExecutionContext context) {
        List<String> ancestry = new ArrayList<>();
        context.getGlobalData(ANCESTRY_KEY).ifPresent(raw -> {
            if (raw instanceof Collection<?> values) {
                for (Object value : values) {
                    addWorkflowIdIfPresent(ancestry, value);
                }
            } else {
                addWorkflowIdIfPresent(ancestry, raw);
            }
        });

        if (context.plan() != null) {
            addWorkflowIdIfPresent(ancestry, context.plan().getId());
        }
        return List.copyOf(ancestry);
    }

    private void addWorkflowIdIfPresent(List<String> ancestry, Object rawWorkflowId) {
        if (rawWorkflowId == null) {
            return;
        }
        String workflowId = String.valueOf(rawWorkflowId).trim();
        if (workflowId.isEmpty() || containsWorkflowId(ancestry, workflowId)) {
            return;
        }
        ancestry.add(workflowId);
    }

    private boolean containsWorkflowId(Collection<String> ancestry, String workflowId) {
        if (workflowId == null) {
            return false;
        }
        for (String ancestor : ancestry) {
            if (workflowId.equalsIgnoreCase(ancestor)) {
                return true;
            }
        }
        return false;
    }

    private List<String> appendWorkflowId(List<String> ancestry, String workflowId) {
        List<String> childAncestry = new ArrayList<>(ancestry);
        addWorkflowIdIfPresent(childAncestry, workflowId);
        return List.copyOf(childAncestry);
    }

    private Map<String, Object> buildChildSubWorkflowGlobalData(int currentDepth, List<String> childAncestry) {
        Map<String, Object> globalData = new LinkedHashMap<>();
        globalData.put(DEPTH_KEY, currentDepth + 1);
        globalData.put(ANCESTRY_KEY, childAncestry);
        return globalData;
    }

    /**
     * Resolves the workflowId, which may be a SpEL expression.
     */
    private String resolveWorkflowId(ExecutionContext context) {
        String rawWorkflowId = config != null ? config.workflowId() : null;
        if (rawWorkflowId == null || rawWorkflowId.isBlank()) {
            return null;
        }

        if (templateAdapter != null) {
            try {
                Map<String, Object> toResolve = Map.of("__wfId__", rawWorkflowId);
                Map<String, Object> resolved = templateAdapter.resolveTemplates(toResolve, context);
                Object result = resolved.get("__wfId__");
                return result != null ? String.valueOf(result) : rawWorkflowId;
            } catch (Exception e) {
                logger.warn("Failed to resolve workflowId expression '{}': {}",
                    rawWorkflowId, e.getMessage());
                return rawWorkflowId;
            }
        }

        return rawWorkflowId;
    }

    /**
     * Resolves the input data to pass to the sub-workflow.
     */
    private Map<String, Object> resolveInputData(ExecutionContext context) {
        String inputMapping = config != null ? config.inputMapping() : null;
        if (inputMapping == null || inputMapping.isBlank()) {
            // Default: pass trigger data as input
            return context.triggerData() != null ? new HashMap<>(context.triggerData()) : new HashMap<>();
        }

        if (templateAdapter != null) {
            try {
                Map<String, Object> toResolve = Map.of("__input__", inputMapping);
                Map<String, Object> resolved = templateAdapter.resolveTemplates(toResolve, context);
                Object result = resolved.get("__input__");
                if (result instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> mapResult = (Map<String, Object>) result;
                    return new HashMap<>(mapResult);
                }
                // If resolved to a string or other type, wrap it
                Map<String, Object> wrapped = new HashMap<>();
                wrapped.put("data", result);
                return wrapped;
            } catch (Exception e) {
                logger.warn("Failed to resolve inputMapping '{}': {}", inputMapping, e.getMessage());
            }
        }

        // Fallback: pass trigger data
        return context.triggerData() != null ? new HashMap<>(context.triggerData()) : new HashMap<>();
    }

    private Map<String, Object> buildInputDataMap(String workflowId) {
        Map<String, Object> inputData = new LinkedHashMap<>();
        inputData.put("workflowId", workflowId);
        if (config != null) {
            if (config.inputMapping() != null) inputData.put("inputMapping", config.inputMapping());
            inputData.put("timeoutSeconds", config.timeoutSeconds());
            inputData.put("maxDepth", config.maxDepth());
            if (config.triggerId() != null) inputData.put("triggerId", config.triggerId());
        }
        return inputData;
    }

    private static Object[] createSubRunLocks() {
        Object[] locks = new Object[SUB_RUN_LOCK_STRIPES];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    /**
     * Stripe lock for a sub-run id. Serializes concurrent fires of the SAME
     * sub-run (different threads, same id) so they don't race on its snapshot.
     *
     * <p>Edge case (accepted): a transitively-nested sub-workflow (recursion
     * capped at {@code config.maxDepth()}, default 5) could hash to the same
     * stripe (~1/64 per level) as a parent still inside its {@code synchronized}
     * block, parking the ForkJoinPool worker until the parent's
     * {@code get(timeoutSeconds)} returns. This is a timeout-bounded circular
     * wait - the parent holds the stripe monitor while blocked on its
     * {@code future.get()} - not a permanent deadlock; it self-heals at the
     * sub-workflow timeout (default 300s). The common case (same stripe,
     * different unrelated runs) is the intended serialization and is covered by
     * SubWorkflowNodeTest#serializesSameChildRunCallsAndReloadsRunBeforeFiring.
     */
    private static Object lockForSubRun(String subRunId) {
        int stripe = Math.floorMod(subRunId.hashCode(), SUB_RUN_LOCKS.length);
        return SUB_RUN_LOCKS[stripe];
    }

    // Getters
    public Core.SubWorkflowConfig getSubWorkflowConfig() { return config; }

    /**
     * Accepts services from the registry.
     * SubWorkflowNode needs WorkflowRepository, WorkflowRunRepository,
     * ReusableTriggerService, StepOutputService, and WorkflowStepDataRepository.
     */
    @Override
    public void acceptServices(ServiceRegistry registry) {
        super.acceptServices(registry);
        this.workflowRepository = registry.getWorkflowRepository();
        this.workflowRunRepository = registry.getWorkflowRunRepository();
        this.reusableTriggerService = registry.getReusableTriggerService();
        this.productionRunResolver = registry.getProductionRunResolver();
        this.stepOutputService = registry.getStepOutputService();
        this.workflowStepDataRepository = registry.getWorkflowStepDataRepository();
        this.workflowRedisPublisher = registry.getWorkflowRedisPublisher();
    }

    // ========================================================================
    // SERVICE INJECTION (via setters, like WaitNode pattern)
    // ========================================================================

    public void setWorkflowRepository(WorkflowRepository workflowRepository) {
        this.workflowRepository = workflowRepository;
    }

    public void setWorkflowRunRepository(WorkflowRunRepository workflowRunRepository) {
        this.workflowRunRepository = workflowRunRepository;
    }

    public void setReusableTriggerService(ReusableTriggerService reusableTriggerService) {
        this.reusableTriggerService = reusableTriggerService;
    }

    public void setProductionRunResolver(
            com.apimarketplace.orchestrator.trigger.ProductionRunResolver productionRunResolver) {
        this.productionRunResolver = productionRunResolver;
    }

    public void setStepOutputService(StepOutputService stepOutputService) {
        this.stepOutputService = stepOutputService;
    }

    public void setWorkflowStepDataRepository(WorkflowStepDataRepository workflowStepDataRepository) {
        this.workflowStepDataRepository = workflowStepDataRepository;
    }

    public void setWorkflowRedisPublisher(
            com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher workflowRedisPublisher) {
        this.workflowRedisPublisher = workflowRedisPublisher;
    }

    // Package-private getters for testing
    WorkflowRepository getWorkflowRepository() { return workflowRepository; }
    WorkflowRunRepository getWorkflowRunRepository() { return workflowRunRepository; }
    ReusableTriggerService getReusableTriggerService() { return reusableTriggerService; }
    StepOutputService getStepOutputService() { return stepOutputService; }
    WorkflowStepDataRepository getWorkflowStepDataRepository() { return workflowStepDataRepository; }

    // ========================================================================
    // BUILDER
    // ========================================================================

    public static class Builder {
        private String nodeId;
        private Core.SubWorkflowConfig config;

        public Builder nodeId(String nodeId) { this.nodeId = nodeId; return this; }
        public Builder subWorkflowConfig(Core.SubWorkflowConfig config) { this.config = config; return this; }
        public SubWorkflowNode build() { return new SubWorkflowNode(nodeId, config); }
    }

    public static Builder builder() { return new Builder(); }
}
