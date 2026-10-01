package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import com.apimarketplace.agent.tools.ToolErrorCode;

/**
 * Manages workflow builder sessions (get, resolve, validate).
 *
 * Extracted from WorkflowBuilderProvider for Single Responsibility Principle.
 *
 * @see WorkflowBuilderProvider
 */
@Component
@RequiredArgsConstructor
public class WorkflowBuilderSessionManager {

    private final WorkflowBuilderSessionStore sessionStore;

    /**
     * Result of a session lookup operation.
     */
    public record SessionResult(WorkflowBuilderSession session, ToolExecutionResult error) {
        public boolean isError() {
            return error != null;
        }

        public boolean isSuccess() {
            return session != null && error == null;
        }
    }

    /**
     * Get the session for an operation, with automatic resolution.
     * Uses conversation-scoped lookup for isolation.
     *
     * @param params Parameters containing optional session_id
     * @param tenantId The tenant ID
     * @param conversationId The conversation ID (for isolation)
     * @return SessionResult containing either the session or an error
     */
    public SessionResult getSession(Map<String, Object> params, String tenantId, String conversationId) {
        String sessionId = (String) params.get("session_id");

        if (sessionId == null || sessionId.isBlank()) {
            return resolveSessionFromConversation(tenantId, conversationId);
        }

        return resolveSessionById(sessionId, tenantId);
    }

    /**
     * Resolve session for a specific conversation.
     */
    private SessionResult resolveSessionFromConversation(String tenantId, String conversationId) {
        // Try conversation-scoped lookup first
        if (conversationId != null && !conversationId.isBlank()) {
            return sessionStore.getSessionForConversation(tenantId, conversationId)
                .map(s -> new SessionResult(s, null))
                .orElseGet(() -> new SessionResult(null, ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND,
                    noSessionMessage(tenantId, conversationId))));
        }

        // Fallback to tenant-wide lookup
        return resolveSessionFromTenant(tenantId);
    }

    /**
     * "No active session", naming the workflow this conversation last built when it is known.
     *
     * <p>A builder session closes on finish, and expires its TTL (30 minutes by default) after
     * the last SAVE of the session: every change saves it, a read (describe, get_plan) does not,
     * so a conversation that only reads or talks for half an hour loses it. The agent's next edit
     * then routinely lands here while it still means "the workflow I was editing". A discarded
     * session is forgotten on purpose (discard means "not this one"), so it is never named here. Naming it lets the agent reopen it with one
     * load instead of guessing or starting a new workflow. It is a hint, never an auto-load:
     * reopening re-reads the saved workflow (every modifying action already auto-saved it), and
     * only the agent knows whether that is the workflow it means to edit now.
     */
    String noSessionMessage(String tenantId, String conversationId) {
        var last = sessionStore.getLastWorkflowForConversation(tenantId, conversationId);
        if (last.isEmpty()) {
            return NO_SESSION_MESSAGE;
        }
        String id = last.get().workflowId();
        String name = last.get().workflowName();
        return "No active build session: the one for workflow "
            + (name != null ? "'" + name + "' (" + id + ")" : id)
            + " was closed by finish, or expired: a session closes "
            + sessionStore.getSessionTtl().toMinutes() + " minutes after its last change, and reading it "
            + "(describe, get_plan) does not keep it open. "
            + "If it still exists, reopen it with workflow(action='load', id='" + id + "') to continue "
            + "from its saved state, then repeat this call. Or start a new workflow with workflow(action='init').";
    }

    static final String NO_SESSION_MESSAGE =
        "No active session. Use workflow(action='init') or workflow(action='load', id='...')";

    /**
     * Resolve session when no session_id is provided (tenant-wide).
     * @deprecated Use resolveSessionFromConversation for proper isolation
     */
    @Deprecated
    private SessionResult resolveSessionFromTenant(String tenantId) {
        List<WorkflowBuilderSession> sessions = sessionStore.getSessionsForTenant(tenantId);

        if (sessions.isEmpty()) {
            return new SessionResult(null, ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, NO_SESSION_MESSAGE));
        }

        if (sessions.size() == 1) {
            return new SessionResult(sessions.get(0), null);
        }

        return new SessionResult(null, ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Multiple active sessions. Use workflow(action='discard') to close the current session first."));
    }

    /**
     * Resolve session by explicit session_id.
     */
    private SessionResult resolveSessionById(String sessionId, String tenantId) {
        return sessionStore.get(sessionId)
            .filter(s -> tenantId.equals(s.getTenantId()))
            .map(s -> new SessionResult(s, null))
            .orElse(new SessionResult(null, ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "Session not found: " + sessionId)));
    }

    /**
     * Get the session store for direct access.
     */
    public WorkflowBuilderSessionStore getSessionStore() {
        return sessionStore;
    }

    /**
     * Get all sessions for a tenant.
     */
    public List<WorkflowBuilderSession> getSessionsForTenant(String tenantId) {
        return sessionStore.getSessionsForTenant(tenantId);
    }

    /**
     * Discard all sessions for a tenant.
     */
    public void discardAllForTenant(String tenantId) {
        sessionStore.discardAllForTenant(tenantId);
    }

    /**
     * Save a session.
     */
    public void save(WorkflowBuilderSession session) {
        sessionStore.save(session);
    }

    /**
     * Delete a session.
     */
    public void delete(String sessionId) {
        sessionStore.delete(sessionId);
    }
}
