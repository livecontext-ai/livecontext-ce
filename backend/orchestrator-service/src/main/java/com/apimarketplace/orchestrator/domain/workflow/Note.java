package com.apimarketplace.orchestrator.domain.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * Represents a sticky note in the workflow plan: a canvas annotation that never runs and is
 * never connected.
 *
 * <p>{@code attachedTo} is the id of the node the note explains (e.g. {@code core:check_seen}),
 * or null for a free note about the whole workflow. The canvas places an attached note without
 * a position next to its node and shows it when that node is focused.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Note(
    String id,
    String type,  // Always "note"
    String label,
    String text,
    String color,
    String borderColor,
    String textColor,
    Integer width,
    Integer height,
    Map<String, Object> position, // { x: number, y: number }, empty when the canvas places it
    String attachedTo
) {
    public Note {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Note id cannot be null or blank");
        }
        type = type != null ? type.trim().toLowerCase() : "note";
        attachedTo = attachedTo != null && !attachedTo.isBlank() ? attachedTo.trim() : null;
        // Defaults for the visual fields are applied in WorkflowPlanParser.parseNotes
    }

    /** A free note (attached to no node). */
    public Note(String id, String type, String label, String text, String color, String borderColor,
                String textColor, Integer width, Integer height, Map<String, Object> position) {
        this(id, type, label, text, color, borderColor, textColor, width, height, position, null);
    }
}
