package com.apimarketplace.datasource.tools.datasource;

import com.apimarketplace.agent.config.ToolAccessControl;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.common.ToolModule;
import com.apimarketplace.datasource.crud.domain.CrudResult;
import com.apimarketplace.datasource.crud.dto.CreateColumnRequest;
import com.apimarketplace.datasource.crud.service.CrudExecutorService;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSource;
import com.apimarketplace.datasource.services.DataSourceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.apimarketplace.datasource.tools.datasource.ToolParameterUtils.*;

/**
 * Module handling schema operations for DataSource tools.
 * Datasource-service native version - uses CrudExecutorService directly (no HTTP hop).
 * Operations: add_columns
 */
@Component
public class DataSourceSchemaModule implements ToolModule {

    private static final Logger log = LoggerFactory.getLogger(DataSourceSchemaModule.class);

    private final CrudExecutorService crudExecutorService;
    private final DataSourceService dataSourceService;
    private final ObjectMapper objectMapper;
    private final com.apimarketplace.datasource.services.VectorFeatureGate vectorFeatureGate;

    public DataSourceSchemaModule(CrudExecutorService crudExecutorService,
                                  DataSourceService dataSourceService,
                                  ObjectMapper objectMapper,
                                  com.apimarketplace.datasource.services.VectorFeatureGate vectorFeatureGate) {
        this.crudExecutorService = crudExecutorService;
        this.dataSourceService = dataSourceService;
        this.objectMapper = objectMapper;
        this.vectorFeatureGate = vectorFeatureGate;
    }

    private static final Set<String> HANDLED_ACTIONS = Set.of("add_columns");

    @Override
    public List<AgentToolDefinition> getToolDefinitions() {
        return List.of();
    }

    @Override
    public boolean canHandle(String toolName) {
        return HANDLED_ACTIONS.contains(toolName);
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

        // Allow-list check: a tables=custom agent must not alter the schema of a table outside its
        // approved list (add_columns mutates structure) - mirror the table CRUD module's gate.
        var notAllowed = TableToolAccess.denyIfNotAllowed(context, getTableId(parameters));
        if (notAllowed.isPresent()) return notAllowed;

        // Thread the caller's org workspace through to the CRUD executor so the
        // strict-scope check matches the org id executeCreate stored on the
        // datasource (org-workspace users otherwise get "DataSource not found").
        String orgId = context != null ? context.orgId() : null;
        return Optional.of(switch (action) {
            case "add_columns" -> executeAddColumns(parameters, tenantId, orgId);
            default -> ToolExecutionResult.failure(ToolErrorCode.TOOL_NOT_FOUND, "Unknown action: " + action);
        });
    }

    @SuppressWarnings("unchecked")
    private ToolExecutionResult executeAddColumns(Map<String, Object> parameters, String tenantId, String orgId) {
        Long datasourceId = getTableId(parameters);
        if (datasourceId == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "table_id is required. Example: table(action='add_columns', table_id=1, columns=[...])");
        }

        Object columnsObj = parameters.get("columns");
        if (columnsObj == null) {
            // Advertise only the types this deployment accepts - vector is
            // self-hosted-only and must not be suggested where it would be
            // rejected.
            String validTypes = "text, number, date, checkbox, select, multi_select, rating, sentiment, progress, file, image, email, phone, url"
                + (vectorFeatureGate.isVectorAllowed() ? ", vector" : "");
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "columns is required. Format: columns=[{name: 'col1', type: 'text'}, {name: 'col2', type: 'number'}]. " +
                "Valid types: " + validTypes + ". " +
                "See table(action='help') for display config options.");
        }

        try {
            List<Map<String, Object>> columnsList = parseColumnsArray(columnsObj, objectMapper, log);
            if (columnsList.isEmpty()) {
                return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR,
                    "columns must contain at least one column definition. " +
                    "Correct format: [{name: 'col1', type: 'text'}, {name: 'col2', type: 'number'}]");
            }

            // Validate: reserved names, known types, edition gate, no intra-request duplicates
            String validationError = validateColumnDefinitions(columnsList, vectorFeatureGate.isVectorAllowed());
            if (validationError != null) {
                return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR, validationError);
            }

            // Reject names that already exist on the table
            Optional<DataSource> dsOpt = dataSourceService.getDataSource(datasourceId);
            Set<String> existing = collectExistingColumnNames(dsOpt);
            for (Map<String, Object> col : columnsList) {
                String colName = sanitizeColumnName((String) col.get("name"));
                if (colName != null && existing.contains(colName.toLowerCase().trim())) {
                    return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR,
                        "Column '" + colName + "' already exists on this table. "
                            + "Use a different name, or call table(action='update_rows', ...) to modify existing data.");
                }
            }

            // Convert to CreateColumnRequest.ColumnDefinition format
            List<CreateColumnRequest.ColumnDefinition> columns = columnsList.stream()
                .map(col -> {
                    Map<String, Object> display = null;
                    Object displayObj = col.get("display");
                    if (displayObj instanceof Map<?, ?> displayMap) {
                        display = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> entry : displayMap.entrySet()) {
                            display.put(String.valueOf(entry.getKey()), entry.getValue());
                        }
                    }
                    return new CreateColumnRequest.ColumnDefinition(
                        sanitizeColumnName((String) col.get("name")),
                        (String) col.getOrDefault("type", "text"),
                        col.get("defaultValue") != null ? String.valueOf(col.get("defaultValue")) : null,
                        display
                    );
                })
                .toList();

            CreateColumnRequest request = new CreateColumnRequest();
            request.setDataSourceId(datasourceId);
            request.setColumns(columns);

            CrudResult result = crudExecutorService.execute(request, tenantId, orgId);

            if (!result.success()) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, result.message());
            }

            Map<String, Object> resultMap = new LinkedHashMap<>();
            resultMap.put("datasourceId", datasourceId);
            resultMap.put("columnsAdded", columns.size());
            resultMap.put("columnNames", columns.stream().map(CreateColumnRequest.ColumnDefinition::name).toList());
            resultMap.put("status", "CREATED");
            resultMap.put("message", "Successfully added " + columns.size() + " columns to table " + datasourceId + ".");
            resultMap.put("marker", "[visualize:datasource:" + datasourceId + "]");

            String title = dsOpt.map(DataSource::name).orElse(null);
            Map<String, Object> metadata = tableVisualizationMetadata(datasourceId, title);

            return ToolExecutionResult.success(resultMap, metadata);
        } catch (Exception e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to add columns: " + e.getMessage());
        }
    }

    /**
     * Collect every existing column name on the data source (case-insensitive, trimmed).
     * Reads from both {@code columnOrder} (persisted with key {@code field}) and
     * {@code mappingSpec} (keyed by the column name), so we catch mismatched shapes
     * and system-added columns alike.
     */
    private static Set<String> collectExistingColumnNames(Optional<DataSource> dsOpt) {
        if (dsOpt.isEmpty()) return Set.of();
        DataSource ds = dsOpt.get();
        Set<String> names = new HashSet<>();
        if (ds.columnOrder() != null) {
            for (Map<String, Object> col : ds.columnOrder()) {
                // Persisted shape is {field, order}; accept "name" as a lenient fallback
                // in case future code or tests use the tool-input shape.
                Object n = col.get("field");
                if (n == null) n = col.get("name");
                if (n != null) names.add(String.valueOf(n).toLowerCase().trim());
            }
        }
        if (ds.mappingSpec() != null) {
            for (String key : ds.mappingSpec().keySet()) {
                if (key != null) names.add(key.toLowerCase().trim());
            }
        }
        return names;
    }
}
