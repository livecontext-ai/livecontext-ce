package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.config.AgentModuleResolver;
import com.apimarketplace.agent.config.GuardOverrides;
import com.apimarketplace.agent.config.ToolAccessControl;
import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider;
import com.apimarketplace.agent.tools.authz.ToolAuthorizationScope;
import com.apimarketplace.agent.tools.common.ToolRateLimiter;
import com.apimarketplace.orchestrator.config.AgentDefaultsConfig;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.adhoc.AdHocNodeExecutionService;
import com.apimarketplace.orchestrator.execution.v2.adhoc.AdHocNodeRequest;
import com.apimarketplace.orchestrator.execution.v2.adhoc.AdHocNodeResult;
import com.apimarketplace.orchestrator.execution.v2.adhoc.AdHocNodeTypeResolver;
import com.apimarketplace.orchestrator.execution.v2.adhoc.AdHocSecretRedactor;
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

    /**
     * Per-plan availability of node types. Setter-injected and optional so the
     * existing constructor stays as it is (this class is built by hand in tests)
     * and so an assembly without auth-client simply gates nothing.
     */
    private com.apimarketplace.auth.client.entitlement.PlanFeatureGate planFeatureGate;

    // workflow(action='present'): switches the user's view. Setter-injected like the
    // gate above so the many hand-built provider fixtures keep their constructor.
    private WorkflowPresenter presenter;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPresenter(WorkflowPresenter presenter) {
        this.presenter = presenter;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPlanFeatureGate(
            com.apimarketplace.auth.client.entitlement.PlanFeatureGate planFeatureGate) {
        this.planFeatureGate = planFeatureGate;
    }
    private final AdHocNodeExecutionService adHocNodeExecutionService;
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

        boolean sessionReloaded = WorkflowBuilderActionConfig.RESYNC_BEFORE_WRITE_ACTIONS.contains(canonicalAction)
                && resyncWithStoredPlan(parameters, tenantId, context);

        try {
            ToolExecutionResult result = dispatchAction(canonicalAction, parameters, tenantId, context);
            if (sessionReloaded) {
                result = withSessionReloadedNotice(result);
            }
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
     * Rebuilds the current session from the stored plan when the builder canvas saved
     * it after the session last read or wrote it (see
     * {@link WorkflowBuilderLoader#resyncWithStoredPlan}). No session yet is a no-op:
     * the action itself reports that. A failure is logged and the action proceeds, as
     * it did before this check existed.
     *
     * @return true when the session was reloaded from the stored plan
     */
    private boolean resyncWithStoredPlan(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        try {
            var sr = sessionManager.getSession(parameters, tenantId, extractConversationId(context));
            return !sr.isError() && loader.resyncWithStoredPlan(sr.session());
        } catch (RuntimeException e) {
            log.warn("Could not check the stored plan before a session write for tenant {}: {}",
                    tenantId, e.getMessage());
            return false;
        }
    }

    static final String SESSION_RELOADED_NOTICE =
            "The workflow was changed and saved outside this session (for example in the editor, or by "
            + "another session) since your previous "
            + "workflow call. Your session was reloaded from that saved version before this call ran, so it "
            + "includes those changes, and the undo history from before this point is gone. "
            + "Call workflow(action='describe') to see the current nodes.";

    /**
     * Tells the agent its session was reloaded: otherwise an undo that now finds nothing,
     * or a connect to a node the user deleted in the editor, fails with no explanation.
     */
    private static ToolExecutionResult withSessionReloadedNotice(ToolExecutionResult result) {
        if (!result.success()) {
            String error = result.error() == null ? SESSION_RELOADED_NOTICE
                    : result.error() + " Note: " + SESSION_RELOADED_NOTICE;
            return new ToolExecutionResult(false, result.data(), error, result.errorCode(), result.metadata());
        }
        if (result.data() instanceof Map<?, ?> data) {
            Map<Object, Object> withNotice = new LinkedHashMap<>(data);
            withNotice.put("session_reloaded", SESSION_RELOADED_NOTICE);
            return new ToolExecutionResult(true, withNotice, null, null, result.metadata());
        }
        log.debug("Session reloaded before an action whose result has no map to carry the notice");
        return result;
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
            case "modify" -> delegateModify(params, tenantId, ctx);
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
            case "stop_run" -> delegateCrud("stop_run", params, tenantId, ctx);
            case "restart_from_node" -> delegateCrud("restart_from_node", params, tenantId, ctx);
            case "get_node_output" -> delegateCrud("get_node_output", params, tenantId, ctx);
            case "run_node" -> executeRunNode(params, tenantId, ctx);
            case "resolve_approval" -> executeResolveApproval(params, tenantId, ctx);
            case "continue_interface" -> executeContinueInterface(params, tenantId, ctx);
            case "present" -> presenter != null
                ? presenter.present(params, tenantId, ctx)
                : ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "present is not available here.");
            case "pin" -> delegateCrud("pin", params, tenantId, ctx);
            case "unpin" -> delegateCrud("unpin", params, tenantId, ctx);
            case "publish" -> delegateCrud("publish", params, tenantId, ctx);
            case "unpublish" -> delegateCrud("unpublish", params, tenantId, ctx);
            default -> ToolExecutionResult.failure(ToolErrorCode.INVALID_ENUM_VALUE, "Unknown action: " + action);
        };
    }

    // ==================== Standalone node execution (no workflow, no run) ====================

    /**
     * Run ONE node from a configuration, with no workflow and no run.
     *
     * <p>Session-free by design (same shape as resolve_approval / continue_interface): there is
     * no draft to attach to, and requiring one would force an agent to open a build session just
     * to try a node.
     *
     * <p><b>The config comes ONLY from {@code params}.</b> {@code add_node} flattens the whole
     * top-level argument map into the node config with top-level winning, which means a declared
     * tool parameter silently overwrites a node config key of the same name: {@code to} would
     * replace a send_email recipient, {@code workflow_id} the sub_workflow target, {@code query}
     * the SQL. That is survivable when it corrupts a draft you can inspect; it is not when the
     * node executes immediately. Nothing outside {@code params} is read as configuration here.
     */
    private ToolExecutionResult executeRunNode(Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
        ToolExecutionResult surfaceDenied = denyRunNodeOutsideConversation(ctx);
        if (surfaceDenied != null) return surfaceDenied;

        String rawType = safeString(params.get("type"));
        if (rawType == null || rawType.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "'type' is required. Example: workflow(action='run_node', type='http_request', "
                    + "params={url: 'https://example.com', method: 'GET'}).");
        }

        // The config must be an object. add_node accepts a non-Map here and silently creates an
        // EMPTY node; for a draft that is a puzzle, for something that executes immediately it
        // would mean running an unconfigured node against real credentials.
        Object paramsObj = params.containsKey("params") ? params.get("params") : params.get("parameters");
        if (paramsObj != null && !(paramsObj instanceof Map)) {
            return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR,
                "'params' must be an object, not " + paramsObj.getClass().getSimpleName()
                    + ". Send params={key: value}, not a JSON string.");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> nodeConfig = paramsObj instanceof Map
            ? new LinkedHashMap<>((Map<String, Object>) paramsObj)
            : new LinkedHashMap<>();

        Object runInputObj = params.get("run_input");
        if (runInputObj != null && !(runInputObj instanceof Map)) {
            return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR,
                "'run_input' must be an object mapping upstream names to values.");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> runInput = runInputObj instanceof Map
            ? new LinkedHashMap<>((Map<String, Object>) runInputObj)
            : new LinkedHashMap<>();

        // run_inputs = the same config run once per entry. Parsed here, but nothing executes until
        // EVERY gate below has passed: a batch must not be a way to get one item past a gate
        // that the single-item path refuses. Note that this buys the EXECUTION order, not the
        // diagnosis order: a call that is both oversized and of a type that cannot run standalone
        // is told about the size first. Nothing runs either way, and reordering to fix the message
        // would move the shape checks after the gates, which is the trade the wrong way round.
        Object itemsObj = params.get("run_inputs");
        List<Map<String, Object>> batchInputs = null;
        if (itemsObj != null) {
            if (runInputObj != null) {
                return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR,
                    "Give 'run_inputs' or 'run_input', not both. 'run_input' runs the node once; 'run_inputs' runs it "
                        + "once per entry. To batch, move the run_input into run_inputs as its first entry.");
            }
            // Defence in depth, not the agent's first line of defence: the tool layer declares
            // 'run_inputs' as an array and type-checks it before this runs, so an agent sending a
            // number or an object is already refused there, and a STRINGIFIED array is coerced
            // into a real list (a platform-wide convenience) and arrives here as one. What
            // survives to this branch is an in-process caller that skipped that layer.
            if (!(itemsObj instanceof List)) {
                return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR,
                    "'run_inputs' must be a list of objects, not " + itemsObj.getClass().getSimpleName()
                        + ". Send run_inputs=[{...}, {...}].");
            }
            List<?> rawItems = (List<?>) itemsObj;
            if (rawItems.isEmpty()) {
                return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR,
                    "'run_inputs' is empty, so there is nothing to run. Omit 'run_inputs' to run the node once.");
            }
            if (rawItems.size() > AdHocNodeExecutionService.MAX_BATCH_ITEMS) {
                return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR,
                    "'run_inputs' carries " + rawItems.size() + " entries; the limit is "
                        + AdHocNodeExecutionService.MAX_BATCH_ITEMS + " because they share one call's time "
                        + "budget. Send fewer, or build the node into a workflow with a split node and use "
                        + "workflow(action='execute') to run over any number of inputs.");
            }
            batchInputs = new ArrayList<>(rawItems.size());
            for (int i = 0; i < rawItems.size(); i++) {
                Object item = rawItems.get(i);
                if (!(item instanceof Map)) {
                    return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR,
                        "run_inputs[" + i + "] must be an object mapping upstream names to values, not "
                            + (item == null ? "null" : item.getClass().getSimpleName()) + ".");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> itemMap = new LinkedHashMap<>((Map<String, Object>) item);
                batchInputs.add(itemMap);
            }
        }

        String canonicalType = AdHocNodeTypeResolver.canonicalType(rawType);
        String refusal = AdHocNodeTypeResolver.refusalReason(canonicalType);
        if (refusal != null) {
            return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR, refusal);
        }
        // Gate the CANONICAL type, after alias resolution. Gating the typed name (what add_node
        // does) lets type='remote_command' through a gate an administrator set on 'ssh'.
        if (!nodeTypeSearchService.isNodeTypeEnabled(canonicalType)) {
            return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED,
                "Node type '" + canonicalType + "' is disabled by the administrator.");
        }
        // Same grant add_node requires. A generate node spends the customer's credits on a paid
        // provider; leaving it out here would reopen the opt-in one indirection away, this time
        // without even a workflow to show for it.
        if ("generate".equals(canonicalType)) {
            ToolExecutionResult generateDenied = refuseGenerateWithoutGrant(ctx);
            if (generateDenied != null) return generateDenied;
        }
        // The config must not be able to rewrite what the node IS. Without this, a type whose
        // config sits at the core's top level lets params={type:'ssh', ssh:{...}} build an SSH
        // node while every gate above was asked about the declared type, and while the
        // conversation and the observability row both record the declared type.
        for (String reserved : AdHocNodeRequest.RESERVED_CORE_KEYS) {
            if (nodeConfig.containsKey(reserved)) {
                return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR,
                    "'" + reserved + "' cannot be set in params: it identifies the node itself, not its "
                        + "configuration. Use the top-level 'type' and 'label' parameters.");
            }
        }

        String label = safeString(params.get("label"));
        AdHocNodeRequest request = new AdHocNodeRequest(
            canonicalType,
            AdHocNodeTypeResolver.configKey(canonicalType),
            nodeConfig,
            runInput,
            tenantId,
            ctx != null ? ctx.orgId() : null,
            ctx != null ? ctx.orgRole() : null,
            label != null && !label.isBlank() ? label : canonicalType);

        if (batchInputs != null) {
            List<AdHocNodeResult> outcomes = adHocNodeExecutionService.executeBatch(
                request, batchInputs, AdHocNodeExecutionService.MAX_BATCH_PARALLELISM);
            return buildRunNodeBatchResult(canonicalType, rawType, nodeConfig, outcomes);
        }

        AdHocNodeResult outcome = adHocNodeExecutionService.execute(request);
        return buildRunNodeResult(canonicalType, rawType, nodeConfig, outcome);
    }

    /**
     * Refuse run_node anywhere but a conversation, or null when the caller is in one.
     *
     * <p>The external {@code /mcp} endpoint runs this same tool registry with EMPTY credentials
     * and no turn: no authorization card can be raised there, no per-turn ceiling applies, and
     * nothing is written to the conversation. API-key scopes are per TOOL, never per action, so
     * every key already issued with the {@code workflow} scope would gain the ability to send
     * mail and run SQL the moment this ships, without its owner doing anything. Until scopes can
     * express an action, the honest answer is that this surface does not carry run_node.
     */
    private ToolExecutionResult denyRunNodeOutsideConversation(ToolExecutionContext ctx) {
        // Ask the SAME predicate that decides whether a card is raised, rather than a lookalike.
        // A plausible test on "is there a conversationId or a streamId?" says yes for a sub-agent,
        // for an agent-backed chat, and for an agent node inside an unattended scheduled run -
        // all of them EXEMPT from the card. run_node would then send mail and run SQL from an
        // agent-written config with nobody to ask, which is the exact hole this guard exists to
        // close. Gate on "a card will actually be raised", nothing weaker.
        if (ToolAuthorizationScope.isCardRaised(ctx != null ? ctx.credentials() : null)) {
            return null;
        }
        return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED,
            "run_node runs a node immediately with real side effects, so it is only available where "
                + "the user can be asked to authorize it: an interactive conversation. This context "
                + "(an external API key, a sub-agent, a task, or a workflow run) has no one to ask. "
                + "Build the node into a workflow and use workflow(action='execute') instead.");
    }

    /**
     * Report a batched {@code run_node}: one entry per item, and no top-level verdict.
     *
     * <p>There is deliberately NO top-level {@code output} or {@code error} key. An empty one
     * would read as "no output" / "no error" and be false the moment any item disagrees; an
     * agent that must look at {@code items} in the response to learn anything should not be handed a summary
     * field that answers first and wrongly.
     *
     * <p><b>One authorization covers the whole batch.</b> The card is raised per tool call, so a
     * user approving a 20-entry batch approves twenty real executions, where before they approved
     * one per call. The entry cap exists partly for that reason; raising it raises what a single
     * approval buys.
     *
     * <p><b>The agent's iteration cap is the second thing this dilutes</b>, and it is worth saying
     * out loud because it is the less obvious half. A loop that runs away is stopped by the
     * iteration ceiling, and batching turns twenty executions into one iteration against it, so
     * the same ceiling now bounds twenty times the work. Both multipliers are the same 20, which
     * is why the cap is deliberately a small number rather than "as many as fit in the budget":
     * the deadline bounds how long a batch takes, and the entry cap bounds what a single consent
     * and a single iteration are worth.
     *
     * <p>Note the deliberate asymmetry with the single-entry path: there, a node that timed out or
     * suspended makes the tool call itself fail, because the response has nothing else in it. Here
     * the same outcome SUCCEEDS, because the per-entry report is the thing worth reading. Both help
     * surfaces say so rather than leaving the agent to discover it.
     *
     * <p>The call FAILS in exactly two cases, both of which leave nothing to read: every entry ran
     * and failed, or none of them ever started. Anything else succeeds, including a batch that
     * returned four usable results and two errors, because reporting that as a failed tool call
     * would throw the four away; the caller reads {@code status} and the per-entry statuses to
     * decide. A mix of failures and never-started entries succeeds for the same reason, and is why
     * the all-failed message may not be used for it.
     */
    private ToolExecutionResult buildRunNodeBatchResult(String canonicalType, String rawType,
                                                        Map<String, Object> nodeConfig,
                                                        List<AdHocNodeResult> outcomes) {
        if (outcomes.isEmpty()) {
            // The surface refuses an empty list before calling the service, so this is reached
            // only by a caller that skipped it. Worth keeping rather than trusting: with no
            // entries the counters would call the batch "completed" and the verdict would call it
            // all-failed, in one response.
            return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR,
                "No entries were run, so there is nothing to report. Send at least one entry in "
                    + "run_inputs, or omit it to run the node once.");
        }
        int completed = 0;
        int failed = 0;
        int timedOut = 0;
        int notStarted = 0;
        int awaitingSignal = 0;
        int unknown = 0;
        long totalDuration = 0L;
        List<Map<String, Object>> items = new ArrayList<>(outcomes.size());
        for (int i = 0; i < outcomes.size(); i++) {
            AdHocNodeResult outcome = outcomes.get(i);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("index", i);
            entry.put("status", outcome.status());
            entry.put("duration_ms", outcome.durationMs());
            // AWAITING_SIGNAL keeps its output too: the node ran and produced one, and the single
            // path always emits it. Dropping it here would make the documented per-entry shape
            // {output or error} carry neither for that status.
            if (outcome.completed() || AdHocNodeResult.AWAITING_SIGNAL.equals(outcome.status())) {
                entry.put("output", outcome.output());
            }
            if (outcome.error() != null) entry.put("error", outcome.error());
            if (outcome.note() != null) entry.put("note", outcome.note());
            items.add(entry);
            totalDuration += outcome.durationMs();
            if (outcome.completed()) completed++;
            else if (AdHocNodeResult.NOT_STARTED.equals(outcome.status())) notStarted++;
            else if (AdHocNodeResult.TIMED_OUT.equals(outcome.status())) timedOut++;
            else if (AdHocNodeResult.AWAITING_SIGNAL.equals(outcome.status())) awaitingSignal++;
            else if (AdHocNodeResult.FAILED.equals(outcome.status())) failed++;
            // A status nobody here recognises is NOT a failure: counting it as one is how
            // awaiting-signal came to be reported as "all failed", and the next status added
            // would inherit the same bug. Unknown means unknown.
            else unknown++;
        }

        // awaiting_signal is neither a success nor a failure and must not fall through to
        // "failed": those items ran correctly and asked to be resumed, which only a real run can
        // do. Folding them into the failure verdict made the message assert they failed, that
        // there was no reason, and that every item failed the same way - none of it true.
        // "failed" only when EVERY entry failed. With a mix of failures and entries whose outcome
        // is unknown, calling the batch failed would assert something about the unknown ones that
        // nothing proved - the per-entry statuses are the answer.
        int size = outcomes.size();
        String status = completed == size ? "completed"
            : completed > 0 ? "partial"
            : failed == size ? "failed"
            : notStarted == size ? "not_started"
            : timedOut == size ? "timed_out"
            : awaitingSignal == size ? "awaiting_signal"
            : "unknown";


        Map<String, Object> body = new LinkedHashMap<>();
        body.put("node_type", canonicalType);
        if (!canonicalType.equals(rawType)) {
            body.put("resolved_from", rawType);
        }
        body.put("item_count", outcomes.size());
        body.put("completed", completed);
        body.put("failed", failed);
        body.put("timed_out", timedOut);
        // Counted apart from timed_out because the two call for opposite actions: a timed-out entry
        // may already have had its effect, a never-started one had none and is safe to resend.
        body.put("not_started", notStarted);
        // Emitted ONLY when there is something to report: an always-present "unrecognised": 0 is
        // one more field to interpret on every healthy batch.
        if (unknown > 0) {
            body.put("unrecognised", unknown);
        }
        // Its own count, never folded into timed_out: an awaiting-signal item is not "maybe still
        // running", it is stopped for good, and only a real run can resume it.
        body.put("awaiting_signal", awaitingSignal);
        body.put("status", status);
        // The SUM of the items, not elapsed time - they ran in parallel, so the call took far less.
        // Named for what it is so it cannot be read as a wall clock.
        body.put("total_item_duration_ms", totalDuration);
        body.put("items", items);
        // Redacted ONCE, on the shared config - the single-item path redacts the same map the
        // same way. Per-item output is NOT redacted, matching that path exactly.
        body.put("config", AdHocSecretRedactor.redactMap(nodeConfig, canonicalType));
        body.put("hint", "Nothing was saved. To keep this node, reuse the same config: "
            + "workflow(action='add_node', type='" + canonicalType + "', label='...', params={...}).");

        // Metadata travels with BOTH outcomes, which is why `status` is computed for the two
        // verdicts that never reach a body: a failed call carries no body at all, and this is then
        // the only place the shape of that failure survives. It is not agent-facing text and is
        // deliberately absent from the help, which enumerates what an agent can read.
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("toolName", "Run node: " + canonicalType + " x" + outcomes.size());
        metadata.put("adHocNodeType", canonicalType);
        metadata.put("adHocStatus", status);
        metadata.put("adHocItemCount", outcomes.size());

        // Fail ONLY when there is nothing to read: every entry ran and failed, or none ever
        // started. A MIX of failures and never-started entries still has results and reasons worth
        // reading, and routing it here would print "all N failed" over entries that never ran.
        if (!(failed == outcomes.size() || notStarted == outcomes.size())) {
            // A batch that timed out is NOT a failed batch: those entries may still be running and
            // may still have their effect, so calling it a failure would invite the agent to resend
            // work that is in flight. The per-entry statuses say what is unknown. Entries that
            // never STARTED are deliberately not counted here: a batch where nothing started
            // produced nothing to read, so it is reported as a failure with the reason.
            return ToolExecutionResult.success(body, metadata);
        }
        // Nothing to read: a failure result carries no body, so the reason has to be IN the
        // message.
        if (notStarted == outcomes.size()) {
            // Carry the entries' OWN reasons rather than asserting the clock. This branch is also
            // reached when the pool refused them or the call was interrupted, and a failure result
            // has no body, so the reasons the service worked to tell apart would be lost exactly
            // where they are the only thing left to read.
            String why = String.join(" | ", outcomes.stream()
                .map(AdHocNodeResult::error)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)));
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                "None of the " + outcomes.size() + " entries started (" + canonicalType + "), so "
                    + "nothing ran and nothing had an effect. "
                    + (why.isBlank() ? "Send them again." : why), metadata);
        }
        // Every entry ran and failed: they share a configuration, so say what they failed on.
        // Keep every DISTINCT reason: the items share a config but not their inputs, so they can
        // fail for different reasons, and a failure result carries no body to look them up in.
        java.util.LinkedHashSet<String> reasons = outcomes.stream()
            // Only error(): this branch runs when EVERY entry failed, and no failed outcome
            // carries a note - reading one would be dead code dressed as defensiveness.
            .map(AdHocNodeResult::error)
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        String detail = reasons.isEmpty()
            ? "the node did not complete and gave no reason."
            : String.join(" | ", reasons);
        return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
            "All " + outcomes.size() + " items failed (" + canonicalType + "): " + detail
                + (reasons.size() == 1
                    ? " Every item failed the same way, so fix it once and retry."
                    : " The reasons differ, so check each entry's input before retrying."), metadata);
    }

    /**
     * Shape the response, with the configuration echoed back REDACTED.
     *
     * <p>Echoing the config is what makes the result reusable: the agent pastes it into add_node.
     * Echoing it raw is what would write an ssh password into the conversation and into the
     * observability row, both stored in full.
     */
    private ToolExecutionResult buildRunNodeResult(String canonicalType, String rawType,
                                                   Map<String, Object> nodeConfig, AdHocNodeResult outcome) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("node_type", canonicalType);
        if (!canonicalType.equals(rawType)) {
            body.put("resolved_from", rawType);
        }
        body.put("status", outcome.status());
        body.put("duration_ms", outcome.durationMs());
        body.put("output", outcome.output());
        if (outcome.error() != null) body.put("error", outcome.error());
        if (outcome.note() != null) body.put("note", outcome.note());
        body.put("config", AdHocSecretRedactor.redactMap(nodeConfig, canonicalType));
        body.put("hint", "Nothing was saved. To keep this node, reuse the same config: "
            + "workflow(action='add_node', type='" + canonicalType + "', label='...', params={...}).");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("toolName", "Run node: " + canonicalType);
        metadata.put("adHocNodeType", canonicalType);
        metadata.put("adHocStatus", outcome.status());

        if (outcome.completed()) {
            return ToolExecutionResult.success(body, metadata);
        }
        // A failure carries only its message to the agent, so everything it needs to act has to
        // be IN that message: the reason AND the note that says what to do instead. Leaving the
        // note in the body would drop it exactly where it matters most.
        StringBuilder message = new StringBuilder();
        message.append(outcome.status()).append(" (").append(canonicalType).append("): ");
        message.append(outcome.error() != null ? outcome.error() : "the node did not complete.");
        if (outcome.note() != null) {
            message.append(' ').append(outcome.note());
        }
        return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, message.toString(), metadata);
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
                        // The production run may exist but be blocked on a signal. Telling the
                        // agent to mint a run and re-pin would move production AWAY from the run
                        // holding that signal, so that case gets its own remedy.
                        //
                        // Ask the FK directly rather than "newest AWAITING_SIGNAL at the pin":
                        // an editor run at the pin can be newer than production and would win a
                        // sorted lookup, making this branch fire about a run that is NOT
                        // production - and then forbid create-and-pin, the one remedy that
                        // works. Re-read the workflow because resolve() above may have rearmed
                        // the FK (healCorruptFk runs REQUIRES_NEW), leaving our copy stale.
                        //
                        // Reading by FK skips the showcase / version predicates the Production*
                        // queries carry, so re-apply them here: a heal that did not land leaves
                        // the FK on a frozen clone or a stale version, and neither is "the
                        // production run at v<pinned>" the message would claim.
                        // PENDING is deliberately NOT matched - a queued run is not
                        // signal-blocked, and "resolve it" would not be an actionable remedy.
                        var freshWorkflow = workflowService.getWorkflow(workflowId).orElse(workflow);
                        var blockedRun = freshWorkflow.getProductionRunId() == null
                            ? java.util.Optional.<com.apimarketplace.orchestrator.domain.WorkflowRunEntity>empty()
                            : workflowRunRepository.findById(freshWorkflow.getProductionRunId());
                        boolean blockedIsProduction = blockedRun.isPresent()
                            && blockedRun.get().getStatus() == com.apimarketplace.orchestrator.domain.workflow.RunStatus.AWAITING_SIGNAL
                            && !com.apimarketplace.orchestrator.trigger.ProductionRunResolver.isShowcaseRun(blockedRun.get())
                            && pinned.equals(blockedRun.get().getPlanVersion());
                        if (blockedIsProduction) {
                            String blockedId = blockedRun.get().getRunIdPublic();
                            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                                "The production run " + blockedId + " at pinned v" + pinned
                                + " is waiting on a signal, so it cannot take a production fire right now. "
                                + "See which signal with workflow(action='get_run', run_id='" + blockedId + "'), then: "
                                + "a pending approval clears with workflow(action='resolve_approval', run_id='"
                                + blockedId + "', decision='approved'); an interface waiting to advance clears with "
                                + "workflow(action='continue_interface', run_id='" + blockedId + "'); "
                                + "a wait timer or an inbound webhook clears on its own. "
                                + "Then retry with version='pinned'. "
                                + "Do NOT create and pin a new run: that would point production away from the run "
                                + "holding the pending signal.");
                        }
                        // Still "execute, then pin" on purpose, and NOT the removed pre-pin
                        // ritual: the workflow is ALREADY pinned here and its run resolves
                        // empty (typically a COMPLETED run - a deliberate stop, which pin
                        // provisioning does not override). Re-pinning alone would elect the
                        // same dead run, so a fresh run really does have to exist first.
                        return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "No production run found for workflow at pinned v" + pinned +
                            " (outcome=" + prodResolution.outcome() + "). " +
                            "The pinned version needs a run in COMPLETED, WAITING_TRIGGER, RUNNING or PAUSED to " +
                            "accumulate production fires - same bootstrap pattern as webhook/schedule triggers. " +
                            "Create one with workflow(action='execute', id='" + workflowIdStr + "', version=" + pinned + "), " +
                            "then workflow(action='pin', id='" + workflowIdStr + "', version=" + pinned + ") to point production at it, " +
                            "then retry with version='pinned'.");
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

    /** Package-private so the set_plan grant wiring is testable without the whole provider graph. */
    /**
     * Third and last door onto the same node. Creation was gated, then
     * set_plan; a node can also simply be RENAMED into a generation, which
     * spends the same money on the same provider.
     *
     * <p>Extracted to sit beside {@link #delegatePlanImport} rather than living
     * inline in the dispatch switch, so all three gates have the same shape and
     * the same kind of test: a missing check has to be provable, not argued.
     */
    ToolExecutionResult delegateModify(Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
        if (modifyTurnsANodeIntoAGeneration(params)) {
            ToolExecutionResult denied = refuseGenerateWithoutGrant(ctx);
            if (denied != null) return denied;
        }
        return delegateCreator(s -> modifier.executeModifyNode(s, params), params, tenantId, ctx);
    }

    ToolExecutionResult delegatePlanImport(Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
        // Same gate as add_node, because this creates the same node. A plan can
        // carry a generate node directly, so gating only the one-node action
        // left the grant reachable by writing the node into a plan instead of
        // adding it, which is the same money on the same provider.
        if (planCarriesAGenerateNode(params)) {
            ToolExecutionResult denied = refuseGenerateWithoutGrant(ctx);
            if (denied != null) return denied;
        }
        var sr = sessionManager.getSession(params, tenantId, extractConversationId(ctx));
        if (sr.isError()) return sr.error();
        return planExporter.executeSetPlan(sr.session(), params);
    }

    /**
     * Whether a modify turns a node INTO a generate node.
     *
     * <p>`type` is a writable top-level key, so a node added as something
     * harmless can be flipped afterwards. Gating creation and not mutation
     * would have left the grant reachable in two ordinary calls: add a
     * transform, then rename its type. Same node, same provider, same money.
     *
     * <p>Reads both spellings the action accepts, `params` and the legacy
     * `changes`, because gating only one of them is the same mistake one level
     * down.
     */
    @SuppressWarnings("unchecked")
    static boolean modifyTurnsANodeIntoAGeneration(Map<String, Object> params) {
        if (params == null) return false;
        for (String key : new String[]{"params", "changes"}) {
            Object changes = params.get(key);
            if (!(changes instanceof Map)) continue;
            Object type = ((Map<String, Object>) changes).get("type");
            if (type instanceof String s && "generate".equalsIgnoreCase(s.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a submitted plan declares a generate node.
     *
     * <p>Read defensively, because this runs BEFORE the plan is validated: any
     * shape may arrive, and a shape this does not recognise is a plan that
     * walks past the grant. Package-private so that is testable directly, which
     * is cheaper and more pointed than driving the whole provider.
     *
     * <p><b>Both lists are scanned, and that is the whole point.</b> generate
     * is filed under {@code agents} now that it is an AI node, but a plan
     * written by hand can put anything anywhere, and what decides whether a
     * node is BUILT is the export step, not the array it arrived in. Scanning
     * fewer buckets than the export reads leaves a grant that refuses the
     * one-node action while a whole plan carrying the same node walks past it,
     * and the node spends the customer's credits on a paid provider, which is
     * the reason the grant exists.
     *
     * <p><b>Why mcps is in the list.</b> The export files any session entry
     * carrying {@code isAgent} into the plan's agents, and import copies the
     * mcps array in verbatim, so an entry written there with
     * {@code isAgent: true, type: "generate"} is saved as an agent and built as
     * a real generate node. That route was inert while the node was built from
     * cores; moving it to the AI family is what made it work, so the gate has to
     * follow.
     */
    @SuppressWarnings("unchecked")
    static boolean planCarriesAGenerateNode(Map<String, Object> params) {
        Object planObj = params == null ? null : params.get("plan");
        if (!(planObj instanceof Map)) return false;
        Map<String, Object> plan = (Map<String, Object>) planObj;
        for (String bucket : List.of("agents", "cores", "mcps")) {
            Object listObj = plan.get(bucket);
            if (!(listObj instanceof List)) continue;
            for (Object node : (List<Object>) listObj) {
                if (!(node instanceof Map)) continue;
                Object type = ((Map<String, Object>) node).get("type");
                // "generate" exactly, because that is the only value the plan
                // validator turns into a generate node. Matching more would
                // refuse plans that never create one.
                if (type instanceof String s && "generate".equalsIgnoreCase(s.trim())) {
                    return true;
                }
            }
        }
        return false;
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
    /**
     * Refuse a generate node when the calling agent was not granted generation,
     * or null when it may proceed.
     *
     * <p>A generate node spends the customer's credits on a paid provider,
     * exactly as the generation tool does. That tool is opt-in; adding the node
     * was not, and building workflows is on by default for every agent, so the
     * opt-in was reachable one indirection away: add the node, run the
     * workflow, same money, no grant anywhere.
     *
     * <p>Only the CREATE side is gated here. Whether an existing node should
     * also be gated at RUN time is a different question with a different
     * answer: a workflow fired by a schedule or a webhook has no agent to ask,
     * and refusing there would break runs that belong to the workspace owner
     * rather than to any agent.
     */
    private ToolExecutionResult refuseGenerateWithoutGrant(ToolExecutionContext ctx) {
        Map<String, Object> credentials = ctx != null ? ctx.credentials() : null;
        // ONLY the generation grant. Accepting `image_generation` here as well
        // was a mistake of mine: this node runs any format, so an agent granted
        // images alone could build and run a per-second video node, which is
        // precisely the widening AgentModuleResolver says must never happen
        // silently. The two grants are asserted independent in both directions
        // on the chat surfaces, and a builder that disagreed with them would
        // make the platform's own rule false rather than merely inconsistent.
        if (AgentModuleResolver.callerMayUse(credentials, "generation")) {
            return null;
        }
        return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED,
                "You are not set up to run generations, so a generate node cannot be added. "
                        + "A generate node spends credits on a paid provider, which is why it is off "
                        + "until it is turned on for you. Enabling it is the workspace owner's decision "
                        + "and nothing you send can trigger it. Everything else in this workflow can "
                        + "still be built.");
    }

    /**
     * @return a refusal naming the plan that includes {@code type}, or {@code null}
     *         when the workspace may use it (including every failure mode - a gate
     *         that cannot read its configuration never blocks a build).
     */
    private ToolExecutionResult planRefusalOrNull(String type, String tenantId) {
        if (planFeatureGate == null || type == null || type.isBlank()) {
            return null;
        }
        String required;
        try {
            required = planFeatureGate.upgradeRequiredFor(tenantId, planFeatureKeys(type));
        } catch (Exception e) {
            log.warn("Plan gate lookup failed for node type '{}' - allowing: {}", type, e.getMessage());
            return null;
        }
        if (required == null) {
            return null;
        }
        return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED,
                "'" + type + "' is not included in this workspace's plan. It is available from the "
                        + required + " plan. "
                        + "You cannot change the plan yourself: tell the user which node you needed and that "
                        + "upgrading to " + required + " unlocks it. In the meantime, build the step with a node "
                        + "type the workspace already has (call workflow(action='search') to list them).");
    }

    /**
     * Gate keys for a {@code type} as add_node receives it, most specific first.
     *
     * <p>A node type is one key. A catalog tool written as {@code apiSlug/toolSlug}
     * is also checked as {@code tool:} then {@code api:}, so an agent is told at
     * BUILD time that the integration is not on this plan instead of building a
     * workflow that fails on its first run. The bare-slug and UUID forms carry no
     * API slug, so they are only caught later, by catalog-service.
     */
    /**
     * Spellings this builder accepts for one node type, mapped to the type the gate is keyed by.
     *
     * <p>Without this a gated node is addable under its alias: {@code browseragent} keys as
     * {@code node:browseragent}, which no requirement row names, so the build-time refusal is
     * skipped and the workflow instead fails at the step when {@code NodePlanGate} asks about the
     * real type. The run-time gate is the enforcement, but a node that builds and then always
     * fails is exactly what this check exists to prevent.
     */
    private static final Map<String, String> PLAN_KEY_ALIASES = Map.of(
            "browseragent", "browser_agent");

    private static java.util.List<String> planFeatureKeys(String type) {
        String value = type.trim().toLowerCase(java.util.Locale.ROOT);
        int slash = value.indexOf('/');
        if (slash > 0 && slash < value.length() - 1) {
            return java.util.List.of(
                    "tool:" + value.substring(slash + 1),
                    "api:" + value.substring(0, slash));
        }
        return java.util.List.of("node:" + PLAN_KEY_ALIASES.getOrDefault(value, value));
    }

    /**
     * The type with any node prefix removed, lowercased. Same stripping the dispatch below does,
     * which happens further down than the policy check needs it.
     */
    private static String bareNodeType(String type) {
        String bare = type == null ? "" : type.toLowerCase().trim();
        for (String prefix : List.of("trigger:", "core:", "agent:", "mcp:", "table:", "interface:", "note:")) {
            if (bare.startsWith(prefix)) {
                return bare.substring(prefix.length());
            }
        }
        return bare;
    }

    /**
     * The add_node types that create a TRIGGER. One list, used both to skip schema validation and to
     * refuse an execution policy: a second copy is how the two came to disagree.
     */
    private static final Set<String> TRIGGER_TYPES = Set.of(
            "webhook", "schedule", "table", "manual", "chat", "form", "workflow", "error", "datasource");

    /** Package-private so the add_node surface is reachable from a test, like delegateModify. */
    ToolExecutionResult executeAddNode(Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
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

        // Refuse a node type this workspace's plan does not include, HERE rather than at
        // run time: a node that can be added and then always fails is worse than one that
        // was never added, and the agent can pick a different type while it still has the
        // task in hand.
        ToolExecutionResult planRefusal = planRefusalOrNull(type, tenantId);
        if (planRefusal != null) {
            return planRefusal;
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

        // Lift the execution policy out of the call before anything else reads it. It is not a
        // parameter of the node's own type: left in the map the schema validator would refuse it
        // as unknown on a core node, and on an mcp node it would be handed to the provider as a
        // tool argument, because there every remaining key IS an endpoint argument.
        Object nodePolicyRequest = NodePolicyApplier.strip(merged);
        if (nodePolicyRequest != null) {
            // strip() replaces the nested container with a stripped COPY instead of mutating the
            // caller's argument map, so this local has to be re-pointed: it is what the schema
            // validator below reads, and the stale one still carries the policy.
            Object strippedNested = merged.get("params");
            if (!(strippedNested instanceof Map)) {
                strippedNested = merged.get("parameters");
            }
            if (strippedNested instanceof Map) {
                paramsObj = strippedNested;
            }
        }
        String policyError = NodePolicyApplier.validate(nodePolicyRequest);
        if (policyError == null) {
            // What the TYPE settles on its own is settled here, before the node exists: a caller
            // that reads a failure and retries add_node would otherwise get a duplicate node. That
            // is triggers and notes and nothing else - whether a type makes a provider call cannot
            // be read off it (a tool arrives as a UUID, a prefixed UUID or apiSlug/toolSlug, and an
            // unrecognised type IS a tool as far as the switch below is concerned), so that rule
            // runs after the creator, off the stored node id.
            String bareType = bareNodeType(type);
            policyError = NodePolicyApplier.rejectionForType(nodePolicyRequest,
                    TRIGGER_TYPES.contains(bareType) || "trigger".equals(bareType) || "note".equals(bareType));
        }
        if (policyError != null) {
            // Refused BEFORE the node exists. Refusing afterwards would leave a node behind that
            // reads as configured and behaves as if it were not, which is the worst outcome for a
            // caller that cannot look at the canvas.
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                    policyError + " No node was created. Send add_node again with a corrected "
                    + "nodePolicy. See workflow(action='help', topics=['node_policy']).");
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
        Set<String> triggerTypes = TRIGGER_TYPES;
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
                case "generate", "generation", "image_generation", "text_to_image",
                     "text_to_video", "text_to_speech" -> refuseGenerateWithoutGrant(ctx) != null
                        ? refuseGenerateWithoutGrant(ctx)
                        : creator.executeAddGenerate(sr.session(), merged);
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
            // The per-node execution policy, written once for every node type. Runs after the
            // creator, because the rules that remain need the node's real type, which only the
            // creator knows (the aliases this switch accepts are not the stored type).
            if (result != null && result.success() && nodePolicyRequest != null) {
                String createdNodeId = sr.session().getLastAddedNodeId();
                String rejection = NodePolicyApplier.rejectionForNode(createdNodeId,
                        sr.session().findNode(createdNodeId).orElse(null), nodePolicyRequest);
                if (rejection != null) {
                    // The node stays, WITHOUT the policy, and the caller is told so in the same
                    // breath. Leaving the node parseable matters more than a tidy all-or-nothing:
                    // an unparseable plan cannot be opened to repair, while an unpoliced node can
                    // be fixed with one modify.
                    return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                            "Node '" + createdNodeId + "' was created, but its execution policy was "
                            + "NOT applied: " + rejection + " Apply a corrected policy with "
                            + "workflow(action='modify', node='" + createdNodeId
                            + "', nodePolicy={...}).");
                }
                // Whether the node RESOLVED is the question here, not whether anything was
                // written: a caller may legitimately send nodePolicy={} (the documented way to
                // clear one) or a block of nothing but defaults, and on a node that has no policy
                // yet both correctly write nothing. Reading "no change" as "could not be written"
                // failed a call that had done exactly what was asked, on a node it had created.
                if (sr.session().findNode(createdNodeId).isEmpty()) {
                    log.error("Node policy could not be written after creating '{}' (type {}): "
                            + "the created node id did not resolve", createdNodeId, cleanType);
                    return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                            "Node '" + label + "' was created, but its execution policy could not "
                            + "be written to it. Set it with workflow(action='modify', node='"
                            + label + "', nodePolicy={...}) and check the result reports it.");
                }
                NodePolicyApplier.apply(sr.session(), nodePolicyRequest, createdNodeId);
                sessionManager.getSessionStore().save(sr.session());
                // Reported back for the same reason modify reports it: the caller cannot see the
                // canvas, so a policy it does not read back is a policy it cannot trust.
                result = NodePolicyApplier.describeInResult(result, sr.session(), createdNodeId);
            }
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
