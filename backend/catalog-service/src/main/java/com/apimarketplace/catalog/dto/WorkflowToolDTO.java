package com.apimarketplace.catalog.dto;

import java.util.List;

/**
 * DTO optimisé pour les tools dans le workflow inspector (liste)
 * Contient uniquement les champs nécessaires à l'affichage de la liste
 */
public record WorkflowToolDTO(
    String slug,
    String name,
    String description,
    String method,
    String apiSlug,
    String iconSlug,
    String iconUrl,
    /**
     * Stable UUID of the api_tool row. The workflow inspector stores this on
     * the node when a tool is picked so downstream per-tool lookups (notably
     * the platform credential pricing toggle) can resolve against an id that
     * does not drift across catalog resyncs the way slugs can.
     */
    String toolId,
    /**
     * V166: per-endpoint OAuth scope requirements. Null when the tool has no
     * requirement (95% of catalog), so the JSON serialization stays compact for
     * the common case. The frontend lock-badge logic in InspectorMcpNode uses
     * this to decide whether to render a lock icon next to the tool name.
     */
    List<String> requiredScopes,
    /**
     * V166: unique-per-API integration name (matches auth.credentials.integration).
     * Required because iconSlug is brand-shared (e.g. googlecloud covers GCS,
     * Translate, Vision...) and cannot be used to look up the user's credential
     * for THIS specific API. Null when no platform credential name is set.
     */
    String integrationName
) {}

