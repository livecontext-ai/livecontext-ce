package com.apimarketplace.datasource.tools.datasource;

import com.apimarketplace.agent.config.ToolAccessControl;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.common.ToolModule;
import com.apimarketplace.datasource.crud.domain.CrudResult;
import com.apimarketplace.datasource.crud.dto.CreateRowRequest;
import com.apimarketplace.datasource.crud.dto.DeleteRowRequest;
import com.apimarketplace.datasource.crud.dto.ReadRowRequest;
import com.apimarketplace.datasource.crud.dto.UpdateRowRequest;
import com.apimarketplace.datasource.crud.dto.SimilarityQueryDto;
import com.apimarketplace.datasource.crud.dto.WhereConditionDto;
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
 * Module handling row operations for DataSource tools.
 * Datasource-service native version - uses CrudExecutorService directly (no HTTP hop).
 * Operations: query_rows, insert_rows, update_rows, delete_rows
 */
@Component
public class DataSourceRowModule implements ToolModule {

    private static final Logger log = LoggerFactory.getLogger(DataSourceRowModule.class);

    private final CrudExecutorService crudExecutorService;
    private final DataSourceService dataSourceService;
    private final ObjectMapper objectMapper;

    public DataSourceRowModule(CrudExecutorService crudExecutorService,
                               DataSourceService dataSourceService,
                               ObjectMapper objectMapper) {
        this.crudExecutorService = crudExecutorService;
        this.dataSourceService = dataSourceService;
        this.objectMapper = objectMapper;
    }

    private static final Set<String> HANDLED_ACTIONS = Set.of(
        "query_rows", "insert_rows", "update_rows", "delete_rows"
    );

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

        // Allow-list check: a tables=custom agent may only touch its approved table ids. Row ops
        // (query/insert/update/delete) all key off the same table_id, so enforce it once here -
        // the table CRUD module already gates get/update/delete this way; row data must not bypass it.
        var notAllowed = TableToolAccess.denyIfNotAllowed(context, getTableId(parameters));
        if (notAllowed.isPresent()) return notAllowed;

        // Thread the caller's org workspace through to the CRUD executor so the
        // strict-scope check in verifyDataSourceAccess matches the org id that
        // executeCreate stored on the datasource. Without this, org-workspace
        // users get a spurious "DataSource not found" on every row operation
        // (create stores organizationId=context.orgId(), row ops passed null).
        String orgId = context != null ? context.orgId() : null;
        return Optional.of(switch (action) {
            case "query_rows" -> executeQueryRows(parameters, tenantId, orgId);
            case "insert_rows" -> executeInsertRows(parameters, tenantId, orgId);
            case "update_rows" -> executeUpdateRows(parameters, tenantId, orgId);
            case "delete_rows" -> executeDeleteRows(parameters, tenantId, orgId);
            default -> ToolExecutionResult.failure(ToolErrorCode.TOOL_NOT_FOUND, "Unknown action: " + action);
        });
    }

    private ToolExecutionResult executeQueryRows(Map<String, Object> parameters, String tenantId, String orgId) {
        Long datasourceId = getTableId(parameters);
        if (datasourceId == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, MISSING_TABLE_ID_HINT);
        }

        int limit = getIntParam(parameters, "limit", 20);
        if (limit < 0) {
            return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR,
                "limit must be >= 0. Got: " + limit + ". Use 0 for no rows or a positive integer.");
        }

        try {
            ReadRowRequest request = new ReadRowRequest();
            request.setDataSourceId(datasourceId);
            request.setLimit(limit);

            @SuppressWarnings("unchecked")
            Map<String, Object> whereMap = (Map<String, Object>) parameters.get("where");
            if (whereMap != null) {
                request.setWhere(new WhereConditionDto(
                    sanitizeColumnName((String) whereMap.get("column")),
                    (String) whereMap.get("operator"),
                    whereMap.get("value")
                ));
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> simMap = (Map<String, Object>) parameters.get("similarity");
            if (simMap != null) {
                String simColumn = (String) simMap.get("column");
                if (simColumn == null || simColumn.isBlank()) {
                    return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                        "similarity.column is required. Provide the name of the vector column to search. "
                            + "Example: similarity={column: 'embedding', queryVector: [...], topK: 5}");
                }
                Object qv = simMap.get("queryVector");
                if (!(qv instanceof List<?> list) || list.isEmpty()) {
                    return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                        "similarity.queryVector is required and must be a non-empty array of numbers. "
                            + "Example: similarity={column: 'embedding', queryVector: [0.1, 0.2, ...], topK: 5}");
                }
                SimilarityQueryDto sim = new SimilarityQueryDto();
                sim.setColumn(sanitizeColumnName(simColumn));
                float[] vec = new float[list.size()];
                for (int i = 0; i < list.size(); i++) {
                    Object v = list.get(i);
                    if (!(v instanceof Number n)) {
                        return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR,
                            "similarity.queryVector must contain only numbers. Got non-numeric value at index " + i);
                    }
                    vec[i] = n.floatValue();
                }
                sim.setQueryVector(vec);
                if (simMap.get("topK") instanceof Number n) sim.setTopK(n.intValue());
                if (simMap.get("threshold") instanceof Number n) sim.setThreshold(n.floatValue());
                request.setSimilarity(sim);
            }

            CrudResult result = crudExecutorService.execute(request, tenantId, orgId);

            if (!result.success()) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, result.message());
            }

            List<Map<String, Object>> rows = result.data() != null && result.data().rows() != null
                ? result.data().rows()
                : List.of();

            // Truncate vector columns for agent consumption - raw embeddings waste LLM tokens
            List<Map<String, Object>> agentRows = truncateVectorColumns(rows, datasourceId);

            return ToolExecutionResult.success(Map.of(
                "datasourceId", datasourceId,
                "rowCount", agentRows.size(),
                "rows", agentRows,
                "status", "OK",
                "message", "Found " + agentRows.size() + " rows in datasource " + datasourceId + "."
            ));
        } catch (Exception e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to query rows: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private ToolExecutionResult executeInsertRows(Map<String, Object> parameters, String tenantId, String orgId) {
        Long datasourceId = getTableId(parameters);
        if (datasourceId == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, MISSING_TABLE_ID_HINT);
        }

        Object rowsObj = parameters.get("rows");
        if (rowsObj == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "rows is required");
        }

        try {
            List<Map<String, Object>> rowsData = parseRowsArray(rowsObj, objectMapper);
            if (rowsData.isEmpty()) {
                return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR, "rows must contain at least one row");
            }

            // Build rows in the format expected by CreateRowRequest.
            // Reject reserved-name keys up-front: otherwise they end up as
            // {"data.id": ...} JSONB keys and are silently shadowed on read.
            List<CreateRowRequest.RowData> crudRows = new ArrayList<>();
            for (Map<String, Object> rowMap : rowsData) {
                Map<String, Object> columns = rowMap.containsKey("columns")
                    ? (Map<String, Object>) rowMap.get("columns")
                    : rowMap;
                Map<String, Object> sanitized = sanitizeKeys(columns);
                String reservedErr = validatePayloadKeys(sanitized);
                if (reservedErr != null) {
                    return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR, reservedErr);
                }
                crudRows.add(new CreateRowRequest.RowData(null, sanitized));
            }

            CreateRowRequest request = new CreateRowRequest();
            request.setDataSourceId(datasourceId);
            request.setRows(crudRows);

            CrudResult result = crudExecutorService.execute(request, tenantId, orgId);

            if (!result.success()) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, result.message());
            }

            Map<String, Object> resultMap = new LinkedHashMap<>();
            resultMap.put("datasourceId", datasourceId);
            resultMap.put("rowsInserted", crudRows.size());
            resultMap.put("insertedIds", result.data() != null && result.data().insertedIds() != null
                ? result.data().insertedIds() : List.of());
            resultMap.put("status", "CREATED");
            resultMap.put("message", "Successfully inserted " + crudRows.size() + " rows into table " + datasourceId + ".");
            resultMap.put("marker", "[visualize:datasource:" + datasourceId + "]");

            return ToolExecutionResult.success(resultMap, buildVizMetadata(datasourceId));
        } catch (Exception e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to insert rows: " + e.getMessage());
        }
    }

    private ToolExecutionResult executeUpdateRows(Map<String, Object> parameters, String tenantId, String orgId) {
        Long datasourceId = getTableId(parameters);
        if (datasourceId == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, MISSING_TABLE_ID_HINT);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> whereMap = (Map<String, Object>) parameters.get("where");
        if (whereMap == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, MISSING_WHERE_HINT_UPDATE);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> setMap = (Map<String, Object>) parameters.get("set");
        if (setMap == null || setMap.isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, MISSING_SET_HINT);
        }

        try {
            Map<String, Object> sanitizedSet = sanitizeKeys(setMap);
            String reservedErr = validatePayloadKeys(sanitizedSet);
            if (reservedErr != null) {
                return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR, reservedErr);
            }

            UpdateRowRequest request = new UpdateRowRequest();
            request.setDataSourceId(datasourceId);
            request.setWhere(new WhereConditionDto(
                sanitizeColumnName((String) whereMap.get("column")),
                (String) whereMap.get("operator"),
                whereMap.get("value")
            ));
            request.setSet(sanitizedSet);

            CrudResult result = crudExecutorService.execute(request, tenantId, orgId);

            if (!result.success()) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, result.message());
            }

            int affectedRows = result.data() != null && result.data().affectedRows() != null
                ? result.data().affectedRows() : 0;

            return ToolExecutionResult.success(Map.of(
                "datasourceId", datasourceId,
                "updatedColumns", sanitizedSet.keySet(),
                "affectedRows", affectedRows,
                "status", "UPDATED",
                "message", "Successfully updated " + affectedRows + " rows in table " + datasourceId + ".",
                "marker", "[visualize:datasource:" + datasourceId + "]"
            ), buildVizMetadata(datasourceId));
        } catch (Exception e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to update rows: " + e.getMessage());
        }
    }

    private ToolExecutionResult executeDeleteRows(Map<String, Object> parameters, String tenantId, String orgId) {
        Long datasourceId = getTableId(parameters);
        if (datasourceId == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, MISSING_TABLE_ID_HINT);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> whereMap = (Map<String, Object>) parameters.get("where");
        if (whereMap == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, MISSING_WHERE_HINT_DELETE);
        }

        try {
            DeleteRowRequest request = new DeleteRowRequest();
            request.setDataSourceId(datasourceId);
            request.setWhere(new WhereConditionDto(
                sanitizeColumnName((String) whereMap.get("column")),
                (String) whereMap.get("operator"),
                whereMap.get("value")
            ));

            CrudResult result = crudExecutorService.execute(request, tenantId, orgId);

            if (!result.success()) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, result.message());
            }

            int deletedRows = result.data() != null && result.data().deletedRows() != null
                ? result.data().deletedRows() : 0;

            return ToolExecutionResult.success(Map.of(
                "datasourceId", datasourceId,
                "deletedRows", deletedRows,
                "status", "DELETED",
                "message", "Successfully deleted " + deletedRows + " rows from table " + datasourceId + ".",
                "marker", "[visualize:datasource:" + datasourceId + "]"
            ), buildVizMetadata(datasourceId));
        } catch (Exception e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to delete rows: " + e.getMessage());
        }
    }

    // ==================== Helper Methods ====================

    private Map<String, Object> buildVizMetadata(Long datasourceId) {
        String title = dataSourceService.getDataSource(datasourceId).map(DataSource::name).orElse(null);
        return tableVisualizationMetadata(datasourceId, title);
    }

    /**
     * Reject row payload keys that collide with physical columns on
     * {@code data_source_items}. Without this, agents can write e.g. {@code id: 999}
     * which lands as {@code data.id} in JSONB and is silently shadowed on read.
     */
    private static String validatePayloadKeys(Map<String, Object> payload) {
        if (payload == null) return null;
        for (String key : payload.keySet()) {
            String err = validateReservedColumnName(key);
            if (err != null) return err;
        }
        return null;
    }

    /**
     * Strip "data." prefix from every key in the supplied map. Used for row keys on
     * insert/update - the {@code data.} prefix is an internal storage detail that
     * leaks through when agents copy-paste from query output.
     */
    private static Map<String, Object> sanitizeKeys(Map<String, Object> input) {
        if (input == null || input.isEmpty()) return input;
        Map<String, Object> cleaned = new LinkedHashMap<>(input.size());
        for (Map.Entry<String, Object> e : input.entrySet()) {
            cleaned.put(sanitizeColumnName(e.getKey()), e.getValue());
        }
        return cleaned;
    }

    /**
     * Truncate vector columns in query results for agent consumption.
     * Raw embedding arrays (e.g. 1536 floats) waste LLM tokens and provide no value to the agent.
     * Replaces vector values with a summary like "[vector dim=1536]".
     * Frontend uses different API paths and is NOT affected.
     *
     * <p>Column types live in {@code mappingSpec} (keyed by column name). {@code columnOrder}
     * entries only carry {@code field}/{@code order} and have no type info, so don't read from it.
     */
    private List<Map<String, Object>> truncateVectorColumns(List<Map<String, Object>> rows, Long datasourceId) {
        if (rows.isEmpty()) return rows;

        Set<String> vectorColumns = new HashSet<>();
        try {
            Optional<DataSource> dsOpt = dataSourceService.getDataSource(datasourceId);
            if (dsOpt.isPresent() && dsOpt.get().mappingSpec() != null) {
                for (Map.Entry<String, com.apimarketplace.datasource.domain.DataSourceModels.ColumnMappingSpec> e
                        : dsOpt.get().mappingSpec().entrySet()) {
                    if (e.getValue() != null
                            && e.getValue().type() == com.apimarketplace.datasource.domain.ColumnType.VECTOR) {
                        vectorColumns.add(e.getKey());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not load column schema for vector truncation: {}", e.getMessage());
            return rows;
        }

        if (vectorColumns.isEmpty()) return rows;

        List<Map<String, Object>> truncated = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> newRow = new LinkedHashMap<>(row);
            for (String vecCol : vectorColumns) {
                Object val = newRow.get(vecCol);
                if (val instanceof List<?> list) {
                    newRow.put(vecCol, "[vector dim=" + list.size() + "]");
                } else if (val instanceof float[] fa) {
                    newRow.put(vecCol, "[vector dim=" + fa.length + "]");
                } else if (val instanceof double[] da) {
                    newRow.put(vecCol, "[vector dim=" + da.length + "]");
                } else if (val instanceof String s && s.startsWith("[") && s.length() > 100) {
                    // Stringified JSON array - estimate dimension from comma count
                    int dim = 1 + (int) s.chars().filter(c -> c == ',').count();
                    newRow.put(vecCol, "[vector dim~" + dim + "]");
                }
            }
            truncated.add(newRow);
        }
        return truncated;
    }
}
