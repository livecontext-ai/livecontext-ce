package com.apimarketplace.datasource.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * DTO for CRUD operation requests sent from orchestrator to datasource-service.
 */
public record CrudRequestDto(
        String operation,
        @JsonProperty("data_source_id") Long dataSourceId,
        @JsonProperty("step_label") String stepLabel,
        @JsonProperty("tenant_id") String tenantId,
        // For create-row
        List<Map<String, Object>> rows,
        // For create-column
        List<ColumnDefDto> columns,
        // For read-row
        WhereConditionDto where,
        Integer limit,
        Integer offset,
        // For update-row
        Map<String, Object> set,
        // For similarity search (vector)
        SimilarityDto similarity,
        // LC-066/LC-011 re-audit item 2: true when the calling orchestrator node is executing
        // inside a run/conversation already tagged restricted (see CrudToolExecutor). Mirrors
        // datasource-service's CrudRequest.restricted over the wire (default false).
        @JsonProperty("restricted") Boolean restricted
) {
    /**
     * The shape before {@code restricted} existed (default: not restricted). Kept so every
     * caller/test fixture built without it still compiles.
     */
    public CrudRequestDto(String operation, Long dataSourceId, String stepLabel, String tenantId,
                          List<Map<String, Object>> rows, List<ColumnDefDto> columns,
                          WhereConditionDto where, Integer limit, Integer offset,
                          Map<String, Object> set, SimilarityDto similarity) {
        this(operation, dataSourceId, stepLabel, tenantId, rows, columns, where, limit, offset,
                set, similarity, null);
    }

    /** {@code true} iff explicitly set - null/false both read as NORMAL. */
    public boolean isRestricted() {
        return Boolean.TRUE.equals(restricted);
    }

    public record ColumnDefDto(
            String name,
            String type,
            @JsonProperty("default_value") String defaultValue,
            Map<String, Object> display
    ) {}

    public record WhereConditionDto(
            String column,
            String operator,
            Object value
    ) {}

    public record SimilarityDto(
            String column,
            @JsonProperty("queryVector") float[] queryVector,
            Integer topK,
            Float threshold
    ) {}
}
