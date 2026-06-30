package com.apimarketplace.catalog.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represente le resultat d'un appel d'outil MCP
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpToolResult {
    
    private boolean success;
    private Object data;
    private String error;
    private String toolName;
    private Long executionTimeMs;
    private String resultType;

    private McpToolResult(boolean success, Object data, String error) {
        this.success = success;
        this.data = data;
        this.error = error;
    }

    /**
     * Creer un resultat de succes
     */
    public static McpToolResult success(Object data) {
        return new McpToolResult(true, data, null);
    }

    /**
     * Creer un resultat d'erreur
     */
    public static McpToolResult error(String error) {
        return new McpToolResult(false, null, error);
    }

    /**
     * Creer un resultat de succes avec metadonnees
     */
    public static McpToolResult success(Object data, String toolName, long executionTimeMs) {
        McpToolResult result = success(data);
        result.setToolName(toolName);
        result.setExecutionTimeMs(executionTimeMs);
        result.setResultType(determineResultType(data));
        return result;
    }

    /**
     * Creer un resultat d'erreur avec metadonnees
     */
    public static McpToolResult error(String error, String toolName, long executionTimeMs) {
        McpToolResult result = error(error);
        result.setToolName(toolName);
        result.setExecutionTimeMs(executionTimeMs);
        return result;
    }

    /**
     * Verifier si le resultat contient des donnees
     */
    public boolean hasData() {
        return data != null;
    }

    /**
     * Obtenir les donnees sous forme de String
     */
    public String getDataAsString() {
        if (data == null) return null;
        return data.toString();
    }

    /**
     * Verifier si les donnees sont de type texte
     */
    public boolean isTextResult() {
        return "text".equals(resultType) || data instanceof String;
    }

    /**
     * Verifier si les donnees sont de type JSON/Object
     */
    public boolean isJsonResult() {
        return "json".equals(resultType) || 
               (data != null && !(data instanceof String) && !(data instanceof Number) && !(data instanceof Boolean));
    }

    /**
     * Verifier si les donnees sont numeriques
     */
    public boolean isNumericResult() {
        return "number".equals(resultType) || data instanceof Number;
    }

    /**
     * Verifier si les donnees sont booleennes
     */
    public boolean isBooleanResult() {
        return "boolean".equals(resultType) || data instanceof Boolean;
    }

    /**
     * Obtenir un resume du resultat
     */
    public String getSummary() {
        if (!success) {
            return "Erreur: " + (error != null ? error : "Erreur inconnue");
        }

        if (data == null) {
            return "Succes (aucune donnee)";
        }

        String summary = "Succes";
        if (toolName != null) {
            summary += " (" + toolName + ")";
        }

        if (isTextResult()) {
            String text = getDataAsString();
            if (text.length() > 50) {
                summary += ": " + text.substring(0, 50) + "...";
            } else {
                summary += ": " + text;
            }
        } else if (isJsonResult()) {
            summary += ": Objet JSON";
        } else if (isNumericResult()) {
            summary += ": " + data;
        } else if (isBooleanResult()) {
            summary += ": " + data;
        } else {
            summary += ": " + resultType;
        }

        if (executionTimeMs != null) {
            summary += " (" + executionTimeMs + "ms)";
        }

        return summary;
    }

    /**
     * Determiner le type de resultat base sur les donnees
     */
    private static String determineResultType(Object data) {
        if (data == null) return "null";
        if (data instanceof String) return "text";
        if (data instanceof Number) return "number";
        if (data instanceof Boolean) return "boolean";
        if (data instanceof java.util.List) return "array";
        if (data instanceof java.util.Map) return "object";
        return "json";
    }

    /**
     * Cloner le resultat avec un nouveau nom d'outil
     */
    public McpToolResult withToolName(String toolName) {
        McpToolResult clone = new McpToolResult(success, data, error);
        clone.setToolName(toolName);
        clone.setExecutionTimeMs(executionTimeMs);
        clone.setResultType(resultType);
        return clone;
    }

    /**
     * Cloner le resultat avec un nouveau temps d'execution
     */
    public McpToolResult withExecutionTime(long executionTimeMs) {
        McpToolResult clone = new McpToolResult(success, data, error);
        clone.setToolName(toolName);
        clone.setExecutionTimeMs(executionTimeMs);
        clone.setResultType(resultType);
        return clone;
    }

    @Override
    public String toString() {
        return getSummary();
    }
}
