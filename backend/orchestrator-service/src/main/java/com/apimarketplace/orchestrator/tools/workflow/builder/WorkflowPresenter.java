package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.config.ToolAccessControl;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.common.PresentedView;
import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.orchestrator.controllers.workflow.WorkflowControllerHelper;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code workflow(action='present')}: the agent chooses what the user LOOKS AT, like a
 * presenter moving to the next slide. It changes nothing: it checks the target exists, is in
 * the caller's workspace and in the agent's allow-list, then returns a {@code visualization}
 * the chat frontend turns into a side-panel switch.
 *
 * <p>Only the views this tool owns: application + run_id ({@code present_application}), run +
 * run_id ({@code present_run}, NOT execute's workflow_run: that one fires on every run, and
 * opening it on every page would move the view as a build side effect), workflow + workflow_id
 * ({@code present_workflow}). A table, an interface, an agent or a file is presented by the tool
 * that owns it ({@code table}, {@code interface}, {@code agent}, {@code files} with
 * action='present'), through that tool's own read checks, so none of them is re-implemented
 * here. Asking this tool for one of those views answers with the call to make instead.
 */
@Component
public class WorkflowPresenter {

    private static final String VIEWS = "'application' or 'run' (run_id), 'workflow' (workflow_id)";

    /** Views owned by another tool: the call that presents each one. */
    private static final Map<String, String> OWNED_ELSEWHERE = Map.of(
        "table", "table(action='present', table_id=...)",
        "data", "table(action='present', table_id=...)",
        "interface", "interface(action='present', interface_id=...)",
        "page", "interface(action='present', interface_id=...)",
        "agent", "agent(action='present', agent_id=...)",
        "file", "files(action='present', file_id=...)");

    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowRepository workflowRepository;
    private OrgAccessGuard orgAccessGuard;

    public WorkflowPresenter(WorkflowRunRepository workflowRunRepository, WorkflowRepository workflowRepository) {
        this.workflowRunRepository = workflowRunRepository;
        this.workflowRepository = workflowRepository;
    }

    /** Member deny-list (workflows). Optional: absent means no per-member restriction. */
    @Autowired(required = false)
    public void setOrgAccessGuard(OrgAccessGuard orgAccessGuard) {
        this.orgAccessGuard = orgAccessGuard;
    }

    public ToolExecutionResult present(Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
        String view = str(params.get("view"));
        if (view == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "'view' is required: " + VIEWS + ".");
        }
        String key = view.toLowerCase();
        String ownerCall = OWNED_ELSEWHERE.get(key);
        if (ownerCall != null) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                "view='" + view + "' is presented by the tool that owns it: call " + ownerCall
                + ". workflow(action='present') shows " + VIEWS + ".");
        }
        return switch (key) {
            case "application", "app" -> presentRun(params, tenantId, ctx, true);
            case "run" -> presentRun(params, tenantId, ctx, false);
            case "workflow", "canvas" -> presentWorkflow(params, tenantId, ctx);
            default -> ToolExecutionResult.failure(ToolErrorCode.INVALID_ENUM_VALUE,
                "Unknown view '" + view + "'. Use " + VIEWS + ".");
        };
    }

    // ── workflow runs ──

    private ToolExecutionResult presentRun(Map<String, Object> params, String tenantId, ToolExecutionContext ctx,
                                           boolean application) {
        String view = application ? "application" : "run";
        String runId = str(params.get("run_id"));
        if (runId == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "view='" + view + "' needs run_id (from workflow(action='execute') or action='runs').");
        }
        WorkflowRunEntity run = workflowRunRepository.findByRunIdPublic(runId).orElse(null);
        if (run == null || run.getWorkflow() == null || !WorkflowControllerHelper.isRunInScope(run, tenantId, orgId(ctx))) {
            return notFound("Run", runId);
        }
        // The run's workflow is a LAZY proxy and this runs outside any session: only its id is
        // readable from it. Name and plan come from a real load.
        UUID workflowUuid = run.getWorkflow().getId();
        String workflowId = workflowUuid.toString();
        if (!allowed(ctx, "workflow", workflowId)) return notApproved("workflow");
        if (!memberMayAccess(ctx, tenantId, "workflow", workflowId)) return notFound("Run", runId);
        WorkflowEntity workflow = workflowRepository.findById(workflowUuid).orElse(null);
        if (application && workflow != null && !hasInterface(workflow)) {
            // Would switch the user to an empty tab: say so instead of reporting a success.
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                "This workflow has no interface node, so its Application tab is empty. Present what it produced "
                + "instead: table(action='present', table_id=...) for the table it writes to, or "
                + "files(action='present', file_id=...) for a file it generated.");
        }
        String title = PresentedView.requestedTitleOr(params, PresentedView.titleOf(workflow != null ? workflow.getName() : null, "Workflow"));
        return PresentedView.result(view, Map.of("workflow_id", workflowId, "run_id", runId), workflowId, title,
            Map.of("runId", runId));
    }

    // ── the workflow itself ──

    private ToolExecutionResult presentWorkflow(Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
        String raw = firstStr(params, "workflow_id", "id");
        UUID id = uuid(raw);
        if (id == null) return badId("workflow_id", raw, "the workflow UUID from workflow(action='list')");
        if (!allowed(ctx, "workflow", raw)) return notApproved("workflow");
        WorkflowEntity workflow = workflowRepository.findById(id).orElse(null);
        if (workflow == null
                || !ScopeGuard.isInStrictScope(tenantId, orgId(ctx), workflow.getTenantId(), workflow.getOrganizationId())
                || !memberMayAccess(ctx, tenantId, "workflow", raw)) {
            return notFound("Workflow", raw);
        }
        return PresentedView.result("workflow", "workflow_id", raw,
            PresentedView.requestedTitleOr(params, PresentedView.titleOf(workflow.getName(), "Workflow")));
    }

    // ── helpers ──

    private static ToolExecutionResult badId(String param, String raw, String expected) {
        return ToolExecutionResult.failure(
            raw == null ? ToolErrorCode.MISSING_PARAMETER : ToolErrorCode.INVALID_PARAMETER_VALUE,
            param + " is required: " + expected + ".");
    }

    private static ToolExecutionResult notFound(String kind, String id) {
        return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND,
            kind + " '" + id + "' not found (or not in your workspace).");
    }

    /** The agent's own configured allow-list: stating it leaks nothing, so it says so plainly. */
    private static ToolExecutionResult notApproved(String kind) {
        return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED,
            "This " + kind + " is not in your approved " + kind + " list.");
    }

    /** Same allow-list the agent's other workflow actions obey: null = unrestricted. */
    private static boolean allowed(ToolExecutionContext ctx, String category, String id) {
        List<String> allowedIds = ctx != null ? ToolAccessControl.getAllowedIds(ctx.credentials(), category) : null;
        return allowedIds == null || allowedIds.contains(id);
    }

    private boolean memberMayAccess(ToolExecutionContext ctx, String tenantId, String resourceType, String id) {
        String orgId = orgId(ctx);
        return orgAccessGuard == null || orgId == null || orgId.isBlank()
            || orgAccessGuard.canAccess(orgId, tenantId, resourceType, id, ctx.orgRole());
    }

    /**
     * Fails OPEN: an unreadable plan must not block a presentation that may well work.
     * Reads the workflow's CURRENT plan, which is also the one the panel draws a bound run
     * with; a run of an older version that differed on interfaces is the accepted blind spot.
     */
    private static boolean hasInterface(WorkflowEntity workflow) {
        try {
            return workflow.getPlan() == null || !WorkflowPlan.fromMap(workflow.getPlan()).getInterfaces().isEmpty();
        } catch (RuntimeException unreadablePlan) {
            return true;
        }
    }

    private static String orgId(ToolExecutionContext ctx) {
        return ctx != null ? ctx.orgId() : null;
    }

    private static String firstStr(Map<String, Object> params, String... keys) {
        for (String key : keys) {
            String value = str(params.get(key));
            if (value != null) return value;
        }
        return null;
    }

    private static UUID uuid(String value) {
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String str(Object value) {
        if (value == null) return null;
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
