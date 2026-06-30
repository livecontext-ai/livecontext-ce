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
 *       matching label are merged field-by-field; new labels are appended;
 *       existing items not in the incoming list are PRESERVED so the LLM
 *       can update one item without re-sending all of them.</li>
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
    private static final Set<String> MERGE_MAP_FIELDS = Set.of(
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
    private static final Set<String> MERGE_LIST_BY_LABEL_FIELDS = Set.of(
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
     *   <li>Build a label-keyed index of {@code existing}.</li>
     *   <li>Walk {@code incoming} in order: when a label matches, deep-merge
     *       the incoming item INTO the existing one (so untouched fields
     *       like {@code id}, {@code type} are preserved); otherwise append
     *       the new item.</li>
     *   <li>Append every existing item whose label was NOT touched by the
     *       incoming list. This is what makes "modify one item" work
     *       without forcing the LLM to re-send the whole list.</li>
     * </ol>
     *
     * <p>Order: incoming items first (in the LLM's order), then any
     * preserved-but-untouched existing items at the end. This keeps the
     * LLM's intent visible while still preserving the rest.
     */
    private static List<Map<String, Object>> mergeListByLabel(
            List<Map<String, Object>> existing, List<Map<String, Object>> incoming) {
        Map<String, Map<String, Object>> existingByLabel = new LinkedHashMap<>();
        for (Map<String, Object> item : existing) {
            String label = labelOf(item);
            if (label != null) existingByLabel.put(label, item);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> touchedLabels = new HashSet<>();
        for (Map<String, Object> incomingItem : incoming) {
            String label = labelOf(incomingItem);
            if (label != null && existingByLabel.containsKey(label)) {
                Map<String, Object> merged = new LinkedHashMap<>(existingByLabel.get(label));
                merged.putAll(incomingItem);
                result.add(merged);
                touchedLabels.add(label);
            } else {
                result.add(new LinkedHashMap<>(incomingItem));
                if (label != null) touchedLabels.add(label);
            }
        }

        // Preserve existing items the LLM didn't mention
        for (Map<String, Object> existingItem : existing) {
            String label = labelOf(existingItem);
            if (label != null && !touchedLabels.contains(label)) {
                result.add(existingItem);
            }
        }
        return result;
    }

    private static String labelOf(Map<String, Object> item) {
        Object label = item.get("label");
        return label != null ? label.toString() : null;
    }
}
