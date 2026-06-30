package com.apimarketplace.orchestrator.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

/**
 * Thrown when a write to a workflow's plan is refused because the workflow is an
 * {@code APPLICATION} (a frozen acquired marketplace clone).
 *
 * <p>The acquirer received an exact snapshot of the published plan at acquisition
 * time; mutating it would silently break the publish/acquire isolation contract
 * and diverge the in-flight workflow from {@code basePlan} with no way to
 * recover the original. The sanctioned restore route is
 * {@code POST /workflows/{id}/reset-plan}, which restores from {@code basePlan}
 * and bypasses this exception entirely.
 *
 * <p>Mirrors the {@link com.apimarketplace.auth.client.access.OrgAccessDeniedException}
 * pattern: a {@link RuntimeException} annotated with {@link ResponseStatus} so
 * Spring maps it to 409 CONFLICT without each controller having to translate.
 * The matching shared-agent-lib failures from {@code WorkflowBuilderProvider} and
 * {@code WorkflowBuilderLoader} use {@code ToolErrorCode.RESOURCE_CONFLICT} +
 * {@code code='APPLICATION_PLAN_IMMUTABLE'} in metadata so the agent sees a
 * consistent shape across REST and tool paths.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class ApplicationPlanImmutableException extends RuntimeException {

    private final UUID workflowId;

    public ApplicationPlanImmutableException(UUID workflowId) {
        super("Cannot update the plan of an APPLICATION workflow " + workflowId
                + ": it is a frozen acquired marketplace clone. Use POST /workflows/"
                + workflowId + "/reset-plan to restore from basePlan.");
        this.workflowId = workflowId;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }
}
