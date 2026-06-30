package com.apimarketplace.datasource.crud.service;

import java.util.List;

/**
 * Result of a column value coercion attempt.
 * Carries the coerced value plus any warnings (e.g. clamped, stripped, unparseable).
 */
public record CoercionResult(Object value, List<String> warnings) {

    public static CoercionResult ok(Object value) {
        return new CoercionResult(value, List.of());
    }

    public static CoercionResult withWarning(Object value, String warning) {
        return new CoercionResult(value, List.of(warning));
    }

    public static CoercionResult failed(String warning) {
        return new CoercionResult(null, List.of(warning));
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
}
