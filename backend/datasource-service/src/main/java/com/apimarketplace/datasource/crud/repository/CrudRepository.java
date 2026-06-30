package com.apimarketplace.datasource.crud.repository;

import com.apimarketplace.datasource.crud.domain.WhereCondition;
import com.apimarketplace.datasource.crud.dto.CreateColumnRequest;
import com.apimarketplace.datasource.crud.dto.CreateRowRequest;
import com.apimarketplace.datasource.crud.service.SqlSanitizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Repository for CRUD operations on datasource items.
 * Uses parameterized queries to prevent SQL injection.
 *
 * Data model (data_source_items table):
 * - id (bigint): Primary key
 * - data_source_id (bigint): Foreign key to data_sources
 * - tenant_id (varchar): Tenant identifier
 * - data (json): User columns stored as JSON
 * - priority (int): Item priority
 * - created_at (timestamp): Creation timestamp
 */
@Repository
public class CrudRepository {

    private static final Logger log = LoggerFactory.getLogger(CrudRepository.class);
    private static final String TABLE_NAME = "data_source_items";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final SqlSanitizer sqlSanitizer;
    private final ObjectMapper objectMapper;

    public CrudRepository(NamedParameterJdbcTemplate jdbcTemplate, SqlSanitizer sqlSanitizer, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.sqlSanitizer = sqlSanitizer;
        this.objectMapper = objectMapper;
    }

    /**
     * Serialize complex values (Map, Collection) to JSON strings for JSONB storage.
     * PostgreSQL JDBC cannot bind Map/Collection directly to jsonb_build_object() parameters
     * without the hstore extension. This ensures all values are scalar types.
     */
    private Object serializeIfComplex(Object value) {
        if (value == null) return "";
        if (value instanceof Map<?, ?> || value instanceof Collection<?>) {
            try {
                return objectMapper.writeValueAsString(value);
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize complex value to JSON, using toString(): {}", e.getMessage());
                return value.toString();
            }
        }
        return value;
    }

    /**
     * Create rows in the datasource.
     *
     * @param dataSourceId The datasource ID
     * @param tenantId The tenant ID
     * @param rows The rows to insert
     * @return List of inserted row IDs
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Long> createRows(Long dataSourceId, String tenantId, List<CreateRowRequest.RowData> rows) {
        List<Long> insertedIds = new ArrayList<>();

        for (CreateRowRequest.RowData row : rows) {
            Map<String, Object> columns = row.columns();
            if (columns == null || columns.isEmpty()) {
                log.warn("Skipping row with no columns");
                continue;
            }

            // Validate all columns and values
            Map<String, Object> validatedColumns = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : columns.entrySet()) {
                String safeColumnName = sqlSanitizer.sanitizeColumnName(entry.getKey());
                sqlSanitizer.validateValueLength(entry.getValue());
                validatedColumns.put(safeColumnName, entry.getValue());
            }

            MapSqlParameterSource params = new MapSqlParameterSource();
            params.addValue("data_source_id", dataSourceId);
            params.addValue("tenant_id", tenantId);
            params.addValue("priority", 0);

            // Build JSONB object from columns
            StringBuilder jsonbBuilder = new StringBuilder("jsonb_build_object(");
            int i = 0;
            for (Map.Entry<String, Object> entry : validatedColumns.entrySet()) {
                if (i > 0) jsonbBuilder.append(", ");
                String keyParam = "col_key_" + i;
                String valParam = "col_val_" + i;
                jsonbBuilder.append(":").append(keyParam).append(", :").append(valParam);
                params.addValue(keyParam, entry.getKey());
                // Null values cause "bad SQL grammar" in jsonb_build_object (untyped NULL)
                // Complex values (Map, Collection) must be serialized to JSON strings
                // to avoid PostgreSQL hstore extension requirement
                Object value = serializeIfComplex(entry.getValue());
                params.addValue(valParam, value != null ? value : "");
                i++;
            }
            jsonbBuilder.append(")");

            String sql = String.format(
                "INSERT INTO %s (data_source_id, tenant_id, data, priority, created_at) " +
                "VALUES (:data_source_id, :tenant_id, %s, :priority, NOW()) RETURNING id",
                TABLE_NAME, jsonbBuilder
            );

            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(sql, params, keyHolder, new String[]{"id"});

            Number key = keyHolder.getKey();
            if (key != null) {
                insertedIds.add(key.longValue());
                log.debug("Inserted row with id: {}", key.longValue());
            }
        }

        log.info("Created {} rows in datasource {}", insertedIds.size(), dataSourceId);
        return insertedIds;
    }

    /**
     * Read rows from the datasource.
     *
     * @param dataSourceId The datasource ID
     * @param tenantId The tenant ID
     * @param where The WHERE condition (can be null for all rows)
     * @param limit Maximum number of rows to return
     * @return List of matching rows
     */
    public List<Map<String, Object>> readRows(Long dataSourceId, String tenantId, WhereCondition where, int limit, int offset) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("data_source_id", dataSourceId);
        params.addValue("tenant_id", tenantId);
        // Caller decides the page size. The repo no longer applies a 101-cap
        // (legacy default from the limit+1-trick web pagination pattern) -
        // the 101 ceiling silently truncated counts for any workflow node that
        // legitimately needed > 100 rows, e.g. the dashboard CRUD `find`
        // pattern (limit=10000 in the plan, used by every Daily Email Digest
        // style count step). The defensive bound now lives in the service
        // layer (CrudExecutorService.executeReadRow), which can be revisited
        // independently if a future bulk API needs a different ceiling.
        params.addValue("limit", Math.max(limit, 1));
        params.addValue("offset", Math.max(offset, 0));

        String whereClause;
        if (where != null) {
            where.validate();
            whereClause = " AND " + buildWhereClause(where, params);
        } else {
            whereClause = "";
        }

        String sql = String.format(
            "SELECT id, data, priority, created_at FROM %s " +
            "WHERE data_source_id = :data_source_id AND tenant_id = :tenant_id%s " +
            "ORDER BY priority DESC, id DESC LIMIT :limit OFFSET :offset",
            TABLE_NAME, whereClause
        );

        log.debug("Executing read query: {}", sql);
        List<Map<String, Object>> results = jdbcTemplate.queryForList(sql, params);
        log.info("Read {} rows from datasource {} (offset={})", results.size(), dataSourceId, offset);

        return results;
    }

    /**
     * Read rows by explicit id list. Used by event emission to capture the
     * before/after snapshots that feed the datasource trigger payload.
     *
     * <p>Bypasses the {@link WhereCondition} operator surface (ids are
     * trusted internal values) and the service-layer page-size cap (event
     * emission may touch up to a full bulk update / delete). The legacy
     * {@code readRows} 101-row cap mentioned in earlier revisions of this
     * Javadoc was removed in the 2026-05-13 scalability fix - page sizing
     * now lives in {@code CrudExecutorService} (MAX_READ_LIMIT=10 000).
     *
     * @return rows in the same shape as {@link #readRows} (id, data, priority, created_at)
     */
    public List<Map<String, Object>> findRowsByIds(Long dataSourceId, String tenantId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("data_source_id", dataSourceId);
        params.addValue("tenant_id", tenantId);
        params.addValue("ids", ids);
        String sql = String.format(
            "SELECT id, data, priority, created_at FROM %s " +
            "WHERE data_source_id = :data_source_id AND tenant_id = :tenant_id AND id IN (:ids)",
            TABLE_NAME
        );
        return jdbcTemplate.queryForList(sql, params);
    }

    /**
     * Resolve ids matching a WHERE clause. Used to capture the id list on
     * UPDATE / DELETE before the mutating statement runs, so we can emit
     * per-row events with accurate before/after snapshots.
     */
    public List<Long> findIdsMatching(Long dataSourceId, String tenantId, WhereCondition where) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("data_source_id", dataSourceId);
        params.addValue("tenant_id", tenantId);
        String whereClause;
        if (where != null) {
            where.validate();
            whereClause = " AND " + buildWhereClause(where, params);
        } else {
            whereClause = "";
        }
        String sql = String.format(
            "SELECT id FROM %s WHERE data_source_id = :data_source_id AND tenant_id = :tenant_id%s",
            TABLE_NAME, whereClause
        );
        return jdbcTemplate.queryForList(sql, params, Long.class);
    }

    /**
     * Update rows in the datasource.
     *
     * @param dataSourceId The datasource ID
     * @param tenantId The tenant ID
     * @param where The WHERE condition
     * @param setColumns Columns to update
     * @return Number of affected rows
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int updateRows(Long dataSourceId, String tenantId, WhereCondition where, Map<String, Object> setColumns) {
        where.validate();

        if (setColumns == null || setColumns.isEmpty()) {
            throw new IllegalArgumentException("No columns to update");
        }

        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("data_source_id", dataSourceId);
        params.addValue("tenant_id", tenantId);

        // Build SET clause using JSONB concatenation
        StringBuilder setClause = new StringBuilder("data = COALESCE(data, '{}'::jsonb)");
        int i = 0;
        for (Map.Entry<String, Object> entry : setColumns.entrySet()) {
            String rawKey = entry.getKey();
            if (rawKey != null && rawKey.startsWith("data.")) {
                rawKey = rawKey.substring("data.".length());
            }
            String safeColumnName = sqlSanitizer.sanitizeColumnName(rawKey);
            sqlSanitizer.validateValueLength(entry.getValue());

            String keyParam = "set_key_" + i;
            String valParam = "set_val_" + i;
            setClause.append(" || jsonb_build_object(:").append(keyParam).append(", :").append(valParam).append(")");
            params.addValue(keyParam, safeColumnName);
            params.addValue(valParam, serializeIfComplex(entry.getValue()));
            i++;
        }

        String whereClause = buildWhereClause(where, params);

        String sql = String.format(
            "UPDATE %s SET %s WHERE data_source_id = :data_source_id AND tenant_id = :tenant_id AND %s",
            TABLE_NAME, setClause, whereClause
        );

        log.debug("Executing update query: {}", sql);
        int affectedRows = jdbcTemplate.update(sql, params);
        log.info("Updated {} rows in datasource {}", affectedRows, dataSourceId);

        return affectedRows;
    }

    /**
     * Delete rows from the datasource.
     *
     * @param dataSourceId The datasource ID
     * @param tenantId The tenant ID
     * @param where The WHERE condition
     * @return Number of deleted rows
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteRows(Long dataSourceId, String tenantId, WhereCondition where) {
        where.validate();

        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("data_source_id", dataSourceId);
        params.addValue("tenant_id", tenantId);

        String whereClause = buildWhereClause(where, params);

        String sql = String.format(
            "DELETE FROM %s WHERE data_source_id = :data_source_id AND tenant_id = :tenant_id AND %s",
            TABLE_NAME, whereClause
        );

        log.debug("Executing delete query: {}", sql);
        int deletedRows = jdbcTemplate.update(sql, params);
        log.info("Deleted {} rows from datasource {}", deletedRows, dataSourceId);

        return deletedRows;
    }

    /**
     * Create columns in the datasource.
     * With JSON, columns are dynamically added. This method optionally sets default values.
     *
     * @param dataSourceId The datasource ID
     * @param tenantId The tenant ID
     * @param columns Column definitions
     * @return List of created column names
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<String> createColumns(Long dataSourceId, String tenantId, List<CreateColumnRequest.ColumnDefinition> columns) {
        List<String> createdColumns = new ArrayList<>();

        for (CreateColumnRequest.ColumnDefinition col : columns) {
            String safeColumnName = sqlSanitizer.sanitizeColumnName(col.name());
            createdColumns.add(safeColumnName);

            // If a default value is provided, update existing rows that don't have this column
            if (col.defaultValue() != null && !col.defaultValue().isBlank()) {
                sqlSanitizer.validateValueLength(col.defaultValue());

                MapSqlParameterSource params = new MapSqlParameterSource();
                params.addValue("data_source_id", dataSourceId);
                params.addValue("tenant_id", tenantId);
                params.addValue("col_name", safeColumnName);
                params.addValue("default_val", col.defaultValue());

                String sql = String.format(
                    "UPDATE %s SET data = COALESCE(data, '{}'::jsonb) || jsonb_build_object(:col_name, :default_val) " +
                    "WHERE data_source_id = :data_source_id AND tenant_id = :tenant_id AND (data IS NULL OR NOT data ?? :col_name)",
                    TABLE_NAME
                );

                int updated = jdbcTemplate.update(sql, params);
                log.debug("Set default value for column {} on {} rows", safeColumnName, updated);
            }
        }

        log.info("Created {} columns in datasource {}", createdColumns.size(), dataSourceId);
        return createdColumns;
    }

    /**
     * Build a WHERE clause from a WhereCondition.
     * Uses the "data" JSON column for user-defined fields.
     *
     * @param where The WHERE condition
     * @param params The parameter source to add values to
     * @return The WHERE clause SQL fragment
     */
    private String buildWhereClause(WhereCondition where, MapSqlParameterSource params) {
        // Strip "data." prefix if present - this method already handles the data JSONB column access
        String rawColumn = where.column();
        if (rawColumn != null && rawColumn.startsWith("data.")) {
            rawColumn = rawColumn.substring("data.".length());
        }
        String safeColumn = sqlSanitizer.sanitizeColumnName(rawColumn);
        String operator = sqlSanitizer.sanitizeOperator(where.operator());

        // Use parameterized JSONB access to prevent SQL injection from column names.
        // jsonb_extract_path_text(data, :param) is equivalent to data->>:param but fully parameterized.
        String columnAccess;
        if (safeColumn.equals("id")) {
            columnAccess = "id::text";
        } else {
            params.addValue("where_col", safeColumn);
            columnAccess = "jsonb_extract_path_text(data, :where_col)";
        }

        return switch (operator) {
            case "IS NULL" -> columnAccess + " IS NULL";
            case "IS NOT NULL" -> columnAccess + " IS NOT NULL";
            case "IN" -> {
                if (where.value() instanceof List<?> list) {
                    if (list.isEmpty()) {
                        throw new IllegalArgumentException(
                            "IN operator requires a non-empty list value. "
                                + "Example: where={column:'status', operator:'IN', value:['open','pending']}");
                    }
                    List<String> stringList = list.stream()
                        .map(Object::toString)
                        .toList();
                    params.addValue("where_val", stringList);
                    // IN (:list) expands to IN (?, ?, ...) via Spring's collection-parameter
                    // unwrapping. ANY(:list) used to be emitted here but produced ANY(?, ?, ...)
                    // - invalid SQL: PostgreSQL's ANY() takes a single array argument, not
                    // positional placeholders. Use IN to match the unwrapping shape.
                    yield columnAccess + " IN (:where_val)";
                }
                throw new IllegalArgumentException("IN operator requires a list value");
            }
            case "LIKE" -> {
                sqlSanitizer.validateValueLength(where.value());
                params.addValue("where_val", where.value().toString());
                yield columnAccess + " LIKE :where_val";
            }
            default -> {
                sqlSanitizer.validateValueLength(where.value());
                params.addValue("where_val", where.value().toString());
                yield columnAccess + " " + operator + " :where_val";
            }
        };
    }
}
