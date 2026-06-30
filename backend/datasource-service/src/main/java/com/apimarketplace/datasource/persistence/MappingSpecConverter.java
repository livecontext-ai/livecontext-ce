package com.apimarketplace.datasource.persistence;

import com.apimarketplace.datasource.domain.ColumnStructure;
import com.apimarketplace.datasource.domain.ColumnType;
import com.apimarketplace.datasource.domain.DataSourceModels.ColumnMappingSpec;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Utility functions to convert the stored mapping_spec JSON into strongly typed objects.
 */
public final class MappingSpecConverter {
    private static final TypeReference<Map<String, Object>> DISPLAY_REF = new TypeReference<>() {};

    private MappingSpecConverter() {}

    public static Map<String, ColumnMappingSpec> parse(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || root.isMissingNode() || !root.isObject()) {
                return Map.of();
            }
            Map<String, ColumnMappingSpec> result = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                result.put(entry.getKey(), toSpec(entry.getKey(), entry.getValue(), objectMapper));
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse mapping_spec", e);
        }
    }

    public static Map<String, ColumnMappingSpec> fromUntyped(Object raw, ObjectMapper objectMapper) {
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, ColumnMappingSpec> result = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> {
            if (!(key instanceof String columnName)) {
                return;
            }
            if (value instanceof String legacyPath) {
                result.put(columnName, ColumnMappingSpec.fromLegacyPath(legacyPath));
            } else if (value instanceof Map<?, ?> mapValue) {
                JsonNode node = objectMapper.valueToTree(mapValue);
                result.put(columnName, toSpec(columnName, node, objectMapper));
            } else if (value instanceof JsonNode nodeValue) {
                result.put(columnName, toSpec(columnName, nodeValue, objectMapper));
            }
        });
        return result;
    }

    private static ColumnMappingSpec toSpec(String key, JsonNode node, ObjectMapper objectMapper) {
        if (node == null || node.isNull()) {
            return ColumnMappingSpec.fromLegacyPath(defaultPath(key));
        }
        if (node.isTextual()) {
            return ColumnMappingSpec.fromLegacyPath(node.asText());
        }
        if (!node.isObject()) {
            return ColumnMappingSpec.fromLegacyPath(node.asText());
        }

        String path = node.hasNonNull("path") ? node.get("path").asText() : defaultPath(key);
        ColumnType type = ColumnType.TEXT;
        if (node.hasNonNull("type")) {
            type = ColumnType.fromValue(node.get("type").asText());
        } else if (node.hasNonNull("visual_type")) {
            type = ColumnType.fromValue(node.get("visual_type").asText());
        }

        ColumnStructure structure = ColumnStructure.SCALAR;
        if (node.hasNonNull("structure")) {
            structure = ColumnStructure.fromValue(node.get("structure").asText());
        } else if (node.hasNonNull("parent_type")) {
            structure = ColumnStructure.fromValue(node.get("parent_type").asText());
        }

        Map<String, ColumnMappingSpec> children = Map.of();
        if (node.has("children") && node.get("children").isObject()) {
            children = parseChildren(node.get("children"), objectMapper);
        }

        Map<String, Object> display = Map.of();
        if (node.has("display")) {
            display = objectMapper.convertValue(node.get("display"), DISPLAY_REF);
        }

        return new ColumnMappingSpec(path, type, structure, children, display);
    }

    private static Map<String, ColumnMappingSpec> parseChildren(JsonNode node, ObjectMapper objectMapper) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, ColumnMappingSpec> children = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            children.put(entry.getKey(), toSpec(entry.getKey(), entry.getValue(), objectMapper));
        });
        return children;
    }

    private static String defaultPath(String key) {
        return key != null && key.startsWith("data.") ? key : "data." + key;
    }
}
