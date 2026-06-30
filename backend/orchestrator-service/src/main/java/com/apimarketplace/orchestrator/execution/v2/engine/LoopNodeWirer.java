package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Wires loop body/exit targets AFTER all nodes have been created (Pass 2).
 * Loop nodes have two ports:
 * - body: the first node inside the loop body
 * - exit: the node to execute after the loop terminates
 */
@Component
public class LoopNodeWirer {

    private static final Logger logger = LoggerFactory.getLogger(LoopNodeWirer.class);

    /**
     * Wires loop body and exit targets to their target nodes.
     *
     * @param nodeMap The map of all nodes
     * @param plan The workflow plan containing core definitions
     * @param loopPortTargets Map of loop keys to port->target mappings
     */
    public void wireLoopPortTargets(
            Map<String, ExecutionNode> nodeMap,
            WorkflowPlan plan,
            Map<String, Map<String, String>> loopPortTargets) {
        if (plan.getCores() == null) {
            return;
        }

        for (Core coreNode : plan.getCores()) {
            if (!"loop".equals(coreNode.type())) {
                continue;
            }

            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String loopKey = "core:" + normalizedLabel;

            ExecutionNode node = nodeMap.get(loopKey);
            if (node == null || !node.isLoopNode()) {
                continue;
            }

            // Get port targets for this loop
            Map<String, String> portTargets = loopPortTargets.getOrDefault(loopKey, Map.of());

            // Wire body target
            String bodyTargetKey = portTargets.get("body");
            if (bodyTargetKey != null) {
                ExecutionNode bodyTarget = nodeMap.get(bodyTargetKey);
                if (bodyTarget != null) {
                    node.addLoopBodyTarget(bodyTarget);
                    bodyTarget.addPredecessor(loopKey);
                    logger.info("Loop {} body wired to: {}", loopKey, bodyTargetKey);
                } else {
                    logger.warn("Loop {} body target not found: {}", loopKey, bodyTargetKey);
                }
            }

            // Wire exit target
            String exitTargetKey = portTargets.get("exit");
            if (exitTargetKey != null) {
                ExecutionNode exitTarget = nodeMap.get(exitTargetKey);
                if (exitTarget != null) {
                    node.addLoopExitTarget(exitTarget);
                    exitTarget.addPredecessor(loopKey);
                    logger.info("Loop {} exit wired to: {}", loopKey, exitTargetKey);
                } else {
                    logger.warn("Loop {} exit target not found: {}", loopKey, exitTargetKey);
                }
            }
        }
    }
}
