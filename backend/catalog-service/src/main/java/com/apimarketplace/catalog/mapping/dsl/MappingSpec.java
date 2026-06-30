package com.apimarketplace.catalog.mapping.dsl;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.List;
import java.util.Map;

/**
 * Main mapping specification that defines how to extract data from various formats
 */
public class MappingSpec {
    private SourceSpec source;
    /** Mappings des items (dans le tableau items_path) */
    private Map<String, FieldSpec> fields;
    /** Mappings des champs globaux (hors items_path), aliases par le spec */
    private Map<String, FieldSpec> globals;

    public SourceSpec getSource() { return source; }
    public void setSource(SourceSpec source) { this.source = source; }

    public Map<String, FieldSpec> getFields() { return fields; }
    public void setFields(Map<String, FieldSpec> fields) { this.fields = fields; }

    public Map<String, FieldSpec> getGlobals() { return globals; }
    public void setGlobals(Map<String, FieldSpec> globals) { this.globals = globals; }
}
