package com.apimarketplace.orchestrator.tools.workflow.builder.validation;

import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderValidator.ValidationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Validates workflow graph structure.
 *
 * Rules enforced:
 * - All nodes must be reachable from triggers
 * - No cycles (except for loop nodes)
 */
@Slf4j
@Component
public class GraphValidation implements WorkflowValidator {

    @Override
    public void validate(WorkflowBuilderSession session, ValidationResult result) {
        ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);
        validateReachability(session, graph, result);
        validateCycles(session, graph, result);
    }

    /**
     * Validate with an existing graph analyzer (for performance when validating multiple aspects).
     */
    public void validate(WorkflowBuilderSession session, ValidationGraphAnalyzer graph, ValidationResult result) {
        validateReachability(session, graph, result);
        validateCycles(session, graph, result);
    }

    private void validateReachability(WorkflowBuilderSession session, ValidationGraphAnalyzer graph, ValidationResult result) {
        Set<String> reachable = graph.getReachableFromTriggers();
        Set<String> allNodes = graph.getAllNodeIds();

        for (String nodeId : allNodes) {
            // Skip triggers (they are starting points)
            if (nodeId.startsWith("trigger:")) continue;

            if (!reachable.contains(nodeId)) {
                result.addError("UNREACHABLE_NODE", nodeId,
                        "Node '" + nodeId + "' is not reachable from any trigger. " +
                        "Use workflow(action='add_node', type='<tool-uuid>', ..., connect_after='Source Label') or workflow(action='connect', from='Source Label', to='Target Label').");
            }
        }
    }

    private void validateCycles(WorkflowBuilderSession session, ValidationGraphAnalyzer graph, ValidationResult result) {
        List<String> cycles = graph.detectCycles();

        for (String cycle : cycles) {
            // Cycles are only allowed for loops (body back to loop)
            if (!cycle.contains("core:")) {
                result.addError("CYCLE_DETECTED", null,
                        "Cycle detected: " + cycle + ". Cycles are only allowed in loops.");
            }
        }
    }
}
