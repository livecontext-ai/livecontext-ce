package com.apimarketplace.orchestrator.services.streaming;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class StreamingEventUtils {

    private static final Map<String, String> STATUS_COUNT_ALIAS_MAP = Map.ofEntries(
        Map.entry("completed", "completed"),
        Map.entry("success", "completed"),
        Map.entry("successful", "completed"),
        Map.entry("done", "completed"),
        Map.entry("error", "failed"),
        Map.entry("failed", "failed"),
        Map.entry("failure", "failed"),
        Map.entry("skipped", "skipped"),
        Map.entry("skip", "skipped"),
        Map.entry("ignored", "skipped"),
        Map.entry("running", "running"),
        Map.entry("dispatched", "running"),
        Map.entry("processed", "processed"),
        Map.entry("total", "total")
    );

    private StreamingEventUtils() {
    }

    public static String mapToUIStatus(String status) {
        if (status == null) {
            return "pending";
        }
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "completed", "success", "done" -> "completed";
            case "running", "in_progress", "pending", "dispatched" -> "running";
            case "failure", "failed", "error", "timeout" -> "failed";
            case "skipped", "ignored", "cancelled", "canceled" -> "skipped";
            default -> status.toLowerCase(Locale.ROOT);
        };
    }

    public static Map<String, Object> canonicalizeStatusCountMap(Map<String, Object> rawCounts) {
        if (rawCounts == null || rawCounts.isEmpty()) {
            return null;
        }
        Map<String, Object> normalized = new HashMap<>();
        rawCounts.forEach((key, value) -> {
            if (key == null || !(value instanceof Number number)) {
                return;
            }
            String normalizedKey = normalizeStatusKey(key);
            if (normalizedKey != null) {
                normalized.put(normalizedKey, number.longValue());
            }
        });
        return normalized.isEmpty() ? null : normalized;
    }

    private static String normalizeStatusKey(String key) {
        if (key == null) {
            return null;
        }
        String trimmed = key.trim().toLowerCase(Locale.ROOT);
        if (STATUS_COUNT_ALIAS_MAP.containsKey(trimmed)) {
            return STATUS_COUNT_ALIAS_MAP.get(trimmed);
        }
        return trimmed;
    }
}
