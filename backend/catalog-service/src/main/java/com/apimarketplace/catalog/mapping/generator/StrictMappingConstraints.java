package com.apimarketplace.catalog.mapping.generator;

import java.util.Set;

/**
 * Constraints for generating strict JSONPath mappings.
 * 
 * This class defines the parameters that control how mappings are generated,
 * including fallback limits, field filtering, and AI model configuration.
 */
public class StrictMappingConstraints {
    
    private String itemsPath;
    private int maxFallbacks = 4;
    private Set<String> fieldWhitelist;
    private Set<String> fieldBlacklist;
    private boolean preferRelative = true;
    private String modelProvider = "deepinfra";
    private String modelName = "llama3.1:8b";
    private int timeoutMs = 300000;
    
    // Constructors
    public StrictMappingConstraints() {}
    
    public StrictMappingConstraints(String itemsPath) {
        this.itemsPath = itemsPath;
    }
    
    public StrictMappingConstraints(String itemsPath, int maxFallbacks) {
        this.itemsPath = itemsPath;
        this.maxFallbacks = maxFallbacks;
    }
    
    // Getters and Setters
    public String getItemsPath() {
        return itemsPath;
    }
    
    public void setItemsPath(String itemsPath) {
        this.itemsPath = itemsPath;
    }
    
    public int getMaxFallbacks() {
        return maxFallbacks;
    }
    
    public void setMaxFallbacks(int maxFallbacks) {
        this.maxFallbacks = maxFallbacks;
    }
    
    public Set<String> getFieldWhitelist() {
        return fieldWhitelist;
    }
    
    public void setFieldWhitelist(Set<String> fieldWhitelist) {
        this.fieldWhitelist = fieldWhitelist;
    }
    
    public Set<String> getFieldBlacklist() {
        return fieldBlacklist;
    }
    
    public void setFieldBlacklist(Set<String> fieldBlacklist) {
        this.fieldBlacklist = fieldBlacklist;
    }
    
    public boolean isPreferRelative() {
        return preferRelative;
    }
    
    public void setPreferRelative(boolean preferRelative) {
        this.preferRelative = preferRelative;
    }
    
    public String getModelProvider() {
        return modelProvider;
    }
    
    public void setModelProvider(String modelProvider) {
        this.modelProvider = modelProvider;
    }
    
    public String getModelName() {
        return modelName;
    }
    
    public void setModelName(String modelName) {
        this.modelName = modelName;
    }
    
    public int getTimeoutMs() {
        return timeoutMs;
    }
    
    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }
    
    @Override
    public String toString() {
        return "StrictMappingConstraints{" +
               "itemsPath='" + itemsPath + '\'' +
               ", maxFallbacks=" + maxFallbacks +
               ", fieldWhitelist=" + fieldWhitelist +
               ", fieldBlacklist=" + fieldBlacklist +
               ", preferRelative=" + preferRelative +
               ", modelProvider='" + modelProvider + '\'' +
               ", modelName='" + modelName + '\'' +
               ", timeoutMs=" + timeoutMs +
               '}';
    }
}
