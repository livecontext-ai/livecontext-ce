package com.apimarketplace.catalog.dto;

/**
 * DTO optimisé pour les réponses de tool dans le workflow inspector
 * Contient uniquement les champs nécessaires à l'affichage
 */
public record WorkflowToolResponseDTO(
    java.util.UUID id,
    String name,
    String description,
    String schema,
    String example,
    String exampleJsonb,
    String format,
    Integer statusCode,
    Boolean isDefault
) {}

