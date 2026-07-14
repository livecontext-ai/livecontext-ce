package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.domain.execution.DagState;
import com.apimarketplace.orchestrator.domain.execution.EpochState;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.utils.EdgeRefParser;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowEpochRepository.EpochHeaderRow;
import com.apimarketplace.orchestrator.repository.WorkflowEpochRepository.EpochHeaderWithEpochRow;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.EditorRunResolver;
import com.apimarketplace.orchestrator.services.StepOutputService;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import com.apimarketplace.orchestrator.trigger.ReusableTriggerService;
import com.apimarketplace.orchestrator.trigger.TriggerExecutionResult;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service responsible for agent-initiated workflow execution.
 *
 * <p>Delegates run creation to {@link EditorRunResolver} for centralized find-or-create
 * logic: reuses the existing live run at the same version and mode (epoch accumulation),
 * or creates a new run marked as {@code __editorRun__}.
 *
 * <p>Only user-fireable trigger types are allowed (manual, chat, form, datasource,
 * webhook, schedule). Workflow and error triggers are system-managed and rejected.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentWorkflowFireService {

    private static final int OUTPUT_PREVIEW_MAX_ROWS = 3;
    private static final int OUTPUT_TRUNCATE_THRESHOLD = OUTPUT_PREVIEW_MAX_ROWS + 1;

    private final WorkflowRunRepository runRepository;
    private final WorkflowExecutionService executionService;
    private final ReusableTriggerService reusableTriggerService;
    private final SignalWaitRepository signalWaitRepository;
    private final EditorRunResolver editorRunResolver;
    private final ObjectMapper objectMapper;
    private final StepOutputService stepOutputService;
    private final WorkflowStepDataRepository stepDataRepository;
    private final WorkflowEpochService epochService;

    // P2.2 site 8 - optional Redis running overlay for agent-tool node-status queries.
    // Pre-P2.3 elision: JSONB carries running, this overlay is additive (small Redis-up cost).
    // Post-elision: JSONB running is empty; Redis is the only source. Optional injection so
    // existing unit tests construct the service via @RequiredArgsConstructor without changes.
    @Autowired(required = false)
    private RunningNodeTracker runningNodeTracker;

    // ==================== Run Creation (editor-style) ====================

    /**
     * Find or create a run for agent-initiated execution.
     *
     * <p>Delegates to {@link EditorRunResolver} for centralized find-or-create logic:
     * <ul>
     *   <li>Same version + existing live run (WAITING_TRIGGER between fires, or RUNNING/PAUSED
     *       mid-epoch) in the same execution mode → reuse (epoch accumulation)</li>
     *   <li>Otherwise → create new run marked as {@code __editorRun__}</li>
     * </ul>
     *
     * @param workflow   the workflow entity
     * @param plan       the workflow plan (from latest saved state)
     * @param dataInputs initial data inputs for execution
     * @param tenantId   the tenant performing the execution
     * @return the resolved (reused or newly created) run
     */
    public WorkflowRunEntity createRun(WorkflowEntity workflow, WorkflowPlan plan,
                                       Map<String, Object> dataInputs, String tenantId) {
        return createRun(workflow, plan, dataInputs, tenantId, null);
    }

    /**
     * Overload with the run-level mock override ({@code mockMode}: null = default -
     * enabled node mocks apply; "off" = ignore all mocks this run; "all_mcp" =
     * catalog-example dry-run). Reconciled onto {@code __mockMode__} run metadata
     * by {@code EditorRunResolver} on create AND reuse.
     */
    public WorkflowRunEntity createRun(WorkflowEntity workflow, WorkflowPlan plan,
                                       Map<String, Object> dataInputs, String tenantId,
                                       String mockMode) {
        // EditorRunResolver handles version resolution, run reuse, creation, snapshotting, and __editorRun__ marking.
        // No extra work needed here - createExecution() inside the resolver already snapshots interfaces.
        return editorRunResolver.findOrCreateRun(workflow, plan, dataInputs, tenantId,
                com.apimarketplace.orchestrator.domain.workflow.ExecutionMode.AUTOMATIC, mockMode).runEntity();
    }

    /**
     * Find or create an editor-style run bound to a specific historical plan version.
     *
     * <p>Unlike {@link #createRun}, this variant does NOT create a new plan version - it
     * targets the version already stored in {@code workflow_plan_versions} and runs the
     * frozen plan as an editor replay. Used when the agent explicitly passes
     * {@code version=N} to {@code workflow(action='execute')}.
     *
     * @param workflow       the workflow entity
     * @param versionedPlan  the parsed plan loaded from that specific version
     * @param planVersion    the target version number
     * @param dataInputs     initial data inputs
     * @param tenantId       the user ID
     * @return the resolved run entity
     */
    public WorkflowRunEntity createRunForVersion(WorkflowEntity workflow, WorkflowPlan versionedPlan,
                                                  int planVersion, Map<String, Object> dataInputs,
                                                  String tenantId) {
        return createRunForVersion(workflow, versionedPlan, planVersion, dataInputs, tenantId, null);
    }

    /** Overload with the run-level mock override - same semantics as {@link #createRun}. */
    public WorkflowRunEntity createRunForVersion(WorkflowEntity workflow, WorkflowPlan versionedPlan,
                                                  int planVersion, Map<String, Object> dataInputs,
                                                  String tenantId, String mockMode) {
        return editorRunResolver.findOrCreateRunForVersion(workflow, versionedPlan, planVersion, dataInputs,
                tenantId, com.apimarketplace.orchestrator.domain.workflow.ExecutionMode.AUTOMATIC, mockMode).runEntity();
    }

    // ==================== Trigger Resolution ====================

    /**
     * Resolve which trigger to fire on the plan.
     *
     * <p>When a single fireable trigger exists, {@code triggerIdHint} is optional (auto-resolved).
     * When multiple fireable triggers exist, {@code triggerIdHint} is <b>mandatory</b> - the agent
     * must specify which trigger to fire.
     *
     * @param plan          the workflow plan
     * @param triggerIdHint normalized key hint (e.g. "trigger:my_webhook").
     *                      Required when multiple fireable triggers exist.
     * @return the resolved Trigger
     * @throws IllegalArgumentException if no fireable trigger is found or trigger_id is missing
     */
    public Trigger resolveTrigger(WorkflowPlan plan, String triggerIdHint) {
        List<Trigger> triggers = plan.getTriggers();
        if (triggers == null || triggers.isEmpty()) {
            throw new IllegalArgumentException("Workflow has no triggers defined.");
        }

        List<Trigger> fireable = triggers.stream()
                .filter(t -> isFireableByAgent(TriggerType.fromString(t.type())))
                .toList();

        if (fireable.isEmpty()) {
            throw new IllegalArgumentException(
                    "No agent-fireable trigger found (workflow/error triggers cannot be fired manually). " +
                    "Available: " + listTriggerIds(triggers));
        }

        if (triggerIdHint != null && !triggerIdHint.isBlank()) {
            // Always normalize the label portion (handles accents, spaces, casing)
            String rawLabel = triggerIdHint.startsWith("trigger:")
                    ? triggerIdHint.substring("trigger:".length())
                    : triggerIdHint;
            String normalized = "trigger:" + LabelNormalizer.normalizeLabel(rawLabel);

            // Search fireable triggers only - agent cannot fire workflow/error triggers
            return fireable.stream()
                    .filter(t -> normalized.equals(t.getNormalizedKey()))
                    .findFirst()
                    .orElseThrow(() -> {
                        // Check if it matched a non-fireable trigger for a better error message
                        boolean matchesNonFireable = triggers.stream()
                                .anyMatch(t -> normalized.equals(t.getNormalizedKey())
                                        && !isFireableByAgent(TriggerType.fromString(t.type())));
                        String msg = matchesNonFireable
                                ? "Trigger '" + triggerIdHint + "' exists but is not agent-fireable " +
                                  "(workflow/error triggers are system-only). "
                                : "Trigger not found: " + triggerIdHint + ". ";
                        return new IllegalArgumentException(
                                msg + "Fireable triggers: " + listFireableTriggerIds(fireable));
                    });
        }

        // Single fireable trigger - auto-resolve
        if (fireable.size() == 1) {
            return fireable.get(0);
        }

        // Multiple fireable triggers - trigger_id is mandatory
        throw new IllegalArgumentException(
                "Multiple fireable triggers found. You must specify trigger_id. " +
                "Available: " + listFireableTriggerIds(fireable));
    }

    /**
     * Validate trigger type is agent-fireable. Throws for workflow/error types.
     */
    public void validateFireable(TriggerType type) {
        if (!isFireableByAgent(type)) {
            throw new IllegalArgumentException(
                    "Cannot manually fire '" + type.getValue() + "' triggers. " +
                    "They are fired automatically by the system (workflow completion / failure). " +
                    "Fireable types: manual, chat, form, datasource, webhook, schedule.");
        }
    }

    private boolean isFireableByAgent(TriggerType type) {
        return type != TriggerType.WORKFLOW && type != TriggerType.ERROR;
    }

    /**
     * True when every trigger in the plan is non-agent-fireable (workflow / error).
     *
     * <p>For these workflows the agent's {@code execute} call cannot fire anything, but it
     * still needs to seed an initial WAITING_TRIGGER run so the dispatcher can attach future
     * parent-workflow events. Callers (WorkflowBuilderProvider.executeWorkflow) detect this
     * shape, skip {@link #resolveTrigger}, and return a {@code BOOTSTRAP_RUN_READY} success
     * with the seed run id instead of throwing "No agent-fireable trigger found".
     *
     * <p>Returns {@code false} when the plan has no triggers (caller emits its own error)
     * or when at least one trigger is agent-fireable (normal fire path applies).
     */
    public boolean hasOnlyBootstrapTriggers(WorkflowPlan plan) {
        if (plan == null) return false;
        List<Trigger> triggers = plan.getTriggers();
        if (triggers == null || triggers.isEmpty()) return false;
        return triggers.stream()
                .allMatch(t -> !isFireableByAgent(TriggerType.fromString(t.type())));
    }

    // ==================== Execution ====================

    /**
     * Fire a trigger on the given run.
     *
     * <p>The agent acts as an instant tester: it can simulate any trigger type with
     * custom data. Payload is validated per trigger type with helpful error messages
     * that guide the agent to self-correct.
     *
     * @param run     the run to fire on
     * @param trigger the trigger to fire
     * @param payload the trigger data (agent-provided simulation data)
     * @return the execution result
     * @throws IllegalArgumentException if payload is invalid for the trigger type
     */
    public TriggerExecutionResult fire(WorkflowRunEntity run, Trigger trigger,
                                       Map<String, Object> payload) {
        TriggerType type = TriggerType.fromString(trigger.type());
        validateFireable(type);
        validatePayload(type, trigger, payload);
        String triggerId = trigger.getNormalizedKey();
        // Agent-supplied payload is LLM-controlled and adversarial-shaped -
        // strip the internal plan-control marker so an agent can't tell the
        // engine to skip its workflow.plan refresh.
        Map<String, Object> sanitized =
                com.apimarketplace.orchestrator.trigger.ReusableTriggerService.sanitizePlanMarker(payload);
        log.info("[AgentFire] Firing trigger={} type={} on run={}", triggerId, type, run.getRunIdPublic());
        return reusableTriggerService.executeTrigger(run, triggerId, type, sanitized);
    }

    /**
     * Validate the agent-provided payload against the trigger type's requirements.
     *
     * <p>Validation is intentionally lenient - the agent is a tester and can provide
     * any data it wants. We only reject payloads that would cause silent runtime
     * failures, and errors clearly describe what's expected so the agent can retry.
     */
    @SuppressWarnings("unchecked")
    void validatePayload(TriggerType type, Trigger trigger, Map<String, Object> payload) {
        Map<String, Object> data = payload != null ? payload : Map.of();

        switch (type) {
            case CHAT -> {
                if (!data.containsKey("message") || !(data.get("message") instanceof String msg) || msg.isBlank()) {
                    throw new IllegalArgumentException(
                            "Chat trigger requires a 'message' field (non-empty string). " +
                            "Example: data_inputs={\"message\": \"Hello, start the workflow\"}");
                }
            }
            case FORM -> {
                // Extract expected fields from trigger params for guidance
                Map<String, Object> params = trigger.params() != null ? trigger.params() : Map.of();
                Object fieldsRaw = params.get("fields");
                if (fieldsRaw instanceof List<?> fieldsList && !fieldsList.isEmpty()) {
                    List<String> requiredFields = new ArrayList<>();
                    List<String> allFields = new ArrayList<>();
                    for (Object fieldObj : fieldsList) {
                        if (fieldObj instanceof Map<?, ?> field) {
                            String name = (String) field.get("name");
                            if (name == null) continue;
                            allFields.add(name);
                            Object req = field.get("required");
                            if (Boolean.TRUE.equals(req) || "true".equalsIgnoreCase(String.valueOf(req))) {
                                requiredFields.add(name);
                            }
                        }
                    }
                    // Check required fields are present with non-empty values
                    List<String> missing = requiredFields.stream()
                            .filter(f -> {
                                Object val = data.get(f);
                                return val == null || (val instanceof String s && s.isBlank());
                            })
                            .toList();
                    if (!missing.isEmpty()) {
                        throw new IllegalArgumentException(
                                "Form trigger has required fields missing: " + missing +
                                ". All form fields: " + allFields +
                                ". Example: data_inputs={" +
                                allFields.stream()
                                        .map(f -> "\"" + f + "\": \"<value>\"")
                                        .collect(Collectors.joining(", ")) +
                                "}");
                    }
                }
                // No fields defined or all optional - any payload is fine
            }
            case WEBHOOK -> {
                // Webhook accepts any JSON - no validation needed.
                // The agent simulates the external caller and provides whatever data it wants.
            }
            case MANUAL, SCHEDULE, DATASOURCE -> {
                // These accept any payload (optional data). No validation needed.
            }
            default -> {
                // Unknown type - let it through, ReusableTriggerService will handle it
            }
        }
    }

    // ==================== Result Building ====================

    /**
     * Build the structured result to return to the agent after trigger execution.
     *
     * <p>Scoped to (run_id, trigger_id, epoch). Includes:
     * <ul>
     *   <li>Epoch info (epoch number, total fire count, plan version)</li>
     *   <li>Status: COMPLETED / FAILED / PARTIAL_SUCCESS / AWAITING_INPUT</li>
     *   <li>Terminal node statuses (nodes with no outgoing edges)</li>
     *   <li>Error info if failed</li>
     *   <li>Blocking signal info if awaiting user input</li>
     * </ul>
     */
    public Map<String, Object> buildResult(WorkflowRunEntity originalRun,
                                           TriggerExecutionResult triggerResult,
                                           WorkflowEntity workflow,
                                           WorkflowPlan plan) {
        return buildResult(originalRun, triggerResult, workflow, plan, null);
    }

    public Map<String, Object> buildResult(WorkflowRunEntity originalRun,
                                           TriggerExecutionResult triggerResult,
                                           WorkflowEntity workflow,
                                           WorkflowPlan plan,
                                           String tenantId) {
        // Re-fetch to get post-execution state (status, metadata, stateSnapshot updated)
        WorkflowRunEntity run = runRepository.findByRunIdPublic(originalRun.getRunIdPublic())
                .orElse(originalRun);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run_id", run.getRunIdPublic());
        result.put("trigger_id", triggerResult.triggerId());
        result.put("epoch", triggerResult.epoch());

        // Fire count = epoch + 1 (epoch is 0-based index of the last completed epoch)
        result.put("fire_count", triggerResult.epoch() + 1);
        result.put("plan_version", run.getPlanVersion());
        result.put("pinned_version", workflow.getPinnedVersion());

        // Determine status
        String status = resolveStatus(run, triggerResult);
        result.put("status", status);

        // Duration from metadata lastCycleAt vs run startedAt - use stateSnapshot totalDurationMs
        addDuration(result, run);

        // All node statuses with output/error data
        Map<String, Object> nodeReport = buildAllNodeStatuses(
                run, plan, triggerResult.epoch(), tenantId);
        if (!nodeReport.isEmpty()) {
            result.putAll(nodeReport);
        }

        // Mock-mode visibility: which nodes served configured mocks instead of
        // really executing (anti "forgot a mock was active" guard - zero noise
        // when no mock is in play).
        addMockInfo(result, run, plan);

        // Top-level error from trigger failure (not a node error)
        if (("FAILED".equals(status) || "PARTIAL_SUCCESS".equals(status))
                && !triggerResult.success() && triggerResult.message() != null) {
            result.put("error", triggerResult.message());
        }

        // Blocking signal info
        if ("AWAITING_INPUT".equals(status)) {
            addBlockingSignalInfo(result, run);
        }

        // JIT zoom hint - guide the agent to inspect the run progressively.
        result.put("NEXT", "workflow(action='get_run', run_id='" + run.getRunIdPublic() +
                "') for macro overview (epochs + node statuses). " +
                "Then workflow(action='get_run', run_id='" + run.getRunIdPublic() +
                "', epoch=" + triggerResult.epoch() + ") to list nodes for this epoch. " +
                "Then workflow(action='get_node_output', run_id='" + run.getRunIdPublic() +
                "', epoch=" + triggerResult.epoch() + ", node_id='<node_id>') to zoom into a specific node's full input/output/error. " +
                "Use the deepest level only for nodes you actually need - don't fetch the whole epoch when one node is enough.");

        return result;
    }

    /**
     * Surfaces the run's mock state on the execute/get_run/wait_run report:
     * {@code mock_mode} + {@code mocked_nodes} (normalized keys of the nodes whose
     * output is a configured mock). OMITTED entirely when no mock is in play -
     * normal runs stay byte-identical. Derived from the frozen run plan + run
     * metadata (static view - the authoritative per-row flag is {@code mocked:true}
     * on get_node_output).
     */
    private void addMockInfo(Map<String, Object> result, WorkflowRunEntity run, WorkflowPlan plan) {
        Map<String, Object> metadata = run.getMetadata();
        boolean editorRun = metadata != null && Boolean.TRUE.equals(metadata.get("__editorRun__"));
        if (!editorRun || plan == null) {
            return; // production fires never apply mocks - nothing to report
        }
        Map<String, NodeMock> mocks = plan.getNodeMocks();
        List<String> enabledMocks = mocks.entrySet().stream()
                .filter(e -> e.getValue().isEffective())
                .map(Map.Entry::getKey)
                .sorted()
                .collect(Collectors.toList());
        Object modeRaw = metadata.get(com.apimarketplace.orchestrator.execution.v2.engine.MockRunGate.MOCK_MODE_METADATA_KEY);
        String mode = modeRaw instanceof String s ? s : "default";

        if ("off".equals(mode)) {
            if (!enabledMocks.isEmpty()) {
                result.put("mock_mode", "off");
                result.put("mock_note", "This run IGNORED the " + enabledMocks.size()
                        + " configured node mock(s) (mock_mode='off') - every node executed for real.");
            }
            return;
        }

        List<String> mockedNodes = new ArrayList<>(enabledMocks);
        if ("all_mcp".equals(mode)) {
            for (Step mcp : plan.getMcps()) {
                String key = mcp.getNormalizedKey();
                if (key != null && !mockedNodes.contains(key) && !mcp.isCrudStep()
                        && WorkflowPlanParser.isCatalogToolId(mcp.id())) {
                    mockedNodes.add(key);
                }
            }
            Collections.sort(mockedNodes);
            result.put("mock_mode", "all_mcp");
        } else {
            if (mockedNodes.isEmpty()) {
                return; // zero noise: no mocks configured, default mode
            }
            result.put("mock_mode", "default");
        }
        result.put("mocked_nodes", mockedNodes);
        result.put("mock_note", "Outputs of mocked_nodes are CONFIGURED MOCKS, not real executions "
                + "(get_node_output shows mocked=true on those rows). Re-run with mock_mode='off' to execute for real.");
    }

    /**
     * Build a macro-level report for a run (Phase 1 - no epoch param).
     * Returns run-level metadata + lightweight summaries for ALL epochs.
     * The agent can drill down with epoch=N to get detailed node outputs.
     */
    public Map<String, Object> buildRunMacroReport(WorkflowRunEntity run, WorkflowPlan plan, String tenantId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run_id", run.getRunIdPublic());
        result.put("status", run.getStatus().name());
        result.put("plan_version", run.getPlanVersion());
        result.put("execution_mode", run.getExecutionMode() != null ? run.getExecutionMode().name() : null);
        result.put("started_at", run.getStartedAt() != null ? run.getStartedAt().toString() : null);
        result.put("ended_at", run.getEndedAt() != null ? run.getEndedAt().toString() : null);
        addDuration(result, run);

        // DAG summary from snapshot (currentEpoch, fireCount, currentSpawn survive pruning)
        result.put("dags", buildDagsSummary(run));

        // Epoch summaries from persistent data
        List<Map<String, Object>> epochsList = buildEpochsMacroList(run.getRunIdPublic());
        result.put("epochs", epochsList);
        result.put("total_epochs", epochsList.size());

        // Blocking signal info if run is still active
        if (run.getStatus() == RunStatus.RUNNING || run.getStatus() == RunStatus.PAUSED) {
            long blockingCount = signalWaitRepository.countActiveBlockingByRunId(run.getRunIdPublic());
            if (blockingCount > 0) {
                addBlockingSignalInfo(result, run);
            }
        }

        result.put("NEXT", "workflow(action='get_run', run_id='" + run.getRunIdPublic() +
                "', epoch=N) to see all nodes with their status for a specific epoch. " +
                "Then workflow(action='get_node_output', run_id='" + run.getRunIdPublic() +
                "', epoch=N, node_id='<node_id>') to zoom into a specific node's full output.");
        return result;
    }

    /**
     * Build a detailed report for a specific epoch (Phase 2 - epoch=N).
     * Shows ALL nodes with status, label, and type - but NO output data.
     * Agent can drill into a specific node with get_node_output for full output.
     */
    public Map<String, Object> buildEpochDetailReport(WorkflowRunEntity run, WorkflowPlan plan,
                                                       int epoch, String tenantId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run_id", run.getRunIdPublic());
        result.put("epoch", epoch);
        result.put("status", run.getStatus().name());

        // Try persistent header first
        EpochState epochState = loadEpochState(run, epoch);
        if (epochState == null) {
            result.put("error", "Epoch " + epoch + " not found for run " + run.getRunIdPublic());
            result.put("hint", "Use workflow(action='get_run', run_id='" + run.getRunIdPublic() +
                    "') without epoch to see available epochs.");
            return result;
        }

        // Build lightweight node summary (all nodes, no output data)
        Map<String, NodeInfo> nodeInfoMap = buildNodeInfoMap(plan);
        List<Map<String, Object>> nodes = buildEpochNodeSummaries(epochState, plan, nodeInfoMap,
                run.getRunIdPublic(), epoch);
        result.put("nodes", nodes);

        result.put("NEXT", "workflow(action='get_node_output', run_id='" + run.getRunIdPublic() +
                "', epoch=" + epoch + ", node_id='<node_id>') to see full output/error for a specific node. " +
                "Or workflow(action='get_run', run_id='" + run.getRunIdPublic() + "') for macro overview.");
        return result;
    }

    /**
     * Build detailed output for a specific node in a specific epoch (Phase 3 - node zoom).
     * Returns the full output data, error details, and execution metadata for one node.
     */
    public Map<String, Object> buildNodeOutputReport(WorkflowRunEntity run, WorkflowPlan plan,
                                                      int epoch, String nodeId, String tenantId) {
        return buildNodeOutputReport(run, plan, epoch, nodeId, tenantId, null, null, null, null, null, null);
    }

    /** Split-aware overload without field-expand - delegates with no expand window. */
    public Map<String, Object> buildNodeOutputReport(WorkflowRunEntity run, WorkflowPlan plan,
                                                      int epoch, String nodeId, String tenantId,
                                                      Integer itemIndex, Integer iteration, Integer spawn) {
        return buildNodeOutputReport(run, plan, epoch, nodeId, tenantId, itemIndex, iteration, spawn, null, null, null);
    }

    /**
     * Build detailed output for a specific node - split-aware overload.
     *
     * <p>Behaviour depends on what the agent persisted for this {@code (runId, nodeId, epoch)} tuple
     * and on whether the agent passed per-item targeting filters:
     * <ul>
     *   <li><b>Zoom mode</b> - when the agent passes any of {@code itemIndex} / {@code iteration} /
     *       {@code spawn}, OR when only one persisted row exists. Returns the full {@code output},
     *       {@code resolved_params}, {@code error}, plus all routing/loop/merge/skip/identity fields
     *       carried by {@link WorkflowStepDataEntity}.</li>
     *   <li><b>List mode</b> - when more than one persisted row exists for the tuple and no filter
     *       was passed. Returns {@code execution_count}, {@code status_counts}, and a compact
     *       {@code items[]} array (one entry per persisted row) so the agent can pick which item
     *       to drill into next.</li>
     * </ul>
     *
     * <p>The persistence layer already writes one {@link WorkflowStepDataEntity} per
     * {@code (item_index, iteration, spawn)} tuple - this method just stops collapsing those
     * rows to one arbitrary entry via {@code findFirst()}.
     */
    public Map<String, Object> buildNodeOutputReport(WorkflowRunEntity run, WorkflowPlan plan,
                                                      int epoch, String nodeId, String tenantId,
                                                      Integer itemIndex, Integer iteration, Integer spawn,
                                                      String expandField, Integer offset, Integer maxBytes) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run_id", run.getRunIdPublic());
        result.put("epoch", epoch);
        result.put("node_id", nodeId);

        // Resolve label and type from plan
        Map<String, NodeInfo> nodeInfoMap = buildNodeInfoMap(plan);
        NodeInfo info = nodeInfoMap.get(nodeId);
        if (info != null) {
            result.put("label", info.label);
            result.put("type", info.type);
        }

        // Load epoch state to get node-level aggregated status
        EpochState epochState = loadEpochState(run, epoch);
        if (epochState == null) {
            result.put("error", "Epoch " + epoch + " not found for run " + run.getRunIdPublic());
            return result;
        }
        String status = resolveNodeStatusInEpoch(epochState, nodeId, redisRunningKeysFor(run.getRunIdPublic()));
        result.put("status", status);

        String runId = run.getRunIdPublic();

        // Load all persisted rows for this (runId, nodeId, epoch). One row per
        // (item_index, iteration, spawn). Empty list = node was status-tracked
        // in EpochState but produced no per-item event (e.g. propagated SKIP).
        List<WorkflowStepDataEntity> stepEntries = loadStepEntriesForEpoch(runId, nodeId, epoch);

        boolean hasFilter = itemIndex != null || iteration != null || spawn != null;
        if (hasFilter) {
            // Apply filters. Partial filters (e.g. iteration=2 alone) may select
            // more than one row - in that case we fall back to list mode of just
            // the matches, instead of silently picking an arbitrary one. The
            // agent then has to add another filter to disambiguate.
            List<WorkflowStepDataEntity> matches = stepEntries.stream()
                    .filter(s -> itemIndex == null || itemIndex.equals(s.getItemIndex()))
                    .filter(s -> iteration == null || iteration.equals(s.getIteration()))
                    .filter(s -> spawn == null || spawn.equals(s.getSpawn()))
                    .toList();
            if (matches.isEmpty()) {
                result.put("note", "No step data matched filters {item_index=" + itemIndex
                        + ", iteration=" + iteration + ", spawn=" + spawn
                        + "}. Call workflow(action='get_node_output', run_id='" + runId
                        + "', epoch=" + epoch + ", node_id='" + nodeId
                        + "') without filters to list available items.");
                result.put("execution_count", stepEntries.size());
                result.put("status_counts", computeStatusCounts(stepEntries));
                result.put("NEXT", "workflow(action='get_node_output', run_id='" + runId
                        + "', epoch=" + epoch + ", node_id='" + nodeId + "') to list items");
                return result;
            }
            if (matches.size() > 1) {
                // Filter is too loose - surface the matched rows so the agent can pick one.
                result.put("execution_count", matches.size());
                result.put("status_counts", computeStatusCounts(matches));
                List<Map<String, Object>> items = new ArrayList<>(matches.size());
                for (WorkflowStepDataEntity step : matches) {
                    items.add(buildItemSummary(step));
                }
                result.put("items", items);
                result.put("note", "Filter {item_index=" + itemIndex + ", iteration=" + iteration
                        + ", spawn=" + spawn + "} matched " + matches.size()
                        + " rows. Add another filter dimension to zoom into one.");
                result.put("NEXT", "workflow(action='get_node_output', run_id='" + runId
                        + "', epoch=" + epoch + ", node_id='" + nodeId
                        + "', item_index=N, iteration=K, spawn=S) - combine filters until one row matches.");
                return result;
            }
            enrichZoomFromStep(result, matches.get(0), tenantId, expandField, offset, maxBytes);
        } else if (stepEntries.size() > 1) {
            // List mode - surface the per-item breakdown the frontend already shows the user
            result.put("execution_count", stepEntries.size());
            result.put("status_counts", computeStatusCounts(stepEntries));
            List<Map<String, Object>> items = new ArrayList<>(stepEntries.size());
            for (WorkflowStepDataEntity step : stepEntries) {
                items.add(buildItemSummary(step));
            }
            result.put("items", items);
            result.put("NEXT", "workflow(action='get_node_output', run_id='" + runId + "', epoch="
                    + epoch + ", node_id='" + nodeId
                    + "', item_index=N) to zoom into one item's full output. "
                    + "Add iteration=N or spawn=N if multiple iterations or re-runs exist.");
            return result;
        } else if (stepEntries.size() == 1) {
            // Single-row zoom - same shape as before but with the new identity/routing fields
            enrichZoomFromStep(result, stepEntries.get(0), tenantId, expandField, offset, maxBytes);
        } else {
            result.put("output", null);
            result.put("note", "No step data found for this node in epoch " + epoch);
        }

        // Load error details if failed (preserves prior behaviour for FAILED status)
        if ("FAILED".equals(status) && !result.containsKey("error")) {
            enrichErrorMessage(result, runId, nodeId);
        }

        result.put("NEXT", "workflow(action='get_run', run_id='" + runId +
                "', epoch=" + epoch + ") to go back to epoch overview");
        return result;
    }

    // ==================== Macro Report Helpers ====================

    private Map<String, Object> buildDagsSummary(WorkflowRunEntity run) {
        Map<String, Object> dags = new LinkedHashMap<>();
        try {
            String snapshotJson = run.getStateSnapshot();
            if (snapshotJson != null && !snapshotJson.isBlank()) {
                StateSnapshot snapshot = objectMapper.readValue(snapshotJson, StateSnapshot.class);
                if (snapshot.getDags() != null) {
                    for (Map.Entry<String, DagState> entry : snapshot.getDags().entrySet()) {
                        DagState dag = entry.getValue();
                        Map<String, Object> dagInfo = new LinkedHashMap<>();
                        dagInfo.put("current_epoch", dag.getCurrentEpoch());
                        dagInfo.put("fire_count", dag.getFireCount());
                        dagInfo.put("current_spawn", dag.getCurrentSpawn());
                        dags.put(entry.getKey(), dagInfo);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[AgentFire] Could not build dags summary: {}", e.getMessage());
        }
        return dags;
    }

    private List<Map<String, Object>> buildEpochsMacroList(String runId) {
        List<Map<String, Object>> epochs = new ArrayList<>();
        try {
            List<EpochHeaderWithEpochRow> headers = epochService.listEpochHeaders(runId);
            for (EpochHeaderWithEpochRow header : headers) {
                Map<String, Object> epochInfo = new LinkedHashMap<>();
                epochInfo.put("epoch", header.epoch());
                epochInfo.put("trigger_id", header.triggerId());
                epochInfo.put("started_at", header.startedAt() != null ? header.startedAt().toString() : null);
                epochInfo.put("ended_at", header.closedAt() != null ? header.closedAt().toString() : null);
                if (header.durationMs() != null) {
                    epochInfo.put("duration_ms", header.durationMs());
                }

                // Derive node counts and epoch status from EpochState JSON
                EpochState state = deserializeEpochState(header.epochStateJson());
                if (state != null) {
                    Map<String, Object> counts = new LinkedHashMap<>();
                    counts.put("completed", state.getCompletedNodeIds().size());
                    counts.put("failed", state.getFailedNodeIds().size());
                    counts.put("skipped", state.getSkippedNodeIds().size());
                    epochInfo.put("node_counts", counts);
                    epochInfo.put("status", computeEpochStatus(state, header.isActive()));
                } else {
                    epochInfo.put("status", header.isActive() ? "ACTIVE" : "UNKNOWN");
                }

                epochs.add(epochInfo);
            }
        } catch (Exception e) {
            log.debug("[AgentFire] Could not build epochs macro list: {}", e.getMessage());
        }
        return epochs;
    }

    private String computeEpochStatus(EpochState state, boolean isActive) {
        if (isActive) return "ACTIVE";
        if (!state.getFailedNodeIds().isEmpty() && !state.getCompletedNodeIds().isEmpty()) return "PARTIAL_SUCCESS";
        if (!state.getFailedNodeIds().isEmpty()) return "FAILED";
        return "COMPLETED";
    }

    // ==================== Epoch Detail Helpers ====================

    /**
     * Load EpochState: try persistent header first, fallback to live snapshot for active epochs.
     */
    private EpochState loadEpochState(WorkflowRunEntity run, int epoch) {
        // Try persistent header
        EpochHeaderRow header = epochService.getEpochHeader(run.getRunIdPublic(), epoch);
        if (header != null && header.epochStateJson() != null) {
            return deserializeEpochState(header.epochStateJson());
        }

        // Fallback: live snapshot for active (not yet persisted) epochs
        return loadLiveEpochState(run, epoch);
    }

    /**
     * Fallback: load EpochState from live StateSnapshot for active epochs.
     */
    private EpochState loadLiveEpochState(WorkflowRunEntity run, int epoch) {
        try {
            String snapshotJson = run.getStateSnapshot();
            if (snapshotJson != null && !snapshotJson.isBlank()) {
                StateSnapshot snapshot = objectMapper.readValue(snapshotJson, StateSnapshot.class);
                if (snapshot.getDags() != null) {
                    for (DagState dag : snapshot.getDags().values()) {
                        if (dag.getEpochs() != null && dag.getEpochs().containsKey(epoch)) {
                            return dag.getEpochs().get(epoch);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[AgentFire] Could not load live epoch state: {}", e.getMessage());
        }
        return null;
    }

    private EpochState deserializeEpochState(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, EpochState.class);
        } catch (Exception e) {
            log.debug("[AgentFire] Could not deserialize EpochState: {}", e.getMessage());
            return null;
        }
    }

    // ==================== Epoch Node Summaries (lightweight, no output) ====================

    /**
     * Simple holder for node label and type derived from the plan.
     */
    private record NodeInfo(String label, String type) {}

    /**
     * Build a map of nodeId → NodeInfo (label + type) from the plan for quick lookup.
     */
    private Map<String, NodeInfo> buildNodeInfoMap(WorkflowPlan plan) {
        Map<String, NodeInfo> map = new LinkedHashMap<>();
        if (plan.getMcps() != null) {
            for (Step s : plan.getMcps()) {
                if (s != null && s.getNormalizedKey() != null)
                    map.put(s.getNormalizedKey(), new NodeInfo(s.label(), s.type() != null ? s.type() : "mcp"));
            }
        }
        if (plan.getAgents() != null) {
            for (Agent a : plan.getAgents()) {
                if (a != null && a.getNormalizedKey() != null)
                    map.put(a.getNormalizedKey(), new NodeInfo(a.label(), a.type() != null ? a.type() : "agent"));
            }
        }
        if (plan.getCores() != null) {
            for (Core c : plan.getCores()) {
                if (c != null && c.getNormalizedKey() != null)
                    map.put(c.getNormalizedKey(), new NodeInfo(c.label(), c.type()));
            }
        }
        if (plan.getTables() != null) {
            for (Step t : plan.getTables()) {
                if (t != null && t.getNormalizedKey() != null)
                    map.put(t.getNormalizedKey(), new NodeInfo(t.label(), t.type() != null ? t.type() : "table"));
            }
        }
        if (plan.getInterfaces() != null) {
            for (InterfaceDef i : plan.getInterfaces()) {
                if (i != null && i.getNormalizedKey() != null)
                    map.put(i.getNormalizedKey(), new NodeInfo(i.label(), "interface"));
            }
        }
        return map;
    }

    /**
     * Build lightweight summaries for ALL nodes in an epoch.
     *
     * <p>For each node in the epoch we surface the same per-item breakdown that
     * {@code useAggregatedSteps} surfaces to the user in the frontend: a single
     * node-level {@code status} plus, when more than one row was persisted (split
     * fan-out, loop iterations, spawn re-runs), an {@code execution_count} and
     * {@code status_counts} map. Without this the agent sees {@code status=COMPLETED}
     * for a classify node whose 32 items were all SKIPPED - technically true at the
     * node level, badly misleading at the item level.
     */
    private List<Map<String, Object>> buildEpochNodeSummaries(EpochState epochState, WorkflowPlan plan,
                                                                Map<String, NodeInfo> nodeInfoMap,
                                                                String runId, int epoch) {
        Set<String> allNodeIds = collectAllNonTriggerNodeIds(plan);
        if (allNodeIds.isEmpty()) return List.of();

        // P2.2 audit-fix - pre-load Redis-running set once outside the per-node loop
        // so we don't N+1 the Redis call. Preserves "PENDING" filter at line below
        // post-elision (without this, currently-running nodes would be invisibly
        // dropped from the listing per audit B finding).
        Set<String> redisRunning = redisRunningKeysFor(runId);
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (String nodeId : allNodeIds) {
            String status = resolveNodeStatusInEpoch(epochState, nodeId, redisRunning);
            // Skip nodes that were never reached (PENDING) to reduce noise
            if ("PENDING".equals(status)) continue;

            Map<String, Object> node = new LinkedHashMap<>();
            node.put("node_id", nodeId);
            NodeInfo info = nodeInfoMap.get(nodeId);
            if (info != null) {
                node.put("label", info.label);
                node.put("type", info.type);
            }
            node.put("status", status);

            // Per-item breakdown (split fan-out / loop iterations / spawn re-runs).
            // One query per non-PENDING node in the epoch - N+1 by design, not
            // batched. Acceptable because get_run epoch=N is agent-driven (low call
            // rate) and N is bounded by workflow plan size, not by run-time
            // fan-out. Replace with a GROUP BY (run_id, normalized_key, epoch,
            // status) batch query if call frequency grows.
            List<WorkflowStepDataEntity> stepEntries = loadStepEntriesForEpoch(runId, nodeId, epoch);
            if (!stepEntries.isEmpty()) {
                node.put("execution_count", stepEntries.size());
                if (stepEntries.size() > 1) {
                    node.put("status_counts", computeStatusCounts(stepEntries));
                }
            }

            // For failed nodes, include short error summary inline
            if ("FAILED".equals(status)) {
                enrichErrorMessage(node, runId, nodeId);
            }

            nodes.add(node);
        }
        return nodes;
    }

    // ==================== Split-aware step loaders & enrichment ====================

    /**
     * Load every persisted row for {@code (runId, nodeId, epoch)} and order them
     * deterministically by {@code (itemIndex, iteration, spawn)} so the agent
     * always sees the same sequence between calls.
     *
     * <p>One row per execution event - split fan-out emits one row per item,
     * loops emit one row per iteration, re-runs emit one row per spawn. The
     * historical {@code findFirst()} on this list was the source of the
     * "1 arbitrary item visible out of 32" bug.
     */
    private List<WorkflowStepDataEntity> loadStepEntriesForEpoch(String runId, String nodeId, int epoch) {
        try {
            return stepDataRepository.findByRunIdAndNormalizedKeyOrderByEpochDesc(runId, nodeId).stream()
                    .filter(s -> s.getEpoch() != null && s.getEpoch() == epoch)
                    .sorted(Comparator
                            .comparing((WorkflowStepDataEntity s) -> s.getItemIndex() != null ? s.getItemIndex() : 0)
                            .thenComparing(s -> s.getIteration() != null ? s.getIteration() : 0)
                            .thenComparing(s -> s.getSpawn() != null ? s.getSpawn() : 0))
                    .toList();
        } catch (Exception e) {
            log.debug("[AgentFire] Could not load step entries for {}/{} epoch {}: {}",
                    runId, nodeId, epoch, e.getMessage());
            return List.of();
        }
    }

    /**
     * Count occurrences by status across all rows. Status names are surfaced
     * lowercased ({@code completed}, {@code failed}, {@code skipped}, …) to match
     * the frontend hook {@code useAggregatedSteps.statusCounts} contract.
     */
    private Map<String, Long> computeStatusCounts(List<WorkflowStepDataEntity> entries) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (WorkflowStepDataEntity step : entries) {
            String s = step.getStatus();
            if (s == null || s.isBlank()) continue;
            String key = s.toLowerCase(Locale.ROOT);
            counts.merge(key, 1L, Long::sum);
        }
        return counts;
    }

    /**
     * Compact summary for one persisted row, used in {@code items[]} of list
     * mode. Surfaces identity ({@code item_index}, {@code iteration},
     * {@code spawn}, {@code item_id}, {@code item_number}), status, error,
     * timing, and routing-decision fields when present. Excludes the
     * {@code output} payload - the agent fetches that with a follow-up zoom
     * call to keep list payloads bounded.
     */
    private Map<String, Object> buildItemSummary(WorkflowStepDataEntity step) {
        Map<String, Object> item = new LinkedHashMap<>();
        if (step.getItemIndex() != null)    item.put("item_index", step.getItemIndex());
        // Hide iteration / spawn when 0 to keep the payload signal-dense for the
        // common case (no loop, no re-run): the persistence layer initialises both
        // to 0 by default (WorkflowStepDataEntity.iteration/spawn = 0) so non-zero
        // means "this row is one of N iterations / re-runs and the agent needs the
        // dimension to disambiguate". Zero adds noise without information.
        if (step.getIteration() != null && step.getIteration() != 0) item.put("iteration", step.getIteration());
        if (step.getSpawn() != null && step.getSpawn() != 0)         item.put("spawn", step.getSpawn());
        if (step.getItemId() != null)       item.put("item_id", step.getItemId());
        if (step.getItemNumber() != null)   item.put("item_number", step.getItemNumber());
        if (step.getStatus() != null)       item.put("status", step.getStatus());
        if (step.getErrorMessage() != null && !step.getErrorMessage().isBlank()) {
            item.put("error", step.getErrorMessage());
        }
        if (step.getStartTime() != null)    item.put("started_at", step.getStartTime().toString());
        if (step.getEndTime() != null)      item.put("ended_at", step.getEndTime().toString());
        if (step.getStartTime() != null && step.getEndTime() != null) {
            item.put("duration_ms", java.time.Duration.between(step.getStartTime(), step.getEndTime()).toMillis());
        }
        if (step.getSelectedBranch() != null)   item.put("selected_branch", step.getSelectedBranch());
        if (step.getConditionResult() != null)  item.put("condition_result", step.getConditionResult());
        if (step.getLoopIteration() != null)    item.put("loop_iteration", step.getLoopIteration());
        if (step.getSkipReason() != null && !step.getSkipReason().isBlank()) {
            item.put("skip_reason", step.getSkipReason());
        }
        return item;
    }

    /**
     * Populate the zoom-mode payload from a single persisted row. Pulls the
     * full output blob from {@link StepOutputService} (only the megabyte-scale
     * inline strings get capped - large lists like 50 CRUD rows pass through),
     * surfaces the resolved input parameters, and adds every routing /
     * loop / merge / skip / identity field stored on
     * {@link WorkflowStepDataEntity}. These are exactly the fields the
     * frontend's {@code useStepData} hook already consumes for the user UI;
     * this method makes them reachable from the agent surface too.
     */
    private void enrichZoomFromStep(Map<String, Object> result, WorkflowStepDataEntity step, String tenantId,
                                    String expandField, Integer offset, Integer maxBytes) {
        // Identity dimensions - what makes this row unique within the epoch
        if (step.getItemIndex() != null)  result.put("item_index", step.getItemIndex());
        if (step.getIteration() != null && step.getIteration() != 0)  result.put("iteration", step.getIteration());
        if (step.getSpawn() != null && step.getSpawn() != 0)          result.put("spawn", step.getSpawn());
        if (step.getItemId() != null)     result.put("item_id", step.getItemId());
        if (step.getItemNumber() != null) result.put("item_number", step.getItemNumber());

        if (step.getNodeType() != null) result.put("node_type", step.getNodeType().name());
        if (step.getToolId() != null)   result.put("tool_id", step.getToolId());
        if (step.getHttpStatus() != null) result.put("http_status", step.getHttpStatus());

        // Per-row execution status (may differ from node-level aggregated status - that's the point)
        if (step.getStatus() != null) result.put("item_status", step.getStatus());

        // Output payload (cap inline binaries, keep list structure intact)
        if (tenantId != null && step.getOutputStorageId() != null) {
            try {
                Map<String, Object> rawOutput = stepOutputService.loadRawOutput(
                        step.getOutputStorageId(), tenantId);
                if (rawOutput != null && !rawOutput.isEmpty()) {
                    // Mock badge: this row's output is a configured mock, not a real
                    // execution (persisted __mocked__/__mock_source__ markers).
                    if (Boolean.TRUE.equals(rawOutput.get(
                            com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys.MOCKED))) {
                        result.put("mocked", true);
                        Object mockSource = rawOutput.get(
                                com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys.MOCK_SOURCE);
                        if (mockSource != null) {
                            result.put("mock_source", mockSource);
                        }
                    }
                    if (expandField != null && !expandField.isBlank()) {
                        // Expand mode: window one output field's full text value (files.view-style),
                        // so the agent can page past the 128 KB preview cap.
                        result.put("output_field", windowOutputField(result, step, rawOutput, expandField, offset, maxBytes));
                    } else {
                        // Default: capped output map + a NEXT expand pointer on each truncated text field.
                        Map<String, Object> capped = capLargeStringsRecursive(rawOutput);
                        addExpandHints(capped, result, step);
                        result.put("output", capped);
                    }
                }
            } catch (Exception e) {
                log.debug("[AgentFire] Could not load output for step {}: {}", step.getId(), e.getMessage());
            }
        }

        if (step.getInputData() != null && !step.getInputData().isEmpty()) {
            result.put("resolved_params", step.getInputData());
        }

        if (step.getErrorMessage() != null && !step.getErrorMessage().isBlank()) {
            result.put("error", step.getErrorMessage());
        }

        // Routing decisions - agent currently has zero visibility on these
        if (step.getSelectedBranch() != null)      result.put("selected_branch", step.getSelectedBranch());
        if (step.getConditionExpression() != null) result.put("condition_expression", step.getConditionExpression());
        if (step.getConditionResult() != null)     result.put("condition_result", step.getConditionResult());

        // Loop detail
        if (step.getLoopId() != null)         result.put("loop_id", step.getLoopId());
        if (step.getLoopIteration() != null)  result.put("loop_iteration", step.getLoopIteration());
        if (step.getLoopExitReason() != null) result.put("loop_exit_reason", step.getLoopExitReason());

        // Merge detail
        if (step.getMergeStrategy() != null) result.put("merge_strategy", step.getMergeStrategy());
        if (step.getMergeReceivedBranches() != null && !step.getMergeReceivedBranches().isEmpty()) {
            result.put("merge_received_branches", step.getMergeReceivedBranches());
        }
        if (step.getMergeSkippedBranches() != null && !step.getMergeSkippedBranches().isEmpty()) {
            result.put("merge_skipped_branches", step.getMergeSkippedBranches());
        }

        // Skip lineage - the only way for the agent to know WHY this row was skipped
        if (step.getSkipReason() != null && !step.getSkipReason().isBlank()) {
            result.put("skip_reason", step.getSkipReason());
        }
        if (step.getSkipSourceNode() != null) result.put("skip_source_node", step.getSkipSourceNode());

        // Timing
        if (step.getStartTime() != null) result.put("started_at", step.getStartTime().toString());
        if (step.getEndTime() != null)   result.put("ended_at", step.getEndTime().toString());
        if (step.getStartTime() != null && step.getEndTime() != null) {
            result.put("duration_ms", java.time.Duration.between(step.getStartTime(), step.getEndTime()).toMillis());
        }
    }

    /**
     * Resolve the status of a node within a specific epoch.
     *
     * <p>P2.2 site 8: terminal sets (completed/failed/skipped) are JSONB-authoritative
     * regardless of elision. The running check falls back to "PENDING" post-P2.3 if
     * the caller doesn't supply Redis state, which is agent-acceptable (less informative
     * but never wrong). Callers wanting RUNNING accuracy post-elision should overlay
     * via {@link #resolveNodeStatusInEpoch(EpochState, String, java.util.Set)} below.
     */
    private String resolveNodeStatusInEpoch(EpochState epochState, String nodeId) {
        return resolveNodeStatusInEpoch(epochState, nodeId, Set.of());
    }

    /**
     * Pre-load the Redis-running keyset for a runId once per agent-tool query so the
     * per-node {@link #resolveNodeStatusInEpoch(EpochState, String, Set)} overlay
     * doesn't N+1 Redis. Returns an empty set when the tracker bean is absent
     * (legacy unit-test wiring) or Redis is unavailable (fail-OPEN).
     */
    private Set<String> redisRunningKeysFor(String runId) {
        if (runningNodeTracker == null) return Set.of();
        Map<String, Integer> counts = runningNodeTracker.getRunningCountsAcrossEpochs(runId);
        if (counts.isEmpty()) return Set.of();
        Set<String> keys = new HashSet<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                keys.add(entry.getKey());
            }
        }
        return keys;
    }

    /**
     * Resolve node status with an explicit Redis-running overlay (P2.2 site 8). The
     * overlay is checked BEFORE the JSONB running set so that post-P2.3 elision the
     * caller's Redis read is authoritative.
     */
    private String resolveNodeStatusInEpoch(EpochState epochState, String nodeId, Set<String> redisRunning) {
        if (epochState.getCompletedNodeIds().contains(nodeId)) return "COMPLETED";
        if (epochState.getFailedNodeIds().contains(nodeId)) return "FAILED";
        if (epochState.getSkippedNodeIds().contains(nodeId)) return "SKIPPED";
        if (redisRunning.contains(nodeId) || epochState.getRunningNodeIds().contains(nodeId)) return "RUNNING";
        if (epochState.getAwaitingSignalNodeIds().contains(nodeId)) return "AWAITING_SIGNAL";
        return "PENDING";
    }

    private String resolveStatus(WorkflowRunEntity run, TriggerExecutionResult triggerResult) {
        if (!triggerResult.success()) return "FAILED";

        // Run back to WAITING_TRIGGER → cycle completed; use lastCycleResult from metadata
        if (run.getStatus() == RunStatus.WAITING_TRIGGER || run.getStatus() == RunStatus.COMPLETED) {
            Map<String, Object> meta = run.getMetadata();
            if (meta != null && meta.containsKey("lastCycleResult")) {
                return ((String) meta.get("lastCycleResult")).toUpperCase();
            }
            return "COMPLETED";
        }

        // Still RUNNING → blocking signal or async execution in progress
        if (run.getStatus() == RunStatus.RUNNING) {
            long blockingCount = signalWaitRepository.countActiveBlockingByRunId(run.getRunIdPublic());
            if (blockingCount > 0) return "AWAITING_INPUT";
            return "RUNNING";
        }

        return run.getStatus().name();
    }

    private void addDuration(Map<String, Object> result, WorkflowRunEntity run) {
        try {
            String snapshotJson = run.getStateSnapshot();
            if (snapshotJson != null && !snapshotJson.isBlank()) {
                Map<?, ?> snap = objectMapper.readValue(snapshotJson, Map.class);
                Object dur = snap.get("totalDurationMs");
                if (dur instanceof Number) {
                    result.put("duration_ms", ((Number) dur).longValue());
                }
            }
        } catch (Exception e) {
            log.debug("[AgentFire] Could not read duration from stateSnapshot: {}", e.getMessage());
        }
    }

    /**
     * Build a complete report of all non-trigger node statuses for this epoch.
     *
     * <p>Returns a map with:
     * <ul>
     *   <li>{@code outputs} - COMPLETED terminal nodes with actual output data</li>
     *   <li>{@code errors} - FAILED nodes with error messages</li>
     *   <li>{@code skipped} - SKIPPED node IDs (downstream of a failure)</li>
     *   <li>{@code awaiting_signal} - nodes waiting for user action</li>
     *   <li>{@code running} - nodes still executing</li>
     * </ul>
     *
     * <p>This gives the agent a full picture of the execution: what succeeded,
     * what failed (and why), what was skipped as a consequence, and what is
     * still pending user input.
     */
    private Map<String, Object> buildAllNodeStatuses(WorkflowRunEntity run,
                                                      WorkflowPlan plan,
                                                      int epoch,
                                                      String tenantId) {
        Set<String> allNodeIds = collectAllNonTriggerNodeIds(plan);
        if (allNodeIds.isEmpty()) return Map.of();

        Set<String> terminalIds = computeTerminalNodeIds(plan);

        Set<String> completed = Set.of();
        Set<String> failed = Set.of();
        Set<String> skipped = Set.of();
        Set<String> running = Set.of();
        Set<String> awaitingSignal = Set.of();

        try {
            String snapshotJson = run.getStateSnapshot();
            if (snapshotJson != null && !snapshotJson.isBlank()) {
                StateSnapshot snapshot = objectMapper.readValue(snapshotJson, StateSnapshot.class);
                completed = snapshot.getCompletedNodeIds();
                failed = snapshot.getFailedNodeIds();
                skipped = snapshot.getSkippedNodeIds();
                running = snapshot.getRunningNodeIds();
                awaitingSignal = snapshot.getAwaitingSignalNodeIds();
            }
        } catch (Exception e) {
            log.debug("[AgentFire] Could not parse StateSnapshot for node statuses: {}", e.getMessage());
        }

        // P2.2 site 8: overlay Redis running on top of JSONB via the shared helper.
        // Pre-elision additive, post-elision Redis becomes the only running source.
        // Fail-OPEN: helper returns empty set on tracker absent / Redis down.
        Set<String> redisRunning = redisRunningKeysFor(run.getRunIdPublic());
        if (!redisRunning.isEmpty()) {
            Set<String> mergedRunning = new HashSet<>(running);
            mergedRunning.addAll(redisRunning);
            running = mergedRunning;
        }

        String runId = run.getRunIdPublic();
        Map<String, Object> report = new LinkedHashMap<>();

        // 1. outputs - completed terminal nodes with their actual output data
        List<Map<String, Object>> outputs = new ArrayList<>();
        for (String nodeId : allNodeIds) {
            if (!completed.contains(nodeId)) continue;
            if (!terminalIds.contains(nodeId)) continue;
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("node_id", nodeId);
            node.put("status", "COMPLETED");
            enrichNodeWithStepData(node, runId, nodeId, epoch, tenantId);
            outputs.add(node);
        }
        if (!outputs.isEmpty()) {
            report.put("outputs", outputs);
        }

        // 2. errors - all failed nodes with error messages
        List<Map<String, Object>> errors = new ArrayList<>();
        for (String nodeId : allNodeIds) {
            if (!failed.contains(nodeId)) continue;
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("node", nodeId);
            enrichErrorMessage(err, runId, nodeId);
            errors.add(err);
        }
        if (!errors.isEmpty()) {
            report.put("errors", errors);
        }

        // 3. skipped - downstream nodes that didn't run because a predecessor failed
        List<String> skippedList = allNodeIds.stream()
                .filter(skipped::contains)
                .toList();
        if (!skippedList.isEmpty()) {
            report.put("skipped", skippedList);
        }

        // 4. awaiting_signal - nodes paused waiting for user/external action
        List<String> awaitingList = allNodeIds.stream()
                .filter(awaitingSignal::contains)
                .toList();
        if (!awaitingList.isEmpty()) {
            report.put("awaiting_signal", awaitingList);
        }

        // 5. running - nodes currently executing
        List<String> runningList = allNodeIds.stream()
                .filter(running::contains)
                .toList();
        if (!runningList.isEmpty()) {
            report.put("running", runningList);
        }

        return report;
    }

    /**
     * Collect all non-trigger node IDs from the plan.
     */
    private Set<String> collectAllNonTriggerNodeIds(WorkflowPlan plan) {
        Set<String> ids = new LinkedHashSet<>();
        if (plan.getMcps() != null)        plan.getMcps().forEach(s -> ids.add(s.getNormalizedKey()));
        if (plan.getAgents() != null)      plan.getAgents().forEach(a -> ids.add(a.getNormalizedKey()));
        if (plan.getCores() != null)       plan.getCores().forEach(c -> ids.add(c.getNormalizedKey()));
        if (plan.getTables() != null)      plan.getTables().forEach(t -> ids.add(t.getNormalizedKey()));
        if (plan.getInterfaces() != null)  plan.getInterfaces().forEach(i -> ids.add(i.getNormalizedKey()));
        return ids;
    }

    /**
     * Enrich an error entry with the error message from step data.
     *
     * <p>Writes under the {@code "error"} key - same field name used by
     * {@link #enrichZoomFromStep} (single-row zoom) and
     * {@link #buildItemSummary} (list-mode items[]). One field name across
     * the three surfaces means an agent debugging a failure can read
     * {@code error} regardless of how it reached the data.
     */
    private void enrichErrorMessage(Map<String, Object> err, String runId, String nodeId) {
        try {
            stepDataRepository.findByRunIdAndNormalizedKeyOrderByEpochDesc(runId, nodeId).stream()
                    .filter(s -> s.getErrorMessage() != null)
                    .findFirst()
                    .ifPresent(s -> err.put("error", s.getErrorMessage()));
        } catch (Exception e) {
            log.debug("[AgentFire] Could not load error for node {}: {}", nodeId, e.getMessage());
        }
    }

    /**
     * Enrich a node entry with actual output data from step data storage.
     */
    private void enrichNodeWithStepData(Map<String, Object> node, String runId,
                                         String nodeId, int epoch, String tenantId) {
        if (tenantId == null) return;
        try {
            List<WorkflowStepDataEntity> stepEntries =
                    stepDataRepository.findByRunIdAndNormalizedKeyOrderByEpochDesc(runId, nodeId);

            // Filter to current epoch
            Optional<WorkflowStepDataEntity> stepOpt = stepEntries.stream()
                    .filter(s -> s.getEpoch() != null && s.getEpoch() == epoch)
                    .findFirst();

            if (stepOpt.isEmpty()) return;
            WorkflowStepDataEntity step = stepOpt.get();

            // Load actual output
            if (step.getOutputStorageId() != null) {
                Map<String, Object> rawOutput = stepOutputService.loadRawOutput(
                        step.getOutputStorageId(), tenantId);
                if (rawOutput != null && !rawOutput.isEmpty()) {
                    node.put("output", truncateLargeValues(rawOutput));
                }
            }

            // Include error message if present
            if (step.getErrorMessage() != null && !step.getErrorMessage().isBlank()) {
                node.put("error", step.getErrorMessage());
            }
        } catch (Exception e) {
            log.debug("[AgentFire] Could not enrich node {} with step data: {}", nodeId, e.getMessage());
        }
    }

    /**
     * Truncate large list values AND large string values via the shared
     * {@link com.apimarketplace.agent.tools.common.ToolResultSizeCap}
     * walker. Used by the {@code workflow(execute)} happy path to summarise
     * the agent-facing snapshot. Lists ≥ {@link #OUTPUT_TRUNCATE_THRESHOLD}
     * are collapsed into a {@code row_count + preview} stub; strings
     * larger than the shared {@code MAX_STRING_BYTES} are byte-capped.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> truncateLargeValues(Map<String, Object> output) {
        Object res = com.apimarketplace.agent.tools.common.ToolResultSizeCap
                .capLargeStringsAndLists(output, OUTPUT_TRUNCATE_THRESHOLD, OUTPUT_PREVIEW_MAX_ROWS);
        return res instanceof Map<?, ?> m ? (Map<String, Object>) m : output;
    }

    /**
     * String-only byte-cap delegated to
     * {@link com.apimarketplace.agent.tools.common.ToolResultSizeCap#capLargeStrings}.
     * Used by the {@code get_node_output} path where the inspector needs
     * the full list structure but megabyte-sized inline binaries must be
     * elided.
     */
    private Map<String, Object> capLargeStringsRecursive(Map<String, Object> map) {
        return com.apimarketplace.agent.tools.common.ToolResultSizeCap.capLargeStrings(map);
    }

    /** Default & max text window for field expand - aligned with files.view + ToolResultSizeCap (128 KB). */
    private static final int OUTPUT_FIELD_MAX_BYTES = com.apimarketplace.agent.tools.common.ToolResultSizeCap.MAX_STRING_BYTES;

    /**
     * Window a single output field's full string value (offset-based expand) using the same
     * {@code content/offset/returned_bytes/original_length/truncated/NEXT} vocabulary as the
     * files tool. Lets the agent page through a node-output text field that
     * {@link #capLargeStringsRecursive} would otherwise elide to a 2 KB preview. Top-level
     * fields only; non-text fields return their (capped) structured value.
     */
    Map<String, Object> windowOutputField(Map<String, Object> result, WorkflowStepDataEntity step,
                                           Map<String, Object> rawOutput, String field,
                                           Integer offset, Integer maxBytes) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("field", field);
        Object v = resolvePath(rawOutput, field);
        if (v == null) {
            w.put("error", "No output field named '" + field + "'.");
            w.put("available_fields", new ArrayList<>(rawOutput.keySet()));
            return w;
        }
        if (!(v instanceof String str)) {
            w.put("type", v.getClass().getSimpleName());
            w.put("value", com.apimarketplace.agent.tools.common.ToolResultSizeCap.capLargeStrings(v));
            w.put("note", "Field '" + field + "' is not a text field; its structured value is shown (capped). "
                    + "offset/max_bytes apply only to text fields.");
            return w;
        }
        // Windows are measured in String chars (UTF-16), matching files.view + ToolResultSizeCap -
        // "bytes" ≈ chars here; multi-byte text yields a larger real UTF-8 size.
        int total = str.length();
        int start = Math.min(Math.max(offset == null ? 0 : offset, 0), total);
        int max = Math.max(1, Math.min(maxBytes == null ? OUTPUT_FIELD_MAX_BYTES : maxBytes, OUTPUT_FIELD_MAX_BYTES));
        int end = Math.min(start + max, total);
        String slice = str.substring(start, end);
        w.put("content", slice);
        w.put("offset", start);
        w.put("returned_bytes", slice.length());
        w.put("original_length", total);
        boolean truncated = end < total;
        w.put("truncated", truncated);
        if (truncated) {
            w.put("NEXT", expandCall(result, step, field, end));
        }
        return w;
    }

    /**
     * For each top-level output field that {@code capLargeStrings} reduced to a string-truncation
     * stub ({@code truncated=true} with {@code original_length}), add a {@code NEXT} pointer telling
     * the agent how to page that field's full value. List-truncation stubs (which carry
     * {@code row_count}, not {@code original_length}) are left untouched.
     */
    void addExpandHints(Map<String, Object> capped, Map<String, Object> result, WorkflowStepDataEntity step) {
        addExpandHintsAt(capped, "", result, step, 0);
    }

    /** Recurse the (capped) output, adding a NEXT expand pointer to each string-truncation stub
     *  ({@code truncated=true} with {@code original_length}) at its dot-path - so nested big text
     *  fields (e.g. an MCP tool result under {@code output.*}) are pageable too. List-truncation
     *  stubs ({@code row_count}, not offset-pageable) and plain leaves are left untouched. Depth-guarded. */
    @SuppressWarnings("unchecked")
    private void addExpandHintsAt(Object node, String path,
                                  Map<String, Object> result, WorkflowStepDataEntity step, int depth) {
        if (depth > 8) return;
        if (node instanceof Map<?, ?> mv) {
            Map<String, Object> m = (Map<String, Object>) mv;
            boolean truncated = Boolean.TRUE.equals(m.get("truncated"));
            if (truncated && m.containsKey("original_length")) {
                // String stub. Skip the expand pointer for binary-shaped (base64) content - paging raw
                // image/binary bytes through the agent is wasteful and useless; ToolResultSizeCap's
                // "fetch via a typed tool" note already steers the agent to the files tool / a download.
                // (Explicit field=... expand is still honoured by windowOutputField if the agent insists.)
                Object note = m.get("note");
                boolean binaryShaped = note instanceof String ns && ns.contains("binary");
                if (!path.isEmpty() && !binaryShaped) m.put("NEXT", expandCall(result, step, path, 0));
                return;                                                                 // don't descend into a stub
            }
            if (truncated && m.containsKey("row_count")) {
                return;                                                                 // list-truncation stub → not pageable
            }
            for (Map.Entry<String, Object> e : m.entrySet()) {
                String child = path.isEmpty() ? e.getKey() : path + "." + e.getKey();
                addExpandHintsAt(e.getValue(), child, result, step, depth + 1);
            }
        } else if (node instanceof List<?> list) {
            // recurse into arrays with numeric path segments (e.g. output.data.0.b64_json) so a big
            // text field nested inside a list (image/embedding tool results) is reachable too.
            for (int i = 0; i < list.size(); i++) {
                addExpandHintsAt(list.get(i), path + "." + i, result, step, depth + 1);
            }
        }
    }

    /** Resolve a dot-path into a nested value; map keys and numeric list indices are both supported
     *  (e.g. {@code "output.image"} or {@code "output.data.0.b64_json"}). Null if a segment is missing. */
    private static Object resolvePath(Map<String, Object> root, String path) {
        Object cur = root;
        for (String seg : path.split("\\.")) {
            if (cur instanceof Map<?, ?> m) {
                cur = m.get(seg);
            } else if (cur instanceof List<?> list) {
                try {
                    int idx = Integer.parseInt(seg);
                    cur = (idx >= 0 && idx < list.size()) ? list.get(idx) : null;
                } catch (NumberFormatException nfe) {
                    return null;
                }
            } else {
                return null;
            }
        }
        return cur;
    }

    /** Build a get_node_output expand-call string carrying the current row's identity filters. */
    String expandCall(Map<String, Object> result, WorkflowStepDataEntity step, String field, int offset) {
        StringBuilder sb = new StringBuilder("workflow(action='get_node_output', run_id='")
                .append(result.get("run_id")).append("', epoch=").append(result.get("epoch"))
                .append(", node_id='").append(result.get("node_id")).append("'");
        if (step.getItemIndex() != null) sb.append(", item_index=").append(step.getItemIndex());
        if (step.getIteration() != null && step.getIteration() != 0) sb.append(", iteration=").append(step.getIteration());
        if (step.getSpawn() != null && step.getSpawn() != 0) sb.append(", spawn=").append(step.getSpawn());
        sb.append(", field='").append(field).append("', offset=").append(offset).append(")");
        return sb.toString();
    }

    private void addBlockingSignalInfo(Map<String, Object> result, WorkflowRunEntity run) {
        try {
            List<com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity> signals =
                    signalWaitRepository.findByRunId(run.getRunIdPublic());
            signals.stream()
                    .filter(s -> s.isBlocking() && s.isActive())
                    .findFirst()
                    .ifPresent(s -> {
                        Map<String, Object> blocking = new LinkedHashMap<>();
                        blocking.put("node", s.getNodeId());
                        String signalType = s.getSignalType() != null ? s.getSignalType().name() : "UNKNOWN";
                        blocking.put("signal_type", signalType);
                        // Point the agent at the action that advances this paused run, so it
                        // doesn't poll or give up. Both are gated by the chat authorization card.
                        if ("USER_APPROVAL".equals(signalType)) {
                            blocking.put("resolve_with", "workflow(action='resolve_approval', run_id='"
                                    + run.getRunIdPublic() + "', decision='approved'|'rejected')");
                        } else if ("INTERFACE_SIGNAL".equals(signalType)) {
                            blocking.put("continue_with", "workflow(action='continue_interface', run_id='"
                                    + run.getRunIdPublic() + "')");
                        }
                        result.put("blocking_on", blocking);
                    });
        } catch (Exception e) {
            log.debug("[AgentFire] Could not read blocking signal info: {}", e.getMessage());
        }
    }

    // ==================== Trigger Info (describe / load / init) ====================

    /**
     * Build trigger execute info for session triggers (raw map format from WorkflowBuilderSession).
     * Used by describe, load, and init responses so the agent knows what data to provide when firing.
     *
     * @param triggerMaps raw trigger maps from {@code WorkflowBuilderSession.getTriggers()}
     * @param workflowId  optional workflow ID to include in the execute hint
     * @return execute_info object, or null if no triggers
     */
    public Map<String, Object> buildTriggerExecuteInfo(List<Map<String, Object>> triggerMaps, String workflowId) {
        if (triggerMaps == null || triggerMaps.isEmpty()) return null;

        Map<String, Object> info = new LinkedHashMap<>();
        List<Map<String, Object>> schemas = triggerMaps.stream()
                .map(this::describeTriggerFromMap)
                .toList();
        info.put("triggers", schemas);

        String idPart = workflowId != null ? ", id='" + workflowId + "'" : "";
        // Always include trigger_id in hint so the agent knows which trigger to target
        info.put("hint", "workflow(action='execute'" + idPart + ", trigger_id='<trigger_id>', data_inputs={...})");
        return info;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> describeTriggerFromMap(Map<String, Object> triggerMap) {
        // Default must match Trigger record compact constructor (normalizeOptional defaults to "datasource")
        String typeStr = (String) triggerMap.getOrDefault("type", "datasource");
        String label = (String) triggerMap.getOrDefault("label", "");
        Map<String, Object> params = triggerMap.containsKey("params")
                ? (Map<String, Object>) triggerMap.get("params")
                : Map.of();

        TriggerType type = TriggerType.fromString(typeStr);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("trigger_id", LabelNormalizer.triggerKey(label));
        info.put("type", typeStr);
        info.put("label", label);
        info.put("fireable", isFireableByAgent(type));

        if (!isFireableByAgent(type)) {
            info.put("reason", "Fired automatically by " +
                    (type == TriggerType.WORKFLOW ? "parent workflow completion" : "parent workflow failure"));
            return info;
        }

        switch (type) {
            case MANUAL -> {
                info.put("required_data", "Any JSON object (optional)");
                info.put("example", Map.of("key", "value"));
            }
            case CHAT -> {
                info.put("required_data", Map.of(
                        "message", Map.of("type", "string", "required", true, "description", "The chat message text")
                ));
                info.put("example", Map.of("message", "Hello, start the workflow"));
                Object chatMatchRaw = triggerMap.get("chatMatch");
                if (chatMatchRaw instanceof Map<?, ?> chatMatch) {
                    Map<String, Object> matchInfo = new LinkedHashMap<>();
                    Object matchType = chatMatch.get("type");
                    if (matchType != null) matchInfo.put("match_type", matchType.toString());
                    Object matchValue = chatMatch.get("value");
                    if (matchValue != null) matchInfo.put("match_value", matchValue);
                    info.put("chat_match", matchInfo);
                }
            }
            case WEBHOOK -> {
                info.put("required_data", "Any JSON payload (sent as webhook body)");
                info.put("example", Map.of("event", "user.created", "data", Map.of("id", "123")));
            }
            case SCHEDULE -> {
                info.put("required_data", "Optional data (cron is ignored when fired manually)");
                info.put("example", Map.of());
                Object cron = params.get("cron");
                if (cron != null) info.put("cron_expression", cron);
            }
            case DATASOURCE -> {
                // Manual fire of a datasource trigger takes the BATCH-SCAN path (test mode):
                // data_inputs is ignored, and the trigger output emits {data[], count, hasMore, ...}
                // by loading rows from the datasource - same shape as find_rows. It does NOT emit
                // the event-driven shape ({event_type, row, previous_row, row_id, datasource_id})
                // - that shape only fires on real row changes (insert/update/delete) in production.
                info.put("required_data", "None - data_inputs is ignored for datasource triggers on manual fire.");
                info.put("example", Map.of());
                info.put("note", "Manual fire runs the batch-scan loader and emits {data:[{id, data:{...columns}}, ...], " +
                        "count, hasMore} - same shape as find_rows.items. To process each row in the batch, chain " +
                        "core:split with input={{trigger:label.output.data}} and read via {{item.data.<column>}}. " +
                        "To exercise the event-driven path (one fire per row, output {event_type, row, previous_row, " +
                        "row_id}), cause a real row change instead: table(action='insert_rows'|'update_rows'|" +
                        "'delete_rows', ...). In production, the trigger fires automatically on real row changes; " +
                        "read columns via {{trigger:label.output.row.<column>}} (subscription filter applied there).");
                Object dsId = params.get("datasourceId");
                if (dsId != null) info.put("datasource_id", dsId);
            }
            case FORM -> {
                Object fieldsRaw = params.get("fields");
                if (fieldsRaw instanceof List<?> fieldsList) {
                    Map<String, Object> requiredData = new LinkedHashMap<>();
                    Map<String, Object> example = new LinkedHashMap<>();
                    for (Object fieldObj : fieldsList) {
                        if (fieldObj instanceof Map<?, ?> field) {
                            String name = (String) field.get("name");
                            if (name == null) continue;
                            Object req = field.get("required");
                            boolean isRequired = Boolean.TRUE.equals(req)
                                    || "true".equalsIgnoreCase(String.valueOf(req));
                            Map<String, Object> fieldInfo = new LinkedHashMap<>();
                            fieldInfo.put("type", field.containsKey("type") ? field.get("type") : "string");
                            fieldInfo.put("required", isRequired);
                            if (field.containsKey("label")) fieldInfo.put("label", field.get("label"));
                            requiredData.put(name, fieldInfo);
                            example.put(name, isRequired ? "<required>" : "<optional>");
                        }
                    }
                    info.put("required_data", requiredData);
                    info.put("example", example);
                } else {
                    info.put("required_data", "Form fields (no schema defined in plan)");
                    info.put("example", Map.of());
                }
            }
            default -> {
                info.put("required_data", "No specific schema defined");
                info.put("example", Map.of());
            }
        }

        return info;
    }

    // ==================== Utilities ====================

    /**
     * Compute terminal node IDs: nodes that have no outgoing edges in the plan.
     * Excludes trigger nodes (they are inputs, not outputs).
     */
    private Set<String> computeTerminalNodeIds(WorkflowPlan plan) {
        if (plan.getEdges() == null) return Set.of();

        // All node IDs with at least one outgoing edge (strip port suffix e.g. "core:check:if" → "core:check")
        Set<String> nodesWithSuccessors = plan.getEdges().stream()
                .map(e -> stripPort(e.from()))
                .collect(Collectors.toSet());

        // Terminal = non-trigger nodes that have no outgoing edges
        return collectAllNonTriggerNodeIds(plan).stream()
                .filter(id -> !nodesWithSuccessors.contains(id))
                .collect(Collectors.toSet());
    }

    /** Strip port suffix: "core:check:if" → "core:check". EdgeRefParser is the single source of truth. */
    private String stripPort(String edgeFrom) {
        if (edgeFrom == null) return "";
        return EdgeRefParser.splitPort(edgeFrom)[0];
    }

    private String listTriggerIds(List<Trigger> triggers) {
        return triggers.stream()
                .map(t -> t.getNormalizedKey() + " (" + t.type() + ")")
                .collect(Collectors.joining(", "));
    }

    private String listFireableTriggerIds(List<Trigger> fireable) {
        return fireable.stream()
                .map(t -> t.getNormalizedKey() + " (" + t.type() + ")")
                .collect(Collectors.joining(", "));
    }
}
