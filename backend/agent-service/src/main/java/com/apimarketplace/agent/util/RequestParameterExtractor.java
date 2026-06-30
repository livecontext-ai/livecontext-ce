package com.apimarketplace.agent.util;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * Type-safe parameter extraction from Map request bodies.
 * Handles common type conversions with null safety.
 *
 * <p>This component replaces 50+ occurrences of manual type casting patterns like:</p>
 * <pre>
 * Object value = request.get("key");
 * if (value instanceof Number) { ... }
 * else if (value instanceof String) { ... }
 * </pre>
 *
 * <p>Follows Single Responsibility Principle - only handles parameter extraction.</p>
 */
@Component
public class RequestParameterExtractor {

    /**
     * Extract String value from request body.
     */
    public String getString(Map<String, Object> request, String key) {
        Object value = request.get(key);
        return value != null ? value.toString() : null;
    }

    /**
     * Extract String value with default.
     */
    public String getString(Map<String, Object> request, String key, String defaultValue) {
        String value = getString(request, key);
        return value != null ? value : defaultValue;
    }

    /**
     * Extract a TEXT / human-or-LLM-authored CONTENT value - names, descriptions,
     * instructions, system prompts, templates.
     *
     * <p>Unlike {@link #getString} (which blindly calls {@code value.toString()}),
     * this REFUSES to silently coerce a non-text JSON value (Number, Boolean, Map,
     * List) into a string. A numeric value for a content field is always a
     * client/import bug: it once stored {@code "106735"} into a skill's
     * {@code instructions} (a numeric value that {@code getString} happily
     * stringified). Use this for every content field so a type mismatch fails
     * fast (400) instead of persisting garbage that later ships to every CE via
     * the signed bundle.
     *
     * @return the string for genuine text, {@code null} when the key is absent/null
     * @throws IllegalArgumentException when the value is present but not text
     */
    public String getText(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (value == null) return null;
        if (value instanceof CharSequence cs) return cs.toString();
        throw new IllegalArgumentException(
            key + " must be text, got " + value.getClass().getSimpleName() + ": " + value);
    }

    /**
     * Extract Integer from Number or String.
     */
    public Integer getInteger(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (value instanceof Number num) {
            return num.intValue();
        } else if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Extract Integer with default value.
     */
    public int getInteger(Map<String, Object> request, String key, int defaultValue) {
        Integer value = getInteger(request, key);
        return value != null ? value : defaultValue;
    }

    /**
     * Extract Long from Number or String.
     */
    public Long getLong(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (value instanceof Number num) {
            return num.longValue();
        } else if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Extract Double from Number or String.
     */
    public Double getDouble(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (value instanceof Number num) {
            return num.doubleValue();
        } else if (value instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Extract BigDecimal from Number or String.
     */
    public BigDecimal getBigDecimal(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (value instanceof Number num) {
            return BigDecimal.valueOf(num.doubleValue());
        } else if (value instanceof String s && !s.isBlank()) {
            try {
                return new BigDecimal(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Extract Boolean with null safety.
     */
    public Boolean getBoolean(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        } else if (value instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return null;
    }

    /**
     * Extract Boolean with default value.
     */
    public boolean getBoolean(Map<String, Object> request, String key, boolean defaultValue) {
        Boolean value = getBoolean(request, key);
        return value != null ? value : defaultValue;
    }

    /**
     * Extract UUID from String.
     */
    public UUID getUUID(Map<String, Object> request, String key) {
        String value = getString(request, key);
        if (value != null && !value.isBlank()) {
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Extract typed Map.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getMap(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return null;
    }

    /**
     * Extract typed List.
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> getList(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (value instanceof List) {
            return (List<T>) value;
        }
        return null;
    }

    /**
     * Extract Set from Collection.
     */
    @SuppressWarnings("unchecked")
    public <T> Set<T> getSet(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (value instanceof Collection) {
            return new HashSet<>((Collection<T>) value);
        }
        return new HashSet<>();
    }

    /**
     * Extract raw Object value.
     */
    public Object getRaw(Map<String, Object> request, String key) {
        return request.get(key);
    }

    /**
     * Check if a key exists and has a non-null value.
     */
    public boolean hasValue(Map<String, Object> request, String key) {
        return request.containsKey(key) && request.get(key) != null;
    }

    /**
     * Extract a subset of keys as an Integer map, preserving the distinction between
     * "key absent" and "key present with explicit null". Used for three-state patch
     * semantics (no-op / clear / set).
     *
     * <p>Rules:
     * <ul>
     *   <li>key absent from request → not included in the returned map</li>
     *   <li>key present with null or missing value → included with {@code null}</li>
     *   <li>key present with Integer or numeric-string → included with parsed int</li>
     *   <li>key present with non-coercible value (Boolean, Map, List, garbage string) →
     *       {@link IllegalArgumentException} (fail fast, no silent coercion)</li>
     * </ul>
     *
     * @return {@code null} when no keys from {@code keys} appear in the request,
     *         otherwise a map sized to the number of present keys.
     */
    public Map<String, Integer> extractIntegerMap(Map<String, Object> request, List<String> keys) {
        Map<String, Integer> out = new HashMap<>();
        for (String k : keys) {
            if (!request.containsKey(k)) continue;
            Object raw = request.get(k);
            Integer parsed = getInteger(request, k);
            if (raw != null && parsed == null) {
                throw new IllegalArgumentException(
                    k + " must be an integer or null, got " + raw.getClass().getSimpleName() + ": " + raw);
            }
            out.put(k, parsed);
        }
        return out.isEmpty() ? null : out;
    }
}
