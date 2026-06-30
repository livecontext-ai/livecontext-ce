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
            List<String> createdColumns
    ) {}
}
