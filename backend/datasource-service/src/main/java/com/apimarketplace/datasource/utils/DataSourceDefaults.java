package com.apimarketplace.datasource.utils;

import com.apimarketplace.datasource.domain.ColumnStructure;
import com.apimarketplace.datasource.domain.ColumnType;
import com.apimarketplace.datasource.domain.DataSourceModels.ColumnMappingSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Utility class for generating default column configurations for DataSources.
 * Ensures that all newly created datasources have proper column_order and mapping_spec.
 */
public final class DataSourceDefaults {

    private DataSourceDefaults() {}

    /**
     * System columns that should always be present in column_order.
     * These are virtual columns used for UI display (not part of actual data).
     */
    private static final List<String> SYSTEM_COLUMNS = List.of(
        "checkbox",   // Selection checkbox
        "index",      // Row number
        "id",         // Item ID
        "priority",   // Priority field
        "created_at"  // Creation timestamp
    );

    /**
     * Generate default column order including system columns and data fields.
     *
     * @param dataFields Set of field names from the actual data
     * @return List of column order entries
     */
    public static List<Map<String, Object>> generateColumnOrder(Set<String> dataFields) {
        List<Map<String, Object>> columnOrder = new ArrayList<>();
        int order = 0;

        // Add system columns first
        for (String systemCol : SYSTEM_COLUMNS) {
            columnOrder.add(Map.of("field", systemCol, "order", order++));
        }

        // Add data fields after system columns
        for (String field : dataFields) {
            // Skip if already in system columns
            if (!SYSTEM_COLUMNS.contains(field)) {
                columnOrder.add(Map.of("field", field, "order", order++));
            }
        }

        return columnOrder;
    }

    /**
     * Generate default mapping spec from data items.
     * Analyzes the first item to discover fields and their types.
     *
     * @param data List of data items
     * @return Mapping spec for all discovered fields
     */
    public static Map<String, ColumnMappingSpec> generateMappingSpec(List<Map<String, Object>> data) {
        if (data == null || data.isEmpty()) {
            return Map.of();
        }

        // Use LinkedHashMap to preserve insertion order
        Map<String, ColumnMappingSpec> mappingSpec = new LinkedHashMap<>();

        // Analyze first item to discover fields
        Map<String, Object> firstItem = data.get(0);

        for (Map.Entry<String, Object> entry : firstItem.entrySet()) {
            String fieldName = entry.getKey();
            Object value = entry.getValue();

            ColumnType type = inferColumnType(value);
            ColumnStructure structure = inferColumnStructure(value);
            String path = "data." + fieldName;

            mappingSpec.put(fieldName, new ColumnMappingSpec(
                path,
                type,
                structure,
                Map.of(),  // No children for simple discovery
                Map.of()   // No display options
            ));
        }

        return mappingSpec;
    }

    /**
     * Infer column type from a value.
     */
    private static ColumnType inferColumnType(Object value) {
        if (value == null) {
            return ColumnType.TEXT;
        }

        if (value instanceof Number) {
            return ColumnType.NUMBER;
        }

        if (value instanceof Boolean) {
            return ColumnType.CHECKBOX;
        }

        if (value instanceof List) {
            return ColumnType.MULTI_SELECT;
        }

        if (value instanceof Map) {
            return ColumnType.TEXT;
        }

        // Check for special string patterns
        if (value instanceof String str) {
            // Check for date patterns (ISO format)
            if (str.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
                return ColumnType.DATE;
            }
            // Check for email patterns
            if (str.matches("^[\\w.+-]+@[\\w.-]+\\.[a-zA-Z]{2,}$")) {
                return ColumnType.EMAIL;
            }
            // Check for URL patterns
            if (str.startsWith("http://") || str.startsWith("https://")) {
                return ColumnType.URL;
            }
        }

        return ColumnType.TEXT;
    }

    /**
     * Infer column structure from a value.
     */
    private static ColumnStructure inferColumnStructure(Object value) {
        if (value == null) {
            return ColumnStructure.SCALAR;
        }

        if (value instanceof List) {
            return ColumnStructure.ARRAY;
        }

        if (value instanceof Map) {
            return ColumnStructure.OBJECT;
        }

        return ColumnStructure.SCALAR;
    }

    /**
     * Extract all field names from a list of data items.
     */
    public static Set<String> extractFieldNames(List<Map<String, Object>> data) {
        if (data == null || data.isEmpty()) {
            return Set.of();
        }

        // Use LinkedHashSet to preserve order
        Set<String> fields = new java.util.LinkedHashSet<>();
        for (Map<String, Object> item : data) {
            fields.addAll(item.keySet());
        }

        return fields;
    }
}
