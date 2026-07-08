package com.apimarketplace.datasource.tools.datasource;

import com.apimarketplace.agent.config.ToolAccessControl;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.common.ToolModule;
import com.apimarketplace.agent.tools.common.ToolParamUtils;
import com.apimarketplace.agent.tools.common.ToolRateLimiter;
import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.datasource.config.DataSourceAgentDefaultsConfig;
import com.apimarketplace.datasource.domain.ColumnType;
import com.apimarketplace.datasource.domain.DataSourceModels.ColumnMappingSpec;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSource;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSourceType;
import com.apimarketplace.datasource.services.DataSourceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.apimarketplace.datasource.tools.datasource.ToolParameterUtils.*;

/**
 * Module handling table-level CRUD operations for DataSource tools.
 * Datasource-service native version - uses DataSourceService directly (no HTTP hop).
 * Operations: create, get, list, update, delete, help
 */
@Component
public class DataSourceTableModule implements ToolModule {

    private static final Logger log = LoggerFactory.getLogger(DataSourceTableModule.class);

    private final DataSourceService dataSourceService;
    private final ObjectMapper objectMapper;
    private final DataSourceAgentDefaultsConfig agentDefaults;
    private final com.apimarketplace.datasource.services.VectorFeatureGate vectorFeatureGate;
    private final ToolRateLimiter createLimiter = new ToolRateLimiter();

    public DataSourceTableModule(DataSourceService dataSourceService,
                                 ObjectMapper objectMapper,
                                 DataSourceAgentDefaultsConfig agentDefaults,
                                 com.apimarketplace.datasource.services.VectorFeatureGate vectorFeatureGate) {
        this.dataSourceService = dataSourceService;
        this.objectMapper = objectMapper;
        this.agentDefaults = agentDefaults;
        this.vectorFeatureGate = vectorFeatureGate;
    }

    /**
     * Resolves the per-turn table-create cap via
     * {@link com.apimarketplace.agent.config.GuardOverrides#resolve}:
     * caller-agent / conversation-scope credential (via
     * {@code __chatMaxPerResourcePerTurn__}, propagated by
     * conversation-service/AgentContextBuilder) → YAML default. No direct
     * caller-agent lookup path here: datasource-service has no agents table,
     * so the effective per-agent value arrives through credentials.
     */
    int resolveMaxPerResourcePerTurn(ToolExecutionContext context) {
        int fallback = agentDefaults.getMaxPerResourcePerTurn();
        Map<String, Object> credentials = context != null ? context.credentials() : null;
        return com.apimarketplace.agent.config.GuardOverrides.resolve(
            null, credentials,
            com.apimarketplace.agent.config.GuardOverrides.CRED_MAX_PER_RESOURCE_PER_TURN,
            fallback);
    }

    // Track recent list calls per conversation+page to detect loops.
    // Keyed by conversation + page signature (offset/limit) so genuine pagination
    // (a NEW page) is never mistaken for a redundant re-list - see executeList.
    private final ConcurrentHashMap<String, Long> recentListCalls = new ConcurrentHashMap<>();
    private static final long LIST_COOLDOWN_MS = 30_000; // 30 seconds

    // Time source - overridable in tests to exercise cooldown expiry/eviction deterministically.
    private java.util.function.LongSupplier clock = System::currentTimeMillis;

    void setClock(java.util.function.LongSupplier clock) {
        this.clock = clock;
    }

    /** Test-only view of the loop-detection cache size (to assert it stays bounded). */
    int recentListCacheSize() {
        return recentListCalls.size();
    }

    private static final Set<String> HANDLED_ACTIONS = Set.of(
        "create", "get", "list", "update", "delete", "help"
    );

    @Override
    public List<AgentToolDefinition> getToolDefinitions() {
        return List.of();
    }

    @Override
    public boolean canHandle(String action) {
        return HANDLED_ACTIONS.contains(action);
    }

    @Override
    public Optional<ToolExecutionResult> execute(String action, Map<String, Object> parameters,
                                                  String tenantId, ToolExecutionContext context) {
        if (!canHandle(action)) {
            return Optional.empty();
        }

        // Access mode check (read/write)
        var accessDenied = ToolAccessControl.checkWriteAccess(
                context != null ? context.credentials() : null, "table", action);
        if (accessDenied.isPresent()) return Optional.of(ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, accessDenied.get()));

        return Optional.of(switch (action) {
            case "create" -> executeCreate(parameters, tenantId, context);
            case "get" -> executeGet(parameters, tenantId, context);
            case "list" -> executeList(parameters, tenantId, context);
            case "update" -> executeUpdate(parameters, tenantId, context);
            case "delete" -> executeDelete(parameters, tenantId, context);
            case "help" -> executeHelp();
            default -> ToolExecutionResult.failure(ToolErrorCode.TOOL_NOT_FOUND, "Unknown action: " + action);
        });
    }

    // ==================== Create ====================

    private ToolExecutionResult executeCreate(Map<String, Object> parameters, String tenantId,
                                               ToolExecutionContext context) {
        String name = (String) parameters.get("name");
        String description = (String) parameters.get("description");
        Object dataObj = parameters.get("data");
        Object columnsObj = parameters.get("columns");

        if (name == null || name.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, MISSING_NAME_HINT);
        }

        // Per-turn create cap (uniform across resource types).
        String turnId = context != null
            ? com.apimarketplace.agent.tools.common.ToolParamUtils.getTurnId(context.credentials())
            : null;
        if (turnId != null) {
            int maxCreates = resolveMaxPerResourcePerTurn(context);
            String createKey = tenantId + ":table:" + turnId;
            var limitResult = createLimiter.checkLimit(createKey, maxCreates,
                "LIMIT REACHED: You have already created " + maxCreates + " tables for this message.\n\n"
                + "WHAT TO DO:\n"
                + "1. Use table(action='list') to see existing tables.\n"
                + "2. Use table(action='update', id='...') to add rows to an existing table.\n"
                + "3. Ask the user which table to extend rather than creating more.\n\n"
                + "DO NOT create more tables. Work with what exists.");
            if (limitResult.isPresent()) return limitResult.get();
        }

        try {
            // Copy into a mutable list so sanitizeAndValidateRowKeys can rewrite entries.
            List<Map<String, Object>> data = dataObj != null
                ? new ArrayList<>(parseDataArray(dataObj, objectMapper))
                : new ArrayList<>();

            boolean isSchemaOnly = false;
            List<String> columnNames = new ArrayList<>();
            Map<String, ColumnMappingSpec> mappingSpec = null;

            if (data.isEmpty()) {
                if (columnsObj == null) {
                    return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                        "Either data[] or columns[] is required. Use data for items, or columns for schema-only table.");
                }
                List<Map<String, Object>> columnsList = parseColumnsArray(columnsObj, objectMapper, log);
                if (columnsList.isEmpty()) {
                    return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR,
                        "columns must contain at least one column definition");
                }
                String validationError = validateColumnDefinitions(columnsList, vectorFeatureGate.isVectorAllowed());
                if (validationError != null) {
                    return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR, validationError);
                }
                // Build mappingSpec from columns - no placeholder row needed
                mappingSpec = new LinkedHashMap<>();
                for (Map<String, Object> col : columnsList) {
                    String colName = sanitizeColumnName((String) col.get("name"));
                    if (colName != null) {
                        columnNames.add(colName);
                        mappingSpec.put(colName, buildColumnMappingSpec(colName, col));
                    }
                }
                isSchemaOnly = true;
            } else if (columnsObj != null) {
                List<Map<String, Object>> columnsList = parseColumnsArray(columnsObj, objectMapper, log);
                if (!columnsList.isEmpty()) {
                    String validationError = validateColumnDefinitions(columnsList, vectorFeatureGate.isVectorAllowed());
                    if (validationError != null) {
                        return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR, validationError);
                    }
                    mappingSpec = new LinkedHashMap<>();
                    for (Map<String, Object> col : columnsList) {
                        String colName = sanitizeColumnName((String) col.get("name"));
                        if (colName != null) {
                            mappingSpec.put(colName, buildColumnMappingSpec(colName, col));
                        }
                    }
                }
            }

            // Whenever data[] is non-empty, every row key is a column name candidate
            // (regardless of whether columns[] is also present - extra keys beyond
            // the declared columns[] still flow into JSONB). Enforce the reserved-
            // name rule on every row and also sanitize the `data.` prefix in place
            // so the persisted keys match what downstream readers expect.
            if (!data.isEmpty()) {
                String rowKeyErr = sanitizeAndValidateRowKeys(data);
                if (rowKeyErr != null) {
                    return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR, rowKeyErr);
                }
            }

            // Direct service call - no HTTP hop. Use 9-arg overload so
            // organization_id gets stamped from the MCP context - without
            // it the table lands NULL-org and org-teammates can't see it.
            // Audit 2026-05-16.
            String orgId = context != null ? context.orgId() : null;
            DataSource result = dataSourceService.createDataSource(
                tenantId, name, description,
                DataSourceType.INLINE, Map.of(),
                data, tenantId, mappingSpec, orgId
            );

            if (result == null) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to create data source");
            }

            String datasourceId = String.valueOf(result.id());

            // Auto-grant: agent gets access to the table it just created
            ToolAccessControl.grantCreatedResource(context.credentials(), "table", datasourceId);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("id", result.id());
            response.put("name", result.name());
            response.put("status", "CREATED");
            response.put("marker", "[visualize:datasource:" + result.id() + "]");

            if (isSchemaOnly) {
                response.put("itemCount", 0);
                response.put("columns", columnNames);
                response.put("message", "Created empty table '" + result.name() + "' with " +
                    columnNames.size() + " columns: " + columnNames + ". Use insert_rows to add data.");
            } else {
                response.put("itemCount", data.size());
                response.put("message", "Created table '" + result.name() + "' with " + data.size() + " items.");
            }

            Map<String, Object> metadata = tableVisualizationMetadata(result.id(), result.name());

            return ToolExecutionResult.success(response, metadata);
        } catch (com.apimarketplace.auth.client.entitlement.LimitExceededException e) {
            return ToolExecutionResult.failure(ToolErrorCode.QUOTA_EXCEEDED, e.getMessage());
        } catch (Exception e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                "Failed to create data source: " + e.getMessage());
        }
    }

    // ==================== Get ====================

    private ToolExecutionResult executeGet(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        Long id = getTableId(parameters);
        if (id == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, MISSING_TABLE_ID_HINT);
        }

        List<String> allowedTableIds = getAllowedTableIds(context);
        if (allowedTableIds != null && !allowedTableIds.contains(String.valueOf(id))) {
            log.info("Agent restriction: table {} not in allowed list", id);
            return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, "This table is not in your approved table list.");
        }

        try {
            Optional<DataSource> opt = dataSourceService.getDataSource(id);
            if (opt.isEmpty()) {
                return ToolExecutionResult.failure(ToolErrorCode.DATASOURCE_NOT_FOUND, "Data source not found: " + id);
            }
            DataSource ds = opt.get();
            if (isOutOfScope(ds, tenantId, context)) {
                return outOfScopeNotFound(id);
            }

            return ToolExecutionResult.success(Map.of(
                "id", ds.id(),
                "name", ds.name(),
                "description", ds.description() != null ? ds.description() : "",
                "sourceType", ds.sourceType() != null ? ds.sourceType().name() : "",
                "sourceConfig", ds.sourceConfig() != null ? ds.sourceConfig() : Map.of(),
                "status", ds.status() != null ? ds.status().name() : "",
                "marker", "[visualize:datasource:" + ds.id() + "]"
            ));
        } catch (Exception e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                "Failed to get data source: " + e.getMessage());
        }
    }

    // ==================== List ====================

    private ToolExecutionResult executeList(Map<String, Object> parameters, String tenantId,
                                             ToolExecutionContext context) {
        Integer limit = getIntParam(parameters, "limit", 25);
        Integer offset = getIntParam(parameters, "offset", 0);
        // Text search: case-insensitive substring over name + description, applied
        // BEFORE pagination so total/hasMore reflect the filtered set.
        String query = ToolParamUtils.getStringParam(parameters, "query");

        // Loop detection: only a repeat of the SAME page (same offset+limit+query) within
        // the cooldown is treated as a redundant re-list. A pagination call advances offset,
        // producing a new page key, so the agent can page through results freely - the
        // first page's own response instructs "Use offset=N to see more.". The query is
        // part of the key so a filtered re-list at the same offset is NOT mistaken for a
        // redundant repeat of the unfiltered page.
        String conversationId = getConversationId(context);
        if (conversationId != null) {
            long now = clock.getAsLong();
            // Bound the cache to the active working set: entries past the cooldown can no
            // longer block anything, so drop them before recording this call. Without this,
            // an agent paging with many distinct offsets would grow the map unbounded
            // (key = conversation+offset+limit+query, all agent-controlled).
            recentListCalls.entrySet().removeIf(e -> now - e.getValue() >= LIST_COOLDOWN_MS);

            String queryKey = ToolParamUtils.hasQuery(query) ? query.trim().toLowerCase(Locale.ROOT) : "";
            String pageKey = conversationId + ":" + offset + ":" + limit + ":" + queryKey;
            Long lastCall = recentListCalls.get(pageKey);
            if (lastCall != null && (now - lastCall) < LIST_COOLDOWN_MS) {
                return ToolExecutionResult.success(Map.of(
                    "status", "ALREADY_LISTED",
                    "message", "You already listed this page of tables. Use the IDs from the previous result, " +
                        "or call list with a higher offset to see more. " +
                        "To add data: table(action='insert_rows', table_id=ID, rows=[{field: value, ...}])"
                ));
            }
            recentListCalls.put(pageKey, now);
        }

        try {
            String orgId = context != null ? context.orgId() : null;
            String orgRole = context != null ? context.orgRole() : null;
            List<DataSource> allDataSources = dataSourceService.getDataSources(tenantId, orgId, orgRole);

            List<String> allowedTableIds = getAllowedTableIds(context);
            if (allowedTableIds != null) {
                allDataSources = allDataSources.stream()
                    .filter(ds -> allowedTableIds.contains(String.valueOf(ds.id())))
                    .toList();
                log.info("Agent restriction: filtered tables to {}/{} allowed",
                    allDataSources.size(), allowedTableIds.size());
            }

            if (ToolParamUtils.hasQuery(query)) {
                allDataSources = allDataSources.stream()
                    .filter(ds -> ToolParamUtils.matchesQuery(query, ds.name(), ds.description()))
                    .toList();
            }

            int total = allDataSources.size();

            List<Map<String, Object>> summaries = allDataSources.stream()
                .skip(offset)
                .limit(limit)
                .map(ds -> Map.<String, Object>of(
                    "id", ds.id(),
                    "name", ds.name(),
                    "sourceType", ds.sourceType().name(),
                    "status", ds.status().name()
                ))
                .toList();

            int count = summaries.size();
            boolean hasMore = (offset + count) < total;
            String message = hasMore
                ? "Showing " + count + " of " + total + " data sources. Use offset=" + (offset + limit) + " to see more."
                : "Listed " + count + " tables. Now use their IDs to perform actions.";

            return ToolExecutionResult.success(Map.of(
                "dataSources", summaries,
                "count", count,
                "total", total,
                "hasMore", hasMore,
                "status", "OK",
                "message", message
            ));
        } catch (Exception e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                "Failed to list data sources: " + e.getMessage());
        }
    }

    // ==================== Update ====================

    private ToolExecutionResult executeUpdate(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        Long id = getTableId(parameters);
        String name = (String) parameters.get("name");
        String description = (String) parameters.get("description");

        if (id == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, MISSING_TABLE_ID_HINT);
        }

        List<String> allowedTableIds = getAllowedTableIds(context);
        if (allowedTableIds != null && !allowedTableIds.contains(String.valueOf(id))) {
            log.info("Agent restriction: table {} not in allowed list", id);
            return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, "This table is not in your approved table list.");
        }

        try {
            Optional<DataSource> existing = dataSourceService.getDataSource(id);
            if (existing.isEmpty()) {
                return ToolExecutionResult.failure(ToolErrorCode.DATASOURCE_NOT_FOUND, "Data source not found: " + id);
            }
            DataSource ds = existing.get();
            if (isOutOfScope(ds, tenantId, context)) {
                return outOfScopeNotFound(id);
            }

            String newName = (name != null && !name.isBlank()) ? name : ds.name();
            String newDescription = description != null ? description : ds.description();

            // Direct service call - no HTTP hop
            DataSource result = dataSourceService.updateDataSource(id, newName, newDescription,
                ds.sourceConfig());

            Map<String, Object> metadata = tableVisualizationMetadata(result.id(), result.name());

            return ToolExecutionResult.success(Map.of(
                "id", result.id(),
                "name", result.name(),
                "status", "UPDATED",
                "message", "You successfully updated data source '" + result.name() + "'.",
                "marker", "[visualize:datasource:" + result.id() + "]"
            ), metadata);
        } catch (Exception e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                "Failed to update data source: " + e.getMessage());
        }
    }

    // ==================== Delete ====================

    private ToolExecutionResult executeDelete(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        Long id = getTableId(parameters);
        if (id == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, MISSING_TABLE_ID_HINT);
        }

        List<String> allowedTableIds = getAllowedTableIds(context);
        if (allowedTableIds != null && !allowedTableIds.contains(String.valueOf(id))) {
            log.info("Agent restriction: table {} not in allowed list", id);
            return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, "This table is not in your approved table list.");
        }

        try {
            Optional<DataSource> existing = dataSourceService.getDataSource(id);
            if (existing.isEmpty()) {
                return ToolExecutionResult.failure(ToolErrorCode.DATASOURCE_NOT_FOUND, "Data source not found: " + id);
            }
            if (isOutOfScope(existing.get(), tenantId, context)) {
                return outOfScopeNotFound(id);
            }

            String deletedName = existing.get().name();
            dataSourceService.deleteDataSource(id);

            return ToolExecutionResult.success(Map.of(
                "id", id,
                "name", deletedName,
                "status", "DELETED",
                "message", "You successfully deleted table '" + deletedName + "'. To see remaining tables: table(action='list')"
            ));
        } catch (Exception e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                "Failed to delete data source: " + e.getMessage());
        }
    }

    // ==================== Help ====================

    private ToolExecutionResult executeHelp() {
        Map<String, Object> help = new LinkedHashMap<>();

        help.put("description", "Persistent database table storage. Use table() to create, query, and manage structured data. " +
            "IMPORTANT: Object keys in data[] become column names - always use meaningful names derived from the user's request " +
            "(e.g. 'category', 'price', 'name'), NEVER generic names like 'Colonne 1', 'col1', 'Column A'.");

        help.put("actions", Map.ofEntries(
            Map.entry("create", "Create table (name REQUIRED, data[] and/or columns[] - at least one). " +
                "data=rows with keys as column names; columns=schema with types. Both together: columns define types for the data keys."),
            Map.entry("get", "Get table metadata by ID (table_id REQUIRED)"),
            Map.entry("list", "List all tables (limit? default=25, offset? default=0)"),
            Map.entry("update", "Update table metadata (table_id REQUIRED, name?, description?)"),
            Map.entry("delete", "Delete a table permanently (table_id REQUIRED)"),
            Map.entry("query_rows", "Query rows (table_id REQUIRED, where?, similarity?, limit? default=20). " +
                "similarity={column, queryVector, topK?, threshold?} for vector nearest-neighbor search (RAG). Can combine with where for hybrid filtering."),
            Map.entry("insert_rows", "Insert rows (table_id REQUIRED, rows=[{field: value, ...}]). Keys must match existing column names."),
            Map.entry("update_rows", "Update rows matching where (table_id REQUIRED, where REQUIRED, set REQUIRED)"),
            Map.entry("delete_rows", "Delete rows matching where (table_id REQUIRED, where REQUIRED)"),
            Map.entry("add_columns", "Add columns (table_id REQUIRED, columns=[{name, type, display?, defaultValue?}])"),
            Map.entry("publish",
                "Add the table to the marketplace. Params: table_id REQUIRED, title REQUIRED, " +
                "interface_id REQUIRED (UUID of the landing interface shown to acquirers before install), " +
                "visibility ('PRIVATE' default, 'PUBLIC', 'UNLISTED'), credits_per_use (default 0). " +
                "PUBLIC listings go through platform review; PRIVATE/UNLISTED activate immediately."),
            Map.entry("unpublish",
                "Mark the table's marketplace listing inactive. Params: table_id REQUIRED. " +
                "Existing acquirers keep their copies - only new installs are blocked.")
        ));

        help.put("columnNaming", Map.of(
            "rule", "Column names come from data object keys. Use descriptive names from the user's domain.",
            "good", "data=[{category: 'Electronics', price: 199, in_stock: true}]",
            "bad", "data=[{Colonne 1: 'Electronics', Colonne 2: 199}]",
            "consistency", "insert_rows keys must match column names from create",
            "reserved", "These names are rejected (used by internal columns): "
                + String.join(", ", new TreeSet<>(RESERVED_COLUMN_NAMES))
                + ". Pick a synonym (e.g. 'modified_at' instead of 'updated_at', 'rank' instead of 'priority')."
        ));

        help.put("whereOperators", List.of("=", "==", "!=", "<>", ">", "<", ">=", "<=", "LIKE", "IN", "IS NULL", "IS NOT NULL"));

        help.put("typeInference", "When creating with data[] only (no columns[]), types are auto-inferred: " +
            "strings→text, numbers→number, booleans→checkbox, arrays→multi_select, dates→date, emails→email, URLs→url. " +
            "Auto-inference CANNOT produce 'select' - to get dropdown/pill columns, pass columns[] alongside data[] with the desired type and display options.");

        // Agent-facing contract: only advertise what THIS deployment can do.
        // On managed cloud the vector type is rejected by validation, so it
        // must not appear here at all (an advertised-but-rejected type sends
        // the agent into a retry loop).
        Map<String, Object> columnTypes = new LinkedHashMap<>(Map.ofEntries(
            Map.entry("text", "Free-form text (default if no type specified)"),
            Map.entry("number", "Numeric values. display: {format: 'plain'|'currency'|'percentage', decimals: 2, currencySymbol: '$'}"),
            Map.entry("date", "Date/time values. display: {dateFormat: 'date'|'datetime'|'time'}"),
            Map.entry("checkbox", "True/false toggle"),
            Map.entry("select", "Single choice from predefined options, displayed as a colored pill. " +
                "REQUIRES display.options. display: {options: [{label: 'Active', value: 'Active', color: '#22c55e'}, {label: 'Inactive', value: 'Inactive', color: '#ef4444'}]}"),
            Map.entry("multi_select", "Multiple choices from predefined options, displayed as colored tags. " +
                "REQUIRES display.options. display: {options: [{label: 'Frontend', value: 'Frontend', color: '#8b5cf6'}, {label: 'Backend', value: 'Backend', color: '#3b82f6'}]}"),
            Map.entry("rating", "Star rating. display: {max: 5, color: '#fbbf24'}"),
            Map.entry("sentiment", "Thumbs up/down. Values: 'up', 'down', 'neutral'. display: {labels: {up: 'Good', down: 'Bad'}}"),
            Map.entry("progress", "Progress bar 0-max. display: {max: 100}"),
            Map.entry("file", "File link/attachment"),
            Map.entry("image", "Image thumbnail. display: {imageFit: 'cover', ratio: '4:3'}"),
            Map.entry("email", "Email address with mailto: link"),
            Map.entry("phone", "Phone number with tel: link"),
            Map.entry("url", "Clickable URL (opens in new tab)")
        ));
        if (vectorFeatureGate.isVectorAllowed()) {
            columnTypes.put("vector", "Embedding vector for similarity search (RAG). display: {dimension: 1536, metric: 'cosine'|'l2'|'dot'}. " +
                "Vectors are stored separately in a dedicated vector table (pgvector). " +
                "Use with crud-find + similarity config for nearest-neighbor queries.");
        }
        help.put("columnTypes", columnTypes);

        help.put("examples", List.of(
            Map.of("action", "create (data + columns with types)",
                "example", "table(action='create', name='products', " +
                    "data=[{category: 'Electronics', product: 'Laptop', price: 999}, {category: 'Books', product: 'Novel', price: 15}], " +
                    "columns=[{name: 'category', type: 'select', display: {options: [{label: 'Electronics', value: 'Electronics', color: '#3b82f6'}, " +
                    "{label: 'Books', value: 'Books', color: '#8b5cf6'}, {label: 'Clothing', value: 'Clothing', color: '#22c55e'}]}}, " +
                    "{name: 'product', type: 'text'}, {name: 'price', type: 'number', display: {format: 'currency', currencySymbol: '$'}}])"),
            Map.of("action", "create (data only - all strings default to text, no dropdowns)",
                "example", "table(action='create', name='notes', data=[{title: 'Meeting', content: 'Discuss roadmap'}])"),
            Map.of("action", "create (schema only, no data yet)",
                "example", "table(action='create', name='tasks', columns=[{name: 'title', type: 'text'}, {name: 'status', type: 'select', " +
                    "display: {options: [{label: 'To Do', value: 'To Do', color: '#f59e0b'}, {label: 'Done', value: 'Done', color: '#22c55e'}]}}, " +
                    "{name: 'done', type: 'checkbox'}])"),
            Map.of("action", "insert_rows (keys match column names)",
                "example", "table(action='insert_rows', table_id=1, rows=[{category: 'Clothing', product: 'Shirt', price: 29}])"),
            Map.of("action", "query_rows",
                "example", "table(action='query_rows', table_id=1, where={column: 'category', operator: '=', value: 'Electronics'})"),
            Map.of("action", "update_rows",
                "example", "table(action='update_rows', table_id=1, where={column: 'id', operator: '=', value: 5}, set={price: 899})"),
            Map.of("action", "add_columns",
                "example", "table(action='add_columns', table_id=1, columns=[{name: 'status', type: 'select', display: {options: [{label: 'Active', value: 'Active', color: '#22c55e'}, {label: 'Done', value: 'Done', color: '#6366f1'}]}, defaultValue: 'Active'}])")
        ));

        // Similarity-search examples only where the feature exists.
        if (vectorFeatureGate.isVectorAllowed()) {
            List<Map<String, String>> examples = new ArrayList<>((List<Map<String, String>>) help.get("examples"));
            examples.add(Map.of("action", "query_rows (similarity search)",
                "example", "table(action='query_rows', table_id=1, similarity={column: 'embedding', queryVector: [0.1, 0.2, ...], topK: 5, threshold: 0.8})"));
            examples.add(Map.of("action", "query_rows (hybrid: similarity + where)",
                "example", "table(action='query_rows', table_id=1, similarity={column: 'embedding', queryVector: [0.1, 0.2, ...], topK: 10}, where={column: 'category', operator: '=', value: 'recipes'})"));
            help.put("examples", examples);
        }

        return ToolExecutionResult.success(help);
    }

    // ==================== Helper Methods ====================

    /**
     * Strict-scope gate for table-level get/update/delete. The agent supplies a numeric
     * table id, so without this an agent in mode {@code all} (no allowlist) could
     * read/mutate/delete a table in another workspace simply by guessing an id:
     * {@link DataSourceService#getDataSource(Long)} is an unscoped {@code findById}.
     * Mirrors the row-path guard in {@code CrudExecutorService.verifyDataSourceAccess}
     * so a table is reachable through these actions iff its rows are too. The allowlist
     * check (when present) is an orthogonal, narrower gate; this is the always-on
     * tenant/org isolation floor.
     */
    private boolean isOutOfScope(DataSource ds, String tenantId, ToolExecutionContext context) {
        String orgId = context != null ? context.orgId() : null;
        return !ScopeGuard.isInStrictScope(tenantId, orgId, ds.tenantId(), ds.organizationId());
    }

    /**
     * Out-of-scope rows map to NOT_FOUND (never PERMISSION_DENIED) so a row's existence
     * is not leaked across workspace boundaries: same contract as the row CRUD path.
     */
    private ToolExecutionResult outOfScopeNotFound(Long id) {
        log.warn("Table {} not in caller scope, denying as not-found", id);
        return ToolExecutionResult.failure(ToolErrorCode.DATASOURCE_NOT_FOUND, "Data source not found: " + id);
    }

    private List<String> getAllowedTableIds(ToolExecutionContext context) {
        // Read from the canonical CREDENTIALS channel (ToolAccessControl.getAllowedIds) so the
        // list round-trips with grantCreatedResource (which writes credentials) - see
        // TableToolAccess. Previously this read context.variables(), diverging from the grant
        // write channel and from every sibling resource module (silent create-grant no-op).
        // ToolAccessControl.getAllowedIds stringifies each id, so a numeric-ID allowlist
        // (tables:[209] from an MCP-created agent) matches `.contains(String.valueOf(id))`.
        return TableToolAccess.allowedTableIds(context);
    }

    private String getConversationId(ToolExecutionContext context) {
        if (context == null || context.variables() == null) {
            return null;
        }
        Object convId = context.variables().get("conversationId");
        return convId != null ? convId.toString() : null;
    }

    @SuppressWarnings("unchecked")
    private ColumnMappingSpec buildColumnMappingSpec(String colName, Map<String, Object> col) {
        String typeStr = (String) col.getOrDefault("type", "text");
        ColumnType type = ColumnType.fromValue(typeStr);
        Map<String, Object> display = null;
        Object displayObj = col.get("display");
        if (displayObj instanceof Map<?, ?> displayMap) {
            display = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : displayMap.entrySet()) {
                display.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return new ColumnMappingSpec("data." + colName, type, null, null, display);
    }

    /**
     * Mutate {@code data} in place: strip {@code data.} prefix from every row key and
     * reject reserved-name collisions. The prefix strip matches {@code insert_rows}'
     * {@code sanitizeKeys}, so both code paths persist identical JSONB keys.
     * Returns an error message, or null if all rows are valid.
     */
    static String sanitizeAndValidateRowKeys(List<Map<String, Object>> data) {
        for (int i = 0; i < data.size(); i++) {
            Map<String, Object> row = data.get(i);
            if (row == null || row.isEmpty()) continue;
            Map<String, Object> cleaned = new LinkedHashMap<>(row.size());
            for (Map.Entry<String, Object> e : row.entrySet()) {
                String sanitized = sanitizeColumnName(e.getKey());
                String err = validateReservedColumnName(sanitized);
                if (err != null) return err;
                cleaned.put(sanitized, e.getValue());
            }
            data.set(i, cleaned);
        }
        return null;
    }
}
