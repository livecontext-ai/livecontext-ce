package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Wires switch case targets AFTER all nodes have been created (Pass 2).
 * Switch nodes have cases identified by ports (case_0, case_1, ..., default).
 */
@Component
public class SwitchNodeWirer {

    private static final Logger logger = LoggerFactory.getLogger(SwitchNodeWirer.class);

    /**
     * Wires switch case targets to their target nodes.
     *
     * @param nodeMap The map of all nodes
     * @param plan The workflow plan containing core definitions
     * @param switchPortTargets Map of switch keys to port->target mappings
     */
    public void wireSwitchCaseTargets(
            Map<String, ExecutionNode> nodeMap,
            WorkflowPlan plan,
            Map<String, Map<String, String>> switchPortTargets) {
        if (plan.getCores() == null) {
            return;
        }

        for (Core coreNode : plan.getCores()) {
            if (!"switch".equals(coreNode.type())) {
                continue;
            }

            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String switchKey = "core:" + normalizedLabel;

            ExecutionNode node = nodeMap.get(switchKey);
            if (node == null || !node.isSwitchNode()) {
                continue;
            }

            // Get port targets for this switch
            Map<String, String> portTargets = switchPortTargets.getOrDefault(switchKey, Map.of());

            // Wire targets to cases using polymorphic addBranchTarget()
            // Count how many cases exist in the Core definition
            List<Core.SwitchCase> cases = coreNode.switchCases();
            if (cases != null) {
                for (int i = 0; i < cases.size(); i++) {
                    Core.SwitchCase sc = cases.get(i);
                    // Port name: "default" for default case, "case_N" for regular cases
                    String port = "default".equals(sc.type()) ? "default" : "case_" + i;

                    String targetKey = portTargets.get(port);
                    if (targetKey != null) {
                        ExecutionNode targetNode = nodeMap.get(targetKey);
                        if (targetNode != null) {
                            node.addBranchTarget(i, targetNode);
                            // Predecessor MUST include the port suffix so SplitAwareNodeExecutor
                            // can filter items by selected_branch (only items routed to this case
                            // execute on this downstream). Same pattern as DecisionNodeWirer:88
                            // and ApprovalNodeWirer:60. Without the suffix the executor falls back
                            // to transitive routing and runs the node for ALL split items, causing
                            // case_0 + case_1 to both execute for every item.
                            String predecessorWithPort = switchKey + ":" + port;
                            targetNode.addPredecessor(predecessorWithPort);

                            logger.info("🔀 Switch {} case '{}' (index={}) wired to: {} (predecessor: {})",
                                switchKey, port, i, targetKey, predecessorWithPort);
                        } else {
                            logger.warn("Switch {} case '{}' target not found: {}", switchKey, port, targetKey);
                        }
                    }
                }
            }

            // Also check for "default" port if not already handled by switchCases
            // (edge core:xxx:default may exist even if no explicit default case in plan)
            String defaultTargetKey = portTargets.get("default");
            if (defaultTargetKey != null) {
                // Check if default was already wired (a switchCase with type="default" exists)
                boolean defaultAlreadyWired = cases != null &&
                    cases.stream().anyMatch(sc -> "default".equals(sc.type()));

                if (!defaultAlreadyWired) {
                    // Add a default case dynamically at the end
                    ExecutionNode defaultTarget = nodeMap.get(defaultTargetKey);
                    if (defaultTarget != null) {
                        int defaultIndex = cases != null ? cases.size() : 0;
                        node.addBranchTarget(defaultIndex, defaultTarget);
                        // Predecessor with ":default" port suffix - see explanation on the
                        // case-port branch above. SplitAware filtering depends on this.
                        String predecessorWithPort = switchKey + ":default";
                        defaultTarget.addPredecessor(predecessorWithPort);

                        logger.info("🔀 Switch {} dynamic default (index={}) wired to: {} (predecessor: {})",
                            switchKey, defaultIndex, defaultTargetKey, predecessorWithPort);
                    }
                }
            }
        }
    }
}
