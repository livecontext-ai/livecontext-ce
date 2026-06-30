package com.apimarketplace.datasource.persistence;

import com.apimarketplace.datasource.domain.ColumnStructure;
import com.apimarketplace.datasource.domain.ColumnType;
import com.apimarketplace.datasource.domain.DataSourceModels.ColumnMappingSpec;
import com.apimarketplace.datasource.domain.DataSourceEnhancedModels.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

/**
 * Repository for DataSource column operations.
 * Handles column definitions, mapping specs, and column management.
 */
@Repository
public class DataSourceColumnRepository {

    private static final Logger logger = LoggerFactory.getLogger(DataSourceColumnRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public DataSourceColumnRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Get column definitions for dynamic table columns.
     */
    public List<ColumnDefinition> getColumnDefinitions(Long dataSourceId, String tenantId) {
        List<ColumnDefinition> columns = new ArrayList<>();

        // Default columns
        columns.add(new ColumnDefinition(
            "id", "ID", "id", ColumnType.NUMBER, false, true, true, 80, null, "left", ColumnStructure.SCALAR, Map.of()
        ));
        columns.add(new ColumnDefinition(
            "priority", "Priority", "priority", ColumnType.NUMBER, true, true, true, 100, null, null, ColumnStructure.SCALAR, Map.of()
        ));
        columns.add(new ColumnDefinition(
            "created_at", "Created At", "created_at", ColumnType.DATE, false, true, true, 150, null, null, ColumnStructure.SCALAR, Map.of()
        ));

        // Load mapping spec
        Map<String, ColumnMappingSpec> mappingSpec = loadMappingSpec(dataSourceId, tenantId);
        if (!mappingSpec.isEmpty()) {
            mappingSpec.forEach((key, spec) -> columns.add(toColumnDefinition(key, spec)));
            return columns;
        }

        // Fallback: detect columns from JSON keys
        Map<String, String> jsonKeys = getJsonKeysPreview(dataSourceId, tenantId);
        for (Map.Entry<String, String> entry : jsonKeys.entrySet()) {
            String key = entry.getKey();
            ColumnType columnType = resolveLegacyColumnType(entry.getValue());
            columns.add(new ColumnDefinition(
                "data." + key,
                key,
                "data." + key,
                columnType,
                true,
                true,
                true,
                150,
                1,
                null,
                ColumnStructure.SCALAR,
                Map.of()
            ));
        }

        return columns;
    }

    /**
     * Load mapping spec from database.
     */
    public Map<String, ColumnMappingSpec> loadMappingSpec(Long dataSourceId, String tenantId) {
        try {
            String sql = """
                SELECT mapping_spec
                FROM data_sources
                WHERE id = ? AND tenant_id = ?
                """;
            List<String> results = jdbcTemplate.queryForList(sql, String.class, dataSourceId, tenantId);
            if (results.isEmpty()) {
                logger.debug("No mapping_spec found for dataSourceId={} tenant={}", dataSourceId, tenantId);
                return Map.of();
            }
            String mappingSpecJson = results.get(0);
            logger.debug("Raw mapping_spec JSON for dataSourceId={}: {}", dataSourceId, mappingSpecJson);
            Map<String, ColumnMappingSpec> parsed = MappingSpecConverter.parse(mappingSpecJson, objectMapper);
            logger.debug("Parsed {} column specs", parsed.size());
            return parsed;
        } catch (Exception e) {
            logger.error("Error loading mapping spec for dataSourceId={} tenant={}: {}", dataSourceId, tenantId, e.getMessage());
            return Map.of();
        }
    }

    /**
     * Save column order for a DataSource.
     */
    public boolean saveColumnOrder(Long dataSourceId, String tenantId, List<Map<String, Object>> columnOrder) {
        try {
            String sql = """
                UPDATE data_sources
                SET column_order = ?::jsonb, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ?
                """;
            String columnOrderJson = objectMapper.writeValueAsString(columnOrder);
            int updated = jdbcTemplate.update(sql, columnOrderJson, dataSourceId, tenantId);
            logger.debug("Updated {} rows for column order", updated);
            return updated > 0;
        } catch (Exception e) {
            logger.error("Error saving column order: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Append a column to the end of column_order.
     * Reads current order, finds max position, appends at max+1.
     */
    public void appendToColumnOrder(Long dataSourceId, String tenantId, String columnName) {
        try {
            String readSql = "SELECT column_order FROM data_sources WHERE id = ? AND tenant_id = ?";
            String currentJson = jdbcTemplate.queryForObject(readSql, String.class, dataSourceId, tenantId);

            List<Map<String, Object>> orderList = new java.util.ArrayList<>();
            int maxOrder = -1;
            if (currentJson != null && !currentJson.isBlank() && !currentJson.equals("[]")) {
                List<Map<String, Object>> existing = objectMapper.readValue(currentJson,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
                orderList.addAll(existing);
                for (Map<String, Object> entry : existing) {
                    Object orderVal = entry.get("order");
                    if (orderVal instanceof Number) {
                        maxOrder = Math.max(maxOrder, ((Number) orderVal).intValue());
                    }
                }
            }

            orderList.add(Map.of("field", columnName, "order", maxOrder + 1));
            saveColumnOrder(dataSourceId, tenantId, orderList);
            logger.debug("Appended column '{}' to column_order at position {}", columnName, maxOrder + 1);
        } catch (Exception e) {
            logger.warn("Could not append column '{}' to column_order: {}", columnName, e.getMessage());
        }
    }

    /**
     * Add a new column to a DataSource.
     */
    public boolean addColumn(Long dataSourceId, String tenantId, String columnName, String columnType,
                             String columnStructure, Map<String, Object> displayConfig, Object defaultValue) {
        try {
            logger.debug("addColumn - columnName: {}, columnType: {}, columnStructure: {}", columnName, columnType, columnStructure);

            ColumnType resolvedType = resolveColumnType(columnType, defaultValue);
            ColumnStructure resolvedStructure = resolveColumnStructure(columnStructure);
            Map<String, Object> normalizedDisplay = displayConfig != null ? displayConfig : Map.of();
            Object convertedDefaultValue = convertDefaultValue(defaultValue, resolvedType);

            // Vector columns store data in data_source_vectors, not in the JSONB data field
            int updatedRows = 0;
            if (resolvedType != ColumnType.VECTOR) {
                String updateItemsSql = """
                    UPDATE data_source_items
                    SET data = data || ?::jsonb, updated_at = NOW()
                    WHERE data_source_id = ? AND tenant_id = ?
                    """;

                Map<String, Object> newColumnData = new HashMap<>();
                newColumnData.put(columnName, convertedDefaultValue);

                updatedRows = jdbcTemplate.update(
                    updateItemsSql,
                    objectMapper.writeValueAsString(newColumnData),
                    dataSourceId,
                    tenantId
                );
            }

            // Update mapping spec
            String updateMappingSql = """
                UPDATE data_sources
                SET mapping_spec = COALESCE(mapping_spec, '{}'::jsonb) || ?::jsonb, updated_at = NOW()
                WHERE id = ? AND tenant_id = ?
                """;

            ColumnMappingSpec spec = new ColumnMappingSpec(
                "data." + columnName,
                resolvedType,
                resolvedStructure,
                Map.of(),
                normalizedDisplay
            );
            Map<String, ColumnMappingSpec> newMappingEntry = Map.of(columnName, spec);

            String mappingSpecJson = objectMapper.writeValueAsString(newMappingEntry);
            logger.debug("Saving mapping_spec for column '{}': {}", columnName, mappingSpecJson);

            jdbcTemplate.update(updateMappingSql, mappingSpecJson, dataSourceId, tenantId);

            // Append new column to column_order at the end
            appendToColumnOrder(dataSourceId, tenantId, columnName);

            logger.debug("Added column '{}' with type '{}' to {} items", columnName, resolvedType.getValue(), updatedRows);
            return true;

        } catch (Exception e) {
            logger.error("Error adding column: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Manage columns (drop, rename, set default value, update display config).
     *
     * <p>{@code UPDATE_DISPLAY} is the only op that may carry both a {@code newKey}
     * (rename) and a {@code display} payload. Both are applied in a single
     * transaction by {@link #updateColumnDisplay} so that a partial failure
     * never leaves a renamed-but-stale-display row.
     */
    @org.springframework.transaction.annotation.Transactional
    public ColumnManagementResult manageColumn(Long dataSourceId, String tenantId, ColumnManagementRequest request) {
        try {
            int affectedRows = 0;

            switch (request.op()) {
                case DROP -> affectedRows = dropColumn(dataSourceId, tenantId, request.key());
                case RENAME -> affectedRows = renameColumn(dataSourceId, tenantId, request.key(), request.newKey());
                case SET_DEFAULT -> affectedRows = setColumnDefault(dataSourceId, tenantId, request.key(), request.defaultValue());
                case UPDATE_DISPLAY -> affectedRows = updateColumnDisplay(
                        dataSourceId, tenantId, request.key(), request.newKey(), request.display());
            }

            return new ColumnManagementResult(true, affectedRows, null);

        } catch (Exception e) {
            return new ColumnManagementResult(false, 0, e.getMessage());
        }
    }

    /**
     * Get preview of JSON keys for dynamic column detection.
     */
    public Map<String, String> getJsonKeysPreview(Long dataSourceId, String tenantId) {
        String sql = """
            SELECT DISTINCT key
            FROM (
                SELECT jsonb_object_keys(data) as key
                FROM data_source_items
                WHERE data_source_id = ? AND tenant_id = ?
            ) sub
            """;
        try {
            List<String> keys = jdbcTemplate.queryForList(sql, String.class, dataSourceId, tenantId);
            Map<String, String> keyTypes = new HashMap<>();

            for (String key : keys) {
                String type = determineJsonKeyType(dataSourceId, tenantId, key);
                keyTypes.put(key, type);
            }

            return keyTypes;
        } catch (Exception e) {
            logger.error("getJsonKeysPreview error dsId={} tenant={}", dataSourceId, tenantId);
            throw e;
        }
    }

    // Private helper methods

    private int dropColumn(Long dataSourceId, String tenantId, String key) {
        String dropSql = """
            UPDATE data_source_items
            SET data = data - ?, updated_at = CURRENT_TIMESTAMP
            WHERE data_source_id = ? AND tenant_id = ?
            """;
        int affectedRows = jdbcTemplate.update(dropSql, key, dataSourceId, tenantId);

        String updateMappingSql = """
            UPDATE data_sources
            SET mapping_spec = mapping_spec - ?, updated_at = NOW()
            WHERE id = ? AND tenant_id = ?
            """;
        jdbcTemplate.update(updateMappingSql, key, dataSourceId, tenantId);

        return affectedRows;
    }

    private int renameColumn(Long dataSourceId, String tenantId, String oldKey, String newKey) {
        String renameSql = """
            UPDATE data_source_items
            SET data = jsonb_set(data - ?, ARRAY[?]::text[], data->?, true), updated_at = CURRENT_TIMESTAMP
            WHERE data_source_id = ? AND tenant_id = ?
            """;
        int affectedRows = jdbcTemplate.update(renameSql, oldKey, newKey, oldKey, dataSourceId, tenantId);

        String updateMappingSql = """
            UPDATE data_sources
            SET mapping_spec = jsonb_set(
                mapping_spec - ?,
                ARRAY[?]::text[],
                jsonb_set(
                    mapping_spec->?,
                    '{path}',
                    to_jsonb((
                        CASE
                            WHEN COALESCE(mapping_spec->?->>'path', '') LIKE 'data.%' THEN 'data.' || ?
                            ELSE ?
                        END
                    )::text),
                    true
                ),
                true
            ), updated_at = NOW()
            WHERE id = ? AND tenant_id = ?
            """;
        jdbcTemplate.update(
            updateMappingSql,
            oldKey,
            newKey,
            oldKey,
            oldKey,
            newKey,
            newKey,
            dataSourceId,
            tenantId
        );

        return affectedRows;
    }

    /**
     * Update a column's display config (and optionally rename it) in a single
     * transaction.
     *
     * <p>Behaviour:
     * <ul>
     *   <li>If {@code newKey} is non-null and differs from {@code key} → rename
     *       first ({@link #renameColumn}), then write {@code newDisplay} onto the
     *       renamed entry. The rename moves the entire {@code ColumnMappingSpec}
     *       (including its old display); the second write replaces just the
     *       {@code display} sub-field. Done in one method under the parent
     *       {@code @Transactional} so a failure rolls back the rename.</li>
     *   <li>If {@code newKey} is null or equal to {@code key} → only the display
     *       sub-field is updated; data and column key are untouched.</li>
     *   <li>If {@code newDisplay} is null → no display write happens (rename-only
     *       through this op is allowed but pointless; callers should prefer the
     *       {@code RENAME} op for that).</li>
     * </ul>
     *
     * <p>The return value is the count of {@code data_source_items} touched by
     * the rename (0 when no rename happens) - consistent with the other ops.
     *
     * @return number of {@code data_source_items} rows affected by the optional rename.
     */
    private int updateColumnDisplay(Long dataSourceId, String tenantId,
                                     String key, String newKey,
                                     Map<String, Object> newDisplay) {
        int affectedRows = 0;
        String effectiveKey = key;
        boolean shouldRename = newKey != null && !newKey.isBlank() && !newKey.equals(key);

        if (shouldRename) {
            affectedRows = renameColumn(dataSourceId, tenantId, key, newKey);
            effectiveKey = newKey;
        }

        if (newDisplay != null) {
            try {
                String displayJson = objectMapper.writeValueAsString(newDisplay);
                String sql = """
                    UPDATE data_sources
                    SET mapping_spec = jsonb_set(mapping_spec, ?::text[], ?::jsonb, true), updated_at = NOW()
                    WHERE id = ? AND tenant_id = ?
                    """;
                String pgPath = "{" + effectiveKey + ",display}";
                jdbcTemplate.update(sql, pgPath, displayJson, dataSourceId, tenantId);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize display config for key '" + effectiveKey + "'", e);
            }
        }

        return affectedRows;
    }

    private int setColumnDefault(Long dataSourceId, String tenantId, String key, Object defaultValue) {
        String defaultSql = """
            UPDATE data_source_items
            SET data = jsonb_set(data, ?::text[], ?::jsonb, true), updated_at = CURRENT_TIMESTAMP
            WHERE data_source_id = ? AND tenant_id = ?
              AND (jsonb_typeof(data->?) IS NULL OR data->? = 'null'::jsonb)
            """;
        try {
            String jsonValue = objectMapper.writeValueAsString(defaultValue);
            return jdbcTemplate.update(defaultSql, "{" + key + "}", jsonValue, dataSourceId, tenantId, key, key);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize default value for key '" + key + "'", e);
        }
    }

    private String determineJsonKeyType(Long dataSourceId, String tenantId, String key) {
        String typeSql = """
            SELECT data->? as value
            FROM data_source_items
            WHERE data_source_id = ? AND tenant_id = ? AND data->? IS NOT NULL
            LIMIT 10
            """;
        List<Object> values = jdbcTemplate.queryForList(typeSql, Object.class, key, dataSourceId, tenantId, key);
        return determineJsonValueType(values);
    }

    private String determineJsonValueType(List<Object> values) {
        if (values.isEmpty()) {
            return "text";
        }

        boolean allObjects = values.stream().allMatch(v ->
            v instanceof Map || v instanceof List ||
            (v instanceof String && ((String) v).startsWith("{") && ((String) v).endsWith("}")) ||
            (v instanceof String && ((String) v).startsWith("[") && ((String) v).endsWith("]"))
        );
        if (allObjects) return "json";

        boolean allNumbers = values.stream().allMatch(v ->
            v instanceof Number ||
            (v instanceof String && ((String) v).matches("^-?\\d+(\\.\\d+)?$"))
        );
        if (allNumbers) return "number";

        boolean allBooleans = values.stream().allMatch(v ->
            v instanceof Boolean || "true".equals(v) || "false".equals(v)
        );
        if (allBooleans) return "checkbox";

        boolean allDates = values.stream().allMatch(v ->
            v instanceof String && ((String) v).matches("^\\d{4}-\\d{2}-\\d{2}")
        );
        if (allDates) return "date";

        return "text";
    }

    private ColumnDefinition toColumnDefinition(String key, ColumnMappingSpec spec) {
        if (spec == null) {
            spec = ColumnMappingSpec.fromLegacyPath("data." + key);
        }
        String path = (spec.path() != null && !spec.path().isBlank()) ? spec.path() : "data." + key;
        ColumnType type = spec.type() != null ? spec.type() : ColumnType.TEXT;
        ColumnStructure structure = spec.structure() != null ? spec.structure() : ColumnStructure.SCALAR;
        Map<String, Object> display = spec.display() != null ? spec.display() : Map.of();
        Object labelOverride = display.get("label");
        String headerName = labelOverride != null ? labelOverride.toString() : key;

        return new ColumnDefinition(
            path,
            headerName,
            path,
            type,
            true,
            true,
            true,
            150,
            1,
            null,
            structure,
            display
        );
    }

    private ColumnType resolveLegacyColumnType(String type) {
        if (type == null) {
            return ColumnType.TEXT;
        }
        return switch (type.toLowerCase()) {
            case "number", "integer", "float", "double" -> ColumnType.NUMBER;
            case "checkbox" -> ColumnType.CHECKBOX;
            case "date", "datetime", "timestamp" -> ColumnType.DATE;
            case "multi_select", "array", "list" -> ColumnType.MULTI_SELECT;
            default -> ColumnType.fromValue(type);
        };
    }

    private ColumnType resolveColumnType(String columnType, Object defaultValue) {
        if (columnType == null || columnType.trim().isEmpty()) {
            if (defaultValue != null) {
                return inferTypeFromValue(defaultValue);
            }
            return ColumnType.TEXT;
        }
        try {
            return ColumnType.fromValue(columnType);
        } catch (Exception e) {
            logger.error("Failed to parse columnType '{}', using TEXT", columnType);
            return ColumnType.TEXT;
        }
    }

    private ColumnStructure resolveColumnStructure(String columnStructure) {
        try {
            return ColumnStructure.fromValue(columnStructure);
        } catch (Exception e) {
            return ColumnStructure.SCALAR;
        }
    }

    private ColumnType inferTypeFromValue(Object value) {
        if (value == null) return ColumnType.TEXT;
        if (value instanceof Number) return ColumnType.NUMBER;
        if (value instanceof Boolean) return ColumnType.CHECKBOX;
        if (value instanceof String strValue) {
            if (strValue.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
                return ColumnType.DATE;
            }
            if (strValue.matches("^[\\w.+-]+@[\\w.-]+\\.[a-zA-Z]{2,}$")) {
                return ColumnType.EMAIL;
            }
            if (strValue.matches("^\\+?[\\d\\s().-]{7,}$")) {
                return ColumnType.PHONE;
            }
            if (strValue.startsWith("http://") || strValue.startsWith("https://")) {
                return ColumnType.URL;
            }
            return ColumnType.TEXT;
        }
        if (value instanceof List) return ColumnType.MULTI_SELECT;
        if (value instanceof Map) return ColumnType.TEXT;
        return ColumnType.TEXT;
    }

    private Object convertDefaultValue(Object defaultValue, ColumnType columnType) {
        ColumnType effectiveType = columnType != null ? columnType : ColumnType.TEXT;
        if (defaultValue == null) {
            return switch (effectiveType) {
                case NUMBER, RATING, PROGRESS -> 0;
                case CHECKBOX -> false;
                case DATE -> null;
                case MULTI_SELECT -> new ArrayList<>();
                case SENTIMENT -> "neutral";
                case SELECT -> "";
                case FILE, IMAGE, URL, EMAIL, PHONE, VECTOR -> null;
                default -> "";
            };
        }

        return switch (effectiveType) {
            case NUMBER, RATING, PROGRESS -> {
                if (defaultValue instanceof Number number) yield number;
                try {
                    yield Double.parseDouble(defaultValue.toString());
                } catch (NumberFormatException e) {
                    yield 0;
                }
            }
            case CHECKBOX -> defaultValue instanceof Boolean ? defaultValue : Boolean.parseBoolean(defaultValue.toString());
            case MULTI_SELECT -> {
                if (defaultValue instanceof List) yield defaultValue;
                yield List.of(defaultValue.toString());
            }
            case VECTOR -> null; // Vectors are stored in data_source_vectors, not in JSONB data
            default -> defaultValue.toString();
        };
    }
}
