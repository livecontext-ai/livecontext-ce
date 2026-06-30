package com.apimarketplace.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;

import java.util.Map;

/**
 * DTO for capability search request
 */
public record CapabilityRequest(
    @NotBlank(message = "Query is required")
    String q,
    
    @Min(value = 1, message = "k must be at least 1")
    @Max(value = 50, message = "k must be at most 50")
    Integer k,
    
    Map<String, String> hints, // action/resource/provider
    
    Boolean useOpenAI
) {
    
    public CapabilityRequest {
        // Default values
        if (k == null) k = 12;
        if (hints == null) hints = Map.of();
        if (useOpenAI == null) useOpenAI = true;
    }
    
    /**
     * Get hint value safely
     */
    public String getHint(String key) {
        return hints != null ? hints.get(key) : null;
    }
    
    /**
     * Get action hint
     */
    public String getAction() {
        return getHint("action");
    }
    
    /**
     * Get resource hint
     */
    public String getResource() {
        return getHint("resource");
    }
    
    /**
     * Get provider hint
     */
    public String getProvider() {
        return getHint("provider");
    }
}
