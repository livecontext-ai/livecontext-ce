package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;

import java.util.Map;
import java.util.Set;

/**
 * Result of executing a single node in step-by-step mode.
 *
 * Contains:
 * - The updated execution context after node completion
 * - The node execution result
 * - Set of ready nodes that can be executed next
 * - Whether the workflow is complete
 *
 * This record is immutable by design (V2 best practice).
 */
public record StepByStepExecutionResult(
    ExecutionContext context,
    NodeExecutionResult nodeResult,
    Set<String> readyNodes,
    boolean workflowComplete
) {
    /**
     * Create a result for successful node execution.
     */
    public static StepByStepExecutionResult success(
            ExecutionContext context,
            NodeExecutionResult nodeResult,
            Set<String> readyNodes) {
        return new StepByStepExecutionResult(
            context,
            nodeResult,
            readyNodes,
            readyNodes.isEmpty()
        );
    }

    /**
     * Create a result indicating workflow completion.
     */
    public static StepByStepExecutionResult completed(ExecutionContext context, NodeExecutionResult nodeResult) {
        return new StepByStepExecutionResult(
            context,
            nodeResult,
            Set.of(),
            true
        );
    }

    /**
     * Create a result for skipped node.
     */
    public static StepByStepExecutionResult skipped(
            ExecutionContext context,
            NodeExecutionResult skipResult,
            Set<String> readyNodes) {
        return new StepByStepExecutionResult(
            context,
            skipResult,
            readyNodes,
            readyNodes.isEmpty()
        );
    }

    /**
     * Create a result indicating the node is waiting for an external trigger (webhook).
     * The workflow is not complete, but no nodes are ready - we're waiting for the webhook call.
     */
    public static StepByStepExecutionResult waitingForTrigger(
            ExecutionContext context,
            String nodeId,
            String message) {
        return new StepByStepExecutionResult(
            context,
            NodeExecutionResult.waitingForTrigger(nodeId, message),
            Set.of(),  // No ready nodes while waiting
            false      // Workflow is not complete
        );
    }

    /**
     * Check if node executed successfully.
     */
    public boolean isSuccess() {
        return nodeResult != null && nodeResult.isSuccess();
    }

    /**
     * Check if node was skipped.
     */
    public boolean isSkipped() {
        return nodeResult != null && nodeResult.isSkipped();
    }

    /**
     * Check if node failed.
     */
    public boolean isFailed() {
        return nodeResult != null && nodeResult.isFailure();
    }

    /**
     * Check if node is waiting for external trigger (webhook).
     */
    public boolean isWaitingForTrigger() {
        return nodeResult != null && nodeResult.isWaitingForTrigger();
    }

    /**
     * Check if node is awaiting an external signal (approval, wait timer, etc.).
     * When true, auto-mode loops must stop - the SignalResumeService will handle
     * execution after the signal resolves.
     */
    public boolean isAwaitingSignal() {
        return nodeResult != null && nodeResult.isAwaitingSignal();
    }

    /**
     * Canonical "has this step reached a final state?" predicate - delegates to
     * {@link NodeExecutionResult#isTerminal()}. See that method's Javadoc for the
     * rationale: code paths that branch on {@link #isSuccess()} as a failure sentinel
     * must gate on this first, or they will mis-treat async-in-flight yields
     * (AWAITING_SIGNAL / RUNNING) as hard failures and trigger spurious refires.
     */
    public boolean isTerminal() {
        return nodeResult != null && nodeResult.isTerminal();
    }

    /**
     * Inverse of {@link #isTerminal()}.
     */
    public boolean isPending() {
        return !isTerminal();
    }

    /**
     * Get the node output data.
     */
    public Map<String, Object> getNodeOutput() {
        return nodeResult != null ? nodeResult.output() : Map.of();
    }

    /**
     * Get the execution time in milliseconds.
     */
    public long getExecutionTime() {
        return nodeResult != null ? nodeResult.durationMs() : 0;
    }

    /**
     * Get the error message if execution failed.
     */
    public String getErrorMessage() {
        if (nodeResult != null && nodeResult.errorMessage().isPresent()) {
            return nodeResult.errorMessage().get();
        }
        return null;
    }
}
