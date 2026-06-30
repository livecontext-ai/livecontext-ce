package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;

import java.util.List;
import java.util.Map;

/**
 * End node - Terminal node that marks workflow completion.
 *
 * This node is automatically added as the final node in the execution tree.
 * It has no successors and simply returns success when executed.
 */
public class EndNode extends BaseNode {

    public EndNode(String nodeId) {
        super(nodeId, NodeType.END);
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        Map<String, Object> output = new java.util.HashMap<>();
        output.put("resolved_params", Map.of("status", "end"));
        output.put("status", "completed");
        output.put("message", "Workflow execution completed");
        return NodeExecutionResult.success(nodeId, output);
    }

    @Override
    public List<ExecutionNode> getNextNodes(NodeExecutionResult result) {
        // No successors - this is the terminal node
        return List.of();
    }

    @Override
    public void onComplete(ExecutionContext context, NodeExecutionResult result) {
        // No-op for end node
    }

    /**
     * EndNode is an end node.
     */
    @Override
    public boolean isEndNode() {
        return true;
    }

    /**
     * EndNode skips split handling - it's a terminal node.
     */
    @Override
    public boolean skipsSplitHandling() {
        return true;
    }
}
