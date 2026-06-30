package com.apimarketplace.orchestrator.services.expression;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared Jackson helpers used by SpEL substitution paths.
 *
 * <p>Centralises the {@link ObjectMapper} and the {@link StreamReadConstraints} hard caps
 * (256KB single string, 64 nesting depth, 2MB document) so every call site (substitution in
 * {@code TemplateEngine.resolveExpressions}, {@code resolveExpressionsWithMap},
 * {@code resolveTemplatesSimple}, and the {@code json()/fromjson()/tojson()} SpEL functions)
 * uses identical serialization semantics.
 *
 * <p>Pre-existing inconsistency repaid: {@code TemplateEngine.formatValueForDisplay} and
 * {@code resolveTemplatesSimple} already serialised Map/List as JSON; the substitution paths
 * used Java {@code String.valueOf}, producing {@code {a=1}} instead of {@code {"a":1}}.
 * This class is the single source of truth for both directions.
 */
public final class JsonOutputUtil {

    private static final Logger logger = LoggerFactory.getLogger(JsonOutputUtil.class);

    private static final ObjectMapper MAPPER = JsonMapper.builder(
        JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                .maxStringLength(256 * 1024)
                .maxNestingDepth(64)
                .maxDocumentLength(2L * 1024 * 1024)
                .maxNumberLength(1000)
                .build())
            .build()
    ).build();

    private JsonOutputUtil() {
        // utility
    }

    /**
     * Shared {@link ObjectMapper} configured with hard caps. Thread-safe per Jackson contract.
     */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * Encode a value to its JSON string representation.
     *
     * <p>Used by template substitution paths to embed Map/List/scalar values into a surrounding
     * template context as valid JSON. On serialization failure, falls back to {@code String.valueOf}
     * - same defensive contract as {@code formatValueForDisplay}: never throws into the template
     * pipeline because that would break unrelated workflows.
     */
    public static String encode(Object value) {
        if (value == null) return "null";
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            logger.warn("JSON encode failed for value of type {}: {} - falling back to String.valueOf",
                value.getClass().getSimpleName(), e.getMessage());
            return String.valueOf(value);
        }
    }
}
