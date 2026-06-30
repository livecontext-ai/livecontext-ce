package com.apimarketplace.datasource.crud.service;

import com.apimarketplace.datasource.domain.ColumnType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Coerces raw values to the expected type based on the column's ColumnType.
 * Applied after SpEL resolution and before JSONB insert to ensure type consistency.
 * Returns {@link CoercionResult} with value + warnings (never silently corrupts data).
 */
@Component
public class ColumnValueCoercer {

    private static final Logger log = LoggerFactory.getLogger(ColumnValueCoercer.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final Set<String> TRUTHY = Set.of("true", "yes", "1", "on");
    private static final Set<String> FALSY_NULL = Set.of("", "null", "none", "nil");
    private static final Set<String> SENTIMENT_UP = Set.of("up", "positive", "yes", "good", "like", "thumbsup", "thumbs_up");
    private static final Set<String> SENTIMENT_DOWN = Set.of("down", "negative", "no", "bad", "dislike", "thumbsdown", "thumbs_down");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern EMAIL_DISPLAY_NAME = Pattern.compile("^.*<\\s*([^>]+)\\s*>\\s*$");
    private static final Pattern DIGITS_PATTERN = Pattern.compile("\\d");
    private static final Pattern NUMERIC_STRIP = Pattern.compile("[^\\d.\\-+eE]");
    private static final Pattern PERCENT_SUFFIX = Pattern.compile("^([\\d.\\-+eE]+)\\s*%$");
    // European decimal: "3,14" (single comma with 1-3 decimals, no other commas)
    private static final Pattern EUROPEAN_DECIMAL = Pattern.compile("^-?\\d+,(\\d{1,3})$");
    // Ordinal suffix pattern: "1st", "2nd", "3rd", "15th"
    private static final Pattern ORDINAL_SUFFIX = Pattern.compile("(\\d+)(st|nd|rd|th)\\b", Pattern.CASE_INSENSITIVE);

    // Epoch: values below this threshold are treated as seconds, above as millis
    private static final long EPOCH_SECONDS_THRESHOLD = 1_000_000_000_0L; // 10 billion (~2286 in seconds)

    // Compact date pattern: 20240115 or 20240115T103000
    private static final Pattern COMPACT_DATE = Pattern.compile("^(\\d{4})(\\d{2})(\\d{2})(?:T(\\d{2})(\\d{2})(\\d{2}))?$");

    // Date-only fallback formatters (parsed as LocalDate → yyyy-MM-dd)
    // Single-digit day variants ('d') come after double-digit ('dd') to avoid ambiguity
    private static final List<DateTimeFormatter> DATE_ONLY_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM dd yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM d yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMMM dd, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMMM dd yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMMM d yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)
    );

    // Date+time fallback formatters (parsed as LocalDateTime → yyyy-MM-dd'T'HH:mm:ss)
    // Fractional-seconds variants come first (more specific → less specific)
    private static final List<DateTimeFormatter> DATETIME_FALLBACK_FORMATTERS = List.of(
            // With fractional seconds (database timestamps like "2024-01-15 10:30:00.123456")
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S"),
            // Without fractional seconds
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm:ss", Locale.ENGLISH)
    );

    // Time-only formatters (parsed as LocalTime → stored with sentinel date 1970-01-01)
    private static final List<DateTimeFormatter> TIME_ONLY_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSS"),
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("HH:mm:ss"),
            DateTimeFormatter.ofPattern("HH:mm"),
            DateTimeFormatter.ofPattern("H:mm:ss"),
            DateTimeFormatter.ofPattern("H:mm")
    );

    // Pattern to detect time-only strings: HH:mm or HH:mm:ss with optional fractional seconds
    private static final Pattern TIME_ONLY_PATTERN = Pattern.compile("^\\d{1,2}:\\d{2}(:\\d{2}(\\.\\d+)?)?$");

    // RFC 2822 / RFC 1123 (e.g. "Mon, 15 Jan 2024 10:30:00 GMT")
    private static final DateTimeFormatter RFC_1123 = DateTimeFormatter.RFC_1123_DATE_TIME;

    /**
     * Coerce a raw value to match the expected column type, with display config for range types.
     */
    public CoercionResult coerce(Object value, ColumnType type, Map<String, Object> displayConfig) {
        if (value == null || type == null) {
            return CoercionResult.ok(value);
        }
        Map<String, Object> display = displayConfig != null ? displayConfig : Map.of();

        try {
            return switch (type) {
                case TEXT -> coerceText(value);
                case NUMBER -> coerceNumber(value);
                case DATE -> coerceDate(value, display);
                case CHECKBOX -> coerceCheckbox(value);
                case SELECT -> coerceSelect(value, display);
                case MULTI_SELECT -> coerceMultiSelect(value);
                case RATING -> coerceRating(value, display);
                case SENTIMENT -> coerceSentiment(value);
                case PROGRESS -> coerceProgress(value, display);
                case FILE -> coerceFile(value);
                case IMAGE -> coerceImage(value);
                case EMAIL -> coerceEmail(value);
                case PHONE -> coercePhone(value);
                case URL -> coerceUrl(value);
                case VECTOR -> coerceVector(value, display);
            };
        } catch (Exception e) {
            log.debug("Coercion failed for type={} value={}: {}", type, value, e.getMessage());
            return CoercionResult.withWarning(value, "Coercion error: " + e.getMessage());
        }
    }

    /**
     * Backward-compatible overload without display config.
     */
    public CoercionResult coerce(Object value, ColumnType type) {
        return coerce(value, type, Map.of());
    }

    // ── TEXT ──────────────────────────────────────────────────────────────

    private CoercionResult coerceText(Object value) {
        if (value instanceof String) return CoercionResult.ok(value);

        // File-like map → extract URL + name as readable text instead of raw JSON
        if (value instanceof Map<?, ?> map && isFileLikeMap(map)) {
            String url = resolveFileUrl(map);
            String name = resolveFileName(map);
            if (url != null) {
                String text = name != null ? name + " (" + url + ")" : url;
                return CoercionResult.ok(text);
            }
        }

        if (value instanceof Map || value instanceof List) {
            try {
                return CoercionResult.ok(objectMapper.writeValueAsString(value));
            } catch (Exception e) {
                return CoercionResult.ok(value.toString());
            }
        }
        return CoercionResult.ok(value.toString());
    }

    // ── NUMBER ───────────────────────────────────────────────────────────

    private CoercionResult coerceNumber(Object value) {
        if (value instanceof Number num) {
            // Guard against NaN/Infinity which are invalid in JSON
            if (num instanceof Double d && (d.isNaN() || d.isInfinite())) {
                return CoercionResult.failed("Invalid number: " + d + " (NaN/Infinity not allowed)");
            }
            if (num instanceof Float f && (f.isNaN() || f.isInfinite())) {
                return CoercionResult.failed("Invalid number: " + f + " (NaN/Infinity not allowed)");
            }
            return CoercionResult.ok(num);
        }
        if (value instanceof Boolean b) return CoercionResult.ok(b ? 1 : 0);

        String str = value.toString().trim();
        if (str.isEmpty()) return CoercionResult.ok(0);

        // Reject NaN/Infinity string literals
        String lower = str.toLowerCase();
        if (lower.equals("nan") || lower.equals("infinity") || lower.equals("-infinity")
                || lower.equals("+infinity") || lower.equals("inf") || lower.equals("-inf")) {
            return CoercionResult.failed("Invalid number: '" + str + "' (NaN/Infinity not allowed)");
        }

        // Try direct parse first
        try {
            return CoercionResult.ok(parseNumeric(str));
        } catch (NumberFormatException ignored) {
        }

        // European decimal: "3,14" → "3.14" (single comma as decimal separator)
        Matcher europeanMatch = EUROPEAN_DECIMAL.matcher(str);
        if (europeanMatch.matches()) {
            String normalized = str.replace(',', '.');
            try {
                return CoercionResult.withWarning(parseNumeric(normalized),
                        "Interpreted comma as decimal separator: '" + str + "' → " + normalized);
            } catch (NumberFormatException ignored) {
            }
        }

        // Strip non-numeric chars (currency symbols, commas, spaces) and retry
        String stripped = NUMERIC_STRIP.matcher(str).replaceAll("");
        if (stripped.isEmpty()) {
            return CoercionResult.failed("Cannot parse as number: '" + str + "'");
        }
        try {
            return CoercionResult.withWarning(parseNumeric(stripped),
                    "Stripped non-numeric characters from '" + str + "'");
        } catch (NumberFormatException e) {
            return CoercionResult.failed("Cannot parse as number: '" + str + "'");
        }
    }

    private Number parseNumeric(String str) {
        if (str.contains(".") || str.toLowerCase().contains("e")) {
            double d = Double.parseDouble(str);
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new NumberFormatException("NaN/Infinity");
            }
            return d;
        }
        try {
            return Long.parseLong(str);
        } catch (NumberFormatException e) {
            // Fall back to BigDecimal for very large integers
            try {
                BigDecimal bd = new BigDecimal(str);
                // If it fits in long, return long
                if (bd.scale() <= 0 && bd.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) <= 0
                        && bd.compareTo(BigDecimal.valueOf(Long.MIN_VALUE)) >= 0) {
                    return bd.longValueExact();
                }
                // Otherwise return as double (lossy but valid JSON)
                return bd.doubleValue();
            } catch (NumberFormatException | ArithmeticException ex) {
                throw new NumberFormatException("Cannot parse: " + str);
            }
        }
    }

    // ── DATE ─────────────────────────────────────────────────────────────

    private CoercionResult coerceDate(Object value, Map<String, Object> display) {
        if (value instanceof Number num) {
            return coerceDateFromEpoch(num);
        }
        if (value instanceof Boolean) {
            return CoercionResult.failed("Cannot convert boolean to date");
        }
        if (!(value instanceof String str)) {
            return CoercionResult.ok(value.toString());
        }

        String trimmed = str.trim();
        if (trimmed.isEmpty()) return CoercionResult.ok(null);

        // 0. Time-only values (14:30, 14:30:00, etc.) - stored with sentinel date 1970-01-01
        //    Try if dateFormat is "time", or if the value looks like a time-only string
        boolean isTimeFormat = "time".equals(display.get("dateFormat"));
        if (isTimeFormat || TIME_ONLY_PATTERN.matcher(trimmed).matches()) {
            for (DateTimeFormatter fmt : TIME_ONLY_FORMATTERS) {
                try {
                    LocalTime time = LocalTime.parse(trimmed, fmt);
                    String iso = LocalDateTime.of(LocalDate.of(1970, 1, 1), time).toString();
                    return CoercionResult.ok(iso);
                } catch (DateTimeParseException ignored) {
                }
            }
        }

        // 1. ISO local date (2024-01-15) - check first to preserve date-only format
        try {
            LocalDate.parse(trimmed);
            return CoercionResult.ok(trimmed);
        } catch (DateTimeParseException ignored) {
        }

        // 2. ISO local date-time (2024-01-15T10:30:00, 2024-01-15T10:30:00.123)
        //    Check before Instant/Offset to preserve local times without timezone
        if (trimmed.contains("T") && !trimmed.endsWith("Z") && !trimmed.matches(".*[+\\-]\\d{2}:\\d{2}$")) {
            try {
                LocalDateTime.parse(trimmed);
                return CoercionResult.ok(trimmed);
            } catch (DateTimeParseException ignored) {
            }
        }

        // 3. ISO offset date-time (2024-01-15T10:30:00+02:00) → normalize to UTC
        try {
            OffsetDateTime odt = OffsetDateTime.parse(trimmed);
            String iso = odt.toInstant().toString();
            return CoercionResult.ok(iso);
        } catch (DateTimeParseException ignored) {
        }

        // 4. ISO instant (2024-01-15T10:30:00Z, 2024-01-15T10:30:00.123Z)
        try {
            Instant instant = Instant.parse(trimmed);
            return CoercionResult.ok(instant.toString());
        } catch (DateTimeParseException ignored) {
        }

        // 5. RFC 1123 / 2822 (Mon, 15 Jan 2024 10:30:00 GMT)
        try {
            OffsetDateTime odt = OffsetDateTime.parse(trimmed, RFC_1123);
            String iso = odt.toInstant().toString();
            return CoercionResult.withWarning(iso, "Converted RFC date to ISO: '" + trimmed + "' → '" + iso + "'");
        } catch (DateTimeParseException ignored) {
        }

        // 6. Compact date (20240115, 20240115T103000)
        Matcher compact = COMPACT_DATE.matcher(trimmed);
        if (compact.matches()) {
            try {
                String isoDate = compact.group(1) + "-" + compact.group(2) + "-" + compact.group(3);
                if (compact.group(4) != null) {
                    String isoTime = compact.group(4) + ":" + compact.group(5) + ":" + compact.group(6);
                    String full = isoDate + "T" + isoTime;
                    LocalDateTime.parse(full); // validate
                    return CoercionResult.withWarning(full, "Converted compact date to ISO: '" + trimmed + "' → '" + full + "'");
                }
                LocalDate.parse(isoDate); // validate
                return CoercionResult.withWarning(isoDate, "Converted compact date to ISO: '" + trimmed + "' → '" + isoDate + "'");
            } catch (DateTimeParseException ignored) {
            }
        }

        // 7. Date+time fallback formats (yyyy-MM-dd HH:mm:ss, dd/MM/yyyy HH:mm, etc.)
        for (DateTimeFormatter fmt : DATETIME_FALLBACK_FORMATTERS) {
            try {
                LocalDateTime parsed = LocalDateTime.parse(trimmed, fmt);
                String iso = parsed.toString();
                return CoercionResult.withWarning(iso, "Converted date format to ISO: '" + trimmed + "' → '" + iso + "'");
            } catch (DateTimeParseException ignored) {
            }
        }

        // 8. Date-only fallback formats (MM/dd/yyyy, dd.MM.yyyy, dd MMM yyyy, etc.)
        for (DateTimeFormatter fmt : DATE_ONLY_FORMATTERS) {
            try {
                LocalDate parsed = LocalDate.parse(trimmed, fmt);
                String iso = parsed.toString();
                return CoercionResult.withWarning(iso, "Converted date format to ISO: '" + trimmed + "' → '" + iso + "'");
            } catch (DateTimeParseException ignored) {
            }
        }

        // 9. Strip ordinal suffixes ("January 15th, 2024" → "January 15, 2024") and retry
        String withoutOrdinal = ORDINAL_SUFFIX.matcher(trimmed).replaceAll("$1");
        if (!withoutOrdinal.equals(trimmed)) {
            for (DateTimeFormatter fmt : DATE_ONLY_FORMATTERS) {
                try {
                    LocalDate parsed = LocalDate.parse(withoutOrdinal, fmt);
                    String iso = parsed.toString();
                    return CoercionResult.withWarning(iso, "Converted date format to ISO: '" + trimmed + "' → '" + iso + "'");
                } catch (DateTimeParseException ignored) {
                }
            }
        }

        // 10. Strip timezone abbreviations (UTC, GMT, EST, etc.) at end and retry as datetime
        String withoutTz = trimmed.replaceAll("\\s+[A-Z]{2,5}$", "").trim();
        if (!withoutTz.equals(trimmed)) {
            for (DateTimeFormatter fmt : DATETIME_FALLBACK_FORMATTERS) {
                try {
                    LocalDateTime parsed = LocalDateTime.parse(withoutTz, fmt);
                    String iso = parsed.toString();
                    return CoercionResult.withWarning(iso, "Converted date format to ISO: '" + trimmed + "' → '" + iso + "'");
                } catch (DateTimeParseException ignored) {
                }
            }
        }

        // 11. Numeric string → try as epoch
        try {
            long epochVal = Long.parseLong(trimmed);
            return coerceDateFromEpoch(epochVal);
        } catch (NumberFormatException ignored) {
        }

        return CoercionResult.failed("Cannot parse as date: '" + trimmed + "'");
    }

    private CoercionResult coerceDateFromEpoch(Number num) {
        long val = num.longValue();
        try {
            Instant instant;
            if (Math.abs(val) < EPOCH_SECONDS_THRESHOLD) {
                // Looks like seconds (before year ~2286)
                instant = Instant.ofEpochSecond(val);
            } else {
                // Looks like milliseconds
                instant = Instant.ofEpochMilli(val);
            }
            return CoercionResult.ok(instant.toString());
        } catch (Exception e) {
            return CoercionResult.failed("Invalid epoch value: " + num);
        }
    }

    // ── CHECKBOX ─────────────────────────────────────────────────────────

    private CoercionResult coerceCheckbox(Object value) {
        if (value instanceof Boolean) return CoercionResult.ok(value);
        if (value instanceof Number num) return CoercionResult.ok(num.doubleValue() != 0);
        String str = value.toString().trim().toLowerCase();
        // Empty, "null", "none", "nil" → null (not false) - distinguish "no data" from "unchecked"
        if (FALSY_NULL.contains(str)) return CoercionResult.ok(null);
        return CoercionResult.ok(TRUTHY.contains(str));
    }

    // ── SELECT ───────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private CoercionResult coerceSelect(Object value, Map<String, Object> display) {
        // Normalize input to string: Number 42.0 → "42" for cleaner matching
        String str;
        if (value instanceof Number num) {
            // If integer-valued double (42.0), format without decimal point for matching
            double d = num.doubleValue();
            str = (d == Math.floor(d) && !Double.isInfinite(d)) ? String.valueOf(num.longValue()) : num.toString();
        } else {
            str = value.toString();
        }
        str = str.trim();
        if (str.isEmpty()) return CoercionResult.ok(null);

        Object optionsObj = display.get("options");
        if (!(optionsObj instanceof List<?> optionsList) || optionsList.isEmpty()) {
            // No options defined - pass through
            return CoercionResult.ok(str);
        }

        // Try to match by value or label (case-insensitive)
        for (Object opt : optionsList) {
            if (opt instanceof Map<?, ?> optMap) {
                String optValue = optMap.get("value") != null ? optMap.get("value").toString().trim() : null;
                String optLabel = optMap.get("label") != null ? optMap.get("label").toString().trim() : null;
                if (str.equalsIgnoreCase(optValue)) return CoercionResult.ok(optValue);
                if (str.equalsIgnoreCase(optLabel)) return CoercionResult.ok(optValue != null ? optValue : optLabel);
            } else if (opt instanceof String optStr) {
                if (str.equalsIgnoreCase(optStr.trim())) return CoercionResult.ok(optStr.trim());
            }
        }

        // No match - store as-is with warning
        return CoercionResult.withWarning(str, "Value '" + str + "' does not match any defined option");
    }

    // ── MULTI_SELECT ─────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private CoercionResult coerceMultiSelect(Object value) {
        if (value instanceof List<?> list) {
            // Ensure all elements are strings
            List<String> stringList = list.stream()
                    .map(item -> item != null ? item.toString() : "")
                    .filter(s -> !s.isEmpty())
                    .toList();
            return CoercionResult.ok(stringList);
        }
        if (!(value instanceof String str)) {
            return CoercionResult.ok(List.of(value.toString()));
        }

        String trimmed = str.trim();
        if (trimmed.isEmpty()) return CoercionResult.ok(List.of());

        // Try JSON array parse
        if (trimmed.startsWith("[")) {
            try {
                List<?> parsed = objectMapper.readValue(trimmed, new TypeReference<List<Object>>() {});
                List<String> stringified = parsed.stream()
                        .map(item -> item != null ? item.toString() : "")
                        .filter(s -> !s.isEmpty())
                        .toList();
                return CoercionResult.ok(stringified);
            } catch (Exception ignored) {
            }
        }

        // Split by comma, semicolon, or pipe (use the first delimiter found)
        String delimiter;
        if (trimmed.contains(";")) {
            delimiter = ";";
        } else if (trimmed.contains("|")) {
            delimiter = "\\|";
        } else {
            delimiter = ",";
        }
        List<String> items = Arrays.stream(trimmed.split(delimiter))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        return CoercionResult.ok(items);
    }

    // ── RATING ───────────────────────────────────────────────────────────

    private CoercionResult coerceRating(Object value, Map<String, Object> display) {
        int max = getIntFromDisplay(display, "max", 5);
        return coerceRangedInt(value, 0, max, "rating");
    }

    // ── SENTIMENT ────────────────────────────────────────────────────────

    private CoercionResult coerceSentiment(Object value) {
        if (value instanceof Boolean b) return CoercionResult.ok(b ? "up" : "down");
        if (value instanceof Number num) {
            double d = num.doubleValue();
            if (d > 0) return CoercionResult.ok("up");
            if (d < 0) return CoercionResult.ok("down");
            return CoercionResult.ok("neutral");
        }
        String str = value.toString().trim().toLowerCase();
        if (SENTIMENT_UP.contains(str)) return CoercionResult.ok("up");
        if (SENTIMENT_DOWN.contains(str)) return CoercionResult.ok("down");
        // "true"/"false" strings → same as boolean
        if ("true".equals(str)) return CoercionResult.ok("up");
        if ("false".equals(str)) return CoercionResult.ok("down");
        // Try numeric string parse ("1", "-1", "+1", "0")
        try {
            double d = Double.parseDouble(str);
            if (d > 0) return CoercionResult.ok("up");
            if (d < 0) return CoercionResult.ok("down");
            return CoercionResult.ok("neutral");
        } catch (NumberFormatException ignored) {
        }
        return CoercionResult.ok("neutral");
    }

    // ── PROGRESS ─────────────────────────────────────────────────────────

    private CoercionResult coerceProgress(Object value, Map<String, Object> display) {
        int max = getIntFromDisplay(display, "max", 100);

        // Handle "75%" string → strip percent and parse
        if (value instanceof String str) {
            Matcher m = PERCENT_SUFFIX.matcher(str.trim());
            if (m.matches()) {
                try {
                    value = Double.parseDouble(m.group(1));
                } catch (NumberFormatException ignored) {
                    value = m.group(1);
                }
            }
        }

        // Convert string to number first, so fraction heuristic applies uniformly
        double numericValue;
        boolean wasString = false;
        if (value instanceof Number num) {
            numericValue = num.doubleValue();
        } else {
            String s = value.toString().trim();
            if (s.isEmpty()) return CoercionResult.ok(null);
            try {
                numericValue = Double.parseDouble(s);
                wasString = true;
            } catch (NumberFormatException e) {
                return CoercionResult.failed("Cannot parse as progress: '" + s + "'");
            }
        }

        // Heuristic: 0.75 with max >= 10 → multiply by max (fraction → absolute)
        // Applies to both Number and parsed String values
        if (max >= 10 && numericValue > 0 && numericValue < 1) {
            int scaled = (int) Math.round(numericValue * max);
            return clampWithWarning(scaled, 0, max, "progress",
                    "Interpreted " + numericValue + " as fraction: " + numericValue + " × " + max + " = " + scaled);
        }

        // Safe rounding without overflow
        int rounded = safeRoundToInt(numericValue);
        return clampWithWarning(rounded, 0, max, "progress", null);
    }

    // ── FILE ─────────────────────────────────────────────────────────────
    //
    // Normalizes all known file formats to canonical: {url, name, mimeType, size}
    // Input formats:
    //   1. FileRef:          {_type:"file", path:"s3/key", name, mimeType, size}
    //   2. DB flattened:     {file_url:"/api/proxy/files/proxy?key=...", file_name, content_type, file_size}
    //   3. Generic upload:   {url, storageKey, fileName, mimeType, size}
    //   4. Frontend cell:    {url, name, mimeType, size}  (already canonical)
    //   5. URL string:       "https://..." or "/api/..."
    //   6. JSON string:      any of the above serialized as JSON

    private CoercionResult coerceFile(Object value) {
        if (value instanceof Map<?, ?> map) {
            return normalizeFileMap(map);
        }
        if (!(value instanceof String str)) return CoercionResult.ok(value);

        String trimmed = str.trim();
        if (trimmed.isEmpty()) return CoercionResult.ok(null);

        // Try JSON parse
        if (trimmed.startsWith("{")) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(trimmed, new TypeReference<>() {});
                return normalizeFileMap(parsed);
            } catch (Exception ignored) {
            }
        }

        // Treat as URL string → build minimal map
        if (looksLikeUrl(trimmed)) {
            Map<String, Object> fileMap = new LinkedHashMap<>();
            fileMap.put("url", trimmed);
            fileMap.put("name", extractFilename(trimmed));
            return CoercionResult.ok(fileMap);
        }

        return CoercionResult.withWarning(value, "Value does not look like a file URL: '" + trimmed + "'");
    }

    /**
     * Normalize any known file map format to canonical {url, name, mimeType, size}.
     */
    private CoercionResult normalizeFileMap(Map<?, ?> map) {
        // Already canonical format (has 'url' key)
        if (map.containsKey("url") && !map.containsKey("_type") && !map.containsKey("storageKey")) {
            return CoercionResult.ok(map);
        }

        Map<String, Object> normalized = new LinkedHashMap<>();

        // Extract URL - try all known key names
        String url = extractStringKey(map, "url", "file_url", "href", "src", "link");

        // FileRef format: {_type:"file", path:"s3/key", name, mimeType, size, id}
        // Build the opaque URL from the storage-row id (no tenant id / s3 key).
        if ("file".equals(map.get("_type"))) {
            String opaque = opaqueFileUrl(map);
            if (opaque != null) {
                url = opaque;
            }
        }

        // Generic upload format: {storageKey:"s3/key", url, fileName, mimeType, size, id}
        if (map.containsKey("storageKey") && (url == null || url.isEmpty())) {
            url = opaqueFileUrl(map);
        }

        if (url == null || url.isEmpty()) {
            return CoercionResult.withWarning(map, "File map has no recognizable URL field");
        }

        normalized.put("url", url);

        // Extract name - try all known key names
        String name = extractStringKey(map, "name", "file_name", "fileName", "filename");
        if (name != null) normalized.put("name", name);

        // Extract mimeType - try all known key names
        String mimeType = extractStringKey(map, "mimeType", "content_type", "contentType", "mime_type", "mime");
        if (mimeType != null) normalized.put("mimeType", mimeType);

        // Extract size - try all known key names
        Number size = extractNumberKey(map, "size", "file_size", "fileSize");
        if (size != null) normalized.put("size", size);

        // Preserve source_url if present (from DownloadFileOutputSchemaMapper)
        String sourceUrl = extractStringKey(map, "source_url", "sourceUrl");
        if (sourceUrl != null) normalized.put("source_url", sourceUrl);

        return CoercionResult.ok(normalized);
    }

    // ── IMAGE ────────────────────────────────────────────────────────────
    //
    // Normalizes to a URL string (ImageCell.tsx stores/displays URL directly).
    // Accepts same input formats as FILE, but extracts only the URL.

    private CoercionResult coerceImage(Object value) {
        if (value instanceof Map<?, ?> map) {
            return extractImageUrl(map);
        }
        if (!(value instanceof String str)) return CoercionResult.ok(value);

        String trimmed = str.trim();
        if (trimmed.isEmpty()) return CoercionResult.ok(null);

        // Try JSON parse to extract url field
        if (trimmed.startsWith("{")) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(trimmed, new TypeReference<>() {});
                return extractImageUrl(parsed);
            } catch (Exception ignored) {
            }
        }

        // Accept as URL string
        if (looksLikeUrl(trimmed)) return CoercionResult.ok(trimmed);

        return CoercionResult.withWarning(trimmed, "Value does not look like an image URL: '" + trimmed + "'");
    }

    /**
     * Extract a displayable URL from any known file map format.
     */
    private CoercionResult extractImageUrl(Map<?, ?> map) {
        // Direct url field
        String url = extractStringKey(map, "url", "file_url", "href", "src", "link");

        // FileRef / generic upload: build the opaque URL from the storage-row id (no tenant id / key)
        if ((url == null || url.isEmpty())
                && ("file".equals(map.get("_type")) || map.containsKey("storageKey"))) {
            url = opaqueFileUrl(map);
        }

        if (url != null && !url.isEmpty()) return CoercionResult.ok(url);
        return CoercionResult.withWarning(map, "Image map has no recognizable URL field");
    }

    // ── EMAIL ────────────────────────────────────────────────────────────

    private CoercionResult coerceEmail(Object value) {
        // File-like map in an email column is a mapping error - warn clearly
        if (value instanceof Map<?, ?> map && isFileLikeMap(map)) {
            String name = resolveFileName(map);
            return CoercionResult.failed("File reference found in EMAIL column"
                    + (name != null ? " (file: " + name + ")" : "") + " - check column mapping");
        }

        String str = value instanceof String s ? s : value.toString();
        String trimmed = str.trim().toLowerCase();
        if (trimmed.isEmpty()) return CoercionResult.ok(null);

        // Strip mailto: prefix
        if (trimmed.startsWith("mailto:")) {
            trimmed = trimmed.substring(7).trim();
        }

        // Extract email from "Display Name <email@example.com>" format
        Matcher displayNameMatcher = EMAIL_DISPLAY_NAME.matcher(trimmed);
        if (displayNameMatcher.matches()) {
            String extracted = displayNameMatcher.group(1).trim().toLowerCase();
            if (EMAIL_PATTERN.matcher(extracted).matches()) {
                return CoercionResult.ok(extracted);
            }
        }

        if (!EMAIL_PATTERN.matcher(trimmed).matches()) {
            return CoercionResult.withWarning(trimmed, "Value does not look like a valid email: '" + trimmed + "'");
        }
        return CoercionResult.ok(trimmed);
    }

    // ── PHONE ────────────────────────────────────────────────────────────

    private CoercionResult coercePhone(Object value) {
        // File-like map in a phone column is a mapping error
        if (value instanceof Map<?, ?> map && isFileLikeMap(map)) {
            String name = resolveFileName(map);
            return CoercionResult.failed("File reference found in PHONE column"
                    + (name != null ? " (file: " + name + ")" : "") + " - check column mapping");
        }

        String str;
        if (value instanceof Number num) {
            // Avoid scientific notation for large numbers
            str = Long.toString(num.longValue());
        } else {
            str = value.toString();
        }

        String trimmed = str.trim();
        if (trimmed.isEmpty()) return CoercionResult.ok(null);

        // Strip tel: prefix (URI scheme for phone)
        if (trimmed.toLowerCase().startsWith("tel:")) {
            trimmed = trimmed.substring(4).trim();
        }

        // Count actual digits
        long digitCount = DIGITS_PATTERN.matcher(trimmed).results().count();
        if (digitCount < 7) {
            return CoercionResult.withWarning(trimmed, "Phone number has fewer than 7 digits: '" + trimmed + "'");
        }
        return CoercionResult.ok(trimmed);
    }

    // ── URL ──────────────────────────────────────────────────────────────

    private CoercionResult coerceUrl(Object value) {
        // File-like map → extract URL
        if (value instanceof Map<?, ?> map && isFileLikeMap(map)) {
            String url = resolveFileUrl(map);
            if (url != null) return CoercionResult.ok(url);
        }

        String str;
        if (value instanceof String s) {
            str = s;
        } else if (value instanceof Map<?, ?> map) {
            // Non-file map - try extracting any url-like key
            String url = extractStringKey(map, "url", "href", "src", "link");
            if (url != null) return CoercionResult.ok(url);
            str = value.toString();
        } else {
            str = value.toString();
        }

        // Try JSON string containing file-like object
        String trimmed = str.trim();
        if (trimmed.isEmpty()) return CoercionResult.ok(null);
        if (trimmed.startsWith("{")) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(trimmed, new TypeReference<>() {});
                if (isFileLikeMap(parsed)) {
                    String url = resolveFileUrl(parsed);
                    if (url != null) return CoercionResult.ok(url);
                }
                // Not file-like but has url key
                String url = extractStringKey(parsed, "url", "href", "src", "link");
                if (url != null) return CoercionResult.ok(url);
            } catch (Exception ignored) {
            }
        }

        if (!trimmed.contains("://")) {
            trimmed = "https://" + trimmed;
        }

        try {
            URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            return CoercionResult.withWarning(trimmed, "Invalid URL: '" + trimmed + "'");
        }

        return CoercionResult.ok(trimmed);
    }

    // ── VECTOR ─────────────────────────────────────────────────────────

    private CoercionResult coerceVector(Object value, Map<String, Object> display) {
        if (value == null) return CoercionResult.ok(null);

        List<Number> vector;

        if (value instanceof List<?> list) {
            vector = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item instanceof Number n) {
                    vector.add(n);
                } else if (item instanceof String s) {
                    try {
                        vector.add(Double.parseDouble(s.trim()));
                    } catch (NumberFormatException e) {
                        return CoercionResult.failed("Vector element is not a number: '" + s + "'");
                    }
                } else {
                    return CoercionResult.failed("Vector element is not a number: " + item);
                }
            }
        } else if (value instanceof String str) {
            String trimmed = str.trim();
            if (trimmed.isEmpty()) return CoercionResult.ok(null);
            // Try parsing as JSON array
            if (trimmed.startsWith("[")) {
                try {
                    List<?> parsed = objectMapper.readValue(trimmed, List.class);
                    return coerceVector(parsed, display);
                } catch (Exception e) {
                    return CoercionResult.failed("Cannot parse vector from string: " + e.getMessage());
                }
            }
            return CoercionResult.failed("Vector must be an array of numbers, got string: '" + trimmed + "'");
        } else {
            return CoercionResult.failed("Vector must be an array of numbers, got: " + value.getClass().getSimpleName());
        }

        // Validate dimension if configured
        int expectedDimension = getIntFromDisplay(display, "dimension", -1);
        if (expectedDimension > 0 && vector.size() != expectedDimension) {
            return CoercionResult.failed("Vector dimension mismatch: expected " + expectedDimension + ", got " + vector.size());
        }

        if (vector.isEmpty()) {
            return CoercionResult.failed("Vector must not be empty");
        }

        // Convert to float array for storage
        float[] floats = new float[vector.size()];
        for (int i = 0; i < vector.size(); i++) {
            floats[i] = vector.get(i).floatValue();
        }

        return CoercionResult.ok(floats);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private CoercionResult coerceRangedInt(Object value, int min, int max, String typeName) {
        double d;
        if (value instanceof Number num) {
            d = num.doubleValue();
        } else {
            String str = value.toString().trim();
            if (str.isEmpty()) return CoercionResult.ok(null);
            try {
                d = Double.parseDouble(str);
            } catch (NumberFormatException e) {
                return CoercionResult.failed("Cannot parse as " + typeName + ": '" + str + "'");
            }
        }

        int rounded = safeRoundToInt(d);
        return clampWithWarning(rounded, min, max, typeName, null);
    }

    /**
     * Safely round a double to int without overflow.
     * Math.round() returns long, then we clamp to int range to prevent silent overflow.
     */
    private int safeRoundToInt(double d) {
        if (Double.isNaN(d)) return 0;
        if (d >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (d <= Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) Math.round(d);
    }

    private CoercionResult clampWithWarning(int value, int min, int max, String typeName, String extraWarning) {
        List<String> warnings = new ArrayList<>();
        if (extraWarning != null) warnings.add(extraWarning);

        int clamped = value;
        if (value < min) {
            clamped = min;
            warnings.add("Clamped " + typeName + " from " + value + " to minimum " + min);
        } else if (value > max) {
            clamped = max;
            warnings.add("Clamped " + typeName + " from " + value + " to maximum " + max);
        }
        return new CoercionResult(clamped, warnings);
    }

    private int getIntFromDisplay(Map<String, Object> display, String key, int defaultValue) {
        Object val = display.get(key);
        if (val instanceof Number num) return num.intValue();
        if (val instanceof String str) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private boolean looksLikeUrl(String str) {
        return str.startsWith("http://") || str.startsWith("https://") || str.startsWith("/")
                || str.startsWith("data:");
    }

    private String extractFilename(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path != null && path.contains("/")) {
                String name = path.substring(path.lastIndexOf('/') + 1);
                if (!name.isEmpty()) return name;
            }
        } catch (Exception ignored) {
        }
        return "file";
    }

    /**
     * Extract first non-null string value from a map by trying multiple key names.
     */
    private String extractStringKey(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object val = map.get(key);
            if (val instanceof String s && !s.isEmpty()) return s;
        }
        return null;
    }

    /**
     * Extract first non-null number value from a map by trying multiple key names.
     */
    private Number extractNumberKey(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object val = map.get(key);
            if (val instanceof Number n) return n;
        }
        return null;
    }

    /**
     * Opaque, relative file URL addressed by the {@code storage.storage} row UUID - never by the s3
     * key, so the cell URL leaks no tenant id. Relative on purpose: the data-table renderer injects
     * the session token via {@code buildAuthUrl}, which only stamps same-origin {@code /api/…} URLs.
     * Returns {@code null} for a legacy file map with no {@code id} (re-write the row to regenerate it).
     */
    private String opaqueFileUrl(Map<?, ?> map) {
        String id = extractStringKey(map, "id");
        return (id != null && !id.isEmpty())
                ? "/api/proxy/files/by-id/" + id + "/raw?disposition=inline"
                : null;
    }

    // ── File-like detection ─────────────────────────────────────────────
    //
    // A Map is "file-like" if it has any of these signatures:
    //   - FileRef:        {_type:"file", path, name, mimeType, size}
    //   - DB flattened:   {file_url, file_name, ...}
    //   - Generic upload: {storageKey, fileName, ...} or {url, name, mimeType}
    // When a file-like map lands in a non-FILE column (TEXT, URL, EMAIL...),
    // we extract the best available URL instead of doing a useless toString().

    /**
     * Check if a Map looks like a file reference (FileRef, DB flattened, generic upload, or canonical).
     */
    private boolean isFileLikeMap(Map<?, ?> map) {
        return "file".equals(map.get("_type"))
                || map.containsKey("file_url")
                || map.containsKey("storageKey")
                || (map.containsKey("url") && (map.containsKey("mimeType") || map.containsKey("name") || map.containsKey("fileName")));
    }

    /**
     * Extract the best displayable URL from any file-like map.
     * Builds a proxy URL from path/storageKey if no direct URL is available.
     * Returns null if no URL can be resolved.
     */
    private String resolveFileUrl(Map<?, ?> map) {
        // Direct URL fields
        String url = extractStringKey(map, "url", "file_url", "href", "src", "link");
        if (url != null && !url.isEmpty()) return url;

        // FileRef / generic upload: build the opaque URL from the storage-row id (no tenant id / key)
        if ("file".equals(map.get("_type")) || map.containsKey("storageKey")) {
            return opaqueFileUrl(map);
        }

        return null;
    }

    /**
     * Extract the file name from any file-like map.
     */
    private String resolveFileName(Map<?, ?> map) {
        return extractStringKey(map, "name", "file_name", "fileName", "filename");
    }
}
