package com.apimarketplace.orchestrator.domain;

/**
 * Reference vers un outil avec son ID et sa version
 * Respecte les principes SOLID et les bonnes pratiques
 */
public record ToolRef(
    String toolId,
    int version
) {
    
    /**
     * Constructeur avec validation
     */
    public ToolRef {
        if (toolId == null || toolId.trim().isEmpty()) {
            throw new IllegalArgumentException("Tool ID cannot be null or empty");
        }
        if (version <= 0) {
            throw new IllegalArgumentException("Version must be positive");
        }
    }
    
    /**
     * Obtient l'ID de l'outil
     */
    public String getToolId() {
        return toolId;
    }
    
    /**
     * Obtient la version de l'outil
     */
    public int getVersion() {
        return version;
    }
    
    /**
     * Verifie si c'est la meme reference d'outil
     */
    public boolean isSameTool(ToolRef other) {
        if (other == null) return false;
        return toolId.equals(other.toolId) && version == other.version;
    }
    
    /**
     * Verifie si c'est le meme outil (meme ID, version differente)
     */
    public boolean isSameToolId(ToolRef other) {
        if (other == null) return false;
        return toolId.equals(other.toolId);
    }
    
    @Override
    public String toString() {
        return "ToolRef{toolId='" + toolId + "', version=" + version + "}";
    }
}
