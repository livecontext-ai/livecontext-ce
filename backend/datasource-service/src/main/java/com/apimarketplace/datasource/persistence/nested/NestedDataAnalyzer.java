package com.apimarketplace.datasource.persistence.nested;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Analyzes nested JSON data structure (object vs array detection, navigability).
 *
 * Extracted from DataSourceNestedRepositories for Single Responsibility Principle.
 *
 * @see DataSourceNestedRepositories
 */
@Component
public class NestedDataAnalyzer {

    private final JdbcTemplate jdbcTemplate;
    private final JsonPathBuilder jsonPathBuilder;

    public NestedDataAnalyzer(JdbcTemplate jdbcTemplate, JsonPathBuilder jsonPathBuilder) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonPathBuilder = jsonPathBuilder;
    }

    /**
     * Detect the JSON type at a specific path (object, array, or null).
     *
     * @param dataSourceId ID of the data source
     * @param tenantId Tenant ID for isolation
     * @param jsonPath JSON path to analyze
     * @return The detected type ("object", "array", or "object" as default)
     */
    public String detectPathType(Long dataSourceId, String tenantId, String jsonPath) {
        List<Object> params = new java.util.ArrayList<>();
        params.add(tenantId);
        params.add(dataSourceId);

        StringBuilder detectTypeSql = new StringBuilder();
        detectTypeSql.append("WITH filtered_items AS (");
        detectTypeSql.append("  SELECT data FROM data_source_items ");
        detectTypeSql.append("  WHERE tenant_id = ? AND data_source_id = ? ");
        detectTypeSql.append(") ");
        detectTypeSql.append("SELECT jsonb_typeof(")
                .append(jsonPathBuilder.buildJsonbPath(jsonPath, params))
                .append(") as path_type ");
        detectTypeSql.append("FROM filtered_items ");
        detectTypeSql.append("WHERE ")
                .append(jsonPathBuilder.buildJsonbPath(jsonPath, params))
                .append(" IS NOT NULL ");
        detectTypeSql.append("LIMIT 1 ");

        try {
            List<String> types = jdbcTemplate.queryForList(detectTypeSql.toString(), String.class, params.toArray());
            if (!types.isEmpty()) {
                return types.get(0);
            }
        } catch (Exception e) {
            // If error, return default
        }
        return "object";
    }

    /**
     * Check if a key in sample data is navigable (contains nested object/array).
     *
     * @param sampleData List of sample data objects
     * @param key The key to check
     * @return true if the key contains navigable JSON
     */
    public boolean isNavigableJson(List<Map<String, Object>> sampleData, String key) {
        for (Map<String, Object> data : sampleData) {
            Object value = data.get(key);
            if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> mapValue = (Map<String, Object>) value;
                if (!mapValue.isEmpty()) {
                    return true;
                }
            }
            if (value instanceof List) {
                @SuppressWarnings("unchecked")
                List<?> listValue = (List<?>) value;
                if (!listValue.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Check if a key in array elements is navigable (contains nested object/array).
     *
     * @param key The key to check
     * @param sampleElements Sample elements from the array
     * @return true if the key contains navigable JSON
     */
    public boolean isNavigableJsonInArray(String key, List<Object> sampleElements) {
        for (Object element : sampleElements) {
            if (element instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> elementMap = (Map<String, Object>) element;
                Object value = elementMap.get(key);
                if (value instanceof Map || value instanceof List) {
                    return true;
                }
            }
        }
        return false;
    }
}
