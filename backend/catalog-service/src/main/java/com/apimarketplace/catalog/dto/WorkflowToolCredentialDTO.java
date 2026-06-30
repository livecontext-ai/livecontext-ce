package com.apimarketplace.catalog.dto;

/**
 * DTO optimisé pour les credentials de tool dans le workflow inspector
 * Contient uniquement les champs nécessaires à l'affichage
 */
public record WorkflowToolCredentialDTO(
    String credentialName,
    Boolean isRequired,
    String usage,
    String condition,
    String metadata,
    // Détails du credential si disponible
    String displayName,
    String description,
    String credentialType,
    String authType,
    String testEndpoint,
    String documentationUrl,
    String iconUrl,
    String properties,
    String extends_
) {}

