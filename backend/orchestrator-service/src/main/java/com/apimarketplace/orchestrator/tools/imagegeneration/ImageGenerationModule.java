package com.apimarketplace.orchestrator.tools.imagegeneration;

import com.apimarketplace.agent.imagegen.ImageProviderCatalog;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.agent.tools.common.ToolModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Dispatcher for the {@code image_generation} tool. Implements only the
 * runtime path - definitions live on
 * {@link ImageGenerationToolsProvider} (matches WebSearch's split).
 *
 * <p><b>Billing contract</b> - bills {@code IMAGE_GENERATION} via the async
 * client, post-success only, on the {@code actualImageCount} returned by
 * the provider (not the requested {@code n}). Per-image content moderation
 * can drop images; the user is charged only for what arrived. The
 * pseudo-model billing key (e.g. {@code gpt-image-1.5-medium}) is resolved
 * from {@code (modelFamily, quality)} via
 * {@link ImageProviderCatalog#openAiBillingModel(String, String)} for OpenAI
 * and is the model name itself for Google (single quality tier per model).
 *
 * <p><b>Pre-flight gate</b> - {@link CreditConsumptionClient#hasPricing} is
 * called against the resolved billing key BEFORE hitting the upstream
 * provider; on miss the call is rejected with {@code QUOTA_EXCEEDED}.
 * Mirrors {@code WebSearchModule}.
 *
 * <p><b>API key resolution</b> - handled by the platform API catalog
 * (the providers call through {@code CatalogToolsGateway}, which reads
 * {@code auth.platform_credentials.integration_name='llm_<provider>'}).
 * The module no longer carries any credential lookup itself.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "image-generation.enabled", havingValue = "true", matchIfMissing = false)
public class ImageGenerationModule implements ToolModule {

    static final Set<String> HANDLED_ACTIONS = Set.of("generate");

    /** Default values for missing optional params. */
    static final String DEFAULT_PROVIDER = "openai";
    static final String DEFAULT_MODEL = "gpt-image-1.5";
    static final String DEFAULT_QUALITY = "medium";
    static final String DEFAULT_SIZE = "1024x1024";
    static final int DEFAULT_N = 1;
    static final int MAX_N = 10;

    private final List<ImageProvider> providers;
    private final CreditConsumptionClient creditClient;

    public ImageGenerationModule(List<ImageProvider> providers,
                                  CreditConsumptionClient creditClient) {
        this.providers = providers;
        this.creditClient = creditClient;
    }

    @Override
    public List<AgentToolDefinition> getToolDefinitions() {
        return List.of(); // Definitions managed by ImageGenerationToolsProvider
    }

    @Override
    public boolean canHandle(String action) {
        return HANDLED_ACTIONS.contains(action);
    }

    @Override
    public Optional<ToolExecutionResult> execute(String action, Map<String, Object> parameters,
                                                  String tenantId, ToolExecutionContext context) {
        if (!"generate".equals(action)) return Optional.empty();
        return Optional.of(executeGenerate(parameters, tenantId, context));
    }

    private ToolExecutionResult executeGenerate(Map<String, Object> parameters,
                                                 String tenantId,
                                                 ToolExecutionContext context) {
        // ── Param parsing & validation ──────────────────────────────────────
        String prompt = stringParam(parameters, "prompt");
        if (prompt == null || prompt.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                    "Parameter 'prompt' is required for action 'generate'");
        }

        String providerKey = stringParam(parameters, "provider", DEFAULT_PROVIDER).toLowerCase();
        String model = stringParam(parameters, "model", DEFAULT_MODEL);
        String quality = stringParam(parameters, "quality", DEFAULT_QUALITY).toLowerCase();
        String size = stringParam(parameters, "size", DEFAULT_SIZE);
        int n = intParam(parameters, "n", DEFAULT_N);
        if (n < 1 || n > MAX_N) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                    "Parameter 'n' must be between 1 and " + MAX_N + " (got " + n + ")");
        }

        // ── Resolve billing pseudo-model ────────────────────────────────────
        String billingModel = resolveBillingModel(providerKey, model, quality);
        if (billingModel == null) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                    "Unknown provider/model/quality combination: provider=" + providerKey
                            + ", model=" + model + ", quality=" + quality
                            + ". See action='help' for the catalog.");
        }

        // V148+ pre-flight gate is now handled by catalog-service's
        // CatalogToolBillingService.preflightReserve via the X-Lc-Billing-Scope-*
        // headers set by CatalogExecuteModule. The legacy hasPricing(provider, model)
        // call against auth.model_pricing is replaced by resolveScopeMarkupRate
        // against the admin-published pricing_version. No pricing → reservation
        // fails closed there → 402 surfaced upstream by catalog. We don't pre-flight
        // here anymore.

        // ── Pick provider strategy ──────────────────────────────────────────
        ImageProvider provider = providers.stream()
                .filter(p -> providerKey.equals(p.providerKey()))
                .findFirst()
                .orElse(null);
        if (provider == null) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                    "No provider strategy registered for '" + providerKey + "'. "
                            + "Valid: " + providers.stream().map(ImageProvider::providerKey).toList());
        }

        // ── tenantId required for catalog credential lookup ─────────────────
        if (tenantId == null || tenantId.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                    "image_generation requires a tenantId in context (X-User-Id) for "
                            + "platform_credential lookup via the API catalog.");
        }

        // ── Call upstream via catalog ───────────────────────────────────────
        // Billing happens DOWNSTREAM in CatalogToolsGateway → CatalogBillingDispatcher
        // → ImageGenerationBillingStrategy. The strategy reads metadata.credentialSource
        // (BYOK vs platform) and the response shape (actualImageCount), then fires the
        // right consumeFor* call. The module just returns the LLM-visible result.
        ImageProvider.Response response;
        try {
            response = provider.generate(new ImageProvider.Request(
                    providerKey, model, prompt, n, quality, size, tenantId), context);
        } catch (ImageGenerationException e) {
            log.warn("image_generation failed: kind={} msg={}", e.getKind(), e.getMessage());
            return ToolExecutionResult.failure(toToolErrorCode(e.getKind()), e.getMessage());
        }

        int actualImageCount = response.images().size();
        if (actualImageCount == 0) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    "Provider returned no images (all rejected by content moderation?)");
        }

        // ── Build result ────────────────────────────────────────────────────
        return ToolExecutionResult.success(buildResultData(response, providerKey, billingModel, prompt, actualImageCount));
    }

    private static String resolveBillingModel(String provider, String model, String quality) {
        if ("openai".equals(provider)) {
            return ImageProviderCatalog.openAiBillingModel(model, quality).orElse(null);
        }
        if ("google".equals(provider)) {
            // Google: model IS the billing key (single quality tier per model).
            return ImageProviderCatalog.GOOGLE_MODELS.contains(model) ? model : null;
        }
        return null;
    }

    private static ToolErrorCode toToolErrorCode(ImageGenerationException.Kind kind) {
        return switch (kind) {
            case INVALID_REQUEST -> ToolErrorCode.INVALID_PARAMETER_VALUE;
            case AUTH_FAILED -> ToolErrorCode.QUOTA_EXCEEDED; // surfaces as "billing/auth not configured" to the agent
            case CONTENT_BLOCKED -> ToolErrorCode.VALIDATION_ERROR;
            case RATE_LIMITED -> ToolErrorCode.RATE_LIMITED;
            case UPSTREAM_FAILED -> ToolErrorCode.EXECUTION_FAILED;
        };
    }

    /** Whitelisted FileRef keys forwarded to the agent-visible result.
     *  We deliberately DROP the {@code url} field (60-min presigned TTL - agents
     *  may cache it and 403 later); downstream consumers (frontend chat card,
     *  Files tab, subsequent tool calls) re-resolve via the opaque, id-based file
     *  URL ({@code /api/proxy/files/by-id/{id}/raw}) built from {@code id}. */
    private static final List<String> AGENT_VISIBLE_FILEREF_KEYS =
            List.of("_type", "path", "name", "mimeType", "size", "source_path", "id");

    private static Map<String, Object> buildResultData(ImageProvider.Response response,
                                                       String provider, String billingModel,
                                                       String prompt, int actualImageCount) {
        List<Map<String, Object>> images = new ArrayList<>(response.images().size());
        for (ImageProvider.GeneratedImage img : response.images()) {
            Map<String, Object> imgMap = new LinkedHashMap<>();
            // v3.0 path: provider returned a FileRef Map (catalog dehydrator
            // produced it). Project the whitelisted FileRef keys so the
            // frontend ImageGenerationVisualizeCard + ToolResultFileRefPreviews
            // render via the proxy URL exactly like any other catalog FileRef.
            // The FileRef path also matches the storage.storage row's s3_key,
            // letting the chat-card click open the Files panel pre-focused.
            // Stamp persisted=true so the LLM and downstream tooling have the
            // same provenance signal as the legacy base64-strip path emits.
            if (img.fileRef() != null) {
                Map<String, Object> source = img.fileRef();
                for (String key : AGENT_VISIBLE_FILEREF_KEYS) {
                    Object v = source.get(key);
                    if (v != null) imgMap.put(key, v);
                }
                imgMap.put("persisted", true);
            } else if (img.base64() != null) {
                // Legacy / opt-out fallback: provider couldn't dehydrate
                // (e.g. workflow caller forced inlineBinaries=true). The
                // raw bytes are present and must reach downstream - the
                // ToolResultPersistEnricher strip path will replace them
                // with persisted=true once the interface row lands.
                imgMap.put("base64", img.base64());
                imgMap.put("mime_type", img.mimeType());
            }
            if (img.revisedPrompt() != null) {
                imgMap.put("revised_prompt", img.revisedPrompt());
            }
            images.add(imgMap);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("images", images);
        data.put("count", actualImageCount);
        data.put("provider", provider);
        data.put("billing_model", billingModel);
        data.put("prompt", prompt);
        return data;
    }

    // ── Param helpers ───────────────────────────────────────────────────────

    private static String stringParam(Map<String, Object> params, String key) {
        Object v = params.get(key);
        return v != null ? String.valueOf(v) : null;
    }

    private static String stringParam(Map<String, Object> params, String key, String defaultValue) {
        String v = stringParam(params, key);
        return (v == null || v.isBlank()) ? defaultValue : v;
    }

    private static int intParam(Map<String, Object> params, String key, int defaultValue) {
        Object v = params.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { }
        }
        return defaultValue;
    }
}
