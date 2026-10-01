package com.apimarketplace.orchestrator.tools.application;

import com.apimarketplace.agent.config.ToolAccessControl;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.tools.common.RunStopToolHandler;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The application allow-list for the actions that address a RUN rather than an application
 * (stop_run, get_run, get_node_output of the application tool). One rule, one place:
 *
 * <ul>
 *   <li>no list = unrestricted;</li>
 *   <li>the caller's OWN run is always allowed: an agent executing inside a run is authorized
 *       to be there by construction (it may stop itself, and read what it produced);</li>
 *   <li>otherwise the run's workflow must be an application (it has a source publication) on
 *       the list; a run of a plain workflow is refused.</li>
 * </ul>
 *
 * <p>The workflow is re-read by id instead of walking {@code run.getWorkflow()}: the association
 * is LAZY and these paths run with open-in-view=false, so touching any field beyond the proxy's
 * id would throw LazyInitializationException and turn a permission decision into a 500.
 */
public final class ApplicationRunAllowList {

    static final String DENIED = "This run does not belong to an application in your approved application list.";

    private ApplicationRunAllowList() {
    }

    public static Optional<ToolExecutionResult> denyIfOutside(WorkflowRunEntity run, ToolExecutionContext context,
                                                              WorkflowRepository workflowRepository) {
        List<String> allowedAppIds = context != null && context.credentials() != null
                ? ToolAccessControl.getAllowedIds(context.credentials(), "application")
                : null;
        if (allowedAppIds == null || RunStopToolHandler.isOwnRun(run, context)) {
            return Optional.empty();
        }
        WorkflowEntity proxy = run.getWorkflow();
        UUID pubId = proxy != null && proxy.getId() != null
                ? workflowRepository.findById(proxy.getId()).map(WorkflowEntity::getSourcePublicationId).orElse(null)
                : null;
        if (pubId == null || !allowedAppIds.contains(pubId.toString())) {
            return Optional.of(ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, DENIED));
        }
        return Optional.empty();
    }
}
