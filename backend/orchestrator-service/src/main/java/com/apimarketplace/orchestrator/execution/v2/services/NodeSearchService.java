package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTree;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service for searching and indexing nodes in execution trees.
 *
 * <p>Centralizes all node search logic used by UnifiedExecutionEngine.
 *
 * <p>Provides:
 * <ul>
 *   <li>findNodeById - recursive search for a node by ID</li>
 *   <li>buildNodeMap - create a map of all nodes in a tree</li>
 *   <li>findNodeFromAllRoots - search across multiple root nodes</li>
 *   <li>buildNodeMapFromAllRoots - map all nodes from multiple roots</li>
 * </ul>
 *
 * <p>Uses polymorphic getAllChildNodes() method for tree traversal,
 * eliminating the need for instanceof checks. Each node type implements
 * getAllChildNodes() to expose its children (branches, body nodes, targets, etc.).
 */
@Service
public class NodeSearchService {

    /**
     * Find a node by ID in the execution tree.
     *
     * @param root The root node to start searching from
     * @param nodeId The ID of the node to find
     * @return The found node, or null if not found
     */
    public ExecutionNode findNodeById(ExecutionNode root, String nodeId) {
        if (root == null) {
            return null;
        }
        return findNodeByIdRecursive(root, nodeId, new HashSet<>());
    }

    /**
     * Find a node by ID searching from ALL root nodes (multi-workflow support).
     *
     * @param tree The execution tree with potentially multiple roots
     * @param nodeId The node ID to find
     * @return The found node, or null if not found in any tree
     */
    public ExecutionNode findNodeFromAllRoots(ExecutionTree tree, String nodeId) {
        for (ExecutionNode root : tree.getRootNodes()) {
            ExecutionNode found = findNodeById(root, nodeId);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * Build a map of nodeId -> ExecutionNode for the entire tree.
     *
     * @param root The root node of the tree
     * @return Map of all nodes indexed by their IDs
     */
    public Map<String, ExecutionNode> buildNodeMap(ExecutionNode root) {
        Map<String, ExecutionNode> nodeMap = new HashMap<>();
        buildNodeMapRecursive(root, nodeMap, new HashSet<>());
        return nodeMap;
    }

    /**
     * Build a node map from ALL root nodes (multi-workflow support).
     *
     * @param tree The execution tree with potentially multiple roots
     * @return Map of nodeId -> ExecutionNode for ALL nodes in all trees
     */
    public Map<String, ExecutionNode> buildNodeMapFromAllRoots(ExecutionTree tree) {
        Map<String, ExecutionNode> nodeMap = new HashMap<>();
        Set<String> visited = new HashSet<>();
        for (ExecutionNode root : tree.getRootNodes()) {
            buildNodeMapRecursive(root, nodeMap, visited);
        }
        return nodeMap;
    }

    // =========================================================================
    // PRIVATE HELPERS
    // =========================================================================

    /**
     * Recursive implementation of findNodeById with cycle detection.
     */
    private ExecutionNode findNodeByIdRecursive(ExecutionNode node, String nodeId, Set<String> visited) {
        if (node == null || visited.contains(node.getNodeId())) {
            return null;
        }
        visited.add(node.getNodeId());

        if (node.getNodeId().equals(nodeId)) {
            return node;
        }

        // Search in successors
        NodeExecutionResult dummyResult = NodeExecutionResult.success(node.getNodeId(), Map.of());
        List<ExecutionNode> nextNodes = node.getNextNodes(dummyResult);

        for (ExecutionNode child : nextNodes) {
            ExecutionNode found = findNodeByIdRecursive(child, nodeId, visited);
            if (found != null) {
                return found;
            }
        }

        // Search in child nodes using polymorphic getAllChildNodes()
        // This covers: DecisionNode branches, OptionNode choices,
        // AgentNode category/guardrail targets, UserApprovalNode port targets
        for (ExecutionNode childNode : node.getAllChildNodes()) {
            ExecutionNode found = findNodeByIdRecursive(childNode, nodeId, visited);
            if (found != null) {
                return found;
            }
        }

        return null;
    }

    /**
     * Recursive implementation of buildNodeMap with cycle detection.
     */
    private void buildNodeMapRecursive(ExecutionNode node, Map<String, ExecutionNode> nodeMap, Set<String> visited) {
        if (node == null || visited.contains(node.getNodeId())) {
            return;
        }
        visited.add(node.getNodeId());
        nodeMap.put(node.getNodeId(), node);

        // Traverse successors
        NodeExecutionResult dummyResult = NodeExecutionResult.success(node.getNodeId(), Map.of());
        for (ExecutionNode child : node.getNextNodes(dummyResult)) {
            buildNodeMapRecursive(child, nodeMap, visited);
        }

        // Traverse child nodes using polymorphic getAllChildNodes()
        // This covers: DecisionNode branches, OptionNode choices,
        // AgentNode category/guardrail targets, UserApprovalNode port targets
        for (ExecutionNode childNode : node.getAllChildNodes()) {
            buildNodeMapRecursive(childNode, nodeMap, visited);
        }
    }
}
