package com.apimarketplace.orchestrator.domain.workflow;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;

import java.util.Map;

/**
 * Result of a step execution.
 */
public record StepExecutionResult(String stepId, NodeStatus status,
                                 String message, Map<String, Object> output,
                                 long executionTime, Exception error) {

    /**
     * Compact constructor - caps {@link #message} at
     * {@link ErrorMessageLimits#MAX_LENGTH} characters so that every downstream
     * consumer (DB error_message column, metadata JSONB statusMessage, SSE
     * payloads, logs) inherits the same bound. Hot-path is zero-allocation when
     * the message is within budget.
     *
     * <p>The {@code error} Exception is left untouched here - its
     * {@code getMessage()} flows through {@code StepMetadataBuilder} which
     * applies the cap explicitly on that path.
     */
    public StepExecutionResult {
        message = ErrorMessageLimits.truncate(message);
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

    /**
     * Canonical "has this step reached a final state?" predicate - delegates to
     * {@link NodeStatus#isTerminal()}. A pending result (RUNNING, AWAITING_SIGNAL,
     * COLLECTING, WAITING_TRIGGER) has {@code isSuccess() == false} AND
     * {@code isFailure() == false}; callers that branch on {@code !isSuccess()} as
     * a failure sentinel must gate on {@code isTerminal()} first.
     */
    public boolean isTerminal() {
        return status != null && status.isTerminal();
    }

    public boolean isPending() {
        return !isTerminal();
    }

    /**
     * Create a result representing a non-terminal yield (awaiting signal, async
     * in flight, still running). Use this when the v2 engine returns a pending
     * result that must be surfaced to callers without being mis-classified as
     * success or failure.
     */
    public static StepExecutionResult pending(String stepId, NodeStatus status, String message, long executionTime) {
        return new StepExecutionResult(stepId, status, message, null, executionTime, null);
    }

    public static StepExecutionResult success(String stepId, Map<String, Object> output, long executionTime) {
        return new StepExecutionResult(stepId, NodeStatus.COMPLETED, "Success", output, executionTime, null);
    }

    public static StepExecutionResult failure(String stepId, String message, Exception error, long executionTime) {
        return new StepExecutionResult(stepId, NodeStatus.FAILED, message, null, executionTime, error);
    }

    /**
     * Creates a failure result that preserves the output data.
     * Useful for storing error details, partial responses, or diagnostic information.
     */
    public static StepExecutionResult failureWithOutput(String stepId, String message, Map<String, Object> output, long executionTime) {
        return new StepExecutionResult(stepId, NodeStatus.FAILED, message, output, executionTime, null);
    }

    public static StepExecutionResult skipped(String stepId, String reason) {
        return new StepExecutionResult(stepId, NodeStatus.SKIPPED, reason, null, 0, null);
    }

    public StepExecutionResult withOutputSnapshot(Map<String, Object> snapshot) {
        Map<String, Object> safeSnapshot = snapshot != null ? snapshot : Map.of();
        return new StepExecutionResult(stepId, status, message, safeSnapshot, executionTime, error);
    }

    public boolean hasError() {
        return error != null;
    }

    /**
     * Returns a copy of this result with the output payload cleared.
     * Used when the payload has been released from memory but the execution metadata must be retained.
     */
    public StepExecutionResult withoutOutput() {
        if (output == null || output.isEmpty()) {
            return this;
        }
        return new StepExecutionResult(stepId, status, message, Map.of(), executionTime, error);
    }


    public StepExecutionResult withStepId(String newStepId) {
        if (newStepId == null || newStepId.isBlank() || java.util.Objects.equals(newStepId, stepId)) {
            return this;
        }
        return new StepExecutionResult(newStepId, status, message, output, executionTime, error);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("StepExecutionResult{");
        sb.append("stepId=").append(stepId)
          .append(", status=").append(status)
          .append(", message=").append(message);

        if (output != null && !output.isEmpty()) {
            sb.append(", output=").append(summarizeValue(output));
        }

        sb.append(", executionTime=").append(executionTime);
        if (error != null) {
            sb.append(", error=").append(error.getClass().getSimpleName())
              .append(": ").append(error.getMessage());
        }
        sb.append('}');
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static String summarizeValue(Object value) {
        if (value == null) {
            return "null";
        }

        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }

        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            int count = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (count++ > 0) {
                    sb.append(", ");
                }
                sb.append(entry.getKey()).append('=').append(summarizeValue(entry.getValue()));
                if (count >= 5) {
                    sb.append(", ...");
                    break;
                }
            }
            sb.append('}');
            return sb.toString();
        }

        if (value instanceof Iterable<?> iterable) {
            StringBuilder sb = new StringBuilder("[");
            int count = 0;
            for (Object item : iterable) {
                if (count++ > 0) {
                    sb.append(", ");
                }
                sb.append(summarizeValue(item));
                if (count >= 5) {
                    sb.append(", ...");
                    break;
                }
            }
            sb.append(']');
            return sb.toString();
        }

        return value.getClass().getSimpleName();
    }
}
