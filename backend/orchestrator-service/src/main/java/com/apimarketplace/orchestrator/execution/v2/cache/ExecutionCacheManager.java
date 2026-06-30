package com.apimarketplace.orchestrator.execution.v2.cache;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTree;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTreeBuilder;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.resume.ExecutionContextManager;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.services.resume.WorkflowRunState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Manages ExecutionTree and WorkflowExecution loading.
 *
 * Single Responsibility: Loading execution state for a run.
 *
 * Use {@link #loadTreeAndExecution(String)} when both tree and execution are needed -
 * shares a single reconstructState call between the two derivations.
 */
@Component
public class ExecutionCacheManager {

    /**
     * Result of loading both tree and execution from DB in a single pass.
     * Avoids redundant DB queries when both are needed (e.g., step-by-step execution).
     */
    public record LoadedExecution(ExecutionTree tree, WorkflowExecution execution) {}

    private static final Logger logger = LoggerFactory.getLogger(ExecutionCacheManager.class);

    private final WorkflowResumeService resumeService;
    private final ExecutionContextManager executionContextManager;
    private final ExecutionTreeBuilder treeBuilder;
    private final WorkflowRunRepository runRepository;

    public ExecutionCacheManager(
            @Lazy WorkflowResumeService resumeService,
            @Lazy ExecutionContextManager executionContextManager,
            ExecutionTreeBuilder treeBuilder,
            WorkflowRunRepository runRepository) {
        this.resumeService = resumeService;
        this.executionContextManager = executionContextManager;
        this.treeBuilder = treeBuilder;
        this.runRepository = runRepository;
    }

    // ==================== Tree Operations ====================

    /**
     * Get execution tree for a run.
     */
    public ExecutionTree getTree(String runId) {
        logger.debug("[ExecutionCache] getTree: runId={}", runId);
        return loadTreeFromDatabase(runId).orElse(null);
    }

    /**
     * Check if a tree can be loaded for the run.
     */
    public boolean hasTree(String runId) {
        // Tree is derivable from plan; existence of execution implies a tree can be built.
        return getExecution(runId) != null;
    }

    // ==================== Execution Operations ====================

    /**
     * Get workflow execution for a run.
     */
    public WorkflowExecution getExecution(String runId) {
        logger.debug("[ExecutionCache] getExecution: runId={}", runId);
        return loadExecutionFromDatabase(runId).orElse(null);
    }

    /**
     * Check if an execution exists for the run.
     */
    public boolean hasExecution(String runId) {
        return resumeService.reconstructState(runId) != null;
    }

    // ==================== Combined Operations ====================

    /**
     * Ensure execution exists for a run.
     * This is the main entry point for step-by-step initialization recovery.
     *
     * @throws IllegalStateException if run not found in database
     */
    public void ensureLoaded(String runId) {
        logger.info("[ExecutionCache] Ensuring execution is available: runId={}", runId);

        // Load execution (needed for tree building)
        WorkflowExecution execution = getExecution(runId);
        if (execution == null) {
            throw new IllegalStateException("Workflow execution not found: " + runId);
        }

        // Verify plan exists
        WorkflowPlan plan = execution.getPlan();
        if (plan == null) {
            throw new IllegalStateException("No workflow plan found for runId: " + runId);
        }
    }

    // ==================== Single-Pass Loading ====================

    /**
     * Load both execution tree and workflow execution, sharing a single reconstructState
     * between the two derivations (one call instead of two).
     *
     * Use in hot paths like step-by-step executeNode() where both tree and execution are needed.
     *
     * @return LoadedExecution with tree and execution, or null if not found
     */
    public LoadedExecution loadTreeAndExecution(String runId) {
        logger.debug("[ExecutionCache] loadTreeAndExecution: runId={}", runId);

        // 1. Single reconstructState call
        WorkflowExecution execution = loadExecutionFromDatabase(runId).orElse(null);
        if (execution == null) {
            logger.warn("[ExecutionCache] No execution found: runId={}", runId);
            return null;
        }

        WorkflowPlan plan = execution.getPlan();
        if (plan == null) {
            logger.warn("[ExecutionCache] No plan found for execution: runId={}", runId);
            return null;
        }

        // 2. Build tree from already-loaded execution (no extra reconstructState)
        try {
            String workflowRunId = execution.getWorkflowRunId() != null
                ? execution.getWorkflowRunId().toString()
                : runId;

            Optional<WorkflowRunEntity> runEntityOpt = runRepository.findByRunIdPublic(runId);
            if (runEntityOpt == null) {
                runEntityOpt = Optional.empty();
            }
            ExecutionTree tree = treeBuilder.build(
                runId,
                workflowRunId,
                plan.getTenantId(),
                plan,
                runEntityOpt.map(WorkflowRunEntity::getOrgId).orElse(null),
                runEntityOpt.map(WorkflowRunEntity::getOrgRole).orElse(null));

            ExecutionMode executionMode = runEntityOpt
                    .map(WorkflowRunEntity::getExecutionMode)
                    .orElse(ExecutionMode.AUTOMATIC);
            tree = tree.withExecutionMode(executionMode);

            logger.debug("[ExecutionCache] loadTreeAndExecution OK: runId={}, nodes={}, mode={}",
                runId, plan.getMcps().size() + plan.getCores().size(), executionMode);

            return new LoadedExecution(tree, execution);
        } catch (Exception e) {
            logger.error("[ExecutionCache] loadTreeAndExecution failed: runId={}", runId, e);
            return null;
        }
    }

    // ==================== Private Loading Methods ====================

    private Optional<ExecutionTree> loadTreeFromDatabase(String runId) {
        try {
            WorkflowExecution execution = getExecution(runId);
            if (execution == null) {
                return Optional.empty();
            }

            WorkflowPlan plan = execution.getPlan();
            if (plan == null) {
                logger.warn("[ExecutionCache] No plan found for execution: runId={}", runId);
                return Optional.empty();
            }

            String workflowRunId = execution.getWorkflowRunId() != null
                ? execution.getWorkflowRunId().toString()
                : runId;

            Optional<WorkflowRunEntity> runEntityOpt = runRepository.findByRunIdPublic(runId);
            if (runEntityOpt == null) {
                runEntityOpt = Optional.empty();
            }
            ExecutionTree tree = treeBuilder.build(
                runId,
                workflowRunId,
                plan.getTenantId(),
                plan,
                runEntityOpt.map(WorkflowRunEntity::getOrgId).orElse(null),
                runEntityOpt.map(WorkflowRunEntity::getOrgRole).orElse(null));

            ExecutionMode executionMode = runEntityOpt
                    .map(WorkflowRunEntity::getExecutionMode)
                    .orElse(ExecutionMode.AUTOMATIC);
            tree = tree.withExecutionMode(executionMode);

            logger.debug("[ExecutionCache] loadTreeFromDatabase OK: runId={}, nodes={}, mode={}",
                runId, plan.getMcps().size() + plan.getCores().size(), executionMode);

            return Optional.of(tree);
        } catch (Exception e) {
            logger.error("[ExecutionCache] loadTreeFromDatabase failed: runId={}", runId, e);
            return Optional.empty();
        }
    }

    private Optional<WorkflowExecution> loadExecutionFromDatabase(String runId) {
        try {
            WorkflowRunState state = resumeService.reconstructState(runId);
            if (state == null) {
                logger.warn("[ExecutionCache] No state found: runId={}", runId);
                return Optional.empty();
            }

            WorkflowExecution execution = executionContextManager.rebuildExecutionContext(runId, state);
            if (execution != null) {
                logger.debug("[ExecutionCache] loadExecutionFromDatabase OK: runId={}", runId);
            }

            return Optional.ofNullable(execution);
        } catch (Exception e) {
            logger.error("[ExecutionCache] loadExecutionFromDatabase failed: runId={}", runId, e);
            return Optional.empty();
        }
    }

}
