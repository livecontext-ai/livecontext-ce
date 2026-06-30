package com.apimarketplace.agent.tools;

/**
 * Standardized error codes for tool execution.
 * Provides consistent error identification across all tool providers.
 */
public enum ToolErrorCode {
    
    // Tool discovery errors (001-009)
    TOOL_NOT_FOUND("TOOL_001", "Tool not found"),
    TOOL_DISABLED("TOOL_002", "Tool is disabled"),
    
    // Validation errors (010-029)
    VALIDATION_ERROR("TOOL_010", "Parameter validation failed"),
    MISSING_PARAMETER("TOOL_011", "Required parameter missing"),
    INVALID_PARAMETER_TYPE("TOOL_012", "Invalid parameter type"),
    INVALID_PARAMETER_VALUE("TOOL_013", "Invalid parameter value"),
    INVALID_ENUM_VALUE("TOOL_014", "Invalid enum value"),
    
    // Authentication/Authorization errors (030-039)
    AUTHENTICATION_REQUIRED("TOOL_030", "Authentication required"),
    PERMISSION_DENIED("TOOL_031", "Permission denied"),
    TENANT_NOT_FOUND("TOOL_032", "Tenant not found"),
    INVALID_CREDENTIALS("TOOL_033", "Invalid credentials"),
    CREDENTIALS_REQUIRED("TOOL_034", "API credentials required"),
    SERVICE_APPROVAL_REQUIRED("TOOL_035", "Service approval required"),
    CREDENTIALS_ALREADY_EXIST("TOOL_036", "Credentials already configured for this service"),
    
    // Resource errors (040-049)
    RESOURCE_NOT_FOUND("TOOL_040", "Resource not found"),
    RESOURCE_ALREADY_EXISTS("TOOL_041", "Resource already exists"),
    RESOURCE_CONFLICT("TOOL_042", "Resource conflict"),
    
    // Execution errors (050-069)
    EXECUTION_FAILED("TOOL_050", "Tool execution failed"),
    TIMEOUT("TOOL_051", "Execution timeout"),
    RATE_LIMITED("TOOL_052", "Rate limit exceeded"),
    QUOTA_EXCEEDED("TOOL_053", "Quota exceeded"),
    INTERNAL_ERROR("TOOL_054", "Internal server error"),
    EXTERNAL_SERVICE_ERROR("TOOL_055", "External service error"),
    
    // Workflow-specific errors (070-079)
    WORKFLOW_NOT_FOUND("TOOL_070", "Workflow not found"),
    WORKFLOW_INVALID("TOOL_071", "Invalid workflow configuration"),
    WORKFLOW_EXECUTION_FAILED("TOOL_072", "Workflow execution failed"),
    
    // Agent-specific errors (080-089)
    AGENT_NOT_FOUND("TOOL_080", "Agent not found"),
    AGENT_INVALID("TOOL_081", "Invalid agent configuration"),
    
    // Datasource-specific errors (090-099)
    DATASOURCE_NOT_FOUND("TOOL_090", "Data source not found"),
    DATASOURCE_INVALID("TOOL_091", "Invalid data source configuration");

    private final String code;
    private final String defaultMessage;

    ToolErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    /**
     * Get the error code string (e.g., "TOOL_001")
     */
    public String getCode() {
        return code;
    }

    /**
     * Get the default error message
     */
    public String getDefaultMessage() {
        return defaultMessage;
    }

    /**
     * Format as "CODE: message"
     */
    public String format(String customMessage) {
        return code + ": " + (customMessage != null ? customMessage : defaultMessage);
    }

    @Override
    public String toString() {
        return code + " - " + defaultMessage;
    }

    /**
     * Find error code by code string
     */
    public static ToolErrorCode fromCode(String code) {
        if (code == null) return null;
        for (ToolErrorCode errorCode : values()) {
            if (errorCode.code.equals(code)) {
                return errorCode;
            }
        }
        return null;
    }
}
