package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Controller for triggering reusable triggers (manual, chat, datasource).
 *
 * Unlike webhooks which use tokens, these triggers are invoked
 * directly on a specific run that is in WAITING_TRIGGER status.
 *
 * All trigger types use the same accumulation and auto-reset flow:
 * - Execute workflow with trigger payload
 * - Increment epoch counter after completion
 * - Return to WAITING_TRIGGER status for next execution
 */
@RestController
@RequestMapping("/api/v2/workflows/runs")
// Note: CORS is handled by the Gateway (see gateway/config/CorsConfig.java)
public class TriggerController {

    private static final Logger logger = LoggerFactory.getLogger(TriggerController.class);

    private final WorkflowRunRepository runRepository;
    private final ReusableTriggerService triggerService;
    private final WorkflowResumeService resumeService;
    private final CreditConsumptionClient creditClient;

    public TriggerController(WorkflowRunRepository runRepository,
                            ReusableTriggerService triggerService,
                            WorkflowResumeService resumeService,
                            CreditConsumptionClient creditClient) {
        this.runRepository = runRepository;
        this.triggerService = triggerService;
        this.resumeService = resumeService;
        this.creditClient = creditClient;
    }

    /**
     * Trigger a manual trigger for a run.
     *
     * POST /api/v2/workflows/runs/{runId}/trigger/manual
     *
     * The run must be in WAITING_TRIGGER status and have a manual trigger.
     *
     * @param runId The public run ID
     * @param payload Optional payload data
     * @return TriggerResponse with execution status
     */
    @PostMapping("/{runId}/trigger/manual")
    public ResponseEntity<TriggerResponse> triggerManual(
            @PathVariable("runId") String runId,
            @RequestBody(required = false) Map<String, Object> payload,
            @RequestHeader(value = "X-User-Plan", required = false) String userPlan) {

        logger.info("[TriggerController] Manual trigger request for runId={}", runId);

        return executeTrigger(runId, TriggerType.MANUAL, payload, userPlan);
    }

    /**
     * Trigger a chat trigger for a run with a message.
     *
     * POST /api/v2/workflows/runs/{runId}/trigger/chat
     * Body: { "message": "user message text", ... }
     *
     * The run must be in WAITING_TRIGGER status and have a chat trigger.
     *
     * @param runId The public run ID
     * @param payload Payload containing the chat message
     * @return TriggerResponse with execution status
     */
    @PostMapping("/{runId}/trigger/chat")
    public ResponseEntity<TriggerResponse> triggerChat(
            @PathVariable("runId") String runId,
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "X-User-Plan", required = false) String userPlan) {

        logger.info("[TriggerController] Chat trigger request for runId={}, message={}",
                   runId, payload != null ? payload.get("message") : null);

        if (payload == null || !payload.containsKey("message")) {
            return ResponseEntity.badRequest().body(
                TriggerResponse.error(runId, "Chat trigger requires a 'message' field in the payload")
            );
        }

        return executeTrigger(runId, TriggerType.CHAT, payload, userPlan);
    }

    /**
     * Trigger a datasource trigger for a run.
     *
     * POST /api/v2/workflows/runs/{runId}/trigger/datasource
     *
     * The run must be in WAITING_TRIGGER status and have a datasource trigger.
     * This loads data from the datasource and executes the workflow with it.
     *
     * @param runId The public run ID
     * @param triggerConfig Optional configuration/filters for datasource loading
     * @return TriggerResponse with execution status
     */
    @PostMapping("/{runId}/trigger/datasource")
    public ResponseEntity<TriggerResponse> triggerDatasource(
            @PathVariable("runId") String runId,
            @RequestBody(required = false) Map<String, Object> triggerConfig,
            @RequestHeader(value = "X-User-Plan", required = false) String userPlan) {

        logger.info("[TriggerController] Datasource trigger request for runId={}", runId);

        return executeTrigger(runId, TriggerType.DATASOURCE, triggerConfig, userPlan);
    }

    /**
     * Trigger a form trigger for a run with form data.
     *
     * POST /api/v2/workflows/runs/{runId}/trigger/form
     * Body: { "fieldName1": "value1", "fieldName2": "value2", ... }
     *
     * The run must be in WAITING_TRIGGER status and have a form trigger.
     *
     * @param runId The public run ID
     * @param formData Form field values submitted by the user
     * @return TriggerResponse with execution status
     */
    @PostMapping("/{runId}/trigger/form")
    public ResponseEntity<TriggerResponse> triggerForm(
            @PathVariable("runId") String runId,
            @RequestBody Map<String, Object> formData,
            @RequestHeader(value = "X-User-Plan", required = false) String userPlan) {

        logger.info("[TriggerController] Form trigger request for runId={}, fields={}",
                   runId, formData != null ? formData.keySet() : "null");

        return executeTrigger(runId, TriggerType.FORM, formData, userPlan);
    }

    // ========== Multi-DAG Support Endpoints ==========

    /**
     * Get available triggers for a run.
     *
     * GET /api/v2/workflows/runs/{runId}/triggers
     *
     * Returns all triggers defined in the workflow plan with their configuration.
     * Used by frontend to display trigger selection UI for multi-DAG workflows.
     *
     * @param runId The public run ID
     * @return List of TriggerInfo objects
     */
    @GetMapping("/{runId}/triggers")
    public ResponseEntity<List<TriggerInfo>> getAvailableTriggers(
            @PathVariable("runId") String runId) {

        logger.info("[TriggerController] Get available triggers for runId={}", runId);

        // 1. Find the run
        Optional<WorkflowRunEntity> runOpt = runRepository.findByRunIdPublic(runId);
        if (runOpt.isEmpty()) {
            logger.warn("[TriggerController] Run not found: {}", runId);
            return ResponseEntity.notFound().build();
        }

        WorkflowRunEntity run = runOpt.get();

        // 2. Don't surface triggers on terminal runs - the fire endpoints reject those
        // statuses, so showing them as fireable in the UI would mislead the user (who
        // would click "Fire" and get a 409 back). A terminal run requires explicit
        // reactivation before its triggers become available again.
        if (run.getStatus() != null && run.getStatus().isTerminal()) {
            logger.info("[TriggerController] Run {} is terminal ({}); returning no triggers",
                runId, run.getStatus());
            return ResponseEntity.ok(List.of());
        }

        // 3. Parse workflow plan and extract triggers
        Map<String, Object> planMap = run.getPlan();
        if (planMap == null) {
            return ResponseEntity.ok(List.of());
        }

        try {
            // Debug: log raw triggers data before parsing
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawTriggers = (List<Map<String, Object>>) planMap.get("triggers");
            logger.info("[TriggerController] Raw triggers count in plan: {}", rawTriggers != null ? rawTriggers.size() : "null");
            if (rawTriggers != null) {
                for (int i = 0; i < rawTriggers.size(); i++) {
                    Map<String, Object> t = rawTriggers.get(i);
                    logger.info("[TriggerController] Raw trigger[{}]: id={}, label={}, type={}",
                        i, t.get("id"), t.get("label"), t.get("type"));
                }
            }

            WorkflowPlan plan = WorkflowPlan.fromMap(planMap);
            List<TriggerInfo> triggers = new ArrayList<>();

            logger.info("[TriggerController] Parsed triggers count: {}", plan.getTriggers().size());
            for (Trigger trigger : plan.getTriggers()) {
                logger.info("[TriggerController] Parsed trigger: id={}, label={}, type={}, normalizedKey={}",
                    trigger.id(), trigger.label(), trigger.type(), trigger.getNormalizedKey());
                triggers.add(TriggerInfo.fromTrigger(trigger));
            }

            logger.info("[TriggerController] Found {} triggers for runId={}", triggers.size(), runId);
            return ResponseEntity.ok(triggers);

        } catch (Exception e) {
            logger.error("[TriggerController] Error parsing plan for runId={}: {}", runId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Trigger a specific trigger by its ID.
     *
     * POST /api/v2/workflows/runs/{runId}/trigger/{triggerType}/{triggerId}
     *
     * This endpoint allows explicit trigger selection for multi-DAG workflows.
     * The triggerId must match a trigger defined in the workflow plan.
     *
     * @param runId The public run ID
     * @param triggerType The trigger type (manual, chat, datasource, form)
     * @param triggerId The specific trigger ID (URL-encoded, e.g., "trigger:my_webhook")
     * @param payload Optional payload data
     * @return TriggerResponse with execution status
     */
    @PostMapping("/{runId}/trigger/{triggerType}/{triggerId}")
    @SuppressWarnings("unchecked")
    public ResponseEntity<TriggerResponse> triggerSpecific(
            @PathVariable("runId") String runId,
            @PathVariable("triggerType") String triggerType,
            @PathVariable("triggerId") String triggerId,
            @RequestBody(required = false) Map<String, Object> payload,
            @RequestHeader(value = "X-User-Plan", required = false) String userPlan) {

        logger.info("[TriggerController] Specific trigger request: runId={}, type={}, triggerId={}",
                   runId, triggerType, triggerId);

        // 1. Find the run
        Optional<WorkflowRunEntity> runOpt = runRepository.findByRunIdPublic(runId);
        if (runOpt.isEmpty()) {
            logger.warn("[TriggerController] Run not found: {}", runId);
            return ResponseEntity.notFound().build();
        }

        WorkflowRunEntity run = runOpt.get();

        // 1b. Credit check before trigger execution
        if (!creditClient.checkCredits(run.getTenantId())) {
            logger.warn("[TriggerController] Insufficient credits for user {}, blocking specific trigger for run {}",
                    run.getTenantId(), runId);
            return ResponseEntity.status(402).body(
                TriggerResponse.error(runId, "Insufficient credits to execute trigger")
            );
        }

        // 2. Check status - only non-terminal states accept trigger fires:
        //   - WAITING_TRIGGER: the proper between-cycle state. resetForNextCycle (line ~1289
        //     of ReusableTriggerService) always transitions a finishing cycle to WAITING_TRIGGER,
        //     even when the cycle ended in failure (cycleResult is recorded in metadata only).
        //   - RUNNING: parallel epochs - EpochConcurrencyLimiter is the actual gate.
        //   - PAUSED: step-by-step mode awaiting the next manual step.
        //
        // COMPLETED/FAILED/CANCELLED/TIMEOUT/PARTIAL_SUCCESS reach this controller only when
        // the run is genuinely terminal (e.g. JVM crashed mid-cycle before resetForNextCycle
        // ran - see prod OOM 2026-05-07 12:40 UTC where run_<id> stayed
        // FAILED and accumulated 73 epochs because the controller kept accepting fires).
        // To re-fire a terminal run, the user must explicitly reactivate it; we no longer
        // auto-cycle a stuck run.
        RunStatus status = run.getStatus();
        boolean canTrigger = status == RunStatus.WAITING_TRIGGER ||
                            status == RunStatus.RUNNING ||
                            status == RunStatus.PAUSED;

        if (!canTrigger) {
            logger.warn("[TriggerController] Run {} is not in a triggerable state. Status: {}, Mode: {}",
                       runId, status, run.getExecutionMode());
            return ResponseEntity.status(409).body(
                TriggerResponse.error(runId,
                    "Run is not waiting for trigger. Current status: " + status)
            );
        }

        logger.info("[TriggerController] Trigger accepted: status={}, mode={}", status, run.getExecutionMode());

        // Sanitize: strip any externally-injected PLAN_FROM_PAYLOAD_MARKER so a
        // malicious client can't bypass the unpinned-plan refresh by stuffing
        // {"__planFromPayload": true} into the request body. The marker is set
        // ONLY by this controller, ONLY when updateRunPlan succeeds below.
        payload = ReusableTriggerService.sanitizePlanMarker(payload);

        // 3. If frontend sends a plan, update the run's plan before execution.
        // Only set planFromPayload when updateRunPlan actually wrote the new plan
        // to run.plan (non-null return). On rejection (empty / topology-incompatible)
        // it returns null and we MUST NOT tag the marker - otherwise
        // executeTriggerInternal would skip the workflow.plan refresh and execute
        // on a stale run.plan.
        boolean planFromPayload = false;
        if (payload != null && payload.get("plan") instanceof Map) {
            Map<String, Object> planMap = (Map<String, Object>) payload.get("plan");
            logger.info("[TriggerController] Updating run plan before specific trigger for runId={}", runId);
            WorkflowPlan accepted = resumeService.updateRunPlan(runId, planMap);
            if (accepted != null) {
                // Re-fetch the run after plan update
                runOpt = runRepository.findByRunIdPublic(runId);
                if (runOpt.isPresent()) {
                    run = runOpt.get();
                }
                planFromPayload = true;
            } else {
                logger.warn("[TriggerController] updateRunPlan rejected payload for runId={} - passive-fire semantics will apply", runId);
            }
        }

        // 4. Parse trigger type
        TriggerType type;
        try {
            type = TriggerType.fromValue(triggerType);
        } catch (IllegalArgumentException e) {
            logger.warn("[TriggerController] Invalid trigger type: {}", triggerType);
            return ResponseEntity.badRequest().body(
                TriggerResponse.error(runId, "Invalid trigger type: " + triggerType)
            );
        }

        // 5. Validate triggerId exists in plan
        if (!validateTriggerId(run, triggerId)) {
            logger.warn("[TriggerController] Trigger not found in plan: {}", triggerId);
            return ResponseEntity.badRequest().body(
                TriggerResponse.error(runId, "Trigger not found in workflow plan: " + triggerId)
            );
        }

        // 6. Validate payload for chat trigger
        if (type == TriggerType.CHAT && (payload == null || !payload.containsKey("message"))) {
            return ResponseEntity.badRequest().body(
                TriggerResponse.error(runId, "Chat trigger requires a 'message' field in the payload")
            );
        }

        // 7. Update user plan in run metadata for priority-based execution queue
        updateUserPlanMetadata(run, userPlan);

        // 8. Execute the trigger.
        // AUTOMATIC mode: dispatch to the worker pool and return 202 immediately.
        // The full epoch (~10 s on Gmail-sized workflows) used to block the Tomcat
        // thread synchronously; SSE already streams progress to the frontend, so the
        // request thread is freed for the next call.
        // STEP_BY_STEP mode: keep synchronous - engine pauses immediately after
        // opening the epoch, and the response.readySteps seeds the SBS panel.
        Map<String, Object> triggerPayload = stripPlanAndTagMarker(payload, planFromPayload);
        boolean async = run.getExecutionMode() != null && run.getExecutionMode().isAutomatic();

        try {
            TriggerExecutionResult result = async
                ? triggerService.executeTriggerAsync(run, triggerId, type, triggerPayload != null ? triggerPayload : Map.of())
                : triggerService.executeTrigger(run, triggerId, type, triggerPayload != null ? triggerPayload : Map.of());

            if (result.success()) {
                logger.info("[TriggerController] Trigger {} {} for run {}, epoch={}, readySteps={}",
                           triggerId, async ? "accepted (async)" : "executed", runId, result.epoch(), result.readySteps());
                return ResponseEntity.accepted().body(
                    TriggerResponse.success(runId, triggerId, type, result.epoch(), result.message(), result.readySteps())
                );
            } else {
                logger.error("[TriggerController] Trigger {} execution failed for run {}: {}",
                            triggerId, runId, result.message());
                return ResponseEntity.badRequest().body(
                    TriggerResponse.error(runId, result.message())
                );
            }
        } catch (Exception e) {
            logger.error("[TriggerController] Error executing trigger {} for run {}: {}",
                        triggerId, runId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(
                TriggerResponse.error(runId, "Internal error: " + e.getMessage())
            );
        }
    }

    /**
     * Validate that a triggerId exists in the workflow plan.
     */
    private boolean validateTriggerId(WorkflowRunEntity run, String triggerId) {
        Map<String, Object> planMap = run.getPlan();
        if (planMap == null) {
            return false;
        }

        try {
            WorkflowPlan plan = WorkflowPlan.fromMap(planMap);
            for (Trigger trigger : plan.getTriggers()) {
                if (triggerId.equals(trigger.getNormalizedKey())) {
                    return true;
                }
            }
        } catch (Exception e) {
            logger.warn("[TriggerController] Failed to validate triggerId for run {}: {}",
                       run.getRunIdPublic(), e.getMessage());
        }

        return false;
    }

    /**
     * Execute a trigger of the specified type.
     */
    @SuppressWarnings("unchecked")
    private ResponseEntity<TriggerResponse> executeTrigger(
            String runId, TriggerType triggerType, Map<String, Object> payload,
            String userPlan) {

        // 1. Find the run
        Optional<WorkflowRunEntity> runOpt = runRepository.findByRunIdPublic(runId);
        if (runOpt.isEmpty()) {
            logger.warn("[TriggerController] Run not found: {}", runId);
            return ResponseEntity.notFound().build();
        }

        WorkflowRunEntity run = runOpt.get();

        // 1b. Credit check before trigger execution
        if (!creditClient.checkCredits(run.getTenantId())) {
            logger.warn("[TriggerController] Insufficient credits for user {}, blocking trigger for run {}",
                    run.getTenantId(), runId);
            return ResponseEntity.status(402).body(
                TriggerResponse.error(runId, "Insufficient credits to execute trigger")
            );
        }

        // 2. Check status - only non-terminal states accept trigger fires.
        // Mirrors the executeSpecificTrigger gate above: terminal runs (FAILED, COMPLETED,
        // CANCELLED, TIMEOUT, PARTIAL_SUCCESS) require an explicit reactivation.
        RunStatus status = run.getStatus();
        boolean canTrigger = status == RunStatus.WAITING_TRIGGER ||
                            status == RunStatus.RUNNING ||
                            status == RunStatus.PAUSED;
        if (!canTrigger) {
            logger.warn("[TriggerController] Run {} is not in a triggerable state. Status: {}",
                       runId, status);
            return ResponseEntity.status(409).body(
                TriggerResponse.error(runId,
                    "Run is not waiting for trigger. Current status: " + status)
            );
        }

        // Sanitize: strip any externally-injected PLAN_FROM_PAYLOAD_MARKER
        // (see executeSpecificTrigger above for rationale).
        payload = ReusableTriggerService.sanitizePlanMarker(payload);

        // 3. If frontend sends a plan, update the run's plan before execution.
        // Only set planFromPayload when updateRunPlan actually wrote the new plan
        // (see executeSpecificTrigger above for rationale).
        boolean planFromPayload = false;
        if (payload != null && payload.get("plan") instanceof Map) {
            Map<String, Object> planMap = (Map<String, Object>) payload.get("plan");
            logger.info("[TriggerController] Updating run plan before trigger for runId={}", runId);
            WorkflowPlan accepted = resumeService.updateRunPlan(runId, planMap);
            if (accepted != null) {
                runOpt = runRepository.findByRunIdPublic(runId);
                if (runOpt.isPresent()) {
                    run = runOpt.get();
                }
                planFromPayload = true;
            } else {
                logger.warn("[TriggerController] updateRunPlan rejected payload for runId={} - passive-fire semantics will apply", runId);
            }
        }

        // 4. Find the trigger ID of the specified type
        String triggerId = findTriggerIdByType(run, triggerType);
        if (triggerId == null) {
            // Distinguish between "not found" and "ambiguous" (multiple triggers of same type)
            String errorMsg = buildTriggerNotFoundMessage(run, triggerType);
            logger.warn("[TriggerController] Cannot resolve {} trigger for run {}: {}",
                       triggerType, runId, errorMsg);
            return ResponseEntity.badRequest().body(
                TriggerResponse.error(runId, errorMsg)
            );
        }

        // 5. Update user plan in run metadata for priority-based execution queue
        updateUserPlanMetadata(run, userPlan);

        // 6. Execute the trigger. AUTOMATIC → async (frees Tomcat thread, SSE streams
        // progress); STEP_BY_STEP → sync (engine pauses immediately, readySteps seeds
        // the SBS panel). See triggerSpecific above for full rationale.
        Map<String, Object> triggerPayload = stripPlanAndTagMarker(payload, planFromPayload);
        boolean async = run.getExecutionMode() != null && run.getExecutionMode().isAutomatic();

        try {
            TriggerExecutionResult result = async
                ? triggerService.executeTriggerAsync(run, triggerId, triggerType, triggerPayload != null ? triggerPayload : Map.of())
                : triggerService.executeTrigger(run, triggerId, triggerType, triggerPayload != null ? triggerPayload : Map.of());

            if (result.success()) {
                logger.info("[TriggerController] {} trigger {} for run {}, epoch={}, readySteps={}",
                           triggerType, async ? "accepted (async)" : "executed", runId, result.epoch(), result.readySteps());
                return ResponseEntity.accepted().body(
                    TriggerResponse.success(runId, triggerId, triggerType, result.epoch(), result.message(), result.readySteps())
                );
            } else {
                logger.error("[TriggerController] {} trigger execution failed for run {}: {}",
                            triggerType, runId, result.message());
                return ResponseEntity.badRequest().body(
                    TriggerResponse.error(runId, result.message())
                );
            }
        } catch (Exception e) {
            logger.error("[TriggerController] Error executing {} trigger for run {}: {}",
                        triggerType, runId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(
                TriggerResponse.error(runId, "Internal error: " + e.getMessage())
            );
        }
    }

    /**
     * Find the trigger ID of the specified type in the workflow plan.
     */
    /**
     * Find the trigger ID of the specified type in the workflow plan.
     *
     * For multi-trigger workflows with multiple triggers of the same type,
     * returns null to indicate ambiguity. The caller should direct users
     * to the specific trigger endpoint instead.
     *
     * @return triggerId if exactly one match, null if zero or multiple matches
     */
    @SuppressWarnings("unchecked")
    private String findTriggerIdByType(WorkflowRunEntity run, TriggerType triggerType) {
        Map<String, Object> planMap = run.getPlan();
        if (planMap == null) {
            return null;
        }

        try {
            WorkflowPlan plan = WorkflowPlan.fromMap(planMap);
            String typeValue = triggerType.getValue();

            List<String> matchingIds = new ArrayList<>();
            for (Trigger trigger : plan.getTriggers()) {
                if (typeValue.equals(trigger.type())) {
                    matchingIds.add(trigger.getNormalizedKey());
                }
            }

            if (matchingIds.size() == 1) {
                return matchingIds.get(0);
            }
            // 0 or >1 matches: return null (ambiguous or not found)
            if (matchingIds.size() > 1) {
                logger.info("[TriggerController] Multiple {} triggers found for run {}: {}",
                    triggerType, run.getRunIdPublic(), matchingIds);
            }
            return null;
        } catch (Exception e) {
            logger.warn("[TriggerController] Failed to parse workflow plan for run {}: {}",
                       run.getRunIdPublic(), e.getMessage());
        }

        return null;
    }

    /**
     * Build an appropriate error message when findTriggerIdByType returns null.
     * Distinguishes between "no trigger of this type" and "multiple triggers of same type (ambiguous)".
     */
    private String buildTriggerNotFoundMessage(WorkflowRunEntity run, TriggerType triggerType) {
        Map<String, Object> planMap = run.getPlan();
        if (planMap == null) {
            return "No " + triggerType.getValue() + " trigger found in this workflow";
        }

        try {
            WorkflowPlan plan = WorkflowPlan.fromMap(planMap);
            String typeValue = triggerType.getValue();
            List<String> matchingIds = new ArrayList<>();
            for (Trigger trigger : plan.getTriggers()) {
                if (typeValue.equals(trigger.type())) {
                    matchingIds.add(trigger.getNormalizedKey());
                }
            }

            if (matchingIds.size() > 1) {
                return "Multiple " + typeValue + " triggers found: " + matchingIds +
                    ". Use POST /{runId}/trigger/" + typeValue + "/{triggerId} with a specific trigger ID.";
            }
        } catch (Exception ignored) {
            // Fall through to default message
        }

        return "No " + triggerType.getValue() + " trigger found in this workflow";
    }

    /**
     * Updates the userPlan in run metadata with the latest value from the request header.
     * This ensures the execution queue always uses the most current plan.
     *
     * <p>Plan v4 E2E4 - uses the targeted {@code upsertUserPlanMetadata} JSONB
     * merge query instead of {@code runRepository.save(run)} on a detached
     * entity. The merge-via-save path was emitting a full-row UPDATE that
     * overwrote the live {@code state_snapshot_seq=N} with the request
     * handler's stale L1 copy {@code N-M}, tripping V181's monotonicity trigger
     * 32k+ times across a 3-min k6 saturation. The targeted UPDATE touches
     * only {@code metadata} (via {@code jsonb_set}) and {@code updated_at}.
     */
    private void updateUserPlanMetadata(WorkflowRunEntity run, String userPlan) {
        if (userPlan == null || userPlan.isBlank()) {
            return;
        }
        // Skip the per-fire hot-row UPDATE when the stamped plan is already current.
        // For reusable triggers re-fired with a stable plan (the common case), this
        // removes one workflow_runs write (metadata + updated_at, hence one tuple-lock
        // contender) per fire. The value is read from the already-loaded entity - no
        // extra query - and the queue message still carries the authoritative plan for
        // this fire, so a rare stale skip cannot misroute execution.
        Map<String, Object> metadata = run.getMetadata();
        if (metadata != null && userPlan.equals(metadata.get("userPlan"))) {
            return;
        }
        try {
            int rows = runRepository.upsertUserPlanMetadata(run.getRunIdPublic(), userPlan);
            if (rows == 0) {
                logger.debug("[TriggerController] upsertUserPlanMetadata touched 0 rows for run {} "
                        + "(row deleted between load and update - safe to ignore)",
                    run.getRunIdPublic());
            }
        } catch (Exception e) {
            logger.warn("[TriggerController] Failed to update userPlan metadata for run {}: {}",
                run.getRunIdPublic(), e.getMessage());
        }
    }

    /**
     * Strips the (already-consumed) {@code plan} key from the trigger payload
     * and tags the internal {@link ReusableTriggerService#PLAN_FROM_PAYLOAD_MARKER}
     * when {@code planFromPayload=true}, signalling to
     * {@code executeTriggerInternal} that {@code run.plan} reflects an
     * explicit user intent and the unpinned-passive-fire branch must NOT
     * clobber it with {@code workflow.plan}.
     */
    private static Map<String, Object> stripPlanAndTagMarker(Map<String, Object> payload, boolean planFromPayload) {
        if (payload == null || (!payload.containsKey("plan") && !planFromPayload)) {
            return payload;
        }
        Map<String, Object> next = new java.util.HashMap<>(payload);
        next.remove("plan");
        if (planFromPayload) {
            next.put(ReusableTriggerService.PLAN_FROM_PAYLOAD_MARKER, Boolean.TRUE);
        }
        return next;
    }

    /**
     * Response DTO for trigger execution.
     */
    public record TriggerResponse(
        String runId,
        String triggerId,
        String triggerType,
        String status,
        String message,
        int epoch,
        java.util.Set<String> readySteps
    ) {
        public static TriggerResponse success(String runId, String triggerId,
                TriggerType type, int epoch, String message, java.util.Set<String> readySteps) {
            return new TriggerResponse(runId, triggerId, type.getValue(),
                "triggered", message, epoch, readySteps != null ? readySteps : java.util.Set.of());
        }

        public static TriggerResponse error(String runId, String message) {
            return new TriggerResponse(runId, null, null, "error", message, 0, java.util.Set.of());
        }
    }
}
