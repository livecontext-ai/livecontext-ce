package com.apimarketplace.orchestrator.tools.imagegeneration;

import com.apimarketplace.agent.imagegen.ImageProviderCatalog;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.orchestrator.domain.ToolRef;
import com.apimarketplace.orchestrator.services.impl.CatalogToolsGateway;
import com.apimarketplace.orchestrator.services.interfaces.ExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI image-generation provider - calls
 * {@code POST /v1/images/generations} <b>via the platform API catalog</b>
 * ({@code openai/openai-create-image}). The catalog handles credential lookup
 * (via {@code auth.platform_credentials} integration {@code llm_openai}),
 * the upstream HTTP call, response projection, and error mapping. This
 * class only adapts the typed {@link ImageProvider.Request} into the
 * catalog parameter map and parses the catalog response back into
 * {@link ImageProvider.Response}.
 *
 * <p><b>Why catalog instead of direct HTTP?</b> The catalog is the single
 * source of truth for which APIs the platform supports. Bypassing it for
 * billable image generation would mean:
 * <ul>
 *   <li>duplicating the endpoint definition (already in
 *       {@code scripts/api-migrations/openai.json:create_image}),</li>
 *   <li>duplicating credential resolution (the catalog reads
 *       {@code platform_credentials}; a direct call would need its own
 *       Spring config + env-var fallback),</li>
 *   <li>missing future updates (e.g. when an admin adds a new model to
 *       the catalog list, both surfaces need to know).</li>
 * </ul>
 * Per-image credit billing remains owned by {@link ImageGenerationModule}
 * - the catalog produces the response, the module counts images and
 * fires {@code consumeForImageGeneration}. The catalog's flat-amount
 * markup pipeline does not run for these calls (it only fires inside
 * {@code StepCompletionOrchestrator} workflow paths).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "image-generation.enabled", havingValue = "true", matchIfMissing = false)
public class OpenAIImageProvider implements ImageProvider {

    /** Catalog tool slug - DB stores tool_slug as "<api-slug>-<tool-name-kebab>". */
    private static final String TOOL_ID = "openai/openai-create-image";

    private final CatalogToolsGateway toolsGateway;

    public OpenAIImageProvider(CatalogToolsGateway toolsGateway) {
        this.toolsGateway = toolsGateway;
    }

    @Override
    public String providerKey() { return "openai"; }

    @Override
    public Response generate(Request request, ToolExecutionContext context) throws ImageGenerationException {
        if (!ImageProviderCatalog.OPENAI_MODEL_FAMILIES.contains(request.model())) {
            throw new ImageGenerationException(
                    ImageGenerationException.Kind.INVALID_REQUEST,
                    "Unknown OpenAI model: " + request.model() + ". Valid: " + ImageProviderCatalog.OPENAI_MODEL_FAMILIES);
        }

        // Build catalog params - must match openai.json:create_image schema.
        // output_format defaults to png so the response mime aligns with what
        // the frontend renders unless a future Request adds an explicit override.
        Map<String, Object> params = new HashMap<>();
        params.put("model", request.model());
        params.put("prompt", request.prompt());
        params.put("n", request.n());
        params.put("size", request.size());
        params.put("quality", request.quality());
        params.put("output_format", "png");

        log.debug("OpenAIImageProvider: catalog call tool={} model={} n={} quality={}",
                TOOL_ID, request.model(), request.n(), request.quality());

        // Forward billing identifiers from the agent's ToolExecutionContext so the
        // dispatcher can build idempotent sourceIds. Workflow-origin runs (with
        // __workflowRunId__) get skipped by the dispatcher - workflow billing fires
        // via StepCompletionOrchestrator instead.
        Map<String, Object> billingIds = extractBillingIdentifiers(context);

        ExecutionResult result;
        try {
            result = toolsGateway.executeTool(new ToolRef(TOOL_ID, 1), params, request.tenantId(), billingIds);
        } catch (Exception e) {
            throw new ImageGenerationException(
                    ImageGenerationException.Kind.UPSTREAM_FAILED,
                    "Catalog gateway failed for OpenAI image gen: " + e.getMessage(), e);
        }
        if (!result.ok()) {
            throw mapErrorToException(result);
        }
        return parseResponse(result.output());
    }

    /**
     * Maps catalog-layer failure into a typed image-gen exception. The
     * catalog flattens upstream HTTP status into {@code output.http_status}
     * and surfaces the upstream error message in {@code errors[].message}
     * - both are used to disambiguate auth/content-mod/rate-limit/other.
     */
    private ImageGenerationException mapErrorToException(ExecutionResult result) {
        Integer status = extractHttpStatus(result.output());
        String message = result.errors().isEmpty()
                ? "OpenAI catalog call failed"
                : String.valueOf(result.errors().get(0).getOrDefault("message", "unknown"));
        if (status != null) {
            if (status == 401 || status == 403) {
                return new ImageGenerationException(
                        ImageGenerationException.Kind.AUTH_FAILED,
                        "OpenAI rejected API key (HTTP " + status + ", platform_credential 'llm_openai' missing or invalid)");
            }
            if (status == 429) {
                return new ImageGenerationException(
                        ImageGenerationException.Kind.RATE_LIMITED,
                        "OpenAI rate limit / quota: " + message);
            }
            if (status == 400 && message.toLowerCase().contains("safety")) {
                return new ImageGenerationException(
                        ImageGenerationException.Kind.CONTENT_BLOCKED,
                        "OpenAI content policy rejected the prompt: " + message);
            }
            if (status >= 400 && status < 500) {
                return new ImageGenerationException(
                        ImageGenerationException.Kind.INVALID_REQUEST,
                        "OpenAI " + status + ": " + message);
            }
        }
        return new ImageGenerationException(
                ImageGenerationException.Kind.UPSTREAM_FAILED, message);
    }

    /**
     * Pulls chat-scope identifiers from the {@code ToolExecutionContext}
     * credentials map for the dispatcher to build deterministic sourceIds.
     * Returns an empty map if context is absent - dispatcher falls back to
     * UUID sourceIds (lossy idempotency).
     */
    private static Map<String, Object> extractBillingIdentifiers(ToolExecutionContext context) {
        Map<String, Object> ids = new HashMap<>();
        if (context != null && context.credentials() != null) {
            Map<String, Object> creds = context.credentials();
            for (String key : List.of("__streamId__", "__toolCallId__", "__workflowRunId__", "__callIndex__")) {
                Object v = creds.get(key);
                if (v != null) ids.put(key, v);
            }
        }
        // v3.0: do NOT opt out of catalog dehydration. Catalog uploads to
        // S3 + indexes the Files tab row + returns a FileRef Map at
        // data[0].b64_json. parseResponse() detects the Map and surfaces
        // the FileRef directly - same flow as GeminiImageProvider.
        return ids;
    }

    private static Integer extractHttpStatus(Map<String, Object> output) {
        if (output == null) return null;
        Object s = output.get("http_status");
        if (s instanceof Number n) return n.intValue();
        // CatalogToolsGateway also flattens metadata.status into top-level
        if (output.get("metadata") instanceof Map<?, ?> meta) {
            Object ms = ((Map<?, ?>) meta).get("status");
            if (ms instanceof Number mn) return mn.intValue();
        }
        return null;
    }

    /**
     * Parses the catalog-flattened response. {@code CatalogToolsGateway}
     * spreads the upstream OpenAI {@code {created, data:[{b64_json,
     * revised_prompt}]}} payload directly into {@code output} (preserving
     * the {@code data} key per
     * {@code openai.json:create_image.outputSchema}).
     */
    @SuppressWarnings("unchecked")
    private Response parseResponse(Map<String, Object> output) {
        if (output == null) {
            throw new ImageGenerationException(
                    ImageGenerationException.Kind.UPSTREAM_FAILED, "Empty output from OpenAI catalog call");
        }
        Object dataObj = output.get("data");
        if (!(dataObj instanceof List<?>)) {
            // Some catalog projections wrap the raw payload under "result"
            // when shape doesn't match outputSchema - fall back gracefully.
            Object resultObj = output.get("result");
            if (resultObj instanceof Map<?, ?> resultMap) {
                dataObj = ((Map<?, ?>) resultMap).get("data");
            }
        }
        if (!(dataObj instanceof List<?> dataList)) {
            throw new ImageGenerationException(
                    ImageGenerationException.Kind.UPSTREAM_FAILED,
                    "OpenAI response missing 'data' array");
        }
        List<GeneratedImage> images = new ArrayList<>();
        for (Object item : (List<Object>) dataList) {
            if (!(item instanceof Map<?, ?> m)) continue;
            Object b64 = m.get("b64_json");
            if (b64 == null) continue;
            Object revisedPrompt = m.get("revised_prompt");
            String revStr = revisedPrompt != null ? String.valueOf(revisedPrompt) : null;

            // v3.0: catalog dehydrator replaced b64_json with a FileRef Map.
            // Surface the Map directly. Same logic as GeminiImageProvider.
            if (b64 instanceof Map<?, ?> fileRefMap && "file".equals(fileRefMap.get("_type"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> fileRef = (Map<String, Object>) fileRefMap;
                images.add(GeneratedImage.ofFileRef(fileRef, revStr));
            } else {
                images.add(GeneratedImage.ofBase64(String.valueOf(b64), "image/png", revStr));
            }
        }
        return new Response(images);
    }
}
