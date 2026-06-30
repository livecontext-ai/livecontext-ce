package com.apimarketplace.common.mapping;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Service wrapper pour le moteur de mapping simple.
 */
@Service
public class SimpleMappingService {

    /**
     * Applique un mapping a des donnees JSON.
     */
    public SimpleMappingEngine.MappingOutcome applyMapping(String inputJson, StrictMappingEngine.StrictMappingSpec mappingSpec) throws IOException {
        // Convertir le spec en JSON pour le moteur simple
        String mappingJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(mappingSpec);
        return SimpleMappingEngine.apply(inputJson, mappingJson);
    }

    /**
     * Applique un mapping et retourne les items mappes.
     */
    public List<Map<String, Object>> mapToItems(String inputJson, StrictMappingEngine.StrictMappingSpec mappingSpec) throws IOException {
        SimpleMappingEngine.MappingOutcome outcome = applyMapping(inputJson, mappingSpec);
        return outcome.items != null ? outcome.items : List.of();
    }

    /**
     * Cree un mapping simple pour un cas d'usage basique.
     */
    public StrictMappingEngine.StrictMappingSpec createSimpleMapping(String itemsPath, Map<String, String> fieldMappings) {
        StrictMappingEngine.StrictMappingSpec spec = new StrictMappingEngine.StrictMappingSpec();
        
        // Source configuration
        spec.source = new StrictMappingEngine.SourceSpec();
        spec.source.format = "json";
        spec.source.items_path = itemsPath;
        
        // Fields configuration
        spec.fields = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> entry : fieldMappings.entrySet()) {
            StrictMappingEngine.FieldSpec fieldSpec = new StrictMappingEngine.FieldSpec();
            fieldSpec.candidates = List.of(entry.getValue());
            fieldSpec.to = "string";
            fieldSpec.required = false;
            spec.fields.put(entry.getKey(), fieldSpec);
        }
        
        return spec;
    }
}
