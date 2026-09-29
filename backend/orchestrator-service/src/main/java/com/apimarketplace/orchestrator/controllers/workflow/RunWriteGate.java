package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single write gate for everything that DRIVES an existing run: pause / stop / resume /
 * rerun / plan edits, trigger fires, step-by-step execution, signal resolution and
 * interface actions. A run executes with its owner's credentials, so driving it is a write
 * on the workflow, gated exactly like executing it ({@code WorkflowExecutionController}):
 *
 * <ol>
 *   <li>the workspace VIEWER role is refused ({@link OrgAccessGuard#isRoleWriteBlocked});</li>
 *   <li>a member the run's workflow is restricted from (DENY or READ-only) is refused
 *       ({@link OrgAccessGuard#canWrite}).</li>
 * </ol>
 *
 * <p>Call it AFTER the scope check, so a refusal never reveals a run the caller cannot see.
 * A share-link visitor reaches these endpoints under the owner's identity with NO role
 * header (the gateway and the CE filter both drop it), so neither check can refuse them,
 * and the deny-list lookup is skipped for them entirely ({@code X-Share-Context: true}):
 * the member list concerns workspace members, and a share visitor is not one.
 *
 * <p>The deny-list lives in auth-service. When that lookup fails the gate answers
 * {@link #GUARD_UNAVAILABLE}, which callers map to 503 via {@link #statusFor}, instead of
 * surfacing the internal exception as a 400/500.
 */
public final class RunWriteGate {

    private static final Logger logger = LoggerFactory.getLogger(RunWriteGate.class);

    /** Message returned to a member restricted from the run's workflow. */
    public static final String READ_ONLY_WORKFLOW = "Workflow access is read-only";

    /** Message returned when the access check itself could not run (auth-service down). */
    public static final String GUARD_UNAVAILABLE =
            "The access check is temporarily unavailable. Please try again in a moment.";

    /** HTTP status for a non-null {@link #denial}: 503 when the check could not run, else 403. */
    public static int statusFor(String denial) {
        return GUARD_UNAVAILABLE.equals(denial) ? 503 : 403;
    }

    private RunWriteGate() {
    }

    /** Message returned to a VIEWER, e.g. "VIEWER role cannot resolve workflow runs". */
    public static String viewerMessage(String action) {
        return "VIEWER role cannot " + action + " workflow runs";
    }

    /**
     * @param guard   the org access guard bean
     * @param run     the (already scope-checked) run
     * @param userId  caller ({@code X-User-ID})
     * @param orgId   caller's active workspace ({@code X-Organization-ID})
     * @param orgRole caller's workspace role ({@code X-Organization-Role})
     * @param action  verb for the message ("pause", "resolve signals on", ...)
     * @return {@code null} when the caller may drive the run, otherwise the refusal message
     */
    public static String denial(OrgAccessGuard guard, WorkflowRunEntity run, String userId,
                                String orgId, String orgRole, String action) {
        if (OrgAccessGuard.isRoleWriteBlocked(orgId, orgRole)) {
            logger.warn("OrgAccess denied: VIEWER user {} attempted to {} run {} in org {}",
                    userId, action, run != null ? run.getRunIdPublic() : null, orgId);
            return viewerMessage(action);
        }
        if (run == null) {
            return null;
        }
        String runOrgId = run.getOrganizationId();
        if (runOrgId == null || run.getWorkflow() == null || run.getWorkflow().getId() == null) {
            return null;
        }
        if (WorkflowControllerHelper.isShareContext()) {
            return null;
        }
        String workflowId = run.getWorkflow().getId().toString();
        boolean canWrite;
        try {
            canWrite = guard.canWrite(runOrgId, userId, "workflow", workflowId, orgRole);
        } catch (RuntimeException e) {
            logger.error("OrgAccess check unavailable for user {} on run {} ({}): {}",
                    userId, run.getRunIdPublic(), action, e.getMessage());
            return GUARD_UNAVAILABLE;
        }
        if (!canWrite) {
            logger.warn("OrgAccess denied: user {} restricted from {} run {} of workflow {} in org {}",
                    userId, action, run.getRunIdPublic(), workflowId, runOrgId);
            return READ_ONLY_WORKFLOW;
        }
        return null;
    }
}
