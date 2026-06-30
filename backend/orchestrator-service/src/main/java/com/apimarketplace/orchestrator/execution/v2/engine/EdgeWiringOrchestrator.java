package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.execution.v2.nodes.*;
import com.apimarketplace.orchestrator.utils.EdgeRefParser;
import com.apimarketplace.orchestrator.utils.EdgeRefParser.EdgeRef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Orchestrates the two-pass edge wiring algorithm.
 *
 * V2 edge format is simple: { from, to, input }
 * - from can include ports: "core:check:if", "core:process:body"
 * - Conditions are stored in Cores, not in edges
 *
 * Two-pass approach:
 * 1. Collect port edges and create nodes (PASS 1)
 * 2. Wire targets after all nodes exist (PASS 2)
 */
@Component
public class EdgeWiringOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(EdgeWiringOrchestrator.class);

    private final CoreNodeBuilder coreNodeBuilder;
    private final DecisionNodeWirer decisionNodeWirer;
    private final SwitchNodeWirer switchNodeWirer;
    private final ForkNodeWirer forkNodeWirer;
    private final OptionNodeWirer optionNodeWirer;
    private final ApprovalNodeWirer approvalNodeWirer;
    private final LoopNodeWirer loopNodeWirer;

    public EdgeWiringOrchestrator(
            CoreNodeBuilder coreNodeBuilder,
            DecisionNodeWirer decisionNodeWirer,
            SwitchNodeWirer switchNodeWirer,
            ForkNodeWirer forkNodeWirer,
            OptionNodeWirer optionNodeWirer,
            ApprovalNodeWirer approvalNodeWirer,
            LoopNodeWirer loopNodeWirer) {
        this.coreNodeBuilder = coreNodeBuilder;
        this.decisionNodeWirer = decisionNodeWirer;
        this.switchNodeWirer = switchNodeWirer;
        this.forkNodeWirer = forkNodeWirer;
        this.optionNodeWirer = optionNodeWirer;
        this.approvalNodeWirer = approvalNodeWirer;
        this.loopNodeWirer = loopNodeWirer;
    }

    /**
     * Wire successors from edges using the two-pass algorithm.
     *
     * @param nodeMap The map of all nodes
     * @param plan The workflow plan containing edges
     */
    public void wireSuccessorsFromEdges(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        logger.info("🔗 V2: Wiring successors from edges: edgeCount={}", plan.getEdges().size());

        // Debug: log all edges
        for (int i = 0; i < plan.getEdges().size(); i++) {
            Edge e = plan.getEdges().get(i);
            logger.info("📝 Edge[{}]: from={}, to={}", i, e.from(), e.to());
        }

        // Collect port edges from all edges
        EdgeCollector collector = collectPortEdges(plan);

        // PASS 1: Create ALL core nodes first (without targets)
        coreNodeBuilder.createCoreNodes(nodeMap, plan, collector.mergeSourceNodes);

        // PASS 2: Wire decision branch targets (now ALL nodes exist)
        decisionNodeWirer.wireDecisionBranchTargets(nodeMap, plan, collector.decisionPortTargets);

        // PASS 2a: Wire switch case targets (now ALL nodes exist)
        switchNodeWirer.wireSwitchCaseTargets(nodeMap, plan, collector.switchPortTargets);

        // PASS 2b: Wire fork branch targets (now ALL nodes exist)
        forkNodeWirer.wireForkBranchTargets(nodeMap, plan, collector.forkBranchTargets);

        // PASS 2c: Wire option choice targets (now ALL nodes exist)
        optionNodeWirer.wireOptionChoiceTargets(nodeMap, plan, collector.optionPortTargets);

        // PASS 2d: Wire classify category targets and guardrail port targets
        logger.debug("🏷️ V2: Wiring classify/guardrail targets: classifyCount={}, guardrailCount={}",
            collector.classifyPortTargets.size(), collector.guardrailPortTargets.size());
        wireAgentBranchTargets(nodeMap, collector.classifyPortTargets, collector.guardrailPortTargets);

        // PASS 2e: Wire approval port targets (now ALL nodes exist)
        approvalNodeWirer.wireApprovalPortTargets(nodeMap, plan, collector.approvalPortTargets);

        // PASS 2f: Wire loop body/exit targets (now ALL nodes exist)
        loopNodeWirer.wireLoopPortTargets(nodeMap, plan, collector.loopPortTargets);

        // Wire all remaining edges (skip decision/fork/option/classify/guardrail/approval port edges since they're handled above)
        for (Edge edge : plan.getEdges()) {
            wireV2Edge(edge, nodeMap, collector.decisionPortTargets, collector.optionPortTargets,
                collector.classifyPortTargets, collector.guardrailPortTargets, collector.approvalPortTargets);
        }

        logger.info("✅ V2: Successors wired from edges");
    }

    /**
     * Collects port edges from all edges in the plan.
     * This includes decision ports, loop ports, fork ports, and merge sources.
     */
    public EdgeCollector collectPortEdges(WorkflowPlan plan) {
        EdgeCollector collector = new EdgeCollector();

        for (Edge edge : plan.getEdges()) {
            if (edge.from() == null) continue;

            // Skip iterate-port edges - they are loop-back connections handled by BackEdgeHandler
            EdgeRef toRefCheck = edge.to() != null ? EdgeRefParser.parse(edge.to()) : null;
            if (toRefCheck != null && toRefCheck.hasPort() && "iterate".equals(toRefCheck.port())) {
                continue;
            }

            EdgeRef fromRef = EdgeRefParser.parse(edge.from());
            if (fromRef == null) continue;

            String toKey = edge.to();
            EdgeRef toRef = EdgeRefParser.parse(edge.to());
            if (toRef != null) {
                toKey = toRef.getNodeKey();
            }

            // Determine port type using EdgeRefParser.getPortType()
            String portType = EdgeRefParser.getPortType(fromRef.port());

            // Decision port edges (if, else, elseif_N)
            if ("decision".equals(portType) && fromRef.hasPort()) {
                String decisionKey = fromRef.getNodeKey();
                String port = fromRef.port();
                collector.decisionPortTargets.computeIfAbsent(decisionKey, k -> new HashMap<>()).put(port, toKey);
                logger.info("🔀 Decision port edge: {}:{} -> {}", decisionKey, port, toKey);
            }

            // Switch port edges (case_N, default)
            if ("switch".equals(portType) && fromRef.hasPort()) {
                String switchKey = fromRef.getNodeKey();
                String port = fromRef.port();
                collector.switchPortTargets.computeIfAbsent(switchKey, k -> new HashMap<>()).put(port, toKey);
                logger.info("🔀 Switch port edge: {}:{} -> {}", switchKey, port, toKey);
            }

            // Fork port edges (branch_0, branch_1, ...)
            if ("fork".equals(portType) && fromRef.hasPort()) {
                String forkKey = fromRef.getNodeKey();
                String port = fromRef.port();
                collector.forkBranchTargets.computeIfAbsent(forkKey, k -> new HashMap<>()).put(port, toKey);
                logger.info("🔱 Fork branch edge: {}:{} -> {}", forkKey, port, toKey);
            }

            // Option port edges (choice_0, choice_1, ...)
            if ("option".equals(portType) && fromRef.hasPort()) {
                String optionKey = fromRef.getNodeKey();
                String port = fromRef.port();
                collector.optionPortTargets.computeIfAbsent(optionKey, k -> new HashMap<>()).put(port, toKey);
                logger.info("🔘 Option choice edge: {}:{} -> {}", optionKey, port, toKey);
            }

            // Classify port edges (category_0, category_1, ...)
            if ("classify".equals(portType) && fromRef.hasPort()) {
                String classifyKey = fromRef.getNodeKey();
                String port = fromRef.port();
                collector.classifyPortTargets.computeIfAbsent(classifyKey, k -> new HashMap<>()).put(port, toKey);
                logger.info("🏷️ Classify category edge: {}:{} -> {}", classifyKey, port, toKey);
            }

            // Guardrail port edges (pass, fail)
            if ("guardrail".equals(portType) && fromRef.hasPort()) {
                String guardrailKey = fromRef.getNodeKey();
                String port = fromRef.port();
                collector.guardrailPortTargets.computeIfAbsent(guardrailKey, k -> new HashMap<>()).put(port, toKey);
                logger.info("🛡️ Guardrail port edge: {}:{} -> {}", guardrailKey, port, toKey);
            }

            // Approval port edges (approved, rejected, timeout)
            if ("approval".equals(portType) && fromRef.hasPort()) {
                String approvalKey = fromRef.getNodeKey();
                String port = fromRef.port();
                collector.approvalPortTargets.computeIfAbsent(approvalKey, k -> new HashMap<>()).put(port, toKey);
                logger.info("Approval port edge: {}:{} -> {}", approvalKey, port, toKey);
            }

            // Loop port edges (body, exit)
            if ("loop".equals(portType) && fromRef.hasPort()) {
                String loopKey = fromRef.getNodeKey();
                String port = fromRef.port();
                collector.loopPortTargets.computeIfAbsent(loopKey, k -> new HashMap<>()).put(port, toKey);
                logger.info("Loop port edge: {}:{} -> {}", loopKey, port, toKey);
            }

            // Collect merge source edges (edges pointing TO merge nodes)
            String fromKey = fromRef.getNodeKey();
            collector.mergeSourceNodes.computeIfAbsent(toKey, k -> new ArrayList<>()).add(fromKey);
        }

        return collector;
    }

    /**
     * Wire classify category and guardrail branch targets on AgentNode.
     * Also sets predecessors on target nodes for proper BFS traversal in SplitContextManager.
     */
    private void wireAgentBranchTargets(
            Map<String, ExecutionNode> nodeMap,
            Map<String, Map<String, String>> classifyPortTargets,
            Map<String, Map<String, String>> guardrailPortTargets) {

        // Wire classify category targets using polymorphic addCategoryTarget()
        for (Map.Entry<String, Map<String, String>> entry : classifyPortTargets.entrySet()) {
            String classifyKey = entry.getKey();
            ExecutionNode node = nodeMap.get(classifyKey);
            if (node == null) continue;

            Map<String, String> portTargets = entry.getValue();
            for (Map.Entry<String, String> portEntry : portTargets.entrySet()) {
                String port = portEntry.getKey();
                String targetKey = portEntry.getValue();
                ExecutionNode targetNode = nodeMap.get(targetKey);

                if (targetNode != null) {
                    // Use polymorphic interface method (no instanceof needed)
                    node.addCategoryTarget(port, targetNode);

                    // Also set predecessor on target node for BFS traversal
                    // Use the full edge ref with port (e.g., "agent:classifyemail:category_0")
                    String predecessorRef = classifyKey + ":" + port;
                    targetNode.addPredecessor(predecessorRef);
                    logger.debug("🏷️ Wired classify category: {} -> {} (port: {}, predecessor: {})",
                        classifyKey, targetKey, port, predecessorRef);
                } else {
                    logger.warn("⚠️ Classify category target not found in nodeMap: {} (port: {} of {}). " +
                        "The target node was not created by ExecutionNodeFactory.", targetKey, port, classifyKey);
                }
            }
        }

        // Wire guardrail pass/fail targets using polymorphic addGuardrailTarget()
        for (Map.Entry<String, Map<String, String>> entry : guardrailPortTargets.entrySet()) {
            String guardrailKey = entry.getKey();
            ExecutionNode node = nodeMap.get(guardrailKey);
            if (node == null) continue;

            Map<String, String> portTargets = entry.getValue();
            for (Map.Entry<String, String> portEntry : portTargets.entrySet()) {
                String port = portEntry.getKey();
                String targetKey = portEntry.getValue();
                ExecutionNode targetNode = nodeMap.get(targetKey);

                if (targetNode != null) {
                    // Use polymorphic interface method (no instanceof needed)
                    node.addGuardrailTarget(port, targetNode);

                    // Also set predecessor on target node for BFS traversal
                    // Use the full edge ref with port (e.g., "agent:guardrail:pass")
                    String predecessorRef = guardrailKey + ":" + port;
                    targetNode.addPredecessor(predecessorRef);
                    logger.debug("🛡️ Wired guardrail port: {} -> {} (port: {}, predecessor: {})",
                        guardrailKey, targetKey, port, predecessorRef);
                } else {
                    logger.warn("⚠️ Guardrail port target not found in nodeMap: {} (port: {} of {}). " +
                        "The target node was not created by ExecutionNodeFactory.", targetKey, port, guardrailKey);
                }
            }
        }
    }

    /**
     * Wires a single V2 edge.
     * Skips decision port edges, fork:branch edges, option:choice edges,
     * classify:category edges, and guardrail:pass/fail edges since they're handled during node wiring.
     */
    private void wireV2Edge(
            Edge edge,
            Map<String, ExecutionNode> nodeMap,
            Map<String, Map<String, String>> decisionPortTargets,
            Map<String, Map<String, String>> optionPortTargets,
            Map<String, Map<String, String>> classifyPortTargets,
            Map<String, Map<String, String>> guardrailPortTargets,
            Map<String, Map<String, String>> approvalPortTargets) {
        if (edge.from() == null || edge.to() == null) {
            return;
        }

        // Parse from and to references
        EdgeRef fromRef = EdgeRefParser.parse(edge.from());
        EdgeRef toRef = EdgeRefParser.parse(edge.to());

        if (fromRef == null || toRef == null) {
            logger.warn("⚠️ V2: Could not parse edge: {} -> {}", edge.from(), edge.to());
            return;
        }

        // Skip iterate-port edges (loop-back connections handled by BackEdgeHandler)
        if (toRef.hasPort() && "iterate".equals(toRef.port())) {
            logger.debug("V2: Skipping iterate edge (loop-back): {} -> {}", edge.from(), edge.to());
            return;
        }

        // Determine port type
        String portType = EdgeRefParser.getPortType(fromRef.port());

        // Skip decision port edges - handled during decision node wiring (branch targets)
        if ("decision".equals(portType) && fromRef.hasPort()) {
            logger.debug("V2: Skipping decision port edge (handled during decision wiring): {} -> {}",
                edge.from(), edge.to());
            return;
        }

        // Skip switch port edges - handled during switch node wiring (case targets)
        if ("switch".equals(portType) && fromRef.hasPort()) {
            logger.debug("V2: Skipping switch port edge (handled during switch wiring): {} -> {}",
                edge.from(), edge.to());
            return;
        }

        // Skip fork:branch edges - handled during fork node creation (branches)
        if ("fork".equals(portType) && fromRef.hasPort()) {
            logger.debug("V2: Skipping fork:branch edge (handled during fork creation): {} -> {}",
                edge.from(), edge.to());
            return;
        }

        // Skip option:choice edges - handled during option node creation (choices)
        if ("option".equals(portType) && fromRef.hasPort()) {
            logger.debug("V2: Skipping option:choice edge (handled during option creation): {} -> {}",
                edge.from(), edge.to());
            return;
        }

        // Skip classify:category edges - handled during agent wiring
        if ("classify".equals(portType) && fromRef.hasPort()) {
            logger.debug("V2: Skipping classify:category edge (handled during agent wiring): {} -> {}",
                edge.from(), edge.to());
            return;
        }

        // Skip guardrail:pass/fail edges - handled during agent wiring
        if ("guardrail".equals(portType) && fromRef.hasPort()) {
            logger.debug("V2: Skipping guardrail:pass/fail edge (handled during agent wiring): {} -> {}",
                edge.from(), edge.to());
            return;
        }

        // Skip approval:approved/rejected/timeout edges - handled during approval node wiring
        if ("approval".equals(portType) && fromRef.hasPort()) {
            logger.debug("V2: Skipping approval port edge (handled during approval wiring): {} -> {}",
                edge.from(), edge.to());
            return;
        }

        // Skip loop:body/exit edges - handled during loop node wiring
        if ("loop".equals(portType) && fromRef.hasPort()) {
            logger.debug("V2: Skipping loop port edge (handled during loop wiring): {} -> {}",
                edge.from(), edge.to());
            return;
        }

        String fromKey = fromRef.getNodeKey();
        String toKey = toRef.getNodeKey();

        ExecutionNode fromNode = nodeMap.get(fromKey);
        ExecutionNode toNode = nodeMap.get(toKey);

        if (fromNode == null) {
            logger.warn("⚠️ V2: Source node not found: {} (fromRef: {})", fromKey, edge.from());
            return;
        }

        if (toNode == null) {
            logger.warn("⚠️ V2: Target node not found: {} (toRef: {})", toKey, edge.to());
            return;
        }

        // Wire the connection as a successor
        fromNode.addSuccessor(toNode);
        logger.debug("V2: Wired edge: {} -> {} (port: {})", fromKey, toKey, fromRef.port());

        // Also add predecessor for implicit merge detection
        // addPredecessor and isImplicitMerge are now in ExecutionNode interface
        toNode.addPredecessor(fromKey);
        if (toNode.isImplicitMerge()) {
            logger.info("🔀 V2: Implicit merge detected: {} has {} predecessors",
                toKey, toNode.getPredecessorIds().size());
        }
    }

    /**
     * Container for collected port edge data.
     */
    public static class EdgeCollector {
        // Map<decisionKey, Map<port, targetKey>>
        public final Map<String, Map<String, String>> decisionPortTargets = new HashMap<>();
        // Map<switchKey, Map<port (case_0, case_1, default), targetKey>>
        public final Map<String, Map<String, String>> switchPortTargets = new HashMap<>();
        // Map<forkKey, Map<port (branch_0, branch_1), targetKey>>
        public final Map<String, Map<String, String>> forkBranchTargets = new HashMap<>();
        // Map<optionKey, Map<port (choice_0, choice_1), targetKey>>
        public final Map<String, Map<String, String>> optionPortTargets = new HashMap<>();
        // Map<mergeKey, List<sourceNodeKey>>
        public final Map<String, List<String>> mergeSourceNodes = new HashMap<>();
        // Map<classifyKey, Map<port (category_0, category_1), targetKey>>
        public final Map<String, Map<String, String>> classifyPortTargets = new HashMap<>();
        // Map<guardrailKey, Map<port (pass, fail), targetKey>>
        public final Map<String, Map<String, String>> guardrailPortTargets = new HashMap<>();
        // Map<approvalKey, Map<port (approved, rejected, timeout), targetKey>>
        public final Map<String, Map<String, String>> approvalPortTargets = new HashMap<>();
        // Map<loopKey, Map<port (body, exit), targetKey>>
        public final Map<String, Map<String, String>> loopPortTargets = new HashMap<>();
    }
}
