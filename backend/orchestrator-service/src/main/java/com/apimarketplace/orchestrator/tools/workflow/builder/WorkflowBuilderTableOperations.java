package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.datasource.client.dto.DataSourceDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import com.apimarketplace.agent.tools.ToolErrorCode;

/**
 * Handles simplified table operations (insert_row, read_rows, update_row, delete_row).
 * Transforms LLM-friendly parameters into proper crud/xxx step format.
 *
 * Extracted from WorkflowBuilderProvider for Single Responsibility Principle.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowBuilderTableOperations {

    private final DataSourceClient dataSourceClient;
    private final WorkflowBuilderCreator creator;

    /**
     * Handle table operation and delegate to step creation.
     */
    public ToolExecutionResult execute(WorkflowBuilderSession session,
                                       Map<String, Object> parameters,
                                       String tenantId,
                                       String operation) {
        // 1. Validate table_id - #TC3: find_rows help docs recommend `dataSourceId`,
        // insert/read/update/delete help docs recommend `table_id`. Accept any of the
        // three aliases here so docs-followers are never rejected at the entry point.
        Object tableIdObj = extractTableId(parameters);
        if (tableIdObj == null) {
            return buildTableIdRequiredError(tenantId, operation);
        }

        Long tableId;
        try {
            tableId = tableIdObj instanceof Number n ? n.longValue() : Long.parseLong(tableIdObj.toString());
        } catch (NumberFormatException e) {
            return buildTableIdRequiredError(tenantId, operation);
        }
        // Normalize to canonical table_id so downstream steps see a single key.
        parameters.put("table_id", tableId);

        // 2. Verify table exists
        DataSourceDto table = dataSourceClient.getDataSource(tableId, tenantId);
        if (table == null) {
            return buildTableNotFoundError(tenantId, tableId, operation);
        }

        // 3. Validate required parameters based on operation
        var validationError = validateParams(parameters, operation, table);
        if (validationError != null) {
            return validationError;
        }

        // 4. Transform to add_mcp parameters with crud/xxx format
        Map<String, Object> stepParams = transformToStep(parameters, operation, tableId);

        // 5. Extract toolId from stepParams and delegate to add_mcp
        String toolId = (String) stepParams.remove("id");
        return creator.executeAddTable(session, stepParams, toolId);
    }

    /**
     * #TC3: accept `table_id`, `dataSourceId`, `datasource_id`, or `tableId`. Canonical
     * key is `table_id`; the others are aliases the LLM/docs may emit.
     * Package-private for direct unit testing.
     */
    Object extractTableId(Map<String, Object> parameters) {
        Object v = parameters.get("table_id");
        if (v != null) return v;
        v = parameters.get("dataSourceId");
        if (v != null) return v;
        v = parameters.get("datasource_id");
        if (v != null) return v;
        return parameters.get("tableId");
    }

    private ToolExecutionResult buildTableIdRequiredError(String tenantId, String operation) {
        List<DataSourceDto> tables = dataSourceClient.getDataSourcesByTenant(tenantId);

        List<Map<String, Object>> availableTables = tables.stream()
            .limit(20)
            .map(ds -> {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("id", ds.id());
                info.put("name", ds.name());
                if (ds.columnOrder() != null && !ds.columnOrder().isEmpty()) {
                    info.put("columns", ds.columnOrder().stream()
                        .map(c -> {
                            // columnOrder uses "field" key (not "name")
                            Object field = c.get("field");
                            if (field == null) field = c.get("name");
                            return field != null ? sanitizeColumnDisplay(field.toString()) : null;
                        })
                        .filter(Objects::nonNull)
                        .limit(5)
                        .toList());
                }
                return info;
            })
            .toList();

        Map<String, Object> errorResponse = new LinkedHashMap<>();
        errorResponse.put("error", "table_id is required");
        errorResponse.put("available_tables", availableTables);
        errorResponse.put("usage", getUsageExample(operation));

        if (tables.isEmpty()) {
            errorResponse.put("hint", "No tables found. Create one first with: table(action='create', name='my_table', columns=[{name:'title', type:'text'}, {name:'status', type:'select'}])");
        }

        return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, errorResponse.toString());
    }

    private ToolExecutionResult buildTableNotFoundError(String tenantId, Long tableId, String operation) {
        List<DataSourceDto> tables = dataSourceClient.getDataSourcesByTenant(tenantId);

        List<Map<String, Object>> availableTables = tables.stream()
            .limit(10)
            .map(ds -> Map.<String, Object>of("id", ds.id(), "name", ds.name()))
            .toList();

        Map<String, Object> errorResponse = new LinkedHashMap<>();
        errorResponse.put("error", "Table with id=" + tableId + " not found");
        errorResponse.put("available_tables", availableTables);
        errorResponse.put("usage", getUsageExample(operation));

        return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, errorResponse.toString());
    }

    private String getUsageExample(String operation) {
        return switch (operation) {
            case "insert_row" -> "workflow(action='insert_row', label='Save', table_id=X, columns={name: 'value', status: '{{trigger:x.status}}'}, connect_after='...')";
            case "read_rows" -> "workflow(action='read_rows', label='Fetch', table_id=X, where={column: 'id', operator: '=', value: '123'}, limit=50, connect_after='...')";
            case "update_row" -> "workflow(action='update_row', label='Update', table_id=X, where={column: 'id', operator: '=', value: '{{trigger:x.id}}'}, set={status: 'done'}, connect_after='...')";
            case "delete_row" -> "workflow(action='delete_row', label='Delete', table_id=X, where={column: 'id', operator: '=', value: '123'}, connect_after='...')";
            case "find_rows" -> "workflow(action='find_rows', label='Find Active', table_id=X, where={column: 'status', operator: '=', value: 'active'}, limit=100, connect_after='...')";
            default -> "";
        };
    }

    private ToolExecutionResult validateParams(Map<String, Object> params, String operation, DataSourceDto table) {
        String label = (String) params.get("label");
        if (label == null || label.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "label is required. Example: " + getUsageExample(operation));
        }

        List<String> columnNames = table.columnOrder() != null
            ? table.columnOrder().stream()
                .map(c -> {
                    // columnOrder uses "field" key (not "name")
                    Object field = c.get("field");
                    if (field == null) field = c.get("name");
                    return field != null ? sanitizeColumnDisplay(field.toString()) : null;
                })
                .filter(Objects::nonNull)
                .toList()
            : List.of();

        return switch (operation) {
            case "insert_row" -> {
                if (params.get("columns") == null) {
                    yield ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "columns is required for insert_row. Available columns in table '" + table.name() + "': " +
                        columnNames + ". Example: columns={name:'John', email:'john@test.com'}");
                }
                yield null;
            }
            case "update_row" -> {
                if (params.get("where") == null) {
                    yield ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "where is required for update_row. Example: where={column:'id', operator:'=', value:'123'}");
                }
                if (params.get("set") == null) {
                    yield ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "set is required for update_row. Available columns: " + columnNames +
                        ". Example: set={status:'completed', updated_at:'{{now}}'}");
                }
                yield null;
            }
            case "delete_row" -> {
                if (params.get("where") == null) {
                    yield ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "where is required for delete_row. Example: where={column:'id', operator:'=', value:'123'}");
                }
                yield null;
            }
            case "read_rows", "find_rows" -> null; // where and limit are optional
            default -> null;
        };
    }

    /**
     * Strip "data." prefix from a column name for agent-facing display.
     */
    private static String sanitizeColumnDisplay(String name) {
        if (name != null && name.startsWith("data.")) {
            return name.substring("data.".length());
        }
        return name;
    }

    /**
     * Strip "data." prefix from where condition column name.
     * Agents may incorrectly include "data." prefix (internal storage detail).
     * The plan and frontend expect bare column names (e.g., "priority" not "data.priority").
     */
    @SuppressWarnings("unchecked")
    private void sanitizeWhereColumn(Map<String, Object> params) {
        Object whereObj = params.get("where");
        if (whereObj instanceof Map) {
            Map<String, Object> where = (Map<String, Object>) whereObj;
            Object col = where.get("column");
            if (col instanceof String colStr && colStr.startsWith("data.")) {
                Map<String, Object> sanitized = new LinkedHashMap<>(where);
                sanitized.put("column", colStr.substring("data.".length()));
                params.put("where", sanitized);
            }
        }
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> transformToStep(Map<String, Object> params, String operation, Long tableId) {
        // Sanitize where column before processing - strip "data." prefix if agent included it
        sanitizeWhereColumn(params);

        Map<String, Object> stepParams = new LinkedHashMap<>(params);

        String crudId = switch (operation) {
            case "insert_row" -> "crud/create-row";
            case "read_rows" -> "crud/read-row";
            case "update_row" -> "crud/update-row";
            case "delete_row" -> "crud/delete-row";
            case "find_rows" -> "crud/find-rows";
            case "create_column" -> "crud/create-column";
            default -> throw new IllegalArgumentException("Unknown operation: " + operation);
        };

        stepParams.put("id", crudId);
        stepParams.put("dataSourceId", tableId);

        Map<String, Object> crud = new LinkedHashMap<>();

        switch (operation) {
            case "insert_row" -> {
                Object columns = params.get("columns");
                if (columns instanceof Map) {
                    crud.put("rows", List.of(Map.of("columns", columns)));
                }
            }
            case "read_rows" -> {
                Object where = params.get("where");
                if (where instanceof Map) {
                    crud.put("where", where);
                }
                Object limit = params.get("limit");
                crud.put("limit", limit != null ? limit : 50);
            }
            case "update_row" -> {
                Object where = params.get("where");
                if (where instanceof Map) {
                    crud.put("where", where);
                }
                Object set = params.get("set");
                if (set instanceof Map) {
                    crud.put("set", set);
                }
            }
            case "delete_row" -> {
                Object where = params.get("where");
                if (where instanceof Map) {
                    crud.put("where", where);
                }
            }
            case "find_rows" -> {
                Object where = params.get("where");
                if (where instanceof Map) {
                    crud.put("where", where);
                }
                Object limit = params.get("limit");
                if (limit == null) limit = params.get("max_items");
                if (limit == null) limit = params.get("maxItems");
                crud.put("limit", limit != null ? limit : 100);
            }
        }

        stepParams.put("crud", crud);

        // Remove LLM-specific params
        stepParams.remove("table_id");
        // #TC3: strip the alias keys as well - execute() already normalized to
        // canonical `table_id`, but the LLM's raw aliases are still in the map
        // and would leak into the final step params (downstream expects only
        // `dataSourceId`, set above). Precedence matches extractTableId():
        // table_id > dataSourceId > datasource_id > tableId.
        stepParams.remove("datasource_id");
        stepParams.remove("tableId");
        stepParams.remove("columns");
        stepParams.remove("where");
        stepParams.remove("set");
        stepParams.remove("limit");
        stepParams.remove("max_items");
        stepParams.remove("maxItems");
        stepParams.remove("action");

        return stepParams;
    }
}
