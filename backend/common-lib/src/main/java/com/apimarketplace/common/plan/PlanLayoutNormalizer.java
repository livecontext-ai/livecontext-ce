package com.apimarketplace.common.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes canvas LAYOUT from a workflow plan so two plans can be compared on what
 * they DO rather than on where their nodes sit.
 *
 * <p>Why this exists: a node's {@code position} is written into the plan by the
 * builder, so nudging a node by two pixels - or an auto-layout pass that
 * re-centres the graph - makes the saved plan differ from the stored version.
 * The save path read that as "the plan changed" and minted a NEW VERSION; the
 * execution path then keyed its run on that new version and created a SECOND run
 * instead of accumulating an epoch into the live one. The observable symptom was
 * two runs of the same workflow, both stamped with the same version, neither
 * accumulating the other's epochs, for a workflow nobody had actually edited.
 *
 * <p>Layout is still SAVED - {@code workflows.plan} keeps the coordinates and the
 * canvas reloads exactly where the user left it. It simply stops being a reason
 * to fork the version history.
 *
 * <p><b>What counts as layout is decided by SHAPE, not by key name and not by a
 * list of node families.</b> Two traps this avoids:
 * <ul>
 *   <li>{@code position} is ALSO a legitimate third-party tool parameter (ClickUp
 *       task position, Notion/Miro block position, playlist position, ...) living
 *       under a node's {@code params}. Stripping by key name anywhere would read
 *       "move this task from 3 to 7" as canvas drift, skip the new version AND
 *       overwrite the current one - silent loss of a real edit. Only a NODE
 *       ENTRY's own field is considered, never anything nested inside it.</li>
 *   <li>A plan has seven node arrays today (triggers, mcps, agents, tables,
 *       cores, notes, interfaces). Enumerating them means the next node family
 *       silently keeps minting versions - which is exactly what happened when
 *       {@code tables} was left out. Any top-level list is scanned instead, and
 *       an entry is only treated as a canvas node when it holds a coordinate
 *       pair.</li>
 * </ul>
 *
 * <p>Layout is where a node SITS and how big it IS: both the coordinate and the
 * box a resize handle writes are dropped, since dragging a note corner forks the
 * version history exactly like dragging the note itself.</p>
 */
public final class PlanLayoutNormalizer {

    /** The layout field the builder stamps on a node entry. */
    private static final String POSITION_KEY = "position";

    /**
     * The BOX a node occupies, written by the resize handles: a note corner
     * ({@code width}/{@code height}), an interface preview corner
     * ({@code previewWidth}/{@code previewHeight}) and a data-input corner
     * ({@code dataInputWidth}/{@code dataInputHeight}). Dragging any of them is
     * canvas work, exactly like moving a node, and left out of this set it kept
     * minting a version per drag - the same fork with a different handle.
     *
     * <p>Only stripped from a NODE ENTRY's own fields and only when the value is a
     * Number, the same two guards {@code position} gets: a tool parameter named
     * {@code width} (image generation, video render, ...) lives under the entry's
     * {@code params} and is never reached.
     */
    private static final Set<String> SIZE_KEYS = Set.of(
            "width", "height", "previewWidth", "previewHeight", "dataInputWidth", "dataInputHeight");

    /**
     * The plan-level reading direction of the canvas ({@code "horizontal"} or
     * {@code "vertical"}). Switching it re-lays every node out and changes nothing the
     * workflow does, so it is layout exactly like the coordinates it comes with: counted
     * as a change, flipping the direction minted a version and forked the next run.
     */
    private static final String LAYOUT_DIRECTION_KEY = "layoutDirection";

    private PlanLayoutNormalizer() {}

    /**
     * Deep copy of {@code plan} with node-entry layout and the plan's reading direction
     * removed.
     *
     * <p>Null-safe (null in, null out) and non-mutating: the caller's plan is never
     * touched, so this is safe to use inside a comparison.
     */
    public static Map<String, Object> withoutLayout(Map<String, Object> plan) {
        if (plan == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : plan.entrySet()) {
            if (LAYOUT_DIRECTION_KEY.equals(entry.getKey())) continue;
            Object value = entry.getValue();
            out.put(entry.getKey(), value instanceof List<?> list ? stripNodeList(list) : value);
        }
        return out;
    }

    /**
     * Copy of a list with each node entry's coordinate pair dropped. Everything
     * else in the entry (params included) is carried over by reference: the result
     * is only ever read for comparison, and the caller's plan must not be mutated.
     */
    private static List<Object> stripNodeList(List<?> nodes) {
        List<Object> out = new ArrayList<>(nodes.size());
        for (Object node : nodes) {
            // The coordinate pair is what identifies a canvas node entry; only such an
            // entry gets its box dropped too, so a plain list of objects that happens
            // to carry a numeric `width` is left alone.
            if (node instanceof Map<?, ?> nodeMap && isCoordinatePair(nodeMap.get(POSITION_KEY))) {
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> field : nodeMap.entrySet()) {
                    String key = String.valueOf(field.getKey());
                    if (POSITION_KEY.equals(key)) continue;
                    if (SIZE_KEYS.contains(key) && field.getValue() instanceof Number) continue;
                    copy.put(key, field.getValue());
                }
                out.add(copy);
            } else {
                out.add(node);
            }
        }
        return out;
    }

    /**
     * True for the builder's {@code {x, y}} canvas coordinate. A tool parameter
     * named {@code position} is a number or a provider-specific object, so it does
     * not match and the entry is left untouched.
     */
    private static boolean isCoordinatePair(Object value) {
        if (!(value instanceof Map<?, ?> map)) return false;
        return map.get("x") instanceof Number && map.get("y") instanceof Number;
    }
}
