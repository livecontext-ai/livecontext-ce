package com.apimarketplace.catalog.dto;

import java.util.List;

/**
 * DTO optimisé pour les paramètres de tool dans le workflow inspector
 * Contient uniquement les champs nécessaires à l'affichage
 */
public record WorkflowParameterDTO(
    String name,
    String description,
    String dataType,
    Boolean isRequired,
    String parameterType, // body, path, query, header
    /**
     * Server-side default value declared in the API migration JSON. The inspector
     * pre-fills the field with this when no user expression is set. {@code null}
     * when the param has no documented default.
     */
    String defaultValue,
    /**
     * Closed enum of admissible scalar values declared in the API migration JSON.
     * Drives the inspector dropdown when non-empty. {@code null} when the param
     * accepts free input.
     */
    List<String> allowedValues,
    /**
     * Builder-only metadata from the param's JSONB {@code extras} column. Carries the Google Drive
     * picker hint ({@code {"picker":{"provider":"google-drive","mimeType":...}}}) that tells the
     * inspector to render a "Pick from Drive" field for a file-ID param (see DriveFileParamPolicy),
     * alongside any other extras (encoding, inlineBody). {@code null} for params without extras.
     */
    com.fasterxml.jackson.databind.JsonNode extras
) {
    /**
     * Back-compat 7-arg constructor (extras defaults to {@code null}) for call sites that do not
     * carry the {@code extras} metadata.
     */
    public WorkflowParameterDTO(
            String name,
            String description,
            String dataType,
            Boolean isRequired,
            String parameterType,
            String defaultValue,
            List<String> allowedValues) {
        this(name, description, dataType, isRequired, parameterType, defaultValue, allowedValues, null);
    }
}
