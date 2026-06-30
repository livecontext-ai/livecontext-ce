package com.apimarketplace.orchestrator.stepdata;

import com.apimarketplace.orchestrator.domain.execution.NodeType;

import java.util.List;
import java.util.Map;

/**
 * Response DTO for detailed step data with node-specific columns.
 * The columns are ordered and typed by the backend based on nodeType.
 */
public record DetailedStepDataResponse(
        NodeType nodeType,
        String stepAlias,
        String toolId,
        List<ColumnDefinition> columns,
        List<Map<String, Object>> rows,
        PaginationInfo pagination
) {

    public record PaginationInfo(
            int page,
            int pageSize,
            long totalRows,
            boolean hasMore
    ) {
        public static PaginationInfo of(int page, int pageSize, long totalRows) {
            return new PaginationInfo(page, pageSize, totalRows, (long) page * pageSize < totalRows);
        }
    }
}
