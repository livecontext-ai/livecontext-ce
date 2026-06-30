package com.apimarketplace.orchestrator.execution.v2;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.*;
import com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.merge.MergeIntegrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * V2 Workflow Execution Service.
 *
 * This is the NEW unified execution engine that replaces:
 * - ItemizedWorkflowOrchestrator (1225 lines) → DELETED
 * - LoopExecutionCoordinator (500+ lines) → DELETED
 * - MergeExecutor (150 lines) → DELETED
 * - StepEventBus (66 lines) → DELETED
 * - And many other fragmented services → DELETED
 *
 * Total legacy code deleted: ~5000 lines
 * New code: ~2500 lines
 * Reduction: ~50%
 *
 * Key Benefits:
 * - Single execution algorithm (tree traversal)
 * - No duplicate merge successor execution bug!
 * - Immutable state (ExecutionContext)
 * - Reactive (CompletableFuture, no blocking)
 * - Simple, testable, maintainable
 */
@Service
public class WorkflowExecutionServiceV2 {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowExecutionServiceV2.class);

    private final UnifiedExecutionEngine executionEngine;
    private final ExecutionTreeBuilder treeBuilder;
    private final V2ExecutionEventService eventService;
    private final MergeIntegrationService mergeIntegrationService;
    private final WorkflowRunRepository workflowRunRepository;

    public WorkflowExecutionServiceV2(
            UnifiedExecutionEngine executionEngine,
            ExecutionTreeBuilder treeBuilder,
            V2ExecutionEventService eventService,
            MergeIntegrationService mergeIntegrationService,
            WorkflowRunRepository workflowRunRepository) {
        this.executionEngine = executionEngine;
        this.treeBuilder = treeBuilder;
        this.eventService = eventService;
        this.mergeIntegrationService = mergeIntegrationService;
        this.workflowRunRepository = workflowRunRepository;
    }

    /**
     * Executes a workflow for multiple trigger items.
     * Backward-compatible overload that uses the first trigger.
     *
     * @param plan Workflow plan
     * @param triggerItems List of trigger items
     * @param tenantId Tenant ID
     * @param execution Workflow execution context
     * @return CompletableFuture with workflow result
     */
    public CompletableFuture<WorkflowResult> executeWorkflow(
            WorkflowPlan plan,
            List<Map<String, Object>> triggerItems,
            String tenantId,
            WorkflowExecution execution) {
        // Use first trigger for backward compatibility
        String triggerId = (plan.getTriggers() != null && !plan.getTriggers().isEmpty())
            ? plan.getTriggers().get(0).getNormalizedKey()
            : "trigger:default";
        return executeWorkflow(plan, triggerItems, tenantId, execution, triggerId);
    }

    /**
     * Executes a workflow for multiple trigger items with explicit trigger selection.
     *
     * @param plan Workflow plan
     * @param triggerItems List of trigger items
     * @param tenantId Tenant ID
     * @param execution Workflow execution context
     * @param triggerId The specific trigger ID to use (e.g., "trigger:my_webhook")
     * @return CompletableFuture with workflow result
     */
    public CompletableFuture<WorkflowResult> executeWorkflow(
            WorkflowPlan plan,
            List<Map<String, Object>> triggerItems,
            String tenantId,
            WorkflowExecution execution,
            String triggerId) {

        String runId = execution.getRunId();
        String workflowRunId = execution.getWorkflowRunId().toString();

        logger.info("🚀 Starting workflow execution V2: runId={}, triggerId={}, items={}, tenantId={}",
            runId, triggerId, triggerItems.size(), tenantId);

        try {
            // 1. Initialize event streaming
            eventService.initializeExecution(execution);

            // 2. Build execution tree from plan
            // PR15 - thread the workspace identity (organization_id /
            // organization_role) from WorkflowRunEntity onto the tree so
            // nodes downstream read the active org from ExecutionContext
            // rather than re-reading the legacy metadata['__orgId__'] stash.
            //
            // Uses getOrgId()/getOrgRole() (column-first with metadata
            // fallback) so in-flight pre-V209 runs where the column is null
            // but the legacy metadata['__orgId__'] stash exists are still
            // routed to org scope. The bare-column getter would miss them.
            String orgId = null;
            String orgRole = null;
            try {
                WorkflowRunEntity runEntity = workflowRunRepository.findByRunIdPublic(runId).orElse(null);
                if (runEntity != null) {
                    orgId = runEntity.getOrgId();
                    orgRole = runEntity.getOrgRole();
                }
            } catch (Exception e) {
                // DB error path - log ERROR-level so ops sees it (silent
                // demote of an org-context run to personal scope is the
                // pre-condition for credential / storage cross-scope leak
                // once PR19/PR18 runtime consumers come online). Run still
                // executes (foundation PR - no enforcement here yet) but
                // the audit trail captures the demote for triage.
                logger.error("[PR15] Failed to load org context for run {} - degrading to "
                        + "personal scope. This is a cross-scope-leak precondition once "
                        + "PR19/PR18 runtime consumers read context.organizationId(): {}",
                        runId, e.getMessage(), e);
            }
            ExecutionTree tree = treeBuilder.build(runId, workflowRunId, tenantId, plan, orgId, orgRole);

            // 3. Initialize merge tracking for this workflow
            mergeIntegrationService.initializeForWorkflow(runId, plan);

            // 4. Convert trigger items to TriggerItem objects using the specified triggerId
            List<TriggerItem> items = new ArrayList<>();
            for (int i = 0; i < triggerItems.size(); i++) {
                Map<String, Object> itemData = triggerItems.get(i);
                String itemId = triggerId + "-" + i;
                items.add(TriggerItem.create(itemId, i, itemData));
            }

            // 5. Execute workflow via unified engine
            // Note: V2WorkflowFinalizer handles emitWorkflowComplete and cleanup
            return executionEngine.executeWorkflow(tree, items, execution, eventService)
                .thenApply(result -> {
                    logger.info("✅ Workflow execution completed V2: runId={}, triggerId={}, success={}, failed={}",
                        runId, triggerId, result.successItems(), result.failedItems());
                    return result;
                })
                .exceptionally(throwable -> {
                    logger.error("❌ Workflow execution failed V2: runId={}, triggerId={}, error={}",
                        runId, triggerId, throwable.getMessage(), throwable);
                    return WorkflowResult.failed(runId, throwable.getMessage());
                });

        } catch (Exception e) {
            logger.error("❌ Failed to start workflow execution V2: runId={}, triggerId={}, error={}",
                runId, triggerId, e.getMessage(), e);

            // Emit failure event and cleanup (tree building failed, finalizer won't run)
            eventService.emitWorkflowComplete(execution, false, e.getMessage());
            eventService.cleanupExecution(runId);

            return CompletableFuture.completedFuture(
                WorkflowResult.failed(runId, e.getMessage())
            );
        }
    }

}
