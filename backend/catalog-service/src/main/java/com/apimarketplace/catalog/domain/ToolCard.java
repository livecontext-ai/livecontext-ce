package com.apimarketplace.catalog.domain;

import java.util.Map;

/**
 * Represents a tool card for the model with concise information
 */
public record ToolCard(
    String name,                    // tool function name
    String description,             // <= 240 chars (short)
    Map<String, String> requiredParams,
    Map<String, String> optionalParams,
    String platform,               // if applicable
    String reliability             // LOW/MEDIUM/HIGH (from metrics)
) {
    
    /**
     * Creates a tool card with empty parameters
     */
    public static ToolCard of(String name, String description, String platform, String reliability) {
        return new ToolCard(name, description, Map.of(), Map.of(), platform, reliability);
    }
    
    /**
     * Creates a tool card with parameters
     */
    public static ToolCard of(String name, String description, 
                             Map<String, String> requiredParams, 
                             Map<String, String> optionalParams,
                             String platform, String reliability) {
        return new ToolCard(name, description, requiredParams, optionalParams, platform, reliability);
    }
}
