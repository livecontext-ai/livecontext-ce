package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.orchestrator.tools.workflow.builder.session.*;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.*;

/**
 * Represents an active workflow builder session.
 * Orchestrates specialized managers for different responsibilities.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowBuilderSession {

    private String sessionId;
    private String tenantId;
    /**
     * Caller's active organization, if any. Threaded through trigger-builder
     * helpers (webhook/chat/form auto-create) so generated rows carry
     * {@code organization_id} matching the active workspace.
     */
    private String orgId;
    private String conversationId;  // Scopes session to a specific conversation
    private Instant createdAt;
    private Instant updatedAt;

    // Workflow metadata
    private String workflowName;
    private String workflowDescription;

    // Schedule configuration
    private Map<String, Object> schedule;

    // Plan components
    @Builder.Default
    private List<Map<String, Object>> triggers = new ArrayList<>();

    @Builder.Default
    private List<Map<String, Object>> mcps = new ArrayList<>();

    @Builder.Default
    private List<Map<String, Object>> cores = new ArrayList<>();

    @Builder.Default
    private List<Map<String, Object>> edges = new ArrayList<>();

    @Builder.Default
    private List<Map<String, Object>> interfaces = new ArrayList<>();

    @Builder.Default
    private List<Map<String, Object>> tables = new ArrayList<>();

    @Builder.Default
    private List<Map<String, Object>> notes = new ArrayList<>();

    @Builder.Default
    private Map<String, NodeSchema> nodeSchemas = new HashMap<>();

    @Builder.Default
    private Map<String, String> nodeParentLoop = new HashMap<>();

    private String lastAddedNodeId;

    @Builder.Default
    private List<SessionAction> actionHistory = new ArrayList<>();

    @Builder.Default
    private List<SessionAction> redoStack = new ArrayList<>();

    @Builder.Default
    private Map<String, List<String>> linkedInterfaces = new LinkedHashMap<>();

    @Builder.Default
    private Map<String, Map<String, String>> missingCredentials = new LinkedHashMap<>();

    private String loadedWorkflowId;

    /**
     * True when {@link #loadedWorkflowId} resolves to a {@code workflow_type=APPLICATION}
     * entity at load time. Applications are frozen acquired marketplace clones - their
     * plan is immutable in place; only {@code POST /workflows/{id}/reset-plan} may
     * restore from {@code basePlan}. Captured here so modifying-action dispatch in
     * {@code WorkflowBuilderProvider} can reject early without re-querying the DB.
     */
    private boolean loadedWorkflowIsApplication;

    /** Snapshot of the plan at load time, used for version archiving on save */
    private Map<String, Object> loadedPlanSnapshot;

    @Builder.Default
    private Map<String, Map<String, Object>> pendingLoopExits = new LinkedHashMap<>();

    /** Maps webhook trigger nodeId (e.g. "trigger:my_webhook") → token (e.g. "wh_a1b2c3...") */
    @Builder.Default
    private Map<String, String> webhookTokens = new LinkedHashMap<>();

    // Lazy-initialized managers (excluded from serialization)
    @JsonIgnore
    private transient SessionNodeFinder nodeFinder;
    @JsonIgnore
    private transient SessionEdgeManager edgeManager;

    /**
     * Session action for undo/redo support.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SessionAction {
        private String actionType;
        private String nodeId;
        private String nodeType;
        private Map<String, Object> nodeData;
        private Map<String, Object> previousState;
        private Instant timestamp;
    }

    // ==================== Factory Methods ====================

    public static String generateSessionId() {
        return "wb_" + UUID.randomUUID().toString().substring(0, 12);
    }

    public static WorkflowBuilderSession create(String tenantId, String conversationId, String name, String description) {
        return create(tenantId, null, conversationId, name, description);
    }

    /**
     * Org-aware overload. Stamps {@code orgId} on the session so that
     * the eventual workflow row written by {@code finishWorkflow} carries the
     * correct workspace scope (org-teammates can see it; personal stays personal).
     */
    public static WorkflowBuilderSession create(String tenantId, String orgId, String conversationId, String name, String description) {
        Instant now = Instant.now();
        return WorkflowBuilderSession.builder()
                .sessionId(generateSessionId())
                .tenantId(tenantId)
                .orgId(orgId)
                .conversationId(conversationId)
                .workflowName(name)
                .workflowDescription(description)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public static String normalizeLabel(String label) {
        String normalized = LabelNormalizer.normalizeLabel(label);
        return normalized != null ? normalized : "";
    }

    // ==================== Manager Accessors ====================

    private SessionNodeFinder getNodeFinder() {
        if (nodeFinder == null) {
            nodeFinder = new SessionNodeFinder(triggers, mcps, cores, interfaces, tables, notes, nodeSchemas);
        }
        return nodeFinder;
    }

    private SessionEdgeManager getEdgeManager() {
        if (edgeManager == null) {
            edgeManager = new SessionEdgeManager(edges, getNodeFinder());
        }
        return edgeManager;
    }

    // ==================== Node Operations (delegated) ====================

    @JsonIgnore
    public Optional<Map<String, Object>> findNode(String nodeId) {
        return getNodeFinder().findNode(nodeId);
    }

    public boolean nodeExists(String nodeId) {
        return getNodeFinder().nodeExists(nodeId);
    }

    @JsonIgnore
    public List<String> getAllNodeIds() {
        return getNodeFinder().getAllNodeIds();
    }

    @JsonIgnore
    public List<String> getAllLabels() {
        return getNodeFinder().getAllLabels();
    }

    public String findNodeByNormalizedLabel(String normalizedLabel) {
        return getNodeFinder().findNodeByNormalizedLabel(normalizedLabel);
    }

    public String findNodeByLabel(String label) {
        return getNodeFinder().findNodeByLabel(label);
    }

    public String resolveNodeReference(String reference) {
        return getNodeFinder().resolveNodeReference(reference);
    }

    @JsonIgnore
    public List<String> getSimilarLabels(String label) {
        return getNodeFinder().getSimilarLabels(label);
    }

    // ==================== Edge Operations (delegated) ====================

    public void addConnection(String fromNodeId, String toNodeId, String condition) {
        getEdgeManager().addConnection(fromNodeId, toNodeId, condition);
    }

    public boolean removeConnection(String fromNodeId, String toNodeId) {
        return getEdgeManager().removeConnection(fromNodeId, toNodeId);
    }

    public boolean hasConnection(String fromNodeId, String toNodeId) {
        return getEdgeManager().hasConnection(fromNodeId, toNodeId);
    }

    public List<Map<String, Object>> getOutgoingConnections(String nodeId) {
        return getEdgeManager().getOutgoingConnections(nodeId);
    }

    public List<Map<String, Object>> getIncomingConnections(String nodeId) {
        return getEdgeManager().getIncomingConnections(nodeId);
    }

    public DisconnectionInfo removeEdgesForNode(String nodeId) {
        SessionEdgeManager.DisconnectionInfo info = getEdgeManager().removeEdgesForNode(nodeId);
        return new DisconnectionInfo(info.sourcesThatLostTarget(), info.targetsThatLostSource());
    }

    public void updateEdgesForRenamedNode(String oldNodeId, String newNodeId) {
        getEdgeManager().updateEdgesForRenamedNode(oldNodeId, newNodeId);
    }

    @JsonIgnore
    public List<String> findOrphanNodes() {
        return getEdgeManager().findOrphanNodes();
    }

    @JsonIgnore
    public List<String> findDeadEndNodes() {
        return getEdgeManager().findDeadEndNodes();
    }

    // ==================== Plan Building ====================

    public Map<String, Object> buildPlanMap() {
        SessionPlanBuilder planBuilder = new SessionPlanBuilder(
                workflowName, workflowDescription, schedule,
                triggers, mcps, cores, interfaces, tables, notes, getEdgeManager());
        return planBuilder.buildPlanMap();
    }

    @JsonIgnore
    public Map<String, Object> getSummary() {
        SessionPlanBuilder planBuilder = new SessionPlanBuilder(
                workflowName, workflowDescription, schedule,
                triggers, mcps, cores, interfaces, tables, notes, getEdgeManager());
        return planBuilder.getSummary();
    }

    // ==================== Visual Building ====================

    public String buildVisualSummary() {
        SessionVisualBuilder visualBuilder = new SessionVisualBuilder(
                workflowName, loadedWorkflowId, triggers, mcps, cores,
                getNodeFinder(), getEdgeManager(), linkedInterfaces);
        return visualBuilder.buildVisualSummary();
    }

    @JsonIgnore
    public List<SessionVisualBuilder.DisplayNode> getDisplayNodeList() {
        SessionVisualBuilder visualBuilder = new SessionVisualBuilder(
                workflowName, loadedWorkflowId, triggers, mcps, cores,
                getNodeFinder(), getEdgeManager(), linkedInterfaces);
        return visualBuilder.getDisplayNodeList();
    }

    @JsonIgnore
    public List<LogicalNode> getLogicalNodeList() {
        SessionVisualBuilder visualBuilder = new SessionVisualBuilder(
                workflowName, loadedWorkflowId, triggers, mcps, cores,
                getNodeFinder(), getEdgeManager(), linkedInterfaces);
        return visualBuilder.getLogicalNodeList().stream()
                .map(LogicalNode::from)
                .toList();
    }

    // ==================== Snapshot ====================

    public static final String SNAPSHOT_MARKER = SessionSnapshotBuilder.SNAPSHOT_MARKER;

    public String toSnapshot() {
        SessionSnapshotBuilder snapshotBuilder = new SessionSnapshotBuilder(
                sessionId, workflowName, loadedWorkflowId,
                triggers, mcps, cores, edges,
                convertToSnapshotActions(), getEdgeManager());
        return snapshotBuilder.toSnapshot();
    }

    private List<SessionSnapshotBuilder.SessionAction> convertToSnapshotActions() {
        return actionHistory.stream()
                .map(a -> SessionSnapshotBuilder.SessionAction.builder()
                        .actionType(a.getActionType())
                        .nodeId(a.getNodeId())
                        .nodeType(a.getNodeType())
                        .nodeData(a.getNodeData())
                        .previousState(a.getPreviousState())
                        .timestamp(a.getTimestamp())
                        .build())
                .toList();
    }

    // ==================== Node Removal ====================

    public boolean removeNode(String nodeId) {
        // Purge per-node state keyed by this id. nodeExists() treats a lingering
        // nodeSchemas entry as "the node still exists" (SessionNodeFinder.nodeExists),
        // and findNodeByNormalizedLabel searches agent: BEFORE core:/interface:/…,
        // so without this purge a node later re-created under the SAME label but a
        // DIFFERENT prefix (e.g. an agent replaced by a code node) resolves back to
        // this now-removed phantom id - producing INVALID_EDGE_SOURCE on connect and
        // "node not found" on a node the Available list still shows. A schema is
        // present whenever a node carries one - triggers/agents/MCP steps on creation,
        // and most node types (incl. cores with outputs) once a workflow is loaded via
        // WorkflowBuilderLoader; the purge is a harmless no-op for types that never get
        // a schema, so the fix is uniform across all types. Routed through removeNode
        // (the single removal primitive) so the remove, undo-of-add, and redo-of-remove
        // paths are all covered. (Trade-off: undo of a remove restores the node into its
        // collection - resolvable again - but not its cached output-hint schema; that is
        // cosmetic - ResponseContextBuilder falls back to a generic {{prefix:label.output}}
        // reference when the schema is absent.)
        nodeSchemas.remove(nodeId);
        missingCredentials.remove(nodeId);
        if (nodeId.startsWith("trigger:")) {
            return triggers.removeIf(t ->
                nodeId.equals("trigger:" + normalizeLabel((String) t.get("label"))));
        } else if (nodeId.startsWith("mcp:")) {
            return mcps.removeIf(s ->
                !Boolean.TRUE.equals(s.get("isAgent")) &&
                nodeId.equals("mcp:" + normalizeLabel((String) s.get("label"))));
        } else if (nodeId.startsWith("agent:")) {
            return mcps.removeIf(s ->
                Boolean.TRUE.equals(s.get("isAgent")) &&
                nodeId.equals("agent:" + normalizeLabel((String) s.get("label"))));
        } else if (LabelNormalizer.isCoreKey(nodeId)) {
            return cores.removeIf(cn -> {
                String label = (String) cn.get("label");
                return nodeId.equals(LabelNormalizer.coreKey(label));
            });
        } else if (LabelNormalizer.isInterfaceKey(nodeId)) {
            return interfaces.removeIf(i -> {
                String label = (String) i.get("label");
                return nodeId.equals(LabelNormalizer.interfaceKey(label));
            });
        } else if (LabelNormalizer.isTableKey(nodeId)) {
            return tables.removeIf(t -> {
                String label = (String) t.get("label");
                return nodeId.equals(LabelNormalizer.tableKey(label));
            });
        } else if (LabelNormalizer.isNoteKey(nodeId)) {
            return notes.removeIf(n -> {
                String label = (String) n.get("label");
                return nodeId.equals(LabelNormalizer.noteKey(label));
            });
        }
        return false;
    }

    // ==================== Label Validation ====================

    /**
     * Cross-prefix label uniqueness check used by every creator's
     * {@code validateNodeNotExists} (and TriggerCreator). Returns an error message
     * if ANY existing node already holds this normalized label (under any prefix),
     * else null. NOTE: {@code nodeType} is informational only (used in the
     * blank-label message) - uniqueness is GLOBAL across prefixes, not per-type, on
     * purpose: the label resolver keys by normalized label with a fixed prefix
     * priority, so two same-label nodes of different types are ambiguous. Do not
     * "fix" this into a per-prefix check - that reintroduces the coexistence bug.
     */
    public String validateUniqueLabel(String label, String nodeType) {
        if (label == null || label.isBlank()) {
            return "Label is required for " + nodeType;
        }

        String normalizedLabel = normalizeLabel(label);
        String existingNode = findNodeByNormalizedLabel(normalizedLabel);

        if (existingNode != null) {
            Optional<Map<String, Object>> node = findNode(existingNode);
            String existingLabel = node.map(n -> (String) n.get("label")).orElse(existingNode);
            String existingType = existingNode.split(":")[0];

            return "Label '" + label + "' already exists as " + existingType + " '" + existingLabel + "'. " +
                   "Use a unique label for each node.";
        }

        return null;
    }

    // ==================== Interface Linking ====================

    public void linkInterface(String nodeId, String interfaceId) {
        linkedInterfaces.computeIfAbsent(nodeId, k -> new ArrayList<>()).add(interfaceId);
    }

    public boolean unlinkInterface(String nodeId, String interfaceId) {
        List<String> interfaces = linkedInterfaces.get(nodeId);
        return interfaces != null && interfaces.remove(interfaceId);
    }

    public List<String> unlinkAllInterfaces(String nodeId) {
        List<String> removed = linkedInterfaces.remove(nodeId);
        return removed != null ? removed : List.of();
    }

    public List<String> getLinkedInterfaces(String nodeId) {
        return linkedInterfaces.getOrDefault(nodeId, List.of());
    }

    @JsonIgnore
    public Map<String, List<String>> getAllLinkedInterfaces() {
        return new LinkedHashMap<>(linkedInterfaces);
    }

    @JsonIgnore
    public Optional<Map<String, Object>> findInterface(String interfaceId) {
        return interfaces.stream()
                .filter(i -> interfaceId.equals(i.get("id")))
                .findFirst();
    }

    /**
     * Single-entry invariant: an application has ONE entry page. When the interface
     * node {@code keepNode} becomes the entry, clear the flag on every other
     * interface node - mirroring the canvas builder's enforcement and matching the
     * showcase resolver's findFirst() semantics, so what the author set is what the
     * application opens on.
     *
     * <p>The kept node is identified by MAP IDENTITY, not by id: a plan interface
     * entry's {@code id} is the interface ENTITY UUID while callers resolve node
     * references to {@code interface:<label>} keys - an id comparison silently
     * demotes the very node that was just flagged (caught live in e2e).
     *
     * @return the labels (fallback: ids) of the interfaces whose flag was cleared
     */
    public List<String> enforceSingleEntryInterface(Map<String, Object> keepNode) {
        List<String> cleared = new ArrayList<>();
        for (Map<String, Object> iface : interfaces) {
            if (iface == keepNode) continue;
            boolean flagged = Boolean.TRUE.equals(iface.get("isEntryInterface"))
                    || Boolean.TRUE.equals(iface.get("is_entry_interface"));
            if (flagged) {
                iface.put("isEntryInterface", false);
                iface.remove("is_entry_interface");
                Object label = iface.get("label");
                cleared.add(String.valueOf(label != null ? label : iface.get("id")));
            }
        }
        return cleared;
    }

    // ==================== Credentials Tracking ====================

    public void trackMissingCredential(String nodeId, String serviceType, String serviceName, String iconSlug) {
        Map<String, String> credInfo = new LinkedHashMap<>();
        credInfo.put("serviceType", serviceType);
        credInfo.put("serviceName", serviceName);
        credInfo.put("iconSlug", iconSlug);
        missingCredentials.put(nodeId, credInfo);
    }

    public boolean hasMissingCredentials() {
        return !missingCredentials.isEmpty();
    }

    @JsonIgnore
    public Map<String, Map<String, String>> getMissingCredentials() {
        return new LinkedHashMap<>(missingCredentials);
    }

    @JsonIgnore
    public List<String> getMissingCredentialServices() {
        return missingCredentials.values().stream()
            .map(info -> info.get("serviceType"))
            .distinct()
            .toList();
    }

    public void removeMissingCredential(String nodeId) {
        missingCredentials.remove(nodeId);
    }

    // ==================== Action History ====================

    public void recordAction(String actionType, String nodeId, String nodeType, Map<String, Object> nodeData) {
        SessionAction action = SessionAction.builder()
                .actionType(actionType)
                .nodeId(nodeId)
                .nodeType(nodeType)
                .nodeData(nodeData != null ? new LinkedHashMap<>(nodeData) : null)
                .timestamp(Instant.now())
                .build();
        actionHistory.add(action);
        clearRedoStack();
    }

    @JsonIgnore
    public Optional<SessionAction> getLastAction() {
        return actionHistory.isEmpty() ? Optional.empty() : Optional.of(actionHistory.get(actionHistory.size() - 1));
    }

    @JsonIgnore
    public Optional<SessionAction> popLastAction() {
        return actionHistory.isEmpty() ? Optional.empty() : Optional.of(actionHistory.remove(actionHistory.size() - 1));
    }

    @JsonIgnore
    public List<String> getActionHistorySummary() {
        return actionHistory.stream()
                .map(a -> a.getActionType() + (a.getNodeId() != null ? ":" + a.getNodeId() : ""))
                .toList();
    }

    // ==================== Redo Support ====================

    public void pushToRedoStack(SessionAction action) {
        redoStack.add(action);
    }

    @JsonIgnore
    public Optional<SessionAction> popRedoStack() {
        return redoStack.isEmpty() ? Optional.empty() : Optional.of(redoStack.remove(redoStack.size() - 1));
    }

    public void clearRedoStack() {
        redoStack.clear();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    // ==================== Loop Exit Management ====================

    public void addPendingLoopExit(String loopId, String exitNodeId, String nodeType) {
        if (pendingLoopExits.containsKey(loopId)) {
            Optional<Map<String, Object>> loopNode = findNode(loopId);
            String loopLabel = loopNode.map(n -> (String) n.get("label")).orElse(loopId);
            throw new IllegalStateException(
                "Loop '" + loopLabel + "' already has an exit defined. Only ONE connect_after_loop per loop is allowed.");
        }

        Map<String, Object> exit = new LinkedHashMap<>();
        exit.put("nodeId", exitNodeId);
        exit.put("nodeType", nodeType);
        pendingLoopExits.put(loopId, exit);
    }

    public Map<String, Object> getPendingLoopExit(String loopId) {
        return pendingLoopExits.get(loopId);
    }

    public boolean hasPendingLoopExit(String loopId) {
        return pendingLoopExits.containsKey(loopId);
    }

    public void updateAllReferences(String oldNodeId, String newNodeId) {
        getEdgeManager().updateAllReferences(oldNodeId, newNodeId);

        // Re-key per-node state from old→new id. Same phantom-resolution class as
        // removeNode: a node renamed via modify (which changes its prefixed id, e.g.
        // when its label changes) would otherwise leave a stale nodeSchemas entry
        // under the old id, and nodeExists() would keep reporting the old id as live -
        // shadowing any node later created under that freed label.
        if (!oldNodeId.equals(newNodeId)) {
            NodeSchema schema = nodeSchemas.remove(oldNodeId);
            if (schema != null) {
                schema.setNodeId(newNodeId);
                // Rewrite the cached output-reference hints (e.g.
                // "{{agent:build_report.output.response}}") to the new id -
                // ResponseContextBuilder surfaces these verbatim to the agent, so a
                // stale old-id reference would point the LLM at a node that no longer
                // exists under that id.
                Map<String, String> refs = schema.getReferenceSyntax();
                if (refs != null) {
                    Map<String, String> rekeyed = new LinkedHashMap<>();
                    refs.forEach((k, v) -> rekeyed.put(k, v == null ? null : v.replace(oldNodeId, newNodeId)));
                    schema.setReferenceSyntax(rekeyed);
                }
                nodeSchemas.put(newNodeId, schema);
            }
            Map<String, String> cred = missingCredentials.remove(oldNodeId);
            if (cred != null) {
                missingCredentials.put(newNodeId, cred);
            }
        }

        if (oldNodeId.startsWith("core:")) {
            Map<String, Object> exit = pendingLoopExits.remove(oldNodeId);
            if (exit != null) pendingLoopExits.put(newNodeId, exit);
        }

        for (Map<String, Object> exit : pendingLoopExits.values()) {
            if (oldNodeId.equals(exit.get("nodeId"))) {
                exit.put("nodeId", newNodeId);
            }
        }
    }

    public void cleanupPendingLoopExit(String nodeId) {
        if (nodeId.startsWith("core:")) {
            pendingLoopExits.remove(nodeId);
        }
        pendingLoopExits.entrySet().removeIf(entry -> nodeId.equals(entry.getValue().get("nodeId")));
    }

    // ==================== Formatting Helpers ====================

    public String formatNodeRef(String nodeId, boolean includeType) {
        Optional<Map<String, Object>> node = findNode(nodeId);
        String label = node.map(n -> (String) n.get("label")).orElse(nodeId);
        if (includeType && nodeId.contains(":")) {
            String type = nodeId.split(":")[0];
            return "\"" + label + "\" (" + type + ")";
        }
        return "\"" + label + "\"";
    }

    public String formatNodeRefWithLabel(String nodeId) {
        Optional<Map<String, Object>> node = findNode(nodeId);
        String label = node.map(n -> (String) n.get("label")).orElse(nodeId);
        return "\"" + label + "\"";
    }

    // ==================== Backward Compatibility ====================

    public String getLogicalIdOrFail(String nodeId) {
        Optional<Map<String, Object>> node = findNode(nodeId);
        String label = node.map(n -> (String) n.get("label")).orElse(null);
        if (label != null) return "\"" + label + "\"";
        return "\"" + com.apimarketplace.orchestrator.utils.LabelNormalizer.extractLabelFromKey(nodeId) + "\"";
    }

    public String getLogicalId(String nodeId) {
        Optional<Map<String, Object>> node = findNode(nodeId);
        String label = node.map(n -> (String) n.get("label")).orElse(null);
        return label != null ? "\"" + label + "\"" : null;
    }

    // ==================== Type Aliases for Backward Compatibility ====================

    /**
     * Alias for backward compatibility - delegates to SessionNodeSchema.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NodeSchema {
        private String nodeId;
        private String nodeType;
        private String label;
        private String toolId;
        private Map<String, String> outputs;
        private Map<String, String> referenceSyntax;

        public static NodeSchema from(SessionNodeSchema schema) {
            if (schema == null) return null;
            return NodeSchema.builder()
                    .nodeId(schema.getNodeId())
                    .nodeType(schema.getNodeType())
                    .label(schema.getLabel())
                    .toolId(schema.getToolId())
                    .outputs(schema.getOutputs())
                    .referenceSyntax(schema.getReferenceSyntax())
                    .build();
        }
    }

    /**
     * Alias for backward compatibility - mirrors SessionVisualBuilder.LogicalNode.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LogicalNode {
        private String logicalId;
        private String nodeId;
        private String type;
        private String label;

        public static LogicalNode from(SessionVisualBuilder.LogicalNode node) {
            if (node == null) return null;
            return LogicalNode.builder()
                    .logicalId(node.getLogicalId())
                    .nodeId(node.getNodeId())
                    .type(node.getType())
                    .label(node.getLabel())
                    .build();
        }
    }

    /**
     * Alias for backward compatibility - mirrors SessionEdgeManager.DisconnectionInfo.
     */
    public record DisconnectionInfo(List<String> sourcesThatLostTarget, List<String> targetsThatLostSource) {
        public boolean hasDisconnections() {
            return !sourcesThatLostTarget.isEmpty() || !targetsThatLostSource.isEmpty();
        }
    }
}
