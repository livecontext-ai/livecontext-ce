package com.apimarketplace.catalog.mapping.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Validator for strict mapping specifications.
 * 
 * This component validates that mapping candidates work correctly on sample JSON data,
 * checks type compatibility, and ensures fallback ordering is appropriate.
 */
@Component
public class MappingValidator {
    
    private static final Logger logger = LoggerFactory.getLogger(MappingValidator.class);
    
    private final JsonPathEvaluator pathEvaluator;
    private final ObjectMapper objectMapper;
    
    public MappingValidator(JsonPathEvaluator pathEvaluator, ObjectMapper objectMapper) {
        this.pathEvaluator = pathEvaluator;
        this.objectMapper = objectMapper;
    }
    
    /**
     * Validates a mapping specification against sample JSON data.
     * 
     * @param sampleJson The sample JSON data to validate against
     * @param mappingSpec The mapping specification to validate
     * @param maxFallbacks Maximum number of fallbacks per field
     * @return Validated mapping specification with invalid candidates removed
     */
    public String validateMapping(String sampleJson, String mappingSpec, int maxFallbacks) {
        try {
            JsonNode mappingNode = objectMapper.readTree(mappingSpec);
            JsonNode sourceNode = mappingNode.get("source");
            JsonNode fieldsNode = mappingNode.get("fields");
            
            if (sourceNode == null || fieldsNode == null) {
                throw new IllegalArgumentException("Invalid mapping specification: missing source or fields");
            }
            
            String itemsPath = sourceNode.has("items_path") ? sourceNode.get("items_path").asText() : null;
            
            // Validate and clean up fields
            JsonNode validatedFields = validateFields(sampleJson, fieldsNode, itemsPath, maxFallbacks);
            
            // Create validated mapping
            com.fasterxml.jackson.databind.node.ObjectNode validatedMapping = objectMapper.createObjectNode();
            validatedMapping.set("source", sourceNode);
            validatedMapping.set("fields", validatedFields);
            
            return objectMapper.writeValueAsString(validatedMapping);
            
        } catch (Exception e) {
            logger.error("Mapping validation failed: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to validate mapping", e);
        }
    }
    
    /**
     * Validates and cleans up field specifications.
     */
    private JsonNode validateFields(String sampleJson, JsonNode fieldsNode, String itemsPath, int maxFallbacks) {
        com.fasterxml.jackson.databind.node.ObjectNode validatedFields = objectMapper.createObjectNode();
        
        fieldsNode.fields().forEachRemaining(fieldEntry -> {
            String fieldName = fieldEntry.getKey();
            JsonNode fieldSpec = fieldEntry.getValue();
            
            JsonNode validatedField = validateField(sampleJson, fieldName, fieldSpec, itemsPath, maxFallbacks);
            if (validatedField != null) {
                validatedFields.set(fieldName, validatedField);
            }
        });
        
        return validatedFields;
    }
    
    /**
     * Validates a single field specification.
     */
    private JsonNode validateField(String sampleJson, String fieldName, JsonNode fieldSpec, String itemsPath, int maxFallbacks) {
        try {
            JsonNode candidatesNode = fieldSpec.get("candidates");
            if (candidatesNode == null || !candidatesNode.isArray()) {
                logger.warn("Field '{}' has no valid candidates", fieldName);
                return null;
            }
            
            List<String> validCandidates = new ArrayList<>();
            String targetType = fieldSpec.has("to") ? fieldSpec.get("to").asText() : "string";
            boolean required = fieldSpec.has("required") && fieldSpec.get("required").asBoolean();
            
            // Validate each candidate
            for (JsonNode candidateNode : candidatesNode) {
                String candidate = candidateNode.asText();
                if (candidate == null || candidate.trim().isEmpty()) {
                    continue;
                }
                
                if (isValidCandidate(sampleJson, candidate, itemsPath, targetType)) {
                    validCandidates.add(candidate);
                    if (validCandidates.size() >= maxFallbacks) {
                        break; // Limit to maxFallbacks
                    }
                }
            }
            
            if (validCandidates.isEmpty()) {
                if (required) {
                    logger.warn("Required field '{}' has no valid candidates", fieldName);
                }
                return null;
            }
            
            // Create validated field specification
            com.fasterxml.jackson.databind.node.ObjectNode validatedField = objectMapper.createObjectNode();
            validatedField.put("to", targetType);
            validatedField.put("required", required);
            
            // Add candidates as array
            JsonNode candidatesArray = objectMapper.valueToTree(validCandidates);
            validatedField.set("candidates", candidatesArray);
            
            return validatedField;
            
        } catch (Exception e) {
            logger.error("Field validation failed for '{}': {}", fieldName, e.getMessage());
            return null;
        }
    }
    
    /**
     * Checks if a candidate path is valid for the given sample JSON.
     */
    private boolean isValidCandidate(String sampleJson, String candidate, String itemsPath, String targetType) {
        try {
            // Check if path exists and returns values
            if (!pathEvaluator.pathExists(sampleJson, candidate, itemsPath)) {
                return false;
            }
            
            // Extract values and check type compatibility
            List<Object> values = pathEvaluator.extractAll(sampleJson, candidate, itemsPath);
            if (values.isEmpty()) {
                return false;
            }
            
            // Check type compatibility
            return isTypeCompatible(values, targetType);
            
        } catch (Exception e) {
            logger.debug("Candidate validation failed for '{}': {}", candidate, e.getMessage());
            return false;
        }
    }
    
    /**
     * Checks if extracted values are compatible with the target type.
     */
    private boolean isTypeCompatible(List<Object> values, String targetType) {
        if (values.isEmpty()) {
            return false;
        }
        
        // Check if all values are compatible with target type
        for (Object value : values) {
            if (!isValueCompatibleWithType(value, targetType)) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Checks if a single value is compatible with the target type.
     */
    private boolean isValueCompatibleWithType(Object value, String targetType) {
        if (value == null) {
            return false;
        }
        
        switch (targetType.toLowerCase()) {
            case "string":
                return value instanceof String || value instanceof Number || value instanceof Boolean;
            case "integer":
                return value instanceof Integer || (value instanceof Number && ((Number) value).intValue() == ((Number) value).doubleValue());
            case "long":
                return value instanceof Long || value instanceof Integer || value instanceof Number;
            case "number":
                return value instanceof Number;
            case "boolean":
                return value instanceof Boolean;
            case "array<string>":
                return value instanceof List || (value instanceof JsonNode && ((JsonNode) value).isArray());
            case "array<number>":
                if (value instanceof final List<?> list) {
                    return list.stream().allMatch(item -> item instanceof Number);
                }
                return value instanceof JsonNode && ((JsonNode) value).isArray();
            case "array<boolean>":
                if (value instanceof final List<?> list) {
                    return list.stream().allMatch(item -> item instanceof Boolean);
                }
                return value instanceof JsonNode && ((JsonNode) value).isArray();
            default:
                return true; // Unknown type, assume compatible
        }
    }
}
