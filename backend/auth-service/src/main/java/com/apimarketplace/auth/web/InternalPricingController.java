package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.ModelPricing;
import com.apimarketplace.auth.service.ModelPricingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Internal endpoint exposing the full pricing snapshot to other services.
 *
 * <p>Consumed by:
 * <ul>
 *   <li>{@code agent-service} - caches rates locally so {@code AgentBudgetGuard} /
 *       {@code TenantBudgetGuard} can compute per-iteration cost projections without
 *       hitting auth-service on every check.</li>
 *   <li>{@code mcp/bridge} (Node) - caches rates so the JS budget guard mirrors the
 *       Java implementation byte-for-byte.</li>
 * </ul>
 *
 * <p>This is an internal route under {@code /api/internal/auth/...} - the gateway should
 * not expose it to public traffic. The default refresh cadence on the client side is
 * 5 minutes.</p>
 */
@RestController
@RequestMapping("/api/internal/auth/pricing")
public class InternalPricingController {

    private final ModelPricingService pricingService;

    public InternalPricingController(ModelPricingService pricingService) {
        this.pricingService = pricingService;
    }

    /**
     * Snapshot of all currently-active pricing rows.
     *
     * <p>Response shape:
     * <pre>
     * {
     *   "version": "2026-04-07T11:42:00Z",
     *   "rates": [
     *     { "provider": "openai", "model": "gpt-4o", "inputRate": 0.005, "outputRate": 0.015,
     *       "fixedCost": 0.0, "contextWindow": 128000, "maxOutputTokens": 16384 },
     *     ...
     *   ]
     * }
     * </pre>
     * Token rates are expressed per 1M tokens after the cloud LLM billing multiplier.
     * Unit-billed rows keep their stored credit price. All values are in the same
     * currency unit as the {@code subscription.remaining_credits} balance.
     * {@code contextWindow} +
     * {@code maxOutputTokens} (V162) drive {@code worstCaseSingleIter} in budget
     * guards; both may be {@code null} for legacy/unknown rows. Consumers must
     * tolerate missing fields (forward+backward compat tested by
     * {@code PricingSnapshotBackwardCompatTest}).</p>
     */
    @GetMapping("/snapshot")
    public ResponseEntity<Map<String, Object>> snapshot() {
        List<ModelPricing> all = pricingService.getAllActivePricing();
        List<Map<String, Object>> rates = all.stream()
            .map(this::toRateRow)
            .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", Instant.now().toString());
        body.put("rates", rates);
        return ResponseEntity.ok(body);
    }

    private Map<String, Object> toRateRow(ModelPricing p) {
        // HashMap (not Map.of) so null context_window / max_output_tokens serialize as
        // JSON null rather than throwing on .of(). Consumers null-check explicitly.
        Map<String, Object> row = new HashMap<>();
        row.put("provider", p.getProvider());
        row.put("model", p.getModel());
        row.put("inputRate", pricingService.applyCloudLlmBillingMultiplier(
                p.getProvider(), p.getModel(), p.getInputRate()));
        row.put("outputRate", pricingService.applyCloudLlmBillingMultiplier(
                p.getProvider(), p.getModel(), p.getOutputRate()));
        row.put("fixedCost", pricingService.applyCloudLlmBillingMultiplier(
                p.getProvider(), p.getModel(),
                p.getFixedCost() != null ? p.getFixedCost() : BigDecimal.ZERO));
        row.put("contextWindow", p.getContextWindow());
        row.put("maxOutputTokens", p.getMaxOutputTokens());
        return row;
    }
}
