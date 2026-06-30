package com.apimarketplace.datasource.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Describes the JSON structure of a column's value.
 */
public enum ColumnStructure {
    SCALAR("scalar"),
    OBJECT("object"),
    ARRAY("array");

    private final String value;

    ColumnStructure(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ColumnStructure fromValue(String value) {
        if (value == null || value.isBlank()) {
            return SCALAR;
        }
        for (ColumnStructure structure : values()) {
            if (structure.value.equalsIgnoreCase(value)) {
                return structure;
            }
        }
        throw new IllegalArgumentException("Invalid column structure: " + value);
    }
}
