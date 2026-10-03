package com.apimarketplace.datasource.crud.dto;

import com.apimarketplace.datasource.crud.domain.CrudResult;

import java.util.List;
import java.util.Map;

/**
 * Response DTO for CRUD operations.
 */
public record CrudResponse(
    String operation,
    boolean success,
    String message,
    ResultData data
) {
    /**
     * Data returned by the CRUD operation.
     */
    public record ResultData(
        // For read-row
        List<Map<String, Object>> rows,
        Integer rowCount,

        // For create-row
        List<Long> insertedIds,
        Integer insertedCount,

        // For update-row
        Integer affectedRows,

        // For delete-row
        Integer deletedRows,

        // For create-column
        List<String> createdColumns,

        // For read-row pagination
        Boolean hasMore,
        Integer offset,

        // Coercion warnings
        List<String> warnings,

        // LC-066/LC-011 re-audit item 2: "RESTRICTED" or "NORMAL", see CrudResult.ResultData.
        String dataSensitivity
    ) {
        /**
         * The shape before dataSensitivity existed. Kept so every caller/test fixture built
         * without it still compiles: adding a component to a record is otherwise a breaking
         * change to code that has nothing to do with restricted-data tagging.
         */
        public ResultData(List<Map<String, Object>> rows, Integer rowCount, List<Long> insertedIds,
                          Integer insertedCount, Integer affectedRows, Integer deletedRows,
                          List<String> createdColumns, Boolean hasMore, Integer offset,
                          List<String> warnings) {
            this(rows, rowCount, insertedIds, insertedCount, affectedRows, deletedRows,
                    createdColumns, hasMore, offset, warnings, null);
        }
    }

    /**
     * Create from domain CrudResult.
     */
    public static CrudResponse fromDomain(CrudResult result) {
        ResultData data = null;
        if (result.data() != null) {
            var domainData = result.data();
            data = new ResultData(
                domainData.rows(),
                domainData.rowCount(),
                domainData.insertedIds(),
                domainData.insertedCount(),
                domainData.affectedRows(),
                domainData.deletedRows(),
                domainData.createdColumns(),
                domainData.hasMore(),
                domainData.offset(),
                domainData.warnings(),
                domainData.dataSensitivity()
            );
        }
        return new CrudResponse(
            result.operation().getValue(),
            result.success(),
            result.message(),
            data
        );
    }

    /**
     * Create a success response.
     */
    public static CrudResponse success(String operation, String message, ResultData data) {
        return new CrudResponse(operation, true, message, data);
    }

    /**
     * Create an error response.
     */
    public static CrudResponse error(String operation, String message) {
        return new CrudResponse(operation, false, message, null);
    }
}
