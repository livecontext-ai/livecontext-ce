package com.apimarketplace.orchestrator.services.impl;

import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.datasource.client.dto.CrudRequestDto;
import com.apimarketplace.datasource.client.dto.CrudResultDto;
import com.apimarketplace.orchestrator.config.OrchestratorLimitsConfig;
import com.apimarketplace.orchestrator.services.interfaces.ExecutionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Executes CRUD operations via DataSourceClient HTTP calls to datasource-service.
 * Extracted from CatalogToolsGateway for better separation of concerns.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CrudToolExecutor {

    private final DataSourceClient dataSourceClient;
    private final OrchestratorLimitsConfig renderLimits;

    /**
     * Execute CRUD operations locally.
     *
     * @param toolId Tool ID like "crud/delete-row", "crud/create-row", etc.
     * @param input Input data containing dataSourceId, crud config, and resolved values
     * @param tenantId Tenant ID for authorization
     * @return ExecutionResult with CRUD operation result
     */
    @SuppressWarnings("unchecked")
    public ExecutionResult execute(String toolId, Map<String, Object> input, String tenantId) {
        String operation = toolId.substring("crud/".length());
        log.info("[CRUD] Executing operation: {}, tenant: {}", operation, tenantId);
        log.debug("[CRUD] Input keys: {}", input != null ? input.keySet() : "null");

        try {
            Long dataSourceId = extractDataSourceId(input);
            if (dataSourceId == null) {
                log.error("[CRUD] dataSourceId is NULL! input.dataSourceId={}", input.get("dataSourceId"));
                return createErrorResult("dataSourceId is required for CRUD operations");
            }
            log.debug("[CRUD] dataSourceId: {}", dataSourceId);

            Map<String, Object> crudConfig = (Map<String, Object>) input.get("crud");
            if (crudConfig == null) {
                log.warn("[CRUD] crudConfig is NULL, using empty map");
                crudConfig = Map.of();
            }
            log.debug("[CRUD] crudConfig keys: {}", crudConfig.keySet());

            CrudRequestDto request = buildCrudRequestDto(operation, dataSourceId, crudConfig, tenantId);
            if (request == null) {
                return createErrorResult("Unknown CRUD operation: " + operation);
            }

            CrudResultDto result = dataSourceClient.executeCrud(request);
            return convertToExecutionResult(result, operation, crudConfig);

        } catch (IllegalArgumentException e) {
            log.warn("[CRUD] Validation error: {}", e.getMessage());
            return createErrorResult(e.getMessage());
        } catch (Exception e) {
            log.error("[CRUD] Execution error: {}", e.getMessage(), e);
            return createErrorResult("CRUD execution failed: " + e.getMessage());
        }
    }

    /**
     * Extract a float array from a query vector value.
     * Handles List&lt;Number&gt; (from JSON), float[], and double[].
     */
    @SuppressWarnings("unchecked")
    private float[] extractFloatArray(Object value) {
        if (value instanceof float[] fa) return fa;
        if (value instanceof double[] da) {
            float[] result = new float[da.length];
            for (int i = 0; i < da.length; i++) result[i] = (float) da[i];
            return result;
        }
        // Handle JSON string representation of array (e.g. "[-0.034, 0.012, ...]")
        // This happens when template resolution produces a stringified array
        if (value instanceof String str && str.startsWith("[")) {
            try {
                List<?> parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(str, List.class);
                return extractFloatArray(parsed);
            } catch (Exception e) {
                log.warn("[CRUD] Failed to parse queryVector JSON string: {}", e.getMessage());
                return null;
            }
        }
        if (value instanceof List<?> list && !list.isEmpty()) {
            float[] result = new float[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                if (item instanceof Number n) {
                    result[i] = n.floatValue();
                } else {
                    log.warn("[CRUD] Non-numeric value in queryVector at index {}: {}", i, item);
                    return null;
                }
            }
            return result;
        }
        log.warn("[CRUD] Cannot convert queryVector of type {}", value != null ? value.getClass().getSimpleName() : "null");
        return null;
    }

    /**
     * Extract dataSourceId from input parameters.
     */
    private Long extractDataSourceId(Map<String, Object> input) {
        Object dsId = input.get("dataSourceId");
        if (dsId == null) dsId = input.get("datasource_id");
        if (dsId == null) dsId = input.get("data_source_id");

        if (dsId instanceof Number) {
            return ((Number) dsId).longValue();
        } else if (dsId instanceof String) {
            try {
                return Long.parseLong((String) dsId);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Build a CrudRequestDto for the datasource-service internal API.
     */
    @SuppressWarnings("unchecked")
    private CrudRequestDto buildCrudRequestDto(String operation, Long dataSourceId,
                                                Map<String, Object> crudConfig, String tenantId) {
        String normalizedOp = switch (operation) {
            case "create-row", "create_row", "insert_row", "insert-row", "insert_rows" -> "create-row";
            case "read-row", "read_row", "read_rows", "find" -> "read-row";
            case "update-row", "update_row", "update_rows" -> "update-row";
            case "delete-row", "delete_row", "delete_rows" -> "delete-row";
            case "create-column" -> "create-column";
            default -> null;
        };
        if (normalizedOp == null) return null;

        // Extract where condition
        CrudRequestDto.WhereConditionDto where = null;
        Map<String, Object> whereData = (Map<String, Object>) crudConfig.get("where");
        if (whereData != null) {
            where = new CrudRequestDto.WhereConditionDto(
                    (String) whereData.get("column"),
                    (String) whereData.get("operator"),
                    whereData.get("value"));
        }

        // Extract rows for create-row
        List<Map<String, Object>> rows = (List<Map<String, Object>>) crudConfig.get("rows");

        // Extract columns for create-column
        List<CrudRequestDto.ColumnDefDto> columns = null;
        List<Map<String, Object>> columnsData = (List<Map<String, Object>>) crudConfig.get("columns");
        if (columnsData != null) {
            columns = columnsData.stream().map(colData -> new CrudRequestDto.ColumnDefDto(
                    (String) colData.get("name"),
                    (String) colData.get("type"),
                    colData.get("defaultValue") != null ? colData.get("defaultValue").toString() : null,
                    colData.get("display") instanceof Map<?, ?> dm ? (Map<String, Object>) dm : null
            )).toList();
        }

        // Extract set data for update-row (with filtering)
        Map<String, Object> setData = (Map<String, Object>) crudConfig.get("set");
        if (setData != null) {
            Map<String, Object> filtered = new HashMap<>();
            for (var entry : setData.entrySet()) {
                Object value = entry.getValue();
                if (value == null || (value instanceof String s && s.isEmpty())) continue;
                filtered.put(entry.getKey(), value);
            }
            setData = filtered.isEmpty() ? null : filtered;
        }

        // Extract limit/offset - enforce maxOutputRows as a ceiling to prevent
        // unbounded result sets from consuming heap (e.g., 540-row table reads).
        Integer limit = crudConfig.get("limit") instanceof Number n ? n.intValue() : null;
        int maxRows = renderLimits.getMaxOutputRows();
        if (limit == null || limit > maxRows) {
            limit = maxRows;
        }
        Integer offset = crudConfig.get("offset") instanceof Number n ? n.intValue() : null;

        // Extract similarity for vector search
        CrudRequestDto.SimilarityDto similarity = null;
        Map<String, Object> simData = (Map<String, Object>) crudConfig.get("similarity");
        if (simData != null) {
            String simColumn = (String) simData.get("column");
            float[] queryVector = extractFloatArray(simData.get("queryVector"));
            Integer topK = simData.get("topK") instanceof Number tk ? tk.intValue() : null;
            Float threshold = simData.get("threshold") instanceof Number th ? th.floatValue() : null;
            if (simColumn != null && queryVector != null) {
                similarity = new CrudRequestDto.SimilarityDto(simColumn, queryVector, topK, threshold);
                log.info("[CRUD] Similarity search: column={}, vectorDim={}, topK={}", simColumn, queryVector.length, topK);
            } else {
                log.warn("[CRUD] Similarity config present but missing column or queryVector");
            }
        }

        return new CrudRequestDto(normalizedOp, dataSourceId, null, tenantId,
                rows, columns, where, limit, offset, setData, similarity);
    }

    /**
     * Convert CrudResultDto to ExecutionResult.
     */
    private ExecutionResult convertToExecutionResult(CrudResultDto result, String operation, Map<String, Object> crudConfig) {
        Map<String, Object> output = new HashMap<>();
        output.put("operation", operation);
        output.put("success", result.success());
        output.put("message", result.message());

        if (result.data() != null) {
            CrudResultDto.ResultData data = result.data();

            if (data.insertedIds() != null) {
                String rowId = !data.insertedIds().isEmpty()
                    ? String.valueOf(data.insertedIds().get(0))
                    : null;
                output.put("row_id", rowId);
                output.put("created_at", java.time.Instant.now().toString());
                output.put("inserted_count", data.insertedCount());
                output.put("inserted_values", extractInsertedValues(crudConfig));
            }

            if (data.rows() != null) {
                List<?> rows = data.rows();
                int maxRows = renderLimits.getMaxOutputRows();
                if (rows.size() > maxRows) {
                    output.put("rows", new ArrayList<>(rows.subList(0, maxRows)));
                    output.put("rows_truncated", true);
                    output.put("rows_total", rows.size());
                    log.info("[CRUD] Output rows truncated: {} → {} (maxOutputRows)", rows.size(), maxRows);
                } else {
                    output.put("rows", rows);
                }
                output.put("row_count", data.rowCount());
                output.put("rowCount", data.rowCount());
            }
            if (data.hasMore() != null) {
                output.put("has_more", data.hasMore());
            }
            if (data.offset() != null) {
                output.put("offset", data.offset());
            }
            if (data.affectedRows() != null) {
                output.put("updated_count", data.affectedRows());
                output.put("rows_affected", data.affectedRows());
                output.put("updated_at", java.time.Instant.now().toString());
            }
            if (data.deletedRows() != null) {
                output.put("deleted_count", data.deletedRows());
                output.put("rows_affected", data.deletedRows());
                output.put("deleted_at", java.time.Instant.now().toString());
            }
            if (data.createdColumns() != null) {
                output.put("createdColumns", data.createdColumns());
            }
        }

        if (result.success()) {
            log.info("[CRUD] Operation {} completed successfully: {}", operation, result.message());
            return new ExecutionResult(true, output, List.of(), List.of());
        } else {
            log.warn("[CRUD] Operation {} failed: {}", operation, result.message());
            output.put("error", result.message());
            return new ExecutionResult(
                false,
                output,
                List.of(Map.of("type", "crud_error", "message", result.message())),
                List.of()
            );
        }
    }

    /**
     * Extract inserted column values from the original crudConfig.
     * The crudConfig.rows[0].columns contains the values that were inserted.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractInsertedValues(Map<String, Object> crudConfig) {
        if (crudConfig == null) return Map.of();
        Object rowsObj = crudConfig.get("rows");
        if (rowsObj instanceof List<?> rowsList && !rowsList.isEmpty()) {
            Object firstRow = rowsList.get(0);
            if (firstRow instanceof Map<?, ?> rowMap) {
                Object columns = rowMap.get("columns");
                if (columns instanceof Map<?, ?>) {
                    return (Map<String, Object>) columns;
                }
            }
        }
        return Map.of();
    }

    /**
     * Create an error ExecutionResult with detailed output for frontend display.
     */
    private ExecutionResult createErrorResult(String errorMessage) {
        Map<String, Object> output = new HashMap<>();
        output.put("error", errorMessage);
        output.put("success", false);
        output.put("message", errorMessage);
        return new ExecutionResult(
            false,
            output,
            List.of(Map.of("type", "crud_error", "message", errorMessage)),
            List.of()
        );
    }
}
