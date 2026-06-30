package com.apimarketplace.orchestrator.services.template;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for navigating paths through nested objects (Maps).
 *
 * Supports:
 * - Simple paths: "name" -> map.get("name")
 * - Nested paths: "user.name" -> map.get("user").get("name")
 * - Output wrapper paths: "output.data" -> map.get("output").get("data")
 *
 * UNIFIED EXPRESSION PATTERN:
 * All node types use {{type:label.output.field}} format:
 * - {{trigger:webhook.output.user_id}}
 * - {{mcp:api_call.output.data}}
 * - {{agent:assistant.output.response}}
 * - {{core:decision.output.selected_branch}}
 *
 * The .output. segment explicitly navigates into the output wrapper.
 * For backwards compatibility, implicit output navigation is also supported
 * when a key is not found directly but exists in the "output" sub-map.
 */
@Service
public class PathNavigator {

    /** Pattern to detect array access: "key[0]" → group(1)="key", group(2)="0" */
    private static final Pattern ARRAY_ACCESS = Pattern.compile("^([^\\[]+)\\[(\\d+)\\]$");

    /**
     * Resolves a single path segment which may include array access (e.g., "edges[0]").
     * If the segment is plain (no brackets), returns map.get(key).
     * If the segment has [N], gets the value for the key and indexes into it if it's a List.
     */
    @SuppressWarnings("unchecked")
    private Object resolveSegment(Map<String, Object> map, String segment) {
        Matcher m = ARRAY_ACCESS.matcher(segment);
        if (m.matches()) {
            String key = m.group(1);
            int index = Integer.parseInt(m.group(2));
            Object value = map.get(key);
            if (value instanceof List<?> list) {
                return (index >= 0 && index < list.size()) ? list.get(index) : null;
            }
            return null;
        }
        return map.get(segment);
    }

    /**
     * Navigate a path through nested objects (Maps).
     *
     * @param root The root object to start navigation from
     * @param path The dot-separated path to navigate (e.g., "user.name")
     * @return The value at the end of the path, or null if not found
     */
    @SuppressWarnings("unchecked")
    public Object navigatePath(Object root, String path) {
        if (root == null || path == null || path.isEmpty()) {
            return root;
        }

        String[] parts = path.split("\\.");
        Object current = root;

        for (String part : parts) {
            if (current == null) {
                return null;
            }

            if (current instanceof Map) {
                current = resolveSegment((Map<String, Object>) current, part);
            } else {
                return null;
            }
        }

        return current;
    }

    /**
     * Navigate a path through nested Maps with output wrapper support.
     * Also tries to find the key in an "output" sub-map if not found directly.
     *
     * @param map The map to navigate
     * @param path The dot-separated path to navigate
     * @return The value at the end of the path, or null if not found
     */
    @SuppressWarnings("unchecked")
    public Object navigateMapPath(Map<String, Object> map, String path) {
        if (map == null || path == null || path.isEmpty()) {
            return map;
        }

        String[] parts = path.split("\\.", 2);
        String segment = parts[0];

        Object value = resolveSegment(map, segment);

        // Also try in "output" sub-map
        if (value == null && map.containsKey("output") && map.get("output") instanceof Map) {
            value = resolveSegment((Map<String, Object>) map.get("output"), segment);
        }

        if (parts.length == 1) {
            return value;
        }

        if (value instanceof Map) {
            return navigateMapPath((Map<String, Object>) value, parts[1]);
        }

        return null;
    }

    /**
     * Get variable value from a simple map with case-insensitive fallback.
     *
     * @param variablePath The variable path to resolve
     * @param variables The map of variable values
     * @return The resolved value, or null if not found
     */
    @SuppressWarnings("unchecked")
    public Object getVariableValueFromMap(String variablePath, Map<String, Object> variables) {
        if (!variablePath.contains(".")) {
            // Try exact match first
            Object value = variables.get(variablePath);
            if (value != null) return value;
            // Try lowercase
            return variables.get(variablePath.toLowerCase(Locale.ROOT));
        }

        String[] parts = variablePath.split("\\.", 2);
        String baseKey = parts[0];
        String path = parts[1];

        // Try exact key
        Object baseValue = variables.get(baseKey);
        if (baseValue == null) {
            baseValue = variables.get(baseKey.toLowerCase(Locale.ROOT));
        }

        if (baseValue instanceof Map) {
            return getNestedValueFromMap((Map<String, Object>) baseValue, path);
        }

        return null;
    }

    /**
     * Get nested value from a map with output wrapper support.
     *
     * @param map The map to search in
     * @param path The path to navigate
     * @return The value at the path, or null if not found
     */
    @SuppressWarnings("unchecked")
    public Object getNestedValueFromMap(Map<String, Object> map, String path) {
        String[] parts = path.split("\\.", 2);
        String segment = parts[0];

        Object value = resolveSegment(map, segment);

        // Also try in "output" sub-map
        if (value == null && map.containsKey("output") && map.get("output") instanceof Map) {
            value = resolveSegment((Map<String, Object>) map.get("output"), segment);
        }

        if (parts.length == 1) {
            return value;
        }

        if (value instanceof Map) {
            return getNestedValueFromMap((Map<String, Object>) value, parts[1]);
        }

        return null;
    }
}
