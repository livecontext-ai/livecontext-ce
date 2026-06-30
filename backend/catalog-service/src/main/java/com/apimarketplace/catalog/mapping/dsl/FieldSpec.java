package com.apimarketplace.catalog.mapping.dsl;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Field specification defining how to extract and transform a specific field
 */
public class FieldSpec {

    @JsonProperty("candidates")
    private List<String> candidates;

    @JsonProperty("path_anyOf")
    private List<String> pathAnyOf;

    @JsonProperty("to")
    private String to; // target type: integer, boolean, datetime, uri, string

    @JsonProperty("default")
    private Object defaultValue;

    @JsonProperty("required")
    private Boolean required = false;

    @JsonProperty("map")
    private Map<String, String> map; // for collection fields

    @JsonProperty("max_fallbacks")
    private Integer maxFallbacks = 3; // Limite de fallbacks par defaut

    // Constructors
    public FieldSpec() {}

    public FieldSpec(List<String> candidates, String to) {
        this.candidates = candidates;
        this.to = to;
    }

    public FieldSpec(List<String> candidates, String to, Object defaultValue, Boolean required) {
        this.candidates = candidates;
        this.to = to;
        this.defaultValue = defaultValue;
        this.required = required;
    }

    // Getters and Setters
    public List<String> getCandidates() {
        return candidates;
    }

    public void setCandidates(List<String> candidates) {
        this.candidates = candidates;
    }

    public List<String> getPathAnyOf() {
        return pathAnyOf;
    }

    public void setPathAnyOf(List<String> pathAnyOf) {
        this.pathAnyOf = pathAnyOf;
    }

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }

    public Object getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(Object defaultValue) {
        this.defaultValue = defaultValue;
    }

    public Boolean getRequired() {
        return required;
    }

    public void setRequired(Boolean required) {
        this.required = required;
    }

    public Map<String, String> getMap() {
        return map;
    }

    public void setMap(Map<String, String> map) {
        this.map = map;
    }

    public Integer getMaxFallbacks() {
        return maxFallbacks;
    }

    public void setMaxFallbacks(Integer maxFallbacks) {
        this.maxFallbacks = maxFallbacks;
    }

    @Override
    public String toString() {
        return "FieldSpec{" +
               "candidates=" + candidates +
               ", pathAnyOf=" + pathAnyOf +
               ", to='" + to + '\'' +
               ", defaultValue=" + defaultValue +
               ", required=" + required +
               ", map=" + map +
               ", maxFallbacks=" + maxFallbacks +
               '}';
    }
}
