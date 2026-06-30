package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Wires option choice targets AFTER all nodes have been created (Pass 2).
 * This allows option choices to reference loop nodes and other control nodes.
 */
@Component
public class OptionNodeWirer {

    private static final Logger logger = LoggerFactory.getLogger(OptionNodeWirer.class);

    /**
     * Wires option choice targets to their target nodes.
     *
     * @param nodeMap The map of all nodes
     * @param plan The workflow plan containing core definitions
     * @param optionPortTargets Map of option keys to port->target mappings
     */
    public void wireOptionChoiceTargets(
            Map<String, ExecutionNode> nodeMap,
            WorkflowPlan plan,
            Map<String, Map<String, String>> optionPortTargets) {
        if (plan.getCores() == null) {
            return;
        }

        for (Core coreNode : plan.getCores()) {
            if (!"option".equals(coreNode.type())) {
                continue;
            }

            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String optionKey = "core:" + normalizedLabel;

            ExecutionNode node = nodeMap.get(optionKey);
            if (node == null || !node.isOptionNode()) {
                continue;
            }

            // Get port targets for this option node
            Map<String, String> portTargets = optionPortTargets.getOrDefault(optionKey, Map.of());

            // Wire targets to choices using polymorphic addBranchTarget()
            if (coreNode.optionChoices() != null) {
                for (int i = 0; i < coreNode.optionChoices().size(); i++) {
                    String port = "choice_" + i;

                    // Resolve and wire target node
                    String targetKey = portTargets.get(port);
                    if (targetKey != null) {
                        ExecutionNode targetNode = nodeMap.get(targetKey);
                        if (targetNode != null) {
                            // Add target to choice using polymorphic interface method
                            node.addBranchTarget(i, targetNode);

                            // Add predecessor WITH port suffix so SplitAwareNodeExecutor can
                            // filter items by selected_branch. Same pattern as DecisionNodeWirer:88
                            // / ApprovalNodeWirer:60 / SwitchNodeWirer (post-fix). Without the
                            // suffix the executor falls back to transitive routing and runs the
                            // node for ALL split items, regardless of which option choice was
                            // selected per item.
                            String predecessorWithPort = optionKey + ":" + port;
                            targetNode.addPredecessor(predecessorWithPort);

                            logger.info("🔘 Option {} choice '{}' (index={}) wired to: {} (predecessor: {})",
                                optionKey, port, i, targetKey, predecessorWithPort);
                        } else {
                            logger.warn("⚠️ Option {} choice '{}' target not found: {}", optionKey, port, targetKey);
                        }
                    }
                }
            }
        }
    }
}
