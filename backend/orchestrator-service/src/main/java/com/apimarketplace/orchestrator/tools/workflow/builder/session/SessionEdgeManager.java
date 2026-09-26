package com.apimarketplace.orchestrator.tools.workflow.builder.session;

import com.apimarketplace.orchestrator.utils.EdgeRefParser;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;

import java.util.*;

/**
 * Handles edge/connection management in a workflow session.
 * Single Responsibility: Edge operations (add, remove, find, validate).
 */
public class SessionEdgeManager {

    private final List<Map<String, Object>> edges;
    private final SessionNodeFinder nodeFinder;

    public SessionEdgeManager(List<Map<String, Object>> edges, SessionNodeFinder nodeFinder) {
        this.edges = edges;
        this.nodeFinder = nodeFinder;
    }

    /**
     * Add a connection (edge) between two nodes.
     *
     * V2 Format Note: The 'condition' parameter is kept for backward compatibility
     * during the session (for display purposes) but is NOT persisted in the final plan.
     *
     * For branching logic:
     * - Create a Decision node with decisionConditions
     * - Connect using ports: "core:label:if", "core:label:else", etc.
     *
     * @param fromNodeId Source node ID (can include port for decisions)
     * @param toNodeId Target node ID
     * @param condition Deprecated - kept for display only, not persisted in V2 format
     */
    public void addConnection(String fromNodeId, String toNodeId, String condition) {
        addConnection(fromNodeId, toNodeId, condition, null);
    }

    /**
     * Add a connection, optionally marking it as a loop-back.
     *
     * @param backEdge {@code {condition, maxIterations}} when this connection re-enters an
     *                 earlier node, else null. Unlike {@code condition}, this one IS persisted:
     *                 without it the connection reads as a forward dependency and the engine
     *                 would have the target wait for a node that runs after it.
     */
    public void addConnection(String fromNodeId, String toNodeId, String condition,
                              Map<String, Object> backEdge) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("from", fromNodeId);
        edge.put("to", toNodeId);
        // Note: condition is stored for session display but filtered out in getPersistableEdges()
        if (condition != null && !condition.isBlank()) {
            edge.put("condition", condition);
        }
        if (backEdge != null) {
            edge.put("backEdge", backEdge);
        }
        edges.add(edge);
    }

    /**
     * Remove a specific connection.
     * Uses prefix matching on 'from' to handle port-qualified edges:
     * e.g., removeConnection("core:fork", "mcp:step") also removes "core:fork:branch_0" → "mcp:step"
     */
    public boolean removeConnection(String fromNodeId, String toNodeId) {
        return edges.removeIf(edge -> {
            String from = (String) edge.get("from");
            String to = getEdgeTarget(edge);
            boolean fromMatches = fromNodeId.equals(from) || (from != null && from.startsWith(fromNodeId + ":"));
            return fromMatches && toNodeId.equals(to);
        });
    }

    /**
     * Check if a connection exists.
     * Uses prefix matching on 'from' to handle port-qualified edges:
     * e.g., hasConnection("core:fork", "mcp:step") matches "core:fork:branch_0" → "mcp:step"
     */
    public boolean hasConnection(String fromNodeId, String toNodeId) {
        return edges.stream().anyMatch(edge -> {
            String from = (String) edge.get("from");
            String to = getEdgeTarget(edge);
            boolean fromMatches = fromNodeId.equals(from) || (from != null && from.startsWith(fromNodeId + ":"));
            return fromMatches && toNodeId.equals(to);
        });
    }

    /**
     * Get all outgoing connections from a node.
     */
    public List<Map<String, Object>> getOutgoingConnections(String nodeId) {
        return edges.stream()
                .filter(edge -> {
                    String from = (String) edge.get("from");
                    if (from == null) return false;
                    return from.equals(nodeId) || from.startsWith(nodeId + ":");
                })
                .toList();
    }

    /**
     * Get all incoming connections to a node.
     */
    public List<Map<String, Object>> getIncomingConnections(String nodeId) {
        return edges.stream()
                .filter(edge -> nodeId.equals(getEdgeTarget(edge)))
                .toList();
    }

    /**
     * Remove edges connected to a node.
     * Returns information about what was disconnected.
     */
    public DisconnectionInfo removeEdgesForNode(String nodeId) {
        List<String> brokenIncoming = new ArrayList<>();
        List<String> brokenOutgoing = new ArrayList<>();

        var iterator = edges.iterator();
        while (iterator.hasNext()) {
            Map<String, Object> edge = iterator.next();
            String from = (String) edge.get("from");
            String to = getEdgeTarget(edge);

            if (nodeId.equals(to)) {
                brokenIncoming.add(from);
                iterator.remove();
            } else if (nodeId.equals(from) || (from != null && from.startsWith(nodeId + ":"))) {
                // Also match port-qualified edges (e.g., core:fork:branch_0 when removing core:fork)
                brokenOutgoing.add(to);
                iterator.remove();
            }
        }

        return new DisconnectionInfo(brokenIncoming, brokenOutgoing);
    }

    /**
     * Update edges when a node is renamed.
     */
    public void updateEdgesForRenamedNode(String oldNodeId, String newNodeId) {
        for (Map<String, Object> edge : edges) {
            if (oldNodeId.equals(edge.get("from"))) {
                edge.put("from", newNodeId);
            }
            String to = getEdgeTarget(edge);
            if (oldNodeId.equals(to)) {
                edge.put("to", newNodeId);
            }
        }
    }

    /**
     * Update all references when a node ID changes.
     */
    public void updateAllReferences(String oldNodeId, String newNodeId) {
        for (Map<String, Object> edge : edges) {
            String from = (String) edge.get("from");
            if (from != null) {
                if (from.equals(oldNodeId)) {
                    edge.put("from", newNodeId);
                } else if (from.startsWith(oldNodeId + ":")) {
                    String port = from.substring(oldNodeId.length());
                    edge.put("from", newNodeId + port);
                }
            }

            String to = (String) edge.get("to");
            if (to != null && to.equals(oldNodeId)) {
                edge.put("to", newNodeId);
            }
        }
    }

    /**
     * Find nodes with no incoming connections (orphans, except triggers and sticky notes).
     *
     * <p>A note is an annotation on the canvas: it is never connected and never runs. It is
     * in {@link SessionNodeFinder#getAllNodeIds()} so an agent can find and edit it, which
     * made every note an "orphan" and failed the agent's save on any workflow carrying one.
     */
    public List<String> findOrphanNodes() {
        Set<String> allNodes = new HashSet<>(nodeFinder.getAllNodeIds());
        Set<String> nodesWithIncoming = new HashSet<>();

        for (Map<String, Object> edge : edges) {
            String to = getEdgeTarget(edge);
            if (to != null) nodesWithIncoming.add(to);
        }

        List<String> orphans = new ArrayList<>();
        for (String nodeId : allNodes) {
            if (!nodeId.startsWith("trigger:") && !LabelNormalizer.isNoteKey(nodeId)
                    && !nodesWithIncoming.contains(nodeId)) {
                orphans.add(nodeId);
            }
        }
        return orphans;
    }

    /**
     * Find nodes with no outgoing connections (dead ends), sticky notes excepted for the
     * same reason as {@link #findOrphanNodes()}.
     */
    public List<String> findDeadEndNodes() {
        Set<String> allNodes = new HashSet<>(nodeFinder.getAllNodeIds());
        Set<String> nodesWithOutgoing = new HashSet<>();

        for (Map<String, Object> edge : edges) {
            String from = (String) edge.get("from");
            if (from != null) {
                String baseNode = extractBaseNodeFromPort(from);
                nodesWithOutgoing.add(baseNode);
            }
        }

        List<String> deadEnds = new ArrayList<>();
        for (String nodeId : allNodes) {
            if (!LabelNormalizer.isNoteKey(nodeId) && !nodesWithOutgoing.contains(nodeId)) {
                deadEnds.add(nodeId);
            }
        }
        return deadEnds;
    }

    /**
     * Get persistable edges in V2 format (from, to, input, backEdge).
     * Filters out visualization-only edges and removes non-V2 fields like "condition".
     *
     * V2 Edge Format:
     * - from: source node with optional port (e.g., "core:label:if")
     * - to: target node
     * - input: optional input mappings
     * - backEdge: optional loop-back marker {condition, maxIterations} for a declared back-edge
     *
     * Conditions for branching are stored in cores[].decisionConditions, NOT in edges.
     */
    public List<Map<String, Object>> getPersistableEdges() {
        List<Map<String, Object>> persistable = new ArrayList<>();
        for (Map<String, Object> edge : edges) {
            if (!Boolean.TRUE.equals(edge.get("_visualOnly"))) {
                // Create V2-compliant edge (only from, to, input, backEdge)
                Map<String, Object> v2Edge = new LinkedHashMap<>();
                v2Edge.put("from", edge.get("from"));
                v2Edge.put("to", edge.get("to"));

                // Include input if present
                Object input = edge.get("input");
                if (input != null) {
                    v2Edge.put("input", input);
                }

                // Include the loop-back marker if present. Dropping it here would silently
                // turn a declared back-edge into a forward edge on the next save, which
                // re-introduces the cycle into the wired graph.
                Object backEdge = edge.get("backEdge");
                if (backEdge != null) {
                    v2Edge.put("backEdge", backEdge);
                }

                // NOTE: "condition" field is NOT included in V2 format.
                // Branching conditions should be in cores[].decisionConditions

                persistable.add(v2Edge);
            }
        }
        return persistable;
    }

    // Helper methods

    private String getEdgeTarget(Map<String, Object> edge) {
        String to = (String) edge.get("to");
        if (to == null) to = (String) edge.get("target");
        return to;
    }

    private String extractBaseNodeFromPort(String nodeRef) {
        if (nodeRef == null) return null;
        // EdgeRefParser is the single source of truth for the port set.
        return EdgeRefParser.splitPort(nodeRef)[0];
    }

    /**
     * Information about disconnected edges after a node removal.
     */
    public record DisconnectionInfo(List<String> sourcesThatLostTarget, List<String> targetsThatLostSource) {
        public boolean hasDisconnections() {
            return !sourcesThatLostTarget.isEmpty() || !targetsThatLostSource.isEmpty();
        }
    }
}
