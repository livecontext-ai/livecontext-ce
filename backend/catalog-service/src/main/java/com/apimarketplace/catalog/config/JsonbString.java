package com.apimarketplace.catalog.config;

/**
 * Marker wrapper around a raw JSON string destined for a Postgres {@code jsonb} column.
 *
 * <p>Spring Data JDBC has no native jsonb support: a plain {@code String} field annotated
 * with {@code @Column} on a jsonb column produces an INSERT with a {@code character varying}
 * parameter and Postgres rejects it with {@code 42804: column "x" is of type jsonb but
 * expression is of type character varying}. The fix is to register a type-targeted
 * {@code WritingConverter} from this wrapper to {@link org.postgresql.util.PGobject} and
 * a corresponding {@code ReadingConverter} for the inverse direction (see
 * {@link DataConfig}). Entity fields keep a String public API via wrapping getters/setters.
 */
public record JsonbString(String value) {
    public static JsonbString of(String value) {
        return value == null ? null : new JsonbString(value);
    }
}
