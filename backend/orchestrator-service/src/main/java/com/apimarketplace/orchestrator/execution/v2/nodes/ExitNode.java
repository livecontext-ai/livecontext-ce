package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Exit node - Ends execution along this branch.
 *
 * Flow:
 * 1. Mark this branch as exited
 * 2. Return empty successors (no downstream execution on this path)
 *
 * Important: Exit only affects the current branch. Other parallel branches
 * (fork, split) continue normally. The workflow completes when all branches finish.
 *
 * Usage:
 * - End a branch early when a condition is met
 * - Exit from error conditions on one path
 * - Terminate one branch of a fork/split without affecting others
 */
public class ExitNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(ExitNode.class);

    private final String reason;

    public ExitNode(String nodeId, String reason) {
        super(nodeId, NodeType.EXIT);
        this.reason = reason != null ? reason : "Branch exited";
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        logger.info("🚪 Exit node executing: nodeId={}, reason={}, itemId={}",
            nodeId, reason, context.itemId());

        Map<String, Object> resolvedParams = new java.util.LinkedHashMap<>();
        resolvedParams.put("reason", reason);

        Map<String, Object> result = new java.util.HashMap<>();
        result.put("resolved_params", resolvedParams);
        result.put("node_type", "EXIT");
        result.put("exited", true);
        result.put("reason", reason);
        result.put("item_index", context.itemIndex());
        result.put("item_id", context.itemId());

        logger.info("✅ Branch exited: nodeId={}, reason={}", nodeId, reason);
        return NodeExecutionResult.success(nodeId, result);
    }

    /**
     * Exit node returns NO successors - branch ends here.
     */
    @Override
    public List<ExecutionNode> getNextNodes(NodeExecutionResult result) {
        // No successors - branch ends at exit node
        return List.of();
    }

    /**
     * ExitNode is an exit node.
     */
    @Override
    public boolean isExitNode() {
        return true;
    }

    public String getReason() {
        return reason;
    }

    public static class Builder {
        private String nodeId;
        private String reason;

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public ExitNode build() {
            return new ExitNode(nodeId, reason);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
