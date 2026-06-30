package com.apimarketplace.datasource.client.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Column JSON structure type enum for inter-service communication.
 */
public enum ColumnStructureDto {
    SCALAR("scalar"),
    OBJECT("object"),
    ARRAY("array");

    private final String value;

    ColumnStructureDto(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ColumnStructureDto fromValue(String value) {
        if (value == null || value.isBlank()) return SCALAR;
        for (ColumnStructureDto s : values()) {
            if (s.value.equalsIgnoreCase(value)) return s;
        }
        throw new IllegalArgumentException("Invalid column structure: " + value);
    }
}
