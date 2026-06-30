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
        List<String> warnings
    ) {}

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
                domainData.warnings()
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
