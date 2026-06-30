package com.apimarketplace.catalog.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represente un outil MCP decouvert
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpTool {
    
    private String name;
    private String description;
    private JsonNode inputSchema;
    private JsonNode outputSchema;

    public McpTool(String name, String description, JsonNode inputSchema) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
    }

    /**
     * Verifier si l'outil a un schema d'entree defini
     */
    public boolean hasInputSchema() {
        return inputSchema != null && !inputSchema.isEmpty();
    }

    /**
     * Verifier si l'outil a un schema de sortie defini
     */
    public boolean hasOutputSchema() {
        return outputSchema != null && !outputSchema.isEmpty();
    }

    /**
     * Obtenir le type d'entree principal (si defini dans le schema)
     */
    public String getInputType() {
        if (!hasInputSchema()) return "unknown";
        
        JsonNode typeNode = inputSchema.get("type");
        return typeNode != null ? typeNode.asText("object") : "object";
    }

    /**
     * Verifier si l'outil necessite des parametres obligatoires
     */
    public boolean hasRequiredParameters() {
        if (!hasInputSchema()) return false;
        
        JsonNode requiredNode = inputSchema.get("required");
        return requiredNode != null && requiredNode.isArray() && requiredNode.size() > 0;
    }

    /**
     * Obtenir la liste des parametres obligatoires
     */
    public String[] getRequiredParameters() {
        if (!hasRequiredParameters()) return new String[0];
        
        JsonNode requiredNode = inputSchema.get("required");
        String[] params = new String[requiredNode.size()];
        for (int i = 0; i < requiredNode.size(); i++) {
            params[i] = requiredNode.get(i).asText();
        }
        return params;
    }

    /**
     * Obtenir la description d'un parametre specifique
     */
    public String getParameterDescription(String paramName) {
        if (!hasInputSchema()) return null;
        
        JsonNode propertiesNode = inputSchema.get("properties");
        if (propertiesNode == null) return null;
        
        JsonNode paramNode = propertiesNode.get(paramName);
        if (paramNode == null) return null;
        
        JsonNode descNode = paramNode.get("description");
        return descNode != null ? descNode.asText() : null;
    }

    /**
     * Obtenir le type d'un parametre specifique
     */
    public String getParameterType(String paramName) {
        if (!hasInputSchema()) return "unknown";
        
        JsonNode propertiesNode = inputSchema.get("properties");
        if (propertiesNode == null) return "unknown";
        
        JsonNode paramNode = propertiesNode.get(paramName);
        if (paramNode == null) return "unknown";
        
        JsonNode typeNode = paramNode.get("type");
        return typeNode != null ? typeNode.asText("string") : "string";
    }

    /**
     * Verifier si un parametre est obligatoire
     */
    public boolean isParameterRequired(String paramName) {
        String[] required = getRequiredParameters();
        for (String reqParam : required) {
            if (reqParam.equals(paramName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Obtenir une description courte de l'outil
     */
    public String getShortDescription() {
        if (description == null) return "Outil MCP";
        
        if (description.length() <= 100) {
            return description;
        }
        
        return description.substring(0, 100) + "...";
    }

    /**
     * Obtenir un resume des parametres
     */
    public String getParametersSummary() {
        if (!hasInputSchema()) return "Aucun parametre";
        
        JsonNode propertiesNode = inputSchema.get("properties");
        if (propertiesNode == null || propertiesNode.size() == 0) {
            return "Aucun parametre";
        }
        
        int totalParams = propertiesNode.size();
        int requiredParams = getRequiredParameters().length;
        
        if (requiredParams == 0) {
            return totalParams + " parametre(s) optionnel(s)";
        } else if (requiredParams == totalParams) {
            return requiredParams + " parametre(s) obligatoire(s)";
        } else {
            return requiredParams + " obligatoire(s), " + (totalParams - requiredParams) + " optionnel(s)";
        }
    }

    @Override
    public String toString() {
        return String.format("McpTool{name='%s', description='%s', params=%s}", 
                           name, getShortDescription(), getParametersSummary());
    }
}
