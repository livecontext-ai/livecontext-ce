package com.apimarketplace.orchestrator.persistence;

import com.apimarketplace.orchestrator.domain.StorageNestedModels.StorageNestedRow;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

/**
 * Repository for nested JSON data extraction in Storage
 * Supports hierarchical navigation through JSON structures using PostgreSQL JSONB
 * Optimized to extract only needed data at SQL level without loading entire JSON
 */
@Repository
public class StorageNestedRepositories {

    private static final int MAX_JSON_PATH_SEGMENT_LENGTH = 256;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public StorageNestedRepositories(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Extract nested data from storage at a specific JSON path
     * Supports both objects and arrays automatically
     * Uses PostgreSQL JSONB operators for direct extraction at SQL level
     *
     * @param storageId ID of the storage (UUID)
     * @param tenantId Tenant ID for isolation
     * @param jsonPath JSON path to extract (e.g., "output.itemIndex" or "")
     * @param page Page number (1-based)
     * @param limit Page size
     * @param sortBy Sort column (optional)
     * @param sortOrder Sort direction (asc/desc, optional)
     * @return List of nested rows
     */
    public List<StorageNestedRow> findNestedData(
            java.util.UUID storageId,
            String tenantId,
            String jsonPath,
            Integer page,
            Integer limit,
            String sortBy,
            String sortOrder) {

        // Automatically detect if it's an object or an array
        List<Object> detectTypeParams = new ArrayList<>();
        String detectTypeSelectPath = buildJsonbPath(jsonPath, detectTypeParams);
        StringBuilder detectTypeSql = new StringBuilder();
        detectTypeSql.append("SELECT jsonb_typeof(").append(detectTypeSelectPath).append(") as path_type ");
        detectTypeSql.append("FROM storage.storage ");
        detectTypeSql.append("WHERE id = ? AND tenant_id = ? ");
        detectTypeParams.add(storageId);
        detectTypeParams.add(tenantId);
        String detectTypeWherePath = buildJsonbPath(jsonPath, detectTypeParams);
        detectTypeSql.append("  AND ").append(detectTypeWherePath).append(" IS NOT NULL ");
        detectTypeSql.append("LIMIT 1 ");

        String pathType = null;
        try {
            List<String> types = jdbcTemplate.queryForList(detectTypeSql.toString(), String.class, detectTypeParams.toArray());
            if (!types.isEmpty()) {
                pathType = types.get(0);
            }
        } catch (Exception e) {
            // On error, fall back to object by default
            pathType = "object";
        }

        // If it's an array, use jsonb_array_elements
        if ("array".equals(pathType)) {
            return findNestedArrayData(storageId, tenantId, jsonPath, page, limit, sortBy, sortOrder);
        }

        // Otherwise, handle as object
        List<Object> params = new ArrayList<>();
        String nestedDataPath = buildJsonbPath(jsonPath, params);
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
        sql.append("  id, ");
        sql.append("  tenant_id, ");
        sql.append("  ").append(nestedDataPath).append(" as nested_data, ");
        sql.append("  created_at ");
        sql.append("FROM storage.storage ");
        sql.append("WHERE id = ? AND tenant_id = ? ");
        params.add(storageId);
        params.add(tenantId);
        sql.append("  AND ").append(buildJsonbPath(jsonPath, params)).append(" IS NOT NULL ");

        // If jsonPath is not empty, verify that it's an object
        if (jsonPath != null && !jsonPath.isEmpty()) {
            sql.append("  AND jsonb_typeof(").append(buildJsonbPath(jsonPath, params)).append(") = 'object' ");
        }

        // Sort
        if (sortBy != null && !sortBy.trim().isEmpty()) {
            String sortSource = buildJsonbPath(jsonPath, params);
            String sortPath = buildJsonbTextPath(sortSource, sortBy, params);
            sql.append("ORDER BY ").append(sortPath).append(" ");
            if (sortOrder != null && sortOrder.equalsIgnoreCase("desc")) {
                sql.append("DESC ");
            } else {
                sql.append("ASC ");
            }
        } else {
            sql.append("ORDER BY created_at DESC ");
        }

        // Pagination
        int offset = (page - 1) * limit;
        sql.append("LIMIT ? OFFSET ? ");
        params.add(limit);
        params.add(offset);

        return jdbcTemplate.query(sql.toString(), new StorageNestedRowMapper(storageId, jsonPath, objectMapper), params.toArray());
    }

    /**
     * Extract array data from storage at a specific JSON path
     * Uses jsonb_array_elements to expand arrays into rows
     */
    private List<StorageNestedRow> findNestedArrayData(
            java.util.UUID storageId,
            String tenantId,
            String jsonPath,
            Integer page,
            Integer limit,
            String sortBy,
            String sortOrder) {

        List<Object> params = new ArrayList<>();
        String arraySourcePath = buildJsonbPath("s.data", jsonPath, params);
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
        sql.append("  s.id, ");
        sql.append("  s.tenant_id, ");
        sql.append("  (elem.ordinality - 1)::int as array_index, ");
        sql.append("  elem.value as array_element ");
        sql.append("FROM storage.storage s, ");
        sql.append("  LATERAL jsonb_array_elements(").append(arraySourcePath).append(") WITH ORDINALITY AS elem(value, ordinality) ");
        sql.append("WHERE s.id = ? AND s.tenant_id = ? ");
        params.add(storageId);
        params.add(tenantId);
        sql.append("  AND ").append(buildJsonbPath("s.data", jsonPath, params)).append(" IS NOT NULL ");
        sql.append("  AND jsonb_typeof(").append(buildJsonbPath("s.data", jsonPath, params)).append(") = 'array' ");

        // Sort
        if (sortBy != null && !sortBy.trim().isEmpty()) {
            // For arrays, sort on a field within the element
            String sortPath = buildJsonbTextPath("elem.value", sortBy, params);
            sql.append("ORDER BY ").append(sortPath).append(" ");
            if (sortOrder != null && sortOrder.equalsIgnoreCase("desc")) {
                sql.append("DESC ");
            } else {
                sql.append("ASC ");
            }
        } else {
            sql.append("ORDER BY elem.ordinality ASC ");
        }

        // Pagination
        int offset = (page - 1) * limit;
        sql.append("LIMIT ? OFFSET ? ");
        params.add(limit);
        params.add(offset);

        return jdbcTemplate.query(sql.toString(), new StorageNestedArrayRowMapper(storageId, jsonPath, objectMapper), params.toArray());
    }

    /**
     * Count nested data rows at a specific JSON path
     * Supports both objects and arrays automatically
     *
     * @param storageId ID of the storage
     * @param tenantId Tenant ID for isolation
     * @param jsonPath JSON path to extract
     * @return Total count
     */
    public int countNestedData(java.util.UUID storageId, String tenantId, String jsonPath) {
        // Detect the type (object or array)
        List<Object> detectTypeParams = new ArrayList<>();
        String detectTypeSelectPath = buildJsonbPath(jsonPath, detectTypeParams);
        StringBuilder detectTypeSql = new StringBuilder();
        detectTypeSql.append("SELECT jsonb_typeof(").append(detectTypeSelectPath).append(") as path_type ");
        detectTypeSql.append("FROM storage.storage ");
        detectTypeSql.append("WHERE id = ? AND tenant_id = ? ");
        detectTypeParams.add(storageId);
        detectTypeParams.add(tenantId);
        String detectTypeWherePath = buildJsonbPath(jsonPath, detectTypeParams);
        detectTypeSql.append("  AND ").append(detectTypeWherePath).append(" IS NOT NULL ");
        detectTypeSql.append("LIMIT 1 ");

        String pathType = null;
        try {
            List<String> types = jdbcTemplate.queryForList(detectTypeSql.toString(), String.class, detectTypeParams.toArray());
            if (!types.isEmpty()) {
                pathType = types.get(0);
            }
        } catch (Exception e) {
            pathType = "object";
        }

        // If it's an array, count the elements
        if ("array".equals(pathType)) {
            List<Object> params = new ArrayList<>();
            String countPath = buildJsonbPath(jsonPath, params);
            StringBuilder sql = new StringBuilder();
            sql.append("SELECT COALESCE(jsonb_array_length(").append(countPath).append("), 0) ");
            sql.append("FROM storage.storage ");
            sql.append("WHERE id = ? AND tenant_id = ? ");
            params.add(storageId);
            params.add(tenantId);
            sql.append("  AND ").append(buildJsonbPath(jsonPath, params)).append(" IS NOT NULL ");
            sql.append("  AND jsonb_typeof(").append(buildJsonbPath(jsonPath, params)).append(") = 'array' ");
            Integer count = jdbcTemplate.queryForObject(sql.toString(), Integer.class, params.toArray());
            return count != null ? count : 0;
        }

        // Otherwise, count the objects (always 1 for storage since there's only one row)
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(*) ");
        sql.append("FROM storage.storage ");
        sql.append("WHERE id = ? AND tenant_id = ? ");
        params.add(storageId);
        params.add(tenantId);
        sql.append("  AND ").append(buildJsonbPath(jsonPath, params)).append(" IS NOT NULL ");

        Integer count = jdbcTemplate.queryForObject(sql.toString(), Integer.class, params.toArray());
        return count != null ? count : 0;
    }

    /**
     * Build PostgreSQL JSONB path from dot-separated path
     * Example: "output.itemIndex" -> "jsonb_extract_path(data, ?, ?)"
     * Example: "" -> "data"
     */
    private String buildJsonbPath(String jsonPath, List<Object> params) {
        return buildJsonbPath("data", jsonPath, params);
    }

    private String buildJsonbPath(String rootExpression, String jsonPath, List<Object> params) {
        requireParams(params);
        List<String> segments = parsePathSegments(jsonPath);
        if (segments.isEmpty()) {
            return rootExpression;
        }
        params.addAll(segments);
        return "jsonb_extract_path(" + rootExpression + ", " + placeholders(segments.size()) + ")";
    }

    private String buildJsonbTextPath(String rootExpression, String fieldName, List<Object> params) {
        requireParams(params);
        params.add(normalizeJsonFieldName(fieldName));
        return "jsonb_extract_path_text(" + rootExpression + ", ?)";
    }

    private List<String> parsePathSegments(String jsonPath) {
        if (jsonPath == null || jsonPath.trim().isEmpty()) {
            return List.of();
        }
        String[] parts = jsonPath.split("\\.");
        List<String> segments = new ArrayList<>(parts.length);
        for (String part : parts) {
            validateJsonPathSegment(part, jsonPath);
            segments.add(part);
        }
        return segments;
    }

    private void validateJsonPathSegment(String segment, String originalPath) {
        if (segment == null || segment.isEmpty()
                || segment.length() > MAX_JSON_PATH_SEGMENT_LENGTH
                || segment.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Invalid JSON path segment in path: " + originalPath);
        }
    }

    private String normalizeJsonFieldName(String fieldName) {
        String normalized = fieldName == null ? "" : fieldName.trim();
        validateJsonPathSegment(normalized, normalized);
        return normalized;
    }

    private String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    private void requireParams(List<Object> params) {
        if (params == null) {
            throw new IllegalArgumentException("params cannot be null");
        }
    }

    /**
     * RowMapper for StorageNestedRow (objects)
     * Transforms JSONB data into Map format
     */
    private static class StorageNestedRowMapper implements RowMapper<StorageNestedRow> {
        private final java.util.UUID storageId;
        private final String jsonPath;
        private final ObjectMapper objectMapper;

        public StorageNestedRowMapper(java.util.UUID storageId, String jsonPath, ObjectMapper objectMapper) {
            this.storageId = storageId;
            this.jsonPath = jsonPath;
            this.objectMapper = objectMapper;
        }

        @Override
        public StorageNestedRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            try {
                java.util.UUID id = (java.util.UUID) rs.getObject("id");
                String tenantId = rs.getString("tenant_id");
                String nestedDataJson = rs.getString("nested_data");
                java.sql.Timestamp createdAt = rs.getTimestamp("created_at");

                // Parse nested data
                Map<String, Object> nestedData = new HashMap<>();
                if (nestedDataJson != null) {
                    nestedData = objectMapper.readValue(nestedDataJson, new TypeReference<Map<String, Object>>() {});
                }

                // Calculate parent path
                String parentPath = null;
                if (jsonPath != null && !jsonPath.isEmpty()) {
                    String[] parts = jsonPath.split("\\.");
                    if (parts.length > 1) {
                        parentPath = String.join(".", Arrays.copyOf(parts, parts.length - 1));
                    }
                }

                return new StorageNestedRow(
                        0L, // ID = 0 for single object
                        storageId,
                        tenantId,
                        jsonPath,
                        parentPath,
                        nestedData,
                        createdAt != null ? createdAt.toInstant() : Instant.now()
                );
            } catch (Exception e) {
                throw new RuntimeException("Error mapping StorageNestedRow", e);
            }
        }
    }

    /**
     * RowMapper for StorageNestedRow (arrays)
     * Transforms array elements into Map format for consistency
     */
    private static class StorageNestedArrayRowMapper implements RowMapper<StorageNestedRow> {
        private final java.util.UUID storageId;
        private final String jsonPath;
        private final ObjectMapper objectMapper;

        public StorageNestedArrayRowMapper(java.util.UUID storageId, String jsonPath, ObjectMapper objectMapper) {
            this.storageId = storageId;
            this.jsonPath = jsonPath;
            this.objectMapper = objectMapper;
        }

        @Override
        public StorageNestedRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            try {
                java.util.UUID id = (java.util.UUID) rs.getObject("id");
                String tenantId = rs.getString("tenant_id");
                Integer arrayIndex = rs.getInt("array_index");
                String arrayElementJson = rs.getString("array_element");

                // Parse array element
                Map<String, Object> nestedData = new HashMap<>();
                String structureType = "normal";

                if (arrayElementJson != null) {
                    try {
                        // Try to parse as object
                        Map<String, Object> elementMap = objectMapper.readValue(arrayElementJson, new TypeReference<Map<String, Object>>() {});
                        nestedData.putAll(elementMap);
                        structureType = "object";
                    } catch (Exception e) {
                        // If it's not an object, handle as primitive value
                        nestedData.put("value", parsePrimitiveValue(arrayElementJson));
                        structureType = "primitive";
                    }
                }

                // Add metadata
                nestedData.put("_structure_type", structureType);
                nestedData.put("_item_index", arrayIndex);

                // Calculate parent path
                String parentPath = null;
                if (jsonPath != null && !jsonPath.isEmpty()) {
                    String[] parts = jsonPath.split("\\.");
                    if (parts.length > 1) {
                        parentPath = String.join(".", Arrays.copyOf(parts, parts.length - 1));
                    }
                }

                return new StorageNestedRow(
                        (long) arrayIndex, // ID = index in the array
                        storageId,
                        tenantId,
                        jsonPath,
                        parentPath,
                        nestedData,
                        Instant.now()
                );
            } catch (Exception e) {
                throw new RuntimeException("Error mapping StorageNestedArrayRow", e);
            }
        }

        /**
         * Parse primitive value from JSON string
         */
        private Object parsePrimitiveValue(String jsonStr) {
            if (jsonStr == null) return null;

            // Remove quotes if it's a JSON string
            String trimmed = jsonStr.trim();
            if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                return trimmed.substring(1, trimmed.length() - 1);
            }

            // Try to parse as number
            try {
                if (trimmed.contains(".")) {
                    return Double.parseDouble(trimmed);
                } else {
                    return Long.parseLong(trimmed);
                }
            } catch (NumberFormatException e) {
                // Try as boolean
                if ("true".equalsIgnoreCase(trimmed)) return true;
                if ("false".equalsIgnoreCase(trimmed)) return false;

                // Return as string
                return trimmed;
            }
        }
    }
}

