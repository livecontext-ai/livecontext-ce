package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Wires approval port targets to successor nodes.
 * Similar to DecisionNodeWirer but for approval nodes with
 * approved/rejected/timeout ports.
 */
@Component
public class ApprovalNodeWirer {

    private static final Logger logger = LoggerFactory.getLogger(ApprovalNodeWirer.class);

    /**
     * Wire approval port targets from collected edge data.
     *
     * @param nodeMap All execution nodes
     * @param plan The workflow plan
     * @param approvalPortTargets Map of approvalKey -> Map of port -> targetKey
     */
    public void wireApprovalPortTargets(
            Map<String, ExecutionNode> nodeMap,
            WorkflowPlan plan,
            Map<String, Map<String, String>> approvalPortTargets) {

        for (Map.Entry<String, Map<String, String>> entry : approvalPortTargets.entrySet()) {
            String approvalKey = entry.getKey();
            Map<String, String> portTargetMap = entry.getValue();

            ExecutionNode node = nodeMap.get(approvalKey);
            if (node == null || !node.isApprovalNode()) {
                logger.warn("Approval node not found or wrong type: {}", approvalKey);
                continue;
            }

            for (Map.Entry<String, String> portEntry : portTargetMap.entrySet()) {
                String port = portEntry.getKey();
                String targetKey = portEntry.getValue();

                ExecutionNode targetNode = nodeMap.get(targetKey);
                if (targetNode == null) {
                    logger.warn("Approval target node not found: {} for port {} of {}",
                        targetKey, port, approvalKey);
                    continue;
                }

                // Add port target on approval node using polymorphic interface method
                node.addPortTarget(port, targetNode);

                // Set predecessor on target node (with port suffix for proper tracking)
                // addPredecessor is now in ExecutionNode interface
                String predecessorRef = approvalKey + ":" + port;
                targetNode.addPredecessor(predecessorRef);
                logger.info("Wired approval port: {}:{} -> {} (predecessor: {})",
                    approvalKey, port, targetKey, predecessorRef);
            }
        }
    }
}
