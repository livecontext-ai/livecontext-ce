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
 * Google Gemini image-generation provider - calls
 * {@code POST /v1beta/models/{model}:generateContent} <b>via the platform
 * API catalog</b> ({@code google-gemini/google-gemini-generate-content}). Same rationale
 * as {@link OpenAIImageProvider}: catalog owns endpoint definition,
 * credential lookup, HTTP transport, and error mapping; this class only
 * adapts request/response shape.
 *
 * <p><b>n &gt; 1 handling</b> - Gemini's {@code generateContent} returns
 * one image per call. To honour {@code request.n()}, this provider issues
 * sequential catalog calls. A failure of any call surfaces immediately
 * (the module then bills based on {@code response.images.size()} -
 * partial success is intentionally handled by counting actual returns,
 * not by best-effort error swallowing).
 *
 * <p><b>Body shape</b> - unlike OpenAI's flat {@code {model, prompt, n,
 * size, quality}}, Gemini takes
 * {@code {contents:[{parts:[{text}]}], generationConfig:{responseModalities:["IMAGE"]}}}.
 * The model is in the path, not the body. The catalog tool definition
 * accepts both, and our params map below sets {@code model} (path
 * substitution) plus the body fields.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "image-generation.enabled", havingValue = "true", matchIfMissing = false)
public class GeminiImageProvider implements ImageProvider {

    /** Catalog tool slug - DB stores tool_slug as "<api-slug>-<tool-name-kebab>". */
    private static final String TOOL_ID = "google-gemini/google-gemini-generate-content";

    private final CatalogToolsGateway toolsGateway;

    public GeminiImageProvider(CatalogToolsGateway toolsGateway) {
        this.toolsGateway = toolsGateway;
    }

    @Override
    public String providerKey() { return "google"; }

    @Override
    public Response generate(Request request, ToolExecutionContext context) throws ImageGenerationException {
        if (!ImageProviderCatalog.GOOGLE_MODELS.contains(request.model())) {
            throw new ImageGenerationException(
                    ImageGenerationException.Kind.INVALID_REQUEST,
                    "Unknown Google model: " + request.model() + ". Valid: " + ImageProviderCatalog.GOOGLE_MODELS);
        }
        if (request.n() < 1) {
            throw new ImageGenerationException(
                    ImageGenerationException.Kind.INVALID_REQUEST, "n must be >= 1");
        }

        // Gemini returns 1 image per call → loop. Each iteration is an
        // independent catalog call (the dispatcher bills per call, so the
        // ledger ends up with n rows of imageCount=1 - semantically equivalent
        // to one row of imageCount=n for analytics + idempotent on retries).
        List<GeneratedImage> images = new ArrayList<>(request.n());
        for (int i = 0; i < request.n(); i++) {
            images.add(generateOne(request, context, i));
        }
        return new Response(images);
    }

    private GeneratedImage generateOne(Request request, ToolExecutionContext context, int index) {
        Map<String, Object> params = new HashMap<>();
        // Path param
        params.put("model", request.model());
        // Body params
        params.put("contents", List.of(Map.of(
                "parts", List.of(Map.of("text", request.prompt())))));
        params.put("generationConfig", Map.of(
                "responseModalities", List.of("IMAGE")));

        log.debug("GeminiImageProvider: catalog call tool={} model={} index={}", TOOL_ID, request.model(), index);

        Map<String, Object> billingIds = extractBillingIdentifiers(context, index);

        ExecutionResult result;
        try {
            result = toolsGateway.executeTool(new ToolRef(TOOL_ID, 1), params, request.tenantId(), billingIds);
        } catch (Exception e) {
            throw new ImageGenerationException(
                    ImageGenerationException.Kind.UPSTREAM_FAILED,
                    "Catalog gateway failed for Gemini image gen: " + e.getMessage(), e);
        }
        if (!result.ok()) {
            throw mapErrorToException(result);
        }
        return parseResponse(result.output());
    }

    private ImageGenerationException mapErrorToException(ExecutionResult result) {
        Integer status = extractHttpStatus(result.output());
        String message = result.errors().isEmpty()
                ? "Gemini catalog call failed"
                : String.valueOf(result.errors().get(0).getOrDefault("message", "unknown"));
        if (status != null) {
            if (status == 401 || status == 403) {
                return new ImageGenerationException(
                        ImageGenerationException.Kind.AUTH_FAILED,
                        "Gemini rejected API key (HTTP " + status + ", platform_credential 'llm_google' missing or invalid)");
            }
            if (status == 429) {
                return new ImageGenerationException(
                        ImageGenerationException.Kind.RATE_LIMITED,
                        "Gemini rate limit / quota: " + message);
            }
            if (status == 400 && message.toLowerCase().contains("safety")) {
                return new ImageGenerationException(
                        ImageGenerationException.Kind.CONTENT_BLOCKED,
                        "Gemini content policy rejected the prompt: " + message);
            }
        }
        return new ImageGenerationException(
                ImageGenerationException.Kind.UPSTREAM_FAILED, message);
    }

    /**
     * Pulls billing identifiers from the agent context, overriding
     * {@code __callIndex__} per loop iteration so the dispatcher gives
     * each Gemini sub-call its own idempotent sourceId.
     */
    private static Map<String, Object> extractBillingIdentifiers(ToolExecutionContext context, int index) {
        Map<String, Object> ids = new HashMap<>();
        if (context != null && context.credentials() != null) {
            Map<String, Object> creds = context.credentials();
            for (String key : List.of("__streamId__", "__toolCallId__", "__workflowRunId__")) {
                Object v = creds.get(key);
                if (v != null) ids.put(key, v);
            }
        }
        ids.put("__callIndex__", index);
        // v3.0: do NOT opt out of catalog dehydration. The catalog now
        // uploads to S3 + indexes a Files-tab row + returns a FileRef Map
        // in inlineData.data. parseResponse() detects the Map and surfaces
        // the FileRef directly to GeneratedImage - no roundtrip through
        // raw base64, no Interface entity bloat, single source of truth
        // (the storage.storage row + S3 object).
        return ids;
    }

    private static Integer extractHttpStatus(Map<String, Object> output) {
        if (output == null) return null;
        Object s = output.get("http_status");
        if (s instanceof Number n) return n.intValue();
        if (output.get("metadata") instanceof Map<?, ?> meta) {
            Object ms = ((Map<?, ?>) meta).get("status");
            if (ms instanceof Number mn) return mn.intValue();
        }
        return null;
    }

    /**
     * Extract the inline image data from the Gemini response. Catalog
     * flattens {@code candidates} directly into {@code output}; we walk
     * to {@code candidates[0].content.parts[].inlineData.{mimeType, data}}.
     *
     * <p>Empty {@code candidates} = upstream content moderation rejected
     * the entire response → surface as {@code CONTENT_BLOCKED}. Otherwise
     * a missing {@code inlineData} part means the response shape changed
     * upstream and the catalog projection didn't catch it →
     * {@code UPSTREAM_FAILED}.
     */
    @SuppressWarnings("unchecked")
    private GeneratedImage parseResponse(Map<String, Object> output) {
        if (output == null) {
            throw new ImageGenerationException(
                    ImageGenerationException.Kind.UPSTREAM_FAILED, "Empty output from Gemini catalog call");
        }
        Object candidatesObj = output.get("candidates");
        // Catalog may also wrap under "result" depending on projection.
        if (!(candidatesObj instanceof List<?>)) {
            Object resultObj = output.get("result");
            if (resultObj instanceof Map<?, ?> rm) candidatesObj = ((Map<?, ?>) rm).get("candidates");
        }
        if (!(candidatesObj instanceof List<?> candidates) || candidates.isEmpty()) {
            throw new ImageGenerationException(
                    ImageGenerationException.Kind.CONTENT_BLOCKED,
                    "Gemini returned no candidates (likely content moderation)");
        }
        Object content = ((Map<String, Object>) candidates.get(0)).get("content");
        if (!(content instanceof Map<?, ?> contentMap)) {
            throw new ImageGenerationException(
                    ImageGenerationException.Kind.UPSTREAM_FAILED, "Gemini response missing 'content'");
        }
        Object partsObj = ((Map<String, Object>) contentMap).get("parts");
        if (!(partsObj instanceof List<?> parts)) {
            throw new ImageGenerationException(
                    ImageGenerationException.Kind.UPSTREAM_FAILED, "Gemini response missing 'parts'");
        }
        for (Object partObj : parts) {
            if (!(partObj instanceof Map<?, ?> part)) continue;
            Object inline = ((Map<String, Object>) part).get("inlineData");
            // Some projections snake_case it
            if (inline == null) inline = ((Map<String, Object>) part).get("inline_data");
            if (!(inline instanceof Map<?, ?> inlineMap)) continue;
            Object data = ((Map<String, Object>) inlineMap).get("data");
            Object mime = ((Map<String, Object>) inlineMap).get("mimeType");
            if (mime == null) mime = ((Map<String, Object>) inlineMap).get("mime_type");
            if (data == null) continue;

            // v3.0 path: catalog dehydrator replaced the b64 string with a
            // FileRef Map ({_type:"file", path, name, mimeType, size, url, …}).
            // Surface the Map directly - no String.valueOf(map) which used
            // to produce literal "{_type=file, path=…}" toString output that
            // ended up in chat-card data URIs (broken).
            if (data instanceof Map<?, ?> fileRefMap && "file".equals(fileRefMap.get("_type"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> fileRef = (Map<String, Object>) fileRefMap;
                return GeneratedImage.ofFileRef(fileRef, null);
            }

            // Legacy / opt-out path: data is a raw base64 string.
            return GeneratedImage.ofBase64(
                    String.valueOf(data),
                    mime != null ? String.valueOf(mime) : "image/png",
                    null);
        }
        throw new ImageGenerationException(
                ImageGenerationException.Kind.UPSTREAM_FAILED,
                "Gemini response had candidates but no inlineData image part");
    }
}
