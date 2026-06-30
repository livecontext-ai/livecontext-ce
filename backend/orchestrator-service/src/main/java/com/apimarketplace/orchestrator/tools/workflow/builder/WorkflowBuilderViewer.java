package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.tools.workflow.builder.viewer.FlowRepresentationBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import com.apimarketplace.agent.tools.ToolErrorCode;

/**
 * Handles viewing/inspection actions for workflow builder.
 * Actions: describe, validate
 *
 * Returns human-readable views, never raw JSON plan structure.
 * Delegates to specialized components:
 * - {@link NodeDescriptionBuilder} for node descriptions
 * - {@link FlowRepresentationBuilder} for flow visualization
 * - {@link WorkflowBuilderValidator} - single source of truth for validation
 *   (used by the validate action, finish guard, and save guard)
 */
@Slf4j
@Component
public class WorkflowBuilderViewer {

    private final NodeDescriptionBuilder nodeDescriptionBuilder;
    private final FlowRepresentationBuilder flowRepresentationBuilder;
    private final WorkflowBuilderValidator workflowBuilderValidator;
    private final ResponseContextBuilder responseContextBuilder;
    private final AgentWorkflowFireService agentFireService;

    public WorkflowBuilderViewer(
            NodeDescriptionBuilder nodeDescriptionBuilder,
            FlowRepresentationBuilder flowRepresentationBuilder,
            WorkflowBuilderValidator workflowBuilderValidator,
            ResponseContextBuilder responseContextBuilder,
            AgentWorkflowFireService agentFireService) {
        this.nodeDescriptionBuilder = nodeDescriptionBuilder;
        this.flowRepresentationBuilder = flowRepresentationBuilder;
        this.workflowBuilderValidator = workflowBuilderValidator;
        this.responseContextBuilder = responseContextBuilder;
        this.agentFireService = agentFireService;
    }

    /**
     * Get a visual summary of the workflow.
     * Returns human-readable flow, not JSON.
     */
    public ToolExecutionResult executeGetSummary(WorkflowBuilderSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("workflow_name", session.getWorkflowName());
        result.put("workflow_description", session.getWorkflowDescription());

        // Status
        String status = session.getLoadedWorkflowId() != null ?
            "EDITING (id: " + session.getLoadedWorkflowId() + ")" : "NEW";
        result.put("status_label", status);

        // Counts
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("triggers", session.getTriggers().size());
        counts.put("mcps", session.getMcps().size());
        counts.put("cores", session.getCores().size());
        counts.put("connections", session.getEdges().size());

        int interfaceCount = session.getLinkedInterfaces().values().stream().mapToInt(List::size).sum();
        counts.put("interfaces_linked", interfaceCount);
        result.put("counts", counts);

        // Build flow representation
        List<Map<String, Object>> flow = flowRepresentationBuilder.buildFlowRepresentation(session);
        result.put("flow", flow);

        // Visual text representation
        result.put("visual", session.buildVisualSummary());

        // Issues summary
        List<String> orphans = session.findOrphanNodes();
        if (!orphans.isEmpty()) {
            result.put("issues", Map.of(
                "orphan_nodes", orphans.size(),
                "message", orphans.size() + " node(s) have no incoming connections",
                "action", "Use workflow(action='validate') for details"
            ));
        }

        // Trigger execute schemas (so agent knows what data_inputs to provide)
        if (!session.getTriggers().isEmpty()) {
            Map<String, Object> executeInfo = agentFireService.buildTriggerExecuteInfo(
                    session.getTriggers(), session.getLoadedWorkflowId());
            if (executeInfo != null) {
                result.put("execute_info", executeInfo);
            }
        }

        // Available actions
        List<String> availableActions = new ArrayList<>();
        if (session.getTriggers().isEmpty()) {
            availableActions.add("action='add_node', type='form|table|schedule|webhook|chat|manual' - Add trigger entry point");
        } else {
            availableActions.add("action='add_node', type='<tool-uuid>' - Add processing step (UUID from catalog(action='search'))");
            availableActions.add("action='add_node', type='decision' - Add branching");
            availableActions.add("action='add_node', type='loop' - Add iteration");
        }
        if (!session.getMcps().isEmpty()) {
            availableActions.add("create - Finalize workflow");
        }
        availableActions.add("validate - Check for errors");
        result.put("available_actions", availableActions);

        return ToolExecutionResult.success(result);
    }

    /**
     * Describe a specific node in detail.
     */
    public ToolExecutionResult executeDescribe(WorkflowBuilderSession session, Map<String, Object> parameters) {
        String nodeRef = (String) parameters.get("node");
        if (nodeRef == null) nodeRef = (String) parameters.get("node_id");

        if (nodeRef == null || nodeRef.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "'node' parameter is required. Available: " + formatAvailableNodes(session));
        }

        String nodeId = session.resolveNodeReference(nodeRef);
        Optional<Map<String, Object>> nodeOpt = session.findNode(nodeId);

        if (nodeOpt.isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "Node not found: " + nodeRef + ". Available: " + formatAvailableNodes(session));
        }

        Map<String, Object> node = nodeOpt.get();
        String logicalId = session.getLogicalIdOrFail(nodeId);
        String label = (String) node.get("label");
        String type = nodeDescriptionBuilder.getNodeTypeLabel(nodeId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("node", logicalId);
        result.put("full_id", nodeId);
        result.put("label", label);
        result.put("type", type);

        // Build type-specific description using NodeDescriptionBuilder
        NodeDescriptionBuilder.DescriptionResult desc = nodeDescriptionBuilder.buildDescription(nodeId, node, session.getTenantId());

        // Expose node config so LLM can see current values (prompt, tool_id, conditions, mappings, etc.)
        if (!desc.config().isEmpty()) {
            result.put("config", desc.config());
        }

        // Expose modifiable fields so LLM knows what can be changed and with which param keys
        if (!desc.modifiableFields().isEmpty()) {
            Map<String, Object> modFields = new LinkedHashMap<>();
            for (Map.Entry<String, NodeDescriptionBuilder.ModifiableField> entry : desc.modifiableFields().entrySet()) {
                NodeDescriptionBuilder.ModifiableField field = entry.getValue();
                if (field.currentValue() != null) {
                    modFields.put(entry.getKey(), Map.of(
                        "current_value", field.currentValue(),
                        "param_key", field.paramKey(),
                        "description", field.description()
                    ));
                }
            }
            if (!modFields.isEmpty()) {
                result.put("modifiable_fields", modFields);
            }
        }

        // === TASK #3: Available variables (inputs from predecessors) ===
        Map<String, Object> availableVars = responseContextBuilder.getAccessibleVariables(session, nodeId);
        if (!availableVars.isEmpty()) {
            result.put("available_variables", availableVars);
        }


        // === TASK #4: Outputs provided by this node ===
        Map<String, String> outputs = buildNodeOutputs(nodeId, label, session);
        if (!outputs.isEmpty()) {
            result.put("outputs", outputs);
        }

        // Webhook URL for webhook triggers (from standalone webhook, auto-created)
        if (nodeId.startsWith("trigger:") && "webhook".equals(node.get("type"))) {
            String standaloneUrl = (String) node.get("standaloneWebhookUrl");
            if (standaloneUrl != null && !standaloneUrl.isBlank()) {
                result.put("webhook_url", standaloneUrl);
            } else {
                result.put("webhook_url", "(webhook not yet created - re-add trigger or reload workflow)");
            }
        }

        if (desc.warning() != null) {
            result.put("\u26a0\ufe0f_WARNING", desc.warning());
        }

        // Add body nodes for loop/split
        if (nodeId.startsWith("core:")) {
            List<String> bodyNodes = findLoopBodyNodes(session, nodeId);
            if (!bodyNodes.isEmpty()) {
                result.put("body_nodes", bodyNodes);
            }
        }

        // Add modify examples (use label, not logicalId - harmonized with ADD syntax)
        Map<String, String> modifyExamples = nodeDescriptionBuilder.buildModifyExamples(nodeId, label, node, session);
        if (!modifyExamples.isEmpty()) {
            result.put("modify_examples", modifyExamples);
        }

        // Connections
        List<Map<String, Object>> incoming = session.getIncomingConnections(nodeId);
        List<Map<String, Object>> outgoing = session.getOutgoingConnections(nodeId);

        Map<String, Object> connections = new LinkedHashMap<>();
        if (!incoming.isEmpty()) {
            connections.put("incoming", incoming.stream()
                .map(e -> formatConnectionForDescribe(session, e, true))
                .toList());
        }
        if (!outgoing.isEmpty()) {
            connections.put("outgoing", outgoing.stream()
                .map(e -> formatConnectionForDescribe(session, e, false))
                .toList());
        }
        if (!connections.isEmpty()) {
            result.put("connections", connections);
        }

        // Linked interfaces
        List<String> interfaces = session.getLinkedInterfaces(nodeId);
        if (!interfaces.isEmpty()) {
            result.put("linked_interfaces", interfaces);
        }

        // Help reference
        if (desc.helpTopic() != null) {
            result.put("help", "workflow(action='help', topics=['" + desc.helpTopic() + "'])");
        }

        // Modification hints (use label for node identification - harmonized with ADD syntax)
        result.put("actions", Map.of(
            "modify", "workflow(action='modify', node='" + label + "', params={...})",
            "remove", "workflow(action='remove', node='" + label + "')"
        ));

        return ToolExecutionResult.success(result);
    }

    /**
     * List all nodes in the workflow.
     */
    public ToolExecutionResult executeListNodes(WorkflowBuilderSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("workflow", session.getWorkflowName());

        List<Map<String, Object>> nodes = new ArrayList<>();

        // Triggers
        for (Map<String, Object> trigger : session.getTriggers()) {
            String label = (String) trigger.get("label");
            String nodeId = "trigger:" + WorkflowBuilderSession.normalizeLabel(label);
            String logicalId = session.getLogicalIdOrFail(nodeId);

            String triggerType = (String) trigger.getOrDefault("type", "datasource");
            nodes.add(Map.of(
                "ref", logicalId,
                "type", "TRIGGER",
                "label", label,
                "trigger_type", "datasource".equals(triggerType) ? "table" : triggerType
            ));
        }

        // Steps
        for (Map<String, Object> step : session.getMcps()) {
            String label = (String) step.get("label");
            boolean isAgent = Boolean.TRUE.equals(step.get("isAgent"));
            String prefix = isAgent ? "agent:" : "mcp:";
            String nodeId = prefix + WorkflowBuilderSession.normalizeLabel(label);
            String logicalId = session.getLogicalIdOrFail(nodeId);

            Map<String, Object> nodeInfo = new LinkedHashMap<>();
            nodeInfo.put("ref", logicalId);
            nodeInfo.put("type", isAgent ? "AGENT" : "STEP");
            nodeInfo.put("label", label);
            if (step.containsKey("tool_id")) {
                nodeInfo.put("tool_id", step.get("tool_id"));
            }
            nodes.add(nodeInfo);
        }

        // Control nodes
        for (Map<String, Object> cn : session.getCores()) {
            String label = (String) cn.get("label");
            String type = (String) cn.get("type");
            String nodeId = type + ":" + WorkflowBuilderSession.normalizeLabel(label);
            String logicalId = session.getLogicalIdOrFail(nodeId);

            nodes.add(Map.of(
                "ref", logicalId,
                "type", type.toUpperCase(),
                "label", label
            ));
        }

        result.put("nodes", nodes);
        result.put("count", nodes.size());

        return ToolExecutionResult.success(result);
    }

    /**
     * List all linked interfaces.
     */
    public ToolExecutionResult executeListInterfaces(WorkflowBuilderSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("workflow", session.getWorkflowName());

        Map<String, List<String>> allLinked = session.getAllLinkedInterfaces();

        if (allLinked.isEmpty()) {
            result.put("message", "No interfaces linked to any nodes.");
            result.put("linked_interfaces", Map.of());
            return ToolExecutionResult.success(result);
        }

        // Format with logical references
        Map<String, Object> formatted = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : allLinked.entrySet()) {
            String nodeId = entry.getKey();
            String ref = formatNodeRef(session, nodeId);
            formatted.put(ref, entry.getValue());
        }

        result.put("linked_interfaces", formatted);
        result.put("total_interfaces", allLinked.values().stream().mapToInt(List::size).sum());

        return ToolExecutionResult.success(result);
    }

    /**
     * Get workflow errors and warnings. Runs the unified validator chain
     * (sub-validators + legacy WorkflowErrorChecker) so {@code validate},
     * {@code finish}, and {@code save} all produce the same verdict.
     */
    public ToolExecutionResult executeGetErrors(WorkflowBuilderSession session) {
        WorkflowBuilderValidator.ValidationResult validation = workflowBuilderValidator.validate(session);
        Map<String, Object> agent = workflowBuilderValidator.toAgentFormat(validation);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("workflow", session.getWorkflowName());
        result.put("errors", agent.get("errors"));
        result.put("warnings", agent.get("warnings"));
        result.put("error_count", agent.get("error_count"));
        result.put("warning_count", agent.get("warning_count"));
        result.put("message", agent.get("message"));
        result.put("can_create", agent.get("can_create"));

        return ToolExecutionResult.success(result);
    }

    /**
     * Validate workflow and return detailed results.
     */
    public ToolExecutionResult executeValidate(WorkflowBuilderSession session) {
        ToolExecutionResult errorsResult = executeGetErrors(session);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) errorsResult.data();

        boolean canCreate = (boolean) result.getOrDefault("can_create", false);

        if (canCreate) {
            result.put("validation_result", "PASSED");
            result.put("next_action", "workflow(action='finish') to finalize and save (closes the build session)");
        } else {
            result.put("validation_result", "FAILED");
            result.put("next_action", "Fix errors listed above, then workflow(action='validate') again");
        }

        return ToolExecutionResult.success(result);
    }

    // ==================== Helper Methods ====================

    private String formatNodeRef(WorkflowBuilderSession session, String nodeId) {
        return session.formatNodeRefWithLabel(nodeId);
    }

    private String formatAvailableNodes(WorkflowBuilderSession session) {
        List<String> formatted = new ArrayList<>();
        for (String nodeId : session.getAllNodeIds()) {
            formatted.add(session.formatNodeRef(nodeId, true));
        }
        return String.join(", ", formatted);
    }

    private List<String> findLoopBodyNodes(WorkflowBuilderSession session, String loopNodeId) {
        List<String> bodyNodes = new ArrayList<>();
        Map<String, String> nodeParentLoop = session.getNodeParentLoop();

        for (Map.Entry<String, String> entry : nodeParentLoop.entrySet()) {
            if (loopNodeId.equals(entry.getValue())) {
                String nodeId = entry.getKey();
                String logicalId = session.getLogicalIdOrFail(nodeId);
                bodyNodes.add(logicalId);
            }
        }

        return bodyNodes;
    }

    private Map<String, Object> formatConnectionForDescribe(WorkflowBuilderSession session,
                                                            Map<String, Object> edge, boolean isIncoming) {
        Map<String, Object> conn = new LinkedHashMap<>();

        if (isIncoming) {
            String from = (String) edge.get("from");
            conn.put("from", from != null ? formatNodeRef(session, from) : "(null)");
        } else {
            String to = (String) edge.get("to");
            if (to == null) to = (String) edge.get("target");
            conn.put("to", to != null ? formatNodeRef(session, to) : "(null - disconnected edge)");
        }

        String condition = (String) edge.get("condition");
        if (condition != null) {
            conn.put("condition", condition);
        }

        return conn;
    }

    /**
     * Build outputs map for a node based on its type.
     * Only returns specific outputs from schema - no generic placeholders.
     */
    private Map<String, String> buildNodeOutputs(String nodeId, String label, WorkflowBuilderSession session) {
        Map<String, String> outputs = new LinkedHashMap<>();
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);

        // Check schema first (for MCP/trigger steps with tool schemas)
        WorkflowBuilderSession.NodeSchema schema = session.getNodeSchemas().get(nodeId);
        if (schema != null && schema.getReferenceSyntax() != null && !schema.getReferenceSyntax().isEmpty()) {
            outputs.putAll(schema.getReferenceSyntax());
            return outputs;
        }

        // Only core nodes have known outputs without schema
        if (nodeId.startsWith("core:")) {
            Optional<Map<String, Object>> nodeOpt = session.findNode(nodeId);
            if (nodeOpt.isPresent()) {
                Map<String, Object> node = nodeOpt.get();
                String nodeType = (String) node.get("type");

                if ("decision".equals(nodeType)) {
                    outputs.put("selected_branch", "{{core:" + normalizedLabel + ".output.selected_branch}}");
                } else if ("loop".equals(nodeType) || "while".equals(nodeType)) {
                    outputs.put("iteration", "{{core:" + normalizedLabel + ".output.iteration}}");
                } else if ("split".equals(nodeType) || "for_each".equals(nodeType)) {
                    outputs.put("current_item", "{{core:" + normalizedLabel + ".output.current_item}}");
                    outputs.put("current_index", "{{core:" + normalizedLabel + ".output.current_index}}");
                } else if ("switch".equals(nodeType)) {
                    outputs.put("selected_case", "{{core:" + normalizedLabel + ".output.selected_case}}");
                }
            }
        } else if (nodeId.startsWith("agent:")) {
            outputs.put("response", "{{agent:" + normalizedLabel + ".output.response}}");
        }

        return outputs;
    }
}
