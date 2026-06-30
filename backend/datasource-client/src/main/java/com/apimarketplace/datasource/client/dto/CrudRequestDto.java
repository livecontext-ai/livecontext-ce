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
        SimilarityDto similarity
) {
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
