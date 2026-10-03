package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSessionStore;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates sticky notes: canvas annotations that explain the workflow to the person reading it.
 *
 * <p>A note never runs and is never connected. It may be ATTACHED to one node
 * ({@code attachedTo} = that node's id): the canvas then places it next to that node when it
 * has no position of its own, shows it when the node is focused, and the note follows the
 * node through a rename and is removed with it.
 *
 * <p>Parameters (inside {@code params}, or at the root):
 * <ul>
 *   <li>{@code text} (required) - what the reader should understand. Alias {@code content}.</li>
 *   <li>{@code attached_to} (optional) - label or id of the node the note explains.
 *       Alias {@code attachedTo}.</li>
 *   <li>{@code color} (optional) - one of the canvas palette: yellow, blue, green, pink, purple,
 *       orange (a name, or the palette's background hex). Without it the note takes the palette
 *       colour after the previous note's, so the notes alternate.</li>
 * </ul>
 * The label is optional: notes are read for their text, so one is generated when omitted.
 */
@Component
@RequiredArgsConstructor
public class NoteCreator extends CreatorBase {

    private static final String EXAMPLE = "workflow(action='add_node', type='note', "
            + "params={text: 'Skips emails already processed: their id is kept in the Seen table.', "
            + "attached_to: 'Check Seen'})";

    /** The canvas note palette (NoteNode NOTE_COLORS): name, background, border, text. */
    private static final List<String[]> PALETTE = List.of(
            new String[]{"yellow", "#fef3c7", "#fbbf24", "#92400e"},
            new String[]{"blue", "#dbeafe", "#3b82f6", "#1e40af"},
            new String[]{"green", "#d1fae5", "#10b981", "#065f46"},
            new String[]{"pink", "#fce7f3", "#ec4899", "#831843"},
            new String[]{"purple", "#e9d5ff", "#a855f7", "#6b21a8"},
            new String[]{"orange", "#fed7aa", "#f97316", "#9a3412"});

    private final WorkflowBuilderSessionStore sessionStore;

    /**
     * Colour a plan note the way the canvas paints one:
     * <ul>
     *   <li>a palette name or background hex ({@code pink}, {@code #FCE7F3}) becomes the palette's
     *       full swatch (background, border, text), as add_node does;</li>
     *   <li>a note with no colour at all takes the palette colour after the previous note's, so
     *       the notes an agent writes alternate instead of all coming out yellow;</li>
     *   <li>any other colouring (a canvas-saved note, or a border or text colour set by hand) is
     *       left exactly as it is.</li>
     * </ul>
     */
    public static void applyNextColor(Map<String, Object> note, List<Map<String, Object>> previousNotes) {
        Object color = note.get("color");
        if (color instanceof String c && !c.isBlank()) {
            String[] swatch = swatchFor(c);
            // A palette NAME is not a CSS colour the canvas can paint with its border; a hex is
            // completed only where the border and text were left out, never overwritten.
            if (swatch != null && (swatch[0].equalsIgnoreCase(c.trim()) || !hasAnyKey(note, "borderColor", "textColor"))) {
                applySwatch(note, swatch);
            }
            return;
        }
        if (hasAnyKey(note, "borderColor", "textColor")) return;
        int next = 0;
        for (int i = previousNotes.size() - 1; i >= 0; i--) {
            Object previous = previousNotes.get(i).get("color");
            int index = paletteIndexOf(previous instanceof String p ? p : null);
            if (index >= 0) {
                next = (index + 1) % PALETTE.size();
                break;
            }
        }
        applySwatch(note, PALETTE.get(next));
    }

    private static int paletteIndexOf(String color) {
        if (color == null) return -1;
        for (int i = 0; i < PALETTE.size(); i++) {
            if (PALETTE.get(i)[1].equalsIgnoreCase(color.trim())) return i;
        }
        return -1;
    }

    /** The palette swatch a name or background hex names, case and spaces ignored; null if none. */
    static String[] swatchFor(String color) {
        String wanted = color.trim();
        return PALETTE.stream()
                .filter(p -> p[0].equalsIgnoreCase(wanted) || p[1].equalsIgnoreCase(wanted))
                .findFirst().orElse(null);
    }

    private static boolean hasAnyKey(Map<String, Object> note, String... keys) {
        for (String key : keys) {
            if (note.get(key) instanceof String v && !v.isBlank()) return true;
        }
        return false;
    }

    private static void applySwatch(Map<String, Object> note, String[] swatch) {
        note.put("color", swatch[1]);
        note.put("borderColor", swatch[2]);
        note.put("textColor", swatch[3]);
    }

    public ToolExecutionResult executeAddNote(WorkflowBuilderSession session, Map<String, Object> parameters) {
        Map<String, Object> params = extractParams(parameters);

        String text = firstNonBlank(getString(params, "text", "content"), getString(parameters, "text", "content"));
        if (text == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                    "A note needs 'text': what the reader should understand. Example: " + EXAMPLE);
        }

        String anchorRef = firstNonBlank(getString(params, "attached_to", "attachedTo"),
                getString(parameters, "attached_to", "attachedTo"));
        String anchorId = null;
        if (anchorRef != null) {
            anchorId = session.resolveNoteAnchor(anchorRef);
            if (anchorId == null) {
                return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND,
                        "attached_to '" + anchorRef + "' is not a node of this workflow (a note can only be "
                        + "attached to a trigger, step, agent, control, table or interface node). Available: "
                        + session.getAllNodeIds().stream().filter(id -> !LabelNormalizer.isNoteKey(id)).toList());
            }
        }

        String label = getLabel(parameters);
        if (label == null) {
            label = generateLabel(session, anchorId);
        }
        String nodeId = LabelNormalizer.noteKey(label);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("type", "note");
        node.put("label", label);
        node.put("text", text);
        if (anchorId != null) node.put(WorkflowBuilderSession.NOTE_ANCHOR_KEY, anchorId);
        String color = firstNonBlank(getString(params, "color"), getString(parameters, "color"));
        if (color != null) {
            String[] swatch = swatchFor(color);
            if (swatch == null) {
                return ToolExecutionResult.failure(ToolErrorCode.INVALID_ENUM_VALUE, "color must be one of: "
                        + String.join(", ", PALETTE.stream().map(p -> p[0]).toList()) + ". Got: '" + color + "'");
            }
            applySwatch(node, swatch);
        } else {
            applyNextColor(node, session.getNotes());
        }

        // Not the last added node: that one is what the next connect_after defaults to in the
        // builder's guidance, and nothing can follow a note.
        session.getNotes().add(node);
        session.recordAction(NodeType.NOTE.getActionName(), nodeId, NodeType.NOTE.getPrefix(), new LinkedHashMap<>(node));
        sessionStore.save(session);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        response.put("node_type", "note");
        response.put("node_id", nodeId);
        response.put("label", label);
        response.put("attached_to", anchorId != null ? anchorId : "(none: a free note about the whole workflow)");
        response.put("color", node.get("color"));
        response.put("note", "Annotation only: never runs, needs no connection. "
                + (anchorId != null
                    ? "The canvas places it next to " + anchorId + " and shows it when that node is focused; "
                        + "it is renamed and removed together with that node."
                    : "The canvas places it above the graph."));
        return ToolExecutionResult.success(response);
    }

    /** "About Check Seen" for an attached note, "Note 1".."Note N" otherwise; first label not taken. */
    private String generateLabel(WorkflowBuilderSession session, String anchorId) {
        String base = "Note";
        if (anchorId != null) {
            String anchorLabel = session.findNode(anchorId)
                    .map(n -> n.get("label") instanceof String s ? s : null)
                    .orElse(null);
            if (anchorLabel != null) base = "About " + anchorLabel;
        }
        String candidate = anchorId != null ? base : base + " 1";
        for (int i = 2; session.validateUniqueLabel(candidate, LabelNormalizer.PREFIX_NOTE) != null; i++) {
            candidate = base + " " + i;
        }
        return candidate;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null ? a : b;
    }
}
