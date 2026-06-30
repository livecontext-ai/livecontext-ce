package com.apimarketplace.orchestrator.services.resume;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.completion.StepCompletionOrchestrator;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.state.RunStateStore;
import com.apimarketplace.orchestrator.services.resume.cache.WorkflowCacheManager;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import com.apimarketplace.orchestrator.execution.v2.services.V2StepByStepService;
import com.apimarketplace.orchestrator.execution.v2.engine.StepByStepExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/**
 * Handles step-by-step execution mode for workflows.
 *
 * Extracted from WorkflowResumeService for Single Responsibility Principle.
 * Responsibilities:
 * - Start workflow in step-by-step mode
 * - Execute single steps (with and without auto-propagation)
 * - Manage execution mode settings
 * - Cleanup step-by-step state
 *
 * @see WorkflowResumeService
 */
@Service
public class StepByStepExecutor {

    private static final Logger logger = LoggerFactory.getLogger(StepByStepExecutor.class);

    private final WorkflowRunRepository runRepository;
    private final WorkflowExecutionService executionService;
    private final WorkflowStreamingService streamingService;
    private final StepCompletionOrchestrator stepCompletionOrchestrator;
    private final RunStateStore runStateStore;
    private final WorkflowCacheManager cacheManager;
    private final ExecutionContextManager contextManager;
    private final StateSnapshotService stateSnapshotService;

    @Autowired(required = false)
    private V2StepByStepService v2StepByStepService;

    // Supplier for state reconstruction (injected to avoid circular dependency)
    private Supplier<WorkflowRunState> stateReconstructorSupplier;

    public StepByStepExecutor(
            WorkflowRunRepository runRepository,
            WorkflowExecutionService executionService,
            WorkflowStreamingService streamingService,
            StepCompletionOrchestrator stepCompletionOrchestrator,
            RunStateStore runStateStore,
            WorkflowCacheManager cacheManager,
            ExecutionContextManager contextManager,
            StateSnapshotService stateSnapshotService) {
        this.runRepository = runRepository;
        this.executionService = executionService;
        this.streamingService = streamingService;
        this.stepCompletionOrchestrator = stepCompletionOrchestrator;
        this.runStateStore = runStateStore;
        this.cacheManager = cacheManager;
        this.contextManager = contextManager;
        this.stateSnapshotService = stateSnapshotService;
    }

    /**
     * Sets the state reconstructor supplier (called by WorkflowResumeService after construction).
     */
    public void setStateReconstructorSupplier(Supplier<WorkflowRunState> supplier) {
        this.stateReconstructorSupplier = supplier;
    }

    /**
     * Reconstructs state using the injected supplier.
     */
    private WorkflowRunState reconstructState(String runId) {
        if (stateReconstructorSupplier == null) {
            throw new IllegalStateException("StateReconstructorSupplier not set");
        }
        return stateReconstructorSupplier.get();
    }

    /**
     * Starts a workflow in step-by-step mode.
     * The workflow will pause after each node execution.
     *
     * If at least one trigger from any DAG is ready (multi-DAG aware), the run
     * status is set to WAITING_TRIGGER so the frontend activates polling for
     * external triggers (webhooks, schedules, etc.).  Otherwise, PAUSED.
     */
    @Transactional
    public WorkflowRunState startInStepByStepMode(String runId, Supplier<WorkflowRunState> stateSupplier) {
        logger.info("Starting workflow in step-by-step mode: {}", runId);

        runRepository.findByRunIdPublic(runId).ifPresent(entity -> {
            entity.setExecutionMode(ExecutionMode.STEP_BY_STEP);
            entity.setStatus(RunStatus.PAUSED);
            entity.setUpdatedAt(Instant.now());
            runRepository.save(entity);
        });

        // Initialize evaluated core nodes tracking (clear any previous state)
        cacheManager.clearEvaluatedCores(runId);

        WorkflowRunState state = stateSupplier.get();

        // Persist initial ready nodes (triggers) to StateSnapshot so that external triggers
        // (webhooks) can verify the trigger is READY when the run is WAITING_TRIGGER.
        Set<String> readySteps = state.readySteps();
        boolean triggerReady = hasTriggerInReadyNodes(readySteps);

        if (readySteps != null && !readySteps.isEmpty()) {
            stateSnapshotService.initializeSnapshot(runId);
            stateSnapshotService.updateReadyNodes(runId, readySteps);
            logger.info("Persisted {} initial ready nodes to StateSnapshot for runId={}: {}",
                readySteps.size(), runId, readySteps);

            // Multi-DAG: if at least one trigger is ready, use WAITING_TRIGGER
            // so the frontend starts polling for external triggers (webhooks, schedules).
            if (triggerReady) {
                runRepository.findByRunIdPublic(runId).ifPresent(entity -> {
                    entity.setStatus(RunStatus.WAITING_TRIGGER);
                    entity.setUpdatedAt(Instant.now());
                    runRepository.save(entity);
                    logger.info("Set status to WAITING_TRIGGER for runId={} (trigger(s) ready: {})",
                        runId, readySteps.stream().filter(s -> s.startsWith("trigger:")).toList());
                });
            }
        }

        // Initialize streaming so WebSocket clients receive initial workflow config and status.
        // Without this, the frontend never gets the workflow structure or initial state events.
        try {
            WorkflowExecution execution = contextManager.getOrCreateExecutionContext(runId, state);
            String statusStr = triggerReady ? RunStatus.WAITING_TRIGGER.getValue() : RunStatus.PAUSED.getValue();
            streamingService.sendWorkflowStatusEvent(execution, statusStr, "Step-by-step mode started");
            streamingService.sendWorkflowConfigurationEvent(execution);
            if (readySteps != null && !readySteps.isEmpty()) {
                streamingService.sendReadyStepsEvent(execution, readySteps);
            }
            logger.info("Initialized streaming for step-by-step mode: runId={}, status={}", runId, statusStr);
        } catch (Exception e) {
            logger.warn("Error initializing streaming for step-by-step mode runId={}: {}", runId, e.getMessage());
        }

        return state;
    }

    /**
     * Checks if the ready nodes contain at least one trigger node.
     * Used to determine whether the run should be WAITING_TRIGGER (for polling)
     * or PAUSED (for manual step-by-step execution).
     */
    public static boolean hasTriggerInReadyNodes(Set<String> readyNodes) {
        return readyNodes != null && readyNodes.stream().anyMatch(s -> s.startsWith("trigger:"));
    }

    /**
     * Reconciles the run status with the current set of ready nodes.
     *
     * In step-by-step mode the run toggles between WAITING_TRIGGER (at least one
     * trigger ready → frontend polls for external events) and PAUSED (no trigger
     * ready → frontend waits for manual user action).
     *
     * This is multi-DAG aware: after executing a step in DAG-1, a trigger from
     * DAG-2 may still be ready.  The snapshot provides the merged view.
     */
    private void reconcileRunStatusWithReadyNodes(String runId, Set<String> mergedReadyNodes,
                                                   WorkflowExecution execution) {
        boolean triggerReady = hasTriggerInReadyNodes(mergedReadyNodes);

        runRepository.findByRunIdPublic(runId).ifPresent(entity -> {
            if (!entity.getExecutionMode().isStepByStep()) return;

            RunStatus desired = triggerReady
                ? RunStatus.WAITING_TRIGGER
                : RunStatus.PAUSED;

            if (entity.getStatus() != desired) {
                logger.info("Reconciling run status for runId={}: {} → {} (triggerReady={})",
                    runId, entity.getStatus(), desired, triggerReady);
                entity.setStatus(desired);
                entity.setUpdatedAt(Instant.now());
                runRepository.save(entity);

                streamingService.sendWorkflowStatusEvent(execution, desired,
                    triggerReady ? "Trigger ready, waiting for external event"
                                : "Waiting for next step");
            }
        });
    }

    /**
     * Sets the execution mode for a workflow run.
     */
    @Transactional
    public void setExecutionMode(String runId, ExecutionMode mode) {
        logger.info("Setting execution mode for run {}: {}", runId, mode);

        runRepository.findByRunIdPublic(runId).ifPresent(entity -> {
            entity.setExecutionMode(mode);
            entity.setUpdatedAt(Instant.now());
            runRepository.save(entity);
        });
    }

    /**
     * Gets the current execution mode for a workflow run.
     */
    @Transactional(readOnly = true)
    public ExecutionMode getExecutionMode(String runId) {
        return runRepository.findByRunIdPublic(runId)
            .map(WorkflowRunEntity::getExecutionMode)
            .orElse(ExecutionMode.AUTOMATIC);
    }

    /**
     * Validates that workflow is in step-by-step mode.
     */
    public void validateStepByStepMode(String runId) {
        ExecutionMode mode = getExecutionMode(runId);
        if (!mode.isStepByStep()) {
            throw new IllegalStateException("Operation is only available in step-by-step mode");
        }
    }

    /**
     * Cleans up step-by-step mode state for a run.
     */
    public void cleanupStepByStepState(String runId) {
        cacheManager.clearAllCaches(runId);
    }

    /**
     * Gets the list of steps that are ready to execute.
     */
    @Transactional(readOnly = true)
    public Set<String> getReadySteps(String runId, Supplier<WorkflowRunState> stateSupplier) {
        WorkflowRunState state = stateSupplier.get();
        return state.readySteps();
    }

    /**
     * Executes a single step (used when paused, not step-by-step mode).
     */
    @Transactional
    public StepExecutionResult executeSingleStep(String runId, String stepId,
            Supplier<WorkflowRunState> stateSupplier,
            Supplier<WorkflowPlan> planRefresher) {
        logger.info("Executing single step: {} for run: {}", stepId, runId);

        // Reconstruct state
        WorkflowRunState state = stateSupplier.get();

        // Verify step can be executed
        if (!state.canExecuteStep(stepId) && !state.readySteps().contains(stepId)) {
            Set<String> missingDeps = contextManager.getMissingDependencies(stepId, state);
            throw new IllegalStateException(
                "Step " + stepId + " cannot be executed. Missing dependencies: " + missingDeps);
        }

        // Get or create execution context
        WorkflowExecution execution = contextManager.getOrCreateExecutionContext(runId, state);

        // Execute the step using V2 step-by-step service
        StepExecutionResult result;
        if (v2StepByStepService != null) {
            try {
                // Ensure V2 service is initialized for this run
                if (!v2StepByStepService.isInitialized(runId)) {
                    logger.info("V2 step-by-step not initialized for runId={}, initializing now...", runId);
                    v2StepByStepService.initializeStepByStep(execution, execution.getPlan());
                }

                // Delegate to V2 step-by-step service
                StepByStepExecutionResult v2Result = v2StepByStepService.executeNode(runId, stepId, "0");

                result = convertV2Result(stepId, v2Result);

                logger.info("V2 step-by-step executed: stepId={}, success={}, readyNodes={}",
                    stepId, v2Result.isSuccess(), v2Result.readyNodes());

                execution.setStepResult(stepId, result);
                streamingService.sendStepEventWithoutPersistence(execution, stepId, result);

                // Use merged ready nodes from snapshot (SINGLE SOURCE OF TRUTH) to avoid
                // overwriting other DAGs' ready nodes sent by V2StepByStepService
                Set<String> mergedReadyNodes = stateSnapshotService.getReadyNodeIds(runId);
                if (!mergedReadyNodes.isEmpty()) {
                    streamingService.sendReadyStepsEvent(execution, mergedReadyNodes);
                } else if (v2Result.readyNodes() != null && !v2Result.readyNodes().isEmpty()) {
                    streamingService.sendReadyStepsEvent(execution, v2Result.readyNodes());
                } else {
                    WorkflowRunState newState = stateSupplier.get();
                    streamingService.sendReadyStepsEvent(execution, newState.readySteps());
                }

                logger.info("Step {} executed with status: {}", stepId, result.status());
                return result;

            } catch (Exception e) {
                logger.error("V2 step-by-step execution failed for step {}: {}", stepId, e.getMessage(), e);
                result = StepExecutionResult.failure(stepId, "V2 execution failed: " + e.getMessage(), e, 0);
            }
        } else {
            logger.warn("V2StepByStepService not available, returning failure for step: {}", stepId);
            result = StepExecutionResult.failure(stepId,
                "Step-by-step execution service not available", null, 0);
        }

        // Fallback persistence
        execution.setStepResult(stepId, result);
        String stepLabel = LabelNormalizer.extractLabel(stepId);
        stepCompletionOrchestrator.completeStep(execution, stepId, stepLabel, result, 0, 0);

        WorkflowRunState newState = stateSupplier.get();
        streamingService.sendReadyStepsEvent(execution, newState.readySteps());

        logger.info("Step {} executed with status: {}", stepId, result.status());
        return result;
    }

    /**
     * Executes a single step in step-by-step mode.
     * Unlike the regular executeSingleStep, this does NOT auto-propagate to next steps.
     */
    @Transactional
    public StepExecutionResult executeSingleStepInStepByStepMode(String runId, String stepId,
            Supplier<WorkflowRunState> stateSupplier,
            Supplier<WorkflowPlan> planRefresher) {
        return executeSingleStepInStepByStepMode(runId, stepId, null, stateSupplier, planRefresher);
    }

    /**
     * Executes a single step in step-by-step mode with optional input data.
     */
    @Transactional
    public StepExecutionResult executeSingleStepInStepByStepMode(String runId, String stepId,
            Map<String, Object> inputData,
            Supplier<WorkflowRunState> stateSupplier,
            Supplier<WorkflowPlan> planRefresher) {
        logger.info("Executing single step (step-by-step mode): {} for run: {}, inputData: {}", stepId, runId, inputData);

        // Verify we're in step-by-step mode
        ExecutionMode mode = getExecutionMode(runId);
        if (!mode.isStepByStep()) {
            return executeSingleStep(runId, stepId, stateSupplier, planRefresher);
        }

        // Sync run plan with latest workflow definition
        planRefresher.get();

        // Reconstruct state
        WorkflowRunState state = stateSupplier.get();

        // Pre-populate stores with accumulated counts
        prePopulateStoresFromState(runId, state);

        // Get or create execution context
        WorkflowExecution execution = contextManager.getOrCreateExecutionContext(runId, state);

        // Get V2 ready nodes for validation
        Set<String> v2ReadyNodes = getV2ReadyNodes(runId, execution);

        // Verify step can be executed
        boolean legacyCanExecute = state.canExecuteStep(stepId) || state.readySteps().contains(stepId);
        boolean v2CanExecute = v2ReadyNodes.contains(stepId);

        if (!legacyCanExecute && !v2CanExecute) {
            Set<String> missingDeps = contextManager.getMissingDependencies(stepId, state);
            throw new IllegalStateException(
                "Step " + stepId + " cannot be executed. Missing dependencies: " + missingDeps);
        }

        if (v2CanExecute && !legacyCanExecute) {
            logger.info("Step {} is ready according to V2 engine but not legacy engine - using V2", stepId);
        }

        // Execute the step
        StepExecutionResult result;
        if (v2StepByStepService != null) {
            try {
                if (!v2StepByStepService.isInitialized(runId)) {
                    logger.info("V2 step-by-step not initialized for runId={}, initializing now...", runId);
                    v2StepByStepService.initializeStepByStep(execution, execution.getPlan());
                }

                // Store chat trigger input if provided
                if (inputData != null && !inputData.isEmpty() && stepId.startsWith("trigger:")) {
                    logger.info("Storing chat trigger input for stepId={}: {}", stepId, inputData);
                    execution.setChatTriggerInput(stepId, inputData);
                }

                StepByStepExecutionResult v2Result = v2StepByStepService.executeNode(runId, stepId, "0");

                result = convertV2Result(stepId, v2Result);

                logger.info("V2 step-by-step executed (step-by-step mode): stepId={}, success={}, readyNodes={}",
                    stepId, v2Result.isSuccess(), v2Result.readyNodes());

                execution.setStepResult(stepId, result);

                // Use merged ready nodes from snapshot (SINGLE SOURCE OF TRUTH) to avoid
                // overwriting other DAGs' ready nodes sent by V2StepByStepService
                Set<String> mergedReadyNodes2 = stateSnapshotService.getReadyNodeIds(runId);
                if (!mergedReadyNodes2.isEmpty()) {
                    streamingService.sendReadyStepsEvent(execution, mergedReadyNodes2);
                } else if (v2Result.readyNodes() != null && !v2Result.readyNodes().isEmpty()) {
                    streamingService.sendReadyStepsEvent(execution, v2Result.readyNodes());
                } else {
                    WorkflowRunState newState = stateSupplier.get();
                    streamingService.sendReadyStepsEvent(execution, newState.readySteps());
                }

                // Multi-DAG: reconcile run status with current ready nodes.
                // If a trigger from another DAG became ready after this step,
                // switch to WAITING_TRIGGER so the frontend starts polling.
                reconcileRunStatusWithReadyNodes(runId, mergedReadyNodes2, execution);

                contextManager.checkAndCompleteWorkflow(runId, execution, state);

                logger.info("Step {} executed with status: {} (step-by-step mode)", stepId, result.status());
                return result;

            } catch (Exception e) {
                logger.error("V2 step-by-step execution failed for step {}: {}", stepId, e.getMessage(), e);
                result = StepExecutionResult.failure(stepId, "V2 execution failed: " + e.getMessage(), e, 0);
            }
        } else {
            logger.warn("V2StepByStepService not available (step-by-step mode), returning failure for step: {}", stepId);
            result = StepExecutionResult.failure(stepId,
                "Step-by-step execution service not available", null, 0);
        }

        // Fallback persistence
        execution.setStepResult(stepId, result);
        String stepLabel = LabelNormalizer.extractLabel(stepId);
        stepCompletionOrchestrator.completeStep(execution, stepId, stepLabel, result, 0, 0);

        WorkflowRunState newState = stateSupplier.get();
        streamingService.sendReadyStepsEvent(execution, newState.readySteps());
        contextManager.checkAndCompleteWorkflow(runId, execution, newState);

        logger.info("Step {} executed with status: {} (step-by-step mode - fallback)", stepId, result.status());
        return result;
    }

    /**
     * Pre-populates stores with accumulated counts from state.
     */
    private void prePopulateStoresFromState(String runId, WorkflowRunState state) {
        // Pre-populate node counts
        if (state.steps() != null && !state.steps().isEmpty()) {
            logger.info("[prePopulateStoresFromState] Pre-populating NodeEventStore with accumulated counts from DB");
            int nodeCount = 0;
            for (WorkflowRunState.StepState stepState : state.steps()) {
                if (stepState.statusCounts() != null && !stepState.statusCounts().isEmpty()) {
                    String nodeLabel = stepState.stepAlias() != null ? stepState.stepAlias() : stepState.stepId();
                    nodeLabel = com.apimarketplace.orchestrator.utils.LabelNormalizer.extractLabelFromKey(nodeLabel);
                    runStateStore.prePopulateNodeCounts(runId, nodeLabel, stepState.statusCounts());
                    nodeCount++;
                }
            }
            logger.info("[prePopulateStoresFromState] Pre-populated {} nodes with accumulated counts", nodeCount);
        }

    }

    /**
     * Converts a V2 StepByStepExecutionResult to a StepExecutionResult.
     *
     * <p>Pending yields (AWAITING_SIGNAL, RUNNING, COLLECTING, WAITING_TRIGGER) must NOT be
     * collapsed to failure - a naive {@code !isSuccess() → failure} mapping would mis-classify
     * an async agent yield as a hard failure, which cascaded into the trigger-refire loop bug
     * (tickets T10/T11/T61). Gate on {@link StepByStepExecutionResult#isTerminal()} first and
     * surface the non-terminal status via {@link StepExecutionResult#pending}.
     */
    private StepExecutionResult convertV2Result(String stepId, StepByStepExecutionResult v2Result) {
        if (v2Result.isSuccess()) {
            return StepExecutionResult.success(stepId, v2Result.getNodeOutput(), v2Result.getExecutionTime());
        } else if (v2Result.isSkipped()) {
            return StepExecutionResult.skipped(stepId, "Step was skipped");
        } else if (!v2Result.isTerminal()) {
            String message = v2Result.nodeResult() != null && v2Result.nodeResult().errorMessage().isPresent()
                ? v2Result.nodeResult().errorMessage().get()
                : "Step pending (awaiting signal or async yield)";
            return StepExecutionResult.pending(
                stepId,
                v2Result.nodeResult() != null ? v2Result.nodeResult().status() : null,
                message,
                v2Result.getExecutionTime());
        } else {
            String errorMsg = v2Result.getErrorMessage() != null ? v2Result.getErrorMessage() : "Step execution failed";
            return StepExecutionResult.failureWithOutput(
                stepId,
                errorMsg,
                v2Result.getNodeOutput(),
                v2Result.getExecutionTime());
        }
    }

    /**
     * Gets V2 ready nodes if service is available.
     */
    private Set<String> getV2ReadyNodes(String runId, WorkflowExecution execution) {
        if (v2StepByStepService == null) {
            return Set.of();
        }

        try {
            if (!v2StepByStepService.isInitialized(runId)) {
                logger.info("V2 step-by-step not initialized for runId={}, initializing for ready check...", runId);
                v2StepByStepService.initializeStepByStep(execution, execution.getPlan());
            }
            Set<String> readyNodes = v2StepByStepService.getReadyNodes(runId, "0");
            logger.info("V2 ready nodes for validation: {}", readyNodes);
            return readyNodes;
        } catch (Exception e) {
            logger.warn("Failed to get V2 ready nodes: {}", e.getMessage());
            return Set.of();
        }
    }
}
