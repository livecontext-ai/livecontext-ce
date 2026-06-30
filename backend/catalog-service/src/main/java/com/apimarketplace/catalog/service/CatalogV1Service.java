package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.dto.IntentResolutionResponse;
import com.apimarketplace.catalog.domain.dto.ToolExecutionRequest;
import com.apimarketplace.catalog.domain.dto.ToolExecutionResponse;
import com.apimarketplace.catalog.domain.dto.ToolListResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Service v1 pour l'API Catalog
 * Gere les outils, l'execution et la resolution d'intentions
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CatalogV1Service {

    private final CatalogToolQueryService catalogToolQueryService;
    private final ToolExecutionManager toolExecutionManager;
    private final IntentResolutionManager intentResolutionManager;

    /**
     * Recupere la liste des outils disponibles
     */
    public ToolListResponse getTools(int limit, String category, String search, String userId, String orgId) {
        log.info("Listing catalog tools - limit={}, category={}, search={}", limit, category, search);
        return catalogToolQueryService.getTools(limit, category, search, userId);
    }

    /**
     * Execute un outil
     */
    public ToolExecutionResponse executeTool(String toolIdOrSlug,
                                             ToolExecutionRequest request,
                                             String userId,
                                             String orgId,
                                             String requestId) {
        log.info("Executing tool {} - userId={}, orgId={}, requestId={}", toolIdOrSlug, userId, orgId, requestId);
        return toolExecutionManager.executeTool(toolIdOrSlug, request, userId, orgId, requestId);
    }

    /**
     * Resout une intention vers des outils candidats
     */
    public IntentResolutionResponse resolveIntent(String query, int limit, String userId, String orgId) {
        log.info("Resolving intent - query={}, limit={}", query, limit);
        return intentResolutionManager.resolve(query, limit);
    }
}
