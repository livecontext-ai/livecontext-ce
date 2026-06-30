package com.apimarketplace.orchestrator.execution.v2.nodes;

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * SubWorkflow node - Executes another workflow by firing its trigger (reusable run pattern).
 *
 * The node loads the target workflow, finds its active run (respecting pinned versions),
 * fires the trigger via ReusableTriggerService, and collects the epoch outputs.
 *
 * Anti-recursion: Tracks call depth via ExecutionContext global data.
 * If the depth exceeds the configured maxDepth, the node fails immediately.
 *
 * Usage:
 * - Compose workflows by calling reusable sub-workflows
 * - Target workflow must have an active run (start it first)
 * - Pass data in via inputMapping, receive results as output
 * - Timeout protection prevents runaway sub-workflows
 */
public class SubWorkflowNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(SubWorkflowNode.class);
    private static final int SUB_RUN_LOCK_STRIPES = 64;
    private static final Object[] SUB_RUN_LOCKS = createSubRunLocks();

    /** Global data key used to track sub-workflow recursion depth. */
    static final String DEPTH_KEY = ExecutionMetadataKeys.SUB_WORKFLOW_DEPTH;

    /** Global data key used to track sub-workflow workflow-id ancestry. */
    static final String ANCESTRY_KEY = ExecutionMetadataKeys.SUB_WORKFLOW_ANCESTRY;

    /** Run statuses considered "active" for sub-workflow dispatch. */
    private static final Set<RunStatus> ACTIVE_STATUSES = Set.of(
        RunStatus.WAITING_TRIGGER, RunStatus.RUNNING, RunStatus.PAUSED
    );

    /** Trigger types that cannot be fired by sub-workflow node. */
    private static final Set<String> UNFIREABLE_TYPES = Set.of("workflow", "error");

    private final Core.SubWorkflowConfig config;

    // Services injected via setters or acceptServices
    private WorkflowRepository workflowRepository;
    private WorkflowRunRepository workflowRunRepository;
    private ReusableTriggerService reusableTriggerService;
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
                    "Workflow not found: " + workflowId, failOutput, 0);
            }

            WorkflowEntity entity = entityOpt.get();
            Map<String, Object> planMap = entity.getPlan();
            if (planMap == null || planMap.isEmpty()) {
                Map<String, Object> failOutput = new HashMap<>();
                failOutput.put("resolved_params", resolvedParams);
                return NodeExecutionResult.failureWithOutput(nodeId,
                    "Workflow has no plan: " + workflowId, failOutput, 0);
            }

            // 4. Find active run (WebhookDispatchService pattern)
            if (workflowRunRepository == null) {
                Map<String, Object> failOutput = new HashMap<>();
                failOutput.put("resolved_params", resolvedParams);
                return NodeExecutionResult.failureWithOutput(nodeId, "WorkflowRunRepository not injected", failOutput, 0);
            }

            WorkflowRunEntity run = findActiveRun(entity, workflowId);
            if (run == null) {
                Map<String, Object> failOutput = new HashMap<>();
                failOutput.put("resolved_params", resolvedParams);
                return NodeExecutionResult.failureWithOutput(nodeId,
                    "No active run found for workflow " + workflowId + ". Start the workflow first.", failOutput, 0);
            }

            if (run.getStatus().isTerminal()) {
                Map<String, Object> failOutput = new HashMap<>();
                failOutput.put("resolved_params", resolvedParams);
                return NodeExecutionResult.failureWithOutput(nodeId,
                    "Run " + run.getRunIdPublic() + " is in terminal status: " + run.getStatus(), failOutput, 0);
            }

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

            // F2.2 - register the parent→child link BEFORE firing so an in-flight
            // cancel on the parent run propagates downward. The engine's
            // isAgentCancelSignalSet walks workflow:parent:{childRunId} pointers
            // up to find a cancelled ancestor. Cleared in finally below.
            if (workflowRedisPublisher != null && context.runId() != null
                    && subRunId != null && !subRunId.equals(context.runId())) {
                workflowRedisPublisher.registerSubWorkflowParent(subRunId, context.runId());
            }

            int timeoutSeconds = config != null ? config.timeoutSeconds() : 300;
            TriggerExecutionResult triggerResult;
            Map<String, Object> resultOutputs;
            synchronized (lockForSubRun(subRunId)) {
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
                try {
                    triggerResult = CompletableFuture.supplyAsync(() -> {
                        TriggerExecutionResult[] holder = new TriggerExecutionResult[1];
                        com.apimarketplace.common.web.TenantResolver.runWithOrgScope(orgIdForWorker, () ->
                            holder[0] = reusableTriggerService.executeTriggerInternal(
                                runForFire, triggerId, triggerType, inputData, true, childGlobalData)
                        );
                        return holder[0];
                    }).get(timeoutSeconds, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    String msg = String.format(
                        "Sub-workflow timed out after %d seconds (runId=%s)", timeoutSeconds, subRunId);
                    logger.error("SubWorkflow timeout: nodeId={}, {}", nodeId, msg);
                    if (workflowRedisPublisher != null) {
                        workflowRedisPublisher.clearSubWorkflowParent(subRunId);
                    }
                    Map<String, Object> failOutput = new HashMap<>();
                    failOutput.put("resolved_params", resolvedParams);
                    return NodeExecutionResult.failureWithOutput(nodeId, msg, failOutput, 0);
                } finally {
                    // Best-effort cleanup on the success path too (TTL backstops if missed).
                    if (workflowRedisPublisher != null) {
                        workflowRedisPublisher.clearSubWorkflowParent(subRunId);
                    }
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

                // 9. Collect outputs from step data (epoch-scoped)
                resultOutputs = collectEpochOutputs(
                    subRunId, triggerResult.epoch(), context.tenantId());
            }

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
     * Finds the active run for the target workflow, following the WebhookDispatchService pattern:
     * - Pinned version? → find latest run with matching planVersion
     * - No pin? → find latest run regardless of version
     * Then validate that the run is in an active status.
     */
    private WorkflowRunEntity findActiveRun(WorkflowEntity entity, UUID workflowId) {
        Optional<WorkflowRunEntity> runOpt;
        if (entity.getPinnedVersion() != null) {
            runOpt = workflowRunRepository.findFirstByWorkflowIdAndPlanVersionAndStatusInOrderByStartedAtDesc(
                workflowId, entity.getPinnedVersion(), ACTIVE_STATUSES);
        } else {
            runOpt = workflowRunRepository.findFirstByWorkflowIdAndStatusInOrderByStartedAtDesc(
                workflowId, ACTIVE_STATUSES);
        }
        return runOpt.orElse(null);
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

    public void setStepOutputService(StepOutputService stepOutputService) {
        this.stepOutputService = stepOutputService;
    }

    public void setWorkflowStepDataRepository(WorkflowStepDataRepository workflowStepDataRepository) {
        this.workflowStepDataRepository = workflowStepDataRepository;
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
