package com.apimarketplace.datasource.client.dto;

import java.util.List;
import java.util.Map;

/**
 * DTO for CRUD operation results returned from datasource-service.
 * Field names match CrudResponse (server) which uses default camelCase serialization.
 */
public record CrudResultDto(
        String operation,
        boolean success,
        String message,
        ResultData data
) {
    public record ResultData(
            List<Map<String, Object>> rows,
            Integer rowCount,
            Boolean hasMore,
            Integer offset,
            List<Long> insertedIds,
            Integer insertedCount,
            Integer affectedRows,
            Integer deletedRows,
            List<String> createdColumns,
            // What the column types reported about the values written. The server has always sent
            // this (CrudResponse.ResultData); without the component here Jackson dropped it on the
            // way in, so a workflow's table step could write a value the platform had diagnosed as
            // unusable and report nothing at all.
            List<String> warnings,
            // LC-066/LC-011 re-audit item 2: "RESTRICTED" or "NORMAL" - for read-row, the OR of
            // every returned row's own stored tag and the request's restricted flag (see
            // datasource-service's CrudExecutorService.readResultSensitivity). Orchestrator's
            // CrudToolExecutor stamps this onto the tool-result metadata so a disallowed LLM
            // processor never receives a row echoing Gmail/Drive content.
            String dataSensitivity
    ) {
        /**
         * The shape before warnings existed. Kept so every caller that builds a result without them
         * - and every test fixture - still compiles: adding a component to a record is otherwise a
         * breaking change to code that has nothing to do with warnings.
         */
        public ResultData(List<Map<String, Object>> rows, Integer rowCount, Boolean hasMore,
                          Integer offset, List<Long> insertedIds, Integer insertedCount,
                          Integer affectedRows, Integer deletedRows, List<String> createdColumns) {
            this(rows, rowCount, hasMore, offset, insertedIds, insertedCount, affectedRows,
                    deletedRows, createdColumns, null, null);
        }

        /** The shape before dataSensitivity existed. Kept for the same reason as the ctor above. */
        public ResultData(List<Map<String, Object>> rows, Integer rowCount, Boolean hasMore,
                          Integer offset, List<Long> insertedIds, Integer insertedCount,
                          Integer affectedRows, Integer deletedRows, List<String> createdColumns,
                          List<String> warnings) {
            this(rows, rowCount, hasMore, offset, insertedIds, insertedCount, affectedRows,
                    deletedRows, createdColumns, warnings, null);
        }
    }
}
