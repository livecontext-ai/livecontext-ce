package com.apimarketplace.catalog.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represente une reponse MCP selon le protocole JSON-RPC 2.0
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpResponse {
    
    private String jsonrpc = "2.0";
    private Long id;
    private JsonNode result;
    private McpError error;

    public McpResponse(Long id, JsonNode result) {
        this.id = id;
        this.result = result;
    }

    public McpResponse(Long id, McpError error) {
        this.id = id;
        this.error = error;
    }

    /**
     * Creer une reponse de succes
     */
    public static McpResponse success(Long id, JsonNode result) {
        return new McpResponse(id, result);
    }

    /**
     * Creer une reponse d'erreur
     */
    public static McpResponse error(Long id, int code, String message, JsonNode data) {
        McpError error = new McpError(code, message, data);
        return new McpResponse(id, error);
    }

    /**
     * Creer une reponse d'erreur simple
     */
    public static McpResponse error(Long id, int code, String message) {
        return error(id, code, message, null);
    }

    /**
     * Verifier si c'est une reponse d'erreur
     */
    public boolean isError() {
        return error != null;
    }

    /**
     * Verifier si c'est une reponse de succes
     */
    public boolean isSuccess() {
        return error == null;
    }

    /**
     * Obtenir le message d'erreur s'il y en a un
     */
    public String getErrorMessage() {
        return error != null ? error.getMessage() : null;
    }

    /**
     * Obtenir le code d'erreur s'il y en a un
     */
    public Integer getErrorCode() {
        return error != null ? error.getCode() : null;
    }

    /**
     * Classe pour representer une erreur MCP
     */
    @Data
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class McpError {
        private int code;
        private String message;
        private JsonNode data;

        public McpError(int code, String message, JsonNode data) {
            this.code = code;
            this.message = message;
            this.data = data;
        }

        public McpError(int code, String message) {
            this(code, message, null);
        }

        // Codes d'erreur standard JSON-RPC 2.0
        public static final int PARSE_ERROR = -32700;
        public static final int INVALID_REQUEST = -32600;
        public static final int METHOD_NOT_FOUND = -32601;
        public static final int INVALID_PARAMS = -32602;
        public static final int INTERNAL_ERROR = -32603;

        // Codes d'erreur specifiques MCP
        public static final int TOOL_NOT_FOUND = -32000;
        public static final int TOOL_EXECUTION_ERROR = -32001;
        public static final int RESOURCE_NOT_FOUND = -32002;
        public static final int RESOURCE_ACCESS_ERROR = -32003;
        public static final int CONNECTION_ERROR = -32004;
        public static final int TIMEOUT_ERROR = -32005;

        /**
         * Creer une erreur de parsing
         */
        public static McpError parseError(String message) {
            return new McpError(PARSE_ERROR, message);
        }

        /**
         * Creer une erreur de requete invalide
         */
        public static McpError invalidRequest(String message) {
            return new McpError(INVALID_REQUEST, message);
        }

        /**
         * Creer une erreur de methode non trouvee
         */
        public static McpError methodNotFound(String method) {
            return new McpError(METHOD_NOT_FOUND, "Methode non trouvee: " + method);
        }

        /**
         * Creer une erreur de parametres invalides
         */
        public static McpError invalidParams(String message) {
            return new McpError(INVALID_PARAMS, message);
        }

        /**
         * Creer une erreur interne
         */
        public static McpError internalError(String message) {
            return new McpError(INTERNAL_ERROR, message);
        }

        /**
         * Creer une erreur d'outil non trouve
         */
        public static McpError toolNotFound(String toolName) {
            return new McpError(TOOL_NOT_FOUND, "Outil non trouve: " + toolName);
        }

        /**
         * Creer une erreur d'execution d'outil
         */
        public static McpError toolExecutionError(String message) {
            return new McpError(TOOL_EXECUTION_ERROR, "Erreur d'execution d'outil: " + message);
        }

        /**
         * Creer une erreur de ressource non trouvee
         */
        public static McpError resourceNotFound(String uri) {
            return new McpError(RESOURCE_NOT_FOUND, "Ressource non trouvee: " + uri);
        }

        /**
         * Creer une erreur de connexion
         */
        public static McpError connectionError(String message) {
            return new McpError(CONNECTION_ERROR, "Erreur de connexion: " + message);
        }

        /**
         * Creer une erreur de timeout
         */
        public static McpError timeoutError(String message) {
            return new McpError(TIMEOUT_ERROR, "Timeout: " + message);
        }
    }

    @Override
    public String toString() {
        if (isError()) {
            return String.format("McpResponse{id=%d, error=%s}", id, error.getMessage());
        } else {
            return String.format("McpResponse{id=%d, result=%s}", id, result);
        }
    }
}
