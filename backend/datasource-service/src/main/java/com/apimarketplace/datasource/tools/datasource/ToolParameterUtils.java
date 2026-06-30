package com.apimarketplace.datasource.tools.datasource;

import com.apimarketplace.datasource.domain.ColumnType;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Utility class for parsing tool parameters.
 * Shared across all DataSource tool modules.
 * Datasource-service native version (identical logic to orchestrator version).
 */
public final class ToolParameterUtils {

    /**
     * Physical columns on {@code data_source_items} (see V9 migration).
     * User-defined columns are stored inside the {@code data} JSONB blob, so any of
     * these names would collide with the physical column of the same name on the
     * query projection and silently shadow the user's value.
     */
    public static final Set<String> RESERVED_COLUMN_NAMES = Set.of(
        "id", "data_source_id", "tenant_id", "data",
        "priority", "row_index", "created_at", "updated_at"
    );

    // Shared error hints - every "missing parameter" error points the agent at a working example.
    public static final String MISSING_TABLE_ID_HINT =
        "table_id is required. Pass the integer id returned by create/list. "
            + "Example: table(action='query_rows', table_id=1, where={column:'status', operator:'=', value:'open'})";

    public static final String MISSING_NAME_HINT =
        "name is required. Example: table(action='create', name='tasks', data=[{title:'Write report', done:false}])";

    public static final String MISSING_WHERE_HINT_UPDATE =
        "where condition is required for update_rows. "
            + "Example: where={column:'id', operator:'=', value:5}";

    public static final String MISSING_WHERE_HINT_DELETE =
        "where condition is required for delete_rows. "
            + "Example: where={column:'status', operator:'=', value:'Done'}";

    public static final String MISSING_SET_HINT =
        "set values are required for update_rows. "
            + "Example: set={price:899, status:'Active'}";

    private ToolParameterUtils() {
        // Utility class - no instantiation
    }

    /**
     * Strip "data." prefix from a column name.
     * Agents may incorrectly include "data." prefix (internal storage detail).
     */
    public static String sanitizeColumnName(String name) {
        if (name != null && name.startsWith("data.")) {
            return name.substring("data.".length());
        }
        return name;
    }

    /**
     * Validate column type against {@link ColumnType}. Returns error message or null if valid.
     * Canonical matching (case-insensitive, trimmed) so this lines up with
     * {@link ColumnType#fromValue} but WITHOUT its silent fallback to TEXT.
     */
    public static String validateColumnType(String typeStr) {
        if (typeStr == null || typeStr.isBlank()) {
            return null; // Defaulting to text is intentional - matches ColumnType.fromValue.
        }
        String lower = typeStr.toLowerCase().trim();
        for (ColumnType ct : ColumnType.values()) {
            if (ct.getValue().equals(lower)) {
                return null;
            }
        }
        return "Invalid column type '" + typeStr + "'. Valid types: "
            + validColumnTypesList()
            + ". See table(action='help') for display config options.";
    }

    private static String validColumnTypesList() {
        StringBuilder sb = new StringBuilder();
        ColumnType[] values = ColumnType.values();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(values[i].getValue());
        }
        return sb.toString();
    }

    /**
     * Reject column names that would collide with physical {@code data_source_items} columns.
     * Returns error message or null if valid.
     */
    public static String validateReservedColumnName(String name) {
        if (name == null) return null;
        if (RESERVED_COLUMN_NAMES.contains(name.toLowerCase().trim())) {
            return "Column name '" + name + "' is reserved (conflicts with an internal column). "
                + "Reserved names: " + String.join(", ", RESERVED_COLUMN_NAMES)
                + ". Pick a different name.";
        }
        return null;
    }

    /**
     * Validate a single column definition: reserved name, known type, the
     * type-specific display contract (select/multi_select require display.options,
     * vector requires display.dimension), and the edition gate (vector columns
     * are self-hosted-only; pass {@code VectorFeatureGate.isVectorAllowed()}).
     *
     * <p>Single chokepoint reused by every path that creates a typed column -
     * agent tool {@code create}/{@code add_columns}, REST UI {@code POST /columns},
     * CRUD {@code create-column}. Keep new type-level checks here, not at call sites.
     * {@code vectorAllowed} is deliberately a required parameter (no permissive
     * overload): a forgotten call site must fail to compile, not silently allow
     * vector columns on managed cloud.
     *
     * <p>Returns an error message, or null if valid.
     */
    public static String validateColumnDefinition(String name, String type, Object display,
                                                  boolean vectorAllowed) {
        String colName = sanitizeColumnName(name);
        String reservedErr = validateReservedColumnName(colName);
        if (reservedErr != null) return reservedErr;
        String typeErr = validateColumnType(type);
        if (typeErr != null) return typeErr;
        String editionErr = validateVectorEdition(type, vectorAllowed);
        if (editionErr != null) return editionErr;
        String optionsErr = validateSelectOptions(type, colName, display);
        if (optionsErr != null) return optionsErr;
        String vectorErr = validateVectorDimension(type, colName, display);
        if (vectorErr != null) return vectorErr;
        return null;
    }

    /**
     * Validate a batch of column definitions: per-column rules via
     * {@link #validateColumnDefinition} plus an intra-request duplicate check
     * (post-sanitization, case-insensitive). Same rule set used by both
     * {@code create} and {@code add_columns} - one place to change it.
     * Returns an error message, or null if every column is valid.
     */
    public static String validateColumnDefinitions(List<Map<String, Object>> columnsList,
                                                   boolean vectorAllowed) {
        Set<String> seen = new java.util.HashSet<>();
        for (Map<String, Object> col : columnsList) {
            if (col == null) continue;
            String rawName = (String) col.get("name");
            String colName = sanitizeColumnName(rawName);

            if (colName != null) {
                String key = colName.toLowerCase().trim();
                if (!key.isEmpty() && !seen.add(key)) {
                    return "Duplicate column name in request: '" + colName
                        + "'. Each column name must be unique within a single create/add_columns call.";
                }
            }

            String err = validateColumnDefinition(rawName, (String) col.get("type"), col.get("display"),
                    vectorAllowed);
            if (err != null) return err;
        }
        return null;
    }

    /**
     * Edition gate for {@code vector} columns - self-hosted deployments only.
     * Returns the agent-actionable rejection message, or null when the type is
     * not vector or the edition allows it.
     */
    static String validateVectorEdition(String typeStr, boolean vectorAllowed) {
        if (vectorAllowed || typeStr == null) return null;
        if (!"vector".equals(typeStr.toLowerCase().trim())) return null;
        return com.apimarketplace.datasource.services.VectorFeatureGate.DISABLED_MESSAGE;
    }

    /** Pgvector index size limit; mirrors {@code VectorRepository.MAX_DIMENSION}. */
    static final int MAX_VECTOR_DIMENSION = 2000;

    /**
     * For {@code vector} columns, require {@code display.dimension} to be a positive
     * integer (1 to {@link #MAX_VECTOR_DIMENSION}). Missing dimension lets vectors of
     * mixed sizes accumulate in {@code data_source_vectors}; HNSW index creation
     * later throws and similarity queries return inconsistent results.
     *
     * <p>Returns an error message, or null if valid (or if type is not vector).
     */
    static String validateVectorDimension(String typeStr, String colName, Object displayObj) {
        if (typeStr == null) return null;
        if (!"vector".equals(typeStr.toLowerCase().trim())) return null;
        Integer dimension = null;
        if (displayObj instanceof Map<?, ?> displayMap) {
            Object dim = displayMap.get("dimension");
            if (dim instanceof Number n) {
                dimension = n.intValue();
            } else if (dim instanceof String s) {
                try { dimension = Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) {}
            }
        }
        if (dimension == null || dimension <= 0 || dimension > MAX_VECTOR_DIMENSION) {
            return "Column '" + (colName != null ? colName : "?")
                + "' has type=vector but display.dimension is missing or invalid. "
                + "Pass display: {dimension: <N>, metric: 'cosine'|'l2'|'dot'} where N is the "
                + "embedding size (1 to " + MAX_VECTOR_DIMENSION + ", e.g. 1536 for OpenAI "
                + "text-embedding-3-small, 768 for many open models). Required up-front so all "
                + "vectors share one size - pgvector HNSW index needs a fixed dimension.";
        }
        return null;
    }

    /**
     * For {@code select} and {@code multi_select} columns, require
     * {@code display.options} to be a non-empty list. Empty/missing options silently
     * produce an unselectable dropdown in the UI even though writes succeed.
     *
     * <p>Validates <em>presence and non-emptiness</em> only - entry shape
     * ({@code label}/{@code value}/{@code color}) is intentionally not checked here;
     * it is a renderer concern surfaced via the help text.
     *
     * <p>Returns an error message, or null if valid.
     */
    static String validateSelectOptions(String typeStr, String colName, Object displayObj) {
        if (typeStr == null) return null;
        String lower = typeStr.toLowerCase().trim();
        if (!"select".equals(lower) && !"multi_select".equals(lower)) {
            return null;
        }
        Object optionsObj = null;
        if (displayObj instanceof Map<?, ?> displayMap) {
            optionsObj = displayMap.get("options");
        }
        if (!(optionsObj instanceof List<?> opts) || opts.isEmpty()) {
            return "Column '" + (colName != null ? colName : "?")
                + "' has type=" + lower + " but no display.options. "
                + "Pass display: {options: [{label: '...', value: '...', color: '#hex'}, ...]} "
                + "or use type='text' if you don't need a dropdown. "
                + "See table(action='help') for examples.";
        }
        return null;
    }

    /**
     * Build the {@code visualization} metadata block returned with mutating table ops.
     */
    public static Map<String, Object> tableVisualizationMetadata(Long datasourceId, String title) {
        String dsId = String.valueOf(datasourceId);
        String safeTitle = (title == null || title.isBlank()) ? "Table #" + dsId : title;
        return Map.of("visualization", Map.of("type", "table", "id", dsId, "title", safeTitle));
    }

    /**
     * Extract the table/datasource ID from parameters.
     * Tries: datasource_id → table_id → id (agents use table_id, code uses datasource_id).
     */
    public static Long getTableId(Map<String, Object> params) {
        Long id = getLongParam(params, "datasource_id");
        if (id == null) id = getLongParam(params, "table_id");
        if (id == null) id = getLongParam(params, "id");
        return id;
    }

    public static Long getLongParam(Map<String, Object> params, String key) {
        Object val = params.get(key);
        if (val instanceof Number) {
            return ((Number) val).longValue();
        }
        if (val instanceof String) {
            try {
                return Long.parseLong((String) val);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public static Integer getIntParam(Map<String, Object> params, String key, int defaultValue) {
        Object val = params.get(key);
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        if (val instanceof String) {
            try {
                return Integer.parseInt((String) val);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    public static String getStringParam(Map<String, Object> params, String key) {
        Object val = params.get(key);
        return val != null ? String.valueOf(val) : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> parseDataArray(Object dataObj, ObjectMapper objectMapper) {
        if (dataObj instanceof List<?> list) {
            return list.stream()
                .map(item -> coerceToMap(item, objectMapper))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
        }
        if (dataObj instanceof Map<?, ?> map) {
            return List.of((Map<String, Object>) map);
        }
        if (dataObj instanceof String str && !str.isBlank()) {
            try {
                List<?> parsed = objectMapper.readValue(str, List.class);
                return parseDataArray(parsed, objectMapper);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid JSON string for data: " + e.getMessage());
            }
        }
        return List.of();
    }

    public static List<Map<String, Object>> parseRowsArray(Object rowsObj, ObjectMapper objectMapper) {
        return parseDataArray(rowsObj, objectMapper);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> coerceToMap(Object item, ObjectMapper objectMapper) {
        if (item instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (item instanceof String str) {
            String trimmed = str.trim();
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                try {
                    return objectMapper.readValue(trimmed,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                } catch (Exception ignored) {
                    // not valid JSON
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseUpdatesObject(Object updatesObj, ObjectMapper objectMapper) {
        if (updatesObj instanceof Map) {
            return (Map<String, Object>) updatesObj;
        } else if (updatesObj instanceof String str) {
            try {
                return objectMapper.readValue(str, Map.class);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid JSON string for updates: " + e.getMessage());
            }
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> parseColumnsArray(Object columnsObj, ObjectMapper objectMapper,
                                                               org.slf4j.Logger log) {
        if (columnsObj instanceof List<?> list) {
            return list.stream()
                .map(item -> {
                    if (item instanceof Map<?, ?> map) {
                        if (!map.containsKey("name")) {
                            throw new IllegalArgumentException(
                                "Each column must have a 'name' field. Got: " + map +
                                ". Correct format: {name: 'column_name', type: 'text'}");
                        }
                        return (Map<String, Object>) map;
                    }
                    if (item instanceof String str) {
                        String trimmed = str.trim();
                        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                            try {
                                Map<String, Object> deserialized = objectMapper.readValue(trimmed,
                                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>(){});
                                if (!deserialized.containsKey("name")) {
                                    throw new IllegalArgumentException(
                                        "Deserialized column JSON must have 'name' field. Got: " + deserialized);
                                }
                                if (log != null) {
                                    log.info("Auto-fixed double-serialized column JSON: {} -> {}", trimmed, deserialized);
                                }
                                return deserialized;
                            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                                if (log != null) {
                                    log.warn("String looks like JSON but failed to parse: {}. Treating as column name.", trimmed);
                                }
                                return Map.<String, Object>of("name", str, "type", "text");
                            }
                        }
                        return Map.<String, Object>of("name", str, "type", "text");
                    }
                    throw new IllegalArgumentException(
                        "Column items must be objects or strings. Got: " + item.getClass().getSimpleName());
                })
                .toList();
        }
        throw new IllegalArgumentException(
            "columns must be an array. Example: columns=[{name: 'email', type: 'text'}, {name: 'count', type: 'number'}]");
    }
}
