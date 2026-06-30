package com.apimarketplace.common.storage.service.mapping;

import com.apimarketplace.common.mapping.StrictMappingEngine;
import com.apimarketplace.common.storage.dto.MappingSpec;
import com.apimarketplace.common.storage.dto.MappingSpec.FieldSpec;
import com.apimarketplace.common.storage.dto.MappingSpec.SourceSpec;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Composant responsable de la conversion entre differents formats de MappingSpec.
 * Respecte SRP: seule responsabilite = convertir les specs.
 */
@Component
public class MappingSpecConverter {

    /**
     * Convertit un Map (depuis JSON) en MappingSpec.
     */
    @SuppressWarnings("unchecked")
    public MappingSpec fromMap(Map<String, Object> specMap) {
        if (specMap == null) {
            return null;
        }

        MappingSpec spec = new MappingSpec();

        // Convertir source
        if (specMap.get("source") instanceof Map) {
            spec.setSource(convertSourceFromMap((Map<String, Object>) specMap.get("source")));
        }

        // Convertir fields
        if (specMap.get("fields") instanceof Map) {
            spec.setFields(convertFieldsFromMap((Map<String, Object>) specMap.get("fields")));
        }

        // Convertir globals
        if (specMap.get("globals") instanceof Map) {
            spec.setGlobals(convertFieldsFromMap((Map<String, Object>) specMap.get("globals")));
        }

        return spec;
    }

    /**
     * Convertit MappingSpec en StrictMappingEngine.StrictMappingSpec.
     */
    public StrictMappingEngine.StrictMappingSpec toStrictSpec(MappingSpec spec) {
        if (spec == null) {
            return null;
        }

        StrictMappingEngine.StrictMappingSpec strictSpec = new StrictMappingEngine.StrictMappingSpec();

        // Convertir source
        StrictMappingEngine.SourceSpec strictSource = new StrictMappingEngine.SourceSpec();
        if (spec.getSource() != null) {
            SourceSpec source = spec.getSource();
            strictSource.format = source.getFormat();
            strictSource.items_path = source.getItemsPath();
            strictSource.root = source.getRoot();
            strictSource.root_alternatives = source.getRootAlternatives();
        }
        strictSpec.source = strictSource;

        // Convertir fields
        strictSpec.fields = new LinkedHashMap<>();
        if (spec.getFields() != null) {
            spec.getFields().forEach((key, field) -> {
                strictSpec.fields.put(key, convertFieldToStrict(field));
            });
        }

        return strictSpec;
    }

    /**
     * Cree un StrictMappingSpec pour les globals.
     */
    public StrictMappingEngine.StrictMappingSpec toGlobalsStrictSpec(MappingSpec spec) {
        if (spec == null || spec.getGlobals() == null || spec.getGlobals().isEmpty()) {
            return null;
        }

        StrictMappingEngine.StrictMappingSpec strictSpec = new StrictMappingEngine.StrictMappingSpec();

        // Source pour globals: toujours $
        StrictMappingEngine.SourceSpec source = new StrictMappingEngine.SourceSpec();
        source.format = "json";
        source.items_path = "$";
        source.root = null;
        source.root_alternatives = List.of("$");
        strictSpec.source = source;

        // Convertir globals en fields
        strictSpec.fields = new LinkedHashMap<>();
        spec.getGlobals().forEach((key, field) -> {
            strictSpec.fields.put(key, convertFieldToStrict(field));
        });

        return strictSpec;
    }

    // ========== Methodes privees ==========

    @SuppressWarnings("unchecked")
    private SourceSpec convertSourceFromMap(Map<String, Object> sourceMap) {
        SourceSpec source = new SourceSpec();
        source.setFormat((String) sourceMap.get("format"));
        source.setRoot((String) sourceMap.get("root"));
        source.setItemsPath((String) sourceMap.get("items_path"));

        if (sourceMap.get("root_alternatives") instanceof List) {
            source.setRootAlternatives((List<String>) sourceMap.get("root_alternatives"));
        }

        return source;
    }

    @SuppressWarnings("unchecked")
    private Map<String, FieldSpec> convertFieldsFromMap(Map<String, Object> fieldsMap) {
        Map<String, FieldSpec> fields = new LinkedHashMap<>();

        fieldsMap.forEach((key, value) -> {
            if (value instanceof Map) {
                Map<String, Object> fieldMap = (Map<String, Object>) value;
                FieldSpec field = new FieldSpec();

                if (fieldMap.get("candidates") instanceof List) {
                    field.setCandidates((List<String>) fieldMap.get("candidates"));
                }

                field.setTo((String) fieldMap.get("to"));
                field.setDefaultValue(fieldMap.get("default"));

                if (fieldMap.get("required") instanceof Boolean) {
                    field.setRequired((Boolean) fieldMap.get("required"));
                }

                fields.put(key, field);
            }
        });

        return fields;
    }

    private StrictMappingEngine.FieldSpec convertFieldToStrict(FieldSpec field) {
        StrictMappingEngine.FieldSpec strictField = new StrictMappingEngine.FieldSpec();
        strictField.candidates = field.getCandidates();
        strictField.to = field.getTo();
        strictField.required = field.getRequired();
        strictField.defaultValue = field.getDefaultValue();
        return strictField;
    }
}
