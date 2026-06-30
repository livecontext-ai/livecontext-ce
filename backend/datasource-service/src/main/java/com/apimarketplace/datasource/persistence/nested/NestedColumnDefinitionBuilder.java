package com.apimarketplace.datasource.persistence.nested;

import com.apimarketplace.datasource.domain.DataSourceNestedModels.NestedColumnDefinition;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Builds column definitions for nested data.
 *
 * Extracted from DataSourceNestedRepositories for Single Responsibility Principle.
 *
 * @see DataSourceNestedRepositories
 */
@Component
public class NestedColumnDefinitionBuilder {

    private final NestedColumnTypeInferencer typeInferencer;
    private final NestedDataAnalyzer dataAnalyzer;

    public NestedColumnDefinitionBuilder(
            NestedColumnTypeInferencer typeInferencer,
            NestedDataAnalyzer dataAnalyzer) {
        this.typeInferencer = typeInferencer;
        this.dataAnalyzer = dataAnalyzer;
    }

    /**
     * Build column definitions from sample object data.
     *
     * @param sampleData List of sample data maps
     * @return List of column definitions
     */
    public List<NestedColumnDefinition> buildFromObjectData(List<Map<String, Object>> sampleData) {
        // Analyze structure to determine columns
        Map<String, String> columnTypes = new HashMap<>();
        Set<String> allKeys = new HashSet<>();

        for (Map<String, Object> data : sampleData) {
            allKeys.addAll(data.keySet());
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();

                if (!columnTypes.containsKey(key)) {
                    String type = typeInferencer.inferColumnType(value);
                    columnTypes.put(key, type);
                }
            }
        }

        // Create column definitions
        List<NestedColumnDefinition> columns = new ArrayList<>();
        for (String key : allKeys) {
            String type = columnTypes.getOrDefault(key, "text");
            boolean isNavigable = dataAnalyzer.isNavigableJson(sampleData, key);

            columns.add(new NestedColumnDefinition(
                key,
                key,
                key,
                type,
                true, // editable
                !type.equals("json"), // sortable (not for JSON)
                true, // filterable
                isNavigable,
                null, // width
                null  // flex
            ));
        }

        return columns;
    }

    /**
     * Build column definitions from sample array elements.
     *
     * @param sampleElements List of sample array elements
     * @return List of column definitions
     */
    public List<NestedColumnDefinition> buildFromArrayData(List<Object> sampleElements) {
        // Determine element types
        Set<String> allKeys = new HashSet<>();
        boolean hasObjects = false;
        boolean hasPrimitives = false;

        for (Object element : sampleElements) {
            if (element instanceof Map) {
                hasObjects = true;
                @SuppressWarnings("unchecked")
                Map<String, Object> elementMap = (Map<String, Object>) element;
                allKeys.addAll(elementMap.keySet());
            } else {
                hasPrimitives = true;
            }
        }

        List<NestedColumnDefinition> columns = new ArrayList<>();

        // If array contains objects, create column for each key
        if (hasObjects && !allKeys.isEmpty()) {
            for (String key : allKeys) {
                String type = typeInferencer.inferColumnTypeFromArrayElements(key, sampleElements);
                boolean isNavigable = dataAnalyzer.isNavigableJsonInArray(key, sampleElements);

                columns.add(new NestedColumnDefinition(
                    key,
                    capitalizeFirst(key),
                    key,
                    type,
                    true,
                    !type.equals("json"),
                    true,
                    isNavigable,
                    null,
                    null
                ));
            }
        } else {
            // Primitive array: index and value columns
            columns.add(new NestedColumnDefinition(
                "array_index",
                "Index",
                "array_index",
                "number",
                false,
                true,
                false,
                false,
                null,
                null
            ));
            columns.add(new NestedColumnDefinition(
                "value",
                "Value",
                "value",
                typeInferencer.inferColumnTypeFromArrayElements("value", sampleElements),
                true,
                true,
                true,
                false,
                null,
                null
            ));
        }

        return columns;
    }

    /**
     * Capitalize first letter of a string.
     */
    private String capitalizeFirst(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
}
