package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.execution.SignalType;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Result of node execution.
 * Immutable record containing execution status, output, and optional state.
 */
public record NodeExecutionResult(
    String nodeId,
    NodeStatus status,
    Map<String, Object> output,
    Optional<String> errorMessage,
    Map<String, Object> metadata,
    long durationMs
) {

    public static NodeExecutionResult success(String nodeId, Map<String, Object> output) {
        return success(nodeId, output, 0);
    }

    public static NodeExecutionResult success(String nodeId, Map<String, Object> output, long durationMs) {
        return new NodeExecutionResult(
            nodeId,
            NodeStatus.COMPLETED,
            output != null ? output : Map.of(),
            Optional.empty(),
            Map.of(),
            durationMs
        );
    }

    public static NodeExecutionResult failure(String nodeId, String error) {
        return failure(nodeId, error, 0);
    }

    public static NodeExecutionResult failure(String nodeId, String error, long durationMs) {
        return new NodeExecutionResult(
            nodeId,
            NodeStatus.FAILED,
            Map.of(),
            Optional.ofNullable(error != null ? error : "Unknown error"),
            Map.of(),
            durationMs
        );
    }

    /**
     * Create a failure result with output preserved.
     * Use this when the execution failed but there's still useful output to store
     * (e.g., partial API response, error details from the remote service).
     */
    public static NodeExecutionResult failureWithOutput(String nodeId, String error, Map<String, Object> output, long durationMs) {
        return new NodeExecutionResult(
            nodeId,
            NodeStatus.FAILED,
            output != null ? output : Map.of(),
            Optional.ofNullable(error != null ? error : "Unknown error"),
            Map.of(),
            durationMs
        );
    }

    public static NodeExecutionResult skipped(String nodeId, String reason) {
        return new NodeExecutionResult(
            nodeId,
            NodeStatus.SKIPPED,
            Map.of(),
            Optional.of(reason),
            Map.of("skip_reason", reason),
            0
        );
    }

    /**
     * SKIPPED result that asks the engine to cascade SKIPPED to all successors via
     * {@code V2SkipPropagationService.cascadeFailureToSuccessors} (which also handles
     * the SKIPPED case despite its name).
     *
     * <p>Use this when the SKIPPED is a TERMINAL outcome (e.g. "no items routed to
     * this branch") and successors should be marked SKIPPED with proper {@code
     * workflow_step_data} rows + {@code EpochState.skippedNodeIds} mutation. Routing
     * skips from Decision/Switch nodes must NOT use this - they rely on per-port edge
     * filtering and should not propagate across sibling branches.
     */
    public static NodeExecutionResult skippedWithCascade(String nodeId, String reason) {
        return new NodeExecutionResult(
            nodeId,
            NodeStatus.SKIPPED,
            Map.of(),
            Optional.of(reason),
            Map.of(
                "skip_reason", reason,
                com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys.CASCADE_SKIP_TO_SUCCESSORS, Boolean.TRUE
            ),
            0
        );
    }

    public static NodeExecutionResult running(String nodeId) {
        return new NodeExecutionResult(
            nodeId,
            NodeStatus.RUNNING,
            Map.of(),
            Optional.empty(),
            Map.of(),
            0
        );
    }

    /**
     * Create a result indicating the node is waiting for an external trigger (webhook).
     */
    public static NodeExecutionResult waitingForTrigger(String nodeId, String message) {
        return new NodeExecutionResult(
            nodeId,
            NodeStatus.WAITING_TRIGGER,
            Map.of(),
            Optional.ofNullable(message),
            Map.of("waiting_reason", message != null ? message : "Waiting for webhook"),
            0
        );
    }

    /**
     * Create a result indicating the node is awaiting a signal (timer, approval, webhook).
     * The engine will YIELD on this result, similar to merge nodes.
     * Execution resumes when the signal resolves via SignalResumeService.
     */
    public static NodeExecutionResult awaitingSignal(String nodeId, SignalType signalType, Map<String, Object> metadata) {
        Map<String, Object> output = new HashMap<>(metadata != null ? metadata : Map.of());
        output.put("signal_type", signalType.name());
        return new NodeExecutionResult(
            nodeId,
            NodeStatus.AWAITING_SIGNAL,
            output,
            Optional.empty(),
            output,
            0
        );
    }

    /**
     * Create a result indicating the node was offloaded to an async worker pool
     * (e.g., agent execution via Redis queue) and the engine should yield without
     * traversing successors. The visible status remains {@code RUNNING} so the
     * frontend reflects intent ("the node is busy") instead of the implementation
     * mechanism ("waiting for a signal").
     *
     * <p>The completion is engine-owned: when the worker delivers the result, an
     * async-completion service calls back into the same sync persistence pipeline
     * used by inline node execution. This avoids the parallel persistence path that
     * would otherwise be needed if AWAITING_SIGNAL were reused for engine-driven I/O.</p>
     *
     * <p>The {@code correlationId} identifies the in-flight task so the completion
     * service can match it back to this run/node/item.</p>
     */
    public static NodeExecutionResult asyncRunning(
            String nodeId,
            String correlationId,
            String agentType,
            Map<String, Object> initialOutput) {
        Map<String, Object> output = new HashMap<>(initialOutput != null ? initialOutput : Map.of());
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ASYNC_RUNNING_MARKER, Boolean.TRUE);
        metadata.put(ASYNC_CORRELATION_ID, correlationId);
        if (agentType != null) {
            metadata.put(ASYNC_AGENT_TYPE, agentType);
        }
        return new NodeExecutionResult(
            nodeId,
            NodeStatus.RUNNING,
            output,
            Optional.empty(),
            metadata,
            0
        );
    }

    /** Metadata key marking a result as async-running (engine-owned async I/O). */
    public static final String ASYNC_RUNNING_MARKER = "async_running";
    /** Metadata key carrying the correlation id of the in-flight async task. */
    public static final String ASYNC_CORRELATION_ID = "async_correlation_id";
    /** Metadata key carrying the agent type ("agent" / "classify" / "guardrail"). */
    public static final String ASYNC_AGENT_TYPE = "async_agent_type";

    /**
     * Create a result indicating the node is collecting items (aggregate node).
     * This status is NOT persisted to DB - only the final aggregated result is persisted.
     */
    public static NodeExecutionResult collecting(String nodeId, Map<String, Object> partialOutput) {
        return new NodeExecutionResult(
            nodeId,
            NodeStatus.COLLECTING,
            partialOutput != null ? partialOutput : Map.of(),
            Optional.empty(),
            Map.of(),
            0
        );
    }

    public boolean isSuccess() {
        return status == NodeStatus.COMPLETED;
    }

    public boolean isFailure() {
        return status == NodeStatus.FAILED;
    }

    public boolean isSkipped() {
        return status == NodeStatus.SKIPPED;
    }

    public boolean isWaitingForTrigger() {
        return status == NodeStatus.WAITING_TRIGGER;
    }

    public boolean isCollecting() {
        return status == NodeStatus.COLLECTING;
    }

    public boolean isAwaitingSignal() {
        return status == NodeStatus.AWAITING_SIGNAL;
    }

    /**
     * Canonical "has this step reached a final state?" predicate.
     *
     * <p>Terminal states are {@link NodeStatus#COMPLETED}, {@link NodeStatus#SKIPPED},
     * and {@link NodeStatus#FAILED}. Non-terminal (pending) states include
     * {@link NodeStatus#RUNNING}, {@link NodeStatus#AWAITING_SIGNAL},
     * {@link NodeStatus#COLLECTING}, and {@link NodeStatus#WAITING_TRIGGER} - i.e. any
     * state where the node has been dispatched but its outcome is still in flight.</p>
     *
     * <p><b>Why this matters:</b> code paths that branch on {@link #isSuccess()} (e.g.
     * "if !success → treat as failure, refire, stamp FAILED, …") must first check
     * {@code isTerminal()} to avoid mis-treating an async-in-flight yield (e.g. an
     * async-running agent waiting on a worker queue) as a hard failure. A pending
     * yield has {@code isSuccess() == false} AND {@code isFailure() == false}; only
     * {@code isTerminal()} distinguishes it from a real terminal outcome.</p>
     */
    public boolean isTerminal() {
        return status != null && status.isTerminal();
    }

    /**
     * Inverse of {@link #isTerminal()} - true when the node is still in flight
     * (RUNNING, AWAITING_SIGNAL, COLLECTING, WAITING_TRIGGER, or PENDING/READY).
     */
    public boolean isPending() {
        return !isTerminal();
    }

    /**
     * Returns true if this result is a yield for engine-owned async I/O
     * (e.g., agent execution offloaded to a Redis worker pool).
     *
     * <p>Async-running results have status {@link NodeStatus#RUNNING} (not
     * {@code AWAITING_SIGNAL}) so the visible status reflects intent rather than
     * the implementation mechanism. The engine still yields without traversing
     * successors - completion is delivered later via an async-completion service.</p>
     */
    public boolean isAsyncRunning() {
        return status == NodeStatus.RUNNING
            && metadata != null
            && Boolean.TRUE.equals(metadata.get(ASYNC_RUNNING_MARKER));
    }

    /**
     * Return a copy of this result with an overridden duration.
     * Used by the execution engine to set wall-clock timing generically
     * for all node types, without each node needing to measure its own time.
     */
    public NodeExecutionResult withDuration(long newDurationMs) {
        return new NodeExecutionResult(nodeId, status, output, errorMessage, metadata, newDurationMs);
    }

}
