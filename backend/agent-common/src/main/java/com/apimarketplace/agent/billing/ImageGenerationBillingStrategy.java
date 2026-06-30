package com.apimarketplace.agent.billing;

import com.apimarketplace.agent.imagegen.ImageProviderCatalog;
import com.apimarketplace.common.billing.catalog.CatalogBillingContext;
import com.apimarketplace.common.billing.catalog.CatalogBillingDispatcher;
import com.apimarketplace.common.billing.catalog.CatalogBillingStrategy;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.credit.SourceIdBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Billing strategy for image-generation tool calls routed through the
 * catalog. Handles both providers in one strategy because the billing
 * formula is the same shape (per-image credit cost × actualImageCount,
 * resolved via the pseudo-model billing key); only the response parsing
 * differs.
 *
 * <p>Lives in {@code agent-common} (rather than orchestrator-service) so
 * the chat-agent path through {@code CatalogExecuteModule} (catalog-service
 * resident) can share the same dispatcher + strategy registry as the
 * orchestrator's {@code CatalogToolsGateway}. Both services depend on
 * {@code agent-common} → both auto-discover this Spring bean → billing is
 * truly centralized regardless of which surface kicks off the catalog
 * call.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>Platform-key: {@code consumeForImageGeneration} via async client
 *       - debits {@code input_rate × actualImageCount} from
 *       {@code auth.model_pricing} (see V141).</li>
 *   <li>User-key (BYOK): {@code consumeForImageGenerationByok} -
 *       writes a 0-amount ledger row with
 *       {@code sourceType='IMAGE_GENERATION_BYOK'} for audit. The user
 *       paid the upstream provider directly.</li>
 *   <li>Zero images returned: no row written (consistent with
 *       {@code consumeForImageGeneration} zero-count short-circuit).</li>
 * </ul>
 *
 * <p>The strategy is fail-safe: any unexpected response shape returns
 * a count of 0, which short-circuits to a no-op rather than billing
 * a wrong amount. The dispatcher swallows thrown exceptions anyway.
 *
 * <p>{@code @ConditionalOnBean(CatalogBillingDispatcher.class)} prevents
 * a dangling bean in services (e.g. agent-service) that depend on
 * agent-common but do not import {@code com.apimarketplace.common.billing}
 * and therefore have no dispatcher to consume the strategy. Without the
 * guard the strategy would still be instantiated there, wasting memory
 * and risking future uniqueness-validation failures unrelated to the
 * service's own concerns.
 */
@Slf4j
@Component
@ConditionalOnBean(CatalogBillingDispatcher.class)
public class ImageGenerationBillingStrategy implements CatalogBillingStrategy {

    /** Catalog tool slugs handled by this strategy. Format is "<api-slug>/<tool-slug>" with the
     *  kebab-prefixed tool_slug as stored in catalog.api_tools. */
    private static final Set<String> HANDLED = Set.of(
            "openai/openai-create-image",
            "google-gemini/google-gemini-generate-content"
    );

    private final CreditConsumptionClient creditClient;

    public ImageGenerationBillingStrategy(CreditConsumptionClient creditClient) {
        this.creditClient = creditClient;
    }

    @Override
    public Set<String> handledToolIds() {
        return HANDLED;
    }

    @Override
    public void bill(CatalogBillingContext ctx) {
        // ── Resolve provider + billing pseudo-model from request params ─────
        Map<String, Object> req = ctx.request();
        String provider = providerForToolId(ctx.toolId());
        String httpModel = stringOrNull(req.get("model")); // real upstream model (e.g. gpt-image-1.5)
        String quality = stringOrDefault(req.get("quality"), "medium");

        if (httpModel == null) {
            log.debug("[ImageGenerationBillingStrategy] No 'model' in request for toolId={} - skipping billing",
                    ctx.toolId());
            return;
        }

        // Gemini image models bill under their own model name (single quality tier);
        // OpenAI bills under a (modelFamily, quality) pseudo-model - both delegated to the catalog.
        String billingModel = resolveBillingModel(provider, httpModel, quality);
        if (billingModel == null) {
            log.debug("[ImageGenerationBillingStrategy] No billing model for ({}, {}, {}) - skipping",
                    provider, httpModel, quality);
            return;
        }

        // ── Count actually returned images (strategy is shape-aware) ────────
        int actualImageCount = countImages(provider, ctx.response());
        if (actualImageCount <= 0) {
            log.debug("[ImageGenerationBillingStrategy] 0 images in response for toolId={} - no-op", ctx.toolId());
            return;
        }

        // ── Build idempotent sourceId ───────────────────────────────────────
        String sourceId = resolveSourceId(ctx);

        // ── Dispatch by credentialSource ────────────────────────────────────
        // BYOK and platform paths share the wire format (auth-service controller
        // routes IMAGE_GENERATION vs IMAGE_GENERATION_BYOK to the right handler).
        String sourceType = ctx.isUserKey() ? "IMAGE_GENERATION_BYOK" : "IMAGE_GENERATION";
        log.debug("[ImageGenerationBillingStrategy] toolId={} provider={} billingModel={} count={} source={} sourceType={}",
                ctx.toolId(), provider, billingModel, actualImageCount, ctx.credentialSource(), sourceType);

        creditClient.consumeCreditsAsync(
                ctx.tenantId(), sourceType, sourceId,
                provider, billingModel, /* imageCount */ actualImageCount);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static String providerForToolId(String toolId) {
        if (toolId == null) return null;
        if (toolId.startsWith("openai/")) return "openai";
        if (toolId.startsWith("google-gemini/")) return "google";
        return null;
    }

    private static String resolveBillingModel(String provider, String httpModel, String quality) {
        if ("openai".equals(provider)) {
            return ImageProviderCatalog.openAiBillingModel(httpModel, quality).orElse(null);
        }
        if ("google".equals(provider)) {
            return ImageProviderCatalog.GOOGLE_MODELS.contains(httpModel) ? httpModel : null;
        }
        return null;
    }

    /**
     * Counts images in the catalog-flattened response. Handles both
     * provider shapes:
     * <ul>
     *   <li>OpenAI: {@code data: [{b64_json, ...}, ...]}</li>
     *   <li>Gemini: {@code candidates: [{content: {parts: [{inlineData: {data, ...}}]}}]}</li>
     * </ul>
     * Tolerates wrapped-under-{@code result} variants.
     */
    @SuppressWarnings("unchecked")
    private static int countImages(String provider, Map<String, Object> response) {
        if (response == null) return 0;

        if ("openai".equals(provider)) {
            Object data = response.get("data");
            if (!(data instanceof List<?>)) {
                Object wrapped = response.get("result");
                if (wrapped instanceof Map<?, ?> wrappedMap) data = wrappedMap.get("data");
            }
            if (!(data instanceof List<?> list)) return 0;
            int count = 0;
            for (Object item : list) {
                if (item instanceof Map<?, ?> m && m.get("b64_json") != null) count++;
            }
            return count;
        }

        if ("google".equals(provider)) {
            Object candidates = response.get("candidates");
            if (!(candidates instanceof List<?>)) {
                Object wrapped = response.get("result");
                if (wrapped instanceof Map<?, ?> wrappedMap) candidates = wrappedMap.get("candidates");
            }
            if (!(candidates instanceof List<?> list) || list.isEmpty()) return 0;
            // Gemini returns 1 image per call (the orchestrator providers loop for n>1
            // and accumulate; each catalog call here is for one image at a time).
            Object first = list.get(0);
            if (!(first instanceof Map<?, ?> candMap)) return 0;
            Object content = candMap.get("content");
            if (!(content instanceof Map<?, ?> contentMap)) return 0;
            Object parts = contentMap.get("parts");
            if (!(parts instanceof List<?> partsList)) return 0;
            for (Object part : partsList) {
                if (!(part instanceof Map<?, ?> partMap)) continue;
                Object inline = partMap.get("inlineData");
                if (inline == null) inline = partMap.get("inline_data");
                if (inline instanceof Map<?, ?> inlineMap && inlineMap.get("data") != null) {
                    return 1;
                }
            }
        }
        return 0;
    }

    /**
     * Idempotent sourceId - chat scope when credentials carry
     * {@code __streamId__}+{@code __toolCallId__}, otherwise UUID
     * fallback. Workflow scope is unreachable here (workflow-origin
     * calls are skipped in {@code CatalogBillingDispatcher#bill}).
     */
    private static String resolveSourceId(CatalogBillingContext ctx) {
        if (ctx.streamId() != null && ctx.toolCallId() != null) {
            return SourceIdBuilder.imageGenerationDebitChat(ctx.streamId(), ctx.toolCallId(), ctx.callIndex());
        }
        return SourceIdBuilder.IMAGE_GENERATION_PREFIX + ":FALLBACK:" + UUID.randomUUID();
    }

    private static String stringOrNull(Object v) {
        return v != null ? String.valueOf(v) : null;
    }

    private static String stringOrDefault(Object v, String def) {
        String s = stringOrNull(v);
        return (s == null || s.isBlank()) ? def : s;
    }
}
