package com.apimarketplace.datasource.persistence;

import com.apimarketplace.datasource.domain.DataSourceEnhancedModels.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Repository for DataSource bulk operations.
 * Handles item CRUD, JSON patch, and bulk operations.
 */
@Repository
public class DataSourceBulkOperationRepository {

    private static final Logger logger = LoggerFactory.getLogger(DataSourceBulkOperationRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public DataSourceBulkOperationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Add a new item to a DataSource.
     *
     * @param dataSourceId ID of the data source
     * @param tenantId     Tenant ID for isolation
     * @param data         Data for the new item
     * @param priority     Priority of the new item
     * @return Created item
     */
    public DataSourceItemRow addItem(Long dataSourceId, String tenantId, Map<String, Object> data, Integer priority) {
        String sql = """
            INSERT INTO data_source_items (data_source_id, tenant_id, data, priority, created_at, updated_at)
            VALUES (?, ?, ?::jsonb, ?, NOW(), NOW())
            RETURNING id, data_source_id, tenant_id, data, priority, created_at, updated_at
            """;

        try {
            return jdbcTemplate.queryForObject(sql, new DataSourceItemRowMapper(),
                dataSourceId, tenantId, objectMapper.writeValueAsString(data), priority);
        } catch (Exception e) {
            logger.error("Error adding item: {}", e.getMessage());
            throw new RuntimeException("Failed to add item", e);
        }
    }

    /**
     * Delete a single item.
     *
     * @param dataSourceId ID of the data source
     * @param tenantId     Tenant ID for isolation
     * @param itemId       ID of the item to delete
     */
    public void deleteItem(Long dataSourceId, String tenantId, Long itemId) {
        String sql = """
            DELETE FROM data_source_items
            WHERE id = ? AND data_source_id = ? AND tenant_id = ?
            """;

        jdbcTemplate.update(sql, itemId, dataSourceId, tenantId);
    }

    /**
     * Apply JSON patch operations to a single item.
     *
     * @param dataSourceId ID of the data source
     * @param tenantId     Tenant ID for isolation
     * @param itemId       ID of the item to update
     * @param patches      List of JSON patch operations
     * @return Updated item
     */
    public DataSourceItemRow applyJsonPatch(
            Long dataSourceId,
            String tenantId,
            Long itemId,
            List<JsonPatchOperation> patches) {

        StringBuilder jsonbSetChain = new StringBuilder("data");

        logger.debug("applyJsonPatch dsId={} tenant={} itemId={} patches={}", dataSourceId, tenantId, itemId, patches);
        for (JsonPatchOperation patch : patches) {
            String path = patch.path();
            Object value = patch.value();

            String pgPath = toPostgresJsonPath(path);

            // Convert value to JSONB
            String jsonValue;
            try {
                jsonValue = objectMapper.writeValueAsString(value);
            } catch (Exception serEx) {
                jsonValue = value instanceof String ? "\"" + value + "\"" : String.valueOf(value);
            }
            jsonValue = escapeSqlLiteral(jsonValue);

            if (patch.op() == PatchOperation.REMOVE) {
                jsonbSetChain = new StringBuilder(
                    String.format("(%s #- '%s')", jsonbSetChain, pgPath)
                );
            } else {
                jsonbSetChain = new StringBuilder(
                    String.format("jsonb_set(%s, '%s', '%s'::jsonb, true)",
                                  jsonbSetChain, pgPath, jsonValue)
                );
            }
        }

        String sql = String.format("""
            UPDATE data_source_items
            SET data = %s, updated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND data_source_id = ? AND tenant_id = ?
            RETURNING id, data_source_id, tenant_id, data, priority, created_at, updated_at
            """, jsonbSetChain);

        try {
            return jdbcTemplate.queryForObject(sql, new DataSourceItemRowMapper(),
                itemId, dataSourceId, tenantId);
        } catch (Exception e) {
            logger.error("applyJsonPatch SQL error dsId={} itemId={} sql={}", dataSourceId, itemId, sql);
            throw e;
        }
    }

    private static String toPostgresJsonPath(String path) {
        String normalized = path == null ? "" : path.trim();
        if (normalized.startsWith("data.")) {
            normalized = normalized.substring("data.".length());
        } else if (normalized.startsWith("data/")) {
            normalized = normalized.substring("data/".length());
        } else if (normalized.startsWith("$.")) {
            normalized = normalized.substring("$.".length());
        }

        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Patch path must target a data field");
        }

        String[] segments = normalized.contains("/")
                ? normalized.split("/")
                : normalized.split("\\.");
        String pgPath = Arrays.stream(segments)
                .map(String::trim)
                .filter(segment -> !segment.isEmpty())
                .map(DataSourceBulkOperationRepository::escapePostgresPathSegment)
                .collect(Collectors.joining(","));
        if (pgPath.isBlank()) {
            throw new IllegalArgumentException("Patch path must target a data field");
        }
        return "{" + pgPath + "}";
    }

    private static String escapePostgresPathSegment(String segment) {
        return "\"" + segment.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String escapeSqlLiteral(String value) {
        return value == null ? "null" : value.replace("'", "''");
    }

    /**
     * Execute bulk operations (delete or patch multiple items).
     *
     * @param dataSourceId ID of the data source
     * @param tenantId     Tenant ID for isolation
     * @param request      Bulk operation request
     * @return Bulk operation result
     */
    public BulkOperationResult executeBulkOperation(
            Long dataSourceId,
            String tenantId,
            BulkOperationRequest request) {

        List<ItemOperationResult> results = new ArrayList<>();
        int processedCount = 0;
        int failedCount = 0;
        List<String> errors = new ArrayList<>();

        for (Long itemId : request.ids()) {
            try {
                if (request.op() == BulkOperationType.DELETE) {
                    deleteItem(dataSourceId, tenantId, itemId);
                } else if (request.op() == BulkOperationType.PATCH) {
                    applyJsonPatch(dataSourceId, tenantId, itemId, request.patch());
                }

                results.add(new ItemOperationResult(itemId, true, null));
                processedCount++;

            } catch (Exception e) {
                results.add(new ItemOperationResult(itemId, false, e.getMessage()));
                failedCount++;
                errors.add("Item " + itemId + ": " + e.getMessage());
            }
        }

        return new BulkOperationResult(
            failedCount == 0,
            processedCount,
            failedCount,
            errors,
            results
        );
    }

    /**
     * Row mapper for DataSourceItemRow.
     */
    private static class DataSourceItemRowMapper implements RowMapper<DataSourceItemRow> {
        private static final ObjectMapper mapper = new ObjectMapper();

        @Override
        public DataSourceItemRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DataSourceItemRow(
                rs.getLong("id"),
                rs.getLong("data_source_id"),
                rs.getString("tenant_id"),
                parseJsonb(rs.getString("data")),
                rs.getInt("priority"),
                rs.getTimestamp("created_at").toInstant(),
                null
            );
        }

        private Map<String, Object> parseJsonb(String json) {
            try {
                if (json == null || json.trim().isEmpty()) {
                    return new HashMap<>();
                }
                return mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                logger.error("Error parsing JSON: {}", e.getMessage());
                return new HashMap<>();
            }
        }
    }
}
