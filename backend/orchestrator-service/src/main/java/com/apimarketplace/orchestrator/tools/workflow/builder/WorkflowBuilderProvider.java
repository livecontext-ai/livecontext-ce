package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.config.GuardOverrides;
import com.apimarketplace.agent.config.ToolAccessControl;
import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider;
import com.apimarketplace.agent.tools.common.ToolRateLimiter;
import com.apimarketplace.orchestrator.config.AgentDefaultsConfig;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.service.NodeLibraryService;
import com.apimarketplace.orchestrator.service.NodeParamsValidator;
import com.apimarketplace.orchestrator.service.validation.ValidationResult;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.orchestrator.services.NodeTypeSearchService;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.tools.workflow.WorkflowCrudModule;
import com.apimarketplace.orchestrator.tools.workflow.WorkflowHelpProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Provider for the workflow interactive tool.
 * Allows agents to build workflows step-by-step with validation and schema discovery.
 *
 * <h2>Architecture</h2>
 * This provider delegates to specialized components (SOLID):
 * <ul>
 *   <li>{@link WorkflowBuilderActionConfig} - Action constants and aliases</li>
 *   <li>{@link WorkflowBuilderSessionManager} - Session management</li>
 *   <li>{@link WorkflowBuilderResultEnricher} - Result enrichment</li>
 *   <li>{@link WorkflowDraftAutoSaver} - Draft auto-save</li>
 *   <li>{@link WorkflowBuilderToolDefinitionFactory} - Tool definition</li>
 *   <li>{@link WorkflowBuilderCreator} - Node creation</li>
 *   <li>{@link WorkflowBuilderConnectionManager} - Edge management</li>
 *   <li>{@link WorkflowBuilderModifier} - Node modification/removal</li>
 *   <li>{@link WorkflowBuilderViewer} - Describe/validate</li>
 *   <li>{@link WorkflowBuilderLoader} - Load/save/discard</li>
 *   <li>{@link WorkflowBuilderTableOperations} - CRUD operations</li>
 *   <li>{@link WorkflowBuilderPlanExporter} - Plan import/export</li>
 *   <li>{@link WorkflowBuilderHelpModule} - Help documentation</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowBuilderProvider implements ToolsProvider {

    // Core dependencies
    private final WorkflowBuilderSessionManager sessionManager;
    private final WorkflowBuilderResultEnricher resultEnricher;
    private final WorkflowDraftAutoSaver draftAutoSaver;
    private final WorkflowBuilderToolDefinitionFactory toolDefinitionFactory;
    private final WorkflowBuilderLogger buildLogger;

    // CRUD module (absorbs workflow_get/list/delete/runs)
    private final WorkflowCrudModule crudModule;

    // Domain services
    private final WorkflowManagementService workflowService;
    private final InterfaceClient interfaceClient;
    private final NodeTypeSearchService nodeTypeSearchService;
    private final NodeLibraryService nodeLibraryService;
    private final NodeParamsValidator nodeParamsValidator;
    private final WorkflowHelpProvider workflowHelpProvider;

    // Delegated handlers
    private final WorkflowBuilderCreator creator;
    private final WorkflowBuilderConnectionManager connectionManager;
    private final WorkflowBuilderModifier modifier;
    private final WorkflowBuilderViewer viewer;
    private final WorkflowBuilderLoader loader;
    private final WorkflowBuilderTableOperations tableOperations;
    private final WorkflowBuilderPlanExporter planExporter;
    private final WorkflowBuilderHelpModule helpModule;

    // For execute action
    private final com.apimarketplace.orchestrator.services.WorkflowExecutionService executionService;
    private final com.apimarketplace.orchestrator.repository.WorkflowRunRepository workflowRunRepository;
    private final AgentWorkflowFireService agentWorkflowFireService;

    // For resolve_approval / continue_interface (advance a paused run)
    private final com.apimarketplace.orchestrator.execution.v2.services.RunSignalResolutionService runSignalResolution;

    // For version-targeted execute
    private final com.apimarketplace.orchestrator.services.WorkflowPlanVersionService planVersionService;
    private final com.apimarketplace.orchestrator.trigger.ProductionRunResolver productionRunResolver;

    // Unified per-turn per-resource cap (shared with agent/skill/sub_agent/interface/table)
    private final AgentDefaultsConfig agentDefaults;

    // Early visualization publish (instant side-panel open before blocking fire())
    private final com.apimarketplace.orchestrator.services.agent.ConversationEventPublisher conversationEventPublisher;

    // Mock mode: proposed mock output per node (mock_suggest action)
    private final MockOutputSuggester mockOutputSuggester;

    private final ToolRateLimiter createLimiter = new ToolRateLimiter();

    @Value("${workflow.builder.allow-create-without-validation:false}")
    private boolean allowCreateWithoutValidation;

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.WORKFLOW;
    }

    @Override
    public List<AgentToolDefinition> getTools() {
        return List.of(toolDefinitionFactory.buildToolDefinition());
    }

    @Override
    public ToolExecutionResult execute(String toolName, Map<String, Object> parameters, ToolExecutionContext context) {
        if (!"workflow".equals(toolName)) {
            return ToolExecutionResult.failure(ToolErrorCode.TOOL_NOT_FOUND, "Unknown tool: " + toolName);
        }

        String tenantId = context.tenantId();

        String action = (String) parameters.get("action");
        if (action == null || action.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "Missing required parameter 'action'. Use:\n" +
                "- Node creation: workflow(action='add_node', type='form|webhook|agent|decision|...', label='...', params={...})\n" +
                "- Workflow ops: workflow(action='init|save|connect|list|get|delete|runs|...', ...)");
        }

        if (!WorkflowBuilderActionConfig.isValidAction(action)) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_ENUM_VALUE, "Unknown action: " + action + ". Allowed: " + WorkflowBuilderActionConfig.PRIMARY_ACTIONS);
        }

        String canonicalAction = WorkflowBuilderActionConfig.resolveAlias(action);

        // Per-resource access mode (read/write) enforcement on the BUILDER path.
        // A read-mode agent (workflowAccessMode='read') may inspect a workflow
        // (load/get/list/describe/validate/get_plan/get_node_output/runs/get_run/search/help)
        // - including the builder-internal TABLE reads read_rows/find_rows, which route
        // through delegateTableOperation and are pure reads - but must NOT mutate one
        // (add_node/connect/finish/save/insert_row/update_row/delete_row/…). The
        // CRUD-delegated actions are ALSO checked again in WorkflowCrudModule - this
        // top-level gate adds the builder-specific write actions (init/add_node/connect/
        // modify/remove/save/finish/set_plan/insert_row/…) the CRUD module never sees.
        // READ actions short-circuit inside checkWriteAccess; an absent/'write' mode is
        // allowed (default = full access).
        var workflowAccessDenied = ToolAccessControl.checkWriteAccess(
                context != null ? context.credentials() : null, "workflow", canonicalAction);
        if (workflowAccessDenied.isPresent()) {
            return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, workflowAccessDenied.get());
        }

        // Reject modifying actions on a loaded APPLICATION workflow up front.
        // APPLICATION-type workflows are frozen acquired marketplace clones; their plan
        // is the contract the acquirer received and must not drift in place. The session
        // flag is set by WorkflowBuilderLoader.convertWorkflowToSession at load time, so
        // this check is a O(1) memory read with no extra DB round-trip per action.
        // Defense-in-depth: WorkflowBuilderLoader.executeSave repeats the check at save
        // time against the live entity, in case a path bypasses this dispatch (e.g. a
        // legacy session restored from disk with a stale flag).
        if (WorkflowBuilderActionConfig.PLAN_MUTATING_ACTIONS.contains(canonicalAction)) {
            ToolExecutionResult immutableReject = rejectIfLoadedApplication(parameters, tenantId, context, canonicalAction);
            if (immutableReject != null) {
                return immutableReject;
            }
        }

        try {
            ToolExecutionResult result = dispatchAction(canonicalAction, parameters, tenantId, context);
            resultEnricher.logAction(action, parameters, result, tenantId);

            if (result.success() && WorkflowBuilderActionConfig.isModifyingAction(canonicalAction)) {
                draftAutoSaver.autoSaveDraft(parameters, tenantId, extractConversationId(context));
            }

            return resultEnricher.addSessionSnapshot(result, parameters, tenantId, canonicalAction);

        } catch (Exception e) {
            log.error("Error executing workflow action {}: {}", action, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Error: " + e.getMessage());
        }
    }

    /**
     * Resolves the current session (if any) and returns a {@link ToolExecutionResult}
     * rejecting the call when the session is wired to an APPLICATION-type workflow.
     *
     * <p>Returns {@code null} when:
     * <ul>
     *   <li>no session exists yet (init/load/help/etc. - those routes own their own
     *       handling and never mutate an existing workflow);</li>
     *   <li>the session exists but has not loaded any workflow (a fresh init session -
     *       the user is building a new workflow from scratch, not editing an app);</li>
     *   <li>the loaded workflow is a regular WORKFLOW - the action is allowed.</li>
     * </ul>
     *
     * <p>Returned message points the agent to {@code workflow(action='discard')} +
     * {@code POST /workflows/{id}/reset-plan} as the sanctioned ways out, so it stops
     * retrying the modify path.
     */
    private ToolExecutionResult rejectIfLoadedApplication(Map<String, Object> parameters, String tenantId,
                                                          ToolExecutionContext context, String action) {
        try {
            String conversationId = extractConversationId(context);
            Optional<WorkflowBuilderSession> sessionOpt = sessionManager.getSessionStore()
                    .getSessionForConversation(tenantId, conversationId);
            if (sessionOpt.isEmpty()) return null;
            WorkflowBuilderSession session = sessionOpt.get();
            if (session.getLoadedWorkflowId() == null) return null;
            if (!session.isLoadedWorkflowIsApplication()) return null;

            log.warn("Refused modifying action '{}' on loaded APPLICATION workflow {}: applications are immutable",
                    action, session.getLoadedWorkflowId());
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("code", "APPLICATION_PLAN_IMMUTABLE");
            meta.put("workflow_id", session.getLoadedWorkflowId());
            meta.put("action", action);
            meta.put("next_action", "workflow(action='discard') to drop the session, then build a fresh "
                    + "workflow. Restoring the application's original plan is done by the workspace owner.");
            return ToolExecutionResult.failure(
                    ToolErrorCode.RESOURCE_CONFLICT,
                    "Cannot " + action + " an APPLICATION workflow: it is a frozen acquired marketplace clone. "
                            + "Use workflow(action='discard') to drop this session and build a fresh workflow.",
                    meta);
        } catch (NullPointerException | IllegalArgumentException e) {
            // Narrow swallow: only known-benign session-lookup failures (bad UUID
            // parse on a malformed stored id, null session field on a partially
            // initialised store). Anything else (DB outage, serialization corruption)
            // must surface - for in-memory session mutations the agent's local
            // executeSave defense-in-depth catches APPLICATION saves but NOT
            // mid-flight add_node/connect/etc., so silently allowing those would
            // leave dirty state on a frozen plan.
            log.warn("APPLICATION guard lookup failed for action {} (treated as pass-through): {}",
                    action, e.getMessage());
            return null;
        }
    }

    // ==================== Action Dispatch ====================

    /**
     * Builder-native workflow READ actions keyed on a target workflow id. Gating these on the
     * agent's allowedWorkflowIds closes the bypass where a restricted agent denied
     * workflow(get,id=B) could still read B's plan via load/describe/validate/get_plan. Gating
     * `load` (the entry that brings a workflow into a session) means every later session-based op
     * only ever sees an already-allowed workflow, so it's comprehensive. (get/runs/get_run/
     * get_node_output route to WorkflowCrudModule, which enforces the allow-list itself.)
     */
    private static final java.util.Set<String> WORKFLOW_READ_ACTIONS =
            java.util.Set.of("load", "describe", "validate", "get_plan");

    private ToolExecutionResult dispatchAction(String action, Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
        if (WORKFLOW_READ_ACTIONS.contains(action)) {
            ToolExecutionResult denied = denyIfWorkflowNotInAllowList(params, ctx);
            if (denied != null) return denied;
        }
        return switch (action) {
            case "init" -> executeInit(params, tenantId, ctx);
            case "load" -> delegateLoad(params, tenantId, ctx);
            case "save" -> delegateSave(params, tenantId, ctx);
            case "discard" -> delegateDiscard(params, tenantId, ctx);
            // `finish` is the canonical name. `create` is kept as an alias so
            // older agents and existing frontend callers don't break - both
            // route through the same handler.
            case "finish", "create" -> executeFinish(params, tenantId, ctx);
            // Unified node creation
            case "add_node" -> executeAddNode(params, tenantId, ctx);
            case "insert_row", "read_rows", "update_row", "delete_row", "find_rows" -> delegateTableOperation(params, tenantId, ctx, action);
            case "connect" -> delegateCreator(s -> connectionManager.executeConnect(s, params), params, tenantId, ctx);
            case "disconnect" -> delegateCreator(s -> connectionManager.executeDisconnect(s, params), params, tenantId, ctx);
            case "modify" -> delegateCreator(s -> modifier.executeModifyNode(s, params), params, tenantId, ctx);
            case "remove" -> delegateCreator(s -> modifier.executeRemove(s, params), params, tenantId, ctx);
            case "undo" -> delegateCreator(s -> modifier.executeUndo(s), params, tenantId, ctx);
            case "mock_suggest" -> delegateCreator(s -> executeMockSuggest(s, params, tenantId), params, tenantId, ctx);
            case "describe" -> {
                String nodeParam = (String) params.get("node");
                if (nodeParam == null) nodeParam = (String) params.get("node_id");
                String finalNodeParam = nodeParam;
                yield delegateCreator(s -> finalNodeParam != null && !finalNodeParam.isBlank()
                        ? viewer.executeDescribe(s, params)
                        : viewer.executeGetSummary(s), params, tenantId, ctx);
            }
            case "validate" -> delegateCreator(s -> viewer.executeValidate(s), params, tenantId, ctx);
            case "get_plan" -> delegatePlanExport(params, tenantId, ctx);
            case "set_plan" -> delegatePlanImport(params, tenantId, ctx);
            case "search" -> executeSearch(params);
            case "execute" -> executeWorkflow(params, tenantId, ctx);
            case "help" -> helpModule.execute("help", params, tenantId, ctx)
                .orElse(ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Help module failed"));
            case "get" -> delegateCrud("get", params, tenantId, ctx);
            case "list" -> delegateCrud("list", params, tenantId, ctx);
            case "delete" -> delegateCrud("delete", params, tenantId, ctx);
            case "runs" -> delegateCrud("runs", params, tenantId, ctx);
            case "get_run" -> delegateCrud("get_run", params, tenantId, ctx);
            case "wait_run" -> delegateCrud("wait_run", params, tenantId, ctx);
            case "get_node_output" -> delegateCrud("get_node_output", params, tenantId, ctx);
            case "resolve_approval" -> executeResolveApproval(params, tenantId, ctx);
            case "continue_interface" -> executeContinueInterface(params, tenantId, ctx);
            case "pin" -> delegateCrud("pin", params, tenantId, ctx);
            case "unpin" -> delegateCrud("unpin", params, tenantId, ctx);
            case "publish" -> delegateCrud("publish", params, tenantId, ctx);
            case "unpublish" -> delegateCrud("unpublish", params, tenantId, ctx);
            default -> ToolExecutionResult.failure(ToolErrorCode.INVALID_ENUM_VALUE, "Unknown action: " + action);
        };
    }

    // ==================== Signal resolution (advance a paused run) ====================

    /**
     * Resolve a pending USER APPROVAL on a paused run. Params: run_id (required),
     * decision ('approved'|'rejected', required), node_id (optional - auto-resolved when
     * exactly one approval is pending), comment / epoch / item_id (optional). Gated in chat
     * via ToolAuthorizationPolicy (workflow:resolve_approval).
     */
    private ToolExecutionResult executeResolveApproval(Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
        String runId = safeString(params.get("run_id"));
        if (runId == null || runId.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "'run_id' is required (from workflow(action='execute') or action='runs').");
        }
        ToolExecutionResult scopeErr = guardRunInScope(runId, tenantId, ctx);
        if (scopeErr != null) return scopeErr;

        String decision = safeString(params.get("decision"));
        if (decision == null) decision = safeString(params.get("resolution"));
        com.apimarketplace.orchestrator.domain.execution.SignalResolution resolution;
        switch (decision == null ? "" : decision.trim().toLowerCase()) {
            case "approved", "approve", "accept", "yes" ->
                resolution = com.apimarketplace.orchestrator.domain.execution.SignalResolution.APPROVED;
            case "rejected", "reject", "deny", "no" ->
                resolution = com.apimarketplace.orchestrator.domain.execution.SignalResolution.REJECTED;
            default -> { return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "'decision' is required and must be 'approved' or 'rejected'."); }
        }

        String nodeId = safeString(params.get("node_id"));
        if (nodeId == null || nodeId.isBlank()) {
            var pending = runSignalResolution.pendingOfType(runId,
                com.apimarketplace.orchestrator.domain.execution.SignalType.USER_APPROVAL);
            ToolExecutionResult err = requireSinglePending(pending, "approval", "resolve_approval");
            if (err != null) return err;
            nodeId = pending.get(0).getNodeId();
        }

        var outcome = runSignalResolution.resolveApproval(runId, nodeId, resolution, buildResolutionData(params),
            tenantId, intOrNull(params.get("epoch")), safeString(params.get("item_id")));
        return signalOutcomeResult(outcome, runId, nodeId, "approval");
    }

    /**
     * Continue a paused interface node (the {@code __continue} advance). Params: run_id (required),
     * node_id (optional - auto-resolved when exactly one interface is paused), data / epoch /
     * item_id (optional). Gated in chat via ToolAuthorizationPolicy (workflow:continue_interface).
     */
    private ToolExecutionResult executeContinueInterface(Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
        String runId = safeString(params.get("run_id"));
        if (runId == null || runId.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "'run_id' is required (from workflow(action='execute') or action='runs').");
        }
        ToolExecutionResult scopeErr = guardRunInScope(runId, tenantId, ctx);
        if (scopeErr != null) return scopeErr;

        String nodeId = safeString(params.get("node_id"));
        if (nodeId == null || nodeId.isBlank()) {
            var pending = runSignalResolution.pendingOfType(runId,
                com.apimarketplace.orchestrator.domain.execution.SignalType.INTERFACE_SIGNAL);
            ToolExecutionResult err = requireSinglePending(pending, "interface", "continue_interface");
            if (err != null) return err;
            nodeId = pending.get(0).getNodeId();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = params.get("data") instanceof Map ? (Map<String, Object>) params.get("data") : Map.of();
        var outcome = runSignalResolution.continueInterface(runId, nodeId, data, tenantId,
            intOrNull(params.get("epoch")), safeString(params.get("item_id")));
        return signalOutcomeResult(outcome, runId, nodeId, "interface");
    }

    /** Run must be visible to the caller (tenant/org), else not-found (no cross-tenant probing). */
    private ToolExecutionResult guardRunInScope(String runId, String tenantId, ToolExecutionContext ctx) {
        var runOpt = workflowRunRepository.findByRunIdPublic(runId);
        String orgId = ctx != null ? ctx.orgId() : null;
        if (runOpt.isEmpty()
                || !com.apimarketplace.orchestrator.controllers.workflow.WorkflowControllerHelper
                        .isRunInScope(runOpt.get(), tenantId, orgId)) {
            return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND,
                "Run '" + runId + "' not found (or not in your workspace).");
        }
        return null;
    }

    private ToolExecutionResult requireSinglePending(
            java.util.List<com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity> pending,
            String kind, String action) {
        if (pending.isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND,
                "No pending " + kind + " on this run - it is not awaiting a " + kind + " right now.");
        }
        if (pending.size() > 1) {
            java.util.List<String> nodes = pending.stream()
                .map(com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity::getNodeId).distinct().toList();
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "Multiple " + kind + " signals are pending - pass node_id to pick one. Pending nodes: " + nodes
                + " (e.g. workflow(action='" + action + "', run_id='...', node_id='" + nodes.get(0) + "', ...)).");
        }
        return null;
    }

    private Map<String, Object> buildResolutionData(Map<String, Object> params) {
        Map<String, Object> data = new LinkedHashMap<>();
        Object comment = params.get("comment");
        if (comment != null) data.put("comment", comment);
        if (params.get("data") instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null) data.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        return data;
    }

    private ToolExecutionResult signalOutcomeResult(
            com.apimarketplace.orchestrator.execution.v2.services.RunSignalResolutionService.Outcome outcome,
            String runId, String nodeId, String kind) {
        if (!outcome.ok()) {
            String msg = switch (outcome.reason() == null ? "" : outcome.reason()) {
                case "no_pending_approval" -> "No pending approval on node '" + nodeId + "' for this run.";
                case "no_pending_interface" -> "No paused interface on node '" + nodeId + "' for this run.";
                case "already_resolved" -> "That " + kind + " signal was already resolved.";
                default -> "Could not resolve the " + kind + " signal (" + outcome.reason() + ").";
            };
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, msg);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "resolved");
        result.put("run_id", runId);
        result.put("node_id", nodeId);
        result.put("resolution", outcome.resolution());
        result.put("epoch", outcome.epoch());
        result.put("message", "interface".equals(kind)
            ? "Interface continued - the run advanced past '" + nodeId + "'."
            : "Approval '" + outcome.resolution() + "' recorded - the run resumed past '" + nodeId + "'.");
        result.put("next", "workflow(action='get_run', run_id='" + runId + "') to see the resumed run.");
        return ToolExecutionResult.success(result);
    }

    private static Integer intOrNull(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    // ==================== Action Implementations ====================

    private ToolExecutionResult executeInit(Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
        String name = (String) params.get("name");
        Boolean force = Boolean.TRUE.equals(params.get("force"));

        if (tenantId == null || tenantId.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "tenantId is required");
        }

        if (ctx.isViewingWorkflow() && !force) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, String.format(
                "Currently viewing workflow '%s'.\n" +
                "Options:\n" +
                "- workflow(action='load') to edit this workflow\n" +
                "- workflow(action='init', name='New Name', force=true) to create a new one",
                ctx.viewingWorkflowName()));
        }

        // Get conversationId from credentials for session isolation
        String conversationId = ctx.credentials() != null
            ? (String) ctx.credentials().get("conversationId")
            : null;

        // Check for existing session in this conversation
        Optional<WorkflowBuilderSession> existing = sessionManager.getSessionStore()
            .getSessionForConversation(tenantId, conversationId);
        if (existing.isPresent() && !force) {
            var s = existing.get();
            int nodes = s.getTriggers().size() + s.getMcps().size() + s.getCores().size();
            String draftId = s.getLoadedWorkflowId();
            String options = draftId != null
                ? String.format(
                    "Options:\n" +
                    "- workflow(action='describe') to see current state\n" +
                    "- workflow(action='discard') to abandon and start fresh\n" +
                    "- workflow(action='save') to save current progress",
                    draftId)
                : String.format(
                    "Options:\n" +
                    "- workflow(action='describe') to see current state\n" +
                    "- workflow(action='discard') to abandon and start fresh");
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, String.format(
                "Active session found: '%s' (%d nodes).\n%s", s.getWorkflowName(), nodes, options));
        }

        // Discard any existing session for this conversation only
        if (conversationId != null) {
            existing.ifPresent(s -> sessionManager.delete(s.getSessionId()));
        } else {
            sessionManager.discardAllForTenant(tenantId);
        }

        if (name == null || name.isBlank()) {
            name = "Workflow-" + java.time.Instant.now().toEpochMilli();
        }

        // Capture active org at session-init so finishWorkflow stamps the
        // workflow row with the correct workspace id. Audit 2026-05-16.
        String orgId = ctx != null ? ctx.orgId() : null;
        WorkflowBuilderSession session = WorkflowBuilderSession.create(tenantId, orgId, conversationId, name, (String) params.get("description"));
        String draftId = draftAutoSaver.createDraft(session, tenantId);
        sessionManager.save(session);
        buildLogger.logSessionStart(session);

        Map<String, Object> result = buildInitResult(session, draftId);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("draftId", draftId);
        metadata.put("visualization", Map.of("type", "workflow", "id", draftId, "title", name));
        return ToolExecutionResult.success(result, metadata);
    }

    private Map<String, Object> buildInitResult(WorkflowBuilderSession session, String draftId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        if (draftId != null) result.put("draft_id", draftId);

        // Add common elements (rules, variable_syntax, available_node_types_by_category, actions)
        WorkflowBuilderCommonResponses.addCommonElements(result, nodeLibraryService, true);

        result.put("NEXT", "workflow(action='add_node', type='<form|webhook|schedule|table|manual|chat>', label='...', params={...})");
        return result;
    }

    /**
     * Finalize a workflow draft and persist it as an active workflow.
     *
     * <p>Two paths:
     * <ul>
     *   <li><b>New workflow</b> (no loaded id): validate → save plan → grant
     *       agent access → close session → return CREATED with the new
     *       workflow id.</li>
     *   <li><b>Editing existing</b> (loaded id present): delegate to
     *       {@code save} which updates the existing row → close session →
     *       return SAVED.</li>
     * </ul>
     *
     * <p>Used to be called {@code executeCreate} (action {@code create}). The
     * new public name is {@code finish} which is less ambiguous for the LLM
     * - "create" reads as "create a new empty workflow" and made the agent
     * loop the action repeatedly. {@code create} stays as a back-compat alias
     * in the dispatcher above.
     */
    /**
     * Resolves the per-turn workflow-create cap via
     * {@link GuardOverrides#resolve}: conversation-scope / caller-agent
     * credential (via {@code __chatMaxPerResourcePerTurn__}, propagated by
     * conversation-service/AgentContextBuilder) → YAML default. Mirrors the
     * pattern used by InterfaceCrudModule / DataSourceTableModule.
     */
    int resolveMaxPerResourcePerTurn(ToolExecutionContext context) {
        int fallback = agentDefaults.getMaxPerResourcePerTurn();
        Map<String, Object> credentials = context != null ? context.credentials() : null;
        return GuardOverrides.resolve(
            null, credentials,
            GuardOverrides.CRED_MAX_PER_RESOURCE_PER_TURN,
            fallback);
    }

    private ToolExecutionResult executeFinish(Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
        var sr = sessionManager.getSession(params, tenantId, extractConversationId(ctx));
        if (sr.isError()) return sr.error();
        WorkflowBuilderSession session = sr.session();

        String nameOverride = (String) params.get("name");
        if (nameOverride != null && !nameOverride.isBlank()) session.setWorkflowName(nameOverride);

        String descOverride = (String) params.get("description");
        if (descOverride != null && !descOverride.isBlank()) session.setWorkflowDescription(descOverride);

        // If editing an existing workflow, delegate to save + close session
        if (session.getLoadedWorkflowId() != null) {
            var saveResult = resultEnricher.enrichResult(loader.executeSave(session), session);
            if (saveResult.success()) {
                buildLogger.logSessionEnd(session, "SAVED");
                sessionManager.delete(session.getSessionId());
                @SuppressWarnings("unchecked")
                Map<String, Object> saveData = saveResult.data() instanceof Map
                    ? new LinkedHashMap<>((Map<String, Object>) saveResult.data())
                    : new LinkedHashMap<>();
                saveData.put("status", "FINISHED");
                saveData.put("outcome", "EXISTING_WORKFLOW_UPDATED");
                saveData.put("message",
                    "Workflow '" + session.getWorkflowName() + "' updated and saved. " +
                    "The build session is now CLOSED - no further workflow(action=...) calls are needed. " +
                    "The workflow will run automatically when its trigger fires. " +
                    "To run it manually now, call workflow(action='execute', id='" + session.getLoadedWorkflowId() + "'). " +
                    "To edit it again later, call workflow(action='load', id='" + session.getLoadedWorkflowId() + "').");
                saveData.put("session_state", "CLOSED");
                saveData.put("STOP", "Do NOT call finish/create/save again - the work is done.");
                return ToolExecutionResult.success(saveData, saveResult.metadata());
            }
            return saveResult;
        }

        var validateResult = viewer.executeValidate(session);
        @SuppressWarnings("unchecked")
        Map<String, Object> vd = (Map<String, Object>) validateResult.data();
        if (!allowCreateWithoutValidation && !Boolean.TRUE.equals(vd.get("can_create"))) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Cannot finish workflow: validation errors. Run workflow(action='validate') for details, " +
                "fix the errors with workflow(action='modify') or workflow(action='remove'), then call " +
                "workflow(action='finish') again.");
        }

        // Per-turn creation cap. Resolves via:
        //   caller-agent override (through __chatMaxPerResourcePerTurn__) → YAML default.
        // Only gates NEW workflow creation - editing an existing workflow is handled above.
        String turnId = ctx != null && ctx.credentials() != null
            ? (String) ctx.credentials().get("turnId")
            : null;
        int maxCreates = resolveMaxPerResourcePerTurn(ctx);
        String createKey = null;
        if (turnId != null) {
            createKey = tenantId + ":workflow:" + turnId;
            var limitResult = createLimiter.checkLimit(createKey, maxCreates,
                "LIMIT REACHED: You have already created " + maxCreates + " workflows for this message.\n\n" +
                "WHAT TO DO:\n" +
                "1. Use workflow(action='list') to see existing workflows\n" +
                "2. Use workflow(action='load', id='...') to edit an existing one\n" +
                "3. Ask the user: 'I've created several workflows. Which one would you like me to refine?'\n\n" +
                "DO NOT create more workflows. Work with what exists.");
            if (limitResult.isPresent()) return limitResult.get();

            log.info("[WORKFLOW_CREATE] Creating workflow {}/{} in turn {}",
                createLimiter.getCount(createKey), maxCreates, turnId);
        }

        try {
            Map<String, Object> planMap = session.buildPlanMap();
            WorkflowPlan plan = WorkflowPlan.fromMap(planMap, tenantId);

            UUID workflowId = UUID.randomUUID();

            // Thread session.getOrgId() - the builder-session captured the caller's
            // active workspace at create time (WorkflowBuilderProvider:325-326).
            // Without this, the finalized workflow inherits NULL organization_id
            // and is invisible to org-scoped list endpoints (mirror of saveDraft fix).
            var saveResult = workflowService.saveWorkflow(plan, Map.of(), workflowId, session.getOrgId());
            buildLogger.logSessionEnd(session, "CREATED");
            sessionManager.delete(session.getSessionId());

            String wfId = saveResult.getWorkflow().getId().toString();
            String wfName = session.getWorkflowName();

            // Auto-grant: agent gets access to the workflow it just created
            ToolAccessControl.grantCreatedResource(ctx.credentials(), "workflow", wfId);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "FINISHED");
            data.put("outcome", "NEW_WORKFLOW_CREATED");
            data.put("workflow_id", wfId);
            data.put("workflow_name", wfName);
            data.put("marker", "[visualize:workflow:" + wfId + "]");
            data.put("message",
                "Workflow '" + wfName + "' has been finalized and saved with id " + wfId + ". " +
                "The build session is now CLOSED - no further workflow(action=...) calls are needed. " +
                "The workflow will run automatically when its trigger fires. " +
                "To run it manually now, call workflow(action='execute', id='" + wfId + "'). " +
                "To edit it again later, call workflow(action='load', id='" + wfId + "').");
            data.put("session_state", "CLOSED");
            data.put("to_edit", "workflow(action='load', id='" + wfId + "')");
            data.put("to_run_now", "workflow(action='execute', id='" + wfId + "')");
            data.put("STOP", "Do NOT call finish/create/save again - the work is done. Inform the user the workflow is ready.");

            return ToolExecutionResult.success(
                data,
                Map.of("visualization", Map.of("type", "workflow", "id", wfId, "title", wfName))
            );

        } catch (com.apimarketplace.auth.client.entitlement.LimitExceededException e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXTERNAL_SERVICE_ERROR, e.getMessage());
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.error("Failed to create workflow: {}", errorMsg, e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to create workflow: " + errorMsg);
        }
    }

    /**
     * mock_suggest: propose a ready-to-edit mock output for a node - the projected
     * catalog example for mcp catalog tools, a schema-synthesized skeleton for every
     * other family. Pure read; the agent edits the proposal (or ignores it) and sets
     * the mock via workflow(action='modify', node=..., mock={output: ...}).
     */
    private ToolExecutionResult executeMockSuggest(WorkflowBuilderSession session,
                                                   Map<String, Object> params, String tenantId) {
        String nodeRef = (String) params.get("node");
        if (nodeRef == null) nodeRef = (String) params.get("node_id");
        if (nodeRef == null || nodeRef.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "'node' parameter is required: workflow(action='mock_suggest', node='<label>').");
        }
        String nodeId = session.resolveNodeReference(nodeRef);
        Optional<Map<String, Object>> nodeOpt = session.findNode(nodeId);
        if (nodeOpt.isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND,
                "Node not found: " + nodeRef);
        }
        if (com.apimarketplace.orchestrator.utils.LabelNormalizer.isTriggerKey(nodeId)
                || com.apimarketplace.orchestrator.utils.LabelNormalizer.isNoteKey(nodeId)) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                "Mocking is not available on trigger or note nodes. Use data_inputs on execute to fake a "
                    + "trigger payload, or suggest for a downstream node instead.");
        }

        MockOutputSuggester.Suggestion suggestion =
            mockOutputSuggester.suggest(nodeId, nodeOpt.get(), tenantId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("node", nodeId);
        result.put("suggested_output", suggestion.output());
        result.put("source", suggestion.source());
        result.put("hint", suggestion.hint());
        result.put("NEXT", "Edit the suggested_output as needed, then set it: "
            + "workflow(action='modify', node='" + nodeRef + "', mock={output: {...}}). "
            + ("catalog_example".equals(suggestion.source())
                ? "Or skip the copy: workflow(action='modify', node='" + nodeRef + "', mock={source: 'catalog_example'})."
                : "Full guide: workflow(action='help', topics=['mocking'])."));
        return ToolExecutionResult.success(result);
    }

    private ToolExecutionResult executeWorkflow(Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
        String workflowIdStr = resolveWorkflowId(params, tenantId, ctx);
        if (workflowIdStr == null) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Provide 'id' parameter or load a workflow first: workflow(action='load', id='...')");
        }

        try {
            UUID workflowId = UUID.fromString(workflowIdStr);
            var existingOpt = workflowService.getWorkflow(workflowId);
            String orgId = TenantResolver.currentRequestOrganizationId();
            if (existingOpt.isEmpty() || !ScopeGuard.isInStrictScope(tenantId, orgId, existingOpt.get().getTenantId(), existingOpt.get().getOrganizationId())) {
                return ToolExecutionResult.failure(ToolErrorCode.WORKFLOW_NOT_FOUND, "Workflow not found: " + workflowIdStr);
            }

            var workflow = existingOpt.get();

            @SuppressWarnings("unchecked")
            Map<String, Object> dataInputs = params.get("data_inputs") instanceof Map<?, ?>
                    ? (Map<String, Object>) params.get("data_inputs") : Map.of();
            String triggerIdHint = params.get("trigger_id") instanceof String s ? s : null;
            Object versionParam = params.get("version");
            String mockMode = params.get("mock_mode") instanceof String s && !s.isBlank() ? s.trim() : null;

            VersionSelection selection;
            try {
                selection = resolveVersionSelection(versionParam, workflow);
            } catch (IllegalArgumentException e) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, e.getMessage());
            }

            // Hard guard: production fires never apply mocks - refuse BEFORE resolving
            // the pinned run so nothing is touched.
            if (mockMode != null && selection.mode == SelectionMode.PINNED) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    "mock_mode is not available with version='pinned'. Production fires always execute the real "
                        + "pinned plan and never apply mocks. Drop version='pinned' to test with mocks on the "
                        + "current plan, or drop mock_mode to fire production.");
            }

            WorkflowPlan plan;
            com.apimarketplace.orchestrator.domain.WorkflowRunEntity run;

            switch (selection.mode) {
                case CURRENT -> {
                    plan = WorkflowPlan.fromMap(workflow.getPlan(), workflowIdStr, tenantId);
                    if (plan.getTriggers() == null || plan.getTriggers().isEmpty()) {
                        return ToolExecutionResult.failure(ToolErrorCode.WORKFLOW_INVALID, "Workflow has no triggers. Add a trigger node first.");
                    }
                    run = agentWorkflowFireService.createRun(workflow, plan, dataInputs, tenantId, mockMode);
                }
                case REPLAY_VERSION -> {
                    var versionOpt = planVersionService.getVersion(workflowId, selection.version);
                    if (versionOpt.isEmpty()) {
                        return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Version " + selection.version + " not found for workflow " + workflowIdStr);
                    }
                    plan = WorkflowPlan.fromMap(versionOpt.get().getPlan(), workflowIdStr, tenantId);
                    if (plan.getTriggers() == null || plan.getTriggers().isEmpty()) {
                        return ToolExecutionResult.failure(ToolErrorCode.WORKFLOW_INVALID, "Version " + selection.version + " has no triggers.");
                    }
                    run = agentWorkflowFireService.createRunForVersion(
                            workflow, plan, selection.version, dataInputs, tenantId, mockMode);
                }
                case PINNED -> {
                    Integer pinned = workflow.getPinnedVersion();
                    if (pinned == null) {
                        return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Workflow is not pinned to a production version. Pin a version first " +
                            "or call without the version param to run the current plan.");
                    }
                    var prodResolution = productionRunResolver.resolve(workflowId, com.apimarketplace.orchestrator.trigger.ProductionRunResolver.RunSelectionPolicy.LATEST_TRUSTED);
                    if (!prodResolution.isFound()) {
                        return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "No production run found for workflow at pinned v" + pinned +
                            " (outcome=" + prodResolution.outcome() + "). " +
                            "The pinned version must have an existing WAITING_TRIGGER run to accumulate " +
                            "production fires - same bootstrap pattern as webhook/schedule triggers.");
                    }
                    // Load the pinned plan (from the version history - source of truth for prod)
                    var pinnedVersionOpt = planVersionService.getVersion(workflowId, pinned);
                    if (pinnedVersionOpt.isEmpty()) {
                        return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "Pinned version " + pinned + " not found in version history.");
                    }
                    plan = WorkflowPlan.fromMap(pinnedVersionOpt.get().getPlan(), workflowIdStr, tenantId);
                    if (plan.getTriggers() == null || plan.getTriggers().isEmpty()) {
                        return ToolExecutionResult.failure(ToolErrorCode.WORKFLOW_INVALID, "Pinned version " + pinned + " has no triggers.");
                    }
                    run = prodResolution.run().orElseThrow();
                }
                default -> throw new IllegalStateException("Unhandled selection mode: " + selection.mode);
            }

            // Early visualization publish (instant side-panel open before blocking fire())
            String workflowName = workflow.getName() != null ? workflow.getName() : "Workflow";
            if (run != null) {
                String streamId = ctx.credentials() != null ? (String) ctx.credentials().get("__streamId__") : null;
                String conversationId = ctx.credentials() != null ? (String) ctx.credentials().get("conversationId") : null;
                conversationEventPublisher.publishVisualizationReady(
                    streamId, conversationId, "workflow_run", workflowIdStr, workflowName, run.getRunIdPublic());
            }

            // Bootstrap-only short-circuit: when every trigger is non-agent-fireable
            // (workflow / error), createRun above already seeded a WAITING_TRIGGER run
            // that the dispatcher will reuse on real parent events. Fire path is a
            // no-op for these - return the seed run id instead of throwing
            // "No agent-fireable trigger found", which previously misled the agent
            // into thinking bootstrap had failed.
            if (triggerIdHint == null && agentWorkflowFireService.hasOnlyBootstrapTriggers(plan)) {
                Map<String, Object> bootstrapResult = new LinkedHashMap<>();
                bootstrapResult.put("status", "BOOTSTRAPPED");
                bootstrapResult.put("outcome", "BOOTSTRAP_RUN_READY");
                bootstrapResult.put("run_id", run.getRunIdPublic());
                bootstrapResult.put("workflow_id", workflowIdStr);
                bootstrapResult.put("plan_version", run.getPlanVersion());
                bootstrapResult.put("message",
                    "Bootstrap WAITING_TRIGGER run is ready for this handler. " +
                    "The dispatcher will attach future parent-workflow events (FAILED / PARTIAL_SUCCESS for error triggers, " +
                    "completion for workflow triggers) to this run. " +
                    "To exercise the dispatch chain end-to-end, cause the watched parent workflow to produce that event " +
                    "(e.g. workflow(action='execute', id='<parent_id>') for a parent that fails) and check " +
                    "workflow(action='runs', workflow_id='" + workflowIdStr + "') for a new epoch.");
                bootstrapResult.put("NEXT", "workflow(action='runs', workflow_id='" + workflowIdStr + "') to verify the seed run, " +
                    "or workflow(action='get_run', run_id='" + run.getRunIdPublic() + "') for run details.");
                return ToolExecutionResult.success(
                    bootstrapResult,
                    Map.of("visualization", Map.of(
                        "type", "workflow_run",
                        "id", workflowIdStr,
                        "title", workflowName,
                        "runId", run.getRunIdPublic()
                    ))
                );
            }

            // Resolve trigger against the selected plan
            com.apimarketplace.orchestrator.domain.workflow.Trigger trigger;
            try {
                trigger = agentWorkflowFireService.resolveTrigger(plan, triggerIdHint);
            } catch (IllegalArgumentException e) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, e.getMessage());
            }

            // Fire trigger (blocking - waits for full epoch cycle in AUTO mode)
            com.apimarketplace.orchestrator.trigger.TriggerExecutionResult triggerResult =
                    agentWorkflowFireService.fire(run, trigger, dataInputs);

            // Build structured result scoped to (run_id, trigger_id, epoch)
            Map<String, Object> result = agentWorkflowFireService.buildResult(run, triggerResult, workflow, plan, tenantId);

            return ToolExecutionResult.success(
                result,
                Map.of("visualization", Map.of(
                    "type", "workflow_run",
                    "id", workflowIdStr,
                    "title", workflowName,
                    "runId", run.getRunIdPublic()
                ))
            );

        } catch (Exception e) {
            log.error("Failed to execute workflow: {}", e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Execute failed: " + e.getMessage());
        }
    }

    /**
     * Parse the optional {@code version} param passed to {@code workflow(action='execute')}.
     *
     * <ul>
     *   <li>{@code null} or missing → {@link SelectionMode#CURRENT} (backward-compatible editor run on current plan)</li>
     *   <li>Integer / Long / numeric string → {@link SelectionMode#REPLAY_VERSION} on that version</li>
     *   <li>Literal string {@code "pinned"} (case-insensitive) → {@link SelectionMode#PINNED} (production fire)</li>
     * </ul>
     */
    private VersionSelection resolveVersionSelection(Object versionParam,
                                                      com.apimarketplace.orchestrator.domain.WorkflowEntity workflow) {
        if (versionParam == null) return VersionSelection.current();

        if (versionParam instanceof Number num) {
            int v = num.intValue();
            if (v <= 0) throw new IllegalArgumentException("version must be a positive integer, got " + v);
            return VersionSelection.replay(v);
        }

        if (versionParam instanceof String str) {
            String trimmed = str.trim();
            if (trimmed.isEmpty()) return VersionSelection.current();
            if ("pinned".equalsIgnoreCase(trimmed)) return VersionSelection.pinned();
            try {
                int v = Integer.parseInt(trimmed);
                if (v <= 0) throw new IllegalArgumentException("version must be a positive integer, got " + v);
                return VersionSelection.replay(v);
            } catch (NumberFormatException ignored) {
                throw new IllegalArgumentException(
                    "Invalid 'version' value: '" + str + "'. Accepted: a positive integer (replay a specific version) " +
                    "or the literal 'pinned' (fire the workflow's pinned production version). Omit for current-plan editor run.");
            }
        }

        throw new IllegalArgumentException(
            "Invalid 'version' type: " + versionParam.getClass().getSimpleName() +
            ". Accepted: integer or the literal string 'pinned'.");
    }

    private enum SelectionMode { CURRENT, REPLAY_VERSION, PINNED }

    private static final class VersionSelection {
        final SelectionMode mode;
        final int version;

        private VersionSelection(SelectionMode mode, int version) {
            this.mode = mode;
            this.version = version;
        }

        static VersionSelection current() { return new VersionSelection(SelectionMode.CURRENT, 0); }
        static VersionSelection replay(int v) { return new VersionSelection(SelectionMode.REPLAY_VERSION, v); }
        static VersionSelection pinned() { return new VersionSelection(SelectionMode.PINNED, 0); }
    }

    /**
     * Resolve the workflow ID for sessionless actions (execute).
     *
     * Priority:
     * 1. Direct 'id' param (no session needed - agent can execute without loading)
     * 2. 'workflow_id' param (alias)
     * 3. Session loaded workflow (builder context fallback)
     *
     * Returns null if no workflow ID can be resolved.
     */
    private String resolveWorkflowId(Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
        // Direct params take priority - no session required
        String direct = (String) params.get("id");
        if (direct != null && !direct.isBlank()) return direct;

        String alias = (String) params.get("workflow_id");
        if (alias != null && !alias.isBlank()) return alias;

        // Fallback: session (builder context)
        var sr = sessionManager.getSession(params, tenantId, extractConversationId(ctx));
        if (!sr.isError()) {
            String fromSession = sr.session().getLoadedWorkflowId();
            if (fromSession != null && !fromSession.isBlank()) return fromSession;
        }

        return null;
    }

    /**
     * Deny when the agent has a restricted workflow allow-list that excludes the workflow id
     * targeted by the {@code id}/{@code workflow_id} param. null list ⇒ unrestricted (no-op).
     * Param-only by design: a session-resolved workflow was brought in via the (now gated) `load`,
     * so it is already allowed - no need to (re)touch the session here.
     */
    private ToolExecutionResult denyIfWorkflowNotInAllowList(Map<String, Object> params, ToolExecutionContext ctx) {
        java.util.List<String> allowed = ctx != null
                ? ToolAccessControl.getAllowedIds(ctx.credentials(), "workflow") : null;
        if (allowed == null) return null; // unrestricted
        String wfId = (String) params.get("id");
        if (wfId == null || wfId.isBlank()) wfId = (String) params.get("workflow_id");
        if (wfId != null && !wfId.isBlank() && !allowed.contains(wfId)) {
            log.info("Agent restriction: workflow {} not in allowed list (builder read)", wfId);
            return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED,
                    "This workflow is not in your approved workflow list.");
        }
        return null;
    }

    private ToolExecutionResult executeSearch(Map<String, Object> params) {
        String query = (String) params.get("query");
        String category = (String) params.get("category");
        Integer limit = params.get("limit") != null ? ((Number) params.get("limit")).intValue() : null;

        Map<String, Object> result = new LinkedHashMap<>();
        try {
            if (category != null && !category.isBlank()) {
                result.put("results", nodeTypeSearchService.searchByCategory(query, category, limit));
            } else if (query != null && !query.isBlank()) {
                result.put("results", nodeTypeSearchService.search(query, limit));
            } else {
                result.put("categories", nodeTypeSearchService.getCategories());
                result.put("node_types", nodeTypeSearchService.getAllGroupedByCategory());
            }
            return ToolExecutionResult.success(result);
        } catch (Exception e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXTERNAL_SERVICE_ERROR, "Search failed: " + e.getMessage());
        }
    }

    // ==================== Delegation Methods ====================

    @FunctionalInterface
    interface SessionAction {
        ToolExecutionResult execute(WorkflowBuilderSession session);
    }

    private ToolExecutionResult delegateCreator(SessionAction action, Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
        var sr = sessionManager.getSession(params, tenantId, extractConversationId(ctx));
        if (sr.isError()) return sr.error();
        var result = action.execute(sr.session());
        return resultEnricher.enrichResult(result, sr.session());
    }

    private ToolExecutionResult delegateTableOperation(Map<String, Object> params, String tenantId, ToolExecutionContext ctx, String operation) {
        var sr = sessionManager.getSession(params, tenantId, extractConversationId(ctx));
        if (sr.isError()) return sr.error();
        var result = tableOperations.execute(sr.session(), params, tenantId, operation);
        return resultEnricher.enrichResult(result, sr.session());
    }

    private ToolExecutionResult delegatePlanExport(Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
        var sr = sessionManager.getSession(params, tenantId, extractConversationId(ctx));
        if (sr.isError()) return sr.error();
        return planExporter.executeGetPlan(sr.session());
    }

    private ToolExecutionResult delegatePlanImport(Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
        var sr = sessionManager.getSession(params, tenantId, extractConversationId(ctx));
        if (sr.isError()) return sr.error();
        return planExporter.executeSetPlan(sr.session(), params);
    }

    private ToolExecutionResult delegateLoad(Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
        String conversationId = extractConversationId(ctx);
        String orgId = ctx != null ? ctx.orgId() : null;
        var result = loader.executeLoad(tenantId, orgId, conversationId, params);
        if (result.success() && result.data() instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            String sessionId = (String) data.get("session_id");
            if (sessionId != null) {
                sessionManager.getSessionStore().get(sessionId)
                    .ifPresent(s -> resultEnricher.enrichResult(result, s));
            }
        }
        return result;
    }

    private ToolExecutionResult delegateSave(Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
        var sr = sessionManager.getSession(params, tenantId, extractConversationId(ctx));
        if (sr.isError()) return sr.error();

        String nameOverride = (String) params.get("name");
        if (nameOverride != null && !nameOverride.isBlank()) sr.session().setWorkflowName(nameOverride);

        String descOverride = (String) params.get("description");
        if (descOverride != null && !descOverride.isBlank()) sr.session().setWorkflowDescription(descOverride);

        return resultEnricher.enrichResult(loader.executeSave(sr.session()), sr.session());
    }

    private ToolExecutionResult delegateCrud(String action, Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
        return crudModule.execute(action, params, tenantId, ctx)
            .orElse(ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "CRUD module failed for action: " + action));
    }

    private ToolExecutionResult delegateDiscard(Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
        var sr = sessionManager.getSession(params, tenantId, extractConversationId(ctx));
        if (sr.isError()) return sr.error();
        return loader.executeDiscard(sr.session());
    }

    /**
     * Unified node creation action: action='add_node', type='...', label='...', params={...}
     */
    private ToolExecutionResult executeAddNode(Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
        String type = safeString(params.get("type"));
        if (type == null || type.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "Missing required parameter 'type' for action='add_node'.\n" +
                "Usage: workflow(action='add_node', type='form|webhook|agent|decision|mcp|...', label='...', params={...})");
        }
        return handleNodeCreation(params, tenantId, type, ctx);
    }

    private ToolExecutionResult handleNodeCreation(Map<String, Object> params, String tenantId, String type, ToolExecutionContext ctx) {
        String label = safeString(params.get("label"));
        if (label == null || label.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "label is required for type='" + type + "'");
        }

        Object paramsObj = params.get("params");
        // LLM sometimes uses "parameters" instead of "params" - treat as equivalent
        if (paramsObj == null) {
            paramsObj = params.get("parameters");
        }
        Map<String, Object> merged = new LinkedHashMap<>(params);
        // Remove workflow-level 'action' from merged so it doesn't collide with node-specific 'action'
        // (e.g., guardrail's action=flag/block/redact vs workflow's action=add_node)
        merged.remove("action");
        if (paramsObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> paramsMap = (Map<String, Object>) paramsObj;
            paramsMap.forEach((k, v) -> merged.putIfAbsent(k, v));
        }

        // Silent alias: type='mcp' + tool_id in params → rewrite to type=<tool_id> with flat params
        // MUST run before validation so the rewritten UUID type skips schema validation
        if ("mcp".equalsIgnoreCase(type)) {
            Object toolIdFromParams = merged.get("tool_id");
            if (toolIdFromParams == null && paramsObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> innerParams = (Map<String, Object>) paramsObj;
                toolIdFromParams = innerParams.get("tool_id");
            }
            if (toolIdFromParams != null && !toolIdFromParams.toString().isBlank()) {
                String resolvedToolId = toolIdFromParams.toString().trim();
                log.debug("Rewriting type='mcp' + tool_id='{}' → type='{}'", resolvedToolId, resolvedToolId);
                type = resolvedToolId;
                merged.remove("tool_id");
                // Flatten nested params.params if present
                Object nestedParams = merged.get("params");
                if (nestedParams instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> nested = (Map<String, Object>) nestedParams;
                    nested.remove("tool_id");
                    Object innerToolParams = nested.get("params");
                    if (innerToolParams instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> flat = (Map<String, Object>) innerToolParams;
                        flat.forEach((k, v) -> merged.putIfAbsent(k, v));
                        nested.remove("params");
                    }
                    nested.forEach((k, v) -> merged.putIfAbsent(k, v));
                }
            }
        }

        // Validate node parameters against schema (skip for MCP tools, unknown types, triggers, and table operations)
        // MCP tools use catalog schema, unknown types may be aliases handled by creator
        // Table operations have their own validation in WorkflowBuilderTableOperations
        // Trigger types are validated by TriggerCreator (they share names with node_type_documentation but have different params)
        Set<String> tableTypes = Set.of("insert_row", "create_row", "read_rows", "get_rows", "get_row", "fetch_rows",
                "update_row", "modify_row", "delete_row", "remove_row", "find_rows", "find", "search_rows",
                "create_column", "add_column", "add_columns");
        Set<String> triggerTypes = Set.of("webhook", "schedule", "table", "manual", "chat", "form", "workflow", "error", "datasource");
        if (!isUuid(type) && !tableTypes.contains(type.toLowerCase()) && !triggerTypes.contains(type.toLowerCase())
                && nodeLibraryService.findByType(type).isPresent()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> nodeParams = paramsObj instanceof Map ? (Map<String, Object>) paramsObj : Map.of();
            ValidationResult validation = nodeParamsValidator.validate(type, nodeParams);
            if (!validation.valid()) {
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("validation_errors", validation.errors());
                metadata.put("suggestions", validation.suggestions());
                metadata.put("node_type", type);

                // Auto-include node help to save an iteration
                Map<String, Object> nodeHelp = workflowHelpProvider.getHelp(type);
                if (nodeHelp != null && !nodeHelp.isEmpty()) {
                    metadata.put("node_help", nodeHelp);
                }

                // Build detailed error message so LLM sees the actual problems
                StringBuilder errorMsg = new StringBuilder();
                errorMsg.append("Invalid parameters for node type '").append(type).append("':\n");
                for (var error : validation.errors()) {
                    errorMsg.append("  - ").append(error.message()).append("\n");
                }
                if (!validation.suggestions().isEmpty()) {
                    errorMsg.append("Suggestions:\n");
                    for (String suggestion : validation.suggestions()) {
                        errorMsg.append("  - ").append(suggestion).append("\n");
                    }
                }

                // Include node_help in error text so LLM sees full parameter schema
                if (nodeHelp != null && !nodeHelp.isEmpty()) {
                    errorMsg.append("\nNode '").append(type).append("' parameter reference:\n");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> helpParams = (Map<String, Object>) nodeHelp.get("params");
                    if (helpParams != null) {
                        for (Map.Entry<String, Object> paramEntry : helpParams.entrySet()) {
                            String paramName = paramEntry.getKey();
                            if (paramEntry.getValue() instanceof Map) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> paramDef = (Map<String, Object>) paramEntry.getValue();
                                String paramType = (String) paramDef.getOrDefault("type", "any");
                                boolean required = Boolean.TRUE.equals(paramDef.get("required"));
                                String desc = (String) paramDef.get("description");
                                Object example = paramDef.get("example");
                                errorMsg.append("  - ").append(paramName)
                                    .append(" (").append(paramType)
                                    .append(required ? ", REQUIRED" : ", optional").append(")");
                                if (desc != null) errorMsg.append(": ").append(desc);
                                if (example != null) errorMsg.append(" | example: ").append(example);
                                errorMsg.append("\n");
                            }
                        }
                    }
                }

                return ToolExecutionResult.failure(
                    ToolErrorCode.VALIDATION_ERROR,
                    errorMsg.toString(),
                    metadata
                );
            }
        }

        // Block creation of disabled node types
        String typeForEnabledCheck = type;
        String[] prefixesForCheck = {"trigger:", "agent:", "core:", "mcp:", "table:", "interface:", "note:"};
        for (String prefix : prefixesForCheck) {
            if (type.toLowerCase().startsWith(prefix)) {
                typeForEnabledCheck = type.substring(prefix.length());
                break;
            }
        }
        if (!nodeTypeSearchService.isNodeTypeEnabled(typeForEnabledCheck)) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Node type '" + typeForEnabledCheck + "' is disabled by the administrator.");
        }

        // Clean up type by removing common prefixes (LLM sometimes confuses edge refs with types)
        // e.g., "trigger:chat" -> "chat", "agent:agent" -> "agent", "core:decision" -> "decision"
        String cleanType = type;
        String[] prefixesToRemove = {"trigger:", "agent:", "core:", "mcp:", "table:", "interface:", "note:"};
        for (String prefix : prefixesToRemove) {
            if (type.toLowerCase().startsWith(prefix)) {
                cleanType = type.substring(prefix.length());
                log.debug("Cleaned type '{}' -> '{}'", type, cleanType);
                break;
            }
        }

        var sr = sessionManager.getSession(merged, tenantId, extractConversationId(ctx));
        if (sr.isError()) return sr.error();

        try {
            ToolExecutionResult result = switch (cleanType.toLowerCase()) {
                case "trigger" -> ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "type='trigger' is not valid. Use a specific trigger type: form, webhook, schedule, table, manual, chat, workflow, error.\n" +
                    "Example: workflow(action='add_node', type='form', label='My Form', params={...})");
                case "webhook", "schedule", "table", "manual", "chat", "form", "workflow", "error" -> {
                    merged.put("trigger_type", cleanType.toLowerCase());
                    yield creator.executeAddTrigger(sr.session(), merged, tenantId);
                }
                case "agent" -> creator.executeAddAgent(sr.session(), merged);
                case "browser_agent", "browseragent" -> {
                    // Browser-agent uses the same agents-array slot as the
                    // conversational agent but flags `agent_type=browser_agent`
                    // so the plan parser routes it to BrowserAgentNode at
                    // execution time. Reuses AgentCreator since the storage
                    // shape is identical.
                    merged.put("agent_type", "browser_agent");
                    merged.put("type", "browser_agent");
                    yield creator.executeAddAgent(sr.session(), merged);
                }
                case "guardrail-agent", "guardrail_agent", "guardrail" -> creator.executeAddGuardrail(sr.session(), merged);
                case "classify-agent", "classify_agent", "classify" -> creator.executeAddClassify(sr.session(), merged);
                case "decision" -> creator.executeAddDecision(sr.session(), merged);
                case "switch", "case" -> creator.executeAddSwitch(sr.session(), merged);
                case "split" -> creator.executeAddSplit(sr.session(), merged);
                case "fork", "parallel" -> creator.executeAddFork(sr.session(), merged);
                case "merge", "join", "sync" -> creator.executeAddMerge(sr.session(), merged);
                case "transform", "map" -> creator.executeAddTransform(sr.session(), merged);
                case "wait", "delay", "sleep" -> creator.executeAddWait(sr.session(), merged);
                case "download_file", "download", "fetch_file" -> creator.executeAddDownloadFile(sr.session(), merged);
                case "public_link", "public_url", "share_link" -> creator.executeAddPublicLink(sr.session(), merged);
                case "media", "audio", "mux" -> creator.executeAddMedia(sr.session(), merged);
                case "http_request", "http", "request", "api_call" -> creator.executeAddHttpRequest(sr.session(), merged);
                case "exit", "halt", "abort" -> creator.executeAddExit(sr.session(), merged);
                case "response", "message", "reply" -> creator.executeAddResponse(sr.session(), merged);
                case "option", "choice", "multi_choice" -> creator.executeAddOption(sr.session(), merged);
                case "aggregate", "collect", "reduce" -> creator.executeAddAggregate(sr.session(), merged);
                case "loop", "while", "for", "repeat" -> creator.executeAddLoop(sr.session(), merged);
                case "approval", "user_approval", "approve" -> creator.executeAddApproval(sr.session(), merged);
                case "data_input", "input", "datainput" -> creator.executeAddDataInput(sr.session(), merged);
                case "filter" -> creator.executeAddFilter(sr.session(), merged);
                case "sort", "order_by" -> creator.executeAddSort(sr.session(), merged);
                case "limit", "take", "top" -> creator.executeAddLimit(sr.session(), merged);
                case "remove_duplicates", "dedup", "deduplicate", "distinct" -> creator.executeAddRemoveDuplicates(sr.session(), merged);
                case "summarize", "pivot", "group_by" -> creator.executeAddSummarize(sr.session(), merged);
                case "date_time", "datetime", "date", "time" -> creator.executeAddDateTime(sr.session(), merged);
                case "crypto_jwt", "crypto", "jwt", "hash", "encrypt" -> creator.executeAddCryptoJwt(sr.session(), merged);
                case "xml", "xml_parse", "xml_to_json", "json_to_xml" -> creator.executeAddXml(sr.session(), merged);
                case "compression", "compress", "decompress", "gzip", "zip" -> creator.executeAddCompression(sr.session(), merged);
                case "rss", "rss_feed", "feed" -> creator.executeAddRss(sr.session(), merged);
                case "convert_to_file", "to_file", "export_file", "to_csv", "to_xlsx" -> creator.executeAddConvertToFile(sr.session(), merged);
                case "extract_from_file", "from_file", "import_file", "from_csv", "from_xlsx" -> creator.executeAddExtractFromFile(sr.session(), merged);
                case "compare_datasets", "compare", "diff", "dataset_diff" -> creator.executeAddCompareDatasets(sr.session(), merged);
                case "sub_workflow", "subworkflow", "call_workflow", "execute_workflow" -> creator.executeAddSubWorkflow(sr.session(), merged);
                case "respond_to_webhook", "webhook_response", "http_response" -> creator.executeAddRespondToWebhook(sr.session(), merged);
                case "send_email", "email", "smtp", "mail" -> creator.executeAddSendEmail(sr.session(), merged);
                case "email_inbox", "imap", "inbox", "read_email", "receive_email" -> creator.executeAddEmailInbox(sr.session(), merged);
                case "code", "code_node", "javascript", "python", "script" -> creator.executeAddCode(sr.session(), merged);
                case "set", "edit_fields", "set_fields", "assign" -> creator.executeAddSet(sr.session(), merged);
                case "html_extract", "html", "scrape", "css_extract" -> creator.executeAddHtmlExtract(sr.session(), merged);
                case "task", "task_crud", "add_task" -> creator.executeAddTask(sr.session(), merged);
                case "stop_on_error", "add_stop_on_error", "fail", "error_stop" -> creator.executeAddStopOnError(sr.session(), merged);
                case "ssh", "add_ssh", "remote_command" -> creator.executeAddSsh(sr.session(), merged);
                case "sftp", "add_sftp", "file_transfer" -> creator.executeAddSftp(sr.session(), merged);
                case "database", "add_database", "db", "sql" -> creator.executeAddDatabase(sr.session(), merged);
                case "interface", "display", "ui" -> creator.executeAddInterface(sr.session(), merged);
                case "insert_row", "create_row" -> tableOperations.execute(sr.session(), merged, tenantId, "insert_row");
                case "update_row", "modify_row" -> tableOperations.execute(sr.session(), merged, tenantId, "update_row");
                case "read_rows", "get_row", "get_rows", "fetch_rows" -> tableOperations.execute(sr.session(), merged, tenantId, "read_rows");
                case "delete_row", "remove_row" -> tableOperations.execute(sr.session(), merged, tenantId, "delete_row");
                case "find_rows", "find", "search_rows" -> tableOperations.execute(sr.session(), merged, tenantId, "find_rows");
                case "create_column", "add_column", "add_columns" -> tableOperations.execute(sr.session(), merged, tenantId, "create_column");
                // Unknown type = treat as tool UUID (MCP node)
                // Format: workflow(action='add_node', type='<tool-uuid>', label='...', params={...})
                default -> creator.executeAddMcp(sr.session(), merged, cleanType);
            };
            return resultEnricher.enrichResult(result, sr.session());
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.error("Error creating node type '{}' (cleaned: '{}'): {}", type, cleanType, errorMsg, e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to create '" + cleanType + "' node: " + errorMsg +
                ". Use workflow(action='help', topics=['" + cleanType + "']) to see required parameters.");
        }
    }

    private static String safeString(Object value) {
        if (value == null) return null;
        String str = value.toString().trim();
        return str.isEmpty() ? null : str;
    }

    /**
     * Check if a string is a valid UUID (for MCP tool detection).
     */
    private static boolean isUuid(String value) {
        if (value == null || value.length() != 36) return false;
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Extract conversationId from context credentials for session isolation.
     */
    private static String extractConversationId(ToolExecutionContext ctx) {
        return ctx != null && ctx.credentials() != null
            ? (String) ctx.credentials().get("conversationId")
            : null;
    }
}
