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
                .orElse(new SessionResult(null, ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "No active session. Use workflow(action='init') or workflow(action='load', id='...')")));
        }

        // Fallback to tenant-wide lookup
        return resolveSessionFromTenant(tenantId);
    }

    /**
     * Resolve session when no session_id is provided (tenant-wide).
     * @deprecated Use resolveSessionFromConversation for proper isolation
     */
    @Deprecated
    private SessionResult resolveSessionFromTenant(String tenantId) {
        List<WorkflowBuilderSession> sessions = sessionStore.getSessionsForTenant(tenantId);

        if (sessions.isEmpty()) {
            return new SessionResult(null, ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "No active session. Use workflow(action='init') or workflow(action='load', id='...')"));
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
