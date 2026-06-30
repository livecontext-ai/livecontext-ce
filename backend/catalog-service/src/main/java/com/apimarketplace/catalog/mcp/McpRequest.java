package com.apimarketplace.catalog.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Represente une requete MCP selon le protocole JSON-RPC 2.0
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpRequest {
    
    private String jsonrpc = "2.0";
    private Long id;
    private String method;
    private Object params;

    public McpRequest(Long id, String method, Object params) {
        this.id = id;
        this.method = method;
        this.params = params;
    }

    /**
     * Creer une requete d'initialisation MCP
     */
    public static McpRequest initialize(Long id) {
        Map<String, Object> params = new HashMap<>();
        params.put("protocolVersion", "2024-11-05");
        
        Map<String, Object> clientInfo = new HashMap<>();
        clientInfo.put("name", "livecontext-backend");
        clientInfo.put("version", "1.0.0");
        params.put("clientInfo", clientInfo);
        
        Map<String, Object> capabilities = new HashMap<>();
        capabilities.put("tools", new HashMap<>());
        capabilities.put("resources", new HashMap<>());
        params.put("capabilities", capabilities);
        
        return new McpRequest(id, "initialize", params);
    }

    /**
     * Creer une requete de liste des outils
     */
    public static McpRequest listTools(Long id) {
        return new McpRequest(id, "tools/list", new HashMap<>());
    }

    /**
     * Creer une requete d'appel d'outil
     */
    public static McpRequest callTool(Long id, String toolName, JsonNode arguments) {
        Map<String, Object> params = new HashMap<>();
        params.put("name", toolName);
        if (arguments != null) {
            params.put("arguments", arguments);
        }
        
        return new McpRequest(id, "tools/call", params);
    }

    /**
     * Creer une requete de liste des ressources
     */
    public static McpRequest listResources(Long id) {
        return new McpRequest(id, "resources/list", new HashMap<>());
    }

    /**
     * Creer une requete de lecture de ressource
     */
    public static McpRequest readResource(Long id, String uri) {
        Map<String, Object> params = new HashMap<>();
        params.put("uri", uri);
        
        return new McpRequest(id, "resources/read", params);
    }

    /**
     * Creer une requete de liste des prompts
     */
    public static McpRequest listPrompts(Long id) {
        return new McpRequest(id, "prompts/list", new HashMap<>());
    }

    /**
     * Creer une requete d'obtention de prompt
     */
    public static McpRequest getPrompt(Long id, String name, JsonNode arguments) {
        Map<String, Object> params = new HashMap<>();
        params.put("name", name);
        if (arguments != null) {
            params.put("arguments", arguments);
        }
        
        return new McpRequest(id, "prompts/get", params);
    }

    /**
     * Creer une requete de ping
     */
    public static McpRequest ping(Long id) {
        return new McpRequest(id, "ping", new HashMap<>());
    }

    /**
     * Creer une notification (sans ID)
     */
    public static McpRequest notification(String method, Object params) {
        McpRequest request = new McpRequest();
        request.setMethod(method);
        request.setParams(params);
        // Pas d'ID pour les notifications
        return request;
    }

    /**
     * Creer une notification d'initialisation terminee
     */
    public static McpRequest initialized() {
        return notification("notifications/initialized", new HashMap<>());
    }

    /**
     * Verifier si c'est une notification (pas d'ID)
     */
    public boolean isNotification() {
        return id == null;
    }

    /**
     * Verifier si c'est une requete (avec ID)
     */
    public boolean isRequest() {
        return id != null;
    }

    /**
     * Obtenir le nom de la methode sans le prefixe
     */
    public String getMethodName() {
        if (method == null) return null;
        
        // Supprimer le prefixe (ex: "tools/call" -> "call")
        int slashIndex = method.lastIndexOf('/');
        if (slashIndex != -1 && slashIndex < method.length() - 1) {
            return method.substring(slashIndex + 1);
        }
        
        return method;
    }

    /**
     * Obtenir la categorie de la methode
     */
    public String getMethodCategory() {
        if (method == null) return null;
        
        // Extraire le prefixe (ex: "tools/call" -> "tools")
        int slashIndex = method.indexOf('/');
        if (slashIndex != -1) {
            return method.substring(0, slashIndex);
        }
        
        return method;
    }

    @Override
    public String toString() {
        return String.format("McpRequest{id=%d, method='%s', params=%s}", 
                           id, method, params);
    }
}
