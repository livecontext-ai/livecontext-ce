package com.apimarketplace.orchestrator.domain.workflow;

import com.apimarketplace.orchestrator.utils.EdgeRefParser;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.*;

/**
 * Unified workflow plan structure.
 * Parsing is delegated to WorkflowPlanParser.
 * Structure analysis is delegated to WorkflowPlanAnalyzer.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkflowPlan {

    private final String id;
    private final String tenantId;
    private final List<Trigger> triggers;
    private final List<Step> mcps;
    private final List<Agent> agents;
    private final List<Edge> edges;
    private final List<Core> cores;
    private final List<Step> tables;
    private final List<Note> notes;
    private final List<InterfaceDef> interfaces;
    private final Map<String, NodePolicy> nodePolicies;
    private final Map<String, Object> originalPlan;

    // Lazy-loaded caches
    private transient ExecutionGraph executionGraph;
    private transient Map<String, MergeNode> mergeNodes;
    private transient Map<String, Step> mcpsByNormalizedAlias;

    /**
     * Back-compat constructor (pre-nodePolicy call sites) - no per-node execution
     * policies; every node resolves to {@link NodePolicy#DEFAULT} = exact legacy behavior.
     */
    public WorkflowPlan(String id, String tenantId, List<Trigger> triggers, List<Step> mcps,
                        List<Agent> agents, List<Edge> edges, List<Core> cores,
                        List<Step> tables, List<Note> notes, List<InterfaceDef> interfaces,
                        Map<String, Object> originalPlan) {
        this(id, tenantId, triggers, mcps, agents, edges, cores, tables, notes, interfaces,
                Map.of(), originalPlan);
    }

    public WorkflowPlan(String id, String tenantId, List<Trigger> triggers, List<Step> mcps,
                        List<Agent> agents, List<Edge> edges, List<Core> cores,
                        List<Step> tables, List<Note> notes, List<InterfaceDef> interfaces,
                        Map<String, NodePolicy> nodePolicies,
                        Map<String, Object> originalPlan) {
        this.id = id;
        this.tenantId = tenantId;
        this.triggers = triggers != null ? new ArrayList<>(triggers) : new ArrayList<>();
        this.mcps = mcps != null ? new ArrayList<>(mcps) : new ArrayList<>();
        this.agents = agents != null ? new ArrayList<>(agents) : new ArrayList<>();
        this.edges = edges != null ? new ArrayList<>(edges) : new ArrayList<>();
        this.cores = cores != null ? new ArrayList<>(cores) : new ArrayList<>();
        this.tables = tables != null ? new ArrayList<>(tables) : new ArrayList<>();
        this.notes = notes != null ? new ArrayList<>(notes) : new ArrayList<>();
        this.interfaces = interfaces != null ? new ArrayList<>(interfaces) : new ArrayList<>();
        this.nodePolicies = nodePolicies != null ? Map.copyOf(nodePolicies) : Map.of();
        this.originalPlan = originalPlan != null ? Collections.unmodifiableMap(originalPlan) : Map.of();
    }


    // ===== FACTORY METHODS =====

    public static WorkflowPlan fromMap(Map<String, Object> planData) {
        return WorkflowPlanParser.parse(planData, null, null);
    }

    public static WorkflowPlan fromMap(Map<String, Object> planData, String externalTenantId) {
        return WorkflowPlanParser.parse(planData, null, externalTenantId);
    }

    /**
     * Parse plan with both id and tenantId provided externally (from dedicated DB columns).
     * The plan JSON should contain only plan data - no id or tenant_id.
     */
    public static WorkflowPlan fromMap(Map<String, Object> planData, String externalId, String externalTenantId) {
        return WorkflowPlanParser.parse(planData, externalId, externalTenantId);
    }

    // ===== BASIC GETTERS =====

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public Map<String, Object> getOriginalPlan() { return originalPlan; }
    public List<Trigger> getTriggers() { return new ArrayList<>(triggers); }
    public List<Step> getMcps() { return new ArrayList<>(mcps); }
    public List<Agent> getAgents() { return new ArrayList<>(agents); }
    public List<Edge> getEdges() { return new ArrayList<>(edges); }
    public List<Core> getCores() { return new ArrayList<>(cores); }
    public List<Step> getTables() { return new ArrayList<>(tables); }
    public List<Note> getNotes() { return new ArrayList<>(notes); }
    public List<InterfaceDef> getInterfaces() { return new ArrayList<>(interfaces); }

    /** All non-default per-node execution policies, keyed by normalized node key. */
    public Map<String, NodePolicy> getNodePolicies() { return nodePolicies; }

    /**
     * Resolves the execution policy for a node. Accepts the engine's nodeId form
     * ({@code mcp:label}, {@code core:label}, optionally with a port suffix on
     * core/agent refs) and returns {@link NodePolicy#DEFAULT} when no policy was
     * declared - the engine then takes the exact legacy code path.
     */
    public NodePolicy getNodePolicy(String nodeId) {
        if (nodeId == null || nodePolicies.isEmpty()) return NodePolicy.DEFAULT;
        NodePolicy direct = nodePolicies.get(nodeId);
        if (direct != null) return direct;
        // Strip a possible port suffix ("core:check:if" → "core:check") and normalize case.
        String baseKey = EdgeRefParser.getNodeKey(nodeId);
        if (baseKey != null) {
            NodePolicy byBase = nodePolicies.get(baseKey);
            if (byBase != null) return byBase;
        }
        String normalized = WorkflowPlanParser.normalizeStepId(nodeId);
        if (normalized != null) {
            NodePolicy byNormalized = nodePolicies.get(normalized);
            if (byNormalized != null) return byNormalized;
        }
        return NodePolicy.DEFAULT;
    }

    // ===== FIND METHODS =====

    public Optional<Trigger> findTriggerById(String triggerId) {
        if (triggerId == null || triggerId.isBlank()) return Optional.empty();
        String normalized = triggerId.trim().toLowerCase(Locale.ROOT);
        return triggers.stream().filter(t -> t.id().equals(normalized)).findFirst();
    }

    public Optional<Trigger> findTriggerByKey(String triggerKey) {
        if (triggerKey == null || triggerKey.isBlank()) return Optional.empty();
        String normalized = triggerKey.trim().toLowerCase(Locale.ROOT);
        return triggers.stream().filter(t -> t.getNormalizedKey().equals(normalized)).findFirst();
    }

    public Optional<Trigger> findTrigger(String triggerId) {
        String normalizedId = triggerId.startsWith("trigger:") ? triggerId.substring(8) : triggerId;
        return triggers.stream().filter(t -> t.id().equals(normalizedId)).findFirst();
    }

    public Optional<Step> findStep(String stepId) {
        if (stepId == null) return Optional.empty();
        String normalized = WorkflowPlanParser.normalizeStepId(stepId);
        if (normalized == null) return Optional.empty();
        return Optional.ofNullable(getMcpsByNormalizedAlias().get(normalized));
    }

    public Optional<Agent> findAgent(String agentId) {
        if (agentId == null) return Optional.empty();
        String normalized = agentId.startsWith("agent:") ? agentId : LabelNormalizer.agentKey(agentId);
        return agents.stream().filter(a -> a.getNormalizedKey().equals(normalized)).findFirst();
    }

    public Optional<InterfaceDef> findInterface(String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        String normalized = key.startsWith("interface:") ? key : LabelNormalizer.interfaceKey(key);
        return interfaces.stream().filter(i -> i.getNormalizedKey().equals(normalized)).findFirst();
    }

    public Optional<Agent> findAgentByLabel(String label) {
        if (label == null) return Optional.empty();
        String normalizedLabel = LabelNormalizer.normalizeLabel(label);
        return agents.stream().filter(a -> a.normalizedLabel().equals(normalizedLabel)).findFirst();
    }

    public Map<String, Object> getStepParams(String stepId) {
        if (stepId == null) return Map.of();
        String normalized = WorkflowPlanParser.normalizeStepId(stepId);
        if (normalized == null) return Map.of();

        Step step = getMcpsByNormalizedAlias().get(normalized);
        if (step != null && step.params() != null && !step.params().isEmpty()) {
            return new HashMap<>(step.params());
        }

        // Check edge params
        for (Edge edge : edges) {
            String toKey = EdgeRefParser.getNodeKey(edge.to());
            if (normalized.equals(toKey) && edge.params() != null && !edge.params().isEmpty()) {
                return new HashMap<>(edge.params());
            }
        }
        return Map.of();
    }

    private Map<String, Step> getMcpsByNormalizedAlias() {
        if (mcpsByNormalizedAlias == null) {
            mcpsByNormalizedAlias = new HashMap<>();
            for (Step mcp : mcps) {
                if (mcp == null) continue;
                String normalizedKey = mcp.getNormalizedKey();
                if (normalizedKey != null) mcpsByNormalizedAlias.put(normalizedKey, mcp);
            }
        }
        return mcpsByNormalizedAlias;
    }

    // ===== EXECUTION GRAPH =====

    public ExecutionGraph getExecutionGraph() {
        if (executionGraph == null) {
            executionGraph = ExecutionGraph.build(this);
        }
        return executionGraph;
    }

    /**
     * Sets a pre-computed execution graph (from cache).
     */
    public void setExecutionGraph(ExecutionGraph executionGraph) {
        this.executionGraph = executionGraph;
    }

    // ===== MERGE NODES =====

    public Map<String, MergeNode> getMergeNodes() {
        if (mergeNodes == null) {
            mergeNodes = WorkflowPlanAnalyzer.detectMergeNodes(edges);
        }
        return new HashMap<>(mergeNodes);
    }

    public synchronized MergeNode ensureMergeNode(String stepId) {
        if (stepId == null) return null;
        String normalizedId = WorkflowPlanParser.normalizeStepId(stepId);
        if (mergeNodes == null || !mergeNodes.containsKey(normalizedId)) {
            mergeNodes = WorkflowPlanAnalyzer.detectMergeNodes(edges);
        }
        return mergeNodes.get(normalizedId);
    }

    public boolean isProbableMergeNode(String stepId) {
        return WorkflowPlanAnalyzer.isProbableMergeNode(stepId, edges);
    }

    // ===== SPLIT LOOPS =====

    /**
     * Detects split loops (parallel for-each) from cores with type="split".
     * This is NOT related to while loops -- split creates N parallel branches.
     */
    public Map<String, SplitLoop> getSplitLoops() {
        return WorkflowPlanAnalyzer.detectSplitLoops(cores, edges);
    }

    // ===== LOOP ITERATE EDGE METHODS =====

    /**
     * Check if an edge is an iterate (loop-back) edge.
     * An iterate edge has to="core:label:iterate".
     */
    private static boolean isIterateEdge(Edge edge) {
        if (edge == null || edge.to() == null) return false;
        return "iterate".equals(EdgeRefParser.getPort(edge.to()));
    }

    /**
     * Get all iterate edges (loop-back edges using :iterate port).
     */
    public List<Edge> getIterateEdges() {
        return edges.stream().filter(WorkflowPlan::isIterateEdge).toList();
    }

    /**
     * Get iterate edges originating from a specific source node.
     * An iterate edge has to="core:label:iterate".
     */
    public List<Edge> getIterateEdgesForSource(String sourceNodeId) {
        if (sourceNodeId == null) return List.of();
        return edges.stream()
            .filter(WorkflowPlan::isIterateEdge)
            .filter(e -> {
                String fromKey = EdgeRefParser.getNodeKey(e.from());
                return sourceNodeId.equals(fromKey);
            })
            .toList();
    }

    /**
     * Check if the plan has any iterate (loop-back) edges.
     */
    public boolean hasIterateEdges() {
        return edges.stream().anyMatch(WorkflowPlan::isIterateEdge);
    }

    /**
     * Find the Core (loop) associated with an iterate edge.
     * The iterate edge target is "core:label:iterate", so the core key is "core:label".
     */
    public Optional<Core> findLoopCoreForIterateEdge(Edge iterateEdge) {
        if (iterateEdge == null || iterateEdge.to() == null) return Optional.empty();
        String coreKey = EdgeRefParser.getNodeKey(iterateEdge.to());
        if (coreKey == null) return Optional.empty();
        return cores.stream()
            .filter(Core::isLoop)
            .filter(c -> c.getNormalizedKey().equals(coreKey))
            .findFirst();
    }

    /**
     * Find the body entry target for a loop core.
     * Returns the target node key of the "core:label:body" edge.
     */
    public String findLoopBodyTarget(String loopCoreKey) {
        if (loopCoreKey == null) return null;
        return edges.stream()
            .filter(e -> {
                String fromKey = EdgeRefParser.getNodeKey(e.from());
                String fromPort = EdgeRefParser.getPort(e.from());
                return loopCoreKey.equals(fromKey) && "body".equals(fromPort);
            })
            .map(e -> EdgeRefParser.getNodeKey(e.to()))
            .findFirst()
            .orElse(null);
    }

    /**
     * Find the exit target for a loop core.
     * Returns the target node key of the "core:label:exit" edge.
     */
    public String findLoopExitTarget(String loopCoreKey) {
        if (loopCoreKey == null) return null;
        return edges.stream()
            .filter(e -> {
                String fromKey = EdgeRefParser.getNodeKey(e.from());
                String fromPort = EdgeRefParser.getPort(e.from());
                return loopCoreKey.equals(fromKey) && "exit".equals(fromPort);
            })
            .map(e -> EdgeRefParser.getNodeKey(e.to()))
            .findFirst()
            .orElse(null);
    }

    // ===== MULTI-TRIGGER DAG METHODS =====
    // Trigger sharing is auto-detected from edge topology - no explicit dagGroup field needed.
    // Two triggers share the same DAG if their descendants (computed via BFS) overlap.

    private transient Map<String, Set<String>> triggerSharingGroups; // lazy cache: triggerKey -> group of triggerKeys

    /**
     * Check if two triggers share the same DAG (have overlapping descendants).
     * Computed from edge topology - no explicit dagGroup field needed.
     */
    public boolean areTriggersInSameDagGroup(String triggerKey1, String triggerKey2) {
        if (triggerKey1 == null || triggerKey2 == null) return false;
        if (triggerKey1.equals(triggerKey2)) return true;
        Map<String, Set<String>> groups = getTriggerSharingGroups();
        Set<String> group1 = groups.get(triggerKey1);
        return group1 != null && group1.contains(triggerKey2);
    }

    /**
     * Get all triggers that share the same DAG as the given trigger.
     * Returns empty list if the trigger doesn't share with anyone.
     */
    public List<Trigger> getTriggersInSameDagGroup(String triggerKey) {
        Map<String, Set<String>> groups = getTriggerSharingGroups();
        Set<String> group = groups.get(triggerKey);
        if (group == null || group.size() <= 1) return List.of();
        return triggers.stream()
            .filter(t -> group.contains(t.getNormalizedKey()))
            .toList();
    }

    /**
     * Check if the plan has any multi-trigger DAG groups (triggers sharing descendants).
     */
    public boolean hasMultiTriggerDagGroups() {
        Map<String, Set<String>> groups = getTriggerSharingGroups();
        return groups.values().stream().anyMatch(g -> g.size() > 1);
    }

    /**
     * Lazy-compute trigger sharing groups from edge topology.
     * Uses BFS to find descendants per trigger, then union-find to group overlapping triggers.
     */
    private Map<String, Set<String>> getTriggerSharingGroups() {
        if (triggerSharingGroups != null) return triggerSharingGroups;

        triggerSharingGroups = new HashMap<>();
        if (triggers.size() < 2) {
            for (Trigger t : triggers) {
                triggerSharingGroups.put(t.getNormalizedKey(), Set.of(t.getNormalizedKey()));
            }
            return triggerSharingGroups;
        }

        // Build adjacency list from edges
        Map<String, Set<String>> adj = new HashMap<>();
        for (Edge edge : edges) {
            String fromKey = com.apimarketplace.orchestrator.utils.EdgeRefParser.getNodeKey(edge.from());
            String toKey = com.apimarketplace.orchestrator.utils.EdgeRefParser.getNodeKey(edge.to());
            if (fromKey != null && toKey != null) {
                adj.computeIfAbsent(fromKey, k -> new HashSet<>()).add(toKey);
            }
        }

        // BFS descendants per trigger
        Map<String, Set<String>> descendants = new HashMap<>();
        List<String> triggerKeys = new ArrayList<>();
        for (Trigger t : triggers) {
            String key = t.getNormalizedKey();
            triggerKeys.add(key);
            descendants.put(key, bfsDescendants(adj, key));
        }

        // Union-Find
        Map<String, String> parent = new HashMap<>();
        for (String key : triggerKeys) parent.put(key, key);

        for (int i = 0; i < triggerKeys.size(); i++) {
            for (int j = i + 1; j < triggerKeys.size(); j++) {
                Set<String> descA = descendants.get(triggerKeys.get(i));
                Set<String> descB = descendants.get(triggerKeys.get(j));
                for (String nodeId : descA) {
                    if (descB.contains(nodeId)) {
                        union(parent, triggerKeys.get(i), triggerKeys.get(j));
                        break;
                    }
                }
            }
        }

        // Build groups
        Map<String, Set<String>> rootToGroup = new HashMap<>();
        for (String key : triggerKeys) {
            String root = find(parent, key);
            rootToGroup.computeIfAbsent(root, k -> new HashSet<>()).add(key);
        }

        // Map each trigger to its group
        for (Set<String> group : rootToGroup.values()) {
            for (String key : group) {
                triggerSharingGroups.put(key, group);
            }
        }

        return triggerSharingGroups;
    }

    private static Set<String> bfsDescendants(Map<String, Set<String>> adj, String start) {
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(start);
        visited.add(start);
        while (!queue.isEmpty()) {
            String node = queue.poll();
            for (String next : adj.getOrDefault(node, Set.of())) {
                if (visited.add(next)) queue.add(next);
            }
        }
        visited.remove(start);
        return visited;
    }

    private static String find(Map<String, String> parent, String x) {
        while (!parent.get(x).equals(x)) {
            parent.put(x, parent.get(parent.get(x)));
            x = parent.get(x);
        }
        return x;
    }

    private static void union(Map<String, String> parent, String a, String b) {
        String ra = find(parent, a);
        String rb = find(parent, b);
        if (!ra.equals(rb)) parent.put(ra, rb);
    }

    // ===== UTILITY METHODS =====

    public boolean hasDecisions() {
        return cores.stream().anyMatch(node ->
            "decision".equals(node.type()) || "switch".equals(node.type()));
    }

    public boolean hasConditionalLogic() {
        return cores.stream().anyMatch(cn -> "decision".equals(cn.type()));
    }

    public Set<String> getAllStepIds() {
        Set<String> allIds = new LinkedHashSet<>();

        triggers.forEach(t -> { if (t != null && t.id() != null) allIds.add(t.getNormalizedKey()); });

        mcps.forEach(s -> {
            if (s == null) return;
            String key = s.getNormalizedKey();
            if (key != null) allIds.add(key);
            else if (s.label() != null && !s.label().trim().isEmpty()) {
                String fallbackKey = LabelNormalizer.mcpKey(s.label());
                if (fallbackKey != null) allIds.add(fallbackKey);
            }
        });

        agents.forEach(a -> {
            if (a == null) return;
            String key = a.getNormalizedKey();
            if (key != null) allIds.add(key);
            else if (a.label() != null && !a.label().trim().isEmpty()) {
                String fallbackKey = LabelNormalizer.agentKey(a.label());
                if (fallbackKey != null) allIds.add(fallbackKey);
            }
        });

        cores.forEach(cn -> { if (cn != null) allIds.add(cn.getNormalizedKey()); });

        tables.forEach(t -> {
            if (t == null) return;
            String tableNormalizedKey = LabelNormalizer.tableKey(t.label());
            if (tableNormalizedKey != null) allIds.add(tableNormalizedKey);
        });

        return allIds;
    }

    @Override
    public String toString() {
        return String.format("WorkflowPlan{id='%s', tenantId='%s', mcps=%d, edges=%d, triggers=%d}",
            id, tenantId, mcps.size(), edges.size(), triggers.size());
    }
}
