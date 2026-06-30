package com.apimarketplace.datasource.client.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Column rendering type enum for inter-service communication.
 * Mirrors datasource-service's ColumnType with 15 canonical types.
 */
public enum ColumnTypeDto {
    TEXT("text"),
    NUMBER("number"),
    DATE("date"),
    CHECKBOX("checkbox"),
    SELECT("select"),
    MULTI_SELECT("multi_select"),
    RATING("rating"),
    SENTIMENT("sentiment"),
    PROGRESS("progress"),
    FILE("file"),
    IMAGE("image"),
    EMAIL("email"),
    PHONE("phone"),
    URL("url"),
    VECTOR("vector");

    private final String value;

    ColumnTypeDto(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ColumnTypeDto fromValue(String value) {
        if (value == null || value.isBlank()) return TEXT;
        String lower = value.toLowerCase().trim();
        for (ColumnTypeDto type : values()) {
            if (type.value.equals(lower)) return type;
        }
        return TEXT;
    }
}
