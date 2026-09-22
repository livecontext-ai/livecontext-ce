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
 * global ranking / enabled. Categories with a reader: {@code chat} and
 * {@code browser_agent}. The retired {@code <format>_generation} keys are still
 * accepted so stored rows stay reachable; forward-extensible to any
 * {@link ModelCategory#isValidShape(String)} key.
 */
@RestController
@RequiredArgsConstructor
public class AgentModelsController {

    private final ModelCatalogService modelCatalogService;

    @GetMapping("/api/internal/agent/models")
    public ResponseEntity<Map<String, Object>> getAvailableModels(
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = com.apimarketplace.agent.client.AgentClient.PUBLIC_READ_PARAM, defaultValue = "false") boolean publicRead,
            @RequestParam(value = com.apimarketplace.agent.client.AgentClient.HIDE_BRIDGES_PARAM, defaultValue = "false") boolean hideBridges,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId) {
        validateCategory(category);
        if (publicRead) {
            // The caller DECLARED itself a public read; it is never inferred here. A missing
            // X-User-ID does not mean "public": OrgContextHeaderForwarder can only copy that
            // header off a bound servlet request, so every internal call made from a scheduler, a
            // trigger or any @Async hop arrives without it too. Trimming on that signal would have
            // made ModelCatalogEnricher - whose provider.enum NodeParamsValidator enforces at
            // WRITE time - see a different catalogue depending on which thread asked, so a
            // classify node on a bridge would save from a request and fail from a schedule.
            //
            // Every other consumer of this shape therefore gets it whole: the enricher,
            // SmartDefaultsEngine, ChatDispatchService, and the admin panel that creates the
            // execution links, which would otherwise lose the very providers those links point at.
            return ResponseEntity.ok(modelCatalogService.hideBridgeProviders(
                    modelCatalogService.getPublicModelsForCategory(category)));
        }
        Map<String, Object> catalog = modelCatalogService.getModelsForCategory(category, tenantId);
        if (hideBridges) {
            // A SIGNED-IN caller who is not a platform admin. Declared by the caller for the
            // same reason publicRead is: this endpoint also serves schedulers and @Async hops
            // that carry no role header, and inferring "not an admin" from a missing header
            // would strip the bridges from the enricher and the execution-link panel too.
            catalog = modelCatalogService.hideBridgeProviders(catalog);
        }
        return ResponseEntity.ok(catalog);
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
