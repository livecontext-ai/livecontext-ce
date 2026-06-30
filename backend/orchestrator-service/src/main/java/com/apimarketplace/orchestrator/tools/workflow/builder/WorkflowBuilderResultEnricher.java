package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Enriches tool execution results with session state and metadata.
 *
 * Extracted from WorkflowBuilderProvider for Single Responsibility Principle.
 *
 * @see WorkflowBuilderProvider
 */
@Component
@RequiredArgsConstructor
public class WorkflowBuilderResultEnricher {

    private final WorkflowBuilderSessionManager sessionManager;
    private final WorkflowBuilderLogger buildLogger;

    /**
     * Enrich a result with session state information and current plan.
     *
     * @param result The original result
     * @param session The session to get state from
     * @return Enriched result with plan in metadata
     */
    @SuppressWarnings("unchecked")
    public ToolExecutionResult enrichResult(ToolExecutionResult result, WorkflowBuilderSession session) {
        if (!result.success() || !(result.data() instanceof Map)) {
            return result;
        }

        Map<String, Object> data = new LinkedHashMap<>((Map<String, Object>) result.data());
        data.putIfAbsent("state", Map.of(
            "triggers", session.getTriggers().size(),
            "mcps", session.getMcps().size(),
            "edges", session.getEdges().size()
        ));
        data.put("phase", WorkflowBuilderPrompts.detectPhase(session).name());

        // Add current plan to metadata for LLM context
        Map<String, Object> metadata = new HashMap<>(result.metadata() != null ? result.metadata() : Map.of());
        metadata.put("plan", session.buildPlanMap());

        return ToolExecutionResult.success(data, metadata);
    }

    /**
     * Add session snapshot to result metadata.
     *
     * @param result The original result
     * @param params Parameters containing optional session_id
     * @param tenantId The tenant ID
     * @param canonicalAction The canonical action name (after alias resolution).
     *     Used to skip the {@code visualization} injection on read-only inspection
     *     actions ({@code get_run}, {@code get_node_output}, {@code describe}, …)
     *     so they don't steal side-panel focus. See {@link WorkflowBuilderActionConfig#isReadOnlyAction}.
     * @return Result with snapshot in metadata
     */
    @SuppressWarnings("unchecked")
    public ToolExecutionResult addSessionSnapshot(ToolExecutionResult result, Map<String, Object> params, String tenantId, String canonicalAction) {
        if (!result.success()) {
            return result;
        }

        WorkflowBuilderSession session = resolveSessionForSnapshot(result, params, tenantId);

        if (session != null) {
            Map<String, Object> meta = new HashMap<>(result.metadata() != null ? result.metadata() : Map.of());
            boolean isReadOnly = WorkflowBuilderActionConfig.isReadOnlyAction(canonicalAction);

            // Defense in depth: actively REMOVE any upstream-injected `visualization`
            // when the action is read-only. Without this, a CRUD handler that injects
            // its own viz (e.g. WorkflowCrudModule.executeGet pre-fix) would slip
            // through the read-only guard because we only used to skip the ADD.
            // Now any read action → viz is stripped, regardless of who put it there.
            if (isReadOnly) {
                meta.remove("visualization");
            }

            String draftId = session.getLoadedWorkflowId();
            if (draftId != null) {
                // Inject visualization so the side panel focuses the workflow tab on
                // every successful WRITE action (add_node, connect, modify, execute,
                // load, finish, …). Skip on read-only inspections (get_run,
                // get_node_output, describe, validate, list, …) - those interrupt
                // the user's flow if they hijack focus.
                if (!isReadOnly) {
                    meta.putIfAbsent("visualization",
                            Map.of("type", "workflow", "id", draftId, "title", session.getWorkflowName()));
                }

                if (result.data() instanceof Map) {
                    Map<String, Object> data = new LinkedHashMap<>((Map<String, Object>) result.data());
                    data.putIfAbsent("draft_id", draftId);
                    return new ToolExecutionResult(true, data, null, null, meta);
                }
            }
            return new ToolExecutionResult(true, result.data(), null, null, meta);
        }

        // No session loaded: still strip any upstream `visualization` for read-only
        // actions. Covers the case where a CRUD handler is called WITHOUT a builder
        // session (e.g. workflow.get on a workflow that isn't the active draft).
        if (WorkflowBuilderActionConfig.isReadOnlyAction(canonicalAction)
                && result.metadata() != null
                && result.metadata().containsKey("visualization")) {
            Map<String, Object> stripped = new HashMap<>(result.metadata());
            stripped.remove("visualization");
            return new ToolExecutionResult(true, result.data(), null, null, stripped);
        }

        return result;
    }

    /**
     * Log an action execution.
     *
     * @param action The action name
     * @param params The action parameters
     * @param result The execution result
     * @param tenantId The tenant ID
     */
    @SuppressWarnings("unchecked")
    public void logAction(String action, Map<String, Object> params, ToolExecutionResult result, String tenantId) {
        WorkflowBuilderSession session = resolveSessionForLogging(result, params, tenantId);

        String message = null;
        if (result.data() instanceof Map) {
            message = (String) ((Map<String, Object>) result.data()).get("message");
        }
        if (!result.success() && result.error() != null) {
            message = result.error();
        }

        buildLogger.logAction(session, action, params, result.data(), result.success(), message);
    }

    /**
     * Resolve session for snapshot addition.
     */
    @SuppressWarnings("unchecked")
    private WorkflowBuilderSession resolveSessionForSnapshot(ToolExecutionResult result, Map<String, Object> params, String tenantId) {
        WorkflowBuilderSession session = null;
        String sessionId = (String) params.get("session_id");

        if (sessionId != null) {
            session = sessionManager.getSessionStore().get(sessionId).orElse(null);
        }

        if (session == null && result.data() instanceof Map) {
            sessionId = (String) ((Map<String, Object>) result.data()).get("session_id");
            if (sessionId != null) {
                session = sessionManager.getSessionStore().get(sessionId).orElse(null);
            }
        }

        if (session == null) {
            var sessions = sessionManager.getSessionsForTenant(tenantId);
            if (sessions.size() == 1) {
                session = sessions.get(0);
            }
        }

        return session;
    }

    /**
     * Resolve session for logging.
     */
    @SuppressWarnings("unchecked")
    private WorkflowBuilderSession resolveSessionForLogging(ToolExecutionResult result, Map<String, Object> params, String tenantId) {
        WorkflowBuilderSession session = null;
        String sessionId = (String) params.get("session_id");

        if (sessionId != null) {
            session = sessionManager.getSessionStore().get(sessionId).orElse(null);
        } else if (result.success() && result.data() instanceof Map) {
            sessionId = (String) ((Map<String, Object>) result.data()).get("session_id");
            if (sessionId != null) {
                session = sessionManager.getSessionStore().get(sessionId).orElse(null);
            }
        }

        if (session == null) {
            var sessions = sessionManager.getSessionsForTenant(tenantId);
            if (sessions.size() == 1) {
                session = sessions.get(0);
            }
        }

        return session;
    }
}
