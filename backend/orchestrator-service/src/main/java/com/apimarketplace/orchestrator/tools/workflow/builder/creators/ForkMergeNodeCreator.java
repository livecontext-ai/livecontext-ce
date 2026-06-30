package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSessionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import com.apimarketplace.agent.tools.ToolErrorCode;

/**
 * Handles fork and merge node creation for the workflow builder.
 * Fork nodes create parallel branches - ALL branches execute simultaneously.
 * Merge nodes wait for ALL predecessors to complete before continuing (AND mode).
 *
 * NEW FORMAT (unified):
 *   workflow(action='add_node', type='fork', label='Parallel', params={branches: [...]})
 *   workflow(action='add_node', type='merge', label='Wait All', params={...})
 *   - All params are flat (no nested 'fork' object)
 *   - ID is auto-generated (core:normalized_label)
 *
 * @see ControlNodeCreator
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ForkMergeNodeCreator extends CreatorBase {

    private final WorkflowBuilderSessionStore sessionStore;

    // ==================== Add Fork ====================

    /**
     * Execute add_fork action.
     * Fork creates parallel branches - ALL branches execute simultaneously.
     * NEW FORMAT: All parameters are flat, no nested 'fork' object.
     */
    @SuppressWarnings("unchecked")
    public ToolExecutionResult executeAddFork(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // 1. Validate label (flat param)
        String label = safeString(parameters.get("label"));
        if (label == null) label = safeString(parameters.get("name"));
        var labelError = validateLabel(label, "fork");
        if (labelError != null) return labelError;

        // 2. MANDATORY: Trigger must exist before adding fork
        if (session.getTriggers().isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "TRIGGER REQUIRED FIRST: Cannot add fork without a trigger. " +
                "Create a trigger first using: workflow(action='add_node', type='form', label='...', params={...})");
        }

        // 3. Parse branches (flat param) - accept alias 'outputs'
        List<Map<String, Object>> branches = (List<Map<String, Object>>) parameters.get("branches");
        if (branches == null) branches = (List<Map<String, Object>>) parameters.get("outputs");
        if (branches == null || branches.size() < 2) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "FORK: 'branches' array is required (minimum 2 branches).\n\n" +
                "FORMAT:\n" +
                "  workflow(action='add_node', type='fork', label='Parallel Tasks',\n" +
                "    params={branches: [\n" +
                "      {label: 'Branch A'},\n" +
                "      {label: 'Branch B'},\n" +
                "      {label: 'Branch C'}\n" +
                "    ]}, connect_after='...')\n\n" +
                "THEN CONNECT EACH BRANCH TO A TARGET:\n" +
                "  workflow(action='connect', from='Parallel Tasks', to='Task A')  # branch_0\n" +
                "  workflow(action='connect', from='Parallel Tasks', to='Task B')  # branch_1\n" +
                "  workflow(action='connect', from='Parallel Tasks', to='Task C')  # branch_2\n\n" +
                "All branches execute IN PARALLEL (unlike decision which is exclusive).");
        }

        // 4. Generate node ID and check uniqueness
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.FORK.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        // 5. Resolve connect_after
        String connectAfter = resolveConnectAfter(parameters, session);
        var connectAfterError = validateConnectAfter(connectAfter, session);
        if (connectAfterError != null) return connectAfterError;

        // 6. Build fork node
        Map<String, Object> forkNode = new LinkedHashMap<>();
        forkNode.put("id", "core:" + normalizedLabel);
        forkNode.put("label", label);
        forkNode.put("type", "fork");
        forkNode.put("position", calculatePosition(session, NodeType.FORK));

        // Build forkOutputs list
        List<Map<String, Object>> forkOutputs = new ArrayList<>();
        for (int i = 0; i < branches.size(); i++) {
            Map<String, Object> branch = branches.get(i);
            String branchLabel = (String) branch.get("label");
            if (branchLabel == null) branchLabel = (String) branch.get("name");
            if (branchLabel == null) branchLabel = "Branch " + (i + 1);

            forkOutputs.add(Map.of(
                "id", nodeId + "-output-" + i,
                "label", branchLabel
            ));
        }
        forkNode.put("forkOutputs", forkOutputs);

        // 6. Add to session and create edge
        session.getCores().add(forkNode);
        if (connectAfter != null && !connectAfter.isBlank()) {
            createForkEdge(session, connectAfter, nodeId);
        }

        // 7. Finalize
        boolean isOrphaned = finalizeNode(session, sessionStore, NodeType.FORK, nodeId, forkNode, connectAfter);

        // 8. Build response
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        response.put("node_type", "fork");
        response.put("node_id", nodeId);
        response.put("label", label);
        response.put("branches_count", branches.size());

        // Show branch ports for edge connections
        List<Map<String, Object>> branchInfo = new ArrayList<>();
        for (int i = 0; i < branches.size(); i++) {
            Map<String, Object> branch = branches.get(i);
            String branchLabel = (String) branch.get("label");
            if (branchLabel == null) branchLabel = "Branch " + (i + 1);
            branchInfo.add(Map.of(
                "port", "branch_" + i,
                "label", branchLabel,
                "edge_from", nodeId + ":branch_" + i
            ));
        }
        response.put("branches", branchInfo);

        // Connection guidance
        Map<String, Object> connectionInfo = new LinkedHashMap<>();
        connectionInfo.put("status", connectAfter != null ? "connected" : "orphaned");
        if (connectAfter != null) {
            connectionInfo.put("connected_after", connectAfter);
        }
        connectionInfo.put("next_step", "Connect each branch to a target step using: " +
            "workflow(action='connect', from='" + label + "', to='Target Step')");
        response.put("connection", connectionInfo);

        // Progressive validation
        int totalNodes = session.getTriggers().size() + session.getMcps().size() + session.getCores().size();
        if (totalNodes >= 3) {
            List<String> orphans = session.findOrphanNodes().stream()
                .filter(id -> !id.equals(nodeId))
                .toList();
            if (!orphans.isEmpty()) {
                Map<String, Object> validation = new LinkedHashMap<>();
                validation.put("other_orphan_nodes", orphans.stream()
                    .map(id -> Map.of("id", id, "logical_id", session.getLogicalId(id)))
                    .toList());
                validation.put("hint", "Other nodes are also disconnected. Use workflow(action='connect', from='Source Label', to='Target Label')");
                response.put("progressive_validation", validation);
            }
        }

        // NEXT pattern with concrete port examples
        List<String> portExamples = new ArrayList<>();
        for (int i = 0; i < branches.size(); i++) {
            portExamples.add(label + ":branch_" + i);
        }
        Map<String, Object> next = new LinkedHashMap<>();
        next.put("per_branch", "workflow(action='add_node', type='...', label='...', params={...}, connect_after='" + label + ":branch_N')");
        next.put("available_ports", portExamples.stream().map(e -> "branch_" + portExamples.indexOf(e)).toList());
        next.put("examples", portExamples);
        next.put("note", "ALL branches execute in parallel (unlike decision which is exclusive)");
        next.put("get_params", "workflow(action='help', topics=['mcp', 'agent', ...]) for node params");
        response.put("NEXT", next);

        // Show saved params so LLM knows what was actually stored
        // Sanitize: strip internal IDs, include only label and computed port
        List<Map<String, Object>> sanitizedBranches = new ArrayList<>();
        for (int i = 0; i < branches.size(); i++) {
            Map<String, Object> branch = branches.get(i);
            String branchLabel = (String) branch.get("label");
            if (branchLabel == null) branchLabel = (String) branch.get("name");
            if (branchLabel == null) branchLabel = "Branch " + (i + 1);
            sanitizedBranches.add(Map.of(
                "label", branchLabel,
                "port", "branch_" + i
            ));
        }
        response.put("saved_params", Map.of("branches", sanitizedBranches));

        return ToolExecutionResult.success(response);
    }

    // ==================== Add Merge ====================

    /**
     * Execute add_merge action.
     * Merge waits for ALL predecessors to complete before continuing (AND mode).
     * NEW FORMAT: All parameters are flat, no nested object.
     */
    @SuppressWarnings("unchecked")
    public ToolExecutionResult executeAddMerge(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // 1. Validate label (flat param)
        String label = safeString(parameters.get("label"));
        if (label == null) label = safeString(parameters.get("name"));
        var labelError = validateLabel(label, "merge");
        if (labelError != null) return labelError;

        // 2. MANDATORY: Trigger must exist before adding merge
        if (session.getTriggers().isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "TRIGGER REQUIRED FIRST: Cannot add merge without a trigger. " +
                "Create a trigger first using: workflow(action='add_node', type='form', label='...', params={...})");
        }

        // 3. Generate node ID and check uniqueness
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = "core:" + normalizedLabel;
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        // 4. Parse source steps from mergeInputs/inputs params (used only for auto-wiring edges)
        List<Map<String, Object>> mergeInputParams = (List<Map<String, Object>>) parameters.get("mergeInputs");
        if (mergeInputParams == null) mergeInputParams = (List<Map<String, Object>>) parameters.get("inputs");

        // 5. Resolve connect_after (creates one incoming edge to merge)
        String connectAfter = resolveConnectAfter(parameters, session);
        var connectAfterError = validateConnectAfter(connectAfter, session);
        if (connectAfterError != null) return connectAfterError;

        // 6. Build merge node (no mergeInputs in plan - connections are edges only)
        Map<String, Object> mergeNode = new LinkedHashMap<>();
        mergeNode.put("id", nodeId);
        mergeNode.put("label", label);
        mergeNode.put("type", "merge");
        mergeNode.put("position", calculatePosition(session, NodeType.MERGE));

        // Collect sourceStep references for auto-wiring edges
        List<String> sourceStepsToWire = new ArrayList<>();
        if (mergeInputParams != null && !mergeInputParams.isEmpty()) {
            for (Map<String, Object> input : mergeInputParams) {
                String sourceStep = (String) input.get("sourceStep");
                if (sourceStep == null) sourceStep = (String) input.get("source_step");
                if (sourceStep != null && !sourceStep.isBlank()) {
                    sourceStepsToWire.add(sourceStep);
                }
            }
        }

        // 7. Add to session
        session.getCores().add(mergeNode);

        // 8. Create edges: connect_after → merge (if provided)
        if (connectAfter != null && !connectAfter.isBlank()) {
            createSimpleEdge(session, connectAfter, nodeId);
        }

        // 9. Auto-wire edges from sourceStep references → merge (if nodes exist)
        String resolvedConnectAfter = connectAfter != null
                ? session.resolveNodeReference(connectAfter) : null;
        List<String> autoWired = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        for (String sourceStep : sourceStepsToWire) {
            String resolvedSource = session.resolveNodeReference(sourceStep);
            // Skip if already wired via connect_after (avoid duplicate edge)
            if (resolvedSource.equals(resolvedConnectAfter)) continue;
            // Only wire if the source node actually exists in the session
            if (session.nodeExists(resolvedSource)) {
                createSimpleEdge(session, resolvedSource, nodeId);
                autoWired.add(resolvedSource);
            } else {
                notFound.add(sourceStep);
            }
        }

        // 10. Finalize
        boolean isOrphaned = finalizeNode(session, sessionStore, NodeType.MERGE, nodeId, mergeNode, connectAfter);

        // 7. Build response
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        response.put("node_type", "merge");
        response.put("node_id", nodeId);
        response.put("label", label);

        // Connection guidance
        Map<String, Object> connectionInfo = new LinkedHashMap<>();
        int totalWired = (connectAfter != null ? 1 : 0) + autoWired.size();
        if (totalWired > 0) {
            connectionInfo.put("status", "connected");
            connectionInfo.put("incoming_edges", totalWired);
            if (connectAfter != null) {
                connectionInfo.put("connected_from", connectAfter);
            }
            if (!autoWired.isEmpty()) {
                connectionInfo.put("auto_wired_from_sourceSteps", autoWired);
            }
        } else {
            connectionInfo.put("status", "waiting_for_connections");
        }
        if (!notFound.isEmpty()) {
            connectionInfo.put("sourceSteps_not_found", notFound);
            connectionInfo.put("hint", "These sourceStep nodes don't exist yet. Connect them manually after creation: " +
                "workflow(action='connect', from='<node>', to='" + label + "')");
        }
        connectionInfo.put("add_more_inputs", "workflow(action='connect', from='Another Branch', to='" + label + "')");
        connectionInfo.put("behavior", "AND mode - waits for ALL connected predecessors to complete (COMPLETED or SKIPPED)");
        response.put("connection", connectionInfo);

        // NEXT pattern (normalizedLabel already defined at line 210)
        response.put("NEXT", Map.of(
            "pattern", "workflow(action='add_node', type='...', label='...', params={...}, connect_after='" + label + "')",
            "this_node_output", "{{core:" + normalizedLabel + ".output.merged_items}}",
            "get_params", "workflow(action='help', topics=['mcp', 'agent', ...]) for required params"
        ));

        // Show saved params so LLM knows what was actually stored
        response.put("saved_params", Map.of("label", label));

        return ToolExecutionResult.success(response);
    }

    // ==================== Edge Creation Helpers ====================

    /**
     * V2: Creates a simple entry edge to a fork node.
     * Fork outputs use port-based edges: core:label:branch_N -> target
     */
    private void createForkEdge(WorkflowBuilderSession session, String from, String forkId) {
        // V2: Simple entry edge to fork node
        createSimpleEdge(session, from, forkId);
    }
}
