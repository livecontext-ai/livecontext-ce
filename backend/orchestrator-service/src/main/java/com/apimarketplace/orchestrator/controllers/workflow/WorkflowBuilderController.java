package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.orchestrator.service.NodeLibraryService;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderCommonResponses;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderPrompts;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSessionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Controller for workflow builder session management.
 * Used by conversation-service to get active session info for agent context.
 */
@Slf4j
@RestController
@RequestMapping("/api/workflow-builder")
@RequiredArgsConstructor
public class WorkflowBuilderController {

    private final WorkflowBuilderSessionStore sessionStore;
    private final NodeLibraryService nodeLibraryService;

    /**
     * Get active workflow builder sessions for the current user.
     * Called by conversation-service to inject session info into agent context.
     *
     * @param tenantId The tenant ID from header
     * @param conversationId Optional conversation ID for conversation-scoped lookup
     */
    @GetMapping("/sessions/active")
    public ResponseEntity<Map<String, Object>> getActiveSessions(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestParam(value = "conversationId", required = false) String conversationId) {

        Map<String, Object> response = new LinkedHashMap<>();

        // Prefer conversation-scoped lookup if conversationId is provided
        if (conversationId != null && !conversationId.isBlank()) {
            return sessionStore.getSessionForConversation(tenantId, conversationId)
                .map(session -> {
                    response.put("hasActiveSession", true);
                    response.put("session", toSessionInfo(session));
                    return ResponseEntity.ok(response);
                })
                .orElseGet(() -> {
                    response.put("hasActiveSession", false);
                    return ResponseEntity.ok(response);
                });
        }

        // Fallback: return all sessions for tenant (legacy behavior)
        List<WorkflowBuilderSession> sessions = sessionStore.getSessionsForTenant(tenantId);

        response.put("hasActiveSession", !sessions.isEmpty());

        if (!sessions.isEmpty()) {
            // Return info about active sessions
            List<Map<String, Object>> sessionInfos = sessions.stream()
                .map(this::toSessionInfo)
                .toList();
            response.put("sessions", sessionInfos);

            // If only one session, also provide it at top level for convenience
            if (sessions.size() == 1) {
                response.put("session", sessionInfos.get(0));
            }
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Get a specific session by ID.
     */
    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<Map<String, Object>> getSession(
            @PathVariable String sessionId,
            @RequestHeader("X-User-ID") String tenantId) {

        return sessionStore.get(sessionId)
            .filter(s -> tenantId.equals(s.getTenantId()))
            .map(session -> {
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("found", true);
                response.put("session", toSessionInfo(session));
                return ResponseEntity.ok(response);
            })
            .orElse(ResponseEntity.ok(Map.of("found", false)));
    }

    private Map<String, Object> toSessionInfo(WorkflowBuilderSession session) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("sessionId", session.getSessionId());
        info.put("name", session.getWorkflowName());
        info.put("description", session.getWorkflowDescription());
        info.put("createdAt", session.getCreatedAt().toString());
        info.put("updatedAt", session.getUpdatedAt().toString());

        if (session.getLoadedWorkflowId() != null) {
            info.put("draftId", session.getLoadedWorkflowId());
        }

        // Include the full plan (like load does)
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", session.getTriggers());
        plan.put("mcps", session.getMcps());
        plan.put("cores", session.getCores());
        plan.put("edges", session.getEdges());
        plan.put("interfaces", session.getInterfaces());
        plan.put("tables", session.getTables());
        info.put("plan", plan);

        // Include context (rules, variable_syntax, actions, NEXT)
        info.put("context", buildContext(session));

        return info;
    }

    /**
     * Build context with rules, variable syntax, available node types, and NEXT guidance.
     * Same content as init/load responses for consistency.
     */
    private Map<String, Object> buildContext(WorkflowBuilderSession session) {
        Map<String, Object> context = new LinkedHashMap<>();

        // Detect phase for contextual NEXT guidance
        WorkflowBuilderPrompts.Phase phase = WorkflowBuilderPrompts.detectPhase(session);
        boolean hasTrigger = !session.getTriggers().isEmpty();

        // Rules - include "trigger first" only if no trigger yet
        context.put("rules", WorkflowBuilderCommonResponses.buildRules(!hasTrigger));

        // Variable syntax
        context.put("variable_syntax", WorkflowBuilderCommonResponses.buildVariableSyntax());

        // Available node types by category (from database - single source of truth)
        context.put("available_node_types_by_category", nodeLibraryService.getNodeTypesMap());

        // Actions
        context.put("actions", WorkflowBuilderCommonResponses.buildActions());

        // Phase and NEXT guidance
        context.put("phase", phase.name());
        context.put("NEXT", buildNextGuidance(session, phase));

        // Help reference
        context.put("help", "workflow(action='help', topics=['webhook', 'agent', 'decision']) for params details");

        return context;
    }

    /**
     * Build NEXT guidance based on current session state.
     */
    private String buildNextGuidance(WorkflowBuilderSession session, WorkflowBuilderPrompts.Phase phase) {
        return switch (phase) {
            case NO_SESSION -> "workflow(action='init', name='...', description='...')";
            case STARTING -> "workflow(action='add_node', type='<trigger_type>', label='...', params={...}) - trigger MUST be created first";
            case BUILDING -> {
                String lastNode = session.getLastAddedNodeId();
                if (lastNode != null) {
                    String label = session.findNode(lastNode)
                        .map(n -> (String) n.get("label"))
                        .orElse(lastNode);
                    yield "workflow(action='add_node', type='...', label='...', params={...}, connect_after='" + label + "')";
                }
                yield "workflow(action='add_node', type='...', label='...', params={...}, connect_after='<previous_node_label>')";
            }
            case WIRING -> {
                List<String> orphans = session.findOrphanNodes();
                if (!orphans.isEmpty()) {
                    yield "workflow(action='connect', from='...', to='" + orphans.get(0) + "') - connect orphan nodes";
                }
                yield "workflow(action='validate') then workflow(action='connect', ...)";
            }
            case FIXING -> "workflow(action='validate') to see issues, then fix with modify/remove/connect";
            case READY -> "workflow(action='finish') to finalize and save (closes the build session)";
        };
    }
}
