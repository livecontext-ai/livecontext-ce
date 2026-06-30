package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Wires decision branch targets AFTER all nodes have been created (Pass 2).
 * This allows decision branches to reference loop nodes and other decision nodes.
 */
@Component
public class DecisionNodeWirer {

    private static final Logger logger = LoggerFactory.getLogger(DecisionNodeWirer.class);

    /**
     * Wires decision branch targets to their target nodes.
     *
     * @param nodeMap The map of all nodes
     * @param plan The workflow plan containing core definitions
     * @param decisionPortTargets Map of decision keys to port->target mappings
     */
    public void wireDecisionBranchTargets(
            Map<String, ExecutionNode> nodeMap,
            WorkflowPlan plan,
            Map<String, Map<String, String>> decisionPortTargets) {
        if (plan.getCores() == null) {
            return;
        }

        for (Core coreNode : plan.getCores()) {
            if (!"decision".equals(coreNode.type())) {
                continue;
            }

            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String decisionKey = "core:" + normalizedLabel;

            ExecutionNode node = nodeMap.get(decisionKey);
            if (node == null || !node.isDecisionNode()) {
                continue;
            }

            // Get port targets for this decision
            Map<String, String> portTargets = decisionPortTargets.getOrDefault(decisionKey, Map.of());

            // Wire targets to branches using polymorphic addBranchTarget()
            if (coreNode.decisionConditions() != null) {
                int branchIndex = 0;
                int elseifIndex = 0;
                boolean isFirst = true;

                for (Core.DecisionCondition dc : coreNode.decisionConditions()) {
                    // Determine port name
                    String port;
                    if ("else".equals(dc.type())) {
                        port = "else";
                    } else if (isFirst) {
                        port = "if";
                        isFirst = false;
                    } else if ("elseif".equals(dc.type())) {
                        port = "elseif_" + elseifIndex++;
                    } else {
                        port = "case_" + elseifIndex++; // For switch
                    }

                    // Resolve and wire target node
                    String targetKey = portTargets.get(port);
                    if (targetKey != null) {
                        ExecutionNode targetNode = nodeMap.get(targetKey);
                        if (targetNode != null) {
                            // Add target to branch using polymorphic interface method
                            node.addBranchTarget(branchIndex, targetNode);

                            // Add predecessor WITH PORT for split-aware routing
                            // The port (e.g., "if", "else") is needed by SplitAwareNodeExecutor.getRoutedItemIndices()
                            // to determine which split items were routed to this branch.
                            // Format: "core:check_item:if" - EdgeRefParser can extract the port.
                            // canExecute() handles port-based predecessors via EdgeRefParser stripping.
                            // addPredecessor is now in ExecutionNode interface
                            String predecessorWithPort = decisionKey + ":" + port;
                            targetNode.addPredecessor(predecessorWithPort);

                            logger.info("🔀 Decision {} branch '{}' (index={}) wired to: {} (predecessor={})",
                                decisionKey, port, branchIndex, targetKey, predecessorWithPort);
                        } else {
                            logger.warn("⚠️ Decision {} branch '{}' target not found: {}", decisionKey, port, targetKey);
                        }
                    }
                    branchIndex++;
                }
            }
        }
    }
}
