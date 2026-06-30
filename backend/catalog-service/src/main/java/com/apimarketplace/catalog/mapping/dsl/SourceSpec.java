package com.apimarketplace.catalog.mapping.dsl;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Source specification defining the root path and format-specific details
 */
public class SourceSpec {

    @JsonProperty("format")
    private String format;

    @JsonProperty("root")
    private String root;

    @JsonProperty("root_anyOf")
    private List<String> rootAnyOf;

    @JsonProperty("root_match")
    private Map<String, Object> rootMatch;

    @JsonProperty("items_path")
    private String itemsPath;

    @JsonProperty("root_alternatives")
    private List<String> rootAlternatives;

    @JsonProperty("strict_mode")
    private Boolean strictMode = true; // Mode strict par defaut pour nouveaux mappings

    /** Retourne root_alternatives ; retombe sur rootAnyOf pour compat. */
    public List<String> getRootAlternatives() {
        if (rootAlternatives != null) return rootAlternatives;
        return rootAnyOf; // retro-compatibilite
    }

    public void setRootAlternatives(List<String> rootAlternatives) {
        this.rootAlternatives = rootAlternatives;
    }

    public Boolean getStrictMode() {
        return strictMode;
    }

    public void setStrictMode(Boolean strictMode) {
        this.strictMode = strictMode;
    }

    // Constructors
    public SourceSpec() {}

    public SourceSpec(String format, String root) {
        this.format = format;
        this.root = root;
    }

    public SourceSpec(String format, String root, List<String> rootAnyOf, Map<String, Object> rootMatch) {
        this.format = format;
        this.root = root;
        this.rootAnyOf = rootAnyOf;
        this.rootMatch = rootMatch;
    }

    // Getters and Setters
    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }

    public List<String> getRootAnyOf() {
        return rootAnyOf;
    }

    public void setRootAnyOf(List<String> rootAnyOf) {
        this.rootAnyOf = rootAnyOf;
    }

    public Map<String, Object> getRootMatch() {
        return rootMatch;
    }

    public void setRootMatch(Map<String, Object> rootMatch) {
        this.rootMatch = rootMatch;
    }

    public String getItemsPath() {
        return itemsPath;
    }

    public void setItemsPath(String itemsPath) {
        this.itemsPath = itemsPath;
    }

    @Override
    public String toString() {
        return "SourceSpec{" +
               "format='" + format + '\'' +
               ", root='" + root + '\'' +
               ", rootAnyOf=" + rootAnyOf +
               ", rootMatch=" + rootMatch +
               ", itemsPath='" + itemsPath + '\'' +
               ", rootAlternatives=" + rootAlternatives +
               ", strictMode=" + strictMode +
               '}';
    }
}
