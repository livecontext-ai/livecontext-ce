package com.apimarketplace.orchestrator.tools.workflow.builder.validation;

import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.utils.EdgeRefParser;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyzes workflow graph structure for validation.
 * Provides methods for cycle detection, reachability analysis, and edge counting.
 *
 * Note: This is different from GraphAnalyzer in the parent package which
 * analyzes variable accessibility based on WorkflowPlan objects.
 */
public class ValidationGraphAnalyzer {

    private final WorkflowBuilderSession session;
    private final Map<String, List<String>> outgoing = new HashMap<>();
    private final Map<String, List<String>> incoming = new HashMap<>();
    private final Set<String> allNodes = new HashSet<>();

    public ValidationGraphAnalyzer(WorkflowBuilderSession session) {
        this.session = session;
        buildGraph();
    }

    private void buildGraph() {
        // Collect all node IDs using LabelNormalizer as single source of truth
        for (Map<String, Object> trigger : session.getTriggers()) {
            String label = (String) trigger.get("label");
            allNodes.add(LabelNormalizer.triggerKey(label));
        }
        for (Map<String, Object> step : session.getMcps()) {
            String label = (String) step.get("label");
            Boolean isAgent = (Boolean) step.get("isAgent");
            if (isAgent != null && isAgent) {
                allNodes.add(LabelNormalizer.agentKey(label));
            } else {
                allNodes.add(LabelNormalizer.mcpKey(label));
            }
        }
        for (Map<String, Object> cn : session.getCores()) {
            String label = (String) cn.get("label");
            // Use LabelNormalizer.coreKey() to be consistent with edge format (core:label)
            allNodes.add(LabelNormalizer.coreKey(label));
        }
        for (Map<String, Object> iface : session.getInterfaces()) {
            String label = (String) iface.get("label");
            if (label == null) {
                label = (String) iface.get("name");
            }
            allNodes.add(LabelNormalizer.interfaceKey(label));
        }
        for (Map<String, Object> table : session.getTables()) {
            String label = (String) table.get("label");
            allNodes.add(LabelNormalizer.tableKey(label));
        }

        // Build edge maps - V2 format: simple { from, to } with optional ports
        for (Map<String, Object> edge : session.getEdges()) {
            String from = (String) edge.get("from");
            String to = edge.get("to") instanceof String ? (String) edge.get("to") : null;

            if (from != null && to != null) {
                // V2: Extract base node from port-based 'from' field
                String baseFromNode = extractBaseNodeId(from);

                outgoing.computeIfAbsent(baseFromNode, k -> new ArrayList<>()).add(to);
                incoming.computeIfAbsent(to, k -> new ArrayList<>()).add(baseFromNode);
            }
        }
    }

    /**
     * V2: Extract base node ID from a port-based reference.
     * Examples:
     * - "core:my_loop:body" -> "core:my_loop"
     * - "core:check:if" -> "core:check"
     * - "agent:classify:category_0" -> "agent:classify"
     * - "mcp:my_step" -> "mcp:my_step"
     */
    private String extractBaseNodeId(String nodeRef) {
        if (nodeRef == null) return null;
        // EdgeRefParser is the single source of truth for the port set.
        return EdgeRefParser.splitPort(nodeRef)[0];
    }

    public boolean nodeExists(String nodeId) {
        // First check exact match
        if (allNodes.contains(nodeId)) {
            return true;
        }
        // For port-based references (e.g., core:if_else:if), check if base node exists
        String baseNode = extractBaseNodeId(nodeId);
        return allNodes.contains(baseNode);
    }

    public Set<String> getAllNodeIds() {
        return allNodes;
    }

    public Map<String, Integer> getIncomingEdgeCounts() {
        Map<String, Integer> counts = new HashMap<>();
        for (String node : allNodes) {
            counts.put(node, incoming.getOrDefault(node, List.of()).size());
        }
        return counts;
    }

    public Set<String> getReachableFromTriggers() {
        Set<String> reachable = new HashSet<>();
        Queue<String> queue = new LinkedList<>();

        // Start from all triggers
        for (String node : allNodes) {
            if (node.startsWith("trigger:")) {
                queue.add(node);
                reachable.add(node);
            }
        }

        // BFS
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String next : outgoing.getOrDefault(current, List.of())) {
                if (!reachable.contains(next)) {
                    reachable.add(next);
                    queue.add(next);
                }
            }
        }

        return reachable;
    }

    public List<String> detectCycles() {
        List<String> cycles = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> recStack = new HashSet<>();

        for (String node : allNodes) {
            detectCyclesDFS(node, visited, recStack, new ArrayList<>(), cycles);
        }

        return cycles;
    }

    private boolean detectCyclesDFS(String node, Set<String> visited, Set<String> recStack,
                                    List<String> path, List<String> cycles) {
        if (recStack.contains(node)) {
            // Found cycle
            int startIdx = path.indexOf(node);
            if (startIdx >= 0) {
                String cycle = path.subList(startIdx, path.size()).stream()
                        .collect(Collectors.joining(" -> ")) + " -> " + node;
                cycles.add(cycle);
            }
            return true;
        }

        if (visited.contains(node)) {
            return false;
        }

        visited.add(node);
        recStack.add(node);
        path.add(node);

        for (String next : outgoing.getOrDefault(node, List.of())) {
            detectCyclesDFS(next, visited, recStack, path, cycles);
        }

        path.remove(path.size() - 1);
        recStack.remove(node);
        return false;
    }

    public boolean hasOutgoingEdges(String nodeId) {
        List<String> edges = outgoing.get(nodeId);
        return edges != null && !edges.isEmpty();
    }
}
