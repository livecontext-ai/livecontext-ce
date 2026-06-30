package com.apimarketplace.agent.tools;

import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.common.web.TenantResolver;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for tool providers.
 * Each provider is responsible for a category of tools.
 */
public interface ToolsProvider {

    /**
     * Get the category this provider handles.
     */
    ToolCategory getCategory();

    /**
     * Get all tools from this provider.
     */
    List<AgentToolDefinition> getTools();

    /**
     * Execute a tool synchronously.
     *
     * @param toolName   The tool name
     * @param parameters The tool parameters
     * @param context    Execution context (tenantId, credentials, etc.)
     * @return Execution result
     */
    ToolExecutionResult execute(String toolName, Map<String, Object> parameters, ToolExecutionContext context);

    /**
     * Execute a tool asynchronously.
     * Default implementation wraps the synchronous execute method.
     *
     * @param toolName   The tool name
     * @param parameters The tool parameters
     * @param context    Execution context
     * @return CompletableFuture with execution result
     */
    default CompletableFuture<ToolExecutionResult> executeAsync(
            String toolName, Map<String, Object> parameters, ToolExecutionContext context) {
        final String orgIdForWorker = context != null ? context.orgId() : null;
        return CompletableFuture.supplyAsync(() -> {
            ToolExecutionResult[] resultHolder = new ToolExecutionResult[1];
            TenantResolver.runWithOrgScope(orgIdForWorker, () ->
                    resultHolder[0] = execute(toolName, parameters, context));
            return resultHolder[0];
        });
    }

    /**
     * Check if this provider can handle a tool.
     */
    default boolean canHandle(String toolName) {
        return getTools().stream().anyMatch(t -> t.name().equals(toolName));
    }

    /**
     * Execution context for tool execution.
     */
    record ToolExecutionContext(
        String tenantId,
        Map<String, Object> credentials,
        Map<String, Object> variables,
        Set<String> approvedServices,
        String viewingWorkflowId,
        String viewingWorkflowName,
        String orgId,
        String orgRole
    ) {
        public static ToolExecutionContext of(String tenantId) {
            return new ToolExecutionContext(tenantId, Map.of(), Map.of(), Set.of(), null, null, null, null);
        }

        public static ToolExecutionContext empty() {
            return new ToolExecutionContext(null, Map.of(), Map.of(), Set.of(), null, null, null, null);
        }

        public static ToolExecutionContext withApprovedServices(String tenantId, Set<String> approvedServices) {
            return new ToolExecutionContext(tenantId, Map.of(), Map.of(), approvedServices != null ? approvedServices : Set.of(), null, null, null, null);
        }

        public static ToolExecutionContext withWorkflowContext(String tenantId, Set<String> approvedServices,
                                                                String workflowId, String workflowName) {
            return new ToolExecutionContext(tenantId, Map.of(), Map.of(),
                approvedServices != null ? approvedServices : Set.of(), workflowId, workflowName, null, null);
        }

        /**
         * Check if a service is approved for execution.
         */
        public boolean isServiceApproved(String serviceType) {
            return approvedServices != null && approvedServices.contains(serviceType);
        }

        /**
         * Check if user is currently viewing a workflow.
         */
        public boolean isViewingWorkflow() {
            return viewingWorkflowId != null && !viewingWorkflowId.isBlank();
        }
    }

    /**
     * Result of tool execution with structured error codes.
     */
    record ToolExecutionResult(
        boolean success,
        Object data,
        String error,
        ToolErrorCode errorCode,
        Map<String, Object> metadata
    ) {
        // ===== SUCCESS FACTORY METHODS =====
        
        public static ToolExecutionResult success(Object data) {
            return new ToolExecutionResult(true, data, null, null, Map.of());
        }

        public static ToolExecutionResult success(Object data, Map<String, Object> metadata) {
            return new ToolExecutionResult(true, data, null, null, metadata);
        }

        // ===== FAILURE FACTORY METHODS (backward compatible) =====
        
        /**
         * Create a failure result with just an error message.
         * @deprecated Use failure(ToolErrorCode, String) for better error handling
         */
        @Deprecated
        public static ToolExecutionResult failure(String error) {
            return new ToolExecutionResult(false, null, error, ToolErrorCode.EXECUTION_FAILED, Map.of());
        }

        /**
         * Create a failure result with error message and metadata.
         * @deprecated Use failure(ToolErrorCode, String, Map) for better error handling
         */
        @Deprecated
        public static ToolExecutionResult failure(String error, Map<String, Object> metadata) {
            return new ToolExecutionResult(false, null, error, ToolErrorCode.EXECUTION_FAILED, metadata);
        }

        // ===== NEW FAILURE FACTORY METHODS WITH ERROR CODES =====
        
        /**
         * Create a failure result with error code and custom message.
         */
        public static ToolExecutionResult failure(ToolErrorCode errorCode, String error) {
            return new ToolExecutionResult(false, null, error, errorCode, Map.of());
        }

        /**
         * Create a failure result with error code, message and metadata.
         */
        public static ToolExecutionResult failure(ToolErrorCode errorCode, String error, Map<String, Object> metadata) {
            return new ToolExecutionResult(false, null, error, errorCode, metadata);
        }

        /**
         * Create a failure result using the error code's default message.
         */
        public static ToolExecutionResult failure(ToolErrorCode errorCode) {
            return new ToolExecutionResult(false, null, errorCode.getDefaultMessage(), errorCode, Map.of());
        }

        // ===== UTILITY METHODS =====

        /**
         * Convert to map for JSON serialization.
         */
        public Map<String, Object> toMap() {
            Map<String, Object> result = new HashMap<>();
            result.put("success", success);
            
            if (success) {
                result.put("data", data != null ? data : Map.of());
            } else {
                result.put("error", error != null ? error : "Unknown error");
                if (errorCode != null) {
                    result.put("errorCode", errorCode.getCode());
                    result.put("errorType", errorCode.name());
                }
            }
            
            result.put("metadata", metadata != null ? metadata : Map.of());
            return result;
        }

        /**
         * Check if this is a validation error.
         */
        public boolean isValidationError() {
            return errorCode != null && (
                errorCode == ToolErrorCode.VALIDATION_ERROR ||
                errorCode == ToolErrorCode.MISSING_PARAMETER ||
                errorCode == ToolErrorCode.INVALID_PARAMETER_TYPE ||
                errorCode == ToolErrorCode.INVALID_PARAMETER_VALUE
            );
        }

        /**
         * Check if this is an authentication/authorization error.
         */
        public boolean isAuthError() {
            return errorCode != null && (
                errorCode == ToolErrorCode.AUTHENTICATION_REQUIRED ||
                errorCode == ToolErrorCode.PERMISSION_DENIED ||
                errorCode == ToolErrorCode.TENANT_NOT_FOUND
            );
        }
    }
}
