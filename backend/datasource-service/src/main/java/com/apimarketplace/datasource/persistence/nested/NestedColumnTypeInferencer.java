package com.apimarketplace.datasource.persistence.nested;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Infers column types from JSON values.
 *
 * Extracted from DataSourceNestedRepositories for Single Responsibility Principle.
 *
 * @see DataSourceNestedRepositories
 */
@Component
public class NestedColumnTypeInferencer {

    /**
     * Infer column type from a value.
     *
     * @param value The value to analyze
     * @return The inferred type (text, number, boolean, date, json)
     */
    public String inferColumnType(Object value) {
        if (value == null) {
            return "text";
        }

        if (value instanceof Number) {
            return "number";
        }

        if (value instanceof Boolean) {
            return "checkbox";
        }

        if (value instanceof String) {
            // Try to detect date format
            if (value.toString().matches("\\d{4}-\\d{2}-\\d{2}.*")) {
                return "date";
            }
            return "text";
        }

        if (value instanceof Map) {
            return "text";
        }

        if (value instanceof List) {
            return "text"; // Array stored as JSON
        }

        return "text";
    }

    /**
     * Infer column type from array elements.
     *
     * @param key The key to analyze (for object arrays) or "value" for primitive arrays
     * @param sampleElements Sample elements from the array
     * @return The inferred type
     */
    public String inferColumnTypeFromArrayElements(String key, List<Object> sampleElements) {
        if (sampleElements == null || sampleElements.isEmpty()) {
            return "text";
        }

        // Collect all values for the given key (if elements are objects) or the primitive values themselves
        List<Object> valuesToInfer = new ArrayList<>();
        for (Object element : sampleElements) {
            if (element instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> elementMap = (Map<String, Object>) element;
                if (elementMap.containsKey(key)) {
                    valuesToInfer.add(elementMap.get(key));
                }
            } else if (key.equals("value")) {
                // For primitive arrays, the "value" column
                valuesToInfer.add(element);
            }
        }

        if (valuesToInfer.isEmpty()) {
            return "text";
        }

        return inferColumnType(valuesToInfer.get(0));
    }
}
