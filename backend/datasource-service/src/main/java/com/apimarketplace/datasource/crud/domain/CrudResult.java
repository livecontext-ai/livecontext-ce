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
        List<String> warnings,

        // LC-066/LC-011 re-audit item 2: "RESTRICTED" or "NORMAL" (DataSensitivity#name()),
        // null for operations that don't classify (create-column, delete-row). For read-row: the
        // OR of every returned row's own data_sensitivity and the request's restricted flag (the
        // interim guard - a read inside an already-restricted run/conversation is RESTRICTED even
        // when every individual row is NORMAL). For create-row/update-row: what was written.
        String dataSensitivity
    ) {
        /**
         * The shape before dataSensitivity existed. Kept so every caller that builds a result
         * without it - and every test fixture - still compiles: adding a component to a record is
         * otherwise a breaking change to code that has nothing to do with restricted-data tagging.
         */
        public ResultData(List<Map<String, Object>> rows, Integer rowCount, List<Long> insertedIds,
                          Integer insertedCount, Integer affectedRows, Integer deletedRows,
                          List<String> createdColumns, Boolean hasMore, Integer offset, List<String> warnings) {
            this(rows, rowCount, insertedIds, insertedCount, affectedRows, deletedRows, createdColumns,
                    hasMore, offset, warnings, null);
        }

        /**
         * Create a result for read-row operation with pagination and a sensitivity tag.
         */
        public static ResultData forRead(List<Map<String, Object>> rows, Boolean hasMore, Integer offset,
                                         String dataSensitivity) {
            return new ResultData(rows, rows != null ? rows.size() : 0, null, null, null, null, null, hasMore,
                    offset, null, dataSensitivity);
        }

        /**
         * Create a result for read-row operation with pagination. NORMAL - the shape before
         * dataSensitivity existed. Kept for callers/tests with no restricted-run context.
         */
        public static ResultData forRead(List<Map<String, Object>> rows, Boolean hasMore, Integer offset) {
            return forRead(rows, hasMore, offset, com.apimarketplace.common.classification.DataSensitivity.NORMAL.name());
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
