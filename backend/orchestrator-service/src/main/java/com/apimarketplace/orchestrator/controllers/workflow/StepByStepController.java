package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.engine.StepByStepExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.scheduler.V2StepByStepScheduler;
import com.apimarketplace.orchestrator.execution.v2.services.V2StepByStepService;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;

import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.services.resume.WorkflowRunState;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Controller for Step-by-Step Execution Mode operations.
 * Handles step execution, core node execution, execution mode management.
 */
@RestController
@RequestMapping("/api/v2/workflows/dag")
@Validated
public class StepByStepController {

    private static final Logger logger = LoggerFactory.getLogger(StepByStepController.class);

    @Autowired
    private WorkflowResumeService resumeService;

    @Autowired(required = false)
    private V2StepByStepService v2StepByStepService;

    @Autowired(required = false)
    private V2StepByStepScheduler v2StepByStepScheduler;

    @Autowired
    private StateSnapshotService stateSnapshotService;

    @Autowired
    private CreditConsumptionClient creditClient;

    @Autowired
    private WorkflowRunRepository runRepository;

    /**
     * Audit 2026-05-16 round-3 - every step-by-step endpoint MUST scope by caller.
     * Returns:
     *   401 if X-User-ID is missing
     *   404 if run is not visible to (tenantId, orgId)
     *   null on success.
     */
    private ResponseEntity<?> guardRunScope(String runId, String userId, String orgId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "Missing X-User-ID"));
        }
        Optional<WorkflowRunEntity> runOpt = runRepository.findByRunIdPublic(runId);
        if (runOpt.isEmpty()) return ResponseEntity.notFound().build();
        if (!WorkflowControllerHelper.isRunInScope(runOpt.get(), userId, orgId)) {
            logger.warn("[SCOPE] StepByStep cross-tenant blocked: runId={} caller={} orgId={}", runId, userId, orgId);
            return ResponseEntity.notFound().build();
        }
        return null;
    }

    /**
     * Executes a single step in step-by-step mode.
     */
    @PostMapping("/runs/{runId}/step/{stepId}/execute")
    public ResponseEntity<?> executeSingleStep(
            @PathVariable("runId") String runId,
            @PathVariable("stepId") String stepId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        try {
            ResponseEntity<?> scopeBlock = guardRunScope(runId, userId, orgId);
            if (scopeBlock != null) return scopeBlock;

            if (!creditClient.checkCredits(userId)) {
                return ResponseEntity.status(402).body(Map.of("error", "Insufficient credits to execute step"));
            }

            logger.info("Executing single step: {} for run: {}", stepId, runId);
            StepExecutionResult result = resumeService.executeSingleStep(runId, stepId);
            WorkflowRunState newState = resumeService.reconstructStateForApi(runId);

            return ResponseEntity.ok(Map.of(
                "success", result.status().isSuccessful(),
                "runId", runId,
                "stepId", stepId,
                "status", result.status().toWireValue(),
                "output", result.output() != null ? result.output() : Map.of(),
                "executionTime", result.executionTime(),
                "message", result.message() != null ? result.message() : "Step executed",
                "readySteps", newState.readySteps(),
                "workflowStatus", newState.status().getValue()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage(), "stepId", stepId));
        } catch (Exception e) {
            logger.error("Error executing step {}: {}", stepId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to execute step: " + e.getMessage()));
        }
    }

    /**
     * Gets the list of steps that are ready to execute.
     */
    @GetMapping("/runs/{runId}/ready-steps")
    public ResponseEntity<?> getReadySteps(
            @PathVariable("runId") String runId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        try {
            ResponseEntity<?> scopeBlock = guardRunScope(runId, userId, orgId);
            if (scopeBlock != null) return scopeBlock;

            logger.info("Getting ready steps for runId: {}", runId);
            Set<String> readySteps = resumeService.getReadySteps(runId);

            return ResponseEntity.ok(Map.of(
                "runId", runId,
                "readySteps", readySteps,
                "count", readySteps.size()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            logger.error("Error getting ready steps for runId: {}", runId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to get ready steps: " + e.getMessage()));
        }
    }

    /**
     * Checks if a workflow is paused.
     */
    @GetMapping("/runs/{runId}/is-paused")
    public ResponseEntity<?> isPaused(
            @PathVariable("runId") String runId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        try {
            ResponseEntity<?> scopeBlock = guardRunScope(runId, userId, orgId);
            if (scopeBlock != null) return scopeBlock;
            boolean paused = resumeService.isPaused(runId);
            return ResponseEntity.ok(Map.of("runId", runId, "isPaused", paused));
        } catch (Exception e) {
            logger.error("Error checking pause status for runId: {}", runId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to check pause status: " + e.getMessage()));
        }
    }

    /**
     * Sets the execution mode for a workflow run.
     */
    @PostMapping("/runs/{runId}/execution-mode")
    public ResponseEntity<?> setExecutionMode(
            @PathVariable("runId") String runId,
            @RequestBody Map<String, String> request,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        try {
            ResponseEntity<?> scopeBlock = guardRunScope(runId, userId, orgId);
            if (scopeBlock != null) return scopeBlock;
            String modeStr = request.get("mode");
            if (modeStr == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Missing 'mode' in request body"));
            }

            ExecutionMode mode = ExecutionMode.fromString(modeStr);
            logger.info("Setting execution mode for run {}: {}", runId, mode);

            resumeService.setExecutionMode(runId, mode);
            WorkflowRunState state = resumeService.reconstructStateForApi(runId);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "runId", runId,
                "executionMode", mode.getValue(),
                "readySteps", state.readySteps(),
                "status", state.status().getValue()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            logger.error("Error setting execution mode for run {}: {}", runId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to set execution mode: " + e.getMessage()));
        }
    }

    /**
     * Gets the current execution mode for a workflow run.
     */
    @GetMapping("/runs/{runId}/execution-mode")
    public ResponseEntity<?> getExecutionMode(
            @PathVariable("runId") String runId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        try {
            ResponseEntity<?> scopeBlock = guardRunScope(runId, userId, orgId);
            if (scopeBlock != null) return scopeBlock;
            ExecutionMode mode = resumeService.getExecutionMode(runId);
            return ResponseEntity.ok(Map.of(
                "runId", runId,
                "executionMode", mode.getValue(),
                "isStepByStep", mode.isStepByStep()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            logger.error("Error getting execution mode for run {}: {}", runId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to get execution mode: " + e.getMessage()));
        }
    }

    /**
     * Starts a workflow in step-by-step mode.
     * Optionally accepts a plan in the request body to update the run's plan before starting.
     */
    @PostMapping("/runs/{runId}/start-step-by-step")
    public ResponseEntity<?> startInStepByStepMode(
            @PathVariable("runId") String runId,
            @RequestBody(required = false) Map<String, Object> requestBody,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        try {
            ResponseEntity<?> scopeBlock = guardRunScope(runId, userId, orgId);
            if (scopeBlock != null) return scopeBlock;
            updatePlanIfPresent(runId, requestBody);

            logger.info("Starting workflow in step-by-step mode: {}", runId);
            WorkflowRunState state = resumeService.startInStepByStepMode(runId);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "runId", runId,
                "executionMode", "step_by_step",
                "status", state.status().getValue(),
                "readySteps", state.readySteps(),
                "message", "Workflow started in step-by-step mode"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            logger.error("Error starting workflow in step-by-step mode: {}", runId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to start in step-by-step mode: " + e.getMessage()));
        }
    }

    /**
     * Executes (evaluates) a core node (decision/switch) in step-by-step mode.
     *
     * Routes through the V2 engine ({@link V2StepByStepService#executeNode}) so that
     * core nodes get the same execution pipeline as regular steps:
     * - EdgeStatusEmitter emits port-qualified edges (COMPLETED/SKIPPED)
     * - V2SkipPropagationService handles recursive skip propagation
     * - SnapshotService sends batch-update WS events with edge data
     * - ReadyNodeCalculator respects merge nodes and multi-DAG
     *
     * Optionally accepts a plan in the request body to update the run's plan before execution.
     *
     * @deprecated Use WebSocket action {@code sbs.execute} via {@code InternalSbsController} instead.
     */
    @Deprecated
    @PostMapping("/runs/{runId}/core/{coreId}/execute")
    public ResponseEntity<?> executeCore(
            @PathVariable("runId") String runId,
            @PathVariable("coreId") String coreId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestBody(required = false) Map<String, Object> requestBody) {
        try {
            ResponseEntity<?> scopeBlock = guardRunScope(runId, userId, orgId);
            if (scopeBlock != null) return scopeBlock;
            if (!creditClient.checkCredits(userId)) {
                return ResponseEntity.status(402).body(Map.of("error", "Insufficient credits to execute step"));
            }

            updatePlanIfPresent(runId, requestBody);

            // Normalize coreId (ensure "core:" prefix for V2 engine)
            String normalizedCoreId = normalizeCoreIdForV2(coreId);
            logger.info("[V2] Executing core node via unified engine: runId={}, coreId={}, normalizedCoreId={}",
                runId, coreId, normalizedCoreId);

            // Execute through V2 engine (same path as regular steps)
            StepByStepExecutionResult v2Result =
                v2StepByStepService.executeNode(runId, normalizedCoreId, "0");

            // Extract decision-specific data for backward-compatible response
            V2StepByStepService.CoreExecutionData coreData =
                v2StepByStepService.extractCoreExecutionData(runId, normalizedCoreId, v2Result);

            // Get merged readyNodes from StateSnapshot (single source of truth)
            Set<String> mergedReadyNodes = stateSnapshotService.getReadyNodeIds(runId);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("runId", runId);
            response.put("coreId", coreId);
            response.put("normalizedCoreId", normalizedCoreId);
            response.put("selectedBranch", coreData.selectedBranch() != null ? coreData.selectedBranch() : "");
            response.put("skippedBranches", coreData.skippedBranches());
            response.put("evaluations", coreData.evaluations());
            response.put("readySteps", mergedReadyNodes);
            response.put("message", "Decision evaluated: " +
                (coreData.selectedBranch() != null ? coreData.selectedBranch() : "no branch selected"));

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage(), "coreId", coreId));
        } catch (Exception e) {
            logger.error("Error executing core node {}: {}", coreId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to execute core node: " + e.getMessage()));
        }
    }

    /**
     * Normalize a core ID for the V2 engine.
     * Ensures the ID has the "core:" prefix required by the execution tree.
     */
    private String normalizeCoreIdForV2(String coreId) {
        if (coreId.startsWith("core:")) {
            return coreId;
        }
        String normalized = LabelNormalizer.extractCoreLabel(coreId);
        String coreKey = LabelNormalizer.coreKey(normalized != null ? normalized : coreId);
        return coreKey != null ? coreKey : "core:" + coreId;
    }

    /**
     * Executes a single step in step-by-step mode (without auto-propagation).
     *
     * @deprecated Use WebSocket action {@code sbs.execute} via {@code InternalSbsController} instead.
     */
    @Deprecated
    @PostMapping("/runs/{runId}/step-by-step/{stepId}/execute")
    public ResponseEntity<?> executeSingleStepInStepByStepMode(
            @PathVariable("runId") String runId,
            @PathVariable("stepId") String stepId,
            @RequestParam(value = "epoch", required = false) Integer epoch,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestBody(required = false) Map<String, Object> inputData) {
        try {
            ResponseEntity<?> scopeBlock = guardRunScope(runId, userId, orgId);
            if (scopeBlock != null) return scopeBlock;
            // Credit pre-check
            if (!creditClient.checkCredits(userId)) {
                return ResponseEntity.status(402).body(Map.of("error", "Insufficient credits to execute step"));
            }

            updatePlanIfPresent(runId, inputData);

            String itemId = "0";
            if (inputData != null && inputData.containsKey("itemId")) {
                itemId = String.valueOf(inputData.get("itemId"));
                logger.info("V2 parallel execution: itemId={}", itemId);
            }

            // Also check for epoch in request body (backward compat)
            if (epoch == null && inputData != null && inputData.containsKey("epoch")) {
                Object epochObj = inputData.get("epoch");
                if (epochObj instanceof Number) {
                    epoch = ((Number) epochObj).intValue();
                }
            }

            // Always use V2 for step-by-step mode when available.
            // V1 uses reconstructState() which has sync issues with V2 engine.
            boolean useV2 = v2StepByStepService != null;

            if (useV2 && !stateSnapshotService.claimNodeForExecution(runId, stepId)) {
                logger.warn("[StepByStep] Node not ready, rejecting: runId={}, stepId={}", runId, stepId);
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "success", false,
                    "error", "NODE_NOT_READY",
                    "message", "Node is not in READY state (already executing or not yet ready)",
                    "stepId", stepId
                ));
            }

            // Check for pending Split items when itemId is "0" (parent item).
            // This handles the case where Split spawned N items and user clicks "Ready" on a step.
            if ("0".equals(itemId) && useV2 && v2StepByStepScheduler != null) {
                Set<String> pendingItemIds = v2StepByStepScheduler.getPendingItemIdsForNode(runId, stepId);
                if (!pendingItemIds.isEmpty()) {
                    V2StepByStepService.SplitExecutionResult splitResult =
                        v2StepByStepService.executeSplitItems(runId, stepId, pendingItemIds);

                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("success", splitResult.allSuccess());
                    response.put("runId", runId);
                    response.put("stepId", stepId);
                    response.put("itemsExecuted", splitResult.itemsExecuted());
                    response.put("status", splitResult.allSuccess() ? "COMPLETED" : "FAILED");
                    response.put("readySteps", splitResult.readyNodes());
                    response.put("workflowComplete", splitResult.anyWorkflowComplete());
                    response.put("executionMode", "step_by_step_v2_split");

                    return ResponseEntity.ok(response);
                }
            }

            if (useV2) {
                logger.info("[StepByStep] ========== V2 STEP EXECUTION ==========");
                logger.info("[StepByStep] Using V2 step-by-step service: stepId={}, itemId={}, epoch={}", stepId, itemId, epoch);
                com.apimarketplace.orchestrator.execution.v2.engine.StepByStepExecutionResult v2Result =
                    epoch != null
                        ? v2StepByStepService.executeNode(runId, stepId, itemId, epoch)
                        : v2StepByStepService.executeNode(runId, stepId, itemId);

                logger.info("[StepByStep] V2 execution result: success={}, readyNodes={}, workflowComplete={}",
                    v2Result.isSuccess(), v2Result.readyNodes(), v2Result.workflowComplete());

                // Build response with interface snapshots
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("success", v2Result.isSuccess());
                response.put("runId", runId);
                response.put("stepId", stepId);
                response.put("itemId", itemId);
                response.put("status", v2Result.isSuccess() ? "COMPLETED" : "FAILED");
                response.put("readySteps", v2Result.readyNodes());
                response.put("workflowComplete", v2Result.workflowComplete());
                response.put("executionMode", "step_by_step_v2");

                // Reconcile run status (PAUSED / WAITING_TRIGGER) after V2 execution
                try {
                    stateSnapshotService.reconcileSbsRunStatus(runId);
                } catch (Exception statusEx) {
                    logger.warn("[StepByStep] Status reconcile failed: runId={}, stepId={}", runId, stepId, statusEx);
                }

                logger.info("[StepByStep] ========== V2 STEP EXECUTION DONE ==========");
                return ResponseEntity.ok(response);
            }

            logger.info("[StepByStep] ========== V1 STEP EXECUTION ==========");
            logger.info("[StepByStep] Executing single step (step-by-step): {} for run: {}, inputData: {}", stepId, runId, inputData);
            StepExecutionResult result = resumeService.executeSingleStepInStepByStepMode(runId, stepId, inputData);
            WorkflowRunState newState = resumeService.reconstructStateForApi(runId);

            Set<String> readySteps = newState.readySteps();
            logger.info("[StepByStep] Step execution result: status={}, executionTime={}", result.status(), result.executionTime());
            logger.info("[StepByStep] Ready nodes from StateManager: {}", readySteps);

            // Build response with interface snapshots
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", result.status().isSuccessful());
            response.put("runId", runId);
            response.put("stepId", stepId);
            response.put("status", result.status().toWireValue());
            response.put("output", result.output() != null ? result.output() : Map.of());
            response.put("executionTime", result.executionTime());
            response.put("message", result.message() != null ? result.message() : "Step executed");
            response.put("readySteps", readySteps);
            response.put("workflowStatus", newState.status().getValue());
            response.put("executionMode", "step_by_step");

            logger.info("[StepByStep] ========== V1 STEP EXECUTION DONE ==========");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage(), "stepId", stepId));
        } catch (Exception e) {
            logger.error("Error executing step {}: {}", stepId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to execute step: " + e.getMessage()));
        }
    }

    // =========================================================================
    // PRIVATE HELPERS
    // =========================================================================

    /**
     * Updates the run plan if a "plan" key is present in the request body.
     *
     * <p>{@link com.apimarketplace.orchestrator.services.resume.WorkflowResumeService#updateRunPlan}
     * returns {@code null} when the payload is rejected (empty, topology-incompatible,
     * or pinned-non-editor refusal). Surface that signal as a WARN - silently
     * swallowing it would leave the user thinking their inspector edit was
     * accepted while the engine runs the frozen plan.
     */
    @SuppressWarnings("unchecked")
    private void updatePlanIfPresent(String runId, Map<String, Object> requestBody) {
        if (requestBody != null && requestBody.get("plan") instanceof Map) {
            Map<String, Object> planMap = (Map<String, Object>) requestBody.get("plan");
            logger.info("[StepByStep] Updating run plan for runId={}", runId);
            com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan accepted =
                    resumeService.updateRunPlan(runId, planMap);
            if (accepted == null) {
                logger.warn("[StepByStep] updateRunPlan rejected for runId={} - step will execute against the frozen run.plan", runId);
            }
        }
    }

}
