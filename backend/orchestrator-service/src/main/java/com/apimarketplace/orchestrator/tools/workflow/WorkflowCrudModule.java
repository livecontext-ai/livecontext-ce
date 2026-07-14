package com.apimarketplace.orchestrator.tools.workflow;

import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.InterfaceDef;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.tools.utility.AgentCancellationProbe;
import org.springframework.beans.factory.annotation.Value;
import com.apimarketplace.orchestrator.tools.application.ApplicationShowcaseResolver;
import com.apimarketplace.orchestrator.repository.OffsetLimitPageable;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.services.WorkflowPinService;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlanParser;
import com.apimarketplace.agent.tools.common.AgentListEnvelope;
import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.orchestrator.tools.common.AgentResourceRequirements;
import com.apimarketplace.orchestrator.tools.common.AgentTriggerSchema;
import com.apimarketplace.agent.tools.common.ToolModule;
import com.apimarketplace.orchestrator.tools.workflow.builder.AgentWorkflowFireService;
import com.apimarketplace.publication.client.PublicationClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.apimarketplace.agent.tools.common.ToolParamUtils.*;
import static com.apimarketplace.agent.tools.common.ToolParamUtils.normalizeVisibility;
import static com.apimarketplace.agent.tools.common.ToolParamUtils.extractPublicationErrorMessage;
import com.apimarketplace.agent.tools.common.ToolParamUtils.InvalidVisibilityException;
import com.apimarketplace.agent.tools.ToolErrorCode;

/**
 * CRUD module for workflow tools.
 * Handles actions: get, list, delete, runs, get_run, wait_run, get_node_output (within the unified "workflow" tool).
 *
 * <p>Run inspection navigation (zoom in/out):
 * <ol>
 *   <li>{@code runs} → list runs for a workflow</li>
 *   <li>{@code get_run} (no epoch) → macro overview with epoch summaries</li>
 *   <li>{@code get_run} (epoch=N) → all nodes with status/label/type (no output data)</li>
 *   <li>{@code get_node_output} → full output/error for a single node</li>
 *   <li>{@code wait_run} → block until the run leaves PENDING/RUNNING (or timeout), then return the macro overview</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowCrudModule implements ToolModule {

    private final WorkflowManagementService workflowService;
    private final WorkflowRunRepository workflowRunRepository;
    private final AgentWorkflowFireService agentWorkflowFireService;
    private final WorkflowPlanVersionService planVersionService;
    private final WorkflowPinService pinService;
    private final PublicationClient publicationClient;
    private final CredentialClient credentialClient;
    private final com.apimarketplace.orchestrator.repository.WorkflowRepository workflowRepository;
    private final ApplicationShowcaseResolver showcaseResolver;
    private final AgentCancellationProbe cancellationProbe;

    /** wait_run bounds. The max (default 240s) must stay under the tightest hop that
     *  tolerates a silent in-flight tool call: the bridge kills a CLI session after
     *  5 min without stdout output and the agent loop's inactivity watchdog defaults
     *  to the same 5 min - a CLI emits nothing while an MCP tool call is blocking, so
     *  a single wait past ~290s dies as INACTIVITY_TIMEOUT. Longer waits are done by
     *  re-calling wait_run (each tool result re-arms the watchdogs). The workflow
     *  tool's own {@code timeoutMs} must exceed this max - see
     *  {@code WorkflowBuilderToolDefinitionFactory}. */
    @Value("${workflow.wait-run.default-timeout-seconds:120}")
    int waitRunDefaultTimeoutSeconds;
    @Value("${workflow.wait-run.max-timeout-seconds:240}")
    int waitRunMaxTimeoutSeconds;
    /** DB poll cadence while waiting. Package-visible for fast unit tests. */
    long waitRunPollIntervalMs = 2_000L;
    /** Cancellation poll granularity inside one DB-poll interval. */
    long waitRunSliceMs = 250L;

    private static final Set<String> HANDLED_ACTIONS = Set.of(
        "get", "list", "delete", "runs", "get_run", "wait_run", "get_node_output", "pin", "unpin",
        "publish", "unpublish"
    );

    @Override
    public List<AgentToolDefinition> getToolDefinitions() {
        return List.of(); // Definitions centralized in WorkflowBuilderProvider
    }

    @Override
    public boolean canHandle(String actionOrToolName) {
        return HANDLED_ACTIONS.contains(actionOrToolName);
    }

    @Override
    public Optional<ToolExecutionResult> execute(String action, Map<String, Object> parameters,
                                                  String tenantId, ToolExecutionContext context) {
        if (!canHandle(action)) return Optional.empty();

        // Access mode check (read/write)
        var accessDenied = com.apimarketplace.agent.config.ToolAccessControl.checkWriteAccess(
                context != null ? context.credentials() : null, "workflow", action);
        if (accessDenied.isPresent()) return Optional.of(ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, accessDenied.get()));

        return Optional.of(switch (action) {
            case "get" -> executeGet(parameters, tenantId, context);
            case "list" -> executeList(parameters, tenantId, context);
            case "delete" -> executeDelete(parameters, tenantId, context);
            case "runs" -> executeRuns(parameters, tenantId, context);
            case "get_run" -> executeGetRun(parameters, tenantId, context);
            case "wait_run" -> executeWaitRun(parameters, tenantId, context);
            case "get_node_output" -> executeGetNodeOutput(parameters, tenantId, context);
            case "pin" -> executePin(parameters, tenantId, context);
            case "unpin" -> executeUnpin(parameters, tenantId, context);
            case "publish" -> executePublish(parameters, tenantId, context);
            case "unpublish" -> executeUnpublish(parameters, tenantId);
            default -> ToolExecutionResult.failure(ToolErrorCode.INVALID_ENUM_VALUE, "Unknown action: " + action);
        });
    }

    // ==================== Pin / Unpin ====================

    private ToolExecutionResult executePin(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        String workflowIdStr = getStringParam(parameters, "workflow_id");
        if (workflowIdStr == null || workflowIdStr.isBlank()) {
            workflowIdStr = getStringParam(parameters, "id");
        }
        if (workflowIdStr == null || workflowIdStr.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "workflow_id is required for pin");
        }

        Integer version = getIntParam(parameters, "version");
        if (version == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "version is required for pin (positive integer). Use workflow(action='unpin', workflow_id='...') to clear the pin.");
        }
        if (version <= 0) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "version must be a positive integer, got " + version);
        }

        try {
            UUID workflowId = UUID.fromString(workflowIdStr);
            // Org-aware scope: a workflow tagged with an organization_id must be
            // pinned with the caller's active org context, otherwise the strict
            // scope check in WorkflowPinService rejects it as Forbidden (masked
            // as "Workflow not found"). Mirrors executeGet/executeDelete which
            // resolve the org from context - pin/unpin previously passed orgId=null.
            String orgId = context != null ? context.orgId() : null;
            return mapPinResult(pinService.pin(workflowId, tenantId, orgId, version), workflowIdStr);
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "Invalid workflow_id format: " + workflowIdStr);
        } catch (Exception e) {
            log.error("Failed to pin workflow {}: {}", workflowIdStr, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to pin: " + e.getMessage());
        }
    }

    private ToolExecutionResult executeUnpin(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        String workflowIdStr = getStringParam(parameters, "workflow_id");
        if (workflowIdStr == null || workflowIdStr.isBlank()) {
            workflowIdStr = getStringParam(parameters, "id");
        }
        if (workflowIdStr == null || workflowIdStr.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "workflow_id is required for unpin");
        }

        try {
            UUID workflowId = UUID.fromString(workflowIdStr);
            // Org-aware scope (see executePin) - pass the caller's active org so an
            // org-tagged workflow is not rejected as Forbidden/"Workflow not found".
            String orgId = context != null ? context.orgId() : null;
            return mapPinResult(pinService.pin(workflowId, tenantId, orgId, null), workflowIdStr);
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "Invalid workflow_id format: " + workflowIdStr);
        } catch (Exception e) {
            log.error("Failed to unpin workflow {}: {}", workflowIdStr, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to unpin: " + e.getMessage());
        }
    }

    private ToolExecutionResult mapPinResult(WorkflowPinService.PinResult result, String workflowIdStr) {
        return switch (result) {
            case WorkflowPinService.PinResult.NotFound ignored ->
                    ToolExecutionResult.failure(ToolErrorCode.WORKFLOW_NOT_FOUND, "Workflow not found: " + workflowIdStr);
            case WorkflowPinService.PinResult.Forbidden ignored ->
                    ToolExecutionResult.failure(ToolErrorCode.WORKFLOW_NOT_FOUND, "Workflow not found: " + workflowIdStr);
            case WorkflowPinService.PinResult.VersionNotFound vnf ->
                    ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Version " + vnf.version() + " not found for workflow " + workflowIdStr);
            case WorkflowPinService.PinResult.NoSuccessfulRun nsr ->
                    ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Cannot pin v" + nsr.version() + ": no successful run exists for this version. " +
                            "Start a run with workflow(action='execute', id='" + workflowIdStr + "', version=" + nsr.version() +
                            ") first, then retry the pin.");
            case WorkflowPinService.PinResult.Success s -> {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("workflow_id", workflowIdStr);
                data.put("pinned_version", s.pinnedVersion());
                data.put("is_production", s.pinnedVersion() != null);
                data.put("status", s.pinnedVersion() != null ? "PINNED" : "UNPINNED");
                data.put("message", s.pinnedVersion() != null
                        ? "Version " + s.pinnedVersion() + " pinned as production. " +
                          "Webhook/schedule triggers and workflow(action='execute', version='pinned') will use this version."
                        : "Workflow unpinned. Production triggers will be rejected until a version is pinned again.");
                yield ToolExecutionResult.success(data);
            }
        };
    }

    // ==================== Publish / Unpublish ====================

    private ToolExecutionResult executePublish(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        String workflowIdStr = getStringParam(parameters, "workflow_id");
        if (workflowIdStr == null || workflowIdStr.isBlank()) {
            workflowIdStr = getStringParam(parameters, "id");
        }
        if (workflowIdStr == null || workflowIdStr.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "workflow_id is required for publish");
        }

        String title = getStringParam(parameters, "title");
        if (title == null || title.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "title is required for publish");
        }

        UUID workflowId;
        try {
            workflowId = UUID.fromString(workflowIdStr);
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "Invalid workflow_id format: " + workflowIdStr);
        }

        // Build request payload for publication-service
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("workflowId", workflowId.toString());
        request.put("title", title);

        String description = getStringParam(parameters, "description");
        if (description != null && !description.isBlank()) request.put("description", description);

        // Interface showcase: a workflow with interface nodes is meant to be USED through them,
        // so publishing it must produce an APPLICATION (a publication is an application iff it
        // carries a showcaseInterfaceId). Mirror application(action='create') here so
        // workflow(action='publish') never silently ships a showcase-less listing whose app
        // preview renders nothing: when the agent omits interface_id and the workflow has
        // interfaces, auto-pick the entry interface + the latest showcaseable run and flag
        // display_mode=APPLICATION. A workflow with NO interface stays a plain WORKFLOW
        // publication - behaviour unchanged.
        String interfaceId = getStringParam(parameters, "interface_id");
        String showcaseRunId = getStringParam(parameters, "showcase_run_id");
        List<String> autoApplicationNotes = new ArrayList<>();

        if (interfaceId == null || interfaceId.isBlank()) {
            Optional<InterfaceDef> entry = showcaseResolver.resolveEntryInterface(
                loadPlanForShowcase(workflowId, tenantId));
            if (entry.isPresent()) {
                interfaceId = entry.get().id();
                String label = entry.get().label() != null ? entry.get().label() : entry.get().id();
                autoApplicationNotes.add("Auto-selected entry interface '" + label
                    + "' as the showcase landing page (pass interface_id to override).");
            }
        }

        if (interfaceId != null && !interfaceId.isBlank()) {
            request.put("showcaseInterfaceId", interfaceId);
            // A showcase interface means this listing IS an application - keep display_mode in
            // lockstep with isApplication so the app surface renders the interface, not a card.
            request.put("displayMode", "APPLICATION");

            if (showcaseRunId == null || showcaseRunId.isBlank()) {
                Optional<String> autoRun = showcaseResolver.resolveLatestShowcaseRunId(workflowId);
                if (autoRun.isPresent()) {
                    showcaseRunId = autoRun.get();
                    if (!autoApplicationNotes.isEmpty()) {
                        autoApplicationNotes.add("Auto-selected the latest successful run as the showcase.");
                    }
                } else if (!autoApplicationNotes.isEmpty()) {
                    autoApplicationNotes.add("No successful run to showcase yet - the app preview stays "
                        + "empty until you run the workflow (workflow(action='execute')) and re-publish.");
                }
            }
        }

        if (showcaseRunId != null && !showcaseRunId.isBlank()) {
            request.put("showcaseRunId", showcaseRunId);
        }

        String categoryId = getStringParam(parameters, "category_id");
        if (categoryId != null && !categoryId.isBlank()) {
            request.put("categoryId", categoryId);
        }

        Integer creditsPerUse = getIntParam(parameters, "credits_per_use", 0);
        request.put("creditsPerUse", creditsPerUse);

        String visibility;
        try {
            visibility = normalizeVisibility(getStringParam(parameters, "visibility"));
        } catch (InvalidVisibilityException e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, e.getMessage());
        }
        request.put("visibility", visibility);

        try {
            // Audit 2026-05-16 round-2: thread ctx.orgId() so publication owner_type='ORG' in org workspace.
            String orgId = context != null ? context.orgId() : null;
            Map<String, Object> response = publicationClient.publishWorkflow(request, tenantId, orgId);
            if (response == null) {
                return ToolExecutionResult.failure(ToolErrorCode.EXTERNAL_SERVICE_ERROR, "Publication service returned no response");
            }

            String publicationStatus = response.get("status") != null
                    ? response.get("status").toString() : "ACTIVE";
            boolean pending = "PENDING_REVIEW".equals(publicationStatus);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", publicationStatus);
            data.put("workflow_id", workflowIdStr);
            data.put("publication_id", response.get("id"));
            data.put("title", response.getOrDefault("title", title));
            data.put("visibility", visibility);
            data.put("credits_per_use", creditsPerUse);
            // Surface the showcase resolution so the agent knows the listing became an application
            // (and which interface/run it landed on) - it never had to choose them.
            if (request.containsKey("showcaseInterfaceId")) {
                data.put("display_mode", "APPLICATION");
                data.put("showcase_interface_id", request.get("showcaseInterfaceId"));
                if (request.containsKey("showcaseRunId")) {
                    data.put("showcase_run_id", request.get("showcaseRunId"));
                }
            }
            if (!autoApplicationNotes.isEmpty()) {
                data.put("auto_application", autoApplicationNotes);
            }
            data.put("message", (pending
                    ? "Workflow submitted for review - not yet visible on the marketplace. "
                    : "Workflow published. ")
                    + (request.containsKey("showcaseInterfaceId") ? "Published as an application. " : "")
                    + "Marketplace publication id: " + response.get("id"));
            return ToolExecutionResult.success(data);
        } catch (RuntimeException e) {
            String msg = extractPublicationErrorMessage(e);
            log.warn("Failed to publish workflow {}: {}", workflowIdStr, msg);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to publish workflow: " + msg);
        }
    }

    /**
     * Parse the workflow's latest saved plan for showcase auto-resolution. Returns null when the
     * workflow is missing, has no plan, or fails to parse - the caller then skips app auto-promotion
     * and falls back to a plain workflow publish (ownership is enforced downstream by publication-service).
     */
    private WorkflowPlan loadPlanForShowcase(UUID workflowId, String tenantId) {
        try {
            return workflowRepository.findById(workflowId)
                .map(WorkflowEntity::getPlan)
                .filter(p -> p != null && !p.isEmpty())
                .map(p -> WorkflowPlanParser.parse(p, workflowId.toString(), tenantId))
                .orElse(null);
        } catch (RuntimeException e) {
            log.warn("[publish] could not load plan for workflow {} - skipping application auto-promotion: {}",
                    workflowId, e.getMessage());
            return null;
        }
    }

    private ToolExecutionResult executeUnpublish(Map<String, Object> parameters, String tenantId) {
        String workflowIdStr = getStringParam(parameters, "workflow_id");
        if (workflowIdStr == null || workflowIdStr.isBlank()) {
            workflowIdStr = getStringParam(parameters, "id");
        }
        if (workflowIdStr == null || workflowIdStr.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "workflow_id is required for unpublish");
        }

        UUID workflowId;
        try {
            workflowId = UUID.fromString(workflowIdStr);
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "Invalid workflow_id format: " + workflowIdStr);
        }

        if (!publicationClient.isWorkflowPublished(workflowId, tenantId)) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Resource not published: workflow " + workflowIdStr + " has no active publication");
        }

        publicationClient.unpublishByWorkflowId(workflowId, tenantId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UNPUBLISHED");
        data.put("workflow_id", workflowIdStr);
        data.put("message", "Workflow publication marked inactive. Existing acquirers keep their copies.");
        return ToolExecutionResult.success(data);
    }

    // ==================== Get ====================

    private ToolExecutionResult executeGet(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        String workflowIdStr = getStringParam(parameters, "workflow_id");
        if (workflowIdStr == null || workflowIdStr.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "workflow_id is required");
        }

        var workflowDenied = denyIfWorkflowNotAllowed(context, workflowIdStr);
        if (workflowDenied.isPresent()) return workflowDenied.get();

        try {
            UUID workflowId = UUID.fromString(workflowIdStr);
            var workflowOpt = workflowService.getWorkflow(workflowId);
            if (workflowOpt.isEmpty()) {
                return ToolExecutionResult.failure(ToolErrorCode.WORKFLOW_NOT_FOUND, "Workflow not found: " + workflowIdStr);
            }

            WorkflowEntity workflow = workflowOpt.get();
            // Scope check - org-aware. Pre-fix this was strict-tenant equality,
            // so a workflow tagged with an org_id was 404'd for any member of
            // the org with a different tenant_id (e.g. team workspace member
            // querying a workflow created by the owner). 404 (not 403) on
            // out-of-scope is intentional: do not leak workflow existence
            // across workspace boundaries.
            String orgId = context != null ? context.orgId() : null;
            if (!ScopeGuard.isInStrictScope(tenantId, orgId,
                    workflow.getTenantId(), workflow.getOrganizationId())) {
                return ToolExecutionResult.failure(ToolErrorCode.WORKFLOW_NOT_FOUND, "Workflow not found: " + workflowIdStr);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", workflow.getId().toString());
            result.put("name", workflow.getName() != null ? workflow.getName() : "");
            result.put("tenantId", workflow.getTenantId() != null ? workflow.getTenantId() : "");
            result.put("plan", workflow.getPlan() != null ? workflow.getPlan() : Map.of());
            result.put("createdAt", workflow.getCreatedAt() != null ? workflow.getCreatedAt().toString() : "");
            result.put("updatedAt", workflow.getUpdatedAt() != null ? workflow.getUpdatedAt().toString() : "");
            result.put("pinned_version", workflow.getPinnedVersion());
            result.put("is_production", workflow.getPinnedVersion() != null);
            result.put("latest_version", planVersionService.getCurrentVersion(workflow.getId()));

            // Mirror ApplicationCrudModule.executeGet: surface the agent-flat
            // `data_inputs_schema` and `fireable_triggers` parsed from the plan, so
            // the agent doesn't have to walk the raw plan JSON to learn the field
            // names. The list path (workflow.list) carries only `trigger_types` for
            // payload size; the schema lives on `get`. Best-effort - any parse
            // failure just omits the keys (the raw plan is still in `result.plan`
            // for the agent to inspect manually).
            try {
                Map<String, Object> planData = workflow.getPlan();
                if (planData != null && !planData.isEmpty()) {
                    WorkflowPlan parsedPlan = WorkflowPlanParser.parse(
                            planData, workflow.getId().toString(), tenantId);
                    String defaultTrigger = AgentTriggerSchema.defaultTriggerId(parsedPlan);
                    if (defaultTrigger != null) result.put("default_trigger_id", defaultTrigger);
                    List<String> triggerTypes = AgentTriggerSchema.fireableTriggerTypes(parsedPlan);
                    if (!triggerTypes.isEmpty()) result.put("trigger_types", triggerTypes);
                    Map<String, Object> schema = AgentTriggerSchema.dataInputsSchema(parsedPlan);
                    if (schema != null) result.put("data_inputs_schema", schema);
                    if (defaultTrigger == null) {
                        List<Map<String, Object>> all = AgentTriggerSchema.fireableTriggers(parsedPlan, true);
                        if (all.size() > 1) result.put("fireable_triggers", all);
                    }
                }
            } catch (Exception e) {
                log.debug("workflow.get: trigger schema enrichment skipped for {}: {}",
                        workflow.getId(), e.getMessage());
            }

            // No `visualization` metadata on read-only `get` - would hijack the user's
            // side-panel focus on every inspection call. The agent already has the
            // workflow context via `data.id` + `data.name`; UX-side, the user clicks
            // explicit visualize markers in chat when they want to open the tab.
            // Mirrors ApplicationCrudModule.executeGet behaviour.
            return ToolExecutionResult.success(result);
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "Invalid workflow_id format: " + workflowIdStr);
        } catch (Exception e) {
            log.error("Failed to get workflow: {}", e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to get workflow: " + e.getMessage());
        }
    }

    // ==================== List ====================

    private ToolExecutionResult executeList(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        // workflow.list = STANDARD caps (default=25 / max=50 / hintThreshold=100,
        // hardRefuse auto=400). `query` (name/description substring) is the one
        // refinement filter, so the `refine` hint suggests it on large result sets.
        String query = getStringParam(parameters, "query");
        AgentListEnvelope.Spec spec = AgentListEnvelope.Spec.of(
                        AgentListEnvelope.Caps.STANDARD, "workflows", "workflows", "workflows")
                .withSuggestedFilters(List.of("query"))
                .withNext(Map.of(
                        "details_with_schema", "workflow(action='get', workflow_id='<id>') - returns full plan + data_inputs_schema for fireable triggers (list items only carry trigger_types; the schema is on get)",
                        "edit", "workflow(action='load', id='<id>')",
                        "execute", "workflow(action='get', workflow_id='<id>') to read data_inputs_schema, then workflow(action='load', id='<id>') then workflow(action='execute', data_inputs={...})",
                        "delete", "workflow(action='delete', workflow_id='<id>')",
                        "history", "workflow(action='runs', workflow_id='<id>')",
                        "open_app_if_published", "application(action='get', application_id='<application_id>') - use the application_id field on items where has_application=true"
                ));
        AgentListEnvelope.Bounds bounds;
        try {
            // Active-filter set is server-derived from the request (never a caller
            // boolean) so the hard-refuse-without-filter guard uses the real truth.
            Set<String> activeFilters = hasQuery(query) ? Set.of("query") : Set.of();
            bounds = AgentListEnvelope.readBounds(parameters, spec, activeFilters);
        } catch (AgentListEnvelope.InvalidParamsException e) {
            // Structured `code:` prefix lets the agent error-mapper parse the failure.
            // Bare-string failure here matches the rest of WorkflowCrudModule's convention
            // (ApplicationCrudModule uses ToolErrorCode - each module stays internally consistent).
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, e.code + ": " + e.getMessage());
        }

        try {
            String orgId = context != null ? context.orgId() : null;
            String orgRole = context != null ? context.orgRole() : null;
            List<WorkflowEntity> allWorkflows = workflowService.listWorkflows(tenantId, orgId, orgRole);

            List<String> allowedWorkflowIds = getAllowedWorkflowIds(context);
            if (allowedWorkflowIds != null) {
                allWorkflows = allWorkflows.stream()
                    .filter(w -> w.getId() != null && allowedWorkflowIds.contains(w.getId().toString()))
                    .toList();
                log.info("Agent restriction: filtered workflows to {}/{} allowed",
                    allWorkflows.size(), allowedWorkflowIds.size());
            }

            // Text search: case-insensitive substring over name + description, applied
            // BEFORE pagination so total/hasMore reflect the filtered set.
            if (hasQuery(query)) {
                allWorkflows = allWorkflows.stream()
                    .filter(w -> matchesQuery(query, w.getName(), w.getDescription()))
                    .toList();
            }

            long total = allWorkflows.size();

            List<WorkflowEntity> page = allWorkflows.stream()
                    .skip(bounds.offset()).limit(bounds.limit())
                    .toList();

            // Batch (workflowId → applicationId) lookup. Embedding the publication id
            // directly avoids forcing the agent to call application(action='my') just
            // to discover the marketplace id of one of its own workflows (audit gap
            // #6 - workflow.list.has_application was a dead-end signal otherwise).
            Map<UUID, UUID> appIdByWorkflowId = Map.of();
            if (!page.isEmpty()) {
                List<UUID> pageIds = page.stream()
                        .map(WorkflowEntity::getId).filter(Objects::nonNull).toList();
                try {
                    appIdByWorkflowId = publicationClient
                            .findActivePublicationIdsByWorkflowIds(pageIds, tenantId);
                } catch (Exception e) {
                    log.debug("workflow.list: application_id enrichment skipped: {}", e.getMessage());
                }
            }
            Map<UUID, UUID> finalAppIdByWorkflowId = appIdByWorkflowId;

            // PR4 - pre-batch requirements lookups. ONE parse per item, cached for
            // the enrichment pass; ONE credential-service call; ONE workflow-repo
            // batch query for sub-workflow existence. The N+1 trap is closed at
            // entry: per-item enrichListItem reads from the prefetched maps, never
            // hits I/O.
            Map<UUID, WorkflowPlan> parsedByWorkflowId = new HashMap<>();
            Set<UUID> referencedSubWorkflowIds = new LinkedHashSet<>();
            for (WorkflowEntity w : page) {
                Map<String, Object> planData = w.getPlan();
                if (planData == null || planData.isEmpty() || w.getId() == null) continue;
                try {
                    WorkflowPlan plan = WorkflowPlanParser.parse(
                            planData, w.getId().toString(), tenantId);
                    parsedByWorkflowId.put(w.getId(), plan);
                    for (var sub : AgentResourceRequirements.subWorkflowsFromPlan(plan)) {
                        referencedSubWorkflowIds.add(sub.workflowId());
                    }
                } catch (Exception e) {
                    log.debug("workflow.list: plan parse failed for {}: {}", w.getId(), e.getMessage());
                }
            }
            Set<String> tenantConfiguredIntegrations = Set.of();
            try {
                tenantConfiguredIntegrations = credentialClient.getConfiguredIntegrations(tenantId);
            } catch (Exception e) {
                log.debug("workflow.list: configured-integrations lookup skipped: {}", e.getMessage());
            }
            Set<UUID> existingSubWorkflowIds = new HashSet<>();
            if (!referencedSubWorkflowIds.isEmpty()) {
                try {
                    workflowRepository.findAllById(referencedSubWorkflowIds)
                            .forEach(w -> { if (w.getId() != null) existingSubWorkflowIds.add(w.getId()); });
                } catch (Exception e) {
                    log.debug("workflow.list: sub-workflow existence lookup skipped: {}", e.getMessage());
                }
            }
            Set<String> finalConfiguredIntegrations = tenantConfiguredIntegrations;
            Set<UUID> finalExistingSubWorkflowIds = existingSubWorkflowIds;

            List<Map<String, Object>> summaries = page.stream()
                .map(w -> {
                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("id", w.getId() != null ? w.getId().toString() : "");
                    summary.put("name", w.getName() != null ? w.getName() : "");
                    if (w.getDescription() != null && !w.getDescription().isBlank()) {
                        summary.put("description", w.getDescription());
                    }
                    summary.put("createdAt", w.getCreatedAt() != null ? w.getCreatedAt().toString() : "");
                    summary.put("updatedAt", w.getUpdatedAt() != null ? w.getUpdatedAt().toString() : "");
                    summary.put("pinned_version", w.getPinnedVersion());
                    summary.put("is_production", w.getPinnedVersion() != null);
                    summary.put("latest_version", w.getId() != null
                            ? planVersionService.getCurrentVersion(w.getId()) : 0);
                    UUID appId = w.getId() != null ? finalAppIdByWorkflowId.get(w.getId()) : null;
                    summary.put("has_application", appId != null);
                    if (appId != null) summary.put("application_id", appId.toString());
                    WorkflowPlan cachedPlan = w.getId() != null ? parsedByWorkflowId.get(w.getId()) : null;
                    enrichListItem(summary, w, cachedPlan,
                            finalConfiguredIntegrations, finalExistingSubWorkflowIds);
                    return summary;
                }).toList();

            Map<String, Object> result = new LinkedHashMap<>(
                    AgentListEnvelope.paginateProjection(summaries, bounds, total, spec));

            return ToolExecutionResult.success(result);
        } catch (Exception e) {
            log.error("Failed to list workflows: {}", e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to list workflows: " + e.getMessage());
        }
    }

    /**
     * Best-effort per-item enrichment for {@link #executeList}.
     *
     * <p>Adds {@code trigger_types} (parsed from the plan - tells the agent
     * whether this workflow is webhook/schedule/chat/form-fireable without a
     * second {@code workflow(action='get')}) and {@code last_run} (status + at,
     * a freshness signal). Any failure is swallowed: the base summary is still
     * returned.
     */
    /**
     * Per-item enrichment. The caller pre-parses the plan ONCE (cached in
     * {@code cachedPlan}) and pre-batches credential/sub-workflow lookups so this
     * method is pure I/O for last_run only (single-row DB query).
     */
    @SuppressWarnings("unchecked")
    private void enrichListItem(Map<String, Object> summary,
                                 WorkflowEntity workflow,
                                 WorkflowPlan cachedPlan,
                                 Set<String> configuredIntegrations,
                                 Set<UUID> existingSubWorkflowIds) {
        if (cachedPlan != null) {
            try {
                List<String> types = AgentTriggerSchema.fireableTriggerTypes(cachedPlan);
                if (!types.isEmpty()) summary.put("trigger_types", types);
            } catch (Exception e) {
                log.debug("workflow.list: trigger_types skipped for {}: {}", workflow.getId(), e.getMessage());
            }
            // PR4 - requirements (integrations + sub-workflows) reuses the same parse
            // for sub-workflow refs; integrations come from the pre-computed
            // WorkflowEntity.nodeIcons (no second walk).
            try {
                List<AgentResourceRequirements.RequiredIntegration> integrations =
                        AgentResourceRequirements.integrationsFromNodeIcons(
                                (List<Map<String, Object>>) (List<?>) workflow.getNodeIcons());
                List<AgentResourceRequirements.RequiredSubWorkflow> subWorkflows =
                        AgentResourceRequirements.subWorkflowsFromPlan(cachedPlan);
                Map<String, Object> req = AgentResourceRequirements.buildEnvelope(
                        integrations, subWorkflows, configuredIntegrations, existingSubWorkflowIds);
                if (req != null) summary.put("requirements", req);
            } catch (Exception e) {
                log.debug("workflow.list: requirements skipped for {}: {}", workflow.getId(), e.getMessage());
            }
        }
        try {
            if (workflow.getId() != null) {
                workflowRunRepository.findFirstByWorkflowIdOrderByStartedAtDesc(workflow.getId())
                        .ifPresent(run -> {
                            Map<String, Object> lastRun = new LinkedHashMap<>();
                            lastRun.put("status", run.getStatus() != null ? run.getStatus().name() : null);
                            if (run.getStartedAt() != null) lastRun.put("at", run.getStartedAt().toString());
                            summary.put("last_run", lastRun);
                        });
            }
        } catch (Exception e) {
            log.debug("workflow.list: last_run skipped for {}: {}",
                    workflow.getId(), e.getMessage());
        }
    }

    // ==================== Delete ====================

    private ToolExecutionResult executeDelete(Map<String, Object> parameters, String tenantId,
                                                ToolExecutionContext context) {
        String workflowIdStr = getStringParam(parameters, "workflow_id");
        if (workflowIdStr == null || workflowIdStr.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "workflow_id is required");
        }

        try {
            UUID workflowId = UUID.fromString(workflowIdStr);
            // Audit 2026-05-17 round-5 - positive owner-or-org scope on workflow
            // BEFORE delete. MCP path was relying on the (deny-list only) canAccess
            // check inside the service, which lets a multi-org-same-tenant caller
            // mutate another teammate's org workflow.
            String callerOrgId = context != null ? context.orgId() : null;
            com.apimarketplace.orchestrator.domain.WorkflowEntity existing =
                    workflowRepository.findById(workflowId).orElse(null);
            if (existing == null) {
                return ToolExecutionResult.failure(ToolErrorCode.WORKFLOW_NOT_FOUND, "Workflow not found: " + workflowIdStr);
            }
            // Strict-isolation scope (2026-05-18, ScopeGuard alignment).
            if (!ScopeGuard.isInStrictScope(tenantId, callerOrgId,
                    existing.getTenantId(), existing.getOrganizationId())) {
                log.warn("[SCOPE] MCP workflow delete cross-tenant blocked: workflowId={} caller={} orgId={}",
                        workflowId, tenantId, callerOrgId);
                return ToolExecutionResult.failure(ToolErrorCode.WORKFLOW_NOT_FOUND, "Workflow not found: " + workflowIdStr);
            }
            boolean deleted = workflowService.deleteWorkflow(workflowId, tenantId);
            if (!deleted) {
                return ToolExecutionResult.failure(ToolErrorCode.WORKFLOW_NOT_FOUND, "Workflow not found: " + workflowIdStr);
            }

            return ToolExecutionResult.success(Map.of(
                "id", workflowIdStr, "status", "DELETED",
                "message", "You successfully deleted workflow (ID: " + workflowIdStr + ")."
            ));
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "Invalid workflow_id format: " + workflowIdStr);
        } catch (Exception e) {
            log.error("Failed to delete workflow: {}", e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to delete workflow: " + e.getMessage());
        }
    }

    // ==================== Runs ====================

    private ToolExecutionResult executeRuns(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        String workflowIdStr = getStringParam(parameters, "workflow_id");
        if (workflowIdStr == null || workflowIdStr.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "workflow_id is required");
        }

        // Allow-list: a restricted agent must not read run history of a workflow outside its list
        // (executeGet gates the same way - runs/get_run/get_node_output were the bypass).
        var workflowDenied = denyIfWorkflowNotAllowed(context, workflowIdStr);
        if (workflowDenied.isPresent()) return workflowDenied.get();

        // workflow.runs uses LARGE caps (default=20, max=100, hintThreshold=200) - runs
        // history is the deep-debug surface so the cap is the most generous of the 3 buckets.
        // hardRefuseOffset auto-derives to 800 (4 × hintThreshold) so the agent is forced
        // to refine with a filter once the trail goes that deep.
        //
        // workflow_id IS the filter for this action - pagination past hardRefuse without
        // another filter is fine because the workflow scope already narrows the result set.
        // Pass {"workflow_id"} as the active-filter-keys set so the hard-refuse path is
        // skipped (this is the API contract: filter-presence is workflow_id, not query/category).
        AgentListEnvelope.Spec spec = AgentListEnvelope.Spec.of(
                        AgentListEnvelope.Caps.LARGE, "runs", "runs", "runs")
                // No general filter for runs - the scope IS workflow_id. Empty filter
                // list suppresses the `refine` hint so the agent doesn't see misleading
                // suggestions like "narrow with query=...".
                .withSuggestedFilters(List.of())
                .withNext(Map.of(
                        "inspect_run", "workflow(action='get_run', run_id='<run_id>')",
                        "inspect_run_epoch", "workflow(action='get_run', run_id='<run_id>', epoch=N)"
                ));
        AgentListEnvelope.Bounds bounds;
        try {
            bounds = AgentListEnvelope.readBounds(parameters, spec, Set.of("workflow_id"));
        } catch (AgentListEnvelope.InvalidParamsException e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, e.code + ": " + e.getMessage());
        }

        try {
            UUID workflowId = UUID.fromString(workflowIdStr);

            // Tenant/org scope check BEFORE reading run history - mirrors executeGet.
            // Without it, findRunSummariesByWorkflowId is explicitly unscoped, so any
            // caller passing another tenant's workflow_id received that tenant's run
            // metadata (cross-tenant IDOR). 404 (not 403) to avoid leaking existence.
            var workflowOpt = workflowService.getWorkflow(workflowId);
            if (workflowOpt.isEmpty()) {
                return ToolExecutionResult.failure(ToolErrorCode.WORKFLOW_NOT_FOUND, "Workflow not found: " + workflowIdStr);
            }
            WorkflowEntity workflow = workflowOpt.get();
            String orgId = context != null ? context.orgId() : null;
            if (!ScopeGuard.isInStrictScope(tenantId, orgId,
                    workflow.getTenantId(), workflow.getOrganizationId())) {
                return ToolExecutionResult.failure(ToolErrorCode.WORKFLOW_NOT_FOUND, "Workflow not found: " + workflowIdStr);
            }

            // Custom Pageable with arbitrary offset support - fixes the silent-snap bug
            // where stock PageRequest.of(offset/limit, limit) rounded offset=33 to 25.
            var page = workflowRunRepository.findRunSummariesByWorkflowId(
                    workflowId, OffsetLimitPageable.of(bounds.offset(), bounds.limit()));

            Integer pinnedVersion = workflow.getPinnedVersion();

            List<Map<String, Object>> runs = page.getContent().stream().map(r -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("run_id", r.getRunIdPublic());
                m.put("status", r.getStatus() != null ? r.getStatus().name() : null);
                m.put("plan_version", r.getPlanVersion());
                m.put("is_pinned_version", pinnedVersion != null
                        && r.getPlanVersion() != null
                        && pinnedVersion.equals(r.getPlanVersion()));
                m.put("started_at", r.getStartedAt() != null ? r.getStartedAt().toString() : null);
                m.put("ended_at", r.getEndedAt() != null ? r.getEndedAt().toString() : null);
                m.put("duration_ms", r.getDurationMs());
                m.put("total_nodes", r.getTotalNodes());
                m.put("execution_mode", r.getExecutionMode() != null ? r.getExecutionMode().name() : null);
                return m;
            }).toList();

            Map<String, Object> data = new LinkedHashMap<>(AgentListEnvelope
                    .paginateProjection(runs, bounds, page.getTotalElements(), spec));
            // Per-action metadata sits alongside the envelope (workflow-scoped context).
            data.put("workflowId", workflowIdStr);
            data.put("pinned_version", pinnedVersion);
            data.put("is_production", pinnedVersion != null);
            data.put("latest_version", planVersionService.getCurrentVersion(workflowId));
            return ToolExecutionResult.success(data);
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "Invalid workflow_id format: " + workflowIdStr);
        } catch (Exception e) {
            log.error("Failed to list runs for workflow {}: {}", workflowIdStr, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to list runs: " + e.getMessage());
        }
    }

    // ==================== Get Run ====================

    private ToolExecutionResult executeGetRun(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        String runId = getStringParam(parameters, "run_id");
        if (runId == null || runId.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "run_id is required");
        }

        try {
            Optional<WorkflowRunEntity> runOpt = workflowRunRepository.findByRunIdPublic(runId);
            if (runOpt.isEmpty()) {
                return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "Run not found: " + runId);
            }

            WorkflowRunEntity run = runOpt.get();
            String orgId = TenantResolver.currentRequestOrganizationId();
            if (!ScopeGuard.isInStrictScope(tenantId, orgId, run.getTenantId(), run.getOrganizationId())) {
                return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "Run not found: " + runId);
            }

            // Load the plan used by this run (from plan version, fallback to current workflow plan)
            WorkflowEntity workflow = run.getWorkflow();
            // Allow-list: a restricted agent must not read the run report of a workflow outside its list.
            var workflowDenied = denyIfWorkflowNotAllowed(context,
                    workflow != null && workflow.getId() != null ? workflow.getId().toString() : null);
            if (workflowDenied.isPresent()) return workflowDenied.get();
            WorkflowPlan plan = resolvePlanForRun(run, workflow, tenantId);

            // Phase 1 (no epoch): macro overview. Phase 2 (epoch=N): detailed node report.
            Integer epoch = getIntParam(parameters, "epoch");
            if (epoch != null) {
                return ToolExecutionResult.success(
                        agentWorkflowFireService.buildEpochDetailReport(run, plan, epoch, tenantId));
            } else {
                return ToolExecutionResult.success(
                        agentWorkflowFireService.buildRunMacroReport(run, plan, tenantId));
            }
        } catch (Exception e) {
            log.error("Failed to get run {}: {}", runId, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to get run: " + e.getMessage());
        }
    }

    // ==================== Wait Run ====================

    /**
     * Block until the run leaves the in-flight states (PENDING/RUNNING) or the
     * timeout elapses, then return the same macro report as {@code get_run}
     * wrapped in a wait envelope. One tool call replaces a get_run poll loop.
     *
     * <p>Returns as soon as the run needs attention, not only on terminal
     * states: PAUSED / AWAITING_SIGNAL / WAITING_TRIGGER all end the wait,
     * because they wait on an input the agent (or user) must provide - sleeping
     * through them would deadlock the agent against its own pending action.
     *
     * <p>The DB poll re-reads the entity every {@link #waitRunPollIntervalMs};
     * between polls, {@link #waitRunSliceMs} slices check the CALLER's cancel
     * signal so a user STOP releases the thread promptly (the agent loop cannot
     * interrupt a tool in flight).
     */
    private ToolExecutionResult executeWaitRun(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        String runId = getStringParam(parameters, "run_id");
        if (runId == null || runId.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "run_id is required");
        }
        Object rawTimeout = parameters.get("timeout_seconds");
        Integer timeoutParam = getIntParam(parameters, "timeout_seconds");
        if (rawTimeout != null && timeoutParam == null) {
            // Present but unparseable must be an explicit error, not a silent
            // fall-through to the default (same strictness as the wait tool).
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                "timeout_seconds must be a whole number between 1 and " + waitRunMaxTimeoutSeconds
                    + " (got '" + rawTimeout + "').");
        }
        int timeoutSeconds = timeoutParam != null ? timeoutParam : waitRunDefaultTimeoutSeconds;
        if (timeoutSeconds < 1 || timeoutSeconds > waitRunMaxTimeoutSeconds) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                "timeout_seconds must be between 1 and " + waitRunMaxTimeoutSeconds + " (got " + timeoutSeconds + "). "
                    + "If the run needs longer, call wait_run again when this call returns with timed_out=true.");
        }

        try {
            Optional<WorkflowRunEntity> runOpt = workflowRunRepository.findByRunIdPublic(runId);
            if (runOpt.isEmpty()) {
                return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "Run not found: " + runId);
            }
            WorkflowRunEntity run = runOpt.get();
            String orgId = TenantResolver.currentRequestOrganizationId();
            if (!ScopeGuard.isInStrictScope(tenantId, orgId, run.getTenantId(), run.getOrganizationId())) {
                return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "Run not found: " + runId);
            }
            WorkflowEntity workflow = run.getWorkflow();
            var workflowDenied = denyIfWorkflowNotAllowed(context,
                    workflow != null && workflow.getId() != null ? workflow.getId().toString() : null);
            if (workflowDenied.isPresent()) return workflowDenied.get();

            long waitStartMs = System.currentTimeMillis();
            long deadlineMs = waitStartMs + timeoutSeconds * 1000L;
            boolean cancelled = false;

            while (isInFlight(run.getStatus()) && System.currentTimeMillis() < deadlineMs && !cancelled) {
                long pollDeadlineMs = Math.min(System.currentTimeMillis() + waitRunPollIntervalMs, deadlineMs);
                while (System.currentTimeMillis() < pollDeadlineMs) {
                    Thread.sleep(Math.min(waitRunSliceMs, Math.max(1L, pollDeadlineMs - System.currentTimeMillis())));
                    if (cancellationProbe.isCallerCancelled(context)) {
                        cancelled = true;
                        break;
                    }
                }
                Optional<WorkflowRunEntity> refreshed = workflowRunRepository.findByRunIdPublic(runId);
                if (refreshed.isEmpty()) {
                    return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND,
                        "Run disappeared while waiting: " + runId);
                }
                run = refreshed.get();
            }

            boolean timedOut = !cancelled && isInFlight(run.getStatus());
            long waitedSeconds = Math.round((System.currentTimeMillis() - waitStartMs) / 1000.0);

            WorkflowPlan plan = resolvePlanForRun(run, run.getWorkflow(), tenantId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", run.getStatus() != null ? run.getStatus().toWireValue() : "unknown");
            out.put("waited_seconds", waitedSeconds);
            out.put("timed_out", timedOut);
            if (cancelled) {
                out.put("cancelled", true);
                out.put("note", "The user stopped this agent while it was waiting. Wrap up now; do not start new work.");
            } else if (timedOut) {
                out.put("next_action", "The run is still in progress after " + waitedSeconds + "s. "
                    + "Call wait_run again to keep waiting, or get_run for a snapshot without blocking.");
            }
            out.put("run", agentWorkflowFireService.buildRunMacroReport(run, plan, tenantId));
            return ToolExecutionResult.success(out);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Wait interrupted before completion.");
        } catch (Exception e) {
            log.error("Failed to wait for run {}: {}", runId, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to wait for run: " + e.getMessage());
        }
    }

    /** In-flight = still worth blocking on. Everything else needs the agent's (or user's) attention. */
    private static boolean isInFlight(RunStatus status) {
        return status == RunStatus.PENDING || status == RunStatus.RUNNING;
    }

    // ==================== Get Node Output ====================

    private ToolExecutionResult executeGetNodeOutput(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        String runId = getStringParam(parameters, "run_id");
        if (runId == null || runId.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "run_id is required");
        }
        Integer epoch = getIntParam(parameters, "epoch");
        if (epoch == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "epoch is required for get_node_output");
        }
        String nodeId = getStringParam(parameters, "node_id");
        if (nodeId == null || nodeId.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "node_id is required for get_node_output");
        }

        // Optional per-row targeting: when the agent already knows which item /
        // iteration / spawn it wants, pass them to zoom directly. Otherwise the
        // service returns list-mode (items[] + status_counts) so the agent can pick.
        Integer itemIndex = getIntParam(parameters, "item_index");
        Integer iteration = getIntParam(parameters, "iteration");
        Integer spawn = getIntParam(parameters, "spawn");
        // Field-expand: page a single output field's full text value past the 128 KB preview cap
        // (same offset/NEXT idiom as the files tool). Absent = current capped-output behaviour.
        String expandField = getStringParam(parameters, "field");
        Integer fieldOffset = getIntParam(parameters, "offset");
        Integer fieldMaxBytes = getIntParam(parameters, "max_bytes");

        try {
            Optional<WorkflowRunEntity> runOpt = workflowRunRepository.findByRunIdPublic(runId);
            if (runOpt.isEmpty()) {
                return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "Run not found: " + runId);
            }

            WorkflowRunEntity run = runOpt.get();
            String orgId = TenantResolver.currentRequestOrganizationId();
            if (!ScopeGuard.isInStrictScope(tenantId, orgId, run.getTenantId(), run.getOrganizationId())) {
                return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "Run not found: " + runId);
            }

            WorkflowEntity workflow = run.getWorkflow();
            // Allow-list: a restricted agent must not read node outputs of a workflow outside its list.
            var workflowDenied = denyIfWorkflowNotAllowed(context,
                    workflow != null && workflow.getId() != null ? workflow.getId().toString() : null);
            if (workflowDenied.isPresent()) return workflowDenied.get();
            WorkflowPlan plan = resolvePlanForRun(run, workflow, tenantId);

            return ToolExecutionResult.success(
                    agentWorkflowFireService.buildNodeOutputReport(
                            run, plan, epoch, nodeId, tenantId, itemIndex, iteration, spawn,
                            expandField, fieldOffset, fieldMaxBytes));
        } catch (Exception e) {
            log.error("Failed to get node output for run {}, node {}: {}", runId, nodeId, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to get node output: " + e.getMessage());
        }
    }

    /**
     * Resolve the plan for a specific run: prefer the versioned plan, fallback to current workflow plan.
     */
    private WorkflowPlan resolvePlanForRun(WorkflowRunEntity run, WorkflowEntity workflow, String tenantId) {
        // Try versioned plan first (matches exact plan used at execution time)
        if (run.getPlanVersion() != null && workflow.getId() != null) {
            var versionOpt = planVersionService.getVersion(workflow.getId(), run.getPlanVersion());
            if (versionOpt.isPresent()) {
                return WorkflowPlan.fromMap(versionOpt.get().getPlan(),
                        workflow.getId().toString(), tenantId);
            }
        }
        // Fallback to current workflow plan
        return WorkflowPlan.fromMap(workflow.getPlan(), workflow.getId().toString(), tenantId);
    }

    // ==================== Helpers ====================

    @SuppressWarnings("unchecked")
    private List<String> getAllowedWorkflowIds(ToolExecutionContext context) {
        if (context == null || context.credentials() == null) return null;
        return com.apimarketplace.agent.config.ToolAccessControl.getAllowedIds(context.credentials(), "workflow");
    }

    /**
     * Deny when the agent has a restricted workflow allow-list that excludes {@code workflowIdStr}.
     * null list ⇒ unrestricted (pass); null id ⇒ pass (left to the caller's own handling).
     * Shared by get / runs / get_run / get_node_output so reads can't bypass the allow-list.
     */
    private Optional<ToolExecutionResult> denyIfWorkflowNotAllowed(ToolExecutionContext context, String workflowIdStr) {
        List<String> allowed = getAllowedWorkflowIds(context);
        if (allowed != null && workflowIdStr != null && !allowed.contains(workflowIdStr)) {
            log.info("Agent restriction: workflow {} not in allowed list", workflowIdStr);
            return Optional.of(ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED,
                    "This workflow is not in your approved workflow list."));
        }
        return Optional.empty();
    }

}
