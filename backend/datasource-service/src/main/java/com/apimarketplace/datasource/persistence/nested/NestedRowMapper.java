package com.apimarketplace.datasource.persistence.nested;

import com.apimarketplace.datasource.domain.DataSourceNestedModels.DataSourceNestedRow;
import com.fasterxml.jackson.core.type.TypeReference;
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
 * Row mapper for DataSourceNestedRow (object type).
 *
 * Extracted from DataSourceNestedRepositories for Single Responsibility Principle.
 *
 * @see DataSourceNestedRepositories
 */
public class NestedRowMapper implements RowMapper<DataSourceNestedRow> {

    private final String jsonPath;
    private final ObjectMapper objectMapper;

    public NestedRowMapper(String jsonPath, ObjectMapper objectMapper) {
        this.jsonPath = jsonPath;
        this.objectMapper = objectMapper;
    }

    @Override
    public DataSourceNestedRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        try {
            Long id = rs.getLong("id");
            Long dataSourceId = rs.getLong("data_source_id");
            String tenantId = rs.getString("tenant_id");
            String nestedDataJson = rs.getString("nested_data");
            Integer priority = rs.getInt("priority");
            OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);

            // Parse nested data
            Map<String, Object> nestedData = new HashMap<>();
            if (nestedDataJson != null) {
                nestedData = objectMapper.readValue(nestedDataJson, new TypeReference<Map<String, Object>>() {});
            }

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
            throw new RuntimeException("Error mapping DataSourceNestedRow", e);
        }
    }

    /**
     * Calculate parent path from current path.
     * Example: "metadata.user.profile" -> "metadata.user"
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
