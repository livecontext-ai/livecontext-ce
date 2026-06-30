package com.apimarketplace.orchestrator.validation;

import com.apimarketplace.orchestrator.domain.workflow.Edge;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.utils.EdgeRefParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Validates that triggers in a WorkflowPlan define independent DAGs.
 *
 * Two triggers CANNOT share any descendant nodes UNLESS they are detected as
 * sharing the same DAG (multi-trigger DAG). Sharing is auto-detected from
 * edge topology - triggers whose descendants overlap share the same graph.
 * Each trigger fire creates its own epoch in its own DagState, so execution
 * is isolated.
 *
 * This validation is required for multi-DAG workflows where multiple triggers
 * can be fired independently and their DAGs execute in parallel.
 */
@Service
public class DAGIndependenceValidator {

    private static final Logger logger = LoggerFactory.getLogger(DAGIndependenceValidator.class);

    /**
     * Validates that all triggers in the plan have independent DAGs.
     *
     * @param plan The workflow plan to validate
     * @throws DAGValidationException if two or more triggers share descendant nodes
     */
    public void validateIndependence(WorkflowPlan plan) {
        List<Trigger> triggers = plan.getTriggers();

        if (triggers == null || triggers.size() <= 1) {
            logger.debug("[DAGValidator] Single trigger or no triggers - skipping validation");
            return;
        }

        logger.info("[DAGValidator] Validating DAG independence for {} triggers", triggers.size());

        // Build adjacency list for efficient traversal
        Map<String, Set<String>> adjacencyList = buildAdjacencyList(plan);

        // Collect descendants for each trigger
        Map<String, Set<String>> triggerDescendants = new HashMap<>();
        for (Trigger trigger : triggers) {
            String triggerId = trigger.getNormalizedKey();
            Set<String> descendants = collectDescendants(adjacencyList, triggerId);
            triggerDescendants.put(triggerId, descendants);
            logger.debug("[DAGValidator] Trigger {} has {} descendants: {}",
                triggerId, descendants.size(), descendants);
        }

        // Check for overlaps between each pair of triggers
        // SKIP validation for triggers in the same dagGroup (multi-trigger DAG)
        List<String> triggerIds = new ArrayList<>(triggerDescendants.keySet());
        for (int i = 0; i < triggerIds.size(); i++) {
            for (int j = i + 1; j < triggerIds.size(); j++) {
                String t1 = triggerIds.get(i);
                String t2 = triggerIds.get(j);

                // Multi-trigger DAG: skip validation for triggers that share descendants
                if (plan.areTriggersInSameDagGroup(t1, t2)) {
                    logger.info("[DAGValidator] Triggers {} and {} share descendants (same DAG group) - skipping independence check", t1, t2);
                    continue;
                }

                Set<String> desc1 = new HashSet<>(triggerDescendants.get(t1));
                Set<String> desc2 = triggerDescendants.get(t2);

                // Find intersection
                desc1.retainAll(desc2);

                if (!desc1.isEmpty()) {
                    logger.error("[DAGValidator] Triggers {} and {} share {} nodes: {}",
                        t1, t2, desc1.size(), desc1);
                    throw new DAGValidationException(t1, t2, desc1);
                }
            }
        }

        logger.info("[DAGValidator] All {} triggers have independent DAGs (multi-trigger groups allowed)", triggers.size());
    }

    /**
     * Build an adjacency list from the workflow plan edges.
     * Maps each node to the set of nodes it connects to (following edges).
     *
     * @param plan The workflow plan
     * @return Adjacency list mapping node keys to their successors
     */
    public Map<String, Set<String>> buildAdjacencyList(WorkflowPlan plan) {
        Map<String, Set<String>> adjacencyList = new HashMap<>();

        if (plan.getEdges() == null) {
            return adjacencyList;
        }

        for (Edge edge : plan.getEdges()) {
            // Get the node key (without port) for both from and to
            String fromKey = EdgeRefParser.getNodeKey(edge.from());
            String toKey = EdgeRefParser.getNodeKey(edge.to());

            if (fromKey == null || toKey == null) {
                logger.warn("[DAGValidator] Skipping invalid edge: {} -> {}", edge.from(), edge.to());
                continue;
            }

            adjacencyList.computeIfAbsent(fromKey, k -> new HashSet<>()).add(toKey);
        }

        return adjacencyList;
    }

    /**
     * Collect all descendant node IDs reachable from a starting node via BFS.
     *
     * @param adjacencyList The graph adjacency list
     * @param startNodeId The node to start traversal from
     * @return Set of all reachable node IDs (excluding the start node)
     */
    public Set<String> collectDescendants(Map<String, Set<String>> adjacencyList, String startNodeId) {
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new LinkedList<>();

        queue.add(startNodeId);
        visited.add(startNodeId);

        while (!queue.isEmpty()) {
            String currentNode = queue.poll();
            Set<String> successors = adjacencyList.getOrDefault(currentNode, Set.of());

            for (String successor : successors) {
                if (!visited.contains(successor)) {
                    visited.add(successor);
                    queue.add(successor);
                }
            }
        }

        // Remove the start node - we only want descendants
        visited.remove(startNodeId);
        return visited;
    }

    /**
     * Collect all descendant node IDs reachable from a trigger in the plan.
     *
     * @param plan The workflow plan
     * @param triggerId The trigger ID (e.g., "trigger:my_webhook")
     * @return Set of all reachable node IDs (excluding the trigger itself)
     */
    public Set<String> collectDescendants(WorkflowPlan plan, String triggerId) {
        Map<String, Set<String>> adjacencyList = buildAdjacencyList(plan);
        return collectDescendants(adjacencyList, triggerId);
    }

    /**
     * Collect ALL nodes belonging to a DAG: the trigger itself + all descendants.
     *
     * Unlike {@link #collectDescendants(WorkflowPlan, String)} which excludes the trigger,
     * this method includes it. This is needed for DAG-scoped reset operations where
     * the trigger node's state must also be cleared (e.g., epoch reset, signal cancellation).
     *
     * @param plan The workflow plan
     * @param triggerId The trigger ID (e.g., "trigger:my_webhook")
     * @return Set of all node IDs in this DAG (including the trigger)
     */
    public Set<String> collectDagNodes(WorkflowPlan plan, String triggerId) {
        Set<String> dagNodes = new HashSet<>(collectDescendants(plan, triggerId));
        dagNodes.add(triggerId);
        return dagNodes;
    }

    /**
     * Find which trigger owns a given node.
     * A node is "owned" by a trigger if it's only reachable from that trigger.
     *
     * <p>For multi-trigger DAG nodes (shared across triggers with overlapping descendants),
     * returns the FIRST trigger in the group. The caller should use the execution
     * context's triggerId for epoch-specific operations instead.
     *
     * @param plan The workflow plan
     * @param nodeId The node ID to check
     * @return Optional containing the owner trigger ID, or empty if node is orphan
     */
    public Optional<String> findOwnerTrigger(WorkflowPlan plan, String nodeId) {
        List<Trigger> triggers = plan.getTriggers();
        if (triggers == null || triggers.isEmpty()) {
            return Optional.empty();
        }

        Map<String, Set<String>> adjacencyList = buildAdjacencyList(plan);
        List<String> ownerTriggerIds = new ArrayList<>();

        for (Trigger trigger : triggers) {
            String triggerId = trigger.getNormalizedKey();
            Set<String> descendants = collectDescendants(adjacencyList, triggerId);

            if (descendants.contains(nodeId)) {
                ownerTriggerIds.add(triggerId);
            }
        }

        if (ownerTriggerIds.isEmpty()) {
            return Optional.empty();
        }

        if (ownerTriggerIds.size() == 1) {
            return Optional.of(ownerTriggerIds.get(0));
        }

        // Multiple owners: check if they all share the same DAG
        boolean allSameGroup = true;
        for (int i = 1; i < ownerTriggerIds.size(); i++) {
            if (!plan.areTriggersInSameDagGroup(ownerTriggerIds.get(0), ownerTriggerIds.get(i))) {
                allSameGroup = false;
                break;
            }
        }

        if (allSameGroup) {
            // Multi-trigger DAG: return first trigger (all share the same graph)
            logger.debug("[DAGValidator] Node {} is in multi-trigger DAG group, returning first owner: {}",
                nodeId, ownerTriggerIds.get(0));
            return Optional.of(ownerTriggerIds.get(0));
        }

        // Node is shared across DIFFERENT DAG groups - invalid state (should have been caught by validation)
        logger.warn("[DAGValidator] Node {} is shared across different DAG groups: {}", nodeId, ownerTriggerIds);
        return Optional.empty();
    }

    /**
     * Find ALL triggers that own a given node (reachable from).
     * For multi-trigger DAGs, returns all triggers in the same dagGroup
     * that can reach this node.
     *
     * @param plan The workflow plan
     * @param nodeId The node ID to check
     * @return List of all owner trigger IDs (may be empty for orphan nodes)
     */
    public List<String> findAllOwnerTriggers(WorkflowPlan plan, String nodeId) {
        List<Trigger> triggers = plan.getTriggers();
        if (triggers == null || triggers.isEmpty()) {
            return List.of();
        }

        Map<String, Set<String>> adjacencyList = buildAdjacencyList(plan);
        List<String> ownerTriggerIds = new ArrayList<>();

        for (Trigger trigger : triggers) {
            String triggerId = trigger.getNormalizedKey();
            Set<String> descendants = collectDescendants(adjacencyList, triggerId);
            if (descendants.contains(nodeId)) {
                ownerTriggerIds.add(triggerId);
            }
        }

        return ownerTriggerIds;
    }

    /**
     * Check if a node is shared across multiple triggers in the same DAG group.
     *
     * @param plan The workflow plan
     * @param nodeId The node ID to check
     * @return true if the node is reachable from multiple triggers in the same dagGroup
     */
    public boolean isMultiTriggerNode(WorkflowPlan plan, String nodeId) {
        List<String> owners = findAllOwnerTriggers(plan, nodeId);
        return owners.size() > 1;
    }

    /**
     * Get a mapping of which trigger owns each node in the plan.
     * Useful for debugging and visualization.
     *
     * @param plan The workflow plan
     * @return Map of nodeId -> triggerId (or null if orphan/shared)
     */
    public Map<String, String> buildNodeOwnershipMap(WorkflowPlan plan) {
        Map<String, String> ownership = new HashMap<>();
        List<Trigger> triggers = plan.getTriggers();

        if (triggers == null || triggers.isEmpty()) {
            return ownership;
        }

        Map<String, Set<String>> adjacencyList = buildAdjacencyList(plan);

        // First pass: collect all descendants for each trigger
        Map<String, Set<String>> triggerDescendants = new HashMap<>();
        for (Trigger trigger : triggers) {
            String triggerId = trigger.getNormalizedKey();
            Set<String> descendants = collectDescendants(adjacencyList, triggerId);
            triggerDescendants.put(triggerId, descendants);
        }

        // Second pass: assign ownership (null if shared)
        Set<String> allNodes = new HashSet<>();
        for (Set<String> descendants : triggerDescendants.values()) {
            allNodes.addAll(descendants);
        }

        for (String nodeId : allNodes) {
            String owner = null;
            for (Map.Entry<String, Set<String>> entry : triggerDescendants.entrySet()) {
                if (entry.getValue().contains(nodeId)) {
                    if (owner != null) {
                        // Shared node - no owner
                        owner = null;
                        break;
                    }
                    owner = entry.getKey();
                }
            }
            ownership.put(nodeId, owner);
        }

        return ownership;
    }
}
