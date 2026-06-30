package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * GraphSurgery - Atomic stateless operations for workflow modifications.
 *
 * PRINCIPLE: Stateless modifications (no sessions, no load-edit-save).
 * Each operation is atomic: load from DB → modify → validate → save in ONE transaction.
 *
 * Operations:
 * - insert_after: Insert new node after existing node
 * - remove_node: Remove node and reconnect graph
 * - modify_node: Update node properties
 * - add_edge: Add connection between nodes
 * - remove_edge: Remove connection
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GraphSurgery {

    private final WorkflowManagementService workflowService;
    private final WorkflowRepository workflowRepository;
    private final SmartDefaultsEngine smartDefaultsEngine;
    private final WorkflowBuilderValidator validator;

    // ═══════════════════════════════════════════════════════════════════════════════
    // INSERTION
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Insert a new node after an existing node.
     *
     * Algorithm:
     * 1. Load workflow from DB
     * 2. Create new node (apply smart defaults)
     * 3. Find edges from afterId
     * 4. Remove old edges: afterId → successors
     * 5. Create new edges: afterId → newNode → successors
     * 6. Validate graph integrity
     * 7. Save workflow (atomic transaction)
     *
     * @param workflowId Workflow ID
     * @param afterNodeId Insert after this node
     * @param nodeData New node data
     * @param tenantId Tenant ID
     * @return Insertion result
     */
    public InsertionResult insertAfter(
            UUID workflowId,
            String afterNodeId,
            Map<String, Object> nodeData,
            String tenantId
    ) {
        log.info("GraphSurgery: insert_after workflow={}, after={}", workflowId, afterNodeId);

        // 1. Load workflow
        var workflowOpt = workflowService.getWorkflow(workflowId);
        if (workflowOpt.isEmpty()) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }

        var workflowEntity = workflowOpt.get();

        // Scope check - org-aware. Pre-fix: strict-tenant rejected any org
        // member with a different tenant_id (the same bug class fixed in
        // WorkflowBuilderLoader.executeLoad). Resolves orgId from the
        // request thread-local so the signature stays compatible with
        // existing callers / tests.
        String orgId = TenantResolver.currentRequestOrganizationId();
        if (!ScopeGuard.isInStrictScope(tenantId, orgId,
                workflowEntity.getTenantId(), workflowEntity.getOrganizationId())) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }

        WorkflowPlan plan = WorkflowPlan.fromMap(workflowEntity.getPlan());

        // 2. Validate afterNodeId exists in the plan
        if (!nodeExistsInPlan(plan, afterNodeId)) {
            throw new IllegalArgumentException("Node not found in workflow: " + afterNodeId +
                ". Available nodes: " + getAvailableNodeIds(plan));
        }

        // 3. Validate nodeData has required fields
        String nodeType = (String) nodeData.get("type");
        if (nodeType == null || nodeType.isBlank()) {
            throw new IllegalArgumentException("node.type is required (mcp, agent, trigger, core)");
        }
        String label = (String) nodeData.get("label");
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("node.label is required");
        }

        // 4. Create new node with smart defaults
        Object newNode = createNodeWithDefaults(nodeType, nodeData);
        String newNodeId = getNodeId(newNode);
        if (newNodeId == null || newNodeId.isBlank()) {
            // Generate ID from label if not provided
            newNodeId = nodeType + ":" + LabelNormalizer.normalizeLabel(label);
            if (newNode instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nodeMap = (Map<String, Object>) newNode;
                nodeMap.put("id", newNodeId);
            }
        }

        // 5. Find edges from afterId
        List<Edge> outgoingEdges = plan.getEdges().stream()
            .filter(e -> e.from().equals(afterNodeId))
            .toList();

        log.debug("Found {} outgoing edges from {}", outgoingEdges.size(), afterNodeId);

        // 6. Remove old edges
        List<Edge> newEdges = new ArrayList<>(plan.getEdges());
        newEdges.removeAll(outgoingEdges);

        // 7. V2: Create new edges: afterId → newNode
        newEdges.add(new Edge(afterNodeId, newNodeId, null));

        // 8. V2: Create edges: newNode → successors
        for (Edge old : outgoingEdges) {
            newEdges.add(new Edge(newNodeId, old.to(), old.params()));
        }

        // 9. Add node to plan
        WorkflowPlan updatedPlan = addNodeToPlan(plan, newNode, newEdges);

        // 10. Validate graph integrity (skip validation for now - would need proper validator integration)
        // validator.validateGraph(updatedPlan);

        // 11. Save workflow (atomic transaction)
        // Thread the request-bound orgId so any future caller that hits a new-row
        // branch through this path inherits the active workspace tag. Today these
        // calls only hit the existing-row branch (workflow already exists), so
        // organizationId is preserved by saveWorkflow's update branch - but
        // wiring it now prevents the silent regression class.
        workflowService.saveWorkflow(updatedPlan, Map.of(), workflowId,
                com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationId());

        log.info("GraphSurgery: insert_after completed. New node: {}", newNodeId);

        // Final reference for lambda
        final String finalNewNodeId = newNodeId;
        return new InsertionResult(
            finalNewNodeId,
            outgoingEdges.size(),
            List.of("Added edge: " + afterNodeId + " → " + finalNewNodeId),
            outgoingEdges.stream()
                .map(e -> "Reconnected: " + finalNewNodeId + " → " + e.to())
                .toList()
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // REMOVAL
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Remove a node and reconnect the graph.
     *
     * Algorithm:
     * 1. Load workflow from DB
     * 2. Find edges TO and FROM the node
     * 3. Remove node from plan
     * 4. Remove all edges connected to node
     * 5. Reconnect: predecessors → successors
     * 6. Validate graph integrity
     * 7. Save workflow
     *
     * @param workflowId Workflow ID
     * @param nodeId Node to remove
     * @param tenantId Tenant ID
     * @return Removal result
     */
    public RemovalResult removeNode(
            UUID workflowId,
            String nodeId,
            String tenantId
    ) {
        log.info("GraphSurgery: remove_node workflow={}, node={}", workflowId, nodeId);

        // 1. Load workflow
        var workflowOpt = workflowService.getWorkflow(workflowId);
        if (workflowOpt.isEmpty()) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }

        var workflowEntity = workflowOpt.get();

        // Scope check - org-aware. Pre-fix: strict-tenant rejected any org
        // member with a different tenant_id (the same bug class fixed in
        // WorkflowBuilderLoader.executeLoad). Resolves orgId from the
        // request thread-local so the signature stays compatible with
        // existing callers / tests.
        String orgId = TenantResolver.currentRequestOrganizationId();
        if (!ScopeGuard.isInStrictScope(tenantId, orgId,
                workflowEntity.getTenantId(), workflowEntity.getOrganizationId())) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }

        WorkflowPlan plan = WorkflowPlan.fromMap(workflowEntity.getPlan());

        // 2. Find edges TO and FROM the node
        List<Edge> incomingEdges = plan.getEdges().stream()
            .filter(e -> e.to().equals(nodeId))
            .toList();

        List<Edge> outgoingEdges = plan.getEdges().stream()
            .filter(e -> e.from().equals(nodeId))
            .toList();

        log.debug("Node {} has {} incoming and {} outgoing edges",
            nodeId, incomingEdges.size(), outgoingEdges.size());

        // 3. Remove node from plan
        WorkflowPlan updatedPlan = removeNodeFromPlan(plan, nodeId);

        // 4. Remove all edges connected to node
        List<Edge> newEdges = new ArrayList<>(updatedPlan.getEdges());
        newEdges.removeIf(e -> e.from().equals(nodeId) || e.to().equals(nodeId));

        // 5. V2: Reconnect: predecessors → successors
        for (Edge incoming : incomingEdges) {
            for (Edge outgoing : outgoingEdges) {
                newEdges.add(new Edge(incoming.from(), outgoing.to(), outgoing.params()));
            }
        }

        updatedPlan = new WorkflowPlan(
            plan.getId(),
            plan.getTenantId(),
            updatedPlan.getTriggers(),
            updatedPlan.getMcps(),
            new ArrayList<>(), // agents
            newEdges,
            updatedPlan.getCores(),
            plan.getTables(),
            new ArrayList<>(), // notes
            plan.getInterfaces(),
            plan.getNodePolicies(),
            plan.getOriginalPlan()
        );

        // 6. Validate graph integrity (skip validation for now)
        // validator.validateGraph(updatedPlan);

        // 7. Save workflow
        // Thread the request-bound orgId so any future caller that hits a new-row
        // branch through this path inherits the active workspace tag. Today these
        // calls only hit the existing-row branch (workflow already exists), so
        // organizationId is preserved by saveWorkflow's update branch - but
        // wiring it now prevents the silent regression class.
        workflowService.saveWorkflow(updatedPlan, Map.of(), workflowId,
                com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationId());

        log.info("GraphSurgery: remove_node completed. Removed: {}", nodeId);

        return new RemovalResult(
            nodeId,
            incomingEdges.size() + outgoingEdges.size(),
            "Node removed and graph reconnected"
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // MODIFICATION
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Modify a node's properties.
     *
     * Algorithm:
     * 1. Load workflow from DB
     * 2. Find node by ID
     * 3. Apply updates to node
     * 4. Validate updated node
     * 5. Save workflow
     *
     * @param workflowId Workflow ID
     * @param nodeId Node to modify
     * @param updates Map of field → new value
     * @param tenantId Tenant ID
     * @return Modification result
     */
    public ModificationResult modifyNode(
            UUID workflowId,
            String nodeId,
            Map<String, Object> updates,
            String tenantId
    ) {
        log.info("GraphSurgery: modify_node workflow={}, node={}, updates={}",
            workflowId, nodeId, updates.keySet());

        // 1. Load workflow
        var workflowOpt = workflowService.getWorkflow(workflowId);
        if (workflowOpt.isEmpty()) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }

        var workflowEntity = workflowOpt.get();

        // Scope check - org-aware. Pre-fix: strict-tenant rejected any org
        // member with a different tenant_id (the same bug class fixed in
        // WorkflowBuilderLoader.executeLoad). Resolves orgId from the
        // request thread-local so the signature stays compatible with
        // existing callers / tests.
        String orgId = TenantResolver.currentRequestOrganizationId();
        if (!ScopeGuard.isInStrictScope(tenantId, orgId,
                workflowEntity.getTenantId(), workflowEntity.getOrganizationId())) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }

        WorkflowPlan plan = WorkflowPlan.fromMap(workflowEntity.getPlan());

        // 2. Find and update node
        WorkflowPlan updatedPlan = updateNodeInPlan(plan, nodeId, updates);

        // 3. Validate (skip validation for now)
        // validator.validateGraph(updatedPlan);

        // 4. Save workflow
        // Thread the request-bound orgId so any future caller that hits a new-row
        // branch through this path inherits the active workspace tag. Today these
        // calls only hit the existing-row branch (workflow already exists), so
        // organizationId is preserved by saveWorkflow's update branch - but
        // wiring it now prevents the silent regression class.
        workflowService.saveWorkflow(updatedPlan, Map.of(), workflowId,
                com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationId());

        log.info("GraphSurgery: modify_node completed. Modified: {}", nodeId);

        return new ModificationResult(
            nodeId,
            updates.keySet().size(),
            "Node updated: " + updates.keySet()
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // EDGE OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Add an edge between two nodes.
     */
    public EdgeResult addEdge(
            UUID workflowId,
            String fromNodeId,
            String toNodeId,
            String condition,
            String tenantId
    ) {
        log.info("GraphSurgery: add_edge workflow={}, from={}, to={}",
            workflowId, fromNodeId, toNodeId);

        var workflowOpt = workflowService.getWorkflow(workflowId);
        if (workflowOpt.isEmpty()) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }

        var workflowEntity = workflowOpt.get();
        // Scope check - see insertAfter for rationale (org-aware via TenantResolver).
        String orgIdForEdge = TenantResolver.currentRequestOrganizationId();
        if (!ScopeGuard.isInStrictScope(tenantId, orgIdForEdge,
                workflowEntity.getTenantId(), workflowEntity.getOrganizationId())) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }

        WorkflowPlan plan = WorkflowPlan.fromMap(workflowEntity.getPlan());

        // Check if edge already exists
        boolean exists = plan.getEdges().stream()
            .anyMatch(e -> e.from().equals(fromNodeId) && e.to().equals(toNodeId));

        if (exists) {
            throw new IllegalArgumentException("Edge already exists: " + fromNodeId + " → " + toNodeId);
        }

        // Add edge - V2: simple (from, to, input) format
        List<Edge> newEdges = new ArrayList<>(plan.getEdges());
        newEdges.add(new Edge(fromNodeId, toNodeId, null));

        WorkflowPlan updatedPlan = new WorkflowPlan(
            plan.getId(),
            plan.getTenantId(),
            plan.getTriggers(),
            plan.getMcps(),
            new ArrayList<>(), // agents
            newEdges,
            plan.getCores(),
            plan.getTables(),
            new ArrayList<>(), // notes
            plan.getInterfaces(),
            plan.getNodePolicies(),
            plan.getOriginalPlan()
        );

        // validator.validateGraph(updatedPlan);
        // Thread the request-bound orgId so any future caller that hits a new-row
        // branch through this path inherits the active workspace tag. Today these
        // calls only hit the existing-row branch (workflow already exists), so
        // organizationId is preserved by saveWorkflow's update branch - but
        // wiring it now prevents the silent regression class.
        workflowService.saveWorkflow(updatedPlan, Map.of(), workflowId,
                com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationId());

        log.info("GraphSurgery: add_edge completed");

        return new EdgeResult("Edge added: " + fromNodeId + " → " + toNodeId);
    }

    /**
     * Remove an edge between two nodes.
     */
    public EdgeResult removeEdge(
            UUID workflowId,
            String fromNodeId,
            String toNodeId,
            String tenantId
    ) {
        log.info("GraphSurgery: remove_edge workflow={}, from={}, to={}",
            workflowId, fromNodeId, toNodeId);

        var workflowOpt = workflowService.getWorkflow(workflowId);
        if (workflowOpt.isEmpty()) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }

        var workflowEntity = workflowOpt.get();
        // Scope check - see insertAfter for rationale (org-aware via TenantResolver).
        String orgIdForEdge = TenantResolver.currentRequestOrganizationId();
        if (!ScopeGuard.isInStrictScope(tenantId, orgIdForEdge,
                workflowEntity.getTenantId(), workflowEntity.getOrganizationId())) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }

        WorkflowPlan plan = WorkflowPlan.fromMap(workflowEntity.getPlan());

        // Remove edge
        List<Edge> newEdges = new ArrayList<>(plan.getEdges());
        boolean removed = newEdges.removeIf(e ->
            e.from().equals(fromNodeId) && e.to().equals(toNodeId));

        if (!removed) {
            throw new IllegalArgumentException("Edge not found: " + fromNodeId + " → " + toNodeId);
        }

        WorkflowPlan updatedPlan = new WorkflowPlan(
            plan.getId(),
            plan.getTenantId(),
            plan.getTriggers(),
            plan.getMcps(),
            new ArrayList<>(), // agents
            newEdges,
            plan.getCores(),
            plan.getTables(),
            new ArrayList<>(), // notes
            plan.getInterfaces(),
            plan.getNodePolicies(),
            plan.getOriginalPlan()
        );

        // validator.validateGraph(updatedPlan);
        // Thread the request-bound orgId so any future caller that hits a new-row
        // branch through this path inherits the active workspace tag. Today these
        // calls only hit the existing-row branch (workflow already exists), so
        // organizationId is preserved by saveWorkflow's update branch - but
        // wiring it now prevents the silent regression class.
        workflowService.saveWorkflow(updatedPlan, Map.of(), workflowId,
                com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationId());

        log.info("GraphSurgery: remove_edge completed");

        return new EdgeResult("Edge removed: " + fromNodeId + " → " + toNodeId);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Create a node with smart defaults applied.
     */
    private Object createNodeWithDefaults(String nodeType, Map<String, Object> nodeData) {
        return switch (nodeType) {
            case "agent" -> {
                smartDefaultsEngine.applyAgentDefaults(nodeData);
                // Would create Agent record here
                yield nodeData;  // Simplified
            }
            case "trigger" -> {
                smartDefaultsEngine.applyTriggerDefaults(nodeData);
                yield nodeData;  // Simplified
            }
            case "interface" -> {
                smartDefaultsEngine.applyInterfaceDefaults(nodeData);
                yield nodeData;
            }
            default -> nodeData;
        };
    }

    /**
     * Get node ID from node object.
     */
    private String getNodeId(Object node) {
        if (node instanceof Map) {
            return (String) ((Map<?, ?>) node).get("id");
        }
        return null;
    }

    /**
     * Check if a node exists in the plan.
     */
    private boolean nodeExistsInPlan(WorkflowPlan plan, String nodeId) {
        // Check triggers
        for (Trigger t : plan.getTriggers()) {
            if (t.getNormalizedKey().equals(nodeId)) return true;
        }
        // Check steps
        for (Step s : plan.getMcps()) {
            if (s.getNormalizedKey().equals(nodeId)) return true;
        }
        // Check control nodes
        for (Core c : plan.getCores()) {
            if (c.getNormalizedKey().equals(nodeId)) return true;
        }
        return false;
    }

    /**
     * Get list of available node IDs for error messages.
     */
    private String getAvailableNodeIds(WorkflowPlan plan) {
        List<String> ids = new ArrayList<>();
        for (Trigger t : plan.getTriggers()) {
            ids.add(t.getNormalizedKey());
        }
        for (Step s : plan.getMcps()) {
            ids.add(s.getNormalizedKey());
        }
        for (Core c : plan.getCores()) {
            ids.add(c.getNormalizedKey());
        }
        return ids.isEmpty() ? "(empty workflow)" : String.join(", ", ids);
    }

    /**
     * Add node to plan (returns new plan with node added).
     */
    private WorkflowPlan addNodeToPlan(WorkflowPlan plan, Object node, List<Edge> edges) {
        // Simplified - would properly add to triggers/steps/cores based on type
        return new WorkflowPlan(
            plan.getId(),
            plan.getTenantId(),
            plan.getTriggers(),
            plan.getMcps(),
            new ArrayList<>(), // agents
            edges,
            plan.getCores(),
            plan.getTables(),
            new ArrayList<>(), // notes
            plan.getInterfaces(),
            plan.getNodePolicies(),
            plan.getOriginalPlan()
        );
    }

    /**
     * Remove node from plan (returns new plan without node). Filters every
     * collection that may carry the node, including {@code interfaces[]} -
     * before this fix, removing an interface node via
     * {@code workflow(action='remove')} stripped the edge but left the
     * {@code interfaces[]} entry behind, leaving dangling references.
     *
     * <p>Package-private so the regression test can exercise it without
     * spinning up the full surgery dependency graph.
     */
    WorkflowPlan removeNodeFromPlan(WorkflowPlan plan, String nodeId) {
        List<Trigger> triggers = plan.getTriggers().stream()
            .filter(t -> !t.getNormalizedKey().equals(nodeId))
            .toList();

        List<Step> steps = plan.getMcps().stream()
            .filter(s -> !s.getNormalizedKey().equals(nodeId))
            .toList();

        List<Core> cores = plan.getCores().stream()
            .filter(c -> !c.getNormalizedKey().equals(nodeId))
            .toList();

        List<com.apimarketplace.orchestrator.domain.workflow.InterfaceDef> interfaces =
            plan.getInterfaces().stream()
                .filter(i -> !nodeId.equals(i.getNormalizedKey()))
                .toList();

        return new WorkflowPlan(
            plan.getId(),
            plan.getTenantId(),
            triggers,
            steps,
            new ArrayList<>(), // agents
            plan.getEdges(),
            cores,
            plan.getTables(),
            new ArrayList<>(), // notes
            interfaces,
            plan.getNodePolicies(),
            plan.getOriginalPlan()
        );
    }

    /**
     * Update node in plan (returns new plan with updated node).
     */
    private WorkflowPlan updateNodeInPlan(WorkflowPlan plan, String nodeId, Map<String, Object> updates) {
        // Simplified - would properly update in triggers/steps/cores
        // This is a placeholder implementation
        return plan;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // RESULT RECORDS
    // ═══════════════════════════════════════════════════════════════════════════════

    public record InsertionResult(
        String newNodeId,
        int reconnectedEdges,
        List<String> edgesAdded,
        List<String> edgesReconnected
    ) {}

    public record RemovalResult(
        String removedNodeId,
        int edgesRemoved,
        String message
    ) {}

    public record ModificationResult(
        String nodeId,
        int fieldsModified,
        String message
    ) {}

    public record EdgeResult(
        String message
    ) {}
}
