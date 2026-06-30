package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.tools.workflow.builder.creators.DecisionNodeCreator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.orchestrator.utils.EdgeRefParser;

/**
 * Handles connection (edge) management for workflow builder.
 * Actions: connect, disconnect
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowBuilderConnectionManager {

    /**
     * Node types that terminate a branch or the whole workflow. They have
     * NO successors by definition - connecting one as the source of an edge
     * (e.g. exit → merge) is structurally invalid and would either dead-end
     * silently or, worse, drag a downstream merge into a partial-DAG state
     * where it waits forever for a predecessor that can never fire.
     *
     * <p>Surface: {@link #isTerminalCoreType} (used by executeConnect to
     * refuse the edge upfront and by NodeStructureValidator to flag any that
     * slipped past on a stale plan). When adding a new terminal type to the
     * parser, add it here too.
     */
    public static final java.util.Set<String> TERMINAL_CORE_TYPES = java.util.Set.of(
        "exit",
        "end",
        "stop_on_error"
    );

    /**
     * Returns true when {@code node} is a terminal-type core node - i.e. an
     * exit, end, or stop_on_error. Null-safe; non-Map / non-core / unknown
     * types return false.
     *
     * <p>The namespace check (id starts with {@code core:}) is defensive: no
     * creator produces a non-core node with these types, but a hand-edited
     * plan with {@code type=exit} on an {@code mcp:} or {@code trigger:}
     * node would otherwise trip the refusal incorrectly. Restricting to the
     * {@code core:} namespace keeps the contract aligned with the engine
     * (only {@code core:exit} / {@code core:end} / {@code core:stop_on_error}
     * have a runtime terminal implementation).
     */
    public static boolean isTerminalCoreType(java.util.Map<String, Object> node) {
        if (node == null) return false;
        Object id = node.get("id");
        if (!(id instanceof String idStr) || !idStr.startsWith("core:")) return false;
        Object type = node.get("type");
        return type instanceof String s && TERMINAL_CORE_TYPES.contains(s);
    }

    private final WorkflowBuilderSessionStore sessionStore;

    /**
     * Connect two nodes.
     * Accepts node labels (e.g., "My Step") or full nodeIds.
     * Accepts both from/to and source/target parameter names.
     */
    public ToolExecutionResult executeConnect(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // Accept both from/to and source/target (for backward compatibility)
        String fromRef = (String) parameters.get("from");
        if (fromRef == null || fromRef.isBlank()) {
            fromRef = (String) parameters.get("source");
        }
        String toRef = (String) parameters.get("to");
        if (toRef == null || toRef.isBlank()) {
            toRef = (String) parameters.get("target");
        }
        String condition = (String) parameters.get("condition");

        if (fromRef == null || fromRef.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "'from' parameter is required. Use node label (e.g., 'My Trigger') or full nodeId.");
        }
        if (toRef == null || toRef.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "'to' parameter is required. Use node label (e.g., 'My Step') or full nodeId.");
        }

        // Resolve references to node IDs
        String fromNodeId = session.resolveNodeReference(fromRef);
        String toNodeId = session.resolveNodeReference(toRef);

        // Validate nodes exist
        if (!session.nodeExists(fromNodeId)) {
            return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "Source node not found: " + fromRef +
                ". Available: " + formatAvailableNodes(session));
        }
        if (!session.nodeExists(toNodeId)) {
            return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "Target node not found: " + toRef +
                ". Available: " + formatAvailableNodes(session));
        }

        // Terminal nodes (exit, end, stop_on_error) cannot have outgoing edges.
        // Pulled out as an early refusal so the LLM gets an actionable error message
        // before the edge lands in the session and pollutes downstream validation.
        Optional<Map<String, Object>> fromNode = session.findNode(fromNodeId);
        if (fromNode.isPresent() && isTerminalCoreType(fromNode.get())) {
            String terminalType = (String) fromNode.get().get("type");
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Cannot connect FROM '" + fromRef + "': it is a terminal '"
                + terminalType + "' node and must have NO outgoing edges. "
                + "Terminal types (exit, end, stop_on_error) end a branch - to "
                + "merge or continue a flow, route the predecessor of '" + fromRef
                + "' to your target instead, or replace this terminal with a "
                + "non-terminal node.");
        }

        // Auto-assign port for branching nodes (fork, decision)
        // When LLM connects from a fork/decision without port qualifier, assign the next available port
        fromNodeId = autoAssignBranchPort(session, fromNodeId, toNodeId);

        // Auto-assign iterate port for loop target nodes
        // When LLM connects TO a loop without port qualifier, and the loop already has an entry edge, assign :iterate
        toNodeId = autoAssignLoopTargetPort(session, toNodeId);

        // One output PORT = one target node. A named branch port - decision
        // (if/elseif_N/else), switch (case_N/default), loop (body/exit), fork
        // (branch_N), option (choice_N), approval (approved/rejected/timeout),
        // classify (category_N), guardrail (pass/fail) - may connect to AT MOST
        // ONE successor. A second edge from the SAME port to a DIFFERENT target
        // hangs two successors off one branch, which is structurally invalid.
        // The builder UI already blocks this drag (connectionValidator.ts); this
        // closes the agent/MCP path, which previously only caught exact duplicates.
        // Nodes WITHOUT a named port (trigger, plain step) are untouched - their
        // multiple outgoing edges are a legitimate implicit fork (parallel).
        String fromPort = EdgeRefParser.splitPort(fromNodeId)[1];
        if (fromPort != null) {
            final String portedFrom = fromNodeId;
            final String targetTo = toNodeId;
            Optional<Map<String, Object>> portTaken = session.getEdges().stream()
                .filter(e -> portedFrom.equals(e.get("from")))
                .filter(e -> {
                    String existingTo = edgeTarget(e);
                    return existingTo != null && !existingTo.equals(targetTo);
                })
                .findFirst();
            if (portTaken.isPresent()) {
                String baseRef = EdgeRefParser.splitPort(fromNodeId)[0];
                String existingTarget = edgeTarget(portTaken.get());
                return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_ALREADY_EXISTS,
                    "❌ Output port '" + fromPort + "' on '" + formatNodeRef(session, baseRef)
                        + "' already connects to '" + formatNodeRef(session, existingTarget) + "'.\n\n"
                        + "💡 RULE: one output port = one target node. A single port cannot fan out to several nodes.\n\n"
                        + "✅ TO RUN SEVERAL NODES FROM HERE IN PARALLEL, insert a Fork:\n"
                        + "  workflow(action='add_node', type='fork', label='Split', params={branches:['Path A','Path B']}, connect_after='"
                        + formatNodeRef(session, baseRef) + "')\n"
                        + "  then connect each fork branch to its own target.\n\n"
                        + "(Or connect to a different free port on '" + formatNodeRef(session, baseRef) + "'.)");
            }
        }

        // Check if connection already exists
        if (session.hasConnection(fromNodeId, toNodeId)) {
            // Provide better guidance if it's a decision node
            if (fromNodeId.startsWith("core:")) {
                // Get available nodes for context
                String availableNodes = formatAvailableNodes(session);

                return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_ALREADY_EXISTS, "❌ Connection already exists: " + fromRef + " → " + toRef + "\n\n" +
                    "💡 DECISION BRANCHING - HOW IT WORKS:\n" +
                    "  • Branches are defined in 'conditions' when creating the decision\n" +
                    "  • Each connect() creates ONE edge to a DIFFERENT target\n" +
                    "  • You CANNOT connect the same decision to the same target twice\n\n" +
                    "✅ CORRECT WORKFLOW:\n" +
                    "  1. Create target steps FIRST (one per branch)\n" +
                    "  2. Connect decision to DIFFERENT targets using their labels\n\n" +
                    "AVAILABLE NODES: " + availableNodes + "\n\n" +
                    "EXAMPLE (3 branches → 3 different targets):\n" +
                    "  workflow(action='connect', from='" + fromRef + "', to='Step A')  ← branch 1\n" +
                    "  workflow(action='connect', from='" + fromRef + "', to='Step B')  ← branch 2\n" +
                    "  workflow(action='connect', from='" + fromRef + "', to='Step C')  ← branch 3\n\n" +
                    "If ALL branches should go to " + toRef + ", you're already done - one connection handles all branches.");
            }
            return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_ALREADY_EXISTS, "Connection already exists: " + fromRef + " → " + toRef);
        }

        // Create connection
        session.addConnection(fromNodeId, toNodeId, condition);

        // Record action for undo
        Map<String, Object> edgeData = new LinkedHashMap<>();
        edgeData.put("from", fromNodeId);
        edgeData.put("to", toNodeId);
        if (condition != null) edgeData.put("condition", condition);
        session.recordAction("connect", null, "edge", edgeData);
        session.clearRedoStack();

        sessionStore.save(session);

        // Build response
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("message", "Connected: " + formatNodeRef(session, fromNodeId) + " → " + formatNodeRef(session, toNodeId));

        if (condition != null) {
            result.put("condition", condition);
        }

        // Show current connections from source
        List<Map<String, Object>> outgoing = session.getOutgoingConnections(fromNodeId);
        if (outgoing.size() > 1) {
            result.put("all_connections_from_source", outgoing.stream()
                .map(e -> formatConnectionInfo(session, e))
                .toList());
        }

        result.put("tip", "Use workflow(action='disconnect', from='" + fromRef + "', to='" + toRef + "') to remove this connection.");

        return ToolExecutionResult.success(result);
    }

    /**
     * Disconnect two nodes.
     * Accepts both from/to and source/target parameter names.
     */
    public ToolExecutionResult executeDisconnect(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // Accept both from/to and source/target (for backward compatibility)
        String fromRef = (String) parameters.get("from");
        if (fromRef == null || fromRef.isBlank()) {
            fromRef = (String) parameters.get("source");
        }
        String toRef = (String) parameters.get("to");
        if (toRef == null || toRef.isBlank()) {
            toRef = (String) parameters.get("target");
        }

        if (fromRef == null || fromRef.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "'from' parameter is required.");
        }
        if (toRef == null || toRef.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "'to' parameter is required.");
        }

        String fromNodeId = session.resolveNodeReference(fromRef);
        String toNodeId = session.resolveNodeReference(toRef);

        // Store edge data before removal for undo
        // Use prefix matching on 'from' to find port-qualified edges (e.g., core:fork:branch_0)
        Optional<Map<String, Object>> existingEdge = session.getEdges().stream()
            .filter(e -> {
                String from = (String) e.get("from");
                boolean fromMatches = fromNodeId.equals(from) || (from != null && from.startsWith(fromNodeId + ":"));
                String to = (String) e.get("to");
                if (to == null) to = (String) e.get("target");
                return fromMatches && toNodeId.equals(to);
            })
            .findFirst();

        if (existingEdge.isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "Connection not found: " + fromRef + " → " + toRef);
        }

        // Remove connection
        session.removeConnection(fromNodeId, toNodeId);

        // Record action for undo
        session.recordAction("disconnect", null, "edge", new LinkedHashMap<>(existingEdge.get()));
        session.clearRedoStack();

        sessionStore.save(session);

        // Build response with warnings
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("message", "Disconnected: " + formatNodeRef(session, fromNodeId) + " ✕ " + formatNodeRef(session, toNodeId));

        // Check for resulting issues
        List<String> warnings = new ArrayList<>();
        List<String> hints = new ArrayList<>();

        // Check if source now has no outgoing
        if (session.getOutgoingConnections(fromNodeId).isEmpty()) {
            warnings.add(formatNodeRef(session, fromNodeId) + " now has no outgoing connections");
            hints.add("workflow(action='connect', from='" + fromRef + "', to='...')");
        }

        // Check if target now has no incoming (orphan)
        if (!toNodeId.startsWith("trigger:") && session.getIncomingConnections(toNodeId).isEmpty()) {
            warnings.add(formatNodeRef(session, toNodeId) + " is now an orphan (no incoming connections)");
            hints.add("workflow(action='connect', from='...', to='" + toRef + "')");
            hints.add("workflow(action='remove', node='" + toRef + "') if no longer needed");
        }

        if (!warnings.isEmpty()) {
            result.put("warnings", warnings);
            result.put("suggested_actions", hints);
        }

        return ToolExecutionResult.success(result);
    }

    /**
     * List all connections in the workflow.
     */
    public ToolExecutionResult executeListConnections(WorkflowBuilderSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("workflow", session.getWorkflowName());
        result.put("total_connections", session.getEdges().size());

        // Group by source node
        Map<String, List<Map<String, Object>>> connectionsBySource = new LinkedHashMap<>();

        for (Map<String, Object> edge : session.getEdges()) {
            String from = (String) edge.get("from");
            String fromRef = formatNodeRef(session, from);

            connectionsBySource.computeIfAbsent(fromRef, k -> new ArrayList<>())
                .add(formatConnectionInfo(session, edge));
        }

        result.put("connections", connectionsBySource);

        // Visual representation
        StringBuilder visual = new StringBuilder();
        visual.append("Connections:\n");
        for (Map.Entry<String, List<Map<String, Object>>> entry : connectionsBySource.entrySet()) {
            visual.append("\n").append(entry.getKey()).append("\n");
            for (Map<String, Object> conn : entry.getValue()) {
                String target = (String) conn.get("to");
                String condition = (String) conn.get("condition");
                if (condition != null) {
                    visual.append("  → ").append(target).append(" [when: ").append(condition).append("]\n");
                } else {
                    visual.append("  → ").append(target).append("\n");
                }
            }
        }
        result.put("visual", visual.toString());

        return ToolExecutionResult.success(result);
    }

    /**
     * Find disconnected nodes (orphans and dead ends).
     */
    public ToolExecutionResult executeGetDisconnected(WorkflowBuilderSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("workflow", session.getWorkflowName());

        List<String> orphans = session.findOrphanNodes();
        List<String> deadEnds = session.findDeadEndNodes();

        boolean hasIssues = !orphans.isEmpty() || !deadEnds.isEmpty();

        if (!hasIssues) {
            result.put("message", "All nodes are properly connected.");
            result.put("orphans", List.of());
            result.put("dead_ends", List.of());
            return ToolExecutionResult.success(result);
        }

        // Format orphans with hints
        if (!orphans.isEmpty()) {
            List<Map<String, Object>> orphanDetails = new ArrayList<>();
            for (String nodeId : orphans) {
                Map<String, Object> detail = new LinkedHashMap<>();
                String logicalId = session.getLogicalIdOrFail(nodeId);
                detail.put("node", formatNodeRef(session, nodeId));
                detail.put("problem", "No incoming connections - never reached");
                detail.put("hint", "workflow(action='connect', from='<source label>', to=" + logicalId + ")");
                orphanDetails.add(detail);
            }
            result.put("orphans", orphanDetails);
        } else {
            result.put("orphans", List.of());
        }

        // Format dead ends with hints
        if (!deadEnds.isEmpty()) {
            List<Map<String, Object>> deadEndDetails = new ArrayList<>();
            for (String nodeId : deadEnds) {
                Map<String, Object> detail = new LinkedHashMap<>();
                String logicalId = session.getLogicalIdOrFail(nodeId);
                String ref = formatNodeRef(session, nodeId);
                detail.put("node", ref);
                detail.put("problem", "No outgoing connections - dead end");
                detail.put("hint", "workflow(action='connect', from=" + logicalId + ", to='<target label>') OR this is intentional end");
                deadEndDetails.add(detail);
            }
            result.put("dead_ends", deadEndDetails);
        } else {
            result.put("dead_ends", List.of());
        }

        result.put("summary", String.format("%d orphan(s), %d dead end(s) found", orphans.size(), deadEnds.size()));

        return ToolExecutionResult.success(result);
    }

    // ==================== Helper Methods ====================

    private String formatNodeRef(WorkflowBuilderSession session, String nodeId) {
        // Use session's formatNodeRefWithLabel which uses getLogicalIdOrFail
        return session.formatNodeRefWithLabel(nodeId);
    }

    /** Reads an edge's target, tolerating both the V2 'to' key and the legacy 'target' key. */
    private static String edgeTarget(Map<String, Object> edge) {
        String to = (String) edge.get("to");
        return to != null ? to : (String) edge.get("target");
    }

    private String formatAvailableNodes(WorkflowBuilderSession session) {
        List<String> formatted = new ArrayList<>();
        for (String nodeId : session.getAllNodeIds()) {
            // Always use formatNodeRef to ensure consistent format: "Label" (type)
            formatted.add(session.formatNodeRef(nodeId, true));
        }
        return String.join(", ", formatted);
    }

    private Map<String, Object> formatConnectionInfo(WorkflowBuilderSession session, Map<String, Object> edge) {
        Map<String, Object> info = new LinkedHashMap<>();
        String to = (String) edge.get("to");
        if (to == null) to = (String) edge.get("target");

        // V2: All edges should have a 'to' field (simple from/to format)
        if (to != null) {
            info.put("to", formatNodeRef(session, to));
        } else {
            info.put("to", "(disconnected edge - missing target)");
        }

        String condition = (String) edge.get("condition");
        if (condition != null) {
            info.put("condition", condition);
        }

        return info;
    }

    /**
     * Auto-assign port qualifier for branching nodes (fork, decision, switch, classify).
     * When an edge comes from a branching node without a port qualifier,
     * assigns the next available branch port based on existing outgoing edges.
     * This ensures the frontend can route edges to the correct handles.
     *
     * Port formats: fork=branch_N, decision=if/elseif_N/else, switch=case_N/default,
     *               option=choice_N, classify=category_N, guardrail=pass/fail
     *
     * @param session The workflow builder session
     * @param fromNodeId The source node ID (may or may not have port)
     * @param toNodeId The target node ID (unused, reserved for future use)
     * @return The fromNodeId with port qualifier if applicable, or unchanged
     */
    @SuppressWarnings("unchecked")
    private String autoAssignBranchPort(WorkflowBuilderSession session, String fromNodeId, String toNodeId) {
        // Skip if already has a port qualifier
        if (fromNodeId.contains(":") && fromNodeId.chars().filter(c -> c == ':').count() >= 2) {
            return fromNodeId; // e.g., core:my_fork:branch_0 already has port
        }

        // Apply to core: nodes (fork, decision, switch) and agent: nodes (classify)
        if (!fromNodeId.startsWith("core:") && !fromNodeId.startsWith("agent:")) {
            return fromNodeId;
        }

        // Find the core node in the session
        for (Map<String, Object> core : session.getCores()) {
            String coreId = (String) core.get("id");
            if (!fromNodeId.equals(coreId)) continue;

            String type = (String) core.get("type");
            if (type == null) continue;

            if ("fork".equals(type)) {
                // Fork: assign branch_N based on existing outgoing edges
                List<Map<String, Object>> forkOutputs = (List<Map<String, Object>>) core.get("forkOutputs");
                int branchCount = forkOutputs != null ? forkOutputs.size() : 0;
                List<Map<String, Object>> existing = session.getOutgoingConnections(fromNodeId);
                int nextBranch = existing.size();
                if (nextBranch < branchCount) {
                    String ported = fromNodeId + ":branch_" + nextBranch;
                    log.info("[CONNECT] Auto-assigned fork port: {} → {}", fromNodeId, ported);
                    return ported;
                }
                // If all branches used, still add the port (overflow)
                String ported = fromNodeId + ":branch_" + nextBranch;
                log.warn("[CONNECT] Fork port overflow: {} has {} branches but connecting branch_{}", fromNodeId, branchCount, nextBranch);
                return ported;
            }

            if ("decision".equals(type)) {
                // Decision: assign if, elseif_N, else based on conditions and existing edges
                List<Map<String, Object>> conditions = (List<Map<String, Object>>) core.get("decisionConditions");
                if (conditions == null || conditions.isEmpty()) break;

                List<Map<String, Object>> existing = session.getOutgoingConnections(fromNodeId);
                int nextIdx = existing.size();

                if (nextIdx == 0) {
                    String ported = fromNodeId + ":if";
                    log.info("[CONNECT] Auto-assigned decision port: {} → {}", fromNodeId, ported);
                    return ported;
                }

                // Check if the next condition is the last one (potential "else")
                if (nextIdx < conditions.size()) {
                    Map<String, Object> cond = conditions.get(nextIdx);
                    String condType = (String) cond.get("type");
                    if ("else".equals(condType)) {
                        String ported = fromNodeId + ":else";
                        log.info("[CONNECT] Auto-assigned decision port: {} → {}", fromNodeId, ported);
                        return ported;
                    }
                    // elseif
                    String ported = fromNodeId + ":elseif_" + (nextIdx - 1);
                    log.info("[CONNECT] Auto-assigned decision port: {} → {}", fromNodeId, ported);
                    return ported;
                }

                // Overflow: more connections than conditions - auto-expand
                String newPort = DecisionNodeCreator.expandDecisionConditions(core, conditions, nextIdx);
                log.info("[CONNECT] Auto-expanded decision conditions: {} → {}:{}", fromNodeId, fromNodeId, newPort);
                return fromNodeId + ":" + newPort;
            }

            if ("switch".equals(type)) {
                // Switch: assign case_N or default based on switch cases and existing edges
                List<Map<String, Object>> switchCases = (List<Map<String, Object>>) core.get("switchCases");
                if (switchCases == null || switchCases.isEmpty()) break;

                List<Map<String, Object>> existing = session.getOutgoingConnections(fromNodeId);
                int nextIdx = existing.size();

                if (nextIdx < switchCases.size()) {
                    Map<String, Object> switchCase = switchCases.get(nextIdx);
                    String caseType = (String) switchCase.get("type");
                    if ("default".equals(caseType)) {
                        String ported = fromNodeId + ":default";
                        log.info("[CONNECT] Auto-assigned switch port: {} → {}", fromNodeId, ported);
                        return ported;
                    }
                    String ported = fromNodeId + ":case_" + nextIdx;
                    log.info("[CONNECT] Auto-assigned switch port: {} → {}", fromNodeId, ported);
                    return ported;
                }
            }

            if ("option".equals(type)) {
                // Option: assign choice_N based on option choices and existing edges
                List<Map<String, Object>> optionChoices = (List<Map<String, Object>>) core.get("optionChoices");
                if (optionChoices == null || optionChoices.isEmpty()) break;

                List<Map<String, Object>> existing = session.getOutgoingConnections(fromNodeId);
                int nextIdx = existing.size();

                if (nextIdx < optionChoices.size()) {
                    String ported = fromNodeId + ":choice_" + nextIdx;
                    log.info("[CONNECT] Auto-assigned option port: {} → {}", fromNodeId, ported);
                    return ported;
                }
                // Overflow
                String ported = fromNodeId + ":choice_" + nextIdx;
                log.warn("[CONNECT] Option port overflow: {} has {} choices but connecting choice_{}", fromNodeId, optionChoices.size(), nextIdx);
                return ported;
            }

            break; // Found the core node, no need to continue
        }

        // Check agent: nodes (classify, guardrail) - stored in mcps
        if (fromNodeId.startsWith("agent:")) {
            for (Map<String, Object> mcp : session.getMcps()) {
                String label = (String) mcp.get("label");
                if (label == null) continue;

                String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
                String expectedNodeId = "agent:" + normalizedLabel;
                if (!fromNodeId.equals(expectedNodeId)) continue;

                // Found matching mcp node - check if it's classify or guardrail
                if (Boolean.TRUE.equals(mcp.get("isClassify"))) {
                    List<Map<String, Object>> classifyOutputs = (List<Map<String, Object>>) mcp.get("classifyOutputs");
                    if (classifyOutputs == null || classifyOutputs.isEmpty()) break;

                    List<Map<String, Object>> existing = session.getOutgoingConnections(fromNodeId);
                    int nextIdx = existing.size();

                    if (nextIdx < classifyOutputs.size()) {
                        String ported = fromNodeId + ":category_" + nextIdx;
                        log.info("[CONNECT] Auto-assigned classify port: {} → {}", fromNodeId, ported);
                        return ported;
                    }
                    // Overflow
                    String ported = fromNodeId + ":category_" + nextIdx;
                    log.warn("[CONNECT] Classify port overflow: {} has {} categories but connecting category_{}", fromNodeId, classifyOutputs.size(), nextIdx);
                    return ported;
                }

                if (Boolean.TRUE.equals(mcp.get("isGuardrail"))) {
                    List<Map<String, Object>> guardrailOutputs = (List<Map<String, Object>>) mcp.get("guardrailOutputs");
                    if (guardrailOutputs == null || guardrailOutputs.isEmpty()) break;

                    List<Map<String, Object>> existing = session.getOutgoingConnections(fromNodeId);
                    int nextIdx = existing.size();

                    if (nextIdx == 0) {
                        String ported = fromNodeId + ":pass";
                        log.info("[CONNECT] Auto-assigned guardrail port: {} → {}", fromNodeId, ported);
                        return ported;
                    }
                    String ported = fromNodeId + ":fail";
                    log.info("[CONNECT] Auto-assigned guardrail port: {} → {}", fromNodeId, ported);
                    return ported;
                }

                break; // Found matching node but it's not classify or guardrail
            }
        }

        return fromNodeId; // No port assignment needed
    }

    /**
     * Auto-assign :iterate port when connecting TO a loop node without a port qualifier.
     * A loop has two target handles: entry (no port) and loop-back (:iterate).
     * If the loop already has an incoming entry edge, the new edge is the loop-back.
     */
    private String autoAssignLoopTargetPort(WorkflowBuilderSession session, String toNodeId) {
        // Skip if already has a port qualifier (e.g., core:my_loop:iterate)
        if (toNodeId.contains(":") && toNodeId.chars().filter(c -> c == ':').count() >= 2) {
            return toNodeId;
        }

        if (!toNodeId.startsWith("core:")) {
            return toNodeId;
        }

        for (Map<String, Object> core : session.getCores()) {
            String coreId = (String) core.get("id");
            if (!toNodeId.equals(coreId)) continue;

            String type = (String) core.get("type");
            if (!"loop".equals(type)) break;

            // Check if the loop already has an incoming entry edge (edge without :iterate port)
            List<Map<String, Object>> incoming = session.getIncomingConnections(toNodeId);
            if (!incoming.isEmpty()) {
                String ported = toNodeId + ":iterate";
                log.info("[CONNECT] Auto-assigned loop iterate port: {} → {}", toNodeId, ported);
                return ported;
            }

            break;
        }

        return toNodeId;
    }
}
