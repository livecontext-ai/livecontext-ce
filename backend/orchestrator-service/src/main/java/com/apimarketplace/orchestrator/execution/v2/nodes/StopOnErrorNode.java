package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * StopOnError node - immediately stops the ENTIRE workflow.
 *
 * Unlike ExitNode (which ends only the current branch), this node terminates
 * ALL execution across ALL parallel branches (fork, split). It throws
 * {@link WorkflowStoppedException} which propagates up through the execution
 * tree, cancelling all parallel futures and marking the run as FAILED.
 *
 * Flow:
 * 1. Build failure output with error message and optional error code
 * 2. Return failure result (persisted by executeNodeCore)
 * 3. Engine detects isStopOnErrorNode() and throws WorkflowStoppedException
 *
 * Usage:
 * - Hard-stop workflow when a critical error condition is detected
 * - Abort all processing with a specific error code
 * - Terminate entire workflow (not just one branch) on failure
 */
public class StopOnErrorNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(StopOnErrorNode.class);

    private final Core.StopOnErrorConfig config;

    public StopOnErrorNode(String nodeId, Core.StopOnErrorConfig config) {
        super(nodeId, NodeType.STOP_ON_ERROR);
        this.config = config;
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        String errorMessage = config != null ? config.errorMessage() : "Workflow stopped due to error";
        String errorCode = config != null ? config.errorCode() : null;

        // Resolve templates
        errorMessage = resolveTemplateString(errorMessage, context);
        if (errorCode != null) {
            errorCode = resolveTemplateString(errorCode, context);
        }

        logger.info("StopOnError node executing: nodeId={}, errorMessage={}, errorCode={}, itemId={}",
            nodeId, errorMessage, errorCode, context.itemId());

        Map<String, Object> resolvedParams = new LinkedHashMap<>();
        resolvedParams.put("error_message", errorMessage);
        if (errorCode != null && !errorCode.isBlank()) {
            resolvedParams.put("error_code", errorCode);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("node_type", "STOP_ON_ERROR");
        result.put("resolved_params", resolvedParams);
        result.put("error_message", errorMessage);
        if (errorCode != null && !errorCode.isBlank()) {
            result.put("error_code", errorCode);
        }
        result.put("stopped_at", Instant.now().toString());
        result.put("status", "failed");
        result.put("item_index", context.itemIndex());
        result.put("item_id", context.itemId());

        logger.info("Workflow stopped by StopOnError: nodeId={}, errorMessage={}, errorCode={}",
            nodeId, errorMessage, errorCode);
        return NodeExecutionResult.failureWithOutput(nodeId, errorMessage, result, 0);
    }

    /**
     * StopOnError node returns NO successors - workflow terminates here.
     */
    @Override
    public List<ExecutionNode> getNextNodes(NodeExecutionResult result) {
        return List.of();
    }

    /**
     * This is a StopOnError node - stops the entire workflow.
     * NOT an exit node (exit only ends one branch).
     */
    @Override
    public boolean isStopOnErrorNode() {
        return true;
    }

    public Core.StopOnErrorConfig getConfig() {
        return config;
    }

    public static class Builder {
        private String nodeId;
        private Core.StopOnErrorConfig config;

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder stopOnErrorConfig(Core.StopOnErrorConfig config) {
            this.config = config;
            return this;
        }

        public Builder templateAdapter(Object adapter) {
            return this; // injected via acceptServices
        }

        public StopOnErrorNode build() {
            return new StopOnErrorNode(nodeId, config);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
