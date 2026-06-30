package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;

/**
 * Thrown when a StopOnError node executes, signaling that the entire workflow
 * must stop immediately - including all parallel branches (fork, split).
 *
 * <p>This exception propagates up through the execution tree. Fork parallel
 * execution catches it to cancel remaining branch futures. The top-level
 * execution handler catches it to mark the workflow run as FAILED.
 *
 * <p>Unlike ExitNode (which silently ends one branch), this exception is a
 * hard stop: no further nodes execute anywhere in the workflow.
 */
public class WorkflowStoppedException extends RuntimeException {

    private final String nodeId;
    private final String errorMessage;
    private final String errorCode;
    private final NodeExecutionResult result;

    public WorkflowStoppedException(String nodeId, String errorMessage, String errorCode, NodeExecutionResult result) {
        super("Workflow stopped by StopOnError node: " + nodeId + " - " + errorMessage);
        this.nodeId = nodeId;
        this.errorMessage = errorMessage;
        this.errorCode = errorCode;
        this.result = result;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public NodeExecutionResult getResult() {
        return result;
    }
}
