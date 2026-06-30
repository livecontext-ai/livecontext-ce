package com.apimarketplace.orchestrator.tools.workflow.builder.session;

import java.util.*;

/**
 * Handles snapshot generation for workflow session persistence.
 * Single Responsibility: Generating snapshots for conversation history.
 */
public class SessionSnapshotBuilder {

    /** Marker used to identify workflow session snapshots in conversation history */
    public static final String SNAPSHOT_MARKER = "[INTERNAL:WORKFLOW_SESSION]";

    private final String sessionId;
    private final String workflowName;
    private final String loadedWorkflowId;
    private final List<Map<String, Object>> triggers;
    private final List<Map<String, Object>> mcps;
    private final List<Map<String, Object>> cores;
    private final List<Map<String, Object>> edges;
    private final List<SessionAction> actionHistory;
    private final SessionEdgeManager edgeManager;

    public SessionSnapshotBuilder(
            String sessionId,
            String workflowName,
            String loadedWorkflowId,
            List<Map<String, Object>> triggers,
            List<Map<String, Object>> mcps,
            List<Map<String, Object>> cores,
            List<Map<String, Object>> edges,
            List<SessionAction> actionHistory,
            SessionEdgeManager edgeManager) {
        this.sessionId = sessionId;
        this.workflowName = workflowName;
        this.loadedWorkflowId = loadedWorkflowId;
        this.triggers = triggers;
        this.mcps = mcps;
        this.cores = cores;
        this.edges = edges;
        this.actionHistory = actionHistory;
        this.edgeManager = edgeManager;
    }

    /**
     * Generate a snapshot for persistence in conversation history.
     */
    public String toSnapshot() {
        StringBuilder sb = new StringBuilder();
        sb.append(SNAPSHOT_MARKER).append("\n");
        sb.append("Session: ").append(sessionId).append("\n");
        sb.append("Workflow: \"").append(workflowName != null ? workflowName : "Untitled").append("\"\n");

        String status = loadedWorkflowId != null ? "EDITING (id: " + loadedWorkflowId + ")" : "NEW";
        sb.append("Status: ").append(status).append("\n");

        int nodeCount = triggers.size() + mcps.size() + cores.size();
        sb.append("Nodes: ").append(nodeCount).append(" (");
        sb.append(triggers.size()).append(" triggers, ");
        sb.append(mcps.size()).append(" steps, ");
        sb.append(cores.size()).append(" control nodes)\n");

        sb.append("Edges: ").append(edges.size()).append("\n");

        if (!triggers.isEmpty() || !mcps.isEmpty() || !cores.isEmpty()) {
            sb.append("\n📋 NODES (use label in quotes for connect, modify, etc.):\n");

            for (Map<String, Object> t : triggers) {
                String label = (String) t.get("label");
                String type = (String) t.get("type");
                sb.append("  • \"").append(label).append("\" (trigger");
                if (type != null) sb.append(", ").append(type);
                sb.append(")\n");
            }

            for (Map<String, Object> s : mcps) {
                String label = (String) s.get("label");
                boolean isAgent = Boolean.TRUE.equals(s.get("isAgent"));
                String nodeType = isAgent ? "agent" : "mcp";
                sb.append("  • \"").append(label).append("\" (").append(nodeType).append(")\n");
            }

            for (Map<String, Object> cn : cores) {
                String label = (String) cn.get("label");
                String type = (String) cn.get("type");
                sb.append("  • \"").append(label).append("\" (").append(type).append(")");

                if ("decision".equals(type)) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> conditions = (List<Map<String, Object>>) cn.get("decisionConditions");
                    if (conditions == null) {
                        conditions = (List<Map<String, Object>>) cn.get("conditions");
                    }
                    if (conditions != null && !conditions.isEmpty()) {
                        List<String> branchLabels = conditions.stream()
                            .map(c -> (String) c.get("label"))
                            .filter(Objects::nonNull)
                            .toList();
                        if (!branchLabels.isEmpty()) {
                            sb.append(" branches: ").append(branchLabels);
                        }
                    }
                }
                sb.append("\n");
            }
            sb.append("\nEXAMPLE: workflow(action='connect', from='Trigger Label', to='Step Label')\n");
        }

        if (!actionHistory.isEmpty()) {
            SessionAction lastAction = actionHistory.get(actionHistory.size() - 1);
            sb.append("Last action: ").append(lastAction.getActionType());
            if (lastAction.getNodeId() != null) {
                sb.append(" (").append(lastAction.getNodeId()).append(")");
            }
            sb.append("\n");
        }

        List<String> orphans = edgeManager.findOrphanNodes();
        if (!orphans.isEmpty()) {
            sb.append("⚠️ Issues: ").append(orphans.size()).append(" disconnected node(s)\n");
        }

        return sb.toString();
    }

    /**
     * Represents an action performed in the session (for undo support).
     */
    @lombok.Data
    @lombok.Builder
    public static class SessionAction {
        private String actionType;
        private String nodeId;
        private String nodeType;
        private Map<String, Object> nodeData;
        private Map<String, Object> previousState;
        private java.time.Instant timestamp;
    }
}
