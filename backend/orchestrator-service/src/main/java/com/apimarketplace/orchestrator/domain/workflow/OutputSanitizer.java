package com.apimarketplace.orchestrator.domain.workflow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility class for sanitizing workflow output maps.
 * Handles circular references, StepExecutionResult conversion, and type normalization.
 */
public final class OutputSanitizer {

    private OutputSanitizer() {}

    public static Map<String, Object> sanitize(Map<String, Object> source) {
        Map<String, Object> sanitized = new HashMap<>();
        if (source == null || source.isEmpty()) return sanitized;

        IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() == null) continue;
            sanitized.put(entry.getKey(), sanitizeValue(entry.getValue(), visited));
        }
        return sanitized;
    }

    private static Object sanitizeValue(Object value, IdentityHashMap<Object, Boolean> visited) {
        if (value == null) return null;

        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }

        if (value instanceof Enum<?>) {
            return value.toString();
        }

        if (value instanceof StepExecutionResult result) {
            Map<String, Object> summary = new HashMap<>();
            summary.put("stepId", result.stepId());
            summary.put("status", result.status().name());
            summary.put("message", result.message());
            summary.put("executionTime", result.executionTime());
            if (result.output() != null && !result.output().isEmpty()) {
                summary.put("output", sanitizeValue(result.output(), visited));
            }
            if (result.error() != null) {
                summary.put("error", result.error().getClass().getSimpleName());
            }
            return summary;
        }

        if (value instanceof Map<?, ?> mapValue) {
            if (visited.put(mapValue, Boolean.TRUE) != null) {
                return "<circular>";
            }
            Map<String, Object> nested = new HashMap<>();
            for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                Object key = entry.getKey();
                if (key == null) continue;
                nested.put(String.valueOf(key), sanitizeValue(entry.getValue(), visited));
            }
            visited.remove(mapValue);
            return nested;
        }

        if (value instanceof Collection<?> collectionValue) {
            if (visited.put(collectionValue, Boolean.TRUE) != null) {
                return List.of("<circular>");
            }
            List<Object> sanitizedList = new ArrayList<>(collectionValue.size());
            for (Object item : collectionValue) {
                sanitizedList.add(sanitizeValue(item, visited));
            }
            visited.remove(collectionValue);
            return sanitizedList;
        }

        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<Object> sanitizedArray = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                sanitizedArray.add(sanitizeValue(java.lang.reflect.Array.get(value, i), visited));
            }
            return sanitizedArray;
        }

        return value.toString();
    }
}
