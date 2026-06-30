package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.domain.ModelCategory;
import com.apimarketplace.agent.service.ModelCatalogService;
import com.apimarketplace.agent.service.ModelCatalogService.AvailableModel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Internal endpoint for LLM model info.
 * Returns yml defaults enriched with DB overrides (rankings, tiers, recommended, etc.).
 *
 * <p>Two shapes:
 * <ul>
 *   <li>{@code /models} - full nested catalog (providers → models → pricing/tier/...)
 *       used by the admin UI picker.</li>
 *   <li>{@code /models/flat} - flat list of {@link AvailableModel} records consumed
 *       by conversation-service to inject the catalog into the agent system prompt
 *       so LLMs never hallucinate disabled or deprecated model names.</li>
 * </ul>
 *
 * <p>Optional {@code ?category=<key>} query parameter (V156 - per-category
 * ranking + enabled): when supplied, the per-category sidecar overrides the
 * global ranking / enabled. Categories: {@code chat}, {@code browser_agent},
 * {@code image_generation}; forward-extensible to any
 * {@link ModelCategory#isValidShape(String)} key.
 */
@RestController
@RequiredArgsConstructor
public class AgentModelsController {

    private final ModelCatalogService modelCatalogService;

    @GetMapping("/api/internal/agent/models")
    public ResponseEntity<Map<String, Object>> getAvailableModels(
            @RequestParam(value = "category", required = false) String category,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId) {
        validateCategory(category);
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.ok(modelCatalogService.getPublicModelsForCategory(category));
        }
        return ResponseEntity.ok(modelCatalogService.getModelsForCategory(category, tenantId));
    }

    /**
     * Flat catalog for LLM prompt injection.
     *
     * <p>Returns every enabled (provider, modelId, tier) triple in display order.
     * The payload is intentionally tiny - no pricing, no rate limits - so the
     * caller can embed it verbatim in a system prompt without bloating the
     * context window. Catalog is platform-wide; conversation-service caches
     * the response for 90 s.
     */
    @GetMapping("/api/internal/agent/models/flat")
    public ResponseEntity<List<AvailableModel>> getAvailableModelsFlat(
            @RequestParam(value = "category", required = false) String category,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId) {
        validateCategory(category);
        return ResponseEntity.ok(modelCatalogService.listAvailableModels(category, tenantId));
    }

    private static void validateCategory(String category) {
        if (category != null && !ModelCategory.isValidShape(category)) {
            throw new IllegalArgumentException(
                    "Invalid category key '" + category + "' - must match ^[a-z][a-z0-9_]*$ (≤32 chars)");
        }
    }
}
