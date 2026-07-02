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
import java.util.List;
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
        validatePortIndexInRange(session, result);
    }

    /**
     * Validate with an existing graph analyzer (for performance when validating multiple aspects).
     */
    public void validate(WorkflowBuilderSession session, ValidationGraphAnalyzer graph, ValidationResult result) {
        validateEdges(session, graph, result);
        validateIncomingEdges(session, graph, result);
        validateOutputPortUniqueness(session, result);
        validatePortIndexInRange(session, result);
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

    /**
     * Rule: an INDEXED port must reference a DECLARED output. An edge from
     * branch_N / choice_N / category_N / case_N / elseif_N where N is at or
     * beyond the node's declared output count references a port the runtime
     * never builds: the undeclared index collapses onto a declared one when the
     * plan is wired for execution - two successors silently share one branch
     * and one branch never fires. The connect action now refuses to create such
     * edges, but whole-plan imports (set_plan / get_plan round-trips, cloned or
     * hand-authored plans) bypass it, so re-check here - same split as
     * validateOutputPortUniqueness.
     *
     * <p>Deliberate decision: plans authored BEFORE this rule that carry such
     * edges now error at validate/finish instead of loading silently - those
     * edges were already broken at runtime (dropped or collapsed onto another
     * branch), so surfacing them is strictly better. For fork the repair is
     * one disconnect+connect (connect auto-extends the declaration).
     */
    private void validatePortIndexInRange(WorkflowBuilderSession session, ValidationResult result) {
        for (Map<String, Object> edge : session.getEdges()) {
            String from = (String) edge.get("from");
            if (from == null) continue;
            String[] split = EdgeRefParser.splitPort(from);
            if (split[1] == null) continue;
            String error = indexedPortRangeError(session, split[0], split[1]);
            if (error != null) {
                result.addError("PORT_INDEX_OUT_OF_RANGE", split[0], error);
            }
        }
    }

    /**
     * Shared range check for an indexed output port. Returns an agent-facing
     * error message when {@code port} references an output the node does not
     * declare, or null when the reference is fine / not an indexed port /
     * the node cannot be found (other rules cover those). Static so the
     * connect action can enforce the same rule inline for explicit ports.
     */
    @SuppressWarnings("unchecked")
    public static String indexedPortRangeError(WorkflowBuilderSession session, String baseId, String port) {
        int idx = indexedPortIndex(port);
        if (idx < 0) return null; // not an indexed port family (if/else/body/pass/...)

        // Switch is positional, not a count: the runtime (Core.getSwitchPorts /
        // SwitchNodeWirer) names port case_N iff position N of switchCases holds
        // a NON-default case. A count-based check would both reject
        // builder-produced graphs (default in the middle, cases appended after a
        // trailing default) and accept case_N pointing AT the default position.
        if (port.startsWith("case_")) {
            Map<String, Object> node = findNodeById(session, baseId);
            if (node == null || !"switch".equals(node.get("type"))) return null;
            List<Map<String, Object>> cases = (List<Map<String, Object>>) node.get("switchCases");
            int size = cases == null ? 0 : cases.size();
            if (idx < size && !"default".equals(cases.get(idx).get("type"))) {
                return null; // declared non-default case at that position
            }
            String reason = (idx < size)
                    ? "position " + idx + " holds the DEFAULT case - its port is 'default', not '" + port + "'"
                    : (size == 0 ? "no cases are declared"
                                 : "only " + size + " cases are declared (positions 0.." + (size - 1) + ")");
            return "Edge from '" + baseId + ":" + port + "' references an undeclared switch port: "
                    + reason + ". At runtime this edge is silently dropped. Add the case to the node "
                    + "(action='modify') or re-wire this edge to a declared port.";
        }

        Integer declared = declaredIndexedPortCount(session, baseId, port);
        if (declared == null) return null; // node not found or family not applicable

        if (idx >= declared) {
            String family = port.substring(0, port.lastIndexOf('_') + 1);
            String validRange = declared == 0
                    ? "none declared"
                    : family + "0.." + family + (declared - 1);
            return "Edge from '" + baseId + ":" + port + "' references an undeclared output: '" + baseId
                    + "' declares only " + declared + " outputs for this port family ("
                    + validRange + "). At runtime an undeclared index collapses onto a declared "
                    + "branch, silently merging two routes. Add the missing output to the node "
                    + "(action='modify') or re-wire this edge to a declared port.";
        }
        return null;
    }

    /** Returns the numeric index of an indexed port (branch_2 → 2), or -1 for non-indexed ports. */
    private static int indexedPortIndex(String port) {
        int us = port.lastIndexOf('_');
        if (us < 0 || us == port.length() - 1) return -1;
        String prefix = port.substring(0, us + 1);
        if (!prefix.equals("branch_") && !prefix.equals("choice_")
                && !prefix.equals("category_") && !prefix.equals("case_")
                && !prefix.equals("elseif_")) {
            return -1;
        }
        try {
            return Integer.parseInt(port.substring(us + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Declared output count for the port family used by {@code port} on the node
     * {@code baseId}, or null when the node cannot be found or the family does
     * not apply to its type (those cases are covered by other edge rules).
     */
    @SuppressWarnings("unchecked")
    private static Integer declaredIndexedPortCount(WorkflowBuilderSession session, String baseId, String port) {
        Map<String, Object> node = findNodeById(session, baseId);
        if (node == null) return null;
        String type = (String) node.get("type");

        if (port.startsWith("branch_") && "fork".equals(type)) {
            List<Map<String, Object>> outputs = (List<Map<String, Object>>) node.get("forkOutputs");
            return outputs == null ? 0 : outputs.size();
        }
        if (port.startsWith("choice_") && "option".equals(type)) {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) node.get("optionChoices");
            return choices == null ? 0 : choices.size();
        }
        if (port.startsWith("category_") && Boolean.TRUE.equals(node.get("isClassify"))) {
            List<Map<String, Object>> categories = (List<Map<String, Object>>) node.get("classifyOutputs");
            return categories == null ? 0 : categories.size();
        }

        // Count-based: exact for every canonical [if, elseif*, else] list (all
        // the builder produces - expansion inserts before the trailing else).
        // A hand-authored list with elseifs AFTER the else diverges from the
        // runtime's positional numbering; such lists are malformed upstream.
        if (port.startsWith("elseif_") && "decision".equals(type)) {
            List<Map<String, Object>> conditions = (List<Map<String, Object>>) node.get("decisionConditions");
            if (conditions == null) return 0;
            int elseifs = 0;
            for (Map<String, Object> c : conditions) {
                String condType = (String) c.get("type");
                if (!"if".equals(condType) && !"else".equals(condType)) elseifs++;
            }
            return elseifs;
        }
        return null;
    }

    /**
     * Find a node by its resolved id in cores and mcps, with the agent-label
     * fallback classify/guardrail steps need (their mcp entry may carry a raw
     * id while edges reference agent:&lt;normalized-label&gt; - the same fallback
     * the connection manager uses). Deliberately scans the raw session lists
     * like the rest of this validator instead of going through the session's
     * node finder: validation must judge exactly the data it was handed.
     */
    private static Map<String, Object> findNodeById(WorkflowBuilderSession session, String baseId) {
        for (Map<String, Object> core : session.getCores()) {
            if (baseId.equals(core.get("id"))) return core;
        }
        for (Map<String, Object> mcp : session.getMcps()) {
            if (baseId.equals(mcp.get("id"))) return mcp;
            String label = (String) mcp.get("label");
            if (label != null && baseId.equals("agent:" + WorkflowBuilderSession.normalizeLabel(label))) {
                return mcp;
            }
        }
        return null;
    }

    private String getEdgeTarget(Map<String, Object> edge) {
        Object to = edge.get("to");
        return to instanceof String ? (String) to : null;
    }
}
