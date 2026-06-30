package com.apimarketplace.common.storage.dto;

import java.util.List;
import java.util.Map;

/**
 * DTO representant une specification de mapping.
 */
public class MappingSpec {

    private SourceSpec source;
    private Map<String, FieldSpec> fields;
    private Map<String, FieldSpec> globals;

    public MappingSpec() {
    }

    public MappingSpec(SourceSpec source, Map<String, FieldSpec> fields, Map<String, FieldSpec> globals) {
        this.source = source;
        this.fields = fields;
        this.globals = globals;
    }

    public SourceSpec getSource() {
        return source;
    }

    public void setSource(SourceSpec source) {
        this.source = source;
    }

    public Map<String, FieldSpec> getFields() {
        return fields;
    }

    public void setFields(Map<String, FieldSpec> fields) {
        this.fields = fields;
    }

    public Map<String, FieldSpec> getGlobals() {
        return globals;
    }

    public void setGlobals(Map<String, FieldSpec> globals) {
        this.globals = globals;
    }

    /**
     * Specification de la source de donnees.
     */
    public static class SourceSpec {
        private String format;
        private String root;
        private String itemsPath;
        private List<String> rootAlternatives;

        public SourceSpec() {
        }

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

        public String getItemsPath() {
            return itemsPath;
        }

        public void setItemsPath(String itemsPath) {
            this.itemsPath = itemsPath;
        }

        public List<String> getRootAlternatives() {
            return rootAlternatives;
        }

        public void setRootAlternatives(List<String> rootAlternatives) {
            this.rootAlternatives = rootAlternatives;
        }
    }

    /**
     * Specification d'un champ de mapping.
     */
    public static class FieldSpec {
        private List<String> candidates;
        private String to;
        private Object defaultValue;
        private Boolean required;

        public FieldSpec() {
        }

        public List<String> getCandidates() {
            return candidates;
        }

        public void setCandidates(List<String> candidates) {
            this.candidates = candidates;
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
    }
}
