package com.apimarketplace.datasource.crud.domain;

import java.util.List;
import java.util.Map;

/**
 * Result of a CRUD operation.
 * Contains operation-specific data.
 */
public record CrudResult(
    CrudOperation operation,
    boolean success,
    String message,
    ResultData data
) {
    /**
     * Data returned by the CRUD operation.
     */
    public record ResultData(
        // For read-row: returned rows
        List<Map<String, Object>> rows,
        Integer rowCount,

        // For create-row: inserted IDs
        List<Long> insertedIds,
        Integer insertedCount,

        // For update-row: affected rows count
        Integer affectedRows,

        // For delete-row: deleted rows count
        Integer deletedRows,

        // For create-column: created column names
        List<String> createdColumns,

        // For read-row pagination
        Boolean hasMore,
        Integer offset,

        // Coercion warnings (nullable for backward compat)
        List<String> warnings
    ) {
        /**
         * Create a result for read-row operation with pagination.
         */
        public static ResultData forRead(List<Map<String, Object>> rows, Boolean hasMore, Integer offset) {
            return new ResultData(rows, rows != null ? rows.size() : 0, null, null, null, null, null, hasMore, offset, null);
        }

        /**
         * Create a result for create-row operation.
         */
        public static ResultData forCreate(List<Long> insertedIds) {
            return forCreate(insertedIds, null);
        }

        public static ResultData forCreate(List<Long> insertedIds, List<String> warnings) {
            return new ResultData(null, null, insertedIds, insertedIds != null ? insertedIds.size() : 0, null, null, null, null, null, warnings);
        }

        /**
         * Create a result for update-row operation.
         */
        public static ResultData forUpdate(int affectedRows) {
            return forUpdate(affectedRows, null);
        }

        public static ResultData forUpdate(int affectedRows, List<String> warnings) {
            return new ResultData(null, null, null, null, affectedRows, null, null, null, null, warnings);
        }

        /**
         * Create a result for delete-row operation.
         */
        public static ResultData forDelete(int deletedRows) {
            return new ResultData(null, null, null, null, null, deletedRows, null, null, null, null);
        }

        /**
         * Create a result for create-column operation.
         */
        public static ResultData forCreateColumn(List<String> createdColumns) {
            return new ResultData(null, null, null, null, null, null, createdColumns, null, null, null);
        }
    }

    /**
     * Create a successful result.
     */
    public static CrudResult success(CrudOperation operation, String message, ResultData data) {
        return new CrudResult(operation, true, message, data);
    }

    /**
     * Create a failed result.
     */
    public static CrudResult failure(CrudOperation operation, String message) {
        return new CrudResult(operation, false, message, null);
    }
}
