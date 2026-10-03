package com.apimarketplace.orchestrator.tools.application;

import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.agent.tools.common.ToolModule;
import com.apimarketplace.orchestrator.tools.workflow.builder.AgentWorkflowFireService;
import com.apimarketplace.orchestrator.trigger.TriggerExecutionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Execute module for the application tool.
 * Handles action='execute' (runs an already-acquired application) and its
 * counterpart action='stop_run' (ends a run that is still going).
 *
 * <p>Flow:
 * <ol>
 *   <li>Validate application_id + allowedApplicationIds</li>
 *   <li>Find acquired workflow via sourcePublicationId</li>
 *   <li>Parse plan, resolve trigger</li>
 *   <li>Create run + fire (blocking)</li>
 *   <li>Build result + enrich with application marker</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApplicationExecuteModule implements ToolModule {

    private final WorkflowRepository workflowRepository;
    private final AgentWorkflowFireService agentWorkflowFireService;
    private final com.apimarketplace.orchestrator.services.agent.ConversationEventPublisher conversationEventPublisher;
    private final com.apimarketplace.orchestrator.services.ApplicationLifecycleService applicationLifecycleService;
    private final com.apimarketplace.orchestrator.repository.WorkflowRunRepository workflowRunRepository;
    private final com.apimarketplace.orchestrator.tools.common.RunStopToolHandler runStopToolHandler;

    private static final Set<String> HANDLED_ACTIONS = Set.of("execute", "stop_run");

    @Override
    public List<AgentToolDefinition> getToolDefinitions() {
        return List.of();
    }

    @Override
    public boolean canHandle(String action) {
        return HANDLED_ACTIONS.contains(action);
    }

    @Override
    public Optional<ToolExecutionResult> execute(String action, Map<String, Object> parameters,
                                                  String tenantId, ToolExecutionContext context) {
        if (!canHandle(action)) return Optional.empty();

        // Access mode check (read/write)
        var accessDenied = com.apimarketplace.agent.config.ToolAccessControl.checkWriteAccess(
                context != null ? context.credentials() : null, "application", action);
        if (accessDenied.isPresent()) return Optional.of(ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, accessDenied.get()));
        // Workspace role (VIEWER is read-only): the READ actions stay, every write is refused.
        var roleDenied = com.apimarketplace.agent.config.ToolAccessControl.checkRoleWriteAccess(
                context != null ? context.orgId() : null,
                context != null ? context.orgRole() : null, "application", action);
        if (roleDenied.isPresent()) return Optional.of(ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, roleDenied.get()));

        if ("stop_run".equals(action)) {
            return Optional.of(stopRun(parameters, tenantId, context));
        }
        return Optional.of(executeApplication(parameters, tenantId, context));
    }

    /**
     * End an application run that is still going: the counterpart of {@code execute}.
     *
     * <p>Run resolution and tenant/org scoping mirror {@code application(action='get_run')},
     * so an agent can stop exactly the runs it can already read. Authorization is then
     * STRICTER than that read: when the agent is restricted to a list of applications, the
     * run's application must be in that list, a check {@code get_run} does not perform. The
     * stop itself is shared with the workflow tool, so both behave identically.
     *
     * <p>Omitting {@code run_id} stops the run the calling agent is executing inside.
     */
    private ToolExecutionResult stopRun(Map<String, Object> parameters, String tenantId,
                                         ToolExecutionContext context) {
        String runId = runStopToolHandler.resolveTargetRunId(parameters, context);
        if (com.apimarketplace.orchestrator.tools.common.RunStopToolHandler.BAD_RUN_ID.equals(runId)) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                runStopToolHandler.badRunIdMessage(parameters));
        }
        if (runId == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                runStopToolHandler.noTargetMessage("application"));
        }

        try {
            Optional<WorkflowRunEntity> runOpt = workflowRunRepository.findByRunIdPublic(runId);
            if (runOpt.isEmpty()) {
                return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "Run not found: " + runId);
            }

            WorkflowRunEntity run = runOpt.get();
            String orgId = context != null ? context.orgId() : null;
            if (!com.apimarketplace.common.scope.ScopeGuard.isInStrictScope(
                    tenantId, orgId, run.getTenantId(), run.getOrganizationId())) {
                return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "Run not found: " + runId);
            }

            // Allow-list: a restricted agent must not stop a run belonging to an
            // application outside its approved list - EXCEPT its own run. An agent
            // executing inside a run is authorized to be there by construction, and
            // the help promises it can abort itself; the list would deny exactly that
            // (a plain workflow run has no source publication at all).
            var appDenied = ApplicationRunAllowList.denyIfOutside(run, context, workflowRepository);
            if (appDenied.isPresent()) return appDenied.get();

            return runStopToolHandler.stop(run, parameters, context, "application");
        } catch (Exception e) {
            log.error("Failed to stop run {}: {}", runId, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                "Failed to stop run: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private ToolExecutionResult executeApplication(Map<String, Object> parameters,
                                                    String tenantId, ToolExecutionContext context) {
        // 1. Validate application_id
        String applicationId = getStringParam(parameters, "application_id");
        if (applicationId == null || applicationId.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "application_id is required for execute action");
        }

        UUID pubId;
        try {
            pubId = UUID.fromString(applicationId);
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                "Invalid application_id format. Expected UUID.");
        }

        // Check allowedApplicationIds restriction
        List<String> allowedAppIds = getAllowedApplicationIds(context);
        if (allowedAppIds != null && !allowedAppIds.contains(applicationId)) {
            log.info("Agent restriction: application {} not in allowed list", applicationId);
            return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED,
                "This application is not in your approved application list.");
        }

        // 2. Find acquired workflow
        // Audit 2026-05-17 round-7 - route through scope-aware finder. Strict-
        // tenant-only path blocked org admin who acquired the publication into
        // the org workspace from executing it from a teammate's chat (their
        // tenantId !== acquirer.tenantId).
        String callerOrgId = context != null ? context.orgId() : null;
        if (callerOrgId == null || callerOrgId.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.AUTHENTICATION_REQUIRED,
                "organizationId required after V261");
        }
        Optional<WorkflowEntity> acquiredOpt =
            applicationLifecycleService.resolveClone(callerOrgId, pubId);
        if (acquiredOpt.isEmpty()) {
            // Fix 2026-06-05 (hardening): mirror the executeGet path. The most common
            // cause of a not-found on execute is the agent passing a workflowId where
            // an application_id was expected - my/search emit both UUIDs side by side.
            // Echo the real application_id instead of a dead-end 404. Shared helper so
            // the wording stays identical to get (ApplicationIdDisambiguator).
            Optional<String> wfHint = ApplicationIdDisambiguator.workflowIdHint(workflowRepository, pubId);
            if (wfHint.isPresent()) {
                return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, wfHint.get());
            }
            return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND,
                "No application found for this ID. Either create it with application(action='create') " +
                "or acquire it from the marketplace with application(action='acquire') first.");
        }

        WorkflowEntity workflow = acquiredOpt.get();
        UUID workflowId = workflow.getId();

        try {
            // 3. Parse plan + resolve trigger
            Map<String, Object> planData = workflow.getPlan();
            if (planData == null || planData.isEmpty()) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    "Acquired application has no plan. The application may be corrupted.");
            }

            WorkflowPlan plan = WorkflowPlan.fromMap(planData, workflowId.toString(), tenantId);

            if (plan.getTriggers() == null || plan.getTriggers().isEmpty()) {
                return ToolExecutionResult.failure(ToolErrorCode.WORKFLOW_INVALID, "Application has no triggers. The application may be corrupted.");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> dataInputs = parameters.get("data_inputs") instanceof Map<?, ?>
                    ? (Map<String, Object>) parameters.get("data_inputs") : Map.of();
            String triggerIdHint = getStringParam(parameters, "trigger_id");

            // Bootstrap check: apps with only non-fireable triggers (workflow/error)
            // cannot be executed directly - return early with BOOTSTRAPPED status.
            if (triggerIdHint == null && agentWorkflowFireService.hasOnlyBootstrapTriggers(plan)) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "BOOTSTRAPPED");
                result.put("application_id", applicationId);
                result.put("workflow_id", workflowId.toString());
                result.put("message", "This application has only non-fireable triggers (workflow/error). "
                    + "It is designed to be triggered by another workflow, not executed directly.");
                result.put("NEXT", "application(action='get', application_id='" + applicationId
                    + "') - inspect the app for details");
                return ToolExecutionResult.success(result);
            }

            Trigger trigger;
            try {
                trigger = agentWorkflowFireService.resolveTrigger(plan, triggerIdHint);
            } catch (IllegalArgumentException e) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, e.getMessage());
            }

            // 4. Create run + fire (blocking)
            WorkflowRunEntity run = agentWorkflowFireService.createRun(workflow, plan, dataInputs, tenantId);

            // Early visualization publish (instant side-panel open before blocking fire())
            String streamId = context.credentials() != null ? (String) context.credentials().get("__streamId__") : null;
            String convId = context.credentials() != null ? (String) context.credentials().get("conversationId") : null;
            String appName = workflow.getName() != null ? workflow.getName() : "Application";
            conversationEventPublisher.publishVisualizationReady(
                streamId, convId, "application", applicationId, appName, run.getRunIdPublic());

            TriggerExecutionResult triggerResult = agentWorkflowFireService.fire(run, trigger, dataInputs,
                com.apimarketplace.common.classification.DataSensitivity.fromCredentials(context.credentials()).isRestricted());

            // 5. Build result + enrich
            Map<String, Object> result = agentWorkflowFireService.buildResult(
                run, triggerResult, workflow, plan, tenantId);
            result.put("application_id", applicationId);
            result.put("workflow_id", workflowId.toString());

            // Override NEXT hint to point to application() instead of workflow()
            String runIdPublicForHint = run.getRunIdPublic();
            if (runIdPublicForHint != null) {
                result.put("NEXT", "application(action='get_run', run_id='" + runIdPublicForHint
                    + "') - macro overview | application(action='get_run', run_id='" + runIdPublicForHint
                    + "', epoch=0) - epoch detail");
            }

            // 4-field marker pins the chat-history preview to THIS execution's run.
            // Fall back to the legacy 3-field shape only when runIdPublic is missing
            // (defensive - populated by createRun in normal flow).
            String runIdPublic = run.getRunIdPublic();
            boolean hasRunId = runIdPublic != null && !runIdPublic.isBlank();
            result.put("marker", hasRunId
                ? "[visualize:application:" + applicationId + ":" + runIdPublic + "]"
                : "[visualize:application:" + applicationId + "]");

            // 6. Return with visualization metadata
            Map<String, Object> viz = new LinkedHashMap<>();
            viz.put("type", "application");
            viz.put("id", applicationId);
            viz.put("title", appName);
            if (hasRunId) {
                viz.put("runId", runIdPublic);
                viz.put("epoch", triggerResult.epoch());
            }
            return ToolExecutionResult.success(result, Map.of("visualization", viz));

        } catch (Exception e) {
            log.error("Failed to execute application {}: {}", applicationId, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                "Execute failed: " + e.getMessage());
        }
    }

    // ==================== HELPERS ====================

    private String getStringParam(Map<String, Object> parameters, String key) {
        Object val = parameters.get(key);
        return val instanceof String s ? s : null;
    }

    @SuppressWarnings("unchecked")
    private List<String> getAllowedApplicationIds(ToolExecutionContext context) {
        if (context == null || context.credentials() == null) return null;
        return com.apimarketplace.agent.config.ToolAccessControl.getAllowedIds(context.credentials(), "application");
    }
}
