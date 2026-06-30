package com.apimarketplace.orchestrator.services.interfaces;

import java.util.List;
import java.util.Map;

/**
 * Result of tool execution.
 * Follows SOLID principles and best practices.
 */
public record ExecutionResult(
    boolean ok,
    Map<String, Object> output,
    List<Map<String, String>> errors,
    List<Map<String, String>> missing
) {

    /**
     * Check if execution succeeded.
     */
    public boolean isSuccess() {
        return ok && (errors == null || errors.isEmpty());
    }

    /**
     * Check if execution failed.
     */
    public boolean isFailure() {
        return !ok || (errors != null && !errors.isEmpty());
    }

    /**
     * Get the main error message.
     */
    public String getErrorMessage() {
        if (errors == null || errors.isEmpty()) {
            return null;
        }
        return errors.get(0).get("message");
    }

    /**
     * Get the main error type.
     */
    public String getErrorType() {
        if (errors == null || errors.isEmpty()) {
            return null;
        }
        return errors.get(0).get("type");
    }
}
