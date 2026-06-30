package com.apimarketplace.orchestrator.services.resume.skip;

import com.apimarketplace.orchestrator.domain.workflow.Edge;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.utils.EdgeRefParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Graph analysis utilities for skip propagation.
 * Provides methods to traverse and classify nodes in the workflow graph.
 *
 * Extracted from SkipPropagationService for Single Responsibility Principle.
 *
 * @see SkipPropagationService
 */
@Component
public class SkipGraphAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(SkipGraphAnalyzer.class);

    // ═══════════════════════════════════════════════════════════════════════════
    // GRAPH TRAVERSAL
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * V2: Find all successor node IDs (nodes that this node has edges pointing TO).
     *
     * <p>In V2, this is simple: find all edges where from matches the nodeId,
     * considering ports (loop:label:body, decision:label:if, etc.)
     *
     * @param plan The workflow plan
     * @param nodeId The node ID to find successors for
     * @return List of successor node IDs
     */
    public List<String> findSuccessorsFromEdges(WorkflowPlan plan, String nodeId) {
        List<String> successors = new ArrayList<>();

        if (plan.getEdges() == null) {
            return successors;
        }

        for (Edge edge : plan.getEdges()) {
            if (edge.from() == null || edge.to() == null) {
                continue;
            }

            // V2: Parse the from reference
            EdgeRefParser.EdgeRef fromRef = EdgeRefParser.parse(edge.from());
            if (fromRef == null) {
                continue;
            }

            // Check if this edge is from our node (ignore port for matching)
            String fromKey = fromRef.getNodeKey();
            if (!fromKey.equals(nodeId) && !edge.from().equals(nodeId)) {
                continue;
            }

            // V2: The successor is the 'to' of the edge (without port)
            String toKey = EdgeRefParser.getNodeKey(edge.to());
            if (toKey == null) {
                toKey = edge.to();
            }

            if (!successors.contains(toKey)) {
                successors.add(toKey);
            }
        }

        return successors;
    }

    /**
     * V2: Find all predecessor node IDs (nodes that have edges pointing TO this node).
     *
     * <p>In V2, this is simple: find all edges where to matches the nodeId.
     *
     * @param plan The workflow plan
     * @param nodeId The node ID to find predecessors for
     * @return List of predecessor node IDs
     */
    public List<String> findPredecessorsFromEdges(WorkflowPlan plan, String nodeId) {
        List<String> predecessors = new ArrayList<>();

        if (plan.getEdges() == null) {
            return predecessors;
        }

        for (Edge edge : plan.getEdges()) {
            if (edge.to() == null || edge.from() == null) {
                continue;
            }

            // V2: Parse the to reference
            String toKey = EdgeRefParser.getNodeKey(edge.to());
            if (toKey == null) {
                toKey = edge.to();
            }

            // Check if this edge points to our node
            if (toKey.equals(nodeId) || edge.to().equals(nodeId)) {
                // Get the predecessor (from node, without port)
                String fromKey = EdgeRefParser.getNodeKey(edge.from());
                if (fromKey == null) {
                    fromKey = normalizeNodeId(edge.from());
                }
                if (!predecessors.contains(fromKey)) {
                    predecessors.add(fromKey);
                }
            }
        }

        return predecessors;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NODE CLASSIFICATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * V2: Check if a node is a merge node (convergence point with multiple incoming edges).
     *
     * <p>Merge nodes should not be skipped during skip propagation because they can still
     * be reached from other non-skipped paths.
     *
     * <p>In V2, we simply count edges where 'to' targets this nodeId.
     *
     * @param plan The workflow plan
     * @param nodeId The node ID to check
     * @return true if the node has multiple incoming edges
     */
    public boolean isMergeNode(WorkflowPlan plan, String nodeId) {
        if (plan.getEdges() == null) {
            return false;
        }

        int incomingEdgeCount = 0;

        for (Edge edge : plan.getEdges()) {
            if (edge.to() == null) {
                continue;
            }

            // V2: Parse the to reference
            String toKey = EdgeRefParser.getNodeKey(edge.to());
            if (toKey == null) {
                toKey = normalizeNodeId(edge.to());
            }

            if (toKey.equals(nodeId) || edge.to().equals(nodeId)) {
                incomingEdgeCount++;
            }

            // Early exit if we found multiple incoming edges
            if (incomingEdgeCount > 1) {
                logger.debug("[SkipGraphAnalyzer] V2: Node {} is a merge node ({} incoming edges)", nodeId, incomingEdgeCount);
                return true;
            }
        }

        return false;
    }

    /**
     * V2: Check if a node is a direct destination of a decision (other than the one currently skipping).
     *
     * <p>This prevents skipping nodes that are branch targets of other decisions.
     *
     * <p>In V2, we check if any edge comes from a decision node (decision:label:port) and goes to nodeId.
     *
     * @param plan The workflow plan
     * @param nodeId The node ID to check
     * @param currentSkipSourceId The decision node that is currently doing the skipping
     * @return true if the node is a direct destination of another decision
     */
    public boolean isDirectDecisionDestination(WorkflowPlan plan, String nodeId, String currentSkipSourceId) {
        if (plan.getEdges() == null) {
            return false;
        }

        String normalizedNodeId = normalizeNodeId(nodeId);

        for (Edge edge : plan.getEdges()) {
            if (edge.from() == null || edge.to() == null) {
                continue;
            }

            // V2: Check if edge comes from a decision/switch node
            // In V2, nodeType from EdgeRefParser is "core", not "decision"
            // Check for decision/switch ports (if, else, elseif_N, case_N, default)
            EdgeRefParser.EdgeRef fromRef = EdgeRefParser.parse(edge.from());
            if (fromRef == null || !"core".equals(fromRef.nodeType())) {
                continue;
            }
            // Skip if not a decision or switch port
            String portType = EdgeRefParser.getPortType(fromRef.port());
            if (!"decision".equals(portType) && !"switch".equals(portType)) {
                continue;
            }

            String decisionKey = fromRef.getNodeKey();

            // Skip if this is the same decision that's currently doing the skipping
            if (decisionKey.equals(currentSkipSourceId)) {
                continue;
            }

            // Check if this edge targets our node
            String toKey = EdgeRefParser.getNodeKey(edge.to());
            if (toKey == null) {
                toKey = normalizeNodeId(edge.to());
            }

            if (toKey.equals(normalizedNodeId)) {
                logger.debug("[SkipGraphAnalyzer] V2: {} is target of decision {} (port: {})",
                    nodeId, decisionKey, fromRef.port());
                return true;
            }
        }

        return false;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UTILITY METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Normalize a node ID by removing any port suffix.
     * Example: "core:loop:body" -> "core:loop"
     *
     * @param nodeId The node ID (possibly with port)
     * @return The normalized node ID (without port)
     */
    public String normalizeNodeId(String nodeId) {
        if (nodeId == null) {
            return null;
        }
        String normalized = EdgeRefParser.getNodeKey(nodeId);
        return normalized != null ? normalized : nodeId;
    }

    /**
     * Extract the label part from a node ID.
     * Example: "mcp:my_step" -> "my_step", "core:my_loop:body" -> "my_loop"
     *
     * @param nodeId The node ID
     * @return The label part
     */
    public String extractLabel(String nodeId) {
        if (nodeId == null) {
            return null;
        }
        EdgeRefParser.EdgeRef ref = EdgeRefParser.parse(nodeId);
        if (ref != null) {
            return ref.nodeLabel();
        }
        // Fallback: split by ':'
        String[] parts = nodeId.split(":");
        return parts.length > 1 ? parts[1] : nodeId;
    }
}
