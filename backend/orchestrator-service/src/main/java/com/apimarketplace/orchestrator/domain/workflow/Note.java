package com.apimarketplace.orchestrator.domain.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * Représente une note dans le plan de workflow
 * Les notes sont des nodes à part entière avec leurs propriétés visuelles
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
    Map<String, Object> position // { x: number, y: number }
) {
    // Constructeur canonique avec validation
    public Note {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Note id cannot be null or blank");
        }
        type = type != null ? type.trim().toLowerCase() : "note";
        // Les valeurs par défaut sont gérées dans le parsing (WorkflowPlan.parseNotes)
    }
}

