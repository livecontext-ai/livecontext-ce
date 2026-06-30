package com.apimarketplace.datasource.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Shared enum describing how a column should be rendered in the DataTable UI.
 * 15 canonical types covering essentials, choices, metrics, media, contact, and vector.
 */
public enum ColumnType {
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

    ColumnType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ColumnType fromValue(String value) {
        if (value == null || value.isBlank()) {
            return TEXT;
        }
        String lower = value.toLowerCase().trim();
        for (ColumnType type : values()) {
            if (type.value.equals(lower)) {
                return type;
            }
        }
        return TEXT;
    }
}
