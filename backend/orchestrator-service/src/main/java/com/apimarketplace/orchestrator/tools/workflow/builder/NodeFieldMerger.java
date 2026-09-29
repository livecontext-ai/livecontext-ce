package com.apimarketplace.orchestrator.tools.workflow.builder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Centralized merge logic for {@code workflow(action='modify')}.
 *
 * <p>Before this class existed, the modify action used a dumb apply loop
 * ({@code node.put(key, value)}) that REPLACED every field unconditionally.
 * This broke many node types: schedule triggers wiped untouched params,
 * decision nodes wiped untouched conditions, MCP nodes corrupted their
 * canonical {@code id}, etc. (See WorkflowBuilderModifier history.)
 *
 * <p>Now the modify path delegates to {@link #merge(Map, String, Object)}
 * which knows three merge strategies per field:
 *
 * <ul>
 *   <li><b>MERGE_MAP</b> - for map-shaped fields ({@code params},
 *       {@code actionMapping}, {@code variableMapping}, …): the new map is
 *       deep-merged into the existing map, preserving keys the LLM didn't
 *       touch. Nested maps recurse.</li>
 *   <li><b>MERGE_LIST_BY_LABEL</b> - for list-shaped fields keyed by a
 *       human-set {@code label} ({@code decisionConditions},
 *       {@code switchCases}, {@code classifyCategories}): items with a
 *       matching label are merged field-by-field IN PLACE; new labels are appended;
 *       existing items not in the incoming list are PRESERVED so the LLM
 *       can update one item without re-sending all of them. Positions never
 *       move, because port indexes (category_N, case_N) are positions.</li>
 *   <li><b>REPLACE</b> (default) - scalars and any field not registered
 *       above. The new value overwrites the old one. Explicit {@code null}
 *       deletes the field, mirroring the existing remove semantic.</li>
 * </ul>
 *
 * <p>The strategy is decided by the field NAME, not by the value type, so
 * the behavior is predictable from the LLM's point of view: the same
 * {@code params} key always merges, the same {@code decisionConditions}
 * key always merges-by-label, etc.
 */
public final class NodeFieldMerger {

    private NodeFieldMerger() {}

    /** Map-shaped fields that should be deep-merged with existing data. */
    public static final Set<String> MERGE_MAP_FIELDS = Set.of(
        "params",
        "actionMapping",
        "variableMapping",
        "metadata"
    );

    /**
     * List-shaped fields keyed by {@code label}. Items with the same label
     * are merged in place; new labels are appended; existing items not
     * referenced by the incoming list are preserved unchanged.
     *
     * <p>Note that {@code decisionConditions} is NOT in this set: its items
     * carry positional roles ({@code if}/{@code elseif}/{@code else}) and
     * merging by label could yield two {@code if} branches in the same
     * decision, which is invalid. Decision condition lists stay REPLACE.
     */
    public static final Set<String> MERGE_LIST_BY_LABEL_FIELDS = Set.of(
        "switchCases",
        "classifyCategories"
    );

    /**
     * Apply a single change to {@code node} using the right merge strategy
     * for {@code key}.
     *
     * @param node     the node map being modified (will be mutated)
     * @param key      the field name on the node
     * @param newValue the value the LLM wants to set; {@code null} deletes
     */
    @SuppressWarnings("unchecked")
    public static void merge(Map<String, Object> node, String key, Object newValue) {
        if (newValue == null) {
            // Explicit null = delete the field, matching the prior contract
            // for callers that wanted to clear a value.
            node.remove(key);
            return;
        }

        Object existing = node.get(key);

        // Strategy 1: deep merge for known map fields
        if (MERGE_MAP_FIELDS.contains(key)
                && existing instanceof Map
                && newValue instanceof Map) {
            node.put(key, deepMergeMaps(
                (Map<String, Object>) existing,
                (Map<String, Object>) newValue
            ));
            return;
        }

        // Strategy 2: merge by label for known list fields
        if (MERGE_LIST_BY_LABEL_FIELDS.contains(key)
                && existing instanceof List
                && newValue instanceof List) {
            node.put(key, mergeListByLabel(
                (List<Map<String, Object>>) existing,
                (List<Map<String, Object>>) newValue
            ));
            return;
        }

        // Strategy 3: replace (scalar, or unregistered field, or type mismatch)
        node.put(key, newValue);
    }

    /**
     * Deep-merge two maps. For each key in {@code incoming}:
     * <ul>
     *   <li>If both sides hold a Map, recurse.</li>
     *   <li>Otherwise, the incoming value replaces the existing one.</li>
     * </ul>
     * Keys in {@code existing} that are absent from {@code incoming} are
     * preserved unchanged.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepMergeMaps(
            Map<String, Object> existing, Map<String, Object> incoming) {
        Map<String, Object> result = new LinkedHashMap<>(existing);
        for (Map.Entry<String, Object> entry : incoming.entrySet()) {
            String k = entry.getKey();
            Object v = entry.getValue();
            if (v == null) {
                result.remove(k);
                continue;
            }
            Object prev = result.get(k);
            if (prev instanceof Map && v instanceof Map) {
                result.put(k, deepMergeMaps((Map<String, Object>) prev, (Map<String, Object>) v));
            } else {
                result.put(k, v);
            }
        }
        return result;
    }

    /**
     * Merge two lists of maps by their {@code label} field.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Walk {@code existing} in order: an item whose label the incoming
     *       list names is deep-merged IN PLACE (untouched fields like
     *       {@code id}, {@code type} are preserved); every other item is kept
     *       as it is. This is what makes "modify one item" work without
     *       forcing the LLM to re-send the whole list.</li>
     *   <li>Append the incoming items whose label is new, in the caller's
     *       order, BEFORE a trailing {@code default} item when there is one.</li>
     * </ol>
     *
     * <p>Order: positions never move. A classify node's {@code category_N}
     * and a switch node's {@code case_N} ports are indexes into this list and
     * the edges hang off them, so moving an item re-points a branch. A switch
     * keeps its default last, which is where the builder puts it and what
     * every port-naming helper assumes.
     */
    public static List<Map<String, Object>> mergeListByLabel(
            List<Map<String, Object>> existing, List<Map<String, Object>> incoming) {
        // Items keep their POSITION: a classify node's category_N and a switch node's case_N
        // ports are indexes into this list, and the edges hang off those ports. Putting the
        // edited items first (as this method once did) silently re-pointed every branch.
        Map<String, Map<String, Object>> incomingByLabel = new LinkedHashMap<>();
        for (Map<String, Object> incomingItem : incoming) {
            String label = labelOf(incomingItem);
            if (label != null) {
                incomingByLabel.put(label, incomingItem);
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        for (Map<String, Object> existingItem : existing) {
            String label = labelOf(existingItem);
            if (label != null && incomingByLabel.containsKey(label) && placed.add(label)) {
                Map<String, Object> merged = new LinkedHashMap<>(existingItem);
                merged.putAll(incomingByLabel.get(label));
                result.add(merged);
            } else {
                result.add(existingItem);
            }
        }
        // New items, in the order the caller gave them, before a trailing default.
        int insertAt = result.size();
        if (!result.isEmpty() && "default".equals(result.get(result.size() - 1).get("type"))) {
            insertAt = result.size() - 1;
        }
        List<Map<String, Object>> added = new ArrayList<>();
        for (Map<String, Object> incomingItem : incoming) {
            String label = labelOf(incomingItem);
            if (label == null || !placed.contains(label)) {
                added.add(new LinkedHashMap<>(incomingItem));
                if (label != null) placed.add(label);
            }
        }
        result.addAll(insertAt, added);
        return result;
    }

    private static String labelOf(Map<String, Object> item) {
        Object label = item.get("label");
        return label != null ? label.toString() : null;
    }
}
