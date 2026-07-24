package com.apimarketplace.orchestrator.services;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowPlanVersionEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.WorkflowPlanVersionRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.persistence.*;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service centralizing workflow execution and step persistence.
 *
 * <p>This service acts as a facade, delegating to specialized services:
 * <ul>
 *   <li>{@link WorkflowRunPersistenceService} - workflow lifecycle (start, completion)</li>
 *   <li>{@link DecisionPersistenceService} - decision node evaluation persistence</li>
 *   <li>{@link ScheduleSyncService} - scheduled execution synchronization</li>
 *   <li>{@link SkippedNodePersistenceService} - skipped node persistence</li>
 *   <li>{@link StepDataPersistenceService} - step entity building and persistence</li>
 *   <li>{@link WorkflowEntityResolverService} - entity resolution</li>
 *   <li>DB-level deduplication via ON CONFLICT DO NOTHING</li>
 * </ul>
 */
@Service
public class WorkflowPersistenceService {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowPersistenceService.class);

    private final WorkflowRepository workflowRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowStepDataRepository stepDataRepository;

    // Delegated services
    private final ScheduleSyncService scheduleSyncService;
    private final DecisionPersistenceService decisionPersistenceService;
    private final SkippedNodePersistenceService skippedNodePersistenceService;
    private final StepDataPersistenceService stepDataPersistenceService;
    private final WorkflowEntityResolverService entityResolverService;
    private final WorkflowRunPersistenceService runPersistenceService;
    private final WorkflowPlanVersionService versionService;
    private final WorkflowPlanVersionRepository planVersionRepository;
    private final StateSnapshotService stateSnapshotService;

    public WorkflowPersistenceService(
            WorkflowRepository workflowRepository,
            WorkflowRunRepository workflowRunRepository,
            WorkflowStepDataRepository stepDataRepository,
            ScheduleSyncService scheduleSyncService,
            DecisionPersistenceService decisionPersistenceService,
            SkippedNodePersistenceService skippedNodePersistenceService,
            StepDataPersistenceService stepDataPersistenceService,
            WorkflowEntityResolverService entityResolverService,
            WorkflowRunPersistenceService runPersistenceService,
            WorkflowPlanVersionService versionService,
            WorkflowPlanVersionRepository planVersionRepository,
            StateSnapshotService stateSnapshotService) {
        this.workflowRepository = workflowRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.stepDataRepository = stepDataRepository;
        this.scheduleSyncService = scheduleSyncService;
        this.decisionPersistenceService = decisionPersistenceService;
        this.skippedNodePersistenceService = skippedNodePersistenceService;
        this.stepDataPersistenceService = stepDataPersistenceService;
        this.entityResolverService = entityResolverService;
        this.runPersistenceService = runPersistenceService;
        this.versionService = versionService;
        this.planVersionRepository = planVersionRepository;
        this.stateSnapshotService = stateSnapshotService;
    }

    /**
     * Persists workflow start information.
     */
    @Transactional
    public void recordWorkflowStart(WorkflowExecution execution) {
        entityResolverService.resolveWorkflowEntity(execution).ifPresent(entity -> {
            try {
                Instant now = Instant.now();
                boolean hasReusable = hasReusableTrigger(execution.getPlan());

                // Auto-archive the execution plan so the run references the real version
                autoArchiveExecutionPlan(entity.getId(), execution.getPlan(), entity.getTenantId());

                WorkflowRunEntity runEntity = createRunEntity(entity, execution, now, hasReusable);
                WorkflowRunEntity savedRun = workflowRunRepository.save(runEntity);

                execution.setWorkflowRunId(savedRun.getId());
                entityResolverService.cacheIds(execution.getRunId(), entity.getId(), savedRun.getId());

                // Initialize StateSnapshot with ready triggers so batch-update WS events
                // include correct readyStepIds from the start (same pattern as V2StepByStepService).
                // Without this, auto-mode batch-updates send readyStepIds=[] which clears
                // the frontend readySteps that were correctly loaded from the API.
                initializeSnapshotWithReadyTriggers(execution, hasReusable);

                updateWorkflowEntity(entity, now);

                if (scheduleSyncService.hasScheduleTrigger(execution.getPlan())) {
                    scheduleSyncService.syncFromPinnedVersion(entity);
                }
            } catch (Exception e) {
                logger.warn("Unable to persist workflow start for run {}: {}", execution.getRunId(), e.getMessage(), e);
            }
        });
    }

    /**
     * Updates run status to RUNNING when execution actually starts.
     * This is needed because reusable trigger workflows start with WAITING_TRIGGER status.
     */
    public void updateRunStatusToRunning(WorkflowExecution execution) {
        if (execution == null || execution.getRunId() == null) {
            return;
        }

        try {
            workflowRunRepository.findByRunIdPublic(execution.getRunId()).ifPresent(runEntity -> {
                if (runEntity.getStatus() == RunStatus.WAITING_TRIGGER) {
                    runEntity.setStatus(RunStatus.RUNNING);
                    runEntity.setUpdatedAt(Instant.now());
                    workflowRunRepository.save(runEntity);
                    logger.info("✅ Updated run status to RUNNING for runId: {}", execution.getRunId());
                }
            });
        } catch (Exception e) {
            logger.warn("Unable to update run status to RUNNING for {}: {}", execution.getRunId(), e.getMessage());
        }
    }

    /**
     * Persists workflow completion.
     */
    @Transactional
    public void recordWorkflowCompletion(WorkflowExecution execution) {
        entityResolverService.resolveWorkflowRunId(execution).ifPresent(runId -> {
            try {
                workflowRunRepository.findById(runId).ifPresent(runEntity -> {
                    Instant now = Instant.now();
                    runEntity.setStatus(execution.getStatus() != null ? execution.getStatus() : RunStatus.FAILED);
                    runEntity.setEndedAt(now);
                    // duration_ms is NOT set here - per-epoch durations are stored in
                    // workflow_epochs (started_at/closed_at/duration_ms per header row),
                    // and the cumulative total is accumulated in StateSnapshot.totalDurationMs.
                    runEntity.setUpdatedAt(now);
                    workflowRunRepository.save(runEntity);
                });
            } catch (Exception e) {
                logger.warn("Unable to persist workflow run completion for {}: {}", execution.getRunId(), e.getMessage(), e);
            }
        });

        entityResolverService.resolveWorkflowEntity(execution).ifPresent(entity -> {
            try {
                Instant now = Instant.now();
                entity.setLastExecutedAt(now);
                entity.setUpdatedAt(now);
                workflowRepository.save(entity);
            } catch (Exception e) {
                logger.warn("Unable to update workflow {} after run {}: {}", entity.getId(), execution.getRunId(), e.getMessage(), e);
            }
        });

        cleanupRunCaches(execution.getRunId());
    }

    /**
     * Restores execution state from persisted data.
     */
    @SuppressWarnings("unchecked")
    public void restoreExecutionState(WorkflowExecution execution) {
        if (execution == null) return;
        try {
            stepDataRepository.findTopByRunIdOrderByIdDesc(execution.getRunId())
                    .map(WorkflowStepDataEntity::getMetadata)
                    .filter(metadata -> metadata != null && metadata.containsKey("mergeStates"))
                    .ifPresent(metadata -> {
                        Object snapshot = metadata.get("mergeStates");
                        if (snapshot instanceof Map<?, ?> mapSnapshot) {
                            execution.restoreMergeStates((Map<String, ?>) mapSnapshot);
                        }
                    });
        } catch (Exception e) {
            logger.warn("Unable to restore merge state for run {}: {}", execution.getRunId(), e.getMessage());
        }
    }

    /**
     * Records a step execution result - delegates to StepDataPersistenceService.
     *
     * @return StepPersistenceResult with success status and storage ID
     */
    public StepPersistenceResult recordStep(WorkflowExecution execution, String stepId, String stepAliasOrId,
                              String graphNodeId, StepExecutionResult result) {
        return stepDataPersistenceService.recordStep(execution, stepId, stepAliasOrId, graphNodeId, result);
    }

    /**
     * Records a step execution result with explicit epoch - delegates to StepDataPersistenceService.
     */
    public StepPersistenceResult recordStep(WorkflowExecution execution, String stepId, String stepAliasOrId,
                              String graphNodeId, StepExecutionResult result, int explicitEpoch) {
        return stepDataPersistenceService.recordStep(execution, stepId, stepAliasOrId, graphNodeId, result, explicitEpoch);
    }

    /**
     * Records a step execution result with explicit epoch AND triggerId. Closes
     * the CRITICAL 2 drift (2026-05-21 e2e audit) where every COMPLETED row
     * landed under {@code "trigger:default"} because the trigger ID was never
     * threaded from the StepCompletionOrchestrator call site to the entity
     * builder.
     */
    public StepPersistenceResult recordStep(WorkflowExecution execution, String stepId, String stepAliasOrId,
                              String graphNodeId, StepExecutionResult result, int explicitEpoch, String triggerId) {
        return stepDataPersistenceService.recordStep(execution, stepId, stepAliasOrId, graphNodeId, result, explicitEpoch, triggerId);
    }

    /**
     * Records a skipped node - delegates to SkippedNodePersistenceService.
     */
    public boolean recordSkippedNode(WorkflowExecution execution, String nodeId, String nodeLabel,
                                     String skipReason, String skipSourceNode, int itemIndex) {
        return skippedNodePersistenceService.recordSkippedNode(
                execution, nodeId, nodeLabel, skipReason, skipSourceNode, itemIndex);
    }

    /**
     * Records a skipped node with explicit epoch - delegates to SkippedNodePersistenceService.
     */
    public boolean recordSkippedNode(WorkflowExecution execution, String nodeId, String nodeLabel,
                                     String skipReason, String skipSourceNode, int itemIndex, int explicitEpoch) {
        return skippedNodePersistenceService.recordSkippedNode(
                execution, nodeId, nodeLabel, skipReason, skipSourceNode, itemIndex, explicitEpoch);
    }

    /**
     * Records a skipped node with explicit epoch and triggerId.
     */
    public boolean recordSkippedNode(WorkflowExecution execution, String nodeId, String nodeLabel,
                                     String skipReason, String skipSourceNode, int itemIndex,
                                     int explicitEpoch, String triggerId) {
        return skippedNodePersistenceService.recordSkippedNode(
                execution, nodeId, nodeLabel, skipReason, skipSourceNode, itemIndex, explicitEpoch, triggerId);
    }

    /**
     * Records a skipped node with explicit iteration, epoch and triggerId.
     */
    public boolean recordSkippedNode(WorkflowExecution execution, String nodeId, String nodeLabel,
                                     String skipReason, String skipSourceNode, int itemIndex,
                                     int iteration, int explicitEpoch, String triggerId) {
        return skippedNodePersistenceService.recordSkippedNode(
                execution, nodeId, nodeLabel, skipReason, skipSourceNode, itemIndex, iteration, explicitEpoch, triggerId);
    }

    /**
     * Records a decision evaluation - delegates to DecisionPersistenceService.
     */
    public void recordDecisionEvaluation(WorkflowExecution execution, DecisionEvaluationInfo evaluation) {
        recordDecisionEvaluation(execution, evaluation, 0);
    }

    /**
     * Records a decision evaluation with explicit epoch - delegates to DecisionPersistenceService.
     */
    public void recordDecisionEvaluation(WorkflowExecution execution, DecisionEvaluationInfo evaluation, int explicitEpoch) {
        UUID workflowRunId = entityResolverService.resolveWorkflowRunId(execution).orElse(null);
        if (workflowRunId == null) return;

        int currentEpoch = (explicitEpoch > 0) ? explicitEpoch : entityResolverService.getCurrentEpochFromRun(workflowRunId);
        String organizationId = com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationId();
        if (organizationId == null || organizationId.isBlank()) {
            organizationId = entityResolverService.getOrganizationIdFromRun(workflowRunId).orElse(null);
        }

        DecisionPersistenceService.DecisionPersistenceContext context =
                new DecisionPersistenceService.DecisionPersistenceContext(
                        execution.getRunId(), workflowRunId, execution.getPlan().getId(),
                        execution.getPlan().getTenantId(), currentEpoch, organizationId);

        decisionPersistenceService.recordDecisionEvaluation(context, evaluation);
    }

    /**
     * Clears deduplication caches for a workflow run (for rerun support).
     * No-op: deduplication is now handled at DB level via ON CONFLICT DO NOTHING.
     */
    public void clearDeduplicationCaches(String runId) {
        // No-op: DB-level dedup via unique index, no in-memory cache to clear
    }

    // ==================== Private Helper Methods ====================

    /**
     * Ensures the version history reflects the plan that is actually executed.
     * Execution never mints a version number: when the execution plan drifted from
     * the latest version's stored content, that content is overwritten in place
     * (same number) - only explicit save paths advance the history.
     *
     * <p>On a PINNED workflow, a plan matching the pinned version resolves to the
     * pinned number read-only instead: this method used to hand the pinned plan to
     * the resolver as if it were drifted canvas content, which overwrote a newer
     * draft in place and destroyed the user's work in progress.
     */
    private void autoArchiveExecutionPlan(UUID workflowId, WorkflowPlan executionPlan, String tenantId) {
        try {
            Map<String, Object> executionPlanMap = executionPlan.getOriginalPlan();
            if (executionPlanMap == null || executionPlanMap.isEmpty()) {
                return;
            }

            int newVersion = versionService.resolveContentVersionForExecution(workflowId, executionPlanMap, tenantId);
            logger.info("Ensured execution plan version {} for workflow {}", newVersion, workflowId);
        } catch (Exception e) {
            logger.warn("Unable to create execution plan version for workflow {}: {}", workflowId, e.getMessage());
        }
    }

    private WorkflowRunEntity createRunEntity(WorkflowEntity entity, WorkflowExecution execution,
                                               Instant now, boolean hasReusable) {
        // Delegate to WorkflowRunPersistenceService - single source of truth for run creation
        return runPersistenceService.buildRunEntity(entity, execution, hasReusable, now);
    }

    private void updateWorkflowEntity(WorkflowEntity entity, Instant now) {
        entity.setLastExecutedAt(now);
        entity.setUpdatedAt(now);
        workflowRepository.save(entity);
    }

    private void cleanupRunCaches(String runId) {
        entityResolverService.cleanup(runId);
    }

    private boolean hasReusableTrigger(WorkflowPlan plan) {
        return plan != null && plan.getTriggers() != null &&
                plan.getTriggers().stream().anyMatch(t -> TriggerType.isReusableTriggerType(t.type()));
    }

    /**
     * Initializes StateSnapshot with ready trigger IDs for reusable-trigger workflows.
     *
     * <p>This mirrors what V2StepByStepService does at SBS initialization:
     * {@code stateSnapshotService.initializeSnapshot(runId)} +
     * {@code stateSnapshotService.updateReadyNodes(runId, readyNodes)}.
     *
     * <p>Without this, the StateSnapshot has empty readyNodeIds and WS batch-updates
     * send {@code readyStepIds: []} to the frontend, which overwrites the correctly
     * API-loaded readySteps.
     */
    private void initializeSnapshotWithReadyTriggers(WorkflowExecution execution, boolean hasReusable) {
        if (!hasReusable) return;

        String runId = execution.getRunId();
        stateSnapshotService.initializeSnapshot(runId);

        Set<String> readyTriggers = execution.getPlan().getTriggers().stream()
                .filter(t -> TriggerType.isReusableTriggerType(t.type()))
                .map(Trigger::getNormalizedKey)
                .collect(Collectors.toSet());

        if (!readyTriggers.isEmpty()) {
            stateSnapshotService.updateReadyNodes(runId, readyTriggers);
            logger.info("Initialized StateSnapshot with ready triggers {} for runId={}", readyTriggers, runId);
        }
    }

}
