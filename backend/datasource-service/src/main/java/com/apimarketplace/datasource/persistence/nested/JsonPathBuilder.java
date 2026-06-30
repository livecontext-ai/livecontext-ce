package com.apimarketplace.datasource.persistence.nested;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds PostgreSQL JSONB paths from dot-separated path strings.
 *
 * Extracted from DataSourceNestedRepositories for Single Responsibility Principle.
 *
 * @see DataSourceNestedRepositories
 */
@Component
public class JsonPathBuilder {

    private static final int MAX_SEGMENT_LENGTH = 256;

    /**
     * Build PostgreSQL JSONB path extraction from a dot-separated path.
     * Example: "metadata.user.profile" -> "jsonb_extract_path(data, ?, ?, ?)"
     *
     * @param jsonPath Dot-separated path (e.g., "metadata.user.profile")
     * @param params Output list for query parameters
     * @return PostgreSQL JSONB path expression
     */
    public String buildJsonbPath(String jsonPath, List<Object> params) {
        return buildJsonbPath("data", jsonPath, params);
    }

    /**
     * Build PostgreSQL JSONB path extraction from a custom JSONB root expression.
     * Values are always parameter-bound; only the placeholder count is dynamic.
     *
     * @param rootExpression SQL expression returning jsonb
     * @param jsonPath Dot-separated path
     * @param params Output list for query parameters
     * @return PostgreSQL JSONB path expression
     */
    public String buildJsonbPath(String rootExpression, String jsonPath, List<Object> params) {
        requireParams(params);

        List<String> segments = parsePathSegments(jsonPath);
        if (segments.isEmpty()) {
            return rootExpression;
        }

        params.addAll(segments);
        return "jsonb_extract_path(" + rootExpression + ", " + placeholders(segments.size()) + ")";
    }

    /**
     * Build PostgreSQL JSONB text extraction for a direct child field.
     * Example: "name" -> "jsonb_extract_path_text(data, ?)"
     *
     * @param rootExpression SQL expression returning jsonb
     * @param fieldName JSON object field name
     * @param params Output list for query parameters
     * @return PostgreSQL JSONB text extraction path
     */
    public String buildJsonbTextPath(String rootExpression, String fieldName, List<Object> params) {
        requireParams(params);
        params.add(normalizeJsonFieldName(fieldName));
        return "jsonb_extract_path_text(" + rootExpression + ", ?)";
    }

    public List<String> parsePathSegments(String jsonPath) {
        if (jsonPath == null || jsonPath.trim().isEmpty()) {
            return List.of();
        }

        String normalized = jsonPath.trim().replaceAll("\\s+", "");
        if (normalized.startsWith(".") || normalized.endsWith(".") || normalized.contains("..")
                || normalized.contains("/") || normalized.contains("\\")) {
            throw new IllegalArgumentException("Invalid JSON path format: " + jsonPath);
        }

        String[] parts = normalized.split("\\.", -1);
        List<String> segments = new ArrayList<>(parts.length);
        for (String part : parts) {
            validateJsonPathSegment(part, jsonPath);
            segments.add(part);
        }
        return segments;
    }

    public String normalizeJsonFieldName(String fieldName) {
        if (fieldName == null || fieldName.trim().isEmpty()) {
            throw new IllegalArgumentException("JSON field name cannot be blank");
        }

        String normalized = fieldName.trim();
        if (normalized.length() > MAX_SEGMENT_LENGTH || containsControlCharacter(normalized)) {
            throw new IllegalArgumentException("Invalid JSON field name: " + fieldName);
        }
        return normalized;
    }

    private void validateJsonPathSegment(String segment, String originalPath) {
        if (segment == null || segment.isEmpty()
                || segment.length() > MAX_SEGMENT_LENGTH
                || containsControlCharacter(segment)) {
            throw new IllegalArgumentException("Invalid JSON path format: " + originalPath);
        }
    }

    private boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private void requireParams(List<Object> params) {
        if (params == null) {
            throw new IllegalArgumentException("params cannot be null");
        }
    }
}
