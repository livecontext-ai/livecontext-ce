package com.apimarketplace.orchestrator.tools.imagegeneration;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;

import java.util.List;

/**
 * Strategy interface for image-generation backends. One implementation per
 * provider (OpenAI, Google). The dispatcher
 * ({@link ImageGenerationModule}) selects the right one by inspecting
 * {@link Request#provider()}.
 *
 * <p>The contract is intentionally narrow: take a typed request, return a
 * typed response (or throw {@link ImageGenerationException} on a failure
 * the module should surface to the agent). Billing is centralised
 * downstream in {@code CatalogBillingDispatcher} +
 * {@code ImageGenerationBillingStrategy} - providers do NOT call any
 * {@code consumeFor*} method themselves; they just hand the
 * {@link ToolExecutionContext} (with its credentials map) to the
 * gateway, which threads {@code billingIdentifiers} through to the
 * dispatcher.
 */
public interface ImageProvider {

    /** Provider identifier this strategy handles ({@code "openai"} or {@code "google"}). */
    String providerKey();

    /**
     * Synchronously call the upstream API and return the generated images.
     * The {@code context} is passed so the provider can extract billing
     * identifiers ({@code __streamId__, __toolCallId__, __workflowRunId__,
     * __callIndex__}) from {@code context.credentials()} and forward them
     * to the catalog gateway, which builds the {@code BillingContext}
     * for the centralised dispatcher.
     *
     * @throws ImageGenerationException with a typed cause on auth failure,
     *         content-mod rejection, transport error, or invalid request.
     *         The module maps the cause to the right
     *         {@link com.apimarketplace.agent.tools.ToolErrorCode}.
     */
    Response generate(Request request, ToolExecutionContext context) throws ImageGenerationException;

    /**
     * Image-generation request. The provider does NOT resolve credentials
     * - the underlying call goes through the platform API catalog
     * ({@code CatalogToolsGateway}), which looks up the upstream API key
     * from {@code auth.platform_credentials.integration_name='llm_<provider>'}
     * (or the per-API {@code platform_credential_name}) using {@code tenantId}.
     *
     * @param provider provider key ({@code "openai"} / {@code "google"})
     * @param model    real upstream model ({@code "gpt-image-1.5"},
     *                 {@code "gemini-2.5-flash-image"}, …) - NOT the
     *                 pseudo-model billing key
     * @param prompt   text prompt
     * @param n        requested image count (provider may return fewer)
     * @param quality  {@code "low"} / {@code "medium"} / {@code "high"} -
     *                 ignored by Google (single tier)
     * @param size     pixel size, currently only {@code "1024x1024"} supported
     * @param tenantId user id for catalog credential resolution (X-User-Id header)
     */
    record Request(
            String provider,
            String model,
            String prompt,
            int n,
            String quality,
            String size,
            String tenantId
    ) {}

    /**
     * Generated images. {@code images.size()} is the {@code actualImageCount}
     * passed to {@code consumeForImageGeneration} - fewer than {@link Request#n()}
     * if per-image content moderation rejected some.
     */
    record Response(List<GeneratedImage> images) {}

    /**
     * One generated image.
     *
     * <p>v3.0 reconciliation: the canonical shape is a {@code FileRef Map}
     * (from the catalog dehydrator's S3 upload) - the agent receives a
     * lightweight reference, the chat side panel + Files tab read the
     * same row, and there is no inline base64 in any persistence layer.
     *
     * <ul>
     *   <li>{@code fileRef} - the {@code {_type:"file", path, name,
     *       mimeType, size, url, source_path}} Map produced by
     *       {@code BinaryResponseHandler.dehydrateInlineBase64}. Always
     *       set when the catalog is on the call path.</li>
     *   <li>{@code base64} - legacy fallback, only populated when the
     *       catalog dehydrator did NOT run (e.g. provider explicitly
     *       opted out via {@code __inlineBinaries__}). v3.0 callers
     *       should NOT rely on this - it remains so existing tests and
     *       the rare opt-out path keep working.</li>
     *   <li>{@code mimeType} - duplicate of {@code fileRef.mimeType}
     *       when present, otherwise the wire MIME from the upstream
     *       response.</li>
     *   <li>{@code revisedPrompt} - OpenAI-only prompt rewrite; null
     *       for Google.</li>
     * </ul>
     */
    record GeneratedImage(java.util.Map<String, Object> fileRef,
                          String base64,
                          String mimeType,
                          String revisedPrompt) {
        /** Convenience: build a FileRef-backed image (the v3.0 path). */
        public static GeneratedImage ofFileRef(java.util.Map<String, Object> fileRef, String revisedPrompt) {
            String mime = fileRef != null ? (String) fileRef.get("mimeType") : "image/png";
            return new GeneratedImage(fileRef, /* base64 */ null, mime, revisedPrompt);
        }

        /** Convenience: build an inline-base64 image (legacy / opt-out path). */
        public static GeneratedImage ofBase64(String base64, String mimeType, String revisedPrompt) {
            return new GeneratedImage(/* fileRef */ null, base64, mimeType, revisedPrompt);
        }
    }
}
