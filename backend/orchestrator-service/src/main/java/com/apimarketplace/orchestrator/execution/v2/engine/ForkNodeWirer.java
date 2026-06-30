package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Wires fork branch targets AFTER all nodes have been created (Pass 2).
 * Fork nodes have branches identified by ports (branch_0, branch_1, etc.)
 */
@Component
public class ForkNodeWirer {

    private static final Logger logger = LoggerFactory.getLogger(ForkNodeWirer.class);

    /**
     * Wires fork branch targets to their target nodes.
     *
     * @param nodeMap The map of all nodes
     * @param plan The workflow plan containing core definitions
     * @param forkBranchTargets Map of fork keys to port->target mappings
     */
    public void wireForkBranchTargets(
            Map<String, ExecutionNode> nodeMap,
            WorkflowPlan plan,
            Map<String, Map<String, String>> forkBranchTargets) {
        if (plan.getCores() == null) {
            return;
        }

        for (Core coreNode : plan.getCores()) {
            if (!"fork".equals(coreNode.type())) {
                continue;
            }

            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String forkKey = "core:" + normalizedLabel;

            ExecutionNode node = nodeMap.get(forkKey);
            if (node == null || !node.isForkNode()) {
                continue;
            }

            // Get port targets for this fork
            Map<String, String> portTargets = forkBranchTargets.getOrDefault(forkKey, Map.of());

            // Wire targets to branches using polymorphic addForkBranch()
            // Sort by branch index (branch_0, branch_1, etc.)
            List<String> sortedPorts = new ArrayList<>(portTargets.keySet());
            sortedPorts.sort((a, b) -> {
                int indexA = extractBranchIndex(a);
                int indexB = extractBranchIndex(b);
                return Integer.compare(indexA, indexB);
            });

            for (String port : sortedPorts) {
                String targetKey = portTargets.get(port);
                ExecutionNode targetNode = nodeMap.get(targetKey);
                if (targetNode != null) {
                    // Create branch and add target using polymorphic interface method
                    String branchId = port;
                    String branchLabel = "Branch " + extractBranchIndex(port);
                    node.addForkBranch(branchId, branchLabel, targetNode);

                    // Add predecessor for implicit merge detection
                    targetNode.addPredecessor(forkKey);

                    logger.info("🔱 Fork {} branch '{}' wired to: {}", forkKey, port, targetKey);
                } else {
                    logger.warn("⚠️ Fork {} branch '{}' target not found: {}", forkKey, port, targetKey);
                }
            }
        }
    }

    /**
     * Extract branch index from port name (e.g., "branch_0" -> 0, "branch_1" -> 1).
     */
    private int extractBranchIndex(String port) {
        if (port == null) return 0;
        if (port.startsWith("branch_")) {
            try {
                return Integer.parseInt(port.substring(7));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }
}
