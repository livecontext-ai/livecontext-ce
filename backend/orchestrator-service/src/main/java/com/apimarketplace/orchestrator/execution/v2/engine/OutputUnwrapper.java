package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shared utility for unwrapping node output from the standard wrapper format.
 *
 * <p>Node outputs in the execution context are wrapped by {@code ExecutionContext.withResult()}
 * in a standard structure: {@code { "output": { actual data... } }}. This utility
 * provides methods to unwrap that structure to access the actual data.
 *
 * <p><b>Consolidation status (2026-05-14)</b>. Two distinct unwrap concerns live here:
 * <ol>
 *   <li><b>{@code {output:...}} envelope strip</b> - {@link #unwrapOutput},
 *       {@link #unwrapOutputWithBranchDetection}, {@link #unwrapOutputObject},
 *       {@link #unwrapForCodeNode}. Consolidated callers:
 *       {@code services.ReadyNodeCalculator}, {@code nodes.merge.Queue1To1Strategy},
 *       {@code nodes.merge.FirstAvailableStrategy}, {@code nodes.merge.CombineAllStrategy}.</li>
 *   <li><b>Map → array-bearing-key unwrap</b> - {@link #tryUnwrapToList}.
 *       Consolidated callers: {@code nodes.SplitNode.evaluateListExpression},
 *       {@code nodes.merge.Queue1To1Strategy.merge} (different fallback policy -
 *       see that call-site). Three sister sites still carry inline divergent copies
 *       and remain follow-up consolidation candidates (out of scope for the
 *       2026-05-14 prod fix; flagged here so a future change can reuse this helper):
 *       {@code nodes.merge.CombineAllStrategy.flattenAndAdd} (4 keys, wrong order),
 *       {@code nodes.CompareDatasetsNode.extractListFromObject} (6 keys incl. bogus
 *       "output"), {@code execution.v2.services.V2TriggerLoadingService.extractTriggerItems}
 *       (2 keys; trigger-payload scope, may legitimately stay separate).</li>
 * </ol>
 */
public final class OutputUnwrapper {

    private static final Logger logger = LoggerFactory.getLogger(OutputUnwrapper.class);

    private OutputUnwrapper() {
        // Utility class - prevent instantiation
    }

    /**
     * Unwraps the "output" wrapper added by {@code ExecutionContext.withResult()}.
     *
     * <p>Input structure: {@code { "output": { actual data... } }}
     * <p>Returns: {@code { actual data... }}
     *
     * <p>If the map does not have the wrapper structure (i.e., it doesn't have exactly
     * one key named "output" whose value is a Map), the original map is returned as-is.
     *
     * @param outputMap The potentially wrapped output map
     * @return The unwrapped map, or the original map if not wrapped
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> unwrapOutput(Map<String, Object> outputMap) {
        if (outputMap == null) {
            return null;
        }
        if (outputMap.containsKey("output") && outputMap.size() == 1) {
            Object inner = outputMap.get("output");
            if (inner instanceof Map) {
                return (Map<String, Object>) inner;
            }
        }
        return outputMap;
    }

    /**
     * Unwraps the "output" wrapper with additional branch detection logic.
     *
     * <p>This variant extends the basic unwrap with handling for decision/switch node outputs
     * where the {@code selected_branch_index} field may be nested inside the "output" wrapper.
     *
     * <p>Logic:
     * <ol>
     *   <li>If map has only "output" key with Map value -> unwrap (same as basic)</li>
     *   <li>If map has "output" key AND no "selected_branch_index" at root level,
     *       check if inner map has decision data (selected_branch_index or node_type)
     *       -> unwrap to inner</li>
     *   <li>Otherwise return original map</li>
     * </ol>
     *
     * <p>This is used by ReadyNodeCalculator to correctly read decision/switch node outputs
     * for branch selection during ready node calculation.
     *
     * @param outputMap The potentially wrapped output map
     * @return The unwrapped map with branch detection, or the original map if not wrapped
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> unwrapOutputWithBranchDetection(Map<String, Object> outputMap) {
        if (outputMap == null) {
            return null;
        }

        // Basic unwrap: single "output" key
        if (outputMap.size() == 1 && outputMap.containsKey("output")) {
            Object inner = outputMap.get("output");
            if (inner instanceof Map) {
                return (Map<String, Object>) inner;
            }
        }

        // DB-stored format: the wrapper has metadata keys like "resultStepId" alongside "output".
        // When step outputs are loaded from DB, they come wrapped as:
        //   { output: { actual node data... }, resultStepId: ..., statusValue: ..., ... }
        // Unwrap to get the actual node data for all node types (loop, decision, switch, etc.)
        if (outputMap.containsKey("output") && outputMap.containsKey("resultStepId")) {
            Object inner = outputMap.get("output");
            if (inner instanceof Map) {
                return (Map<String, Object>) inner;
            }
        }

        // Extended unwrap: "output" key present but branching keys not at root
        if (outputMap.containsKey("output") && !outputMap.containsKey(ExecutionMetadataKeys.SELECTED_BRANCH_INDEX)) {
            Object inner = outputMap.get("output");
            if (inner instanceof Map) {
                Map<String, Object> innerMap = (Map<String, Object>) inner;
                // Check if inner has decision/switch/loop/approval data
                if (innerMap.containsKey(ExecutionMetadataKeys.SELECTED_BRANCH_INDEX)
                        || innerMap.containsKey(ExecutionMetadataKeys.NODE_TYPE)
                        || innerMap.containsKey(ExecutionMetadataKeys.TERMINATED)
                        || innerMap.containsKey(ExecutionMetadataKeys.SELECTED_PORT)) {
                    return innerMap;
                }
            }
        }

        return outputMap;
    }

    /**
     * Unwraps the "output" wrapper from an Object (for merge strategies).
     *
     * <p>This variant accepts Object input (which may or may not be a Map) to support
     * merge strategies that deal with heterogeneous data types.
     *
     * <p>Input structure: {@code { "output": { actual data... } }}
     * <p>Returns: {@code { actual data... }}
     *
     * <p>If the input is not a Map, or does not have the wrapper structure, the original
     * object is returned as-is.
     *
     * @param data The potentially wrapped output data
     * @return The unwrapped data, or the original data if not wrapped
     */
    @SuppressWarnings("unchecked")
    public static Object unwrapOutputObject(Object data) {
        if (data instanceof Map) {
            Map<String, Object> mapData = (Map<String, Object>) data;
            if (mapData.containsKey("output") && mapData.size() == 1) {
                Object innerOutput = mapData.get("output");
                if (innerOutput != null) {
                    return innerOutput;
                }
            }
        }
        return data;
    }

    /**
     * Unwraps the "output" wrapper for code node $input injection.
     *
     * <p>Code nodes need direct access to step output fields (e.g., {@code $input.alias.items})
     * without the {@code .output} wrapper or metadata keys ({@code resultStepId}, etc.).
     * This method extracts the inner "output" map regardless of sibling keys.
     *
     * <p>If the value is not a Map or has no "output" key, it is returned as-is.
     *
     * @param data The step output value (potentially wrapped)
     * @return The unwrapped output data for code injection
     */
    @SuppressWarnings("unchecked")
    public static Object unwrapForCodeNode(Object data) {
        if (data instanceof Map<?, ?> map && map.containsKey("output")) {
            Object inner = map.get("output");
            if (inner != null) {
                return inner;
            }
        }
        return data;
    }

    /**
     * Ordered list of keys recognized as "array-bearing" inside a wrapper Map. Order is
     * specificity-first: less-ambiguous keys win when a Map has multiple matches. {@code data}
     * is intentionally last because it is the most overloaded across catalog tools (used both
     * as an array container and as a single-row envelope, e.g. Stripe {@code {data: {...}}}).
     *
     * <p>Sourced from cross-tool frequency analysis in {@code scripts/api-migrations/*.json}:
     * {@code data} 369, {@code items} 177, {@code results} 162, {@code values} 89,
     * {@code records} 30, {@code entries} 29, {@code edges} 16, {@code rows} 14, {@code hits} 14.
     */
    public static final List<String> ARRAY_BEARING_KEYS = List.of(
        "items", "records", "results", "dataset", "entries", "rows", "hits", "edges", "values", "data"
    );

    /**
     * Attempts to extract an iterable list from a wrapper Map (e.g. Apify's
     * {@code {items:[...], status, runId}}, Airtable's {@code {records:[...]}}, OpenAI's
     * {@code {data:[...]}}).
     *
     * <p>The intent is to absorb the 95% of catalog-tool outputs that wrap their dataset
     * one key deep, while leaving the door open for callers to fail loud when no recognized
     * key is present. Replaces an unprincipled silent "wrap whole Map as 1-item list" fallback
     * that masked the prod 2026-05-14 split-bypass bug (Instagram Profile Scraper).
     *
     * <p>Returns:
     * <ul>
     *   <li>{@code Optional.of(list)} if {@code data} is a List, Collection, or array.</li>
     *   <li>{@code Optional.of(list)} if {@code data} is a Map and one of {@link #ARRAY_BEARING_KEYS}
     *       maps to a List/Collection/array. Priority: first match in {@code ARRAY_BEARING_KEYS}
     *       order.</li>
     *   <li>{@code Optional.empty()} for everything else - Map with no recognized key, Map whose
     *       recognized-key value is itself a Map, primitive, null, etc. Callers decide whether
     *       to fail loud (Split) or fall back (Queue1To1Strategy.merge: add as single source item).</li>
     * </ul>
     *
     * <p>This DOES NOT recursively descend - a nested Map under a recognized key is rejected
     * (returns {@code empty}) on purpose: the caller likely needs the agent to point at the
     * correct deeper path explicitly, not have us guess.
     *
     * @param data The candidate iterable source
     * @return Optional list when extraction succeeded, empty otherwise
     */
    @SuppressWarnings("unchecked")
    public static Optional<List<Object>> tryUnwrapToList(Object data) {
        if (data == null) {
            return Optional.empty();
        }
        if (data instanceof List<?> list) {
            return Optional.of((List<Object>) list);
        }
        if (data instanceof java.util.Collection<?> coll) {
            return Optional.of(new ArrayList<>(coll));
        }
        if (data.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(data);
            List<Object> out = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                out.add(java.lang.reflect.Array.get(data, i));
            }
            return Optional.of(out);
        }
        if (data instanceof Map<?, ?> map) {
            for (String key : ARRAY_BEARING_KEYS) {
                if (!map.containsKey(key)) continue;
                Object candidate = map.get(key);
                if (candidate instanceof List<?> list) {
                    return Optional.of((List<Object>) list);
                }
                if (candidate instanceof java.util.Collection<?> coll) {
                    return Optional.of(new ArrayList<>(coll));
                }
                if (candidate != null && candidate.getClass().isArray()) {
                    int length = java.lang.reflect.Array.getLength(candidate);
                    List<Object> out = new ArrayList<>(length);
                    for (int i = 0; i < length; i++) {
                        out.add(java.lang.reflect.Array.get(candidate, i));
                    }
                    return Optional.of(out);
                }
                // Recognized key but value is not array-shaped - stop, do not try other keys.
                // Caller should fail loud rather than guess.
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Builds the loud-fail message when a split/iteration source resolves to something that is not
     * an iterable list (and {@link #tryUnwrapToList} returned empty). Lists the observed Map keys
     * (or the actual type for non-Maps) and points the agent at the canonical fix - a self-correction
     * path that needs no log access. Shared by {@code SplitNode} and {@code SplitNodeExecutor} so the
     * two split entry points cannot drift. {@code result} must be non-null (callers diagnose null
     * separately, since "resolved to null" warrants a different hint).
     *
     * @param result     the non-null, non-iterable evaluation result
     * @param expression  the source expression, echoed into the message
     * @return a human-readable diagnostic
     */
    public static String describeNonListShape(Object result, String expression) {
        if (result instanceof Map<?, ?> map) {
            String observedKeys = map.keySet().stream()
                .map(String::valueOf)
                .limit(20)
                .collect(java.util.stream.Collectors.joining(", "));
            return "Split expected an array, but `" + expression + "` resolved to a Map with keys ["
                + observedKeys + "]. None match known array-bearing keys ("
                + String.join(", ", ARRAY_BEARING_KEYS)
                + "). Adjust the reference to point at the array field directly, e.g. "
                + "`{{<predecessor>.output.items}}` or `{{<predecessor>.output.<your-array-key>}}`.";
        }
        return "Split expected an array, but `" + expression + "` resolved to "
            + result.getClass().getSimpleName()
            + ". Reference an array field on the predecessor output, e.g. "
            + "`{{<predecessor>.output.items}}`.";
    }
}
