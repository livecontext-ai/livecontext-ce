package com.apimarketplace.agent.tools.common;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shared utility methods for extracting typed parameters from tool argument maps.
 * Eliminates duplicated getUuidParam/getIntParam/getLongParam/getDecimalParam
 * across InterfaceToolsProvider, AgentToolsProvider, and DataSourceToolsProvider.
 */
public final class ToolParamUtils {

    private ToolParamUtils() {}

    public static UUID getUuidParam(Map<String, Object> params, String key) {
        Object val = params.get(key);
        if (val instanceof String str) {
            try {
                return UUID.fromString(str);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    public static Integer getIntParam(Map<String, Object> params, String key) {
        Object val = params.get(key);
        if (val instanceof Number num) return num.intValue();
        if (val instanceof String str) {
            try { return Integer.parseInt(str); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    public static Integer getIntParam(Map<String, Object> params, String key, Integer defaultValue) {
        Integer val = getIntParam(params, key);
        return val != null ? val : defaultValue;
    }

    public static Long getLongParam(Map<String, Object> params, String key) {
        Object val = params.get(key);
        if (val instanceof Number num) return num.longValue();
        if (val instanceof String str) {
            try { return Long.parseLong(str); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    public static BigDecimal getDecimalParam(Map<String, Object> params, String key) {
        Object val = params.get(key);
        if (val instanceof Number num) return BigDecimal.valueOf(num.doubleValue());
        if (val instanceof String str) {
            try { return new BigDecimal(str); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    public static String getStringParam(Map<String, Object> params, String key) {
        Object val = params.get(key);
        return val instanceof String str ? str : null;
    }

    /**
     * Case-insensitive substring match of a free-text list filter ({@code query})
     * against one or more resource fields (typically name + description).
     *
     * <p>Returns {@code true} when {@code query} is {@code null} or blank - a blank
     * filter matches everything, so callers can pass the raw param through without a
     * null guard. Otherwise returns {@code true} iff at least one non-null field
     * contains the trimmed, lower-cased query.
     *
     * <p>Centralized so every list action (workflow.list, interface.list, skill.list,
     * agent.list, application.my, table.list) applies the SAME matching semantics -
     * the agent gets one consistent {@code query} behavior across all list tools.
     */
    public static boolean matchesQuery(String query, String... fields) {
        if (query == null) return true;
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return true;
        if (fields == null) return false;
        for (String f : fields) {
            if (f != null && f.toLowerCase(Locale.ROOT).contains(q)) return true;
        }
        return false;
    }

    /**
     * True when {@code query} is a non-blank, actionable filter. Use to decide whether
     * to add {@code "query"} to the {@code AgentListEnvelope} active-filter set (which
     * governs the hard-refuse-without-filter guard) so the check matches
     * {@link #matchesQuery} exactly.
     */
    public static boolean hasQuery(String query) {
        return query != null && !query.trim().isEmpty();
    }

    /**
     * Extract conversationId from tool execution context credentials.
     */
    public static String getConversationId(Map<String, Object> credentials) {
        if (credentials == null) return null;
        Object convId = credentials.get("conversationId");
        return convId != null ? convId.toString() : null;
    }

    /**
     * Extract turnId from tool execution context credentials.
     * Each user message generates a unique turnId for per-message rate limiting.
     */
    public static String getTurnId(Map<String, Object> credentials) {
        if (credentials == null) return null;
        Object turnId = credentials.get("turnId");
        return turnId != null ? turnId.toString() : null;
    }

    /**
     * Merge a nested "params" object into top-level parameters.
     * Supports both flat and nested param styles from LLMs.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> mergeParams(Map<String, Object> parameters) {
        Map<String, Object> merged = new LinkedHashMap<>(parameters);
        Object paramsObj = parameters.get("params");
        if (paramsObj instanceof Map) {
            Map<String, Object> paramsMap = (Map<String, Object>) paramsObj;
            paramsMap.forEach(merged::put);
        }
        return merged;
    }

    private static final Set<String> VALID_VISIBILITIES = Set.of("PRIVATE", "PUBLIC", "UNLISTED");

    /**
     * Exception thrown when a caller passes a visibility value that is not one of
     * {@code PRIVATE}, {@code PUBLIC}, or {@code UNLISTED}. Modules catch this and
     * surface a clean error to the agent instead of forwarding a typo to the backend.
     */
    public static final class InvalidVisibilityException extends IllegalArgumentException {
        public InvalidVisibilityException(String raw) {
            super("Invalid visibility: '" + raw + "'. Must be one of PRIVATE, PUBLIC, UNLISTED.");
        }
    }

    /**
     * Normalize a raw {@code visibility} input for marketplace publication.
     *
     * <ul>
     *   <li>{@code null} or blank → {@code "PRIVATE"} (default).</li>
     *   <li>Valid uppercase value ({@code PRIVATE}, {@code PUBLIC}, {@code UNLISTED}) -
     *       case-insensitive input, trimmed, returned uppercase.</li>
     *   <li>Anything else → {@link InvalidVisibilityException}.</li>
     * </ul>
     *
     * <p>Centralized so all publish modules (agent/skill/table/interface/workflow)
     * enforce the same enum contract at the tool boundary, rather than silently
     * forwarding a typo like {@code "PUBLI"} to publication-service.
     */
    public static String normalizeVisibility(String raw) {
        if (raw == null) return "PRIVATE";
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return "PRIVATE";
        String upper = trimmed.toUpperCase();
        if (!VALID_VISIBILITIES.contains(upper)) {
            throw new InvalidVisibilityException(raw);
        }
        return upper;
    }

    /**
     * Peel off the first {@code ": "} wrapper from an exception message so the agent
     * sees the backend's actual validation error (e.g. {@code "PUBLIC workflow
     * publications must be free (creditsPerUse=0)."}) instead of a generic wrapper
     * like {@code "Failed to publish workflow: ..."}.
     */
    public static String extractPublicationErrorMessage(Throwable e) {
        String msg = e.getMessage();
        if (msg == null) return e.getClass().getSimpleName();
        int idx = msg.indexOf(": ");
        return idx > 0 && idx < msg.length() - 2 ? msg.substring(idx + 2) : msg;
    }
}
