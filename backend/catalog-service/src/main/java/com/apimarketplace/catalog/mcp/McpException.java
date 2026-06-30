package com.apimarketplace.catalog.mcp;

/**
 * Exception specifique aux operations MCP
 */
public class McpException extends Exception {
    
    private final String errorCode;
    private final Object errorData;

    public McpException(String message) {
        super(message);
        this.errorCode = null;
        this.errorData = null;
    }

    public McpException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = null;
        this.errorData = null;
    }

    public McpException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
        this.errorData = null;
    }

    public McpException(String message, String errorCode, Object errorData) {
        super(message);
        this.errorCode = errorCode;
        this.errorData = errorData;
    }

    public McpException(String message, String errorCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.errorData = null;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Object getErrorData() {
        return errorData;
    }

    public boolean hasErrorCode() {
        return errorCode != null;
    }

    public boolean hasErrorData() {
        return errorData != null;
    }

    /**
     * Creer une exception de connexion MCP
     */
    public static McpException connectionError(String message) {
        return new McpException(message, "CONNECTION_ERROR");
    }

    /**
     * Creer une exception de timeout MCP
     */
    public static McpException timeoutError(String message) {
        return new McpException(message, "TIMEOUT_ERROR");
    }

    /**
     * Creer une exception d'outil non trouve
     */
    public static McpException toolNotFound(String toolName) {
        return new McpException("Outil MCP non trouve: " + toolName, "TOOL_NOT_FOUND");
    }

    /**
     * Creer une exception d'execution d'outil
     */
    public static McpException toolExecutionError(String toolName, String error) {
        return new McpException("Erreur d'execution de l'outil " + toolName + ": " + error, "TOOL_EXECUTION_ERROR");
    }

    /**
     * Creer une exception de protocole MCP
     */
    public static McpException protocolError(String message) {
        return new McpException("Erreur de protocole MCP: " + message, "PROTOCOL_ERROR");
    }

    /**
     * Creer une exception de configuration invalide
     */
    public static McpException configurationError(String message) {
        return new McpException("Configuration MCP invalide: " + message, "CONFIGURATION_ERROR");
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("McpException");
        
        if (errorCode != null) {
            sb.append(" [").append(errorCode).append("]");
        }
        
        sb.append(": ").append(getMessage());
        
        if (getCause() != null) {
            sb.append(" (cause par: ").append(getCause().getMessage()).append(")");
        }
        
        return sb.toString();
    }
}
