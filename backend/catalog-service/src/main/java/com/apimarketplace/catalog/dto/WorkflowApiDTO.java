package com.apimarketplace.catalog.dto;

import java.util.List;

/**
 * DTO optimisé pour les APIs dans le workflow inspector
 * Contient uniquement les champs nécessaires à l'affichage
 */
public record WorkflowApiDTO(
    String slug,
    String apiName,
    String description,
    Integer toolsCount,
    String iconSlug,
    String iconUrl
) {}

