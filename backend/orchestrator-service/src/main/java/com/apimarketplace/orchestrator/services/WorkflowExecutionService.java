package com.apimarketplace.orchestrator.services;

import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.config.WorkflowExecutionConfig;
import com.apimarketplace.orchestrator.utils.WorkflowUtils;
import com.apimarketplace.orchestrator.metrics.WorkflowMetrics;
import com.apimarketplace.orchestrator.execution.v2.WorkflowExecutionServiceV2;
import com.apimarketplace.orchestrator.execution.v2.engine.WorkflowResult;
import com.apimarketplace.orchestrator.services.cache.RunScopedCache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Workflow Execution Service - V2 Adapter
 *
 * This service provides a compatibility layer between the legacy controller API
 * and the new V2 unified execution engine.
 *
 * ARCHITECTURE:
 * - Controller creates WorkflowExecution (legacy object for compatibility)
 * - Execution delegates to WorkflowExecutionServiceV2 (unified tree engine)
 * - Legacy orchestrators, executors, and coordinators DELETED
 *
 * MIGRATION STATUS: ✅ COMPLETE
 * - Legacy code: ~5000 lines DELETED
 * - New V2 code: ~2500 lines
 * - Reduction: ~50%
 */
@Service
public class WorkflowExecutionService implements RunScopedCache {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowExecutionService.class);

    @Autowired
    private WorkflowExecutionServiceV2 executionServiceV2;

    @Autowired
    private WorkflowPersistenceService persistenceService;

    @Autowired
    private WorkflowExecutionConfig config;

    @Autowired(required = false)
    private TriggerResolverService triggerResolverService;

    @Autowired
    private com.apimarketplace.interfaces.client.InterfaceClient interfaceClient;

    @Autowired
    private com.apimarketplace.orchestrator.services.interfaces.InterfacePlanExtractor interfacePlanExtractor;

    @Autowired
    private ExecutionGraphService executionGraphService;

    @Autowired
    private WorkflowMetrics workflowMetrics;

    @Autowired(required = false)
    private com.apimarketplace.orchestrator.services.markup.PlatformMarkupPinService platformMarkupPinService;

    @Autowired(required = false)
    private com.apimarketplace.orchestrator.services.markup.PlatformMarkupPlanValidator platformMarkupPlanValidator;

    /**
     * Creates a new workflow execution.
     *
     * This creates a WorkflowExecution object for compatibility with the controller.
     * The actual execution will be delegated to V2.
     *
     * planVersion is NOT set here - used by trigger-initiated runs where the pinned
     * version logic in buildRunEntity() should apply (planVersion=null → use pinned).
     */
    public WorkflowExecution createExecution(WorkflowPlan plan, Map<String, Object> dataInputs) {
        return createExecution(plan, dataInputs, null);
    }

    /**
     * Creates a new workflow execution with an explicit plan version.
     *
     * When planVersion is non-null, it is set on the execution BEFORE recordWorkflowStart(),
     * so that buildRunEntity() sees it and does NOT override with the pinned version.
     * This is used by manual runs from the editor (the user is running the current canvas version).
     *
     * When planVersion is null (trigger-initiated runs), buildRunEntity() applies
     * the pinned version logic as expected for production triggers.
     */
    public WorkflowExecution createExecution(WorkflowPlan plan, Map<String, Object> dataInputs, Integer planVersion) {
        String runId = WorkflowUtils.generateRunId();

        // Refuse plans that break platform-markup invariants before any side
        // effect (pin, persistence, interface snapshot). Fail-fast - the agent
        // or builder must fix the plan before re-submitting.
        if (platformMarkupPlanValidator != null) {
            platformMarkupPlanValidator.validate(plan);
        }

        // Pre-load execution graph from Redis cache
        executionGraphService.getExecutionGraph(plan);

        WorkflowExecution execution = new WorkflowExecution(runId, plan, dataInputs);

        // Set plan version BEFORE recordWorkflowStart() so buildRunEntity() sees it.
        // For manual runs: planVersion is the resolved version (e.g. v5) → pinned logic skipped.
        // For trigger runs: planVersion is null → pinned logic applies correctly.
        if (planVersion != null) {
            execution.setPlanVersion(planVersion);
        }

        persistenceService.recordWorkflowStart(execution);
        persistenceService.restoreExecutionState(execution);

        // Credits are consumed per-node in StepCompletionOrchestrator (-1 per node traversed),
        // not per workflow execution.

        // Pin platform-credential pricing versions for the life of the run so
        // mid-run rate changes don't leak into billing. Fail-open - run starts
        // even if pin creation fails (markup simply won't apply).
        if (platformMarkupPinService != null) {
            try {
                Long userId = Long.parseLong(plan.getTenantId());
                platformMarkupPinService.createPinsForRun(runId, userId, plan);
            } catch (NumberFormatException nfe) {
                logger.warn("Cannot pin markup - tenantId {} is not a numeric user id", plan.getTenantId());
            } catch (Exception e) {
                logger.warn("Failed to create platform-markup pins for run {}: {}", runId, e.getMessage());
            }
        }

        // Snapshot interfaces via interface-service to freeze templates
        if (execution.getWorkflowRunId() != null) {
            try {
                snapshotInterfacesForRun(plan, execution.getWorkflowRunId());
            } catch (Exception e) {
                logger.warn("Failed to snapshot interfaces for run {}: {}", runId, e.getMessage());
            }
        }

        // Enregistrer les metriques
        workflowMetrics.recordWorkflowStarted(plan.getTenantId());

        logger.info("✅ Created workflow execution: {} for plan: {}", runId, plan.getId());
        return execution;
    }

    /**
     * Executes a workflow using the V2 unified execution engine.
     *
     * This method delegates to WorkflowExecutionServiceV2 which uses the new
     * tree-based execution algorithm.
     */
    public void execute(WorkflowExecution execution) {
        logger.info("🚀 Executing workflow via V2: runId={}", execution.getRunId());

        try {
            // Update run status to RUNNING (needed for reusable trigger workflows)
            persistenceService.updateRunStatusToRunning(execution);

            WorkflowPlan plan = execution.getPlan();
            String tenantId = plan.getTenantId();

            // Resolve trigger items from datasource
            List<Map<String, Object>> triggerItems = resolveTriggerItems(plan, tenantId);

            // Execute via V2
            CompletableFuture<WorkflowResult> future = executionServiceV2.executeWorkflow(
                plan,
                triggerItems,
                tenantId,
                execution
            );

            // Wait for completion
            WorkflowResult result = future.join();

            // Update execution status
            if (result.successItems() > 0) {
                execution.complete();
                logger.info("✅ Workflow execution completed successfully: runId={}, success={}, failed={}",
                    execution.getRunId(), result.successItems(), result.failedItems());
            } else {
                String errorMsg = result.errorMessage().orElse("Execution failed");
                execution.setError(errorMsg, null);
                logger.error("❌ Workflow execution failed: runId={}, error={}",
                    execution.getRunId(), errorMsg);
            }

            // Record metrics
            long executionTime = calculateExecutionTime(execution);
            workflowMetrics.recordWorkflowCompleted(tenantId, executionTime, result.successItems() > 0);

        } catch (Exception e) {
            logger.error("❌ Workflow execution error: runId={}", execution.getRunId(), e);
            execution.setError(e.getMessage(), e);
            long executionTime = calculateExecutionTime(execution);
            workflowMetrics.recordWorkflowCompleted(execution.getPlan().getTenantId(), executionTime, false);
            throw e;
        } finally {
            cleanupExecutionResources(execution);
        }
    }

    /**
     * Calculate execution levels for the workflow graph.
     * Used by the controller to show workflow structure.
     */
    public Map<String, Object> calculateExecutionLevels(WorkflowPlan plan) {
        ExecutionGraph graph = executionGraphService.getExecutionGraph(plan);
        Map<String, Object> result = new HashMap<>();

        List<Map<String, Object>> levels = new ArrayList<>();
        for (int level = 0; level <= graph.getMaxLevel(); level++) {
            List<String> steps = graph.getStepsAtLevel(level);
            levels.add(Map.of(
                    "level", level,
                    "steps", steps
                             ));
        }

        result.put("levels", levels);
        result.put("maxLevel", graph.getMaxLevel());
        result.put("totalSteps", plan.getMcps().size() + plan.getTriggers().size());
        result.put("hasLoops", false);
        result.put("hasConditionalLogic", plan.hasConditionalLogic());

        return result;
    }

    /**
     * Handle execution errors.
     */
    public void handleExecutionError(WorkflowExecution execution, Exception error) {
        logger.error("💥 Handling execution error: runId={}, error={}",
            execution.getRunId(), error.getMessage(), error);

        try {
            execution.setError(error.getMessage(), error);
            persistenceService.recordWorkflowCompletion(execution);
            long executionTime = calculateExecutionTime(execution);
            workflowMetrics.recordWorkflowCompleted(execution.getPlan().getTenantId(), executionTime, false);
        } catch (Exception e) {
            logger.error("💥 Error while handling execution error: {}", e.getMessage(), e);
        }
    }

    /**
     * Cleanup execution resources.
     */
    private void cleanupExecutionResources(WorkflowExecution execution) {
        try {
            // Persist final state
            persistenceService.recordWorkflowCompletion(execution);

            logger.info("🧹 Cleaned up execution resources: {}", execution.getRunId());
        } catch (Exception e) {
            logger.warn("⚠️ Error cleaning up execution resources: {}", e.getMessage());
        }
    }

    /**
     * Resolve trigger items from datasource.
     */
    private List<Map<String, Object>> resolveTriggerItems(WorkflowPlan plan, String tenantId) {
        List<Map<String, Object>> triggerItems = new ArrayList<>();

        // Get the first trigger from the plan
        Trigger trigger = null;
        if (plan.getTriggers() != null && !plan.getTriggers().isEmpty()) {
            trigger = plan.getTriggers().get(0);
        }

        // Fetch real data from datasource
        if (trigger != null && "datasource".equalsIgnoreCase(trigger.type())) {
            if (triggerResolverService != null) {
                try {
                    logger.info("📦 [V2] Fetching datasource data: trigger={}, tenantId={}", trigger.label(), tenantId);

                    // Paginate through all items
                    int offset = 0;
                    int batchSize = config != null ? config.getTriggerBatchSize() : 100;
                    int maxItems = config != null ? config.getMaxDatasourceItems() : 10000;
                    boolean hasMore = true;

                    while (hasMore && triggerItems.size() < maxItems) {
                        TriggerBatchResult batchResult = triggerResolverService.resolveTriggerBatch(trigger, tenantId, offset, batchSize);

                        if (batchResult == null || batchResult.items() == null || batchResult.items().isEmpty()) {
                            break;
                        }

                        triggerItems.addAll(batchResult.items());
                        hasMore = batchResult.hasMore();
                        offset = batchResult.nextOffset();

                        logger.debug("📦 [V2] Loaded batch: offset={}, items={}, hasMore={}", offset, batchResult.items().size(), hasMore);
                    }

                    logger.info("✅ [V2] Loaded {} total items from datasource", triggerItems.size());
                } catch (Exception e) {
                    logger.error("❌ [V2] Failed to fetch datasource data: {}", e.getMessage(), e);
                }
            }
        }

        return triggerItems;
    }

    /**
     * Calculate execution time in milliseconds.
     * Returns 0 if endTime is not set.
     */
    private long calculateExecutionTime(WorkflowExecution execution) {
        return execution.getEndTime() != null ?
            execution.getEndTime().toEpochMilli() - execution.getStartTime().toEpochMilli() : 0;
    }

    /**
     * Snapshot interfaces for a workflow run by delegating to interface-service.
     * Extracts interface IDs and mappings from the plan, then creates snapshots remotely.
     */
    /**
     * Snapshot interfaces for a workflow run. Public so agent execution path can reuse it.
     */
    public void snapshotInterfacesForRun(WorkflowPlan plan, UUID workflowRunId) {
        Set<UUID> interfaceIds = interfacePlanExtractor.extractInterfaceIds(plan);
        if (interfaceIds.isEmpty()) return;

        Map<UUID, Map<String, String>> variableMappings = interfacePlanExtractor.extractMappingsFromPlan(plan);
        Map<UUID, Map<String, String>> actionMappings = interfacePlanExtractor.extractActionMappingsFromPlan(plan);

        int created = 0;
        for (UUID ifaceId : interfaceIds) {
            try {
                var request = new com.apimarketplace.interfaces.client.dto.SnapshotCreateRequest();
                request.setInterfaceId(ifaceId);
                request.setWorkflowRunId(workflowRunId);
                request.setVariableMappings(variableMappings.getOrDefault(ifaceId, Map.of()));
                request.setActionMappings(actionMappings.getOrDefault(ifaceId, Map.of()));

                var snapshot = interfaceClient.createSnapshot(request, plan.getTenantId());
                if (snapshot != null) created++;
            } catch (Exception e) {
                logger.warn("Failed to create snapshot for interface {}: {}", ifaceId, e.getMessage());
            }
        }

        logger.info("Created {} interface snapshot(s) for workflow run {}", created, workflowRunId);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RunScopedCache Implementation (no-op since cache removed)
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public void cleanupRun(String runId) {
        // No-op: in-memory cache removed
    }

    @Override
    public String getCacheName() {
        return "ActiveExecutionsCache";
    }

    @Override
    public CacheDomain getDomain() {
        return CacheDomain.EXECUTION;
    }

    @Override
    public int getCacheSize() {
        return 0;
    }
}
