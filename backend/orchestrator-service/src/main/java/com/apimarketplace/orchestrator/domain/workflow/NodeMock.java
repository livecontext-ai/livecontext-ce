package com.apimarketplace.orchestrator.domain.workflow;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Per-node mock definition - the optional {@code mock} block on a WorkflowPlan node
 * entry (mcps / tables / agents / cores / interfaces; triggers and notes are excluded -
 * a fake trigger payload is already covered by {@code trigger_payload} on manual fires).
 *
 * <p>A node carrying an enabled mock returns the configured result INSTEAD of executing
 * its real body: no HTTP call, no LLM call, no DB write, no signal wait. Substitution
 * happens above {@code ExecutionNode.execute()} (see {@code MockExecutionService}), so
 * ports, edges, persistence, schema mapping and run reports behave exactly as for a
 * real execution. Mocks apply to EDITOR runs only, and can be disabled per run
 * ({@code mockMode = "off"}) - production / pinned / trigger-dispatched runs never
 * apply them (see {@code MockRunGate}).
 *
 * <p>Sources:
 * <ul>
 *   <li>{@link #SOURCE_STATIC} (default) - {@code output} is returned verbatim as the
 *       node's output. Optional for port-selecting nodes (defaults to an empty object
 *       for pure routing mocks), required otherwise.</li>
 *   <li>{@link #SOURCE_CATALOG_EXAMPLE} - mcp catalog-tool nodes only. The tool's
 *       default example response is fetched from catalog-service at execution time and
 *       projected through the tool's output schema, so the mocked output is
 *       byte-shaped exactly like a real execution. {@code output} is forbidden
 *       (no ambiguity about which wins).</li>
 *   <li>{@link #SOURCE_ERROR} - the node is marked FAILED with {@code error.message}
 *       (and optional {@code error.output}), to exercise error branches, retry
 *       policies and continueOnFailure paths without a real failure.</li>
 * </ul>
 *
 * <p>{@code port} drives branch selection on port-selecting nodes (decision / switch /
 * option / approval cores and classify agents): the factory translates it into the
 * node's native routing output key ({@code selected_branch_index},
 * {@code selected_case_index}, {@code selected_choice_index}, {@code selected_port},
 * {@code selected_category_index}) via {@code ExecutionNode.portSelectionOutput}.
 *
 * <p>{@code durationMs} simulates the node's execution time: the engine waits that
 * long before serving the mock result, like the real call would (the step's measured
 * execution time reflects it). Valid with every source, capped at
 * {@link #MAX_DURATION_MS} (10 minutes). Absent or 0 = instant.
 *
 * <p>Like {@link NodePolicy}, parsing is lenient on shape (unknown keys ignored for
 * forward compatibility) but STRICT on values, with errors naming the offending node.
 * Node-type compatibility (which sections may carry which source / port) is validated
 * in {@code WorkflowPlanParser.parseNodeMocks}, where the node type is known.
 */
public record NodeMock(
        boolean enabled,
        String source,
        Map<String, Object> output,
        String port,
        MockError error,
        Long durationMs
) {

    /** JSON key of the mock block on a plan node entry. */
    public static final String JSON_KEY = "mock";

    /** Upper bound of {@code durationMs}: 10 minutes. */
    public static final long MAX_DURATION_MS = 600_000L;

    public static final String SOURCE_STATIC = "static";
    public static final String SOURCE_CATALOG_EXAMPLE = "catalog_example";
    public static final String SOURCE_ERROR = "error";

    private static final Set<String> VALID_SOURCES =
            Set.of(SOURCE_STATIC, SOURCE_CATALOG_EXAMPLE, SOURCE_ERROR);

    /** Simulated failure: {@code message} is the node's error text, {@code output} the optional failure output. */
    public record MockError(String message, Map<String, Object> output) {
        public MockError {
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("mock.error.message must be a non-blank string");
            }
            output = output == null ? Map.of() : Map.copyOf(output);
        }
    }

    public NodeMock {
        if (source == null || !VALID_SOURCES.contains(source)) {
            throw new IllegalArgumentException(
                    "mock.source must be one of " + VALID_SOURCES + " (got '" + source + "')");
        }
        if (durationMs != null && (durationMs < 0 || durationMs > MAX_DURATION_MS)) {
            throw new IllegalArgumentException(
                    "mock.durationMs must be between 0 and " + MAX_DURATION_MS
                            + " milliseconds (10 minutes max), got " + durationMs);
        }
        output = output == null ? null : Map.copyOf(output);
        if (SOURCE_ERROR.equals(source)) {
            if (error == null) {
                throw new IllegalArgumentException("mock.source='error' requires an error block with a message");
            }
            if (port != null) {
                throw new IllegalArgumentException("mock.port cannot be combined with source='error'");
            }
            if (output != null) {
                throw new IllegalArgumentException(
                        "mock.output cannot be combined with source='error' (use error.output for the failure output)");
            }
        } else {
            if (error != null) {
                throw new IllegalArgumentException(
                        "mock.error is only valid with source='error' (set source='error' or remove the error block)");
            }
            if (SOURCE_CATALOG_EXAMPLE.equals(source) && output != null) {
                throw new IllegalArgumentException(
                        "mock.output cannot be combined with source='catalog_example' (the catalog example IS the output; "
                                + "use source='static' to author a custom output)");
            }
        }
    }

    /** True when this mock should be applied (the block can be parked with enabled=false). */
    public boolean isEffective() {
        return enabled;
    }

    public boolean isStatic() {
        return SOURCE_STATIC.equals(source);
    }

    public boolean isCatalogExample() {
        return SOURCE_CATALOG_EXAMPLE.equals(source);
    }

    public boolean isError() {
        return SOURCE_ERROR.equals(source);
    }

    /** True when this mock simulates execution time (a strictly positive {@code durationMs}). */
    public boolean hasSimulatedDuration() {
        return durationMs != null && durationMs > 0;
    }

    /**
     * Parses a raw {@code mock} JSON block.
     *
     * <p>Shape-lenient, value-strict (the {@link NodePolicy#fromMap} discipline).
     * {@code source} is inferred when absent: an {@code error} block implies
     * {@code source='error'}; anything else defaults to {@code 'static'} - so the
     * common authoring forms ({@code {output: {...}}}, {@code {port: 'if'}},
     * {@code {error: {message: '...'}}}) need no explicit source.
     *
     * @param raw     the raw value under the {@code mock} key (expected Map)
     * @param nodeKey normalized node key for error messages (e.g. {@code mcp:send_email})
     * @return the parsed mock, or {@code null} when the block is absent or an empty object
     *         (an empty {@code mock: {}} is the documented "clear" form)
     * @throws IllegalArgumentException on invalid shape / values, naming the node
     */
    public static NodeMock fromMap(Object raw, String nodeKey) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                    "Invalid mock for node '" + nodeKey + "': expected an object, got "
                            + raw.getClass().getSimpleName());
        }
        if (map.isEmpty()) {
            return null;
        }
        try {
            boolean enabled = coerceBoolean(map.get("enabled"), "enabled", true);
            Map<String, Object> output = coerceObject(map.get("output"), "output");
            String port = coerceString(map.get("port"), "port");
            MockError error = parseError(map.get("error"));
            // duration_ms accepted as an alias: agents used to snake_case output
            // fields write it naturally, and silently ignoring it would make the
            // mock "work" with no delay.
            Long durationMs = coerceDurationMs(
                    map.containsKey("durationMs") ? map.get("durationMs") : map.get("duration_ms"));
            String source = coerceString(map.get("source"), "source");
            if (source == null) {
                source = error != null ? SOURCE_ERROR : SOURCE_STATIC;
            } else {
                source = source.trim().toLowerCase(Locale.ROOT);
            }
            return new NodeMock(enabled, source, output, port, error, durationMs);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid mock for node '" + nodeKey + "': " + e.getMessage(), e);
        }
    }

    private static MockError parseError(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("error must be an object with a message");
        }
        Object message = map.get("message");
        if (!(message instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException("error.message must be a non-blank string");
        }
        return new MockError(s, coerceObject(map.get("output"), "error.output"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> coerceObject(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException(field + " must be a JSON object (got "
                + value.getClass().getSimpleName() + ")");
    }

    private static Long coerceDurationMs(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (!Double.isNaN(d) && !Double.isInfinite(d)) {
                return Math.round(d); // fractional ms round (matches the builder's sanitize)
            }
        } else if (value instanceof String s && !s.isBlank()) {
            try {
                double d = Double.parseDouble(s.trim());
                if (!Double.isNaN(d) && !Double.isInfinite(d)) {
                    return Math.round(d);
                }
            } catch (NumberFormatException e) {
                // fall through to the shared error below
            }
        }
        throw new IllegalArgumentException(
                "durationMs must be a number of milliseconds (0 to " + MAX_DURATION_MS + ")");
    }

    private static String coerceString(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s.isBlank() ? null : s.trim();
        }
        throw new IllegalArgumentException(field + " must be a string");
    }

    private static boolean coerceBoolean(Object value, String field, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            if ("true".equalsIgnoreCase(s.trim())) return true;
            if ("false".equalsIgnoreCase(s.trim())) return false;
        }
        throw new IllegalArgumentException(field + " must be a boolean");
    }
}
