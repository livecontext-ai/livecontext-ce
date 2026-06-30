package com.apimarketplace.orchestrator.tools.workflow.builder.viewer;

import com.apimarketplace.orchestrator.tools.workflow.builder.NodeDescriptionBuilder;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Builds visual flow representations of workflows.
 * Creates human-readable flow diagrams from workflow sessions.
 */
@Component
public class FlowRepresentationBuilder {

    private final NodeDescriptionBuilder nodeDescriptionBuilder;

    public FlowRepresentationBuilder(NodeDescriptionBuilder nodeDescriptionBuilder) {
        this.nodeDescriptionBuilder = nodeDescriptionBuilder;
    }

    /**
     * Builds a flow representation starting from triggers.
     *
     * @param session The workflow builder session
     * @return List of flow nodes in traversal order
     */
    public List<Map<String, Object>> buildFlowRepresentation(WorkflowBuilderSession session) {
        List<Map<String, Object>> flow = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        for (Map<String, Object> trigger : session.getTriggers()) {
            String label = (String) trigger.get("label");
            String nodeId = "trigger:" + WorkflowBuilderSession.normalizeLabel(label);
            String logicalId = session.getLogicalIdOrFail(nodeId);

            String triggerType = (String) trigger.getOrDefault("type", "datasource");
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("ref", logicalId);
            node.put("type", "TRIGGER");
            node.put("label", label);
            node.put("trigger_type", "datasource".equals(triggerType) ? "table" : triggerType);

            List<String> interfaces = session.getLinkedInterfaces(nodeId);
            if (!interfaces.isEmpty()) {
                node.put("interfaces", interfaces);
            }

            flow.add(node);
            visited.add(nodeId);

            addConnectedNodesToFlow(session, nodeId, flow, visited, 0);
        }

        return flow;
    }

    /**
     * Recursively adds connected nodes to the flow representation.
     */
    private void addConnectedNodesToFlow(WorkflowBuilderSession session, String fromNodeId,
                                         List<Map<String, Object>> flow, Set<String> visited, int depth) {
        if (depth > 20) return;

        List<Map<String, Object>> outgoing = session.getOutgoingConnections(fromNodeId);

        for (Map<String, Object> edge : outgoing) {
            String toNodeId = (String) edge.get("to");
            if (toNodeId == null) toNodeId = (String) edge.get("target");
            if (toNodeId == null || visited.contains(toNodeId)) continue;

            visited.add(toNodeId);

            Optional<Map<String, Object>> nodeOpt = session.findNode(toNodeId);
            if (nodeOpt.isPresent()) {
                Map<String, Object> nodeData = nodeOpt.get();
                String label = (String) nodeData.get("label");
                String logicalId = session.getLogicalIdOrFail(toNodeId);

                Map<String, Object> node = new LinkedHashMap<>();
                node.put("ref", logicalId);
                node.put("type", nodeDescriptionBuilder.getNodeTypeLabel(toNodeId));
                node.put("label", label);

                String condition = (String) edge.get("condition");
                if (condition != null) {
                    node.put("condition", condition);
                }

                if (nodeData.containsKey("tool_id")) {
                    node.put("tool_id", nodeData.get("tool_id"));
                }

                List<String> interfaces = session.getLinkedInterfaces(toNodeId);
                if (!interfaces.isEmpty()) {
                    node.put("interfaces", interfaces);
                }

                flow.add(node);
                addConnectedNodesToFlow(session, toNodeId, flow, visited, depth + 1);
            }
        }
    }
}
