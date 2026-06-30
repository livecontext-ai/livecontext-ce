package com.apimarketplace.datasource.persistence;

import com.apimarketplace.datasource.domain.DataSourceEnhancedModels;
import com.apimarketplace.datasource.domain.DataSourceEnhancedModels.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Repository for DataSource item query operations.
 * Handles pagination, filtering, and searching.
 */
@Repository
public class DataSourceItemQueryRepository {

    private static final Logger logger = LoggerFactory.getLogger(DataSourceItemQueryRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public DataSourceItemQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Check if a DataSource exists and belongs to the tenant.
     */
    public boolean dataSourceExists(Long dataSourceId, String tenantId) {
        String sql = """
            SELECT COUNT(*)
            FROM data_sources
            WHERE id = ? AND tenant_id = ?
            """;
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, dataSourceId, tenantId);
        return count != null && count > 0;
    }

    /**
     * Check if an item exists and belongs to the tenant and data source.
     */
    public boolean itemExists(Long dataSourceId, String tenantId, Long itemId) {
        String sql = """
            SELECT COUNT(*)
            FROM data_source_items
            WHERE id = ? AND data_source_id = ? AND tenant_id = ?
            """;
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, itemId, dataSourceId, tenantId);
        return count != null && count > 0;
    }

    /**
     * Find items with server-side pagination, filtering, and sorting (keyset pagination).
     */
    public List<DataSourceItemRow> findItemsWithPagination(
            Long dataSourceId,
            String tenantId,
            PaginationRequest request) {

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT id, data_source_id, tenant_id, data, priority, created_at ");
        sql.append("FROM data_source_items ");
        sql.append("WHERE tenant_id = ? AND data_source_id = ? ");

        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.add(dataSourceId);

        // Text search filtering
        if (request.query() != null && !request.query().trim().isEmpty()) {
            sql.append("AND data::text ILIKE ? ");
            params.add("%" + request.query() + "%");
        }

        // JSON criteria filtering
        appendFilterCriteria(sql, params, request.filter());

        // Keyset cursor pagination
        if (request.cursor() != null && !request.cursor().trim().isEmpty()) {
            appendCursorCondition(sql, params, request.cursor());
        }

        // Default sorting
        sql.append("ORDER BY created_at DESC, id ASC ");

        // Limit + 1 to detect if there's a next page
        sql.append("LIMIT ? ");
        params.add(request.limit() + 1);

        return jdbcTemplate.query(sql.toString(), new DataSourceItemRowMapper(), params.toArray());
    }

    /**
     * Find items with offset-based pagination (for direct page navigation).
     */
    public List<DataSourceItemRow> findItemsWithOffset(
            Long dataSourceId,
            String tenantId,
            PaginationRequest request,
            int offset) {

        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();

        sql.append("SELECT id, data_source_id, tenant_id, data, priority, created_at ");
        sql.append("FROM data_source_items ");
        sql.append("WHERE tenant_id = ? AND data_source_id = ? ");
        params.add(tenantId);
        params.add(dataSourceId);

        // Text search filtering
        appendTextSearchCondition(sql, params, request.query());

        // Column-specific filtering
        appendColumnFilterConditions(sql, params, request.filter());

        // Sorting
        appendSortConditions(sql, params, request.sort());

        // Offset pagination
        sql.append("LIMIT ? OFFSET ? ");
        params.add(request.limit());
        params.add(offset);

        return jdbcTemplate.query(sql.toString(), new DataSourceItemRowMapper(), params.toArray());
    }

    /**
     * Count total items for a data source with filters.
     */
    public int countItemsWithFilters(Long dataSourceId, String tenantId, PaginationRequest request) {
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();

        sql.append("SELECT COUNT(*) FROM data_source_items ");
        sql.append("WHERE data_source_id = ? AND tenant_id = ? ");
        params.add(dataSourceId);
        params.add(tenantId);

        // Text search filtering
        appendTextSearchCondition(sql, params, request.query());

        // Column-specific filtering
        appendColumnFilterConditions(sql, params, request.filter());

        return jdbcTemplate.queryForObject(sql.toString(), Integer.class, params.toArray());
    }

    /**
     * Find items by their IDs.
     */
    public List<DataSourceItemRow> findByIds(Long dataSourceId, String tenantId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }

        String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
        String sql = """
            SELECT id, data_source_id, tenant_id, data, priority, created_at, updated_at
            FROM data_source_items
            WHERE data_source_id = ? AND tenant_id = ? AND id IN (%s)
            ORDER BY created_at DESC, id ASC
            """.formatted(placeholders);

        List<Object> params = new ArrayList<>();
        params.add(dataSourceId);
        params.add(tenantId);
        params.addAll(ids);

        return jdbcTemplate.query(sql, new DataSourceItemRowMapper(), params.toArray());
    }

    // Helper methods for query building

    private void appendFilterCriteria(StringBuilder sql, List<Object> params, Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> filterMap = (Map<String, Object>) value;
                appendOperatorFilter(sql, params, key, filterMap);
            } else {
                sql.append("AND data->>? = ? ");
                params.add(key);
                params.add(value);
            }
        }
    }

    private void appendOperatorFilter(StringBuilder sql, List<Object> params, String key, Map<String, Object> filterMap) {
        for (Map.Entry<String, Object> filterEntry : filterMap.entrySet()) {
            String operator = filterEntry.getKey();
            Object filterValue = filterEntry.getValue();

            switch (operator) {
                case ">" -> {
                    sql.append("AND (data->>?)::numeric > ? ");
                    params.add(key);
                    params.add(filterValue);
                }
                case ">=" -> {
                    sql.append("AND (data->>?)::numeric >= ? ");
                    params.add(key);
                    params.add(filterValue);
                }
                case "<" -> {
                    sql.append("AND (data->>?)::numeric < ? ");
                    params.add(key);
                    params.add(filterValue);
                }
                case "<=" -> {
                    sql.append("AND (data->>?)::numeric <= ? ");
                    params.add(key);
                    params.add(filterValue);
                }
                case "IN" -> {
                    if (filterValue instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<Object> values = (List<Object>) filterValue;
                        String placeholders = values.stream().map(v -> "?").collect(Collectors.joining(","));
                        sql.append("AND data->>? IN (").append(placeholders).append(") ");
                        params.add(key);
                        params.addAll(values);
                    }
                }
                default -> {
                    sql.append("AND data->>? = ? ");
                    params.add(key);
                    params.add(filterValue);
                }
            }
        }
    }

    private void appendCursorCondition(StringBuilder sql, List<Object> params, String cursorStr) {
        try {
            DataSourceEnhancedModels.KeysetCursor cursor = DataSourceEnhancedModels.KeysetCursor.decode(cursorStr);
            sql.append("AND (created_at < ? OR (created_at = ? AND id < ?)) ");
            params.add(Timestamp.from(Instant.ofEpochMilli(cursor.createdAtMs())));
            params.add(Timestamp.from(Instant.ofEpochMilli(cursor.createdAtMs())));
            params.add(cursor.id());
        } catch (IllegalArgumentException e) {
            // Invalid cursor, ignore pagination
        }
    }

    private void appendTextSearchCondition(StringBuilder sql, List<Object> params, String query) {
        if (query == null || query.trim().isEmpty()) {
            return;
        }
        sql.append("AND (");
        sql.append("CAST(id AS TEXT) ILIKE ? OR ");
        sql.append("CAST(priority AS TEXT) ILIKE ? OR ");
        sql.append("data::text ILIKE ? ");
        sql.append(") ");
        String searchPattern = "%" + query.trim() + "%";
        params.add(searchPattern);
        params.add(searchPattern);
        params.add(searchPattern);
    }

    private void appendColumnFilterConditions(StringBuilder sql, List<Object> params, Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value == null) continue;

            if (key.startsWith("data.")) {
                String jsonKey = key.substring(5);
                appendJsonColumnFilter(sql, params, jsonKey, value);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void appendJsonColumnFilter(StringBuilder sql, List<Object> params, String jsonKey, Object value) {
        if (value instanceof Map) {
            Map<String, Object> filterMap = (Map<String, Object>) value;
            String filterType = (String) filterMap.get("filterType");
            Object filterValue = filterMap.get("filter");

            if (filterType != null && filterValue != null) {
                switch (filterType.toLowerCase()) {
                    case "contains" -> {
                        sql.append("AND data->>? ILIKE ? ");
                        params.add(jsonKey);
                        params.add("%" + filterValue + "%");
                    }
                    case "equals" -> {
                        sql.append("AND data->>? = ? ");
                        params.add(jsonKey);
                        params.add(filterValue);
                    }
                    case "starts_with" -> {
                        sql.append("AND data->>? ILIKE ? ");
                        params.add(jsonKey);
                        params.add(filterValue + "%");
                    }
                    case "ends_with" -> {
                        sql.append("AND data->>? ILIKE ? ");
                        params.add(jsonKey);
                        params.add("%" + filterValue);
                    }
                    default -> {
                        sql.append("AND data->>? = ? ");
                        params.add(jsonKey);
                        params.add(filterValue);
                    }
                }
            }
        } else {
            sql.append("AND data->>? = ? ");
            params.add(jsonKey);
            params.add(value);
        }
    }

    private void appendSortConditions(StringBuilder sql, List<Object> params, List<SortRequest> sortList) {
        if (sortList == null || sortList.isEmpty()) {
            sql.append("ORDER BY created_at DESC, id ASC ");
            return;
        }

        sql.append("ORDER BY ");
        for (int i = 0; i < sortList.size(); i++) {
            SortRequest sortReq = sortList.get(i);
            if (i > 0) sql.append(", ");

            if (sortReq.colId().equals("id")) {
                sql.append("id ");
            } else if (sortReq.colId().equals("priority")) {
                sql.append("priority ");
            } else if (sortReq.colId().equals("created_at")) {
                sql.append("created_at ");
            } else if (sortReq.colId().startsWith("data.")) {
                String jsonKey = sortReq.colId().substring(5);
                sql.append("data->>? ");
                params.add(jsonKey);
            } else {
                sql.append("created_at ");
            }

            sql.append(sortReq.sort().getValue().toUpperCase());
        }
        sql.append(" ");
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
                return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                logger.error("Error parsing JSON: {}", e.getMessage());
                return new HashMap<>();
            }
        }
    }
}
