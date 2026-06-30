package com.apimarketplace.datasource.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Column mapping specification DTO for inter-service communication.
 */
public record ColumnMappingSpecDto(
        @JsonProperty("path") String path,
        @JsonProperty("type") ColumnTypeDto type,
        @JsonProperty("structure") ColumnStructureDto structure,
        @JsonProperty("children") Map<String, ColumnMappingSpecDto> children,
        @JsonProperty("display") Map<String, Object> display
) {
    public ColumnMappingSpecDto {
        path = path == null ? "" : path;
        type = type == null ? ColumnTypeDto.TEXT : type;
        structure = structure == null ? ColumnStructureDto.SCALAR : structure;
        children = children == null ? Map.of() : Map.copyOf(children);
        display = display == null ? Map.of() : Map.copyOf(display);
    }
}
