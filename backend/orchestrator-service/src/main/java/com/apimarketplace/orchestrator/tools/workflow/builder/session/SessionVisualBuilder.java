package com.apimarketplace.orchestrator.tools.workflow.builder.session;

import com.apimarketplace.orchestrator.utils.LabelNormalizer;

import java.util.*;

/**
 * Handles visual summary and display building for workflow sessions.
 * Single Responsibility: Building visual representations.
 *
 * Uses {@link LabelNormalizer} as the single source of truth for:
 * - Prefix detection and key type checking
 * - Key construction (triggerKey, mcpKey, agentKey, coreKey)
 */
public class SessionVisualBuilder {

    private final String workflowName;
    private final String loadedWorkflowId;
    private final List<Map<String, Object>> triggers;
    private final List<Map<String, Object>> mcps;
    private final List<Map<String, Object>> cores;
    private final SessionNodeFinder nodeFinder;
    private final SessionEdgeManager edgeManager;
    private final Map<String, List<String>> linkedInterfaces;

    public SessionVisualBuilder(
            String workflowName,
            String loadedWorkflowId,
            List<Map<String, Object>> triggers,
            List<Map<String, Object>> mcps,
            List<Map<String, Object>> cores,
            SessionNodeFinder nodeFinder,
            SessionEdgeManager edgeManager,
            Map<String, List<String>> linkedInterfaces) {
        this.workflowName = workflowName;
        this.loadedWorkflowId = loadedWorkflowId;
        this.triggers = triggers;
        this.mcps = mcps;
        this.cores = cores;
        this.nodeFinder = nodeFinder;
        this.edgeManager = edgeManager;
        this.linkedInterfaces = linkedInterfaces;
    }

    /**
     * Build a visual summary of the workflow for display.
     */
    public String buildVisualSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("╔═══════════════════════════════════════════════════════════════╗\n");
        sb.append(String.format("║  Workflow: \"%s\"%s║\n",
                workflowName,
                " ".repeat(Math.max(1, 50 - workflowName.length()))));

        String status = loadedWorkflowId != null ? "EDITING (id: " + loadedWorkflowId + ")" : "NEW";
        sb.append(String.format("║  Status: %-54s║\n", status));
        sb.append("╠═══════════════════════════════════════════════════════════════╣\n");

        List<String> visitedNodes = new ArrayList<>();

        for (Map<String, Object> trigger : triggers) {
            String label = (String) trigger.get("label");
            String nodeId = LabelNormalizer.triggerKey(label);
            String type = (String) trigger.getOrDefault("type", "datasource");

            sb.append(String.format("║  [TRIGGER] \"%s\" (%s)%s║\n",
                    label, type,
                    " ".repeat(Math.max(1, 45 - label.length() - type.length()))));

            List<String> interfaces = linkedInterfaces.getOrDefault(nodeId, List.of());
            if (!interfaces.isEmpty()) {
                sb.append(String.format("║      │     📋 Interfaces: %s%s║\n",
                        String.join(", ", interfaces),
                        " ".repeat(Math.max(1, 35 - String.join(", ", interfaces).length()))));
            }

            visitedNodes.add(nodeId);
            appendConnectedNodes(sb, nodeId, visitedNodes, 1);
        }

        sb.append("║                                                               ║\n");
        sb.append("╠═══════════════════════════════════════════════════════════════╣\n");

        int interfaceCount = linkedInterfaces.values().stream().mapToInt(List::size).sum();
        sb.append(String.format("║  📋 Interfaces: %d linked%s║\n",
                interfaceCount, " ".repeat(45)));

        List<String> orphans = edgeManager.findOrphanNodes();
        sb.append(String.format("║  ⚠️  Issues: %d%s║\n",
                orphans.size(), " ".repeat(50)));

        sb.append(String.format("║  🔗 Connections: %d total%s║\n",
                edgeManager.getPersistableEdges().size(), " ".repeat(43)));

        sb.append("╚═══════════════════════════════════════════════════════════════╝");

        return sb.toString();
    }

    private void appendConnectedNodes(StringBuilder sb, String fromNodeId, List<String> visited, int depth) {
        List<Map<String, Object>> outgoing = edgeManager.getOutgoingConnections(fromNodeId);
        String indent = "      │" + "     ".repeat(depth - 1);

        for (int i = 0; i < outgoing.size(); i++) {
            Map<String, Object> edge = outgoing.get(i);
            String toNodeId = (String) edge.get("to");
            if (toNodeId == null) toNodeId = (String) edge.get("target");
            if (toNodeId == null || visited.contains(toNodeId)) continue;

            visited.add(toNodeId);
            String condition = (String) edge.get("condition");

            Optional<Map<String, Object>> nodeOpt = nodeFinder.findNode(toNodeId);
            if (nodeOpt.isPresent()) {
                Map<String, Object> node = nodeOpt.get();
                String label = (String) node.get("label");
                String type = getNodeTypeLabel(toNodeId, node);

                String connector = (outgoing.size() > 1 && i < outgoing.size() - 1) ? "├─" : "└─";
                if (condition != null) {
                    sb.append(String.format("║  %s%s [%s] %s \"%s\"%s║\n",
                            indent, connector, condition, type, label,
                            " ".repeat(Math.max(1, 30 - label.length() - condition.length()))));
                } else {
                    sb.append(String.format("║  %s      ▼%s║\n", indent, " ".repeat(50)));
                    sb.append(String.format("║  %s  [%s] \"%s\"%s║\n",
                            indent, type, label,
                            " ".repeat(Math.max(1, 45 - label.length() - type.length()))));
                }

                List<String> interfaces = linkedInterfaces.getOrDefault(toNodeId, List.of());
                if (!interfaces.isEmpty()) {
                    sb.append(String.format("║  %s     📋 Interface: %s%s║\n",
                            indent, interfaces.get(0),
                            " ".repeat(Math.max(1, 35 - interfaces.get(0).length()))));
                }

                appendConnectedNodes(sb, toNodeId, visited, depth + 1);
            }
        }
    }

    /**
     * Get all nodes as DisplayNode objects.
     */
    public List<DisplayNode> getDisplayNodeList() {
        List<DisplayNode> result = new ArrayList<>();

        for (Map<String, Object> trigger : triggers) {
            String label = (String) trigger.get("label");
            String nodeId = LabelNormalizer.triggerKey(label);

            result.add(DisplayNode.builder()
                    .nodeId(nodeId)
                    .type(LabelNormalizer.PREFIX_TRIGGER)
                    .label(label)
                    .build());
        }

        for (Map<String, Object> mcp : mcps) {
            String label = (String) mcp.get("label");
            boolean isAgent = Boolean.TRUE.equals(mcp.get("isAgent"));
            String nodeId = LabelNormalizer.computeStepNodeId(label, isAgent);

            result.add(DisplayNode.builder()
                    .nodeId(nodeId)
                    .type(LabelNormalizer.getStepPrefix(isAgent))
                    .label(label)
                    .toolName((String) mcp.get("tool_id"))
                    .build());
        }

        for (Map<String, Object> cn : cores) {
            String label = (String) cn.get("label");
            String coreType = (String) cn.get("type"); // decision, loop, split, etc.
            String nodeId = LabelNormalizer.coreKey(label);

            List<String> branches = null;
            if ("decision".equals(coreType)) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> conditions = (List<Map<String, Object>>) cn.get("decisionConditions");
                if (conditions == null) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> fallback = (List<Map<String, Object>>) cn.get("conditions");
                    conditions = fallback;
                }
                if (conditions != null) {
                    branches = conditions.stream()
                        .map(c -> (String) c.get("label"))
                        .filter(Objects::nonNull)
                        .toList();
                }
            }

            result.add(DisplayNode.builder()
                    .nodeId(nodeId)
                    .type(coreType) // Keep specific type for display (decision, loop, etc.)
                    .label(label)
                    .branches(branches)
                    .build());
        }

        return result;
    }

    /**
     * Get all nodes as LogicalNode objects for display.
     */
    public List<LogicalNode> getLogicalNodeList() {
        List<LogicalNode> nodes = new ArrayList<>();

        for (Map<String, Object> t : triggers) {
            String label = (String) t.get("label");
            if (label != null) {
                String nodeId = LabelNormalizer.triggerKey(label);
                nodes.add(LogicalNode.builder()
                        .logicalId("\"" + label + "\"")
                        .nodeId(nodeId)
                        .type(LabelNormalizer.PREFIX_TRIGGER)
                        .label(label)
                        .build());
            }
        }

        for (Map<String, Object> s : mcps) {
            String label = (String) s.get("label");
            boolean isAgent = Boolean.TRUE.equals(s.get("isAgent"));
            if (label != null) {
                String nodeId = LabelNormalizer.computeStepNodeId(label, isAgent);
                String type = LabelNormalizer.getStepPrefix(isAgent);
                nodes.add(LogicalNode.builder()
                        .logicalId("\"" + label + "\"")
                        .nodeId(nodeId)
                        .type(type)
                        .label(label)
                        .build());
            }
        }

        for (Map<String, Object> cn : cores) {
            String label = (String) cn.get("label");
            String coreType = (String) cn.get("type"); // decision, loop, split, etc.
            if (label != null && coreType != null) {
                String nodeId = LabelNormalizer.coreKey(label);
                nodes.add(LogicalNode.builder()
                        .logicalId("\"" + label + "\"")
                        .nodeId(nodeId)
                        .type(coreType) // Keep specific type for display
                        .label(label)
                        .build());
            }
        }

        return nodes;
    }

    /**
     * Gets a human-readable type label for display purposes.
     *
     * @param nodeId The node ID with prefix (e.g., "core:my_loop")
     * @param node The node data (used to get specific core type)
     * @return A display label (TRIGGER, STEP, AGENT, LOOP, SPLIT, DECISION, etc.)
     */
    private String getNodeTypeLabel(String nodeId, Map<String, Object> node) {
        if (LabelNormalizer.isTriggerKey(nodeId)) return "TRIGGER";
        if (LabelNormalizer.isMcpKey(nodeId)) return "STEP";
        if (LabelNormalizer.isAgentKey(nodeId)) return "AGENT";
        if (LabelNormalizer.isCoreKey(nodeId)) {
            // Get specific core type for better display
            String coreType = (String) node.get("type");
            if (coreType != null) {
                return coreType.toUpperCase();
            }
            return "CORE";
        }
        return "NODE";
    }

    // Inner classes for display

    @lombok.Data
    @lombok.Builder
    public static class DisplayNode {
        private String nodeId;
        private String type;
        private String label;
        private String toolName;
        private Map<String, Object> input;
        private List<String> linkedInterfaces;
        private List<String> branches;
        private int depth;
    }

    @lombok.Data
    @lombok.Builder
    public static class LogicalNode {
        private String logicalId;
        private String nodeId;
        private String type;
        private String label;
    }
}
