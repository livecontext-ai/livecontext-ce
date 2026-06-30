package com.apimarketplace.datasource.persistence.nested;

import com.apimarketplace.datasource.domain.DataSourceNestedModels.DataSourceNestedRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Row mapper for DataSourceNestedRow (array type).
 * Transforms array elements into Map format for consistency.
 *
 * Extracted from DataSourceNestedRepositories for Single Responsibility Principle.
 *
 * @see DataSourceNestedRepositories
 */
public class NestedArrayRowMapper implements RowMapper<DataSourceNestedRow> {

    private final String jsonPath;
    private final ObjectMapper objectMapper;

    public NestedArrayRowMapper(String jsonPath, ObjectMapper objectMapper) {
        this.jsonPath = jsonPath;
        this.objectMapper = objectMapper;
    }

    @Override
    public DataSourceNestedRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        try {
            Long id = rs.getLong("id");
            Long dataSourceId = rs.getLong("data_source_id");
            String tenantId = rs.getString("tenant_id");
            Integer arrayIndex = extractArrayIndex(rs);
            Integer priority = rs.getInt("priority");
            OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);

            // Parse array element
            String arrayElementJson = rs.getString("array_element");
            Object arrayElement = parseArrayElement(arrayElementJson);

            // Transform array element into Map format
            Map<String, Object> nestedData = transformToMap(arrayElement, arrayIndex);

            // Calculate parent path
            String parentPath = calculateParentPath(jsonPath);

            return new DataSourceNestedRow(
                id,
                dataSourceId,
                tenantId,
                jsonPath,
                parentPath,
                nestedData,
                priority,
                createdAt != null ? createdAt.toInstant() : Instant.now(),
                null // updated_at not available
            );
        } catch (Exception e) {
            throw new RuntimeException("Error mapping DataSourceNestedArrayRow", e);
        }
    }

    /**
     * Extract array_index from result set, handling missing column.
     */
    private Integer extractArrayIndex(ResultSet rs) {
        try {
            int index = rs.getInt("array_index");
            if (!rs.wasNull()) {
                return index;
            }
        } catch (SQLException e) {
            // Column doesn't exist (not an array), ignore
        }
        return null;
    }

    /**
     * Parse array element JSON string.
     */
    private Object parseArrayElement(String arrayElementJson) {
        if (arrayElementJson == null) {
            return null;
        }
        try {
            return objectMapper.readValue(arrayElementJson, Object.class);
        } catch (Exception e) {
            // If parsing fails, treat as string
            return arrayElementJson;
        }
    }

    /**
     * Transform array element into Map format for consistency.
     */
    private Map<String, Object> transformToMap(Object arrayElement, Integer arrayIndex) {
        Map<String, Object> nestedData = new HashMap<>();

        if (arrayElement instanceof Map) {
            // If element is an object, use it directly
            @SuppressWarnings("unchecked")
            Map<String, Object> elementMap = (Map<String, Object>) arrayElement;
            nestedData.putAll(elementMap);
        } else {
            // If element is primitive, wrap it in "value" key
            nestedData.put("value", arrayElement);
        }

        // Add array_index only if it exists (for arrays)
        if (arrayIndex != null) {
            nestedData.put("array_index", arrayIndex);
        }

        return nestedData;
    }

    /**
     * Calculate parent path from current path.
     */
    private String calculateParentPath(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        String[] parts = path.split("\\.");
        if (parts.length > 1) {
            return String.join(".", Arrays.copyOf(parts, parts.length - 1));
        }
        return null;
    }
}
