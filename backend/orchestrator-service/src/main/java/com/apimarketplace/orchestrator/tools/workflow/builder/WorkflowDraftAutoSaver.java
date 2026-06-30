package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Handles auto-saving of workflow drafts and session-scoped versioning after modifying actions.
 *
 * <p>After each modifying action, this service:
 * <ol>
 *   <li>Saves the current plan to {@code workflows.plan} (draft persistence)</li>
 *   <li>Creates or updates a session-scoped version in {@code workflow_plan_versions}
 *       - one version per session, overwritten on each change to avoid spam</li>
 * </ol>
 *
 * @see WorkflowBuilderProvider
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowDraftAutoSaver {

    private final WorkflowBuilderSessionManager sessionManager;
    private final WorkflowManagementService workflowService;
    private final WorkflowPlanVersionService versionService;

    /**
     * Auto-save the current session as a draft and create/update a session-scoped version.
     *
     * @param params Parameters containing optional session_id
     * @param tenantId The tenant ID
     * @param conversationId The conversation ID for session isolation
     */
    public void autoSaveDraft(Map<String, Object> params, String tenantId, String conversationId) {
        try {
            var sr = sessionManager.getSession(params, tenantId, conversationId);
            if (sr.isError()) {
                log.warn("Auto-save skipped: session not found for tenant {} conversation {}",
                        tenantId, conversationId);
                return;
            }

            WorkflowBuilderSession session = sr.session();
            String draftIdStr = session.getLoadedWorkflowId();
            if (draftIdStr == null) {
                log.warn("Auto-save skipped: no loadedWorkflowId in session {}",
                        session.getSessionId());
                return;
            }

            UUID workflowId = UUID.fromString(draftIdStr);
            Map<String, Object> planMap = session.buildPlanMap();

            // 1. Save draft to workflows.plan. Thread session.getOrgId() so the
            //    draft row carries organization_id matching the active workspace;
            //    without it, drafts created in an org workspace land NULL-org and
            //    disappear from org-scoped list endpoints (bell Triggers tab,
            //    workflow list, etc).
            workflowService.saveDraft(planMap, tenantId, workflowId, session.getOrgId());

            // 2. Create or update session-scoped version (new behavior)
            // Uses sessionId as label: if latest version has same sessionId → overwrite,
            // otherwise create new version. This ensures 1 version per agent session.
            try {
                versionService.createOrUpdateSessionVersion(
                        workflowId, planMap, tenantId, session.getSessionId());
            } catch (Exception e) {
                log.warn("Auto-version failed for workflow {} session {}: {}",
                        workflowId, session.getSessionId(), e.getMessage());
            }

            log.debug("Auto-saved draft {} for tenant {} (session={})",
                    workflowId, tenantId, session.getSessionId());

        } catch (Exception e) {
            log.warn("Auto-save failed: {}", e.getMessage());
        }
    }

    /**
     * Create a new draft for a session.
     * Draft creation is mandatory for init - the draftId is required for visualization
     * (the card shown in chat that controls the right side panel).
     *
     * @param session The session to create a draft for
     * @param tenantId The tenant ID
     * @return The draft ID (never null)
     * @throws RuntimeException if draft creation fails
     */
    public String createDraft(WorkflowBuilderSession session, String tenantId) {
        // Thread session.getOrgId() - matches the autoSaveDraft path so the very
        // first draft also carries organization_id when the builder session was
        // opened in an org workspace.
        var savedDraft = workflowService.saveDraft(session.buildPlanMap(), tenantId, null, session.getOrgId());
        String draftId = savedDraft.getId().toString();
        session.setLoadedWorkflowId(draftId);
        log.info("Created draft {} for session {} (orgScope={})", draftId, session.getSessionId(),
                (session.getOrgId() != null && !session.getOrgId().isBlank()));
        return draftId;
    }
}
