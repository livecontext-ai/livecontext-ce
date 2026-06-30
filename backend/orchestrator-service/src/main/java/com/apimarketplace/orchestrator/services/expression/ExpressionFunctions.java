package com.apimarketplace.orchestrator.services.expression;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Static utility functions for SpEL expression evaluation.
 * All methods are public static so they can be registered with SpEL.
 *
 * Functions are called in SpEL with #functionName(args), e.g.:
 * - #int(value) - Convert to integer
 * - #double(value) - Convert to double
 * - #size(collection) - Get size of collection/string
 * - #default(value, fallback) - Return fallback if value is null/empty
 */
public final class ExpressionFunctions {

    private ExpressionFunctions() {
        // Utility class - no instantiation
    }

    // ==========================================
    // Type Casting Functions
    // ==========================================

    /**
     * Convert value to integer.
     * Handles Number, String, Boolean.
     */
    public static Integer toInt(Object value) {
        if (value == null) return 0;
        if (value instanceof Number num) return num.intValue();
        if (value instanceof Boolean bool) return bool ? 1 : 0;
        try {
            String str = String.valueOf(value).trim();
            // Handle decimal strings by parsing as double first
            if (str.contains(".")) {
                return (int) Double.parseDouble(str);
            }
            return Integer.parseInt(str);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Convert value to long.
     */
    public static Long toLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number num) return num.longValue();
        if (value instanceof Boolean bool) return bool ? 1L : 0L;
        try {
            String str = String.valueOf(value).trim();
            if (str.contains(".")) {
                return (long) Double.parseDouble(str);
            }
            return Long.parseLong(str);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * Convert value to double.
     */
    public static Double toDouble(Object value) {
        if (value == null) return 0.0;
        if (value instanceof Number num) return num.doubleValue();
        if (value instanceof Boolean bool) return bool ? 1.0 : 0.0;
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * Convert value to float.
     */
    public static Float toFloat(Object value) {
        if (value == null) return 0.0f;
        if (value instanceof Number num) return num.floatValue();
        if (value instanceof Boolean bool) return bool ? 1.0f : 0.0f;
        try {
            return Float.parseFloat(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0.0f;
        }
    }

    /**
     * Convert value to string.
     */
    public static String toString(Object value) {
        if (value == null) return "";
        return String.valueOf(value);
    }

    /**
     * Convert value to boolean.
     * Handles Boolean, Number (0 = false, others = true), String ("true", "false", "1", "0").
     */
    public static Boolean toBool(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number num) return num.doubleValue() != 0;
        String str = String.valueOf(value).trim().toLowerCase();
        return "true".equals(str) || "1".equals(str) || "yes".equals(str) || "on".equals(str);
    }

    // ==========================================
    // Utility Functions
    // ==========================================

    /**
     * Get the size of a collection, map, string, or array.
     */
    public static Integer size(Object value) {
        if (value == null) return 0;
        if (value instanceof String str) return str.length();
        if (value instanceof Collection<?> col) return col.size();
        if (value instanceof Map<?, ?> map) return map.size();
        if (value.getClass().isArray()) return java.lang.reflect.Array.getLength(value);
        return 0;
    }

    /**
     * Get the type name of a value.
     * Named "typeof" to avoid conflict with common "type" variable names in user data.
     */
    public static String typeof(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return "string";
        if (value instanceof Integer || value instanceof Long) return "int";
        if (value instanceof Double || value instanceof Float) return "double";
        if (value instanceof Boolean) return "bool";
        if (value instanceof List) return "list";
        if (value instanceof Map) return "map";
        if (value.getClass().isArray()) return "array";
        return value.getClass().getSimpleName().toLowerCase();
    }

    /**
     * Return default value if the first value is null or empty.
     */
    public static Object defaultValue(Object value, Object fallback) {
        if (value == null) return fallback;
        if (value instanceof String str && str.isEmpty()) return fallback;
        if (value instanceof Collection<?> col && col.isEmpty()) return fallback;
        if (value instanceof Map<?, ?> map && map.isEmpty()) return fallback;
        return value;
    }

    /**
     * Return the first non-null, non-empty value.
     */
    public static Object coalesce(Object... values) {
        if (values == null) return null;
        for (Object value : values) {
            if (value != null) {
                if (value instanceof String str) {
                    if (!str.isEmpty()) return value;
                } else {
                    return value;
                }
            }
        }
        return null;
    }

    /**
     * Return fallback if value is null or empty string.
     */
    public static Object ifEmpty(Object value, Object fallback) {
        if (value == null) return fallback;
        if (value instanceof String str && str.isEmpty()) return fallback;
        return value;
    }

    /**
     * Check if value is null.
     */
    public static Boolean isNull(Object value) {
        return value == null;
    }

    /**
     * Check if value is empty (null, empty string, empty collection).
     */
    public static Boolean isEmpty(Object value) {
        if (value == null) return true;
        if (value instanceof String str) return str.isEmpty();
        if (value instanceof Collection<?> col) return col.isEmpty();
        if (value instanceof Map<?, ?> map) return map.isEmpty();
        if (value.getClass().isArray()) return java.lang.reflect.Array.getLength(value) == 0;
        return false;
    }

    // ==========================================
    // Math Functions
    // ==========================================

    /**
     * Absolute value.
     */
    public static Number abs(Object value) {
        double d = toDouble(value);
        return Math.abs(d);
    }

    /**
     * Round to specified decimal places.
     */
    public static Number round(Object value, Object decimals) {
        double d = toDouble(value);
        int dec = toInt(decimals);
        if (dec <= 0) {
            return (long) Math.round(d);
        }
        double factor = Math.pow(10, dec);
        return Math.round(d * factor) / factor;
    }

    /**
     * Floor (round down).
     */
    public static Long floor(Object value) {
        return (long) Math.floor(toDouble(value));
    }

    /**
     * Ceiling (round up).
     */
    public static Long ceil(Object value) {
        return (long) Math.ceil(toDouble(value));
    }

    /**
     * Minimum of two values.
     */
    public static Number min(Object a, Object b) {
        double da = toDouble(a);
        double db = toDouble(b);
        return Math.min(da, db);
    }

    /**
     * Maximum of two values.
     */
    public static Number max(Object a, Object b) {
        double da = toDouble(a);
        double db = toDouble(b);
        return Math.max(da, db);
    }

    /**
     * Power function.
     */
    public static Double pow(Object base, Object exponent) {
        return Math.pow(toDouble(base), toDouble(exponent));
    }

    /**
     * Square root.
     */
    public static Double sqrt(Object value) {
        return Math.sqrt(toDouble(value));
    }

    // ==========================================
    // String Functions
    // ==========================================

    /**
     * Convert to uppercase.
     */
    public static String uppercase(Object value) {
        if (value == null) return "";
        return String.valueOf(value).toUpperCase();
    }

    /**
     * Convert to lowercase.
     */
    public static String lowercase(Object value) {
        if (value == null) return "";
        return String.valueOf(value).toLowerCase();
    }

    /**
     * Capitalize first letter.
     */
    public static String capitalize(Object value) {
        if (value == null) return "";
        String str = String.valueOf(value);
        if (str.isEmpty()) return str;
        return Character.toUpperCase(str.charAt(0)) + str.substring(1).toLowerCase();
    }

    /**
     * Trim whitespace.
     */
    public static String trim(Object value) {
        if (value == null) return "";
        return String.valueOf(value).trim();
    }

    /**
     * Truncate string to max length with optional suffix.
     */
    public static String truncate(Object value, Object maxLength, Object suffix) {
        if (value == null) return "";
        String str = String.valueOf(value);
        int max = toInt(maxLength);
        String suf = suffix != null ? String.valueOf(suffix) : "...";

        if (str.length() <= max) return str;
        if (max <= suf.length()) return str.substring(0, max);
        return str.substring(0, max - suf.length()) + suf;
    }

    /**
     * Pad string on the left.
     */
    public static String padLeft(Object value, Object length, Object padChar) {
        if (value == null) return "";
        String str = String.valueOf(value);
        int len = toInt(length);
        String pad = padChar != null ? String.valueOf(padChar) : " ";
        if (pad.isEmpty()) pad = " ";

        StringBuilder sb = new StringBuilder(str);
        while (sb.length() < len) {
            sb.insert(0, pad);
        }
        return sb.substring(Math.max(0, sb.length() - len));
    }

    /**
     * Pad string on the right.
     */
    public static String padRight(Object value, Object length, Object padChar) {
        if (value == null) return "";
        String str = String.valueOf(value);
        int len = toInt(length);
        String pad = padChar != null ? String.valueOf(padChar) : " ";
        if (pad.isEmpty()) pad = " ";

        StringBuilder sb = new StringBuilder(str);
        while (sb.length() < len) {
            sb.append(pad);
        }
        return sb.substring(0, Math.min(len, sb.length()));
    }

    /**
     * Replace occurrences in string.
     */
    public static String replace(Object value, Object search, Object replacement) {
        if (value == null) return "";
        if (search == null) return String.valueOf(value);
        String rep = replacement != null ? String.valueOf(replacement) : "";
        return String.valueOf(value).replace(String.valueOf(search), rep);
    }

    /**
     * Extract substring.
     */
    public static String substring(Object value, Object start, Object end) {
        if (value == null) return "";
        String str = String.valueOf(value);
        int s = Math.max(0, Math.min(toInt(start), str.length()));
        int e = end != null ? Math.max(s, Math.min(toInt(end), str.length())) : str.length();
        return str.substring(s, e);
    }

    /**
     * Split string into list.
     */
    @SuppressWarnings("unchecked")
    public static List<String> split(Object value, Object delimiter) {
        if (value == null) return List.of();
        String delim = delimiter != null ? String.valueOf(delimiter) : ",";
        return Arrays.asList(String.valueOf(value).split(Pattern.quote(delim)));
    }

    /**
     * Join collection elements with delimiter.
     */
    @SuppressWarnings("unchecked")
    public static String join(Object value, Object delimiter) {
        if (value == null) return "";
        String delim = delimiter != null ? String.valueOf(delimiter) : ",";

        if (value instanceof Collection<?> col) {
            return String.join(delim, col.stream().map(String::valueOf).toList());
        }
        if (value.getClass().isArray()) {
            List<String> list = new ArrayList<>();
            int len = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < len; i++) {
                list.add(String.valueOf(java.lang.reflect.Array.get(value, i)));
            }
            return String.join(delim, list);
        }
        return String.valueOf(value);
    }

    /**
     * Check if string starts with prefix.
     */
    public static Boolean startsWith(Object value, Object prefix) {
        if (value == null || prefix == null) return false;
        return String.valueOf(value).startsWith(String.valueOf(prefix));
    }

    /**
     * Check if string ends with suffix.
     */
    public static Boolean endsWith(Object value, Object suffix) {
        if (value == null || suffix == null) return false;
        return String.valueOf(value).endsWith(String.valueOf(suffix));
    }

    /**
     * Check if string contains substring.
     */
    public static Boolean contains(Object value, Object search) {
        if (value == null || search == null) return false;
        if (value instanceof Collection<?> col) {
            return col.contains(search);
        }
        return String.valueOf(value).contains(String.valueOf(search));
    }

    /**
     * Check if string matches regex pattern.
     */
    public static Boolean matches(Object value, Object pattern) {
        if (value == null || pattern == null) return false;
        try {
            return String.valueOf(value).matches(String.valueOf(pattern));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get length of string, collection, map, or array.
     * Works like JavaScript's .length - universal across types.
     */
    public static Integer length(Object value) {
        if (value == null) return 0;
        if (value instanceof Collection<?> col) return col.size();
        if (value instanceof Map<?, ?> map) return map.size();
        if (value.getClass().isArray()) return java.lang.reflect.Array.getLength(value);
        return String.valueOf(value).length();
    }

    // ==========================================
    // Date/Number Formatting Functions
    // ==========================================

    /**
     * Format date with pattern.
     * Supports: Long (epoch millis), String (ISO format), LocalDate, LocalDateTime
     */
    public static String formatDate(Object value, Object pattern) {
        if (value == null) return "";

        String pat = pattern != null ? String.valueOf(pattern) : "yyyy-MM-dd";
        // Convert common date patterns
        pat = pat.replace("DD", "dd")
                 .replace("YYYY", "yyyy")
                 .replace("YY", "yy");

        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pat);

            if (value instanceof Long timestamp) {
                // SpEL `formatTimestamp(epochMs, pattern)` is user-visible
                // in workflow outputs. Render in UTC so the result matches
                // what the rest of the platform stores/displays.
                LocalDateTime dateTime = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(timestamp), ZoneOffset.UTC);
                return dateTime.format(formatter);
            } else if (value instanceof LocalDate date) {
                return date.format(formatter);
            } else if (value instanceof LocalDateTime dateTime) {
                return dateTime.format(formatter);
            } else if (value instanceof String strValue) {
                try {
                    // Try ISO date
                    LocalDate date = LocalDate.parse(strValue);
                    return date.format(formatter);
                } catch (Exception e) {
                    try {
                        // Try ISO datetime
                        LocalDateTime dateTime = LocalDateTime.parse(strValue);
                        return dateTime.format(formatter);
                    } catch (Exception e2) {
                        return strValue;
                    }
                }
            }
            return String.valueOf(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    /**
     * Format number with decimal places.
     */
    public static String formatNumber(Object value, Object decimals) {
        if (value == null) return "0";
        double num = toDouble(value);
        int dec = decimals != null ? toInt(decimals) : 2;

        NumberFormat formatter = NumberFormat.getNumberInstance(Locale.getDefault());
        formatter.setMinimumFractionDigits(dec);
        formatter.setMaximumFractionDigits(dec);
        return formatter.format(num);
    }

    /**
     * Format as currency.
     * Named "formatCurrency" to avoid conflict with common "currency" variable names in user data.
     */
    public static String formatCurrency(Object value, Object currencyCode) {
        if (value == null) return "0";
        double amount = toDouble(value);
        String code = currencyCode != null ? String.valueOf(currencyCode) : "EUR";

        try {
            NumberFormat formatter = NumberFormat.getCurrencyInstance(Locale.getDefault());
            formatter.setCurrency(java.util.Currency.getInstance(code));
            return formatter.format(amount);
        } catch (Exception e) {
            return formatNumber(value, 2) + " " + code;
        }
    }

    /**
     * Get current datetime as ISO string in UTC (e.g. "2026-03-04T15:30:45").
     * Works with formatdate(): formatdate(now(), 'dd/MM/yyyy HH:mm')
     */
    public static String now() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
    }

    /**
     * Get today's date as ISO string (UTC calendar day).
     */
    public static String today() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    // ==========================================
    // JSON Functions
    // ==========================================

    /**
     * Parse a JSON string into a typed object (Map / List / Number / Boolean / String).
     *
     * <p>Idempotent: a value that is already a {@link Map}, {@link java.util.Collection},
     * {@link Number}, or {@link Boolean} is returned unchanged. {@code null} returns {@code null}.
     * A blank string returns {@code null} (consistent with {@code defaultValue}/{@code ifEmpty}).
     *
     * <p>Use case: when a workflow author / agent embeds a literal JSON object into a tool
     * parameter that expects a typed object, wrap the value in {@code {{json(...)}}}:
     * <pre>
     *   {"generationConfig": "{{json('{\"responseModalities\":[\"IMAGE\"]}')}}"}
     *   {"messages":         "{{json(trigger:webhook.output.raw_body)}}"}
     * </pre>
     *
     * <p>Hard caps via {@link JsonOutputUtil}: 256KB single string, 64 nesting depth, 2MB document.
     *
     * @throws JsonParseException if {@code value} is a non-blank string that fails to parse,
     *     carrying a truncated value preview for inspector display
     */
    public static Object json(Object value) {
        if (value == null) return null;
        if (value instanceof Map || value instanceof java.util.Collection
            || value instanceof Number || value instanceof Boolean
            || value.getClass().isArray()) {
            // Already-typed structured values (Map/List/array/scalar wrappers) pass through
            // unchanged - re-running json() on a parsed value is a no-op.
            return value;
        }
        String s = value instanceof String str ? str : String.valueOf(value);
        if (s.isBlank()) return null;
        try {
            return JsonOutputUtil.mapper().readValue(s, Object.class);
        } catch (java.io.IOException e) {
            // JsonProcessingException (subclass of IOException) covers syntax errors AND
            // stream constraint violations (StreamConstraintsException since Jackson 2.15).
            // String-source readValue does not raise raw I/O in practice, but the declared
            // throws is IOException so we catch it once.
            String detail = (e instanceof com.fasterxml.jackson.core.JsonProcessingException jpe)
                ? jpe.getOriginalMessage() : e.getMessage();
            throw new JsonParseException(
                "json() failed to parse value as JSON: " + detail,
                JsonParseException.preview(s),
                e
            );
        }
    }

    /**
     * Alias for {@link #json(Object)}, mirrors GitHub Actions {@code fromJSON()} for familiarity.
     */
    public static Object fromJson(Object value) {
        return json(value);
    }

    /**
     * Serialize a typed value (Map / List / scalar) to a compact JSON string.
     * Inverse of {@link #json(Object)}: {@code json(toJson(map))} round-trips to {@code map}.
     *
     * <p>{@code null} input yields the literal string {@code "null"} (matches Jackson default).
     * Falls back to {@code String.valueOf} on serialization failure rather than throwing
     * (same defensive contract as substitution-side {@link JsonOutputUtil#encode(Object)}).
     */
    public static String toJson(Object value) {
        return JsonOutputUtil.encode(value);
    }
}
