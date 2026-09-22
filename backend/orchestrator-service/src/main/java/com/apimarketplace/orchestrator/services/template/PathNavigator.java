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

    // ========================================
    // Diagnostics: WHY a path resolved to null
    // ========================================

    /**
     * Mirrors {@link #getVariableValueFromMap} and reports why it would answer null.
     *
     * <p>Kept in this class, beside the resolver it mirrors and sharing its
     * {@code resolveSegment}, because a probe that disagrees with the resolver is a
     * new lie rather than a fix. {@code PathProbeAgreementTest} pins the two
     * together: a probe may never report MISSING for a path that resolves to a value.
     *
     * @param variablePath the reference as written, e.g. {@code trigger:form.output.task}
     * @param variables the evaluation context
     * @return where resolution stopped, or {@link PathProbe#resolved()} when every
     *         segment exists (the value may still be a real null)
     */
    @SuppressWarnings("unchecked")
    public PathProbe probeVariablePath(String variablePath, Map<String, Object> variables) {
        if (variablePath == null || variablePath.isEmpty()) {
            return PathProbe.resolved();
        }
        if (variables == null) {
            return PathProbe.missingRoot(rootSegmentOf(variablePath));
        }

        if (!variablePath.contains(".")) {
            return containsBaseKey(variables, variablePath)
                    ? PathProbe.resolved()
                    : PathProbe.missingRoot(variablePath);
        }

        String[] parts = variablePath.split("\\.", 2);
        String baseKey = parts[0];
        String path = parts[1];

        if (!containsBaseKey(variables, baseKey)) {
            return PathProbe.missingRoot(baseKey);
        }

        Object baseValue = variables.get(baseKey);
        if (baseValue == null) {
            baseValue = variables.get(baseKey.toLowerCase(Locale.ROOT));
        }

        if (!(baseValue instanceof Map)) {
            // getVariableValueFromMap answers null for any non-Map base with a path left.
            return PathProbe.missingSegment(baseKey, rootSegmentOf(path));
        }

        return probeNested((Map<String, Object>) baseValue, path, baseKey);
    }

    /**
     * Mirrors {@link #getNestedValueFromMap}, including its implicit hop into an
     * {@code output} sub-map, and reports the first segment that does not exist.
     */
    @SuppressWarnings("unchecked")
    private PathProbe probeNested(Map<String, Object> map, String path, String prefix) {
        String[] parts = path.split("\\.", 2);
        String segment = parts[0];

        Object value = resolveSegment(map, segment);
        boolean present = containsSegment(map, segment);

        // Same implicit "output" wrapper hop the resolver takes, on the same condition.
        if (value == null && map.containsKey("output") && map.get("output") instanceof Map) {
            Map<String, Object> output = (Map<String, Object>) map.get("output");
            value = resolveSegment(output, segment);
            present = present || containsSegment(output, segment);
        }

        if (!present) {
            return PathProbe.missingSegment(prefix, segment);
        }

        if (parts.length == 1) {
            return PathProbe.resolved();
        }

        if (value instanceof Map) {
            return probeNested((Map<String, Object>) value, parts[1], prefix + "." + segment);
        }

        // The resolver answers null here: there is more path but nothing left to walk.
        return PathProbe.missingSegment(prefix + "." + segment, rootSegmentOf(parts[1]));
    }

    /** Base-key presence, with the same lowercase fallback the resolver applies. */
    private boolean containsBaseKey(Map<String, Object> variables, String baseKey) {
        return variables.containsKey(baseKey)
                || variables.containsKey(baseKey.toLowerCase(Locale.ROOT));
    }

    /** Segment presence, honouring the {@code key[N]} form {@code resolveSegment} accepts. */
    private boolean containsSegment(Map<String, Object> map, String segment) {
        Matcher m = ARRAY_ACCESS.matcher(segment);
        if (m.matches()) {
            Object value = map.get(m.group(1));
            int index = Integer.parseInt(m.group(2));
            return value instanceof List<?> list && index >= 0 && index < list.size();
        }
        return map.containsKey(segment);
    }

    private String rootSegmentOf(String path) {
        int dot = path.indexOf('.');
        return dot < 0 ? path : path.substring(0, dot);
    }
}
