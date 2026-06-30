package com.apimarketplace.orchestrator.services.state;

import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Builds an immutable WorkflowGraph from a WorkflowPlan.
 * <p>
 * This builder analyzes the workflow plan structure including:
 * <ul>
 *   <li>Triggers, Steps, and Agents</li>
 *   <li>Control nodes (decisions and loops)</li>
 *   <li>Edges and their conditional/loop logic</li>
 *   <li>Merge nodes (convergence points)</li>
 * </ul>
 * </p>
 */
public final class WorkflowGraphBuilder {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowGraphBuilder.class);

    private final WorkflowPlan plan;
    private final Map<NodeId, WorkflowNode.Builder> nodeBuilders = new LinkedHashMap<>();
    private final List<WorkflowGraph.Edge> graphEdges = new ArrayList<>();
    private final Map<String, NodeId> normalizedKeyToNodeId = new HashMap<>();

    private WorkflowGraphBuilder(WorkflowPlan plan) {
        this.plan = Objects.requireNonNull(plan, "WorkflowPlan cannot be null");
    }

    /**
     * Builds a WorkflowGraph from a WorkflowPlan.
     *
     * @param plan The workflow plan to build from
     * @return The constructed WorkflowGraph
     */
    public static WorkflowGraph build(WorkflowPlan plan) {
        return new WorkflowGraphBuilder(plan).buildGraph();
    }

    private WorkflowGraph buildGraph() {
        logger.debug("[WorkflowGraphBuilder] Building graph for plan: {}", plan.getId());

        // Step 1: Create nodes for all triggers, steps, agents, and control nodes
        createTriggerNodes();
        createStepNodes();
        createAgentNodes();
        createCores();

        // Step 2: Process edges to establish connections
        processEdges();

        // Step 3: Detect and configure merge nodes
        detectAndConfigureMergeNodes();

        // Step 4: Build final nodes and graph
        return buildFinalGraph();
    }

    // ========================================================================
    // NODE CREATION METHODS
    // ========================================================================

    private void createTriggerNodes() {
        for (Trigger trigger : plan.getTriggers()) {
            String normalizedKey = trigger.getNormalizedKey();
            if (normalizedKey == null) {
                logger.warn("[WorkflowGraphBuilder] Trigger with null normalized key, skipping: {}", trigger);
                continue;
            }

            NodeId nodeId = NodeId.parse(normalizedKey);
            WorkflowNode.Builder builder = WorkflowNode.builder(nodeId, WorkflowNode.NodeType.TRIGGER);

            nodeBuilders.put(nodeId, builder);
            normalizedKeyToNodeId.put(normalizedKey, nodeId);

            logger.debug("[WorkflowGraphBuilder] Created trigger node: {}", nodeId);
        }
    }

    private void createStepNodes() {
        for (Step step : plan.getMcps()) {
            String normalizedKey = step.getNormalizedKey();
            if (normalizedKey == null) {
                logger.warn("[WorkflowGraphBuilder] Step with null normalized key, skipping: {}", step);
                continue;
            }

            NodeId nodeId = NodeId.parse(normalizedKey);
            WorkflowNode.Builder builder = WorkflowNode.builder(nodeId, WorkflowNode.NodeType.MCP);

            nodeBuilders.put(nodeId, builder);
            normalizedKeyToNodeId.put(normalizedKey, nodeId);

            logger.debug("[WorkflowGraphBuilder] Created step node: {}", nodeId);
        }
    }

    private void createAgentNodes() {
        for (Agent agent : plan.getAgents()) {
            String normalizedKey = agent.getNormalizedKey();
            if (normalizedKey == null) {
                logger.warn("[WorkflowGraphBuilder] Agent with null normalized key, skipping: {}", agent);
                continue;
            }

            NodeId nodeId = NodeId.parse(normalizedKey);
            WorkflowNode.Builder builder = WorkflowNode.builder(nodeId, WorkflowNode.NodeType.AGENT);

            nodeBuilders.put(nodeId, builder);
            normalizedKeyToNodeId.put(normalizedKey, nodeId);

            logger.debug("[WorkflowGraphBuilder] Created agent node: {}", nodeId);
        }
    }

    private void createCores() {
        for (Core core : plan.getCores()) {
            String label = core.label();
            if (label == null || label.isBlank()) {
                label = core.id();
            }

            NodeId nodeId;
            WorkflowNode.NodeType nodeType;

            if ("decision".equals(core.type()) || "switch".equals(core.type()) || "option".equals(core.type())) {
                // Decision-like nodes: select ONE branch (exclusive)
                // - decision: if/elseif/else branching
                // - switch: case/default branching
                // - option: N choices with expressions (first-true-wins)
                String decisionKey = LabelNormalizer.coreKey(label);
                if (decisionKey == null) {
                    logger.warn("[WorkflowGraphBuilder] Decision/Switch/Option with null key, skipping: {}", core);
                    continue;
                }
                nodeId = NodeId.parse(decisionKey);
                nodeType = WorkflowNode.NodeType.DECISION;
            } else if ("loop".equals(core.type())) {
                String loopKey = LabelNormalizer.coreKey(label);
                if (loopKey == null) {
                    logger.warn("[WorkflowGraphBuilder] Loop with null key, skipping: {}", core);
                    continue;
                }
                nodeId = NodeId.parse(loopKey);
                nodeType = WorkflowNode.NodeType.LOOP;
            } else if ("split".equals(core.type())) {
                // Split nodes use NodeId.split() factory method
                String normalizedLabel = LabelNormalizer.normalizeLabel(label);
                if (normalizedLabel == null || normalizedLabel.isBlank()) {
                    logger.warn("[WorkflowGraphBuilder] Split with null/blank label, skipping: {}", core);
                    continue;
                }
                nodeId = NodeId.split(normalizedLabel);
                nodeType = WorkflowNode.NodeType.LOOP;
            } else if ("fork".equals(core.type())) {
                // Fork nodes activate ALL branches (parallel)
                String forkKey = LabelNormalizer.coreKey(label);
                if (forkKey == null) {
                    logger.warn("[WorkflowGraphBuilder] Fork with null key, skipping: {}", core);
                    continue;
                }
                nodeId = NodeId.parse(forkKey);
                nodeType = WorkflowNode.NodeType.FORK;
            } else if ("merge".equals(core.type())) {
                // Merge nodes wait for ALL predecessors
                String mergeKey = LabelNormalizer.coreKey(label);
                if (mergeKey == null) {
                    logger.warn("[WorkflowGraphBuilder] Merge with null key, skipping: {}", core);
                    continue;
                }
                nodeId = NodeId.parse(mergeKey);
                nodeType = WorkflowNode.NodeType.MERGE;
            } else if ("aggregate".equals(core.type())) {
                // Aggregate nodes collect N items into 1
                String aggregateKey = LabelNormalizer.coreKey(label);
                if (aggregateKey == null) {
                    logger.warn("[WorkflowGraphBuilder] Aggregate with null key, skipping: {}", core);
                    continue;
                }
                nodeId = NodeId.parse(aggregateKey);
                nodeType = WorkflowNode.NodeType.AGGREGATE;
            } else {
                // All other core types behave like MCP steps for graph purposes
                // (simple passthrough or terminal nodes: transform, wait, exit, stop_on_error,
                //  http_request, code, sub_workflow, send_email, ssh, sftp, database, etc.)
                String coreKey = LabelNormalizer.coreKey(label);
                if (coreKey == null) {
                    logger.warn("[WorkflowGraphBuilder] Passthrough core node with null key, skipping: {}", core);
                    continue;
                }
                nodeId = NodeId.parse(coreKey);
                nodeType = WorkflowNode.NodeType.MCP;
            }

            WorkflowNode.Builder builder = WorkflowNode.builder(nodeId, nodeType);
            nodeBuilders.put(nodeId, builder);
            normalizedKeyToNodeId.put(nodeId.toKey(), nodeId);

            logger.debug("[WorkflowGraphBuilder] Created control node: {} (type={})", nodeId, nodeType);
        }
    }

    // ========================================================================
    // EDGE PROCESSING (V2 Format)
    // ========================================================================

    private void processEdges() {
        // V2: All edges are simple from/to with optional ports
        for (Edge edge : plan.getEdges()) {
            processV2Edge(edge);
        }
    }

    /**
     * V2: Process a simple edge using EdgeRefParser.
     * Edge format: { from: "nodeType:label:port", to: "nodeType:label" }
     */
    private void processV2Edge(Edge edge) {
        if (edge.from() == null || edge.to() == null) {
            return;
        }

        // Parse the from and to references
        com.apimarketplace.orchestrator.utils.EdgeRefParser.EdgeRef fromRef =
            com.apimarketplace.orchestrator.utils.EdgeRefParser.parse(edge.from());
        com.apimarketplace.orchestrator.utils.EdgeRefParser.EdgeRef toRef =
            com.apimarketplace.orchestrator.utils.EdgeRefParser.parse(edge.to());

        if (fromRef == null || toRef == null) {
            logger.warn("[WorkflowGraphBuilder] Could not parse V2 edge: {} -> {}", edge.from(), edge.to());
            return;
        }

        NodeId fromId = resolveNodeId(fromRef.getNodeKey());
        NodeId toId = resolveNodeId(toRef.getNodeKey());

        if (fromId == null || toId == null) {
            logger.warn("[WorkflowGraphBuilder] Could not resolve V2 edge: {} -> {}", edge.from(), edge.to());
            return;
        }

        // Handle decision/switch edges with ports (if, else, elseif_N, case_N, default)
        // Store the port -> target mapping for branch selection
        // Note: nodeType from EdgeRefParser is "core", not "decision"/"switch"
        // Use getPortType() to determine if this is a decision/switch port
        String portType = com.apimarketplace.orchestrator.utils.EdgeRefParser.getPortType(fromRef.port());
        if (("decision".equals(portType) || "switch".equals(portType)) && fromRef.hasPort()) {
            WorkflowNode.Builder fromBuilder = nodeBuilders.get(fromId);
            if (fromBuilder != null) {
                fromBuilder.addPortSuccessor(fromRef.port(), toId);
                logger.debug("[WorkflowGraphBuilder] Added decision port mapping: {}:{} -> {}",
                    fromId, fromRef.port(), toId);
            }
        }

        // Handle classify edges with ports (category_0, category_1, etc.)
        // Classify nodes behave like Decision nodes - they select ONE branch based on AI classification
        if ("classify".equals(portType) && fromRef.hasPort()) {
            WorkflowNode.Builder fromBuilder = nodeBuilders.get(fromId);
            if (fromBuilder != null) {
                fromBuilder.addPortSuccessor(fromRef.port(), toId);
                logger.debug("[WorkflowGraphBuilder] Added classify port mapping: {}:{} -> {}",
                    fromId, fromRef.port(), toId);
            }
        }

        // Handle loop edges with ports (body, exit, iterate)
        if ("loop".equals(portType) && fromRef.hasPort()) {
            WorkflowNode.Builder fromBuilder = nodeBuilders.get(fromId);
            if (fromBuilder != null) {
                fromBuilder.addPortSuccessor(fromRef.port(), toId);
                logger.debug("[WorkflowGraphBuilder] Added loop port mapping: {}:{} -> {}",
                    fromId, fromRef.port(), toId);
            }

        }

        addEdge(fromId, toId);

        logger.debug("[WorkflowGraphBuilder] Added V2 edge: {} -> {} (port: {})", fromId, toId, fromRef.port());
    }

    // ========================================================================
    // MERGE NODE DETECTION
    // ========================================================================

    private void detectAndConfigureMergeNodes() {
        // Count incoming edges for each node
        Map<NodeId, Integer> incomingCount = new HashMap<>();
        for (WorkflowGraph.Edge edge : graphEdges) {
            incomingCount.merge(edge.to(), 1, Integer::sum);
        }

        // Configure merge strategy for nodes with multiple predecessors
        for (Map.Entry<NodeId, Integer> entry : incomingCount.entrySet()) {
            if (entry.getValue() > 1) {
                NodeId nodeId = entry.getKey();
                WorkflowNode.Builder builder = nodeBuilders.get(nodeId);

                if (builder != null) {
                    // Determine merge strategy
                    WorkflowNode.MergeStrategy strategy = determineMergeStrategy(nodeId);
                    builder.mergeStrategy(strategy);

                    logger.debug("[WorkflowGraphBuilder] Configured merge node: {} with strategy: {}",
                            nodeId, strategy);
                }
            }
        }
    }

    private WorkflowNode.MergeStrategy determineMergeStrategy(NodeId nodeId) {
        // Always use ALL strategy - merge nodes wait for ALL predecessors to be resolved
        // This ensures consistent behavior between auto mode and step-by-step mode
        return WorkflowNode.MergeStrategy.ALL;
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private NodeId resolveNodeId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        // First, check if we already have a mapping
        NodeId existing = normalizedKeyToNodeId.get(raw);
        if (existing != null) {
            return existing;
        }

        // Try to parse and find the node
        try {
            NodeId parsed = NodeId.parse(raw);
            if (nodeBuilders.containsKey(parsed)) {
                return parsed;
            }

            // Try with different prefixes (V2 valid prefixes)
            String[] prefixes = {"mcp:", "trigger:", "agent:", "core:", "table:", "note:", "interface:"};
            for (String prefix : prefixes) {
                String keyWithPrefix = prefix + parsed.label();
                existing = normalizedKeyToNodeId.get(keyWithPrefix);
                if (existing != null) {
                    return existing;
                }

                NodeId withPrefix = NodeId.parse(keyWithPrefix);
                if (nodeBuilders.containsKey(withPrefix)) {
                    return withPrefix;
                }
            }

            // Create the node if it doesn't exist (for implicit nodes)
            return parsed;
        } catch (IllegalArgumentException e) {
            logger.warn("[WorkflowGraphBuilder] Could not parse node ID: {}", raw);
            return null;
        }
    }

    private void ensureNodeExists(NodeId nodeId, WorkflowNode.NodeType type) {
        if (!nodeBuilders.containsKey(nodeId)) {
            WorkflowNode.Builder builder = WorkflowNode.builder(nodeId, type);
            nodeBuilders.put(nodeId, builder);
            normalizedKeyToNodeId.put(nodeId.toKey(), nodeId);

            logger.debug("[WorkflowGraphBuilder] Created implicit node: {} (type={})", nodeId, type);
        }
    }

    private void addEdge(NodeId from, NodeId to) {
        if (from == null || to == null) {
            return;
        }

        // Ensure both nodes exist
        ensureNodeExists(from, guessNodeType(from));
        ensureNodeExists(to, guessNodeType(to));

        // Add edge to list
        WorkflowGraph.Edge edge = new WorkflowGraph.Edge(from, to);

        // Avoid duplicate edges
        if (!graphEdges.contains(edge)) {
            graphEdges.add(edge);

            // Update predecessors and successors
            WorkflowNode.Builder fromBuilder = nodeBuilders.get(from);
            WorkflowNode.Builder toBuilder = nodeBuilders.get(to);

            if (fromBuilder != null) {
                fromBuilder.addSuccessor(to);
            }
            if (toBuilder != null) {
                toBuilder.addPredecessor(from);
            }

            logger.debug("[WorkflowGraphBuilder] Added edge: {} -> {}", from, to);
        }
    }

    private WorkflowNode.NodeType guessNodeType(NodeId nodeId) {
        if (nodeId.isTrigger()) {
            return WorkflowNode.NodeType.TRIGGER;
        } else if (nodeId.isLoop()) {
            return WorkflowNode.NodeType.LOOP;
        } else if (nodeId.isDecision()) {
            return WorkflowNode.NodeType.DECISION;
        } else if (nodeId.isAgent()) {
            return WorkflowNode.NodeType.AGENT;
        } else {
            return WorkflowNode.NodeType.MCP;
        }
    }

    private WorkflowGraph buildFinalGraph() {
        // Build all nodes
        Map<NodeId, WorkflowNode> nodes = new LinkedHashMap<>();
        for (Map.Entry<NodeId, WorkflowNode.Builder> entry : nodeBuilders.entrySet()) {
            nodes.put(entry.getKey(), entry.getValue().build());
        }

        // Find ALL trigger nodes (multi-workflow support)
        List<NodeId> triggerIds = new ArrayList<>();
        for (WorkflowNode node : nodes.values()) {
            if (node.isTrigger()) {
                triggerIds.add(node.id());
            }
        }

        if (triggerIds.isEmpty()) {
            throw new IllegalStateException("No trigger node found in workflow plan");
        }

        if (triggerIds.size() > 1) {
            logger.info("[WorkflowGraphBuilder] Multi-workflow mode: {} independent triggers detected: {}",
                    triggerIds.size(), triggerIds);
        }

        logger.info("[WorkflowGraphBuilder] Built graph with {} nodes and {} edges, triggers: {}",
                nodes.size(), graphEdges.size(), triggerIds);

        return new WorkflowGraph(nodes, triggerIds, graphEdges);
    }
}
