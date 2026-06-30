package com.apimarketplace.catalog.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Parses the {@code api_tool_parameters.allowed_values} column (a JSON-serialized
 * array stored as TEXT) into a typed {@code List<String>} for DTOs.
 *
 * <p>Centralized so the workflow inspector path, the catalog "get" path, and the
 * orchestrator-facing tool info path all return the same shape and treat malformed
 * input the same way (downgrade to {@code null}, never throw).
 *
 * <p>Returns {@code null} on null/blank/empty-array/malformed input. {@code null}
 * is the source-of-truth signal "no enum declared" - empty array carries no
 * information and is treated identically.
 */
@Slf4j
public final class AllowedValuesParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

    private AllowedValuesParser() {}

    /**
     * Parses an arbitrary JDBC-returned cell ({@code String}, {@code PGobject},
     * etc.) into a typed list. Accepts anything whose {@code toString()} yields
     * a JSON array.
     */
    public static List<String> parse(Object raw) {
        if (raw == null) {
            return null;
        }
        return parseString(raw.toString());
    }

    /**
     * Parses a JSON-array TEXT column value directly. Equivalent to {@link #parse(Object)}
     * when the caller already has a {@code String} (e.g. {@code rs.getString(...)}).
     */
    public static List<String> parseString(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            List<String> values = MAPPER.readValue(trimmed, STRING_LIST_TYPE);
            return (values == null || values.isEmpty()) ? null : values;
        } catch (Exception e) {
            log.debug("Could not parse allowed_values '{}' as JSON array: {}", trimmed, e.getMessage());
            return null;
        }
    }
}
