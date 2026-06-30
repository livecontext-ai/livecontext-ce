package com.apimarketplace.orchestrator.tools.workflow.builder.validation;

import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderValidator.ValidationResult;
import com.apimarketplace.orchestrator.utils.EdgeRefParser;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Validates workflow edges and incoming edge constraints.
 *
 * Rules enforced:
 * - Edge source must exist
 * - Edge target must exist
 * - No self-loops (except for loop nodes)
 * - Trigger must have 0 incoming edges
 * - Merge nodes: should have 2+ incoming edges (warns if fewer)
 * - Other core nodes (decision, loop, switch, fork, etc.): exactly 1 incoming edge
 * - Each named output port wires to at most 1 target (one port = one node)
 */
@Slf4j
@Component
public class EdgeValidator implements WorkflowValidator {

    @Override
    public void validate(WorkflowBuilderSession session, ValidationResult result) {
        ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);
        validateEdges(session, graph, result);
        validateIncomingEdges(session, graph, result);
        validateOutputPortUniqueness(session, result);
    }

    /**
     * Validate with an existing graph analyzer (for performance when validating multiple aspects).
     */
    public void validate(WorkflowBuilderSession session, ValidationGraphAnalyzer graph, ValidationResult result) {
        validateEdges(session, graph, result);
        validateIncomingEdges(session, graph, result);
        validateOutputPortUniqueness(session, result);
    }

    private void validateEdges(WorkflowBuilderSession session, ValidationGraphAnalyzer graph, ValidationResult result) {
        for (Map<String, Object> edge : session.getEdges()) {
            String from = (String) edge.get("from");
            String to = getEdgeTarget(edge);

            // Rule: 'from' must exist
            if (from != null && !graph.nodeExists(from)) {
                result.addError("INVALID_EDGE_SOURCE", null, "from",
                        "Edge source '" + from + "' does not exist.");
            }

            // Rule: 'to' must exist (if specified)
            if (to != null && !graph.nodeExists(to)) {
                result.addError("INVALID_EDGE_TARGET", null, "to",
                        "Edge target '" + to + "' does not exist.");
            }

            // Rule: No self-loops (except for loop nodes)
            if (from != null && from.equals(to) && !from.startsWith("core:")) {
                result.addError("SELF_LOOP", from,
                        "Node '" + from + "' cannot connect to itself.");
            }
        }
    }

    private void validateIncomingEdges(WorkflowBuilderSession session, ValidationGraphAnalyzer graph, ValidationResult result) {
        Map<String, Integer> incomingCount = graph.getIncomingEdgeCounts();

        // Build a map of core node types for type-specific validation
        Map<String, String> coreNodeTypes = new HashMap<>();
        for (Map<String, Object> core : session.getCores()) {
            String label = (String) core.get("label");
            if (label != null) {
                String nodeId = LabelNormalizer.coreKey(label);
                String type = (String) core.get("type");
                if (type != null) {
                    coreNodeTypes.put(nodeId, type);
                }
            }
        }

        // Check each node's incoming edge count
        for (Map.Entry<String, Integer> entry : incomingCount.entrySet()) {
            String nodeId = entry.getKey();
            int count = entry.getValue();

            // Rule: Trigger must have 0 incoming edges
            if (nodeId.startsWith("trigger:") && count > 0) {
                result.addError("TRIGGER_HAS_INCOMING", nodeId,
                        "Trigger cannot have incoming edges. Triggers are entry points only.");
            }

            // Rule: Core nodes - type-specific incoming edge validation
            if (nodeId.startsWith("core:")) {
                String coreType = coreNodeTypes.get(nodeId);

                if ("merge".equals(coreType)) {
                    // Merge nodes join multiple branches - should have 2+ incoming edges
                    if (count < 2) {
                        result.addWarning("MERGE_FEW_INCOMING", nodeId,
                                "Merge node has only " + count + " incoming edge(s). " +
                                "Merge nodes typically join 2 or more branches.");
                    }
                } else {
                    // All other core nodes (decision, loop, switch, split, fork, transform, wait, option)
                    // should have exactly 1 incoming edge
                    if (count != 1) {
                        String typeName = coreType != null ? coreType.substring(0, 1).toUpperCase() + coreType.substring(1) : "Core";
                        if (count == 0) {
                            result.addError("CORE_NO_INCOMING", nodeId,
                                    typeName + " node must have exactly 1 incoming edge. Found 0. " +
                                    "Use workflow(action='add_node', type='<tool-uuid>', ..., connect_after='Source Label') to connect it.");
                        } else {
                            result.addError("CORE_MULTIPLE_INCOMING", nodeId,
                                    typeName + " node must have exactly 1 incoming edge. Found " + count + ". " +
                                    "Use a Merge node before the " + typeName + " to join branches.");
                        }
                    }
                }
            }
        }
    }

    /**
     * Rule: one output PORT = one target node. A named branch port - decision
     * (if/elseif_N/else), switch (case_N/default), loop (body/exit), fork
     * (branch_N), option (choice_N), approval (approved/rejected/timeout),
     * classify (category_N), guardrail (pass/fail) - may wire to at most one
     * successor. The connect action already guards this, but whole-plan
     * imports (set_plan / get_plan round-trips) bypass it, so re-check here.
     * Nodes WITHOUT a named port (trigger, plain step) are exempt - their
     * multiple outgoing edges are a legitimate implicit fork (parallel).
     */
    private void validateOutputPortUniqueness(WorkflowBuilderSession session, ValidationResult result) {
        Map<String, Set<String>> targetsByPortedSource = new LinkedHashMap<>();
        for (Map<String, Object> edge : session.getEdges()) {
            String from = (String) edge.get("from");
            if (from == null || EdgeRefParser.splitPort(from)[1] == null) {
                continue; // no named port - implicit fork is allowed
            }
            // Tolerate both the V2 'to' key and the legacy 'target' key so an
            // imported plan edge can't slip a port fan-out past this check.
            String to = edge.get("to") != null ? (String) edge.get("to") : (String) edge.get("target");
            if (to == null) {
                continue;
            }
            targetsByPortedSource.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(to);
        }

        for (Map.Entry<String, Set<String>> entry : targetsByPortedSource.entrySet()) {
            Set<String> targets = entry.getValue();
            if (targets.size() > 1) {
                String[] split = EdgeRefParser.splitPort(entry.getKey());
                result.addError("PORT_MULTIPLE_TARGETS", split[0],
                        "Output port '" + split[1] + "' on '" + split[0] + "' connects to "
                        + targets.size() + " targets (" + String.join(", ", targets) + "). "
                        + "One output port = one target node - a single port cannot fan out to several nodes. "
                        + "Insert a Fork node to run several nodes from here in parallel.");
            }
        }
    }

    private String getEdgeTarget(Map<String, Object> edge) {
        Object to = edge.get("to");
        return to instanceof String ? (String) to : null;
    }
}
